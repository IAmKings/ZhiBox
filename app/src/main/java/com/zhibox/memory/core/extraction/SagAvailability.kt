package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmPreferences

/**
 * B9：SAG 抽取可用性判定（唯一事实源）。
 *
 * 可用 = 用户开启 SAG 增强 **且** 允许联网 **且** 已保存可用 LLM 配置。
 * 三者缺一 → 抽取任务置 `not_indexed`（文档仍 ready、离线检索不受影响），
 * 用户补齐配置后由 `SagExtractionScheduler.backfill` 重新入队。
 *
 * 说明：
 * - **不加载端侧嵌入模型**（可用性只看 LLM 配置；模型缺失由 pipeline 执行时以
 *   EMBEDDING_UNAVAILABLE 失败并重试，二者是不同恢复路径）；
 * - 配置读取失败（Keystore 损坏/解密失败）视为不可用，不抛出、不影响导入/检索主链路；
 * - Keystore store 按 applicationContext 缓存，避免每个 chunk 重新初始化 KeyStore。
 */
object SagAvailability {

    @Volatile
    private var cachedStore: LlmConfigStore? = null

    @Volatile
    private var cachedStoreContext: Context? = null

    /**
     * 测试注入（`@VisibleForTesting`，生产路径保持 null）：data-reviewer 在 Robolectric 中
     * 用 `InMemoryLlmConfigStore` 构造"已配置"状态，避免依赖不可用的 AndroidKeyStore。
     */
    @Volatile
    @VisibleForTesting
    internal var storeOverrideForTests: LlmConfigStore? = null

    /** @return true=可执行远程抽取；false=应把任务置 not_indexed（文档仍 ready） */
    fun isAvailable(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!LlmPreferences.sagEnabled(appContext)) return false
        if (!LlmPreferences.networkAllowed(appContext)) return false
        val store = resolveStore(appContext)
        return try {
            store.load() != null
        } catch (_: Exception) {
            // 配置损坏/Keystore 失效：按"未配置"处理（UI 会引导重新保存 Key），不得中断导入/抽取
            false
        }
    }

    @Synchronized
    private fun resolveStore(appContext: Context): LlmConfigStore {
        storeOverrideForTests?.let { return it }
        val cached = cachedStore
        if (cached != null && cachedStoreContext === appContext) return cached
        return KeystoreLlmConfigStore(appContext).also {
            cachedStoreContext = appContext
            cachedStore = it
        }
    }
}
