package com.zhibox.memory.core.embedding

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 嵌入引擎统一运行时（W2）：进程级单例 Provider 的唯一持有者。
 *
 * 导入（IngestBatchWorker）与检索（SearchService）共用同一引擎实例，
 * 全部访问经由 [withProvider] 互斥串行（引擎非线程安全；模型 1.4GB 只允许一份）。
 * 模型文件路径变化时（设置切换/兜底降级）自动重建，旧实例即时释放。
 *
 * **线程边界（P0-2）**：本对象所有重活——Provider 构建（`ParcelFileDescriptor.open` +
 * JNI `createFromOptions`，秒级）、GPU 试探首推理、以及调用方的 block（检索矩阵扫描/
 * 批量嵌入）——都在 [Dispatchers.Default] 上执行；Main 调用方只挂起等待，不阻塞 UI。
 * 生命周期状态由纯 JVM 的 [EngineCache] 承载（单测覆盖，见 EngineCacheTest）。
 *
 * **B7 模型收敛**：createProvider 只路由 EGemma2 家族——440M/740M + SM8750 NPU 变体走
 * 路径 A（UniversalEmbedder），270M 走路径 B（litertlm EmbeddingEngine）。已下线的
 * Path C（`.task`/TextEmbedder）、granite、LFM 分支与 SPACE_ID_B 兼容映射均已删除。
 *
 * GPU 策略（W3 遗留闭环，2026-10-16）：默认 auto——先试 GPU（建引擎 + 探针首推理，
 * 捕获"建引擎成功但首推理崩"的延迟失败），失败自动回退 CPU 并记录原因；
 * Phase 0 结论仍有效：仅 SM8750/Adreno 可用 GPU，MT6985/MT6893 创建即失败。
 * 偏好 off=强制 CPU（基线），on=强制 GPU（失败报错，用于基准对照）。
 */
object EmbeddingRuntime {

    /** 纯 JVM 引擎缓存（P1-3：重建失败自愈 / 锁内 shutdown / 纯 JVM 单测） */
    private val engine = EngineCache()

    /** B1-11/B1-4：历史 meta 惰性维护（revision 回填 + active 收敛）每进程只尝试一次 */
    private val metaMaintenanceChecked = AtomicBoolean(false)

    /** 最近一次引擎构建实际生效的 delegate（null=尚未构建） */
    @Volatile
    var effectiveGpu: Boolean? = null
        private set

    /** auto 模式下 GPU 失败回退的原因（null=未回退或非 auto） */
    @Volatile
    var gpuFallbackReason: String? = null
        private set

    /**
     * 仅供单测的 Provider 工厂注入（默认 null=生产 [createProvider]）。
     * 生产行为不变；纯 JVM 单测可直接测 [EngineCache]/[probeGpuWithTimeout]，
     * 需要覆盖完整 [withProvider] 时用 `androidx.test` 的 Robolectric Context 注入 fake。
     */
    @VisibleForTesting
    internal var providerFactory: ((Context, File, Boolean, Boolean) -> EmbeddingProvider)? = null

    /**
     * 串行访问当前模型对应的 Provider；block 内不得再嵌套调用本方法。
     * gpuOverride 仅基准对照用（null=按 PathPreferences.gpuMode）。
     *
     * 实现说明（P1-3）：缓存/重建/关闭全部收敛到 [EngineCache.access]——key 变化时先清引用
     * 再关旧实例，构建失败保持空以便下次调用自愈重试。
     */
    suspend fun <T> withProvider(
        context: Context,
        model: File,
        gpuOverride: Boolean? = null,
        block: suspend (EmbeddingProvider) -> T,
    ): T {
        val appContext = context.applicationContext
        // P0-2：SharedPreferences 首次读取也可能触盘 + JNI 构建为秒级阻塞 → 统一在 Default
        return withContext(Dispatchers.Default) {
            val mode = PathPreferences.gpuMode(appContext)
            val key = "${model.absolutePath}::gpu=${gpuOverride ?: mode}"
            engine.access(
                key = key,
                build = { buildEngine(appContext, model, gpuOverride, mode) },
                block = { provider -> block(provider) },
            )
        }
    }

