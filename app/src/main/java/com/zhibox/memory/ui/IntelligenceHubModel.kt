package com.zhibox.memory.ui

/**
 * 智能状态中枢的**纯逻辑 resolver**（M4 A/B：空态 + 待处理行动作）。
 *
 * 规格：`docs/product/INTELLIGENCE_HUB_SPEC.md` §4（E1–E15 空态/异常矩阵与优先级）、
 * §6.3–§6.5（面板信息架构/深链）、§8.4（空态文案）。
 *
 * 本文件**不依赖 Android**（不引用任何 android / androidx 类型），只把
 * [IntelligenceStatus] 快照解析成「首屏关键行 + 附加 pill」两类展示指令，
 * 便于纯 JVM 单测锁定优先级，UI 层只负责按结果渲染与文案映射。
 *
 * 判定顺序严格按 §4「空态组合判定顺序」：`E1 > E2 > E15 > E12 > E13 > E3 > 正常`。
 * 注意：E12/E13 是"进行中"状态，仍排在 E3 之前——干着活时先报进度，不报空库。
 */

/**
 * 面板首屏「关键行」的互斥状态（§4 矩阵）。
 *
 * - [E1] 全新安装、全空（缺模型且没有任何候选模型文件）；
 * - [E2] 缺模型但已检测到候选模型文件；
 * - [E15] 模型文件损坏；
 * - [E12] 模型下载中（带进度）；
 * - [E13] 旧数据重嵌入同步中（带进度）；
 * - [E3] 模型就绪但知识库为空（chunkCount == 0）；
 * - [NORMAL] 无需空态/进行中提示，按四态结论展示即可。
 */
enum class HubPrimary { E1, E2, E15, E12, E13, E3, NORMAL }

/**
 * 解析首屏关键行（§4 优先级，自上而下短路，先命中者生效）。
 *
 * 数据不可知时的降级（§10.3/§10.4）：
 * - [IntelligenceStatus.modelFileCount] == -1（读取失败）时既不算 E1 也不算 E2，
 *   避免把"读不到文件数"伪装成"全新安装"；
 * - [IntelligenceStatus.chunkCount] == -1 时不算 E3。
 */
fun resolvePrimary(status: IntelligenceStatus): HubPrimary {
    val model = status.model
    return when {
        // E1：缺模型 + 候选文件数为 0（计数未知 -1 不伪装成全空）
        model is ModelStatus.Missing && status.modelFileCount == 0 -> HubPrimary.E1
        // E2：缺模型但已有候选模型文件（权限/IO 导致 resolve 失败的真实异常路径）
        model is ModelStatus.Missing && status.modelFileCount > 0 -> HubPrimary.E2
        // E15：模型文件损坏优先于"下载中"（损坏要先重新下载，进度行在此场景没有意义）
        model is ModelStatus.Corrupt -> HubPrimary.E15
        // E12/E13：进行中任务（顺序固定，下载 > 同步）
        status.busy == IntelligenceBusy.DOWNLOADING -> HubPrimary.E12
        status.busy == IntelligenceBusy.SYNCING -> HubPrimary.E13
        // E3：模型就绪且知识库为空（用 chunk 数判定，与"有数据但无向量空间"区分）
        model is ModelStatus.Ready && status.chunkCount == 0 -> HubPrimary.E3
        else -> HubPrimary.NORMAL
    }
}

/**
 * 附加信息 pill（§4 E4/E8/E14）。
 *
 * 纯数据 + [Kind]，文案由 UI 层映射到既有 `settings_*` / 新增 `intelligence_*` 字符串，
 * 保证本文件不出现字面量、也不依赖 Android 资源。
 */
/**
 * 抽取失败「重试」是否可用（§6.4 就地幂等动作的前提）。
 *
 * 与 `SagAvailability.isAvailable` 的用户可见口径一致：LLM 已配置、SAG 增强已开启、
 * 未开启「禁止网络」。三者任一不满足时重试是空操作——面板应改为「去配置」深链，
 * 避免点了没反应。
 */
fun canRetryExtraction(status: IntelligenceStatus): Boolean =
    status.llm is LlmStatus.Configured &&
        status.sagEnabled == true &&
        status.networkDisabled == false

data class HubInfoPill(val kind: Kind) {
    enum class Kind {
        /** E4：共享目录未配置（模型存于应用私有目录）。 */
        STORE_NOT_CONFIGURED,

        /** E8：SAG 增强关闭。 */
        SAG_DISABLED,

        /** E8：禁止网络开启。 */
        NETWORK_DISABLED,

        /** E14：问答首次联网隐私说明未同意。 */
        ANSWER_CONSENT_REQUIRED,
    }
}

/**
 * 解析附加 pill 列表（§4 E4/E8/E14，顺序即展示顺序）。
 *
 * 只读 pill，不改变底色/四态；读取失败（`null`）按「未知」不展示（§10.3 降级原则）。
 * E8 的两个条件互相独立，可同时出现两个 pill。
 */
fun infoPills(status: IntelligenceStatus): List<HubInfoPill> = buildList {
    if (status.storeNotConfigured) {
        add(HubInfoPill(HubInfoPill.Kind.STORE_NOT_CONFIGURED))
    }
    if (status.sagEnabled == false) {
        add(HubInfoPill(HubInfoPill.Kind.SAG_DISABLED))
    }
    if (status.networkDisabled == true) {
        add(HubInfoPill(HubInfoPill.Kind.NETWORK_DISABLED))
    }
    if (status.answerConsentGranted == false) {
        add(HubInfoPill(HubInfoPill.Kind.ANSWER_CONSENT_REQUIRED))
    }
}
