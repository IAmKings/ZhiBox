package com.zhibox.memory.core.extract

/**
 * B8 抽取器接口（B9 由 Remote LLM 实现 + [ExtractionJson] 解析；测试用
 * [MockEventEntityExtractor]）。
 *
 * 契约：
 * - **suspend**（远程调用/IO 不阻塞主线程）；实现方负责超时与取消传播；
 * - 任何失败都返回 [ExtractionResult.Failure]（稳定错误码 + 可读 message），不得抛出
 *   业务异常让 worker 崩溃；CancellationException 应向上抛（协程取消语义）；
 * - 不得在 message/日志中携带 API key、完整 chunk 正文（隐私/安全）；
 * - 单次调用只处理一个 chunk（1 chunk = 1 event），不要跨 chunk 合并。
 */
interface EventEntityExtractor {
    suspend fun extract(input: ExtractionInput): ExtractionResult
}