    /** 按偏好/白名单决定是否尝试 GPU，并完成构建 + 探针（失败策略按 auto/forced 区分） */
    private suspend fun buildEngine(
        context: Context,
        model: File,
        gpuOverride: Boolean?,
        mode: String,
    ): EmbeddingProvider {
        effectiveGpu = null
        gpuFallbackReason = null
        val wanted = gpuOverride ?: when (mode) {
            PathPreferences.GPU_ON -> true
            PathPreferences.GPU_OFF -> false
            else -> true // auto：先试 GPU
        }
        val autoFallback = gpuOverride == null && mode == PathPreferences.GPU_AUTO
        // AUTO 仅对已验证 GPU 可用的组合试探（440M 通用版+SM8750，2026-10-16 实测 6.2×）；
        // 历史 granite 路线 int8 曾确定性挂死（双重复现，B7 已下线该路线）；SOC 特化版走 NPU
        // 后端不进本逻辑；其余直接 CPU，避免旗舰日常首用白付 2-15s 试探成本。W8 监控解锁后放开。
        // AUTO 白名单（2026-10-16 三档 warm 定案）：440M 通用版 + **Adreno SOC**。
        // GPU 收益为 Adreno 专属：旗舰 440M GPU warm 44ms vs CPU 62-126ms；Mali 两档
        // 440M GPU warm 153-352ms 反慢于同机 CPU 126ms，且 K40 Mali 编译 RSS 飙至 2.6GB
        // （7.3GB 机型危险）→ MT 直接 CPU。特化版走 NPU 不进本逻辑。
        val tryGpu = when {
            !wanted -> false
            autoFallback -> shouldTryGpuAuto(model.name)
            else -> true // 显式 on：用户强制，挂死如实呈现
        }
        if (!tryGpu) {
            effectiveGpu = false
            return buildProvider(context, model, useGpu = false)
        }
        return try {
            val provider = if (autoFallback) {
                // auto 模式限时试探：JNI 阻塞无法被取消 → 超时/取消时由 probeGpuWithTimeout
                // 关闭已构建实例（P1-3 泄漏修复），再回退 CPU
                probeGpuWithTimeout(
                    timeoutMs = gpuProbeTimeoutMsForTest ?: GPU_PROBE_TIMEOUT_MS,
                    build = { buildProvider(context, model, useGpu = true) },
                    probe = { it.embedQuery("gpu 探针") },
                )
            } else {
                // 强制 on：挂死即挂死，如实呈现（基准对照语义）
                buildProvider(context, model, useGpu = true).also {
                    it.embedQuery("gpu 探针") // 延迟失败在此暴露（建引擎成功≠能推理）
                }
            }
            effectiveGpu = true
            provider
        } catch (t: Throwable) {
            // 调用方取消（WorkManager 停止/协程取消）：不是 GPU 不可用，不得回退 CPU 继续跑
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (!autoFallback) throw t
            gpuFallbackReason = "${t.javaClass.simpleName}: ${t.message?.lineSequence()?.first() ?: "GPU 探针失败/超时"}"
            effectiveGpu = false
            buildProvider(context, model, useGpu = false)
        }
    }

    /** AUTO 白名单：440M 通用版 + Adreno SOC（见 buildEngine 注释的实测依据） */
    private fun shouldTryGpuAuto(modelName: String): Boolean {
        val soc = ModelCatalog.detectSocTag()
        return IngestRepository.isPathAModel(modelName) &&
            !isSocSpecialized(modelName) &&
            (soc.contains("sm8") || soc.contains("sm_8"))
    }

    /** auto 模式 GPU 试探预算：超时回退 CPU（历史 granite int8 @8 Elite 曾挂死 >8min，防同类未知模型） */
    private const val GPU_PROBE_TIMEOUT_MS = 15_000L

    /** 仅供单测：缩短 GPU 试探预算（默认 null=生产 15s） */
    @VisibleForTesting
    internal var gpuProbeTimeoutMsForTest: Long? = null

    private fun buildProvider(
        context: Context,
        model: File,
        useGpu: Boolean,
        useNpu: Boolean = false,
    ): EmbeddingProvider =
        providerFactory?.invoke(context, model, useGpu, useNpu)
            ?: createProvider(context, model, useGpu, useNpu)

    /** 仅供诊断/设置页显示；业务访问一律走 [withProvider] */
    val currentModelPath: String? get() = engine.sharedKey

