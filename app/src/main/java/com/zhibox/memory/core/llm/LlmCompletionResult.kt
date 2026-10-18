package com.zhibox.memory.core.llm

/**
 * 单次 LLM 文本生成（completion）结果（B9）。
 *
 * 约定与 [LlmTestResult] 一致：失败以值返回、不抛业务异常；
 * [Failure.message] 为中文可读文案，**不得包含 API Key 或请求/响应正文**。
 */
sealed interface LlmCompletionResult {
    /** 生成成功：[text] 为 assistant 输出（已保证非空白），[model] 为服务端返回/回退配置模型。 */
    data class Success(
        val text: String,
        val latencyMs: Long,
        val model: String,
    ) : LlmCompletionResult

    /** 生成失败：[code] 复用 [LlmErrorCode] 稳定错误码，[message] 可读且不含密钥/正文。 */
    data class Failure(val code: String, val message: String) : LlmCompletionResult {
        /** 单行展示文案：`message（code）`；UI/调用方可直接使用。 */
        fun readableMessage(): String = "$message（$code）"
    }

    val isSuccess: Boolean
        get() = this is Success
}
