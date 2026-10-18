package com.zhibox.memory.core.llm

/**
 * 单次 LLM 连通性测试结果（B8）。
 *
 * 约定：`testConnection` 不抛异常，失败以 [Failure] 值返回（错误处理规范：失败以值跨层）。
 * [Failure.message] 为中文可读文案，**不得包含 API Key 或请求/响应正文**；
 * 排查时用 [Failure.readableMessage]（`message（code）`）即可，UI 可直接展示。
 */
sealed interface LlmTestResult {
    /** 连通成功：[latencyMs] 为端到端耗时（毫秒），[model] 为服务端返回（缺省回退配置的模型名）。 */
    data class Success(val latencyMs: Long, val model: String) : LlmTestResult

    /** 连通失败：[code] 为稳定错误码（见 [LlmErrorCode]），[message] 为可读原因（不含密钥/正文）。 */
    data class Failure(val code: String, val message: String) : LlmTestResult {
        /** 单行展示文案：`message（code）`；UI 只需展示这个即可。 */
        fun readableMessage(): String = "$message（$code）"
    }

    val isSuccess: Boolean
        get() = this is Success
}

/**
 * LLM 稳定错误码（写入 UI/测试断言；**禁止随意改名**）。
 *
 * 命名约定与导入管线 `CODE:detail` 一致：UI 可按 code 分支给动作建议，
 * 详细原因放在 [LlmTestResult.Failure.message]。
 */
object LlmErrorCode {
    /** 未配置/缺少 API Key。 */
    const val NOT_CONFIGURED: String = "LLM_NOT_CONFIGURED"

    /** 禁网开关关闭（默认）：直接失败，**不发起任何请求**。 */
    const val OFFLINE: String = "LLM_OFFLINE"

    /** baseUrl 不是 HTTPS（隐私/安全要求：仅 HTTPS）。 */
    const val INSECURE_URL: String = "LLM_INSECURE_URL"

    /** 配置字段非法（空白/超时越界/URL 畸形等）。 */
    const val INVALID_CONFIG: String = "LLM_INVALID_CONFIG"

    /** 连接/读取超时。 */
    const val TIMEOUT: String = "LLM_TIMEOUT"

    /** 网络层失败（DNS/连接被拒/TLS 等 IO 异常）。 */
    const val NETWORK: String = "LLM_NETWORK_ERROR"

    /** 服务端返回非 2xx。 */
    const val HTTP: String = "LLM_HTTP_ERROR"

    /** 鉴权失败（HTTP 401/403）。 */
    const val AUTH: String = "LLM_AUTH_ERROR"

    /** 被限流（HTTP 429）。 */
    const val RATE_LIMITED: String = "LLM_RATE_LIMITED"

    /** 响应不是预期的 OpenAI-compatible JSON（缺少 choices 等）。 */
    const val PARSE: String = "LLM_PARSE_ERROR"

    /** 本地配置存储/加解密失败（Keystore）。 */
    const val STORE: String = "LLM_STORE_ERROR"
}
