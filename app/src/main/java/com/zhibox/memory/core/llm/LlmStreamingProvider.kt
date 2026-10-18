package com.zhibox.memory.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * B11：远程 LLM 流式生成抽象（OpenAI-compatible `/chat/completions` SSE）。
 *
 * 与 [LlmCompletionProvider] 相同的安全/失败约定：禁网零请求、仅 HTTPS、错误以值返回、
 * 不记录 key 与正文、`CancellationException` 原样重抛、取消时关闭连接。
 *
 * 流事件语义：
 * - [LlmStreamEvent.Delta] 可以多次出现，按到达顺序拼接为最终文本；
 * - 正常结束必须恰好发一次 [LlmStreamEvent.Completed]；
 * - 失败只发 [LlmStreamEvent.Failed] 并结束；两者不会同时出现；
 * - 取消由调用方取消 Flow，不要求发事件。
 */
interface LlmStreamingProvider {
    /**
     * 发起流式生成；调用方收集 Flow 即开始请求，取消收集即停止并关闭连接。
     *
     * @param config 连接配置（通常来自 [LlmConfigStore]）
     * @param systemPrompt 系统提示词（角色/引用规则/拒答规则）
     * @param userPrompt 用户消息（query + 证据原文）
     * @param maxTokens 输出 token 上限
     */
    fun stream(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = DEFAULT_STREAMING_MAX_TOKENS,
    ): Flow<LlmStreamEvent>

    companion object {
        const val DEFAULT_STREAMING_MAX_TOKENS: Int = 1024
    }
}

/** B11：流式 LLM 事件（值语义；不抛业务异常）。 */
sealed interface LlmStreamEvent {
    /** 一个增量文本片段（可能为空片段，调用方应过滤空白）。 */
    data class Delta(val text: String) : LlmStreamEvent

    /** 正常结束；[model] 为服务端返回/回退配置模型。 */
    data class Completed(val model: String, val latencyMs: Long) : LlmStreamEvent

    /** 失败；[code] 复用 [LlmErrorCode]，[message] 可读且不含密钥/正文。 */
    data class Failed(val code: String, val message: String) : LlmStreamEvent
}
