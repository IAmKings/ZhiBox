package com.zhibox.memory.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * B8 LLM 开关/网络策略（JVM Robolectric，真实 Android SharedPreferences）：
 * - 隐私默认值：SAG/问答/联网全部 false（**默认禁网**）；
 * - 开关 roundtrip；
 * - `NetworkPolicy.from(context)` 每次调用**实时**读取开关（不缓存）；
 * - 默认禁网下 Provider 返回 [LlmErrorCode.OFFLINE] 且**零网络请求**（本地 ServerSocket 观测）。
 *
 * Keystore 加密存储在 `KeystoreLlmConfigStoreTest`（instrumentation，AndroidKeyStore 在 JVM
 * 不可用；Robolectric 无真实 Keystore provider），见该文件说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LlmPreferencesRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("llm_preferences", android.content.Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultsArePrivacyPreservingAndOffline() {
        assertFalse("SAG 增强默认关闭", LlmPreferences.sagEnabled(app))
        assertFalse("问答默认关闭", LlmPreferences.answerEnabled(app))
        assertFalse("联网默认关闭（默认禁网）", LlmPreferences.networkAllowed(app))
    }

    @Test
    fun togglesRoundTrip() {
        LlmPreferences.setSagEnabled(app, true)
        LlmPreferences.setAnswerEnabled(app, true)
        LlmPreferences.setNetworkAllowed(app, true)
        assertTrue(LlmPreferences.sagEnabled(app))
        assertTrue(LlmPreferences.answerEnabled(app))
        assertTrue(LlmPreferences.networkAllowed(app))

        LlmPreferences.setSagEnabled(app, false)
        LlmPreferences.setAnswerEnabled(app, false)
        LlmPreferences.setNetworkAllowed(app, false)
        assertFalse(LlmPreferences.sagEnabled(app))
        assertFalse(LlmPreferences.answerEnabled(app))
        assertFalse(LlmPreferences.networkAllowed(app))
    }

    @Test
    fun networkPolicyFromReadsPreferenceRealtime() {
        val policy = NetworkPolicy.from(app)
        assertFalse("默认禁网：策略应拒绝", policy.isAllowed())

        LlmPreferences.setNetworkAllowed(app, true)
        assertTrue("打开开关后同一 policy 应立即允许（不缓存）", policy.isAllowed())

        LlmPreferences.setNetworkAllowed(app, false)
        assertFalse("关闭开关后应立即拒绝（不缓存）", policy.isAllowed())
    }

    @Test
    fun defaultOfflineProviderMakesZeroRequests() {
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val policy = NetworkPolicy.from(app)
            val provider = OpenAiCompatibleLlmProvider(policy)
            val config = LlmConfig(
                baseUrl = "https://127.0.0.1:${server.localPort}/v1",
                model = "gpt-4o-mini",
                apiKey = "sk-test-1234567890abcd",
            )
            val result = runBlocking { provider.testConnection(config) }
            assertTrue(result is LlmTestResult.Failure)
            assertEquals(LlmErrorCode.OFFLINE, (result as LlmTestResult.Failure).code)

            val connected = try {
                server.accept().use { true }
            } catch (e: SocketTimeoutException) {
                false
            } catch (e: IOException) {
                false
            }
            assertFalse("默认禁网时不得建立任何连接", connected)
        }
    }

    @Test
    fun networkAllowedStillRejectsInsecureHttpWithoutRequest() {
        LlmPreferences.setNetworkAllowed(app, true)
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.from(app))
            val config = LlmConfig(
                baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                model = "gpt-4o-mini",
                apiKey = "sk-test-1234567890abcd",
            )
            val result = runBlocking { provider.testConnection(config) }
            assertTrue(result is LlmTestResult.Failure)
            assertEquals(LlmErrorCode.INSECURE_URL, (result as LlmTestResult.Failure).code)

            val connected = try {
                server.accept().use { true }
            } catch (e: SocketTimeoutException) {
                false
            } catch (e: IOException) {
                false
            }
            assertFalse("非 HTTPS 必须在联网前拒绝（零请求）", connected)
        }
    }
}
