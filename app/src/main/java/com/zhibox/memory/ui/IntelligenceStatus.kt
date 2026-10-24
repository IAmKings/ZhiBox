package com.zhibox.memory.ui

/**
 * 智能状态中枢的数据契约（P0-1）。
 *
 * 设计依据：`docs/product/INTELLIGENCE_HUB_SPEC.md` §2/§3/§10。
 * 本文件**不依赖 Android**，只做「读快照 → 判状态」的纯函数，便于纯 JVM 单测覆盖判定矩阵。
 * 运行期读取（文件系统 / DB / 偏好 / Keystore）在 `HomeViewModel` 中完成，
 * 任何一项读失败都以 `Unknown`/`-1` 降级，不抛给 UI。
 */

/** 四态：就绪 / 部分就绪 / 未就绪 / 异常（§3.2）。 */
enum class IntelligenceLevel { READY, PARTIAL, NOT_READY, ERROR }

/** 进行中的智能任务：只影响动效节奏，不改变底色（§3.4）。 */
enum class IntelligenceBusy { NONE, DOWNLOADING, EXTRACTING, SYNCING }

sealed interface ModelStatus {
    /** 未发现可用嵌入模型（L1 缺失）。 */
    data object Missing : ModelStatus

    /** 模型就绪。 */
    data class Ready(val name: String) : ModelStatus

    /** 模型存在但文件损坏/不可读（L1 异常）。 */
    data object Corrupt : ModelStatus
}

sealed interface SpaceStatus {
    /** 读取失败，面板显示「未知」。 */
    data object Unknown : SpaceStatus

    /** 没有任何向量空间（空库，仍是就绪）。 */
    data object None : SpaceStatus

    data class Ready(val count: Int) : SpaceStatus
}

/** GPU 只能表达"档位偏好"，真实可用性事前不可知（§10.3）。 */
enum class GpuStatus { AUTO, ON, OFF, UNKNOWN }

sealed interface LlmStatus {
    data object NotConfigured : LlmStatus
    data object Configured : LlmStatus

    /** 配置存在但读取失败（Keystore 解密/损坏）。 */
    data object LoadError : LlmStatus

    data object Unknown : LlmStatus
}

/** L4 待处理项（有则 PARTIAL，数量进角标）。 */
enum class PendingItem {
    LEGACY_SPACES,
    EXTRACTION_FAILED,
    INDEX_UNHEALTHY,
    STORE_PERMISSION_LOST,
    LLM_LOAD_ERROR,
}

/**
 * 一次读取的快照（原始事实，不含判定逻辑）。
 * 用 `-1`/`null`/`false` 表达"未知"，绝不把读取失败伪装成"正常"。
 */
data class IntelligenceReadings(
    /** 当前生效的嵌入模型名；null = 未配置。 */
    val modelName: String? = null,
    /** 模型文件存在但不可读/0 字节（廉价损坏判定；完整校验在导入/检索时做）。 */
    val modelFileBroken: Boolean = false,
    /** 向量空间数量；-1 = 读取失败。 */
    val spaceCount: Int = -1,
    /** GPU 档位原始值（auto/on/off）；null = 读取失败。 */
    val gpuMode: String? = null,
    /** LLM 是否已保存配置（baseUrl + model 非空）。 */
    val llmConfigured: Boolean = false,
    /** 读取 LLM 配置时抛异常（Keystore/损坏）。 */
    val llmLoadError: Boolean = false,
    /** 读取 LLM 配置是否成功（false 时 LLM 状态为 Unknown）。 */
    val llmReadSucceeded: Boolean = true,
    /** 旧模型空间数量；-1 = 读取失败。 */
    val legacySpaceCount: Int = -1,
    /** 抽取失败的分块数；-1 = 读取失败。 */
    val extractionFailedCount: Int = -1,
    /** 当前是否有抽取任务在跑（决定快脉冲）。 */
    val extracting: Boolean = false,
    /** 共享目录授权丢失。 */
    val storePermissionLost: Boolean = false,
    /** 索引不一致（轻量探测：chunk 行数 vs FTS 行数不一致）。 */
    val indexUnhealthy: Boolean = false,
    /** 共享目录未配置（E4）。 */
    val storeNotConfigured: Boolean = false,
    /** 候选模型文件数（E1/E2 区分依据）；-1 = 读取失败。 */
    val modelFileCount: Int = -1,
    /** 知识库 chunk 总数（E3 空库判定依据）；-1 = 读取失败。 */
    val chunkCount: Int = -1,
    /** SAG 增强是否开启（E8）；null = 读取失败（未知，不展示 pill）。 */
    val sagEnabled: Boolean? = null,
    /** 是否处于「禁止网络」（E8）；null = 读取失败。 */
    val networkDisabled: Boolean? = null,
    /** 问答首次联网隐私说明是否已同意（E14）；null = 读取失败。 */
    val answerConsentGranted: Boolean? = null,
)

