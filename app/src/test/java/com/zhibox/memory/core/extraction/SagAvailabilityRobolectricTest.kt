package com.zhibox.memory.core.extraction

import com.zhibox.memory.core.llm.InMemoryLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.llm.LlmStoreException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B9 SAG 可用性判定（JVM Robolectric，真实 SharedPreferences）：
 * 可用 = SAG 开关 && 允许联网 && 已保存可用 LLM 配置；任一缺失/配置读取异常 → false。
 * 用 `SagAvailability.storeOverrideForTests` 注入内存 store，避免 Robolectric 下的 AndroidKeyStore。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SagAvailabilityRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val config = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
    )

    @Before
    fun setUp() {
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(app, false)
        LlmPreferences.setNetworkAllowed(app, false)
    }

    @After
    fun tearDown() {
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(app, false)
        LlmPreferences.setNetworkAllowed(app, false)
    }

    @Test
    fun defaultsAreUnavailableOffline() {
        assertFalse("默认 SAG 关闭 + 禁网 → 不可用", SagAvailability.isAvailable(app))
    }

    @Test
    fun requiresSagEnabledNetworkAllowedAndConfig() {
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(config)

        LlmPreferences.setSagEnabled(app, true)
        assertFalse("未允许联网不可用", SagAvailability.isAvailable(app))

        LlmPreferences.setNetworkAllowed(app, true)
        assertTrue("三条件齐备应可用", SagAvailability.isAvailable(app))

        LlmPreferences.setSagEnabled(app, false)
        assertFalse("SAG 关闭不可用", SagAvailability.isAvailable(app))
    }

    @Test
    fun missingConfigIsUnavailable() {
        LlmPreferences.setSagEnabled(app, true)
        LlmPreferences.setNetworkAllowed(app, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore()
        assertFalse("未保存 LLM 配置 → 不可用", SagAvailability.isAvailable(app))
    }

    @Test
    fun brokenStoreIsTreatedAsUnavailableWithoutThrowing() {
        LlmPreferences.setSagEnabled(app, true)
        LlmPreferences.setNetworkAllowed(app, true)
        SagAvailability.storeOverrideForTests = object : LlmConfigStore {
            override fun load(): LlmConfig? = throw LlmStoreException("Keystore 密钥失效")
            override fun save(config: LlmConfig) = Unit
            override fun clear() = Unit
            override fun maskedKey(): String? = null
        }
        assertFalse("配置读取异常按不可用处理", SagAvailability.isAvailable(app))
    }
}
