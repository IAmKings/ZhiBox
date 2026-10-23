package com.zhibox.memory.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhibox.memory.R
import com.zhibox.memory.core.backup.KnowledgeBackup
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmStoreException
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.extraction.ExtractionQualityAnalyzer
import com.zhibox.memory.core.extraction.ExtractionQualityReport
import com.zhibox.memory.core.extraction.SagAvailability
import com.zhibox.memory.core.extraction.SagExtractionPreferences
import com.zhibox.memory.core.extraction.SagExtractionScheduler
import com.zhibox.memory.core.llm.LlmTestResult
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.ingest.ReembedScheduler
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.model.SharedModelStore
import com.zhibox.memory.core.sag.SagBudgetPreferences
import com.zhibox.memory.core.sag.SagBudgetTier
import com.zhibox.memory.core.text.PdfOcrPreferences
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模型文件条目：文件大小在 ViewModel 的 IO 阶段算好，composition 内不再做 `File.length()` 磁盘 IO。 */
data class ModelFileItem(val file: File, val label: String, val sizeBytes: Long)

/**
 * B8：设置页 LLM 配置视图模型（**只含掩码 key**，明文 apiKey 永不进入 UiState/日志/错误文案）。
 */
data class LlmConfigUi(
    val baseUrl: String,
    val model: String,
    val timeoutSeconds: Int,
    /** 形如 "sk-***abcd"；null = 未保存密钥 */
    val maskedKey: String?,
)

/** 向量空间条目：维度取自 `embedding_meta.dimensions`（不再解析 spaceId 推断，P1-6）。 */
data class SpaceInfo(val id: String, val count: Int, val dimensions: Int)

/**
 * B7：旧模型空间（非 EGemma2）的 UI 模型。
 * `IngestRepository.legacySpacesReport()` 返回 core 的 LegacySpaceInfo，这里只取展示所需字段，
 * 避免 UI 直接耦合 core 数据结构 FQN（`id/providerId` 不展示）。
 */
data class LegacySpaceUi(val modelId: String, val vectorCount: Int)

/**
 * 设置屏 UiState（P1-8）：所有长任务（刷新/模型下载/备份导入导出/索引重建/清空共享目录）
 * 在 `viewModelScope + Dispatchers.IO` 中执行，屏幕离开组合不取消；异常统一转为可读提示。
 */
@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val model: String? = null,
    val preference: String = PathPreferences.AUTO,
    val gpuPref: String = PathPreferences.GPU_AUTO,
    val perfPref: String = ModelCatalog.PERF_BALANCED,
    val socTag: String = "",
    val models: List<ModelFileItem> = emptyList(),
    val spaces: List<SpaceInfo> = emptyList(),
    val chunkCount: Int = 0,
    val docReady: Int = 0,
    val storeState: SharedModelStore.State = SharedModelStore.State.NOT_CONFIGURED,
    val sharedNames: List<String> = emptyList(),
    val downloadEntry: ModelCatalog.Entry? = null,
    val downloadPct: Int = -1,
    val downloading: Boolean = false,
    /** 模型商店操作结果（成功/信息） */
    val message: String? = null,
    /** 全局错误（目录授权/下载/删除/清空等），可读单行 */
    val error: String? = null,
    /** 备份迁移状态/错误 */
    val migrationMessage: String? = null,
    val hasAutoBackup: Boolean = false,
    val reembedDone: Int = 0,
    val reembedTotal: Int = -1,
    val lastReembed: String? = null,
    /** B7：检测到的旧模型空间（非 EGemma2）；非空时设置页显示“需重新导入”警告，不自动删数据 */
    val legacySpaces: List<LegacySpaceUi> = emptyList(),
    /** B7 审查修复：旧空间报表失败时的可读错误（不静默吞掉；不阻塞整体刷新） */
    val legacySpacesError: String? = null,
    /** B3-14：索引三表健康报告（chunk/FTS/向量计数 + 孤儿行），null = 尚未检查 */
    val indexHealth: IngestRepository.IndexHealth? = null,
    /** B3-14：索引修复进行中（按钮 loading/finally 复位） */
    val indexRepairing: Boolean = false,
    val diagMessage: String? = null,
    val importPreview: KnowledgeBackup.ImportPreview? = null,
    val importUri: Uri? = null,
    val importFile: File? = null,
    // ---- B8：SAG 增强 / LLM（未配置时离线检索不受影响；默认禁网） ----
    /** LLM 配置（仅掩码 key）；null = 未配置 */
    val llm: LlmConfigUi? = null,
    /** 连接测试进行中（finally 复位） */
    val llmTesting: Boolean = false,
    /** 连接测试结果/保存/清除结果（可读；不含明文 key） */
    val llmTestMessage: String? = null,
    /** LLM 配置读取/存储错误（如 Keystore 解密失败；可读且不含 key） */
    val llmError: String? = null,
    /** SAG 增强（事件/实体抽取）开关 */
    val sagEnabled: Boolean = false,
    /** 问答生成开关 */
    val answerEnabled: Boolean = false,
    /** 禁止网络总开关（true = 拒绝所有 LLM 调用；默认 true） */
    val networkDisabled: Boolean = true,
    /** B11：问答首次联网隐私说明是否已同意（未同意前 Chat 不发起生成请求） */
    val answerNetworkConsent: Boolean = false,
    /** B12：检索预算档位（PRD §7.2，默认 HIGH；下一轮检索/问答生效） */
    val budgetTier: SagBudgetTier = SagBudgetTier.HIGH,
    // ---- B12：SAG 抽取并发度（1..4，默认 1=串行；Worker 读取） ----
    val extractionParallelism: Int = SagExtractionPreferences.DEFAULT_PARALLELISM,
    val extractionParallelismSaving: Boolean = false,
    /** P1.1：扫描 PDF 端侧 OCR 开关（默认 true；仅文本层为空时对前 20 页 OCR） */
    val pdfOcrEnabled: Boolean = PdfOcrPreferences.DEFAULT_PDF_OCR_ENABLED,
    val pdfOcrSaving: Boolean = false,
    /** B12：档位持久化进行中（UI 禁用，避免连点/乱序写） */
    val budgetSaving: Boolean = false,
    // ---- B9：SAG 两阶段抽取状态 ----
    /** SAG 抽取可用性（LLM 配置 + SAG 开关 + 未禁网）；false 时补抽取/重试按钮禁用 */
    val sagAvailable: Boolean = false,
    /** 全局抽取任务计数（queued/running/done/failed/not_indexed） */
    val extractionCounts: ExtractionCountsUi = ExtractionCountsUi(),
    /** 补抽取/重试失败动作进行中（finally 复位） */
    val extractionWorking: Boolean = false,
    /** 抽取动作结果/错误（可读单行） */
    val extractionMessage: String? = null,
    // ---- B12：检索/问答 trace 诊断（脱敏导出） ----
    /** query_trace 总条数（null=尚未读取） */
    val traceTotalCount: Int? = null,
    /** 最近 50 条 trace 按 mode 计数（qa/vector/multi_es_fast/multi…） */
    val traceRecentModes: Map<String, Int> = emptyMap(),
    val traceExporting: Boolean = false,
    /** trace 导出结果/错误（可读；不含正文） */
    val traceExportMessage: String? = null,
    // ---- B15.2：抽取质量只读诊断 ----
    val qualityReport: ExtractionQualityReport? = null,
    val qualityLoading: Boolean = false,
    val qualityExporting: Boolean = false,
    /** 质量报告读取/导出结果（可读；不含正文） */
    val qualityMessage: String? = null,
)

