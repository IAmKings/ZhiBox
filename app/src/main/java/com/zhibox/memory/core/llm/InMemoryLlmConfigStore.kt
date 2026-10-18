package com.zhibox.memory.core.llm

/**
 * 内存版 [LlmConfigStore]（B8）：测试/Compose Preview/临时会话用。
 *
 * 行为与生产实现保持一致（未配置返回 null、maskedKey 脱敏、不写磁盘），
 * 但**不提供跨进程/重启持久化**，也不做加密（数据只在内存）。
 */
class InMemoryLlmConfigStore(initial: LlmConfig? = null) : LlmConfigStore {
    private var config: LlmConfig? = initial

    @Synchronized
    override fun load(): LlmConfig? = config

    @Synchronized
    override fun save(config: LlmConfig) {
        if (config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            throw LlmStoreException("LLM 配置不完整：baseUrl / model / API Key 均不能为空")
        }
        this.config = config
    }

    @Synchronized
    override fun clear() {
        config = null
    }

    @Synchronized
    override fun maskedKey(): String? =
        config?.apiKey?.takeIf { it.isNotBlank() }?.let(LlmConfig::maskSecret)
}
