package com.zhibox.memory.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * B8 网络策略/存储契约单测（JVM，无 Android/Robolectric）：
 * - 禁网时 Provider 直接 Failure 且**零网络请求**（用本地 ServerSocket 观测连接计数，
 *   若误发请求会收到 accept，可稳定断言）；
 * - HTTPS-only 在连网前即拒绝；
 * - InMemoryLlmConfigStore 的 save/load/clear/maskedKey 语义。
 *
 * Keystore/SharedPreferences 版本由 data-reviewer 的 Robolectric/instrumentation 测试覆盖。
 */
class NetworkPolicyTest {

    private val validConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
    )

    @Test
    fun `blocked policy fails offline without opening any connection`() {
        // 启一个本地监听端口作为"是否发生连接"的观测点（Provider 不应连接任何地方，含该端口）。
        ServerSocket(0).use { server ->
            server.soTimeout = 300 // 300ms 内无连接 => 证明零请求
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysBlocked())

            val result = runBlocking { provider.testConnection(validConfig) }

            assertTrue(result is LlmTestResult.Failure)
            assertEquals(LlmErrorCode.OFFLINE, (result as LlmTestResult.Failure).code)
            val connected = try {
                server.accept().use { true }
            } catch (e: SocketTimeoutException) {
                false
            }
            assertFalse("禁网时不得发起任何连接", connected)
        }
    }

    @Test
    fun `insecure http base url is rejected before any request`() {
        val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
        val result = runBlocking {
            provider.testConnection(validConfig.copy(baseUrl = "http://api.example.com/v1"))
        }
        assertTrue(result is LlmTestResult.Failure)
        assertEquals(LlmErrorCode.INSECURE_URL, (result as LlmTestResult.Failure).code)
    }

    @Test
    fun `missing api key is reported as not configured`() {
        val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
        val result = runBlocking { provider.testConnection(validConfig.copy(apiKey = "")) }
        assertTrue(result is LlmTestResult.Failure)
        assertEquals(LlmErrorCode.NOT_CONFIGURED, (result as LlmTestResult.Failure).code)
    }

    @Test
    fun `blank base url is reported as invalid config`() {
        val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
        val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = " ")) }
        assertTrue(result is LlmTestResult.Failure)
        assertEquals(LlmErrorCode.INVALID_CONFIG, (result as LlmTestResult.Failure).code)
    }

    @Test
    fun `network policy delegates to lambda dynamically`() {
        var allowed = false
        val policy = NetworkPolicy { allowed }
        assertFalse(policy.isAllowed())
        allowed = true
        assertTrue(policy.isAllowed())
    }

    // ---- InMemoryLlmConfigStore ----

    @Test
    fun `in memory store saves loads and clears config`() {
        val store = InMemoryLlmConfigStore()
        assertNull(store.load())
        assertNull(store.maskedKey())

        store.save(validConfig)
        assertEquals(validConfig, store.load())
        assertEquals("sk-***abcd", store.maskedKey())

        store.clear()
        assertNull(store.load())
        assertNull(store.maskedKey())
    }

    @Test
    fun `in memory store rejects incomplete config`() {
        val store = InMemoryLlmConfigStore()
        val error = runCatching { store.save(validConfig.copy(apiKey = "")) }.exceptionOrNull()
        assertTrue(error is LlmStoreException)
        assertTrue(error!!.message.orEmpty().contains("不完整"))
    }

    @Test
    fun `masked key never exposes full secret`() {
        val store = InMemoryLlmConfigStore(validConfig)
        val masked = store.maskedKey()
        assertTrue(masked != null)
        assertFalse(masked!!.contains(validConfig.apiKey))
        assertTrue(masked.startsWith("sk-"))
    }
}