    /**
     * B3-2 审查修复：本次检索是否会复用已构建引擎（warm）。
     *
     * 不能只判 `currentModelPath != null`：换模型/切换 GPU 偏好后 key 变化仍会重建
     * （5-10s），旧逻辑会漏报阶段提示。这里按 [withProvider] 的同一 key 规则
     * （`absolutePath::gpu=<mode>`）比较，只有模型与模式都一致才算 warm。
     * SharedPreferences/JNI 读取放 Default，避免主线程磁盘访问。
     */
    suspend fun isEngineWarm(context: Context): Boolean = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val model = findModel(appContext) ?: return@withContext false
        val key = "${model.absolutePath}::gpu=${PathPreferences.gpuMode(appContext)}"
        currentModelPath == key
    }

    /** SOC 特化版判定：`_<Vendor>_<Chipset>` 后缀（litert-community 特化版命名约定） */
    fun isSocSpecialized(fileName: String): Boolean =
        fileName.contains("_Qualcomm", ignoreCase = true) ||
            fileName.contains("_MediaTek", ignoreCase = true) ||
            fileName.contains("_Google_Tensor", ignoreCase = true) ||
            fileName.contains("_Intel", ignoreCase = true)

    /**
     * 空间注册——`embedding_meta` 的**唯一写入口**（P1-6）。
     *
     * 所有字段直接由 [EmbeddingDescriptor] 生成，不再二次推断：
     * - `accelerator` ← descriptor.accelerator（此前恒 cpu）；
     * - `taskTemplate` ← 双模板策略（RAW/RAW = raw-symmetric，其余 = app-prefix-v1；
     *   此前按 spaceId 猜测，GPU/NPU 的 app-prefix 空间被误记为 raw-symmetric）；
     * - `modelRevision` ← descriptor.modelRevision（Provider 构造时由 [ModelFingerprint] 计算）；
     * - `quantization` ← descriptor.quantization（当前向量存储格式）。
     *
     * **B1-4 单一 active 不变式**：`embedding_meta.active` 语义 = "当前默认空间"（供
     * 备份/设置页展示），**不等于可检索性**（检索按 resolveSpaceId(当前模型) 选空间，
     * 旧空间仍可用其自身模型检索）。在**同一事务**内把其他 active 行置 false 后再 upsert
     * 当前空间 active=true，事务外观察不到中间态，保证任意时刻最多一行 active。
     *
     * 返回解析后的空间 id（供调用方写向量使用）。已有 meta 仅在字段变化/active 收敛时更新
     * （保留 createdAt），并顺带**回填**存量行的 modelRevision——这是"同名换文件自动新空间"能生效的基线。
     */
    suspend fun registerSpace(context: Context, provider: EmbeddingProvider, model: File): String {
        val db = AppDatabase.get(context)
        val descriptor = provider.descriptor
        // B1-11：注册前先做一次惰性 revision 回填（每进程仅一次；见 ensureMetaLazyMaintenance）
        val spaceId = resolveSpaceId(context, provider)
        // B4-3：维度单一来源（catalog 已收录走 catalog dims，未收录回退 DEFAULT_DIMS）
        val dimensions = descriptor.dimensions.takeIf { it > 0 }
            ?: ModelCatalog.dimsFor(model.name)
        db.withTransaction {
            val existing = db.embeddingDao().meta(spaceId)
            val meta = EmbeddingMetaEntity(
                id = spaceId,
                providerId = descriptor.providerId,
                modelId = descriptor.modelId,
                modelRevision = descriptor.modelRevision,
                dimensions = dimensions,
                normalization = descriptor.normalization,
                taskTemplate = taskTemplateFor(descriptor),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                active = true,
                accelerator = descriptor.accelerator,
                quantization = descriptor.quantization,
            )
            // B1-4：把其他 active 行收敛为 false（deactivated 只包含需要变更的行）
            val deactivated = deactivateOthers(db.embeddingDao().allMeta(), spaceId)
            if (deactivated.isNotEmpty() || existing != meta) {
                db.embeddingDao().upsertMetaAll(deactivated + meta)
            }
        }
        return spaceId
    }

    /**
     * B1-4 纯函数：返回除 [activeSpaceId] 外仍为 active 的行（置 false 后的副本）。
     * 供 [registerSpace] 在同一事务内收敛"仅一个 active"不变式；单测直接覆盖。
     */
    internal fun deactivateOthers(
        metas: List<EmbeddingMetaEntity>,
        activeSpaceId: String,
    ): List<EmbeddingMetaEntity> =
        metas.filter { it.id != activeSpaceId && it.active }.map { it.copy(active = false) }

    /**
     * 解析当前 provider 应写入/检索的向量空间 id（含同名换文件检测，P1-6）。
     *
     * 兼容空间（仅 EGemma2-440M 通用 CPU → [IngestRepository.SPACE_ID]）保持旧 id 以复用
     * 存量向量（B7 后 granite 的 SPACE_ID_B 映射已删除，旧空间由 legacySpacesReport 报告），但若该空间已登记的
     * modelRevision 与新文件 revision 不同，说明用户用同名新版本覆盖了模型文件：
     * 此时生成带 `@<rev前12位>` 后缀的新空间，避免新旧权重向量静默混用。
     * 存量 meta 的 modelRevision 为 null（升级前写入）时不判定为换文件——首次
     * [registerSpace] 会把当前 revision 回填，之后的替换即可检出。
     */
    suspend fun resolveSpaceId(context: Context, provider: EmbeddingProvider): String {
        val descriptor = provider.descriptor
        val db = AppDatabase.get(context)
        val revision = descriptor.modelRevision?.take(REVISION_LENGTH)?.takeIf { it.isNotBlank() }
        // 存量空间兼容（P1-6 审查修复）：升级前已存在的非 legacy 空间 id 不带 @revision
        // （如 GPU/NPU 特化版、740M 等 EGemma2 旧空间），若直接按新规则计算会得到新 id，
        // 导致旧向量"消失"且无回填。这里优先采纳已存在的旧格式空间，并由 registerSpace
        // 回填 modelRevision；只有当该行的 revision 与新文件不一致（同名换文件）时才建新空间。
        val oldFormatIds = listOfNotNull(
            legacySpaceIdFor(descriptor),
            revisionLabeledSpaceId(descriptor, null),
        ).distinct()
        val existing = oldFormatIds.firstNotNullOfOrNull { id ->
            db.embeddingDao().meta(id)?.let { id to it }
        }
        val resolved = if (existing != null) {
            val (id, meta) = existing
            val swapped = meta.modelRevision != null && revision != null && meta.modelRevision != revision
            if (swapped) revisionLabeledSpaceId(descriptor, revision) else id
        } else {
            spaceIdResolved(descriptor, knownRevision = null)
        }
        // B1-11/B1-4：解析决策**完成之后**再做惰性维护——回填是按 catalog 猜测的 revision，
        // 不能反过来影响本次"采纳旧空间"的决策（否则老库首次检索可能被误判为换文件）。
        // 每进程最多一次写（失败可重试），不逐查询写库、不打断检索。
        ensureMetaLazyMaintenance(db, resolved)
        return resolved
    }

    // ───────────────────── B1-11 modelRevision 惰性回填（只读路径） ─────────────────────

    /**
     * B1-11（+B1-4）：v5 升级前写入的历史 meta 有两类历史欠账，只读搜索/启动不触发写入时
     * 永远无法收敛：
     * 1. `modelRevision` 为空 → "同名换文件自动新空间"对老库失效；
     * 2. `active` 可能多行（旧实现每个空间都 active=true）→ 违反"仅一个 active"不变式。
     *
     * 本方法在**首次 resolveSpaceId（检索/导入共用）**时做一次惰性维护：
     * - revision：按 modelId 对 ModelCatalog sha 回填（未收录保持空，不猜测）；
     * - active：若本次解析出的当前空间已在 meta 中，则只保留它为 active（B1-4 不变式）；
     * - 进程内 [AtomicBoolean] + 持久 app_kv 标记双重守卫 → 每进程最多尝试一次、跨进程仅一次写；
     * - 搜索路径只多一次 app_kv 读（首次）与少量 meta upsert，不逐查询写库、不逐查询卡顿；
     * - 失败（IO/迁移竞争）时重置进程内标记，下次调用重试，且绝不打断检索。
     *
     * **时点限制**（与 resolveSpaceId 注释一致，需写入发布说明）：回填只是"按文件名猜历史
     * 权重版本"，无法验证旧向量实际由哪个权重生成；若用户在升级前已替换同名模型文件，
     * 历史混用无法追溯。
     */
    private suspend fun ensureMetaLazyMaintenance(db: AppDatabase, resolvedSpaceId: String) {
        if (!metaMaintenanceChecked.compareAndSet(false, true)) return
        try {
            if (db.appKvDao().get(KEY_META_LAZY_MAINTENANCE) != null) return
            // 维护行与"已完成"标记在同一事务写入：半途失败不落标记，下次进程可重试
            db.withTransaction {
                val updates = lazyMetaUpdates(db.embeddingDao().allMeta(), resolvedSpaceId)
                if (updates.isNotEmpty()) db.embeddingDao().upsertMetaAll(updates)
                db.appKvDao().put(
                    AppKvEntity(KEY_META_LAZY_MAINTENANCE, "1", System.currentTimeMillis()),
                )
            }
        } catch (e: Exception) {
            // 首次检索/启动可能遇到 IO 或迁移竞争：允许下次调用重试（不打断检索）
            metaMaintenanceChecked.set(false)
        }
    }

    /**
     * B1-11 + B1-4 纯函数：计算惰性维护需要 upsert 的 meta 行（单测覆盖）：
     * 1. modelRevision 为空且 catalog 可反查 → 回填 sha 前 12 位；
     * 2. [resolvedSpaceId] 存在于 meta 中时，active 收敛为仅它一行 true
     *    （修复旧库"每空间都 active=true"的历史状态；新建空间则等 registerSpace 落 active）。
     * 返回的行是最终值；调用方与"已完成"标记在同一事务内 upsert。
     */
    internal fun lazyMetaUpdates(
        metas: List<EmbeddingMetaEntity>,
        resolvedSpaceId: String,
    ): List<EmbeddingMetaEntity> {
        if (metas.isEmpty()) return emptyList()
        // 本次解析空间存在于 meta 时才收敛 active（新建空间在 registerSpace 时落 active=true）
        val currentSpaceId = resolvedSpaceId.takeIf { id -> metas.any { it.id == id } }
        val backfilled = revisionBackfillFor(metas).associateBy { it.id }
        return metas.mapNotNull { meta ->
            var updated = backfilled[meta.id] ?: meta
            if (currentSpaceId != null) {
                val shouldBeActive = updated.id == currentSpaceId
                if (updated.active != shouldBeActive) {
                    updated = updated.copy(active = shouldBeActive)
                }
            }
            updated.takeIf { it != meta }
        }
    }

    /** B1-11 纯函数：为 modelRevision 为空且能由 catalog 反查 sha 的 meta 生成回填副本（单测覆盖） */
    internal fun revisionBackfillFor(metas: List<EmbeddingMetaEntity>): List<EmbeddingMetaEntity> =
        metas.mapNotNull { meta ->
            if (!meta.modelRevision.isNullOrBlank()) null
            else catalogRevisionForModelId(meta.modelId)?.let { meta.copy(modelRevision = it) }
        }

    /**
     * B1-11 纯函数：modelId（模型文件名去扩展名）→ catalog sha 前 12 位。
     * 用户自投放/已下线路线模型未收录于 catalog → null（保持空，不猜测）。
     */
    internal fun catalogRevisionForModelId(modelId: String): String? =
        ModelCatalog.ENTRIES
            .firstOrNull { it.fileName.substringBeforeLast('.') == modelId }
            ?.sha256
            ?.take(REVISION_LENGTH)

    /**
     * 空间指纹（纯函数，单测覆盖）：
     * - EGemma2-440M 通用 CPU 保持旧 SPACE_ID（存量向量兼容；B7 后 granite SPACE_ID_B 删除）；
     * - **GPU/NPU 向量必须独立空间**（实测跨设备不逐位一致：G1E 旗舰 6864bf58 vs K60U acd0ba1f——
     *   GPU 编译随设备驱动而异），id 加 -gpu/-npu 后缀；
     * - 其余新模型按 modelId 独立空间，并把 modelRevision 纳入 id（`...@<rev前12位>`，
     *   P1-6：同名换文件 = 新空间）；
     * - 兼容空间检出同名换文件（[knownRevision] 非空且与当前不同）时也退化为带 revision 的新空间。
     */
    fun spaceIdFor(provider: EmbeddingProvider): String =
        spaceIdResolved(provider.descriptor, knownRevision = null)

    /**
     * B7：旧空间 id 兼容**仅保留 EGemma2-440M 通用 CPU → SPACE_ID**（存量用户库/向量不丢）。
     * granite 的 SPACE_ID_B 兼容分支已随路线下线删除；旧 granite/LFM/Path C 空间由
     * [IngestRepository.legacySpacesReport] 识别并提示"重新导入"。
     */
    private fun legacySpaceIdFor(d: EmbeddingDescriptor): String? = when {
        d.accelerator == "cpu" && d.providerId == UniversalEmbedderProvider.PROVIDER_ID &&
            d.modelId == "embeddinggemma-2-text-vision-440m" -> IngestRepository.SPACE_ID
        else -> null
    }

    /** 纯函数：空间 id 解析（[spaceIdFor] 与 DB-aware [resolveSpaceId] 共用，单测直接覆盖） */
    internal fun spaceIdResolved(d: EmbeddingDescriptor, knownRevision: String?): String {
        val revision = d.modelRevision?.take(REVISION_LENGTH)?.takeIf { it.isNotBlank() }
        val legacy = legacySpaceIdFor(d)
        if (legacy != null) {
            val swapped = knownRevision != null && revision != null && knownRevision != revision
            return if (swapped) revisionLabeledSpaceId(d, revision) else legacy
        }
        return revisionLabeledSpaceId(d, revision)
    }

    private fun revisionLabeledSpaceId(d: EmbeddingDescriptor, revision: String?): String {
        val accelSuffix = if (d.accelerator == "cpu") "" else "-${d.accelerator}"
        val revSuffix = revision?.let { "@$it" } ?: ""
        return "${d.providerId}/${d.modelId}/raw-symmetric-v1$accelSuffix$revSuffix"
    }

    /** 模板策略 → meta.taskTemplate（P1-6：由 descriptor 生成，不再按 spaceId 二次推断） */
    internal fun taskTemplateFor(d: EmbeddingDescriptor): String {
        val raw = TaskTemplatePolicy.RAW.name
        return when {
            d.queryTaskTemplate == null && d.documentTaskTemplate == null -> "raw-symmetric"
            d.queryTaskTemplate == raw && d.documentTaskTemplate == raw -> "raw-symmetric"
            else -> "app-prefix-v1"
        }
    }

    /** 检索/设置页共用：当前可选模型（与 IngestRepository.findModel 同一偏好与显式名单） */
    fun findModel(context: Context): File? = IngestRepository.findModelIn(context)

    /**
     * 关闭并释放当前引擎（P1-3：改为 suspend + Mutex，消除与 [withProvider] 并发的
     * use-after-close；benchmark 等调用点需在协程内调用）。
     */
    suspend fun shutdown() {
        engine.shutdown()
        effectiveGpu = null
        gpuFallbackReason = null
    }

    fun createProvider(
        context: Context,
        model: File,
        useGpu: Boolean = false,
        useNpu: Boolean = false,
    ): EmbeddingProvider = when {
        // SOC 特化版（NPU 编译）→ Gallery 同款通道：tasks-retrieval UniversalEmbedder + Delegate.NPU
        // + NpuOptions。litertlm EmbeddingEngine 通道已实测挂死（2026-10-16），弃用。
        isSocSpecialized(model.name) -> UniversalEmbedderProvider(context, model, useNpu = useNpu)
        // B7：路径 B 引擎只剩 EGemma2-270M；其余 EGemma2（440M/740M）走路径 A UE。
        IngestRepository.isPathBModel(model.name) -> LiteRtLmEmbeddingProvider(context, model, useGpu, useNpu = false)
        else -> UniversalEmbedderProvider(context, model, useGpu = useGpu)
    }

    /** 指纹长度（与 [ModelFingerprint.REVISION_LENGTH] 一致） */
    private const val REVISION_LENGTH = ModelFingerprint.REVISION_LENGTH

    /** B1-11/B1-4 持久标记：历史 meta 惰性维护（revision 回填 + active 收敛）已完成（跨进程只写一次） */
    private const val KEY_META_LAZY_MAINTENANCE = "meta_lazy_maintenance_v1"
}
