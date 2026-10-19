package com.zhibox.memory.core.sag

/**
 * B10：SAG 检索核心数据契约（Retriever / UI / LLM 选择器 / 测试共用）。
 *
 * 分层：
 * - [RetrievalMode]：三种检索模式（vector 纯向量基线 / multi_es_fast 离线事件层 / multi 叠加 LLM）；
 * - [SagQuery]/[SagBudget]：请求与预算；
 * - [QueryEntity]/[SagHit]/[SagTrace]/[SagResult]：实体种子、命中、轨迹与结果。
 *
 * 约定：本文件只放纯数据（不依赖 Android/DB），可被 core/llm、UI、单元测试直接引用。
 */

/** 检索模式（wire 为持久化/query_trace/评测 JSON 中的稳定字符串，禁止随意改名） */
enum class RetrievalMode(val wire: String) {
    /** 纯向量 + 关键词融合（B9 之前的离线检索，SAG 不可用时的基线/降级目标） */
    VECTOR("vector"),

    /** 离线 SAG：事件向量种子 + 实体词法种子 + event_entity 1 跳扩展 + 确定性融合（零网络） */
    MULTI_ES_FAST("multi_es_fast"),

    /** SAG + 远程 LLM：LLM 抽取查询实体 / LLM 选择上下文（需显式开启 SAG 与联网） */
    MULTI("multi"),
    ;

    companion object {
        private val byWire: Map<String, RetrievalMode> = values().associateBy { it.wire }

        /** 未知 wire 返回 null（读取路径原样展示/回退，不抛异常） */
        fun fromWire(raw: String?): RetrievalMode? = raw?.let(byWire::get)
    }
}

/**
 * 检索预算（默认值即 MVP 生产值；各阶段硬上限，防止实体扩展/候选放大不受控）。
 *
 * - [kPool]：向量通道候选池（SearchService topK 基数）；
 * - [kSeed]：事件向量种子数 + 实体种子数上限；
 * - [frontier]：实体 1 跳扩展后进入候选的事件上限（SQL LIMIT/合并截断）；
 * - [kCand]：融合排序后的候选事件上限；
 * - [kEvent]：最终选中的事件数上限（送入 context / 映射 chunk）；
 * - [kOut]：最终输出 chunk 命中上限。
 */
data class SagBudget(
    val kPool: Int = 50,
    val kSeed: Int = 50,
    val frontier: Int = 50,
    val kCand: Int = 100,
    val kEvent: Int = 5,
    val kOut: Int = 10,
) {
    init {
        require(kPool > 0 && kSeed > 0 && frontier > 0 && kCand > 0 && kEvent > 0 && kOut > 0) {
            "SagBudget 各预算必须为正数：$this"
        }
    }
}

/** B12：设备/用户预算档位（PRD §7.2；高/中/低端设备可切换，默认 HIGH）。 */
enum class SagBudgetTier(val wire: String, val budget: SagBudget) {
    HIGH("high", SagBudget(kPool = 500, kSeed = 50, frontier = 50, kCand = 100, kEvent = 5, kOut = 10)),
    MID("mid", SagBudget(kPool = 300, kSeed = 30, frontier = 30, kCand = 60, kEvent = 4, kOut = 10)),
    LOW("low", SagBudget(kPool = 150, kSeed = 20, frontier = 20, kCand = 40, kEvent = 3, kOut = 10)),
    ;

    companion object {
        fun fromWire(value: String?): SagBudgetTier =
            entries.firstOrNull { it.wire == value } ?: HIGH
    }
}

/**
 * 单次检索请求。
 *
 * @param query 原始查询（Retriever 内部做 TextNormalizer + 截断，不在此处预处理）
 * @param mode 请求模式；失败/不可用时 [SagResult.effectiveMode] 可能降级为 VECTOR
 * @param topK 输出命中上限（与 budget.kOut 取小）
 * @param sourceIds 来源范围过滤（空=全库；匹配 document.sourceId）
 * @param budget 各阶段预算
 */
data class SagQuery(
    val query: String,
    val mode: RetrievalMode,
    val topK: Int = 10,
    val sourceIds: List<String> = emptyList(),
    val budget: SagBudget = SagBudget(),
)

/**
 * 查询实体种子（检索期的实体候选，不落库）。
 *
 * `id` 语义（B10 契约）：
 * - `id > 0`：已绑定 DB `entity.id`（[LexicalQueryEntityExtractor] 的精确/FTS 命中）；
 * - `id <= 0L`（约定 **0L**）：**尚未绑定 DB 实体**（LLM 抽取产物，或词法候选未回查到行）。
 *   Retriever 会用 [normalizedName] 回查 `EntityDao.byNormalizedName` 完成绑定；绑定失败则丢弃
 *   （不参与 event_entity 扩展，但可用于 trace/调试）。
 *
 * @param type `entity.entityType` wire（如 person/time；未知可为 null）
 * @param name 展示名（原文大小写）
 * @param normalizedName 归一化去重键（NFC + trim + lowercase + 连续空白合一，与 entity 表一致）
 * @param score 种子置信度（0..1，降序使用；词法精确=1.0，FTS 按名次衰减；LLM 由其自报）
 * @param source 种子来源（"exact" / "entity_fts" / "llm"）
 */
