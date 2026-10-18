package com.zhibox.memory.core.llm

/**
 * 远程 LLM（OpenAI-compatible）连接配置（B8）。
 *
 * 安全约定（与 PRD FR-08 / B8 门禁"密钥不入库/日志/备份"对应）：
 * - [apiKey] 只允许存在于内存与 [LlmConfigStore] 的 Keystore 加密存储中；
 * - [toString] 已对 apiKey 脱敏——**不要**绕过它把本对象直接拼进日志/错误信息/备份；
 * - 本对象不进 KnowledgeBackup（备份只含数据库），也不进系统备份（全局 `allowBackup=false`）。
 *
 * @param baseUrl API 根地址，必须 `https://` 开头；Provider 会拼接 `/chat/completions`，
 *   例如 OpenAI 为 `https://api.openai.com/v1`，不要自带 `/chat/completions`。
 * @param model 模型名（如 `gpt-4o-mini` / 自建服务暴露的模型标识）。
 * @param apiKey 服务端 API Key（建议使用最小权限、可撤销的 key）。
 * @param timeoutSeconds 连接/读取超时（秒），限制在 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]。
 * @param maxTokens 生成上限（连接测试不用它，留给 B11 问答；此处仍持久化用户偏好）。
 */
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    /** 脱敏后的安全字符串（可安全写日志/异常；仍不包含 key 明文）。 */
    override fun toString(): String =
        "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=${maskSecret(apiKey)}, " +
            "timeoutSeconds=$timeoutSeconds, maxTokens=$maxTokens)"

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Int = 30
        const val DEFAULT_MAX_TOKENS: Int = 256
        const val MIN_TIMEOUT_SECONDS: Int = 1
        const val MAX_TIMEOUT_SECONDS: Int = 120
        const val MIN_MAX_TOKENS: Int = 1
        const val MAX_MAX_TOKENS: Int = 8192

        /**
         * 密钥脱敏：长 key 保留前 3 + 后 4（形如 `sk-***abcd`），短 key 全部打码。
         * UI（maskedKey）与日志安全展示统一复用此实现，避免各处脱敏口径不一致。
         */
        fun maskSecret(secret: String): String = when {
            secret.isEmpty() -> ""
            secret.length < 16 -> "****"  // 短 key 不露任何字符，避免掩码泄露有效信息
            else -> secret.take(3) + "***" + secret.takeLast(4)
        }
    }
}
