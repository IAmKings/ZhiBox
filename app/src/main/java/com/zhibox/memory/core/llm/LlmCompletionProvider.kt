package com.zhibox.memory.core.llm

/**
 * 远程 LLM 文本生成抽象（B9）：在 [LlmProvider] 连通性测试之外提供 completion。
 *
 * B11 的带引用问答/上下文选择/流式生成会在该接口/实现上继续扩展（当前为非流式一次性返回）。
 *
 * 实现约定（与 [LlmProvider] 一致）：
 * - 禁网时**不发起任何请求**，直接返回 `Failure(LLM_OFFLINE)`；
 * - 仅 HTTPS、超时/错误码/可读错误；不记录 key 与请求/响应正文；
 * - 失败以 [LlmCompletionResult.Failure] 返回值表达，不向调用方抛业务异常
 *   （`CancellationException` 必须原样重抛）。
 */
interface LlmCompletionProvider {
    /**
     * 调用 OpenAI-compatible `/chat/completions` 生成一次文本。
     *
     * @param config 连接配置（通常来自 [LlmConfigStore]）。
     * @param systemPrompt 系统提示词（角色/规则/schema）；不得为空。
     * @param userPrompt 用户消息（待处理内容，如单 chunk 正文包裹）；不得为空。
     * @param maxTokens 生成长度上限（默认 1024；范围 [LlmConfig.MIN_MAX_TOKENS]..[LlmConfig.MAX_MAX_TOKENS]）。
     * @return 成功携带非空白文本；空内容/坏 JSON/缺 choices → [LlmErrorCode.PARSE]。
     */
    suspend fun complete(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = DEFAULT_COMPLETION_MAX_TOKENS,
    ): LlmCompletionResult
}