/**
 * 设置屏 ViewModel：
 * - 模型列表/路径偏好/空间注册表/共享目录状态：一次 IO 快照后写 StateFlow；
 * - 下载：`SharedModelStore.downloadEntry` 在 viewModelScope 中跑，离开页面不取消（P1-7），
 *   进度百分比写入 UiState；
 * - SAF（P1-10）：`setTreeUri` 包异常边界，SecurityException 等失败给可读提示不崩溃；
 * - 备份导入失败展示可读错误（P1-10），导入预览/确认在 UiState 中流转。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val db = AppDatabase.get(application)
    private val repository = IngestRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
        observeReembedProgress()
    }

    /**
     * R2 复审修复（P2-8）：屏幕进入时刷新快照。手动导航下 ViewModel 常驻 Activity，
     * 仅 init 刷新会在"导入/删源/切换模型后再进设置页"时展示陈旧计数
     * （Chat/Graph/Sources/Import 均已做同款进场刷新）。
     *
     * P0 补充：5s 节流。
     *
     * 原实现每次进入设置页都全量刷新一次；快速来回切换时会连续触发多轮快照 +
     * 多次整屏状态发射。5 秒内重复进入直接跳过；数据变更后仍会在下一次进入时刷新
     * （导入/删源等操作通常伴随页面切换，5s 窗口足够短，不会长期展示陈旧计数）。
     */
    /** 本次会话是否已加载过 LLM 配置（Keystore 解密一次性完成，后续由保存/清除动作更新）。 */
    @Volatile
    private var llmLoaded = false

    /**
     * 按需加载 LLM 配置（P1）：设置页滚到「SAG 增强 / LLM」段时调用。
     * 避免每次进入设置都做 Keystore 解密。
     */
    fun ensureLlmLoaded() {
        if (llmLoaded) return
        llmLoaded = true
        safeLaunch(
            onError = { t ->
                update { it.copy(llmError = str(R.string.settings_llm_load_failed, t.readableMessage())) }
            },
        ) {
            val snap = withContext(Dispatchers.IO) { readLlmSnapshot() }
            update { it.copy(llm = snap.config, llmError = snap.error) }
        }
    }

    fun onScreenStarted() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoRefreshAtMs < AUTO_REFRESH_MIN_INTERVAL_MS) return
        lastAutoRefreshAtMs = now
        refresh()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun update(block: (SettingsUiState) -> SettingsUiState) {
        _state.update(block)
    }

    private data class Snapshot(
        val model: String?,
        val preference: String,
        val gpuPref: String,
        val perfPref: String,
        val socTag: String,
        val models: List<ModelFileItem>,
        val spaces: List<SpaceInfo>,
        val chunkCount: Int,
        val docReady: Int,
        val storeState: SharedModelStore.State,
        val sharedNames: List<String>,
        val lastReembed: String?,
        val indexHealth: IngestRepository.IndexHealth?,
        val legacySpaces: List<LegacySpaceUi>,
        val legacySpacesError: String?,
        val hasAutoBackup: Boolean,
        // B8
        val llm: LlmConfigUi?,
        val llmError: String?,
        val sagEnabled: Boolean,
        val answerEnabled: Boolean,
        val networkDisabled: Boolean,
        val answerNetworkConsent: Boolean,
        val budgetTier: SagBudgetTier,
        val extractionParallelism: Int,
        val pdfOcrEnabled: Boolean,
        // B9
        val sagAvailable: Boolean,
        val extractionCounts: ExtractionCountsUi,
        // B12：trace 诊断
        val traceTotalCount: Int,
        val traceRecentModes: Map<String, Int>,
    )

    /** R2（P2-13）：刷新代际——并发 refresh（进场刷新/操作收尾）时旧快照后到不得覆盖新状态。 */
    private var refreshSeq: Int = 0

    /** 进场自动刷新的节流时间戳（仅自动刷新用；手动 refresh 不受限）。 */
    private var lastAutoRefreshAtMs = 0L

    fun refresh() {
        val seq = ++refreshSeq
        safeLaunch(
            onError = { t ->
                if (seq == refreshSeq) {
                    update {
                        it.copy(
                            loading = false,
                            error = str(R.string.settings_refresh_failed, t.readableMessage()),
                        )
                    }
                }
            },
        ) {
            update { it.copy(loading = true) }
            try {
                val s = withContext(Dispatchers.IO) { loadSnapshot() }
                if (seq != refreshSeq) return@safeLaunch // 过期快照：已有更新一轮 refresh 在途
                update {
                    it.copy(
                        loading = false,
                        error = null,
                        model = s.model,
                        preference = s.preference,
                        gpuPref = s.gpuPref,
                        perfPref = s.perfPref,
                        socTag = s.socTag,
                        models = s.models,
                        spaces = s.spaces,
                        chunkCount = s.chunkCount,
                        docReady = s.docReady,
                        storeState = s.storeState,
                        sharedNames = s.sharedNames,
                        lastReembed = s.lastReembed,
                        // 已检查过的健康报告保持到下次检查/修复；进场快照不再触发全表扫描
                        indexHealth = it.indexHealth ?: s.indexHealth,
                        legacySpaces = s.legacySpaces,
                        legacySpacesError = s.legacySpacesError,
                        hasAutoBackup = s.hasAutoBackup,
                        // LLM 配置是按需加载的：已加载则保留，避免 refresh 又把表单清空
                        llm = if (llmLoaded) it.llm else s.llm,
                        llmError = if (llmLoaded) it.llmError else s.llmError,
                        sagEnabled = s.sagEnabled,
                        answerEnabled = s.answerEnabled,
                        networkDisabled = s.networkDisabled,
                        answerNetworkConsent = s.answerNetworkConsent,
                        budgetTier = s.budgetTier,
                        extractionParallelism = s.extractionParallelism,
                        pdfOcrEnabled = s.pdfOcrEnabled,
                        sagAvailable = s.sagAvailable,
                        extractionCounts = s.extractionCounts,
                        traceTotalCount = s.traceTotalCount,
                        traceRecentModes = s.traceRecentModes,
                    )
                }
                // P1：抽取质量是重查询（全表扫描 + 聚合），改为滚到该段时按需加载（见 UI 的 LaunchedEffect）。
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // R3（复审补）：内层 catch 同样受代际守卫——旧轮 refresh 的迟到错误
                // 不得覆盖新一轮已写入的成功状态（与 onError 守卫对称）
                if (seq == refreshSeq) {
                    update {
                        it.copy(
                            loading = false,
                            error = str(R.string.settings_refresh_failed, t.readableMessage()),
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadSnapshot(): Snapshot {
        val preference = PathPreferences.get(app)
        val gpuPref = PathPreferences.gpuMode(app)
        val perfPref = PathPreferences.perfMode(app)
        val socTag = ModelCatalog.detectSocTag()
        val models = PathPreferences.candidates(app).map {
            ModelFileItem(it, PathPreferences.pathLabel(it.name), it.length())
        }
        val model = EmbeddingRuntime.findModel(app)?.name
        val spaces = db.embeddingDao().allMeta().map {
            SpaceInfo(it.id, db.embeddingDao().countBySpace(it.id), it.dimensions)
        }
        val chunkCount = db.chunkDao().count()
        val docReady = db.documentDao().readyCount(DocStatus.READY.value)
        val lastDone = db.appKvDao().get("reembed_last_done")?.toLongOrNull()
        val lastReembed = lastDone?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }
        // P1：索引健康检查是 O(全部 chunk + 全部向量) 的分页全表扫描，绝不放进进场路径。
        // 初值 null = 未检查；只有用户点「检查并修复索引」（repairIndex）时才执行。
        val indexHealth: IngestRepository.IndexHealth? = null
        // B7：旧模型空间（非 EGemma2）检测。只展示警告、不自动删除数据；失败不影响设置页刷新，
        // 但错误要可见（审查修复：此前静默吞成空列表，用户不知道检测失败）。
        var legacySpaces: List<LegacySpaceUi> = emptyList()
        var legacySpacesError: String? = null
        try {
            legacySpaces = repository.legacySpacesReport().map {
                LegacySpaceUi(modelId = it.modelId, vectorCount = it.vectorCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            legacySpacesError = str(
                R.string.settings_legacy_spaces_error,
                t.readableMessage(),
            )
        }
        // P1：LLM 配置（Keystore 解密）不再在进场时读取——改为滚到 LLM 段时按需加载
        // （ensureLlmLoaded）。这里只保留"未配置"初值，避免每次进设置都碰 Keystore。
        val llmSnapshot = LlmSnapshot(config = null, error = null)
        val llm = llmSnapshot.config
        val llmError = llmSnapshot.error
        val sagEnabled = LlmPreferences.sagEnabled(app)
        val answerEnabled = LlmPreferences.answerEnabled(app)
        val networkDisabled = !LlmPreferences.networkAllowed(app)
        val answerNetworkConsent = LlmPreferences.answerNetworkConsent(app)
        val budgetTier = SagBudgetPreferences.tier(app)
        val extractionParallelism = SagExtractionPreferences.parallelism(app)
        val pdfOcrEnabled = PdfOcrPreferences.enabled(app)
        // B9：SAG 抽取可用性与任务计数（IO 上下文；可用性失败按不可用处理，不阻塞刷新）
        val sagAvailable = runCatching { SagAvailability.isAvailable(app) }.getOrDefault(false)
        val extractionCounts = readExtractionCounts()
        // B12：query_trace 诊断（总条数 + 最近 50 条按 mode 计数；无正文）
        val traceDao = db.queryTraceDao()
        val traceTotalCount = traceDao.count()
        val traceRecentModes = traceDao.recent(TRACE_RECENT_LIMIT)
            .groupingBy { it.mode }
            .eachCount()
        val storeState = SharedModelStore.state(app)
        val sharedNames = if (storeState == SharedModelStore.State.CONNECTED) {
            SharedModelStore.listNamesSync(app)
        } else {
            emptyList()
        }
        val hasAutoBackup = KnowledgeBackup.latestSnapshot(app) != null
        return Snapshot(
            model = model,
            preference = preference,
            gpuPref = gpuPref,
            perfPref = perfPref,
            socTag = socTag,
            models = models,
            spaces = spaces,
            chunkCount = chunkCount,
            docReady = docReady,
            storeState = storeState,
            sharedNames = sharedNames,
            lastReembed = lastReembed,
            indexHealth = indexHealth,
            legacySpaces = legacySpaces,
            legacySpacesError = legacySpacesError,
            hasAutoBackup = hasAutoBackup,
            llm = llm,
            llmError = llmError,
            sagEnabled = sagEnabled,
            answerEnabled = answerEnabled,
            networkDisabled = networkDisabled,
            answerNetworkConsent = answerNetworkConsent,
            budgetTier = budgetTier,
            extractionParallelism = extractionParallelism,
            pdfOcrEnabled = pdfOcrEnabled,
            sagAvailable = sagAvailable,
            extractionCounts = extractionCounts,
            traceTotalCount = traceTotalCount,
            traceRecentModes = traceRecentModes,
        )
    }

    fun setPreference(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            val changed = _state.value.preference != value
            PathPreferences.set(app, value)
            if (changed) ReembedScheduler.enqueue(app)
            refresh()
        }
    }

    fun setGpuMode(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            PathPreferences.setGpuMode(app, value)
            update { it.copy(gpuPref = value) }
        }
    }

    fun setPerfMode(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            PathPreferences.setPerfMode(app, value)
            update { it.copy(perfPref = value) }
        }
    }

    /**
     * P1-10：SAF 目录授权可能抛 SecurityException（部分 OEM/云盘），必须捕获并提示。
     * backup-fixer 最终 API：`setTreeUri` 返回 Boolean（false = provider 拒绝持久授权，
     * 不写偏好），这里对 "抛异常" 与 "返回 false" 两条失败路径都给出可读提示。
     */
    fun setTreeUri(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(error = str(R.string.settings_dir_permission_failed, t.readableMessage()))
                }
            },
        ) {
            try {
                val granted = SharedModelStore.setTreeUri(app, uri)
                if (!granted) {
                    update {
                        it.copy(
                            error = str(
                                R.string.settings_dir_permission_failed,
                                str(R.string.settings_dir_permission_denied),
                            ),
                        )
                    }
                    return@safeLaunch
                }
                update { it.copy(error = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(error = str(R.string.settings_dir_permission_failed, t.readableMessage()))
                }
            }
        }
    }

    fun startDownload(entry: ModelCatalog.Entry) {
        if (_state.value.downloading) return
        update {
            it.copy(
                downloading = true,
                downloadEntry = entry,
                downloadPct = 0,
                message = null,
                error = null,
            )
        }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(error = str(R.string.settings_download_failed, t.readableMessage()))
                }
            },
        ) {
            try {
                val result = SharedModelStore.downloadEntry(app, entry) { p ->
                    if (p.total > 0) {
                        update { it.copy(downloadPct = (p.downloaded * 100 / p.total).toInt()) }
                    }
                }
                result.onSuccess {
                    update { it.copy(message = str(R.string.settings_download_done, entry.fileName)) }
                }.onFailure { t ->
                    update {
                        it.copy(error = str(R.string.settings_download_failed, t.readableMessage()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(downloading = false, downloadEntry = null) }
                refresh()
            }
        }
    }

    fun enableShared(name: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_store_op_failed, t.readableMessage())) } },
        ) {
            val enabled = SharedModelStore.enable(app, name) != null
            update {
                it.copy(
                    message = if (enabled) {
                        str(R.string.settings_enabled, name)
                    } else {
                        str(R.string.settings_enable_failed, name)
                    },
                )
            }
            refresh()
        }
    }

    fun clearShared() {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_clear_all_failed, t.readableMessage())) } },
        ) {
            val n = SharedModelStore.clearAll(app)
            update { it.copy(message = str(R.string.settings_clear_all_done, n), error = null) }
            refresh()
        }
    }

    fun deleteModel(target: File) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_delete_model_failed, t.readableMessage())) } },
        ) {
            withContext(Dispatchers.IO) { target.delete() }
            refresh()
        }
    }

    fun export(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update { it.copy(migrationMessage = str(R.string.settings_export_failed, t.readableMessage())) }
            },
        ) {
            update { it.copy(migrationMessage = str(R.string.settings_exporting)) }
            val result = KnowledgeBackup.export(app, uri)
            result.onSuccess { n ->
                update { it.copy(migrationMessage = str(R.string.settings_export_done, n)) }
            }.onFailure { t ->
                update { it.copy(migrationMessage = str(R.string.settings_export_failed, t.readableMessage())) }
            }
        }
    }

    fun previewImport(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            },
        ) {
            val result = KnowledgeBackup.preview(app, uri)
            result.onSuccess { preview ->
                update {
                    it.copy(importPreview = preview, importUri = uri, importFile = null, migrationMessage = null)
                }
            }.onFailure { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            }
        }
    }

    fun previewLatestSnapshot() {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            },
        ) {
            val file = KnowledgeBackup.latestSnapshot(app)
            if (file == null) {
                update { it.copy(migrationMessage = str(R.string.settings_restore_unavailable)) }
                return@safeLaunch
            }
            KnowledgeBackup.previewFromFile(app, file).onSuccess { preview ->
                update {
                    it.copy(importPreview = preview, importUri = null, importFile = file, migrationMessage = null)
                }
            }.onFailure { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            }
        }
    }

    fun dismissImportPreview() {
        update { it.copy(importPreview = null, importUri = null, importFile = null) }
    }

    fun confirmImport(dropIncompatibleGpuVectors: Boolean) {
        val uri = _state.value.importUri
        val file = _state.value.importFile
        if (uri == null && file == null) return
        // B12：导入前快照预览，成功后据此给出 SAG 补抽取提示（v1/v2 或 SAG 空包）
        val preview = _state.value.importPreview
        update { it.copy(migrationMessage = str(R.string.settings_backup_importing), importPreview = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_import_failed, t.readableMessage()))
                }
            },
        ) {
            val result = if (file != null) {
                KnowledgeBackup.importFromFile(app, file, dropIncompatibleGpuVectors)
            } else {
                KnowledgeBackup.import(app, checkNotNull(uri), dropIncompatibleGpuVectors)
            }
            result.onSuccess { outcome ->
                val sagHint = when {
                    preview != null && preview.formatVersion < 3 -> "\n" + str(R.string.settings_import_legacy_sag_hint)
                    preview != null && preview.eventCount == 0 && preview.extractionJobCount == 0 ->
                        "\n" + str(R.string.settings_import_sag_after_hint)
                    else -> ""
                }
                update {
                    val warning = if (outcome.warning == KnowledgeBackup.ImportWarning.SAG_FTS_REBUILD_FAILED) {
                        "\n" + str(R.string.settings_backup_import_index_warning)
                    } else {
                        ""
                    }
                    it.copy(
                        migrationMessage = str(
                            R.string.settings_backup_import_done,
                            outcome.restoredCount,
                        ) + warning + sagHint,
                        importUri = null,
                        importFile = null,
                    )
                }
                refresh()
            }.onFailure { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_import_failed, t.readableMessage()))
                }
            }
        }
    }

    /**
     * B3-14：检查并修复索引（复用 core 的 [IngestRepository.repairIndex]）。
     * 同一事务清孤儿 FTS/向量，必要时重建 FTS；修复后把 before/after 行数写成可读提示，
     * `indexRepairing` 在 finally 复位（失败也有提示，不会卡在“修复中”）。
     */
    fun repairIndex() {
        if (_state.value.indexRepairing) return
        update { it.copy(indexRepairing = true) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        indexRepairing = false,
                        diagMessage = str(R.string.settings_index_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val report = withContext(Dispatchers.IO) { repository.repairIndex() }
                val rebuiltSuffix = if (report.rebuiltFts) {
                    str(R.string.settings_index_rebuilt_suffix)
                } else {
                    ""
                }
                val message = str(
                    R.string.settings_index_repair_done,
                    report.removedFtsRows,
                    report.removedEmbeddingRows,
                    rebuiltSuffix,
                ) + "\n" + str(
                    R.string.settings_index_before,
                    report.before.chunkCount,
                    report.before.ftsCount,
                    report.before.embeddingCount,
                ) + "\n" + str(
                    R.string.settings_index_after,
                    report.after.chunkCount,
                    report.after.ftsCount,
                    report.after.embeddingCount,
                )
                update {
                    it.copy(
                        indexHealth = report.after,
                        diagMessage = message,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(indexRepairing = false) }
                refresh()
            }
        }
    }

    // ───────────────────────── B8：SAG 增强 / LLM（设置页骨架） ─────────────────────────

    /** 加密配置存储（Keystore AES/GCM）。构造/读写均为轻量同步 API，调用方须在 IO 上下文。 */
    private val llmStore: LlmConfigStore by lazy { KeystoreLlmConfigStore(app) }

    /** B8：LLM 配置读取结果（config=null 且 error=null 表示未配置）。 */
    private data class LlmSnapshot(val config: LlmConfigUi?, val error: String?)

    /**
     * 读取 LLM 配置为 UI 模型：**只调用 maskedKey()**，不把明文 apiKey 带出 core/llm。
     * core 约定：未配置返回 null；配置损坏/解密失败抛 LlmStoreException（message 中文可读）。
     * 这里把失败原因转成可读文案（不含 key），不阻塞设置页其余内容刷新。
     */
    private fun readLlmSnapshot(): LlmSnapshot = try {
        val store = llmStore
        val config = store.load()
        if (config == null) {
            LlmSnapshot(config = null, error = null)
        } else {
            LlmSnapshot(
                config = LlmConfigUi(
                    baseUrl = config.baseUrl,
                    model = config.model,
                    timeoutSeconds = config.timeoutSeconds,
                    maskedKey = store.maskedKey(),
                ),
                error = null,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        LlmSnapshot(
            config = null,
            error = str(R.string.settings_llm_load_failed, t.readableMessage()),
        )
    }

    /**
     * 保存 LLM 配置。`apiKey` 为空 = 保留已保存密钥；无任何可用密钥时给出可读提示。
     * 错误文案会做明文 key 脱敏兜底（正常情况下 core 的异常/toString 已脱敏）。
     */
    fun saveLlmConfig(baseUrl: String, model: String, apiKey: String, timeoutSeconds: Int) {
        val trimmedKey = apiKey.trim()
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTestMessage = str(
                            R.string.settings_llm_save_failed,
                            redactSecret(t.readableMessage(), trimmedKey),
                        ),
                    )
                }
            },
        ) {
            val saved = withContext(Dispatchers.IO) {
                val store = llmStore
                val effectiveKey = if (trimmedKey.isNotBlank()) {
                    trimmedKey
                } else {
                    // 不 runCatching：配置损坏时 load() 的 LlmStoreException 需冒泡为可读错误，
                    // 而不是误报“请填写 API Key”。
                    store.load()?.apiKey.orEmpty()
                }
                check(effectiveKey.isNotBlank()) { str(R.string.settings_llm_api_key_required) }
                store.save(
                    LlmConfig(
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        apiKey = effectiveKey,
                        timeoutSeconds = timeoutSeconds.coerceIn(1, 120),
                    ),
                )
                LlmConfigUi(
                    baseUrl = baseUrl.trim(),
                    model = model.trim(),
                    timeoutSeconds = timeoutSeconds.coerceIn(1, 120),
                    maskedKey = store.maskedKey(),
                )
            }
            update {
                it.copy(
                    llm = saved,
                    llmTestMessage = str(R.string.settings_llm_saved),
                )
            }
            refreshSagSummary()
        }
    }

    /**
     * 测试连接：统一走 Provider（禁网由 Provider 内部按隐私策略强制返回 LLM_OFFLINE 且零请求，
     * UI **不**仅靠本地开关短路，保证任何调用方都无法绕过网络策略）；保存的配置缺失时提示先保存。
     * 成功展示延迟/模型；失败展示 core 的 `readableMessage()`（不含明文 key），
     * 本地存储损坏与网络错误分开提示。
     */
    fun testLlmConnection() {
        if (_state.value.llmTesting) return
        update { it.copy(llmTesting = true, llmTestMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTesting = false,
                        llmTestMessage = str(R.string.settings_llm_test_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        // 不 runCatching：配置损坏时 LlmStoreException 由下面单独归类展示
                        val config = llmStore.load()
                        if (config == null) {
                            null
                        } else {
                            OpenAiCompatibleLlmProvider.from(app).testConnection(config)
                        }
                    }
                } catch (e: LlmStoreException) {
                    // 本地存储/解密错误 ≠ 网络错误：展示“LLM 配置读取失败”并给出恢复入口
                    update {
                        it.copy(
                            llmTesting = false,
                            llmTestMessage = str(
                                R.string.settings_llm_load_failed,
                                e.readableMessage(),
                            ),
                        )
                    }
                    return@safeLaunch
                }
                update {
                    it.copy(
                        llmTesting = false,
                        llmTestMessage = when (result) {
                            null -> str(R.string.settings_llm_test_needs_saved)
                            is LlmTestResult.Success -> if (result.model.isBlank()) {
                                str(R.string.settings_llm_test_ok_no_model, result.latencyMs)
                            } else {
                                str(R.string.settings_llm_test_ok, result.latencyMs, result.model)
                            }
                            is LlmTestResult.Failure -> when (result.code) {
                                // Provider 在禁网时强制零请求：展示“已拦截”而非笼统连接失败
                                LlmErrorCode.OFFLINE ->
                                    str(R.string.settings_llm_test_network_blocked)
                                else -> buildString {
                                    append(
                                        str(
                                            R.string.settings_llm_test_failed,
                                            result.readableMessage(),
                                        ),
                                    )
                                    // 按稳定错误码追加可执行动作提示；未知 code 保留 readableMessage 兜底
                                    append(
                                        when (result.code) {
                                            LlmErrorCode.AUTH -> str(R.string.settings_llm_hint_auth)
                                            LlmErrorCode.INSECURE_URL ->
                                                str(R.string.settings_llm_hint_https)
                                            LlmErrorCode.RATE_LIMITED ->
                                                str(R.string.settings_llm_hint_rate_limited)
                                            LlmErrorCode.TIMEOUT,
                                            LlmErrorCode.NETWORK ->
                                                str(R.string.settings_llm_hint_network)
                                            else -> ""
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(llmTesting = false) }
            }
        }
    }

    /**
     * 清除本机保存的 LLM 配置（core `clear()` 会删除整份配置：baseUrl/model/加密 key；下次保存
     * 自动重建 Keystore key）。离线检索与已索引数据不受影响；表单本地输入会保留便于重填。
     */
    fun clearLlmKey() {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTestMessage = str(R.string.settings_llm_clear_key_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            withContext(Dispatchers.IO) { llmStore.clear() }
            update {
                // core 的 clear() 清除整份配置（含 URL/模型），这里如实置空；表单本地输入会保留，
                // 用户可重新填写后保存。离线检索与已索引数据不受影响。
                it.copy(
                    llm = null,
                    llmTestMessage = str(R.string.settings_llm_clear_key_done),
                )
            }
            refreshSagSummary()
        }
    }

    fun setSagEnabled(enabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setSagEnabled(app, enabled) },
            refreshSag = true,
        ) { it.copy(sagEnabled = enabled) }
    }

    fun setAnswerEnabled(enabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setAnswerEnabled(app, enabled) },
        ) { it.copy(answerEnabled = enabled) }
    }

    /** B12：保存检索预算档位（IO 持久化；下一次检索/问答读取生效）。 */
    fun setBudgetTier(tier: SagBudgetTier) {
        val current = _state.value
        if (current.budgetSaving || current.budgetTier == tier) return
        update { it.copy(budgetSaving = true) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        budgetSaving = false,
                        error = str(R.string.settings_budget_save_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            withContext(Dispatchers.IO) { SagBudgetPreferences.setTier(app, tier) }
            update { it.copy(budgetTier = tier, budgetSaving = false) }
        }
    }

    /** P1.1：保存扫描件 OCR 开关（IO 持久化；下一次 PDF 解析读取）。 */
    fun setPdfOcrEnabled(enabled: Boolean) {
        if (_state.value.pdfOcrEnabled == enabled || _state.value.pdfOcrSaving) return
        update { it.copy(pdfOcrSaving = true, error = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        pdfOcrSaving = false,
                        error = str(R.string.settings_pdf_ocr_save_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                withContext(Dispatchers.IO) { PdfOcrPreferences.setEnabled(app, enabled) }
                update { it.copy(pdfOcrEnabled = enabled, pdfOcrSaving = false) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(pdfOcrSaving = false) }
            }
        }
    }

    /** B12：保存 SAG 抽取并发度（1..4；IO 持久化，Worker 下一次领取任务时读取）。 */
    fun setExtractionParallelism(value: Int) {
        val clamped = value.coerceIn(
            SagExtractionPreferences.MIN_PARALLELISM,
            SagExtractionPreferences.MAX_PARALLELISM,
        )
        if (_state.value.extractionParallelism == clamped || _state.value.extractionParallelismSaving) return
        update { it.copy(extractionParallelismSaving = true, error = null, extractionMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        extractionParallelismSaving = false,
                        extractionMessage = str(
                            R.string.settings_extraction_parallelism_save_failed,
                            t.readableMessage(),
                        ),
                    )
                }
            },
        ) {
            try {
                val saved = withContext(Dispatchers.IO) {
                    SagExtractionPreferences.setParallelism(app, clamped)
                }
                update {
                    it.copy(
                        extractionParallelism = saved,
                        extractionParallelismSaving = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(extractionParallelismSaving = false) }
            }
        }
    }

    /** B11：记录/撤销「问答首次联网隐私说明」同意（首次开启问答前必须取得）。 */
    fun setAnswerNetworkConsent(granted: Boolean) {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(llmTestMessage = str(R.string.settings_llm_toggle_failed, t.readableMessage()))
                }
            },
        ) {
            withContext(Dispatchers.IO) { LlmPreferences.setAnswerNetworkConsent(app, granted) }
            update { it.copy(answerNetworkConsent = granted) }
        }
    }

    /** `disabled = true` 表示禁止网络（持久化为 networkAllowed=false，默认即禁网）。 */
    fun setNetworkDisabled(disabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setNetworkAllowed(app, !disabled) },
            refreshSag = true,
        ) { it.copy(networkDisabled = disabled) }
    }

    private fun updateToggle(
        failureId: Int,
        write: () -> Unit,
        refreshSag: Boolean = false,
        apply: (SettingsUiState) -> SettingsUiState,
    ) {
        safeLaunch(
            onError = { t -> update { it.copy(llmTestMessage = str(failureId, t.readableMessage())) } },
        ) {
            withContext(Dispatchers.IO) { write() }
            update { apply(it) }
            if (refreshSag) refreshSagSummary()
        }
    }

    /** 错误/提示文案中的明文 key 兜底脱敏（正常情况下 core 不返回含 key 的消息）。 */
    private fun redactSecret(message: String, secret: String): String =
        if (secret.isNotBlank()) message.replace(secret, "***") else message

    // ───────────────────────── B9：SAG 两阶段抽取（状态摘要 + 补抽取/重试） ─────────────────────────

    /** 读取五类抽取任务计数（在 IO 上下文调用；共享口径见 ExtractionUi.readCounts）。 */
    private suspend fun readExtractionCounts(): ExtractionCountsUi =
        db.extractionJobDao().readCounts()

    /** 刷新 SAG 可用性 + 抽取计数（LLM 配置/开关/禁网变化后调用；不触发整页 loading）。 */
    private fun refreshSagSummary() {
        safeLaunch {
            val available = withContext(Dispatchers.IO) {
                runCatching { SagAvailability.isAvailable(app) }.getOrDefault(false)
            }
            val counts = withContext(Dispatchers.IO) { readExtractionCounts() }
            update { it.copy(sagAvailable = available, extractionCounts = counts) }
        }
    }

    /** B9「补抽取」：把 ready 文档的未抽取/not_indexed chunk 重新入队（core scheduler 负责入队）。 */
    fun backfillExtraction() {
        runExtractionAction(R.string.settings_sag_backfill_done) {
            SagExtractionScheduler.backfill(app, db)
        }
    }

    /** B9「重试失败」：把终态 failed 的抽取任务重新排队（attempt 清零由 core 处理）。 */
    fun retryFailedExtraction() {
        runExtractionAction(R.string.settings_sag_retry_done) {
            SagExtractionScheduler.retryFailed(app, db)
        }
    }

    private fun runExtractionAction(doneId: Int, action: suspend () -> Int) {
        if (_state.value.extractionWorking) return
        if (!_state.value.sagAvailable) {
            update { it.copy(extractionMessage = str(R.string.settings_sag_unavailable)) }
            return
        }
        update { it.copy(extractionWorking = true, extractionMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        extractionWorking = false,
                        extractionMessage = str(R.string.settings_sag_action_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val n = withContext(Dispatchers.IO) { action() }
                update { it.copy(extractionMessage = str(doneId, n)) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(extractionWorking = false) }
                refreshSagSummary()
            }
        }
    }

    // ───────────────────────── B15.2：抽取质量只读诊断 ─────────────────────────

    /** 读取抽取质量报告（IO；失败可读、不阻塞其他设置；防重入）。 */
    fun refreshExtractionQuality() {
        if (_state.value.qualityLoading) return
        update { it.copy(qualityLoading = true, qualityMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        qualityLoading = false,
                        qualityMessage = str(R.string.settings_quality_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val report = withContext(Dispatchers.IO) {
                    ExtractionQualityAnalyzer.analyze(db)
                }
                update { it.copy(qualityReport = report, qualityLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(qualityLoading = false) }
            }
        }
    }

    /** 通过 SAF 目标 Uri 导出脱敏质量 JSON（无正文/errorDetail/密钥）。 */
    fun exportExtractionQuality(uri: Uri) {
        if (_state.value.qualityExporting) return
        val report = _state.value.qualityReport
        if (report == null) {
            update { it.copy(qualityMessage = str(R.string.settings_quality_export_empty)) }
            return
        }
        update { it.copy(qualityExporting = true, qualityMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        qualityExporting = false,
                        qualityMessage = str(R.string.settings_quality_export_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ExtractionQualityAnalyzer.toJson(report).toByteArray(Charsets.UTF_8)
                }
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入导出文件（Uri 无输出流）")
                    out.use { stream -> stream.write(bytes) }
                }
                update {
                    it.copy(
                        qualityExporting = false,
                        qualityMessage = str(R.string.settings_quality_export_done, bytes.size),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(
                        qualityExporting = false,
                        qualityMessage = str(R.string.settings_quality_export_failed, t.readableMessage()),
                    )
                }
            }
        }
    }

    // ───────────────────────── B12：trace 诊断导出（脱敏，无正文） ─────────────────────────

    /**
     * 导出最近 50 条检索/问答 trace（脱敏诊断，实现见 [TraceExport]；R2/P2-10 与搜索页共用单一实现）。
     */
    fun exportTraceJson() {
        if (_state.value.traceExporting) return
        update { it.copy(traceExporting = true, traceExportMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        traceExporting = false,
                        traceExportMessage = str(R.string.settings_trace_export_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val (count, path) = TraceExport.export(app, db)
                update {
                    it.copy(
                        traceExportMessage = if (path == null) {
                            str(R.string.settings_trace_export_empty)
                        } else {
                            str(R.string.settings_trace_exported, count, path)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(traceExporting = false) }
                refreshTraceStats()
            }
        }
    }

    /** 刷新 trace 总条数与最近 50 条的模式分布（诊断展示）。 */
    private fun refreshTraceStats() {
        safeLaunch {
            val stats = withContext(Dispatchers.IO) {
                val dao = db.queryTraceDao()
                dao.count() to dao.recent(TRACE_RECENT_LIMIT).groupingBy { it.mode }.eachCount()
            }
            update { it.copy(traceTotalCount = stats.first, traceRecentModes = stats.second) }
        }
    }

    fun reembedNow() {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_reembed_enqueue_failed, t.readableMessage())) } },
        ) {
            ReembedScheduler.enqueue(app)
        }
    }

    fun cancelReembed() {
        ReembedScheduler.cancel(app)
    }

    private companion object {
        /** B12：trace 展示条数（导出条数统一在 TraceExport.EXPORT_LIMIT）。 */
        const val TRACE_RECENT_LIMIT = 50

        /** 进场自动刷新最小间隔（毫秒）。 */
        const val AUTO_REFRESH_MIN_INTERVAL_MS = 5_000L
    }

    private fun observeReembedProgress() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ReembedScheduler.UNIQUE_WORK).collect { infos ->
                val work = infos.firstOrNull() ?: return@collect
                when (work.state) {
                    WorkInfo.State.RUNNING -> update {
                        it.copy(
                            reembedTotal = work.progress.getInt("total", -1),
                            reembedDone = work.progress.getInt("done", 0),
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> update {
                        it.copy(
                            reembedTotal = work.progress.getInt("total", it.reembedTotal),
                            reembedDone = work.progress.getInt("total", it.reembedDone),
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** B15.2：覆盖率百分比（0 保护 + 夹取 0..100；UI 与测试共用）。 */
internal fun coveragePercent(covered: Int, total: Int): Int {
    if (total <= 0) return 0
    return ((covered.toLong() * 100L) / total.toLong()).toInt().coerceIn(0, 100)
}

/** B15.2：比率（0..1）→ 百分比整数（四舍五入后夹取 0..100）。 */
internal fun ratioPercent(ratio: Double): Int {
    if (ratio.isNaN() || ratio <= 0.0) return 0
    return Math.round(ratio * 100.0).toInt().coerceIn(0, 100)
}
