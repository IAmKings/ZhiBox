package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.core.backup.UI_HINT_KEY_PREFIX
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.extraction.SagExtractionScheduler
import com.zhibox.memory.core.intelligence.IntelligenceBusySource
import com.zhibox.memory.core.intelligence.IntelligenceBusyTick
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.ingest.ReembedScheduler
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.model.SharedModelStore
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.DocumentPreviewRow
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 首页（时间线）状态（UI 改版）。
 *
 * 数据源：Room 的三条只读 Flow（文档 / 活跃来源 / 每文档首块摘要）在 ViewModel 内
 * `combine` 一次，按 createdAt 倒序取最近 [MAX_ITEMS] 条，映射为 [HomeCardItem] 后
 * 按「今天 / 昨天 / 更早」分组。分类过滤在内存中完成（列表规模有限，不额外查库）。
 *
 * 与既有 [SourcesViewModel] 一致的取舍：
 * - 用 `AndroidViewModel` + [AppDatabase] 单例，仓库层不引入新的 Repository 抽象；
 * - `stateIn(WhileSubscribed(5s))` 避免离开首页后长期持有游标；
 * - 异常不抛给 UI，收敛成 [HomeUiState.Error] 的可读文案。
 *
 * 刻意不做：点击卡片进入「文档详情」——当前没有该目的地（证据页需要 chunk id），
 * 先跳到来源页；等文档详情屏落地后再改回调。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)

    /**
     * 下载/重嵌入进度的进程级单一来源（设置页发起下载，首页状态点也能看到快脉冲）。
     * 懒初始化：只在真正需要时构造 WorkManager 观察。
     */
    private val busySource: IntelligenceBusySource by lazy {
        IntelligenceBusySource.get(application)
    }

    /** 最近一次读数快照：busy 变化时用它重算状态，避免重复 IO 扫描。 */
    @Volatile
    private var lastReadings: IntelligenceReadings? = null

    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())

    /**
     * 重试闸门：Room Flow 一旦抛出（库损坏/迁移失败）就会终止，`catch` 之后无法自愈，
     * 因此用 flatMapLatest 包一层——[retry] 时整体重新订阅，错误态才真正可恢复。
     */
    private val retryTrigger = MutableStateFlow(0)

    // ── 新内容胶囊：记录"用户已经看过"的条目 id ──────────────────
    /** 已看过的文档 id（进程内）；首次加载整批视为已看过，避免冷启动弹胶囊。 */
    private val seenIds = mutableSetOf<String>()

    /** 最近一次计算出的全量条目 id（点胶囊后整批标记为已读）。 */
    private var lastAllIds: Set<String> = emptySet()
    private var baselineReady = false

    /** 触发重算（点胶囊后把新内容标记为已读）。 */
    private val seenRevision = MutableStateFlow(0)

    val uiState: StateFlow<HomeUiState> = retryTrigger
        .flatMapLatest {
            combine(
                db.documentDao().observeAll(),
                db.sourceDao().observeAll(SourceStatus.ACTIVE.value),
                db.chunkDao().observeFirstChunks(),
                filter,
                seenRevision,
            ) { documents, sources, previews, active, _ ->
                buildState(documents, sources, previews, active)
            }
                .catch { throwable ->
                    // 数据库异常（损坏/升级失败等）不该让首页白屏：转成可重试错误态
                    emit(HomeUiState.Error(throwable.readableMessage()))
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState.Loading,
        )

    // ─────────────────── 智能状态（M1：绿点说真话） ───────────────────

    private val _intelligence = MutableStateFlow(IntelligenceStatus.Loading)

    init {
        // Busy 只影响动效/描述，不改变底色：拿到新信号后用最近一次读数重算即可。
        // 共享 Flow 用 WhileSubscribed(5s)，首页离开后不持有订阅。
        viewModelScope.launch {
            busySource.state.collect { tick ->
                val readings = lastReadings ?: return@collect
                _intelligence.value = statusOf(readings, tick)
            }
        }
    }

    /**
     * 读数 + 进行中任务 → 聚合状态（busy 的三个通道一起映射，避免各调用点漏字段）：
     * - `busyLabel`：下载中的模型名（E12 行标题）；
     * - `busyDone/busyTotal`：重嵌入进度（E13 进度文案；total 未知为 null）。
     */
    private fun statusOf(readings: IntelligenceReadings, tick: IntelligenceBusyTick): IntelligenceStatus =
        evaluateIntelligence(
            readings = readings,
            busyOverride = tick.toBusyOverride(),
            busyPct = tick.toBusyPct(),
            busyLabel = tick.toBusyLabel(),
            busyDone = tick.toBusyDone(),
            busyTotal = tick.toBusyTotal(),
        )

    private fun IntelligenceBusyTick.toBusyOverride(): IntelligenceBusy? = when (this) {
        is IntelligenceBusyTick.Downloading -> IntelligenceBusy.DOWNLOADING
        is IntelligenceBusyTick.Reembedding -> IntelligenceBusy.SYNCING
        IntelligenceBusyTick.Idle -> null
    }

    private fun IntelligenceBusyTick.toBusyPct(): Int? = when (this) {
        is IntelligenceBusyTick.Downloading -> pct
        is IntelligenceBusyTick.Reembedding -> if (total > 0) done * 100 / total else null
        IntelligenceBusyTick.Idle -> null
    }

    private fun IntelligenceBusyTick.toBusyLabel(): String? = when (this) {
        is IntelligenceBusyTick.Downloading -> modelLabel
        else -> null
    }

    private fun IntelligenceBusyTick.toBusyDone(): Int? = when (this) {
        is IntelligenceBusyTick.Reembedding -> done
        else -> null
    }

    private fun IntelligenceBusyTick.toBusyTotal(): Int? = when (this) {
        // total <= 0 = 总量未知：映射成 null，面板用不确定进度条（§10.3）。
        is IntelligenceBusyTick.Reembedding -> total.takeIf { it > 0 }
        else -> null
    }

    /** 首页左上角状态点消费：四态 + 待处理项数 + 进行中任务（只影响动效）。 */
    val intelligence: StateFlow<IntelligenceStatus> = _intelligence.asStateFlow()

    private val _showOnboarding = MutableStateFlow(false)

    /** 首次引导条：仅"未就绪 + 用户未处理过"时展示（一次性）。 */
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    /**
     * 读取一次智能状态快照。
     *
     * 由首页进入组合时调用（从设置页返回会重新进入组合 → 颜色立即刷新）。
     * 所有读取都在 IO 线程；单项失败按 `Unknown` 降级，不阻塞其他状态。
     */
    fun refreshIntelligence() {
        viewModelScope.launch {
            val readings = withContext(Dispatchers.IO) { readIntelligenceReadings() }
            lastReadings = readings
            val status = statusOf(readings, busySource.state.value)
            _intelligence.value = status
            val seen = withContext(Dispatchers.IO) { onboardingSeen() }
            _showOnboarding.value = status.needsOnboarding && !seen
        }
    }

    // ─────────────────── 面板内就地动作（§6.4 幂等写操作） ───────────────────

    /** 就地动作防重入（简单 busy 标记）：连点只触发一次，避免重复入队/重复修复。 */
    @Volatile
    private var retryingExtraction = false

    @Volatile
    private var repairingIndex = false

    /**
     * E10 待处理行「重试」：把终态 failed 的抽取任务重新排队。
     *
     * 与设置页 `SettingsViewModel.retryFailedExtraction` 走同一 core 入口
     * [SagExtractionScheduler.retryFailed]；面板内就地执行，不跳设置页、不关闭面板。
     * SAG 不可用时 core 直接返回 0（任务保持 failed，待处理行不消失），不抛给 UI。
     */
    fun retryExtractionFailed() {
        if (retryingExtraction) return
        retryingExtraction = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) { SagExtractionScheduler.retryFailed(app, db) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 就地动作失败不崩溃：保留失败计数，用户可再次点击重试。
            } finally {
                retryingExtraction = false
            }
            // 入队成功后任务转为 queued/running，刷新一次让 busy/待处理行即时更新。
            refreshIntelligence()
        }
    }

    /**
     * E11 待处理行「修复」：就地执行索引修复（清理孤儿 FTS/向量行，必要时重建 FTS）。
     *
     * 复用 [IngestRepository.repairIndex]（与设置页「检查并修复索引」同一实现），
     * 面板不关闭；完成后重新读数，待处理行随索引恢复自动消失。
     */
    fun repairIndexNow() {
        if (repairingIndex) return
        repairingIndex = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) { IngestRepository(app).repairIndex() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 修复失败不崩溃：读数保持"不一致"，用户可再次点击。
            } finally {
                repairingIndex = false
            }
            refreshIntelligence()
        }
    }

    /**
     * E13「停止同步」：就地取消唯一重嵌入工作流。
     *
     * 取消是幂等的（无任务时无副作用）；busy 由 [IntelligenceBusySource] 观察 WorkManager，
     * 取消后信号自动回到 Idle，面板进度行随状态刷新消失。
     */
    fun cancelReembedSync() {
        runCatching { ReembedScheduler.cancel(getApplication()) }
    }

    /** 用户点过引导条 CTA 或关闭：持久化标记，不再打扰（模型缺失时状态点仍保持灰色提示）。 */
    fun onOnboardingSeen() {
        _showOnboarding.value = false
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.appKvDao().put(
                    AppKvEntity(
                        key = KEY_INTELLIGENCE_ONBOARDED,
                        value = "1",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun readIntelligenceReadings(): IntelligenceReadings {
        val app = getApplication<Application>()
        // 模型：文件扫描（IO）
        val modelFile = runCatching { EmbeddingRuntime.findModel(app) }.getOrNull()
        val modelBroken = modelFile != null &&
            (!modelFile.exists() || modelFile.length() == 0L)
        // 向量空间（DB）
        val spaceCount = runCatching { db.embeddingDao().allMeta().size }.getOrDefault(-1)
        // GPU 档位偏好（只能表达偏好，真实可用性事前不可知）
        val gpuMode = runCatching { PathPreferences.gpuMode(app) }.getOrNull()
        // 共享目录授权
        val storeState = runCatching { SharedModelStore.state(app) }.getOrNull()
        // LLM 配置：load() 抛异常即"配置存在但读取失败"（Keystore/损坏）
        var llmConfigured = false
        var llmLoadError = false
        runCatching { KeystoreLlmConfigStore(app).load() }
            .onSuccess { llmConfigured = it != null }
            .onFailure { llmLoadError = true }
        // 旧模型空间（B7 检测；失败按未知 -1，不误报待处理）
        val legacyCount = runCatching {
            IngestRepository(app).legacySpacesReport().size
        }.getOrDefault(-1)
        // SAG 抽取计数：失败按未知，不误报
        val counts = runCatching { db.extractionJobDao().readCounts() }.getOrNull()
        // E1/E2：候选模型文件数（纯文件扫描；读取失败 -1——既不判 E1 也不判 E2，
        // 避免把"读不到文件数"伪装成"全新安装"）。
        val modelFileCount = runCatching { PathPreferences.candidates(app).size }.getOrDefault(-1)
        // E3/E11：chunk 总数与 FTS 行数（各一次 COUNT(*)，不做 indexHealthCheck 全表扫描）
        val chunkCount = runCatching { db.chunkDao().count() }.getOrDefault(-1)
        val ftsCount = runCatching { db.chunkDao().ftsCount() }.getOrDefault(-1)
        // E8/E14：L3 可选能力开关（读取失败按未知 null，pill 不展示，§10.3）
        val sagEnabled = runCatching { LlmPreferences.sagEnabled(app) }.getOrNull()
        val networkDisabled = runCatching { !LlmPreferences.networkAllowed(app) }.getOrNull()
        val answerConsentGranted = runCatching {
            LlmPreferences.answerNetworkConsent(app)
        }.getOrNull()

        return IntelligenceReadings(
            modelName = modelFile?.name,
            modelFileBroken = modelBroken,
            spaceCount = spaceCount,
            gpuMode = gpuMode,
            llmConfigured = llmConfigured,
            llmLoadError = llmLoadError,
            legacySpaceCount = legacyCount,
            extractionFailedCount = counts?.failed ?: -1,
            extracting = (counts?.running ?: 0) > 0,
            storePermissionLost = storeState == SharedModelStore.State.PERMISSION_LOST,
            // E11：轻量索引一致性探测——chunk 行数与 FTS 行数不一致即视为不一致。
            // 完整检查（孤儿集合）仍只在设置页「检查并修复索引」里执行；
            // 任一计数读取失败（-1）按"未知"处理，不误报待处理。
            indexUnhealthy = chunkCount >= 0 && ftsCount >= 0 && chunkCount != ftsCount,
            storeNotConfigured = storeState == SharedModelStore.State.NOT_CONFIGURED,
            modelFileCount = modelFileCount,
            chunkCount = chunkCount,
            sagEnabled = sagEnabled,
            networkDisabled = networkDisabled,
            answerConsentGranted = answerConsentGranted,
        )
    }

    private suspend fun onboardingSeen(): Boolean =
        runCatching { db.appKvDao().get(KEY_INTELLIGENCE_ONBOARDED) != null }.getOrDefault(false)

    /** 切换分类药丸。 */
    fun selectFilter(value: HomeFilter) {
        filter.value = value
    }

    /** 顶部胶囊点击后：把当前所有条目标记为已看过（胶囊消失，不改变滚动位置）。 */
    fun markNewContentSeen() {
        seenIds.addAll(lastAllIds)
        seenRevision.value += 1
    }

    /** 错误态重试：重新订阅数据流。 */
    fun retry() {
        retryTrigger.value += 1
    }

    private fun buildState(
        documents: List<DocumentEntity>,
        sources: List<SourceEntity>,
        previews: List<DocumentPreviewRow>,
        active: HomeFilter,
    ): HomeUiState {
        val sourceById = sources.associateBy { it.id }
        val previewById = previews.associateBy { it.documentId }
        val now = System.currentTimeMillis()

        val recent = documents
            .asSequence()
            .sortedByDescending { it.createdAt }
            .take(MAX_ITEMS)
            .toList()

        val cards = recent.map { document ->
            document.toCard(
                source = sourceById[document.sourceId],
                preview = previewById[document.id]?.content.orEmpty(),
            )
        }

        val target = active.toCategory()
        val filtered = if (target == null) cards else cards.filter { it.category == target }

        // 新内容：首次加载整批视为"已看过"；之后按 unseen 计数
        if (!baselineReady) {
            seenIds.addAll(cards.map { it.id })
            baselineReady = true
        }
        lastAllIds = cards.map { it.id }.toSet()
        val newContent = countNewContent(cards, seenIds.toSet(), active)

        return HomeUiState.Loaded(
            filter = active,
            groups = filtered.groupByDay(now),
            totalCount = documents.size,
            shownCount = filtered.size,
            newInFilterCount = newContent.inFilter,
            newElsewhereCategory = newContent.elsewhere,
            newElsewhereCount = newContent.elsewhereCount,
        )
    }

    private fun DocumentEntity.toCard(source: SourceEntity?, preview: String) = HomeCardItem(
        id = id,
        category = categoryOf(parserId, mimeType = source?.mimeType, originUrl = source?.originUrl),
        createdAt = createdAt,
        timeLabel = timeFormat.format(Date(createdAt)),
        title = title.ifBlank { getApplication<Application>().getString(R.string.home_untitled) },
        preview = preview.toSnippet(),
        sourceLabel = source?.title?.takeIf { it.isNotBlank() }
            ?: getApplication<Application>().getString(R.string.home_source_unknown),
        status = DocStatus.fromValue(status)?.toHomeStatus(),
    )

    private fun List<HomeCardItem>.groupByDay(now: Long): List<HomeTimelineGroup> {
        val buckets = linkedMapOf<HomeGroup, MutableList<HomeCardItem>>()
        for (card in this) {
            buckets.getOrPut(card.createdAt.bucketOf(now)) { mutableListOf() }.add(card)
        }
        // 固定「今天 → 昨天 → 更早」顺序，空分组不渲染
        return HomeGroup.entries.mapNotNull { group ->
            buckets[group]?.let { HomeTimelineGroup(label = group, items = it) }
        }
    }

    private companion object {
        /**
         * 一次性引导标记（app_kv）：用户处理/关闭后不再打扰。
         * 加 `ui_hint_` 前缀 → 不随知识库备份迁移（见 core/backup/BackupKeyPolicy.kt）。
         */
        const val KEY_INTELLIGENCE_ONBOARDED = UI_HINT_KEY_PREFIX + "intelligence_onboarded"

        /** 首页只展示最近 N 条（超出走检索/来源页），避免 LazyColumn 首帧开销。 */
        const val MAX_ITEMS = 40

        /** 卡片摘要上限：两行 13sp 约 40 个汉字，取 120 字符留余量。 */
        const val SNIPPET_LIMIT = 120
    }
}

// ── 纯函数映射（可单测，不依赖 Android） ────────────────────────────

/** 分类判定：优先看解析器，其次看来源 MIME / 网页 provenance。 */
internal fun categoryOf(
    parserId: String,
    mimeType: String?,
    originUrl: String?,
): HomeCategory {
    val parser = parserId.lowercase(Locale.ROOT)
    val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") || parser.startsWith("image") -> HomeCategory.IMAGE
        originUrl != null || parser.startsWith("html") || mime == "text/html" ->
            HomeCategory.SNAPSHOT
        // pdf-*、docx/pptx/xlsx-ooxml-v1 等结构化文档
        parser.startsWith("pdf") || parser.endsWith("-ooxml-v1") -> HomeCategory.DOCUMENT
        // md-utf8-v1 / txt-utf8-v1 / pending（尚未回写解析器）→ 文本笔记
        else -> HomeCategory.NOTE
    }
}

/** 过滤分类 → 内容分类（[HomeFilter.ALL] 不过滤，返回 null）。 */
internal fun HomeFilter.toCategory(): HomeCategory? = when (this) {
    HomeFilter.ALL -> null
    HomeFilter.NOTE -> HomeCategory.NOTE
    HomeFilter.IMAGE -> HomeCategory.IMAGE
    HomeFilter.DOCUMENT -> HomeCategory.DOCUMENT
}

/** 文档状态 → 首页提示状态（ready 不显示）。 */
internal fun DocStatus.toHomeStatus(): HomeStatus? = when (this) {
    DocStatus.READY -> null
    DocStatus.PENDING, DocStatus.PROCESSING -> HomeStatus.PROCESSING
    DocStatus.FAILED -> HomeStatus.FAILED
    DocStatus.CANCELLED -> HomeStatus.CANCELLED
}

/** 区块文本压成单行摘要（换行/多空格折叠，超长截断）。 */
internal fun String.toSnippet(limit: Int = 120): String {
    val collapsed = trim()
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit).trimEnd() + "…"
}

/** 创建时间落在哪个日期分组（按本地时区自然日边界，处理夏令时）。 */
internal fun Long.bucketOf(now: Long): HomeGroup {
    val todayStart = now.startOfDay()
    val yesterdayStart = (Calendar.getInstance().apply {
        timeInMillis = todayStart
        add(Calendar.DAY_OF_YEAR, -1)
    }).timeInMillis
    return when {
        this >= todayStart -> HomeGroup.TODAY
        this >= yesterdayStart -> HomeGroup.YESTERDAY
        else -> HomeGroup.EARLIER
    }
}

private fun Long.startOfDay(): Long = Calendar.getInstance().apply {
    timeInMillis = this@startOfDay
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
