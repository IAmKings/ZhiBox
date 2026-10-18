package com.zhibox.memory.core.sag

/**
 * B10：查询实体抽取抽象（检索期把自然语言 query 映射为实体种子）。
 *
 * 实现：
 * - [LexicalQueryEntityExtractor]：离线词法（entity_fts + normalizedName 精确匹配，零网络）；
 * - `core/llm` 的 LLM 实现（B10-LLM）：远程抽取，`id=0L`（未绑定 DB），Retriever 按
 *   normalizedName 回查绑定。
 *
 * 契约：
 * - suspend（IO/网络），CancellationException 向上传播；
 * - 任何失败返回空列表（不得抛业务异常让检索崩溃）；
 * - 不记录 query/正文/密钥到日志（项目约定：core/sag 不写日志）；
 * - normalizedName 必须与 entity 表口径一致（NFC + trim + lowercase + 连续空白合一）。
 */
interface QueryEntityExtractor {
    suspend fun extract(query: String): List<QueryEntity>
}

/**
 * B10：SAG 上下文选择器（从候选事件/命中中选出最终上下文，供 multi 模式或未来的精排使用）。
 *
 * 实现：
 * - [DeterministicSagSelector]：离线确定性排序（默认，零网络）；
 * - `core/llm` 的 LLM 实现（B10-LLM）：远程选择，失败/超时/禁网→降级返回前 K 候选
 *   （不得抛业务异常让检索崩溃；CancellationException 向上传播）。
 *
 * 契约：
 * - 返回候选的**子集**（顺序即选择顺序）；可少于 [candidates]；
 * - 不得返回候选之外的新 [SagHit]（Retriever 会按 chunkId 过滤，防御性处理）；
 * - Retriever 负责再按 `SagBudget.kEvent` 截断与映射 chunk。
 */
interface SagContextSelector {
    suspend fun select(query: String, candidates: List<SagHit>): List<SagHit>
}


/**
 * B10 测试网关（internal，非公开契约）：查询向量注入。
 *
 * 生产默认 null → [SagRetriever] 走 `EmbeddingRuntime.withProvider { embedQuery }`；
 * 测试（instrumentation 无模型资产）注入可控向量；返回 null 表示"事件层不可用"（触发降级）。
 */
internal fun interface QueryEmbeddingGateway {
    suspend fun embedQuery(text: String): FloatArray?
}

/**
 * B10 测试网关（internal，非公开契约）：向量语义补位注入（默认走真实 [com.zhibox.memory.core.search.SearchService]）。
 *
 * 调用方保证 `excludeChunkIds` 不重复出现；实现返回的 hits 会被 Retriever 再按 exclude 过滤。
 */
internal fun interface VectorSupplementGateway {
    suspend fun supplement(
        query: String,
        topK: Int,
        excludeChunkIds: Set<Long>,
        sourceIds: List<String>,
    ): List<com.zhibox.memory.core.search.SearchService.SearchHit>
}
