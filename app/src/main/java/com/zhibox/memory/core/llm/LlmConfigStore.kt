package com.zhibox.memory.core.llm

/**
 * LLM 配置存储失败（可读文案）。
 *
 * 约定：message 面向用户/日志可读，**不得包含 API Key 明文**；[cause] 仅用于开发排查。
 * UI 建议 `runCatching { store.load() }.onFailure { 展示 it.message }`，
 * 不清空用户数据、不崩溃。
 */
class LlmStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * LLM 配置的安全存储抽象（B8）。
 *
 * 生产实现：[KeystoreLlmConfigStore]（Android Keystore AES/GCM 加密 API Key）；
 * 测试/预览实现：[InMemoryLlmConfigStore]。
 *
 * 线程约定：本接口为同步轻量 API（SharedPreferences + 一次对称加解密），
 * ViewModel/调用方应在 `Dispatchers.IO` 中调用；实现内部已做同步保护。
 */
interface LlmConfigStore {
    /**
     * 读取已保存配置；未配置返回 null。
     *
     * @throws LlmStoreException 存储数据损坏/Keystore 密钥失效等（message 可读）。
     */
    fun load(): LlmConfig?

    /**
     * 保存配置（覆盖式）。baseUrl/model/apiKey 任一为空视为不完整并抛 [LlmStoreException]。
     *
     * @throws LlmStoreException 本地写入或加密失败（message 可读）。
     */
    fun save(config: LlmConfig)

    /** 清除已保存配置（含加密密钥材料；不删除 Keystore 中不可恢复的 key 也不影响后续重建）。 */
    fun clear()

    /**
     * 供 UI 展示的脱敏 key（形如 `sk-***abcd`）；未配置或无法解密时返回 null。
     * 永不返回明文。
     */
    fun maskedKey(): String?
}