/** 面板/状态点消费的聚合结果。 */
data class IntelligenceStatus(
    val level: IntelligenceLevel,
    val busy: IntelligenceBusy,
    /** 进行中任务的百分比（0..100）；仅下载/同步有，其它为 null。 */
    val busyPct: Int? = null,
    val model: ModelStatus,
    val spaces: SpaceStatus,
    val gpu: GpuStatus,
    val llm: LlmStatus,
    val pending: List<PendingItem>,
    /** 旧模型空间数量（0 = 无；用于面板待处理行文案）。 */
    val legacySpaceCount: Int = 0,
    /** 抽取失败的分块数（0 = 无；用于面板待处理行文案）。 */
    val extractionFailedCount: Int = 0,
    /** 候选模型文件数；-1 = 读取失败（E1/E2 区分依据）。 */
    val modelFileCount: Int = -1,
    /** 知识库 chunk 总数；-1 = 读取失败（E3 空库判定依据）。 */
    val chunkCount: Int = -1,
    /** 共享目录未配置（E4 pill）。 */
    val storeNotConfigured: Boolean = false,
    /** SAG 增强开关；null = 读取失败（E8 pill 未知则不展示）。 */
    val sagEnabled: Boolean? = null,
    /** 是否「禁止网络」；null = 读取失败（E8 pill 未知则不展示）。 */
    val networkDisabled: Boolean? = null,
    /** 问答首次联网同意是否授予；null = 读取失败（E14 pill 未知则不展示）。 */
    val answerConsentGranted: Boolean? = null,
    /** 下载中的模型名（E12 行标题）。 */
    val busyLabel: String? = null,
    /** 重嵌入已完成数（E13 进度）。 */
    val busyDone: Int? = null,
    /** 重嵌入总数；null = 未知（E13 用不确定进度条）。 */
    val busyTotal: Int? = null,
) {
    /** 角标数字 = 待处理项数（不是分块数）。 */
    val pendingCount: Int get() = pending.size

    /** 是否需要首次引导条（仅"未就绪"）。 */
    val needsOnboarding: Boolean get() = level == IntelligenceLevel.NOT_READY

    /**
     * 是否有动效：就绪/部分就绪慢呼吸；未就绪与异常静止，
     * **但有进行中的任务时（下载/重嵌入/抽取）仍然脉冲**——干着活就该动，
     * 否则用户从"未就绪"点下载后看不到任何反馈。
     */
    val animates: Boolean
        get() = level == IntelligenceLevel.READY ||
            level == IntelligenceLevel.PARTIAL ||
            busy != IntelligenceBusy.NONE

    companion object {
        /** 冷启动首帧占位：亮灰、不动，避免"先绿后灰"的跳变。 */
        val Loading = IntelligenceStatus(
            level = IntelligenceLevel.NOT_READY,
            busy = IntelligenceBusy.NONE,
            model = ModelStatus.Missing,
            spaces = SpaceStatus.Unknown,
            gpu = GpuStatus.UNKNOWN,
            llm = LlmStatus.Unknown,
            pending = emptyList(),
        )
    }
}