data class QueryEntity(
    val id: Long,
    val type: String?,
    val name: String,
    val normalizedName: String,
    val score: Double,
    val source: String,
)

/**
 * SAG 命中（chunk 级；事件层命中也映射回 chunk，保证证据可回溯）。
 *
 * 必选字段与 B9 的 SearchHit 对齐（chunk → document → source + 原文定位）；
 * 事件层额外携带 [eventId]/[entityIds]/[channels]，以及供 LLM selector / 展示使用的最小上下文
 * [eventTitle]/[eventSummary]/[entityNames]（均为空串/空列表表示无事件层信息）。
 *
 * @param score 主排序分（0..1 归一化口径由 Retriever 决定；向量=余弦，事件=融合分）
 * @param fusedScore 融合分（多通道时给 UI/评测展示；null=单通道/未融合）
 * @param channels 命中所经通道，取值见 [SagChannels]
 */
data class SagHit(
    val chunkId: Long,
    val documentId: String,
    val sourceId: String,
    val ordinal: Int,
    val content: String,
    val charStart: Int,
    val charEnd: Int,
    val docTitle: String,
    val sourceTitle: String,
    val sourceUri: String,
    val eventId: Long? = null,
    val entityIds: List<Long> = emptyList(),
    val channels: Set<String> = emptySet(),
    val score: Double = 0.0,
    val fusedScore: Double? = null,
    /** 事件短标题（LLM selector 的最小上下文；无事件层为空串） */
    val eventTitle: String = "",
    /** 事件一句话摘要（同上） */
    val eventSummary: String = "",
    /** 事件关联实体名（展示/selector 用；与 entityIds 同序，无则空列表） */
    val entityNames: List<String> = emptyList(),
)

/**
 * 通道名（SagHit.channels 取值；UI 展示/评测统计共用，**字符串值固定**，禁止各写自由字符串）。
 *
 * 稳定值（与已落盘的 `SagModelsTest` 锁定一致）：`vector` / `keyword` / `event_vector` /
 * `event_entity` / `semantic_fill` / `llm_selected`。
 *
 * 语义映射（Lead 口径）：event_vector=事件向量种子通道、event_entity=实体扩展通道、
 * semantic_fill=语义补位、llm_selected=multi 的 LLM 选中标记。
 */
object SagChannels {
    /** 纯向量通道（SearchService） */
    const val VECTOR: String = "vector"

    /** 关键词通道（SearchService FTS bigram） */
    const val KEYWORD: String = "keyword"

    /** 事件向量种子通道（sag_vector role=event_content 余弦） */
    const val EVENT_VECTOR: String = "event_vector"

    /** 实体扩展通道（query entity → event_entity 1 跳，含 incidence weight） */
    const val EVENT_ENTITY: String = "event_entity"

    /** 语义补位通道（事件层选出 chunk 后，用 SearchService/网关补齐到 kOut） */
    const val SEMANTIC_FILL: String = "semantic_fill"

    /** LLM selector 选中标记（multi 模式） */
    const val LLM_SELECTED: String = "llm_selected"

    /**
     * R2 复审修复（P2-2）：桥接救援标记——命中分数被提升到 `BRIDGE_RESCUE_SCORE` 的候选
     * 携带本通道值，UI/trace/评测可区分"真实融合分"与"救援提升分"（不再仅靠分数不可解释地偏高）。
     */
    const val BRIDGE_RESCUE: String = "bridge_rescue"
}

/** 轨迹阶段（name/count/ms 即可；不记录正文/实体名以外敏感信息） */
data class SagTraceStage(
    val name: String,
    val count: Int,
    val ms: Long,
)

/**
 * 单次检索轨迹（写入 `query_trace.timingJson` 的诊断数据；**不含 query 正文**）。
 *
 * @param mode 请求模式 wire（trace 记录"用户要什么"，effectiveMode 在 [SagResult] 里给出实际模式）
 * @param queryHash query 文本 SHA-256 hex（不落正文）
 * @param stages 各阶段（名称/计数/耗时），按执行顺序
 * @param seedEvents 事件向量种子数
 * @param seedEntities 查询实体种子数
 * @param expandedEvents 实体 1 跳扩展得到的事件数（截断前）
 * @param candidates 融合后候选事件数（kCand 内）
 * @param selected 最终选中/输出命中数
 * @param degradedReason 非空=发生降级（事件层不可用/预算截断/LLM 回退等，可读原因，不含正文）
 */
data class SagTrace(
    val mode: String,
    val queryHash: String,
    val stages: List<SagTraceStage>,
    val seedEvents: Int,
    val seedEntities: Int,
    val expandedEvents: Int,
    val candidates: Int,
    val selected: Int,
    val degradedReason: String?,
    val elapsedMs: Long,
)

/**
 * 检索结果。
 *
 * @param refused 非空=拒绝/失败（用户可读原因；此时 hits 通常为空，但仍写 trace）
 * @param degraded 是否发生能力降级（含 effectiveMode != mode 的降级与预算/LLM 回退）
 * @param effectiveMode 实际执行模式（multi/multi_es_fast 降级时=VECTOR）
 */
data class SagResult(
    val query: String,
    val mode: RetrievalMode,
    val hits: List<SagHit>,
    val trace: SagTrace,
    val refused: String?,
    val degraded: Boolean,
    val effectiveMode: RetrievalMode,
)