/**
 * 判定主函数（§3.1）：
 *
 * ```
 * 模型损坏            -> ERROR
 * 模型缺失            -> NOT_READY
 * 有待处理项          -> PARTIAL
 * 否则                -> READY
 * ```
 *
 * 注意：LLM 读取失败属于 L3 待处理项，只让状态变 PARTIAL，不判 L1 异常。
 */
fun evaluateIntelligence(
    readings: IntelligenceReadings,
    busyOverride: IntelligenceBusy? = null,
    busyPct: Int? = null,
    busyLabel: String? = null,
    busyDone: Int? = null,
    busyTotal: Int? = null,
): IntelligenceStatus {
    val model = when {
        readings.modelFileBroken -> ModelStatus.Corrupt
        !readings.modelName.isNullOrBlank() -> ModelStatus.Ready(readings.modelName.orEmpty())
        else -> ModelStatus.Missing
    }

    val spaces = when {
        readings.spaceCount < 0 -> SpaceStatus.Unknown
        readings.spaceCount == 0 -> SpaceStatus.None
        else -> SpaceStatus.Ready(readings.spaceCount)
    }

    val gpu = when (readings.gpuMode?.lowercase()) {
        "auto" -> GpuStatus.AUTO
        "on" -> GpuStatus.ON
        "off" -> GpuStatus.OFF
        else -> GpuStatus.UNKNOWN
    }

    val llm = when {
        !readings.llmReadSucceeded -> LlmStatus.Unknown
        readings.llmLoadError -> LlmStatus.LoadError
        readings.llmConfigured -> LlmStatus.Configured
        else -> LlmStatus.NotConfigured
    }

    // 顺序即展示顺序：数据质量 > 抽取 > 索引 > 授权 > LLM
    val pending = buildList {
        if (readings.legacySpaceCount > 0) add(PendingItem.LEGACY_SPACES)
        if (readings.extractionFailedCount > 0) add(PendingItem.EXTRACTION_FAILED)
        if (readings.indexUnhealthy) add(PendingItem.INDEX_UNHEALTHY)
        if (readings.storePermissionLost) add(PendingItem.STORE_PERMISSION_LOST)
        if (readings.llmLoadError) add(PendingItem.LLM_LOAD_ERROR)
    }

    val level = when {
        model is ModelStatus.Corrupt -> IntelligenceLevel.ERROR
        model is ModelStatus.Missing -> IntelligenceLevel.NOT_READY
        pending.isNotEmpty() -> IntelligenceLevel.PARTIAL
        else -> IntelligenceLevel.READY
    }

    // 共享 Flow 上报的下载/同步优先于 DB 抽取计数；都没有则 NONE
    val busy = when {
        busyOverride == IntelligenceBusy.DOWNLOADING -> IntelligenceBusy.DOWNLOADING
        busyOverride == IntelligenceBusy.SYNCING -> IntelligenceBusy.SYNCING
        readings.extracting -> IntelligenceBusy.EXTRACTING
        else -> IntelligenceBusy.NONE
    }

    return IntelligenceStatus(
        level = level,
        busy = busy,
        busyPct = busyPct?.coerceIn(0, 100),
        model = model,
        spaces = spaces,
        gpu = gpu,
        llm = llm,
        pending = pending,
        legacySpaceCount = readings.legacySpaceCount.coerceAtLeast(0),
        extractionFailedCount = readings.extractionFailedCount.coerceAtLeast(0),
        modelFileCount = readings.modelFileCount,
        chunkCount = readings.chunkCount,
        storeNotConfigured = readings.storeNotConfigured,
        sagEnabled = readings.sagEnabled,
        networkDisabled = readings.networkDisabled,
        answerConsentGranted = readings.answerConsentGranted,
        busyLabel = busyLabel,
        busyDone = busyDone,
        busyTotal = busyTotal,
    )
}
