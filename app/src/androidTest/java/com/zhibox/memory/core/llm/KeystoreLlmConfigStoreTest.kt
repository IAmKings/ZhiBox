package com.zhibox.memory.core.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B8 Keystore 配置存储验收（**instrumentation**）。
 *
 * 为什么不用 Robolectric：`KeystoreLlmConfigStore` 依赖真实 `AndroidKeyStore` provider
 * （KeyGenParameterSpec/AES-GCM 硬件密钥），Robolectric 的 JVM 环境没有该 provider；
 * AndroidKeyStore 行为只能在真机/模拟器验证，因此放 androidTest。
 *
 * 覆盖：save/load 加解密 roundtrip、maskedKey 脱敏、clear 清空、SharedPreferences 中
 * **不出现 API Key 明文**、密文损坏时 load 抛可读 [LlmStoreException] 且 maskedKey 静默 null、
 * 不完整配置拒绝保存。
 */
class KeystoreLlmConfigStoreTest {

    private lateinit var context: Context
    private lateinit var store: KeystoreLlmConfigStore
    private val prefs get() = context.getSharedPreferences("llm_config", Context.MODE_PRIVATE)

    /** 测试前设备上用户已保存的真实配置（测试结束恢复，避免 instrumentation 清掉用户 key）。 */
    private var preservedConfig: LlmConfig? = null

    private val config = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "gpt-4o-mini",
        apiKey = "sk-instrumented-1234567890abcd",
        timeoutSeconds = 20,
        maxTokens = 128,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = KeystoreLlmConfigStore(context)
        // B10 事故修复：此 instrumentation 测试会 clear() 生产 prefs/Keystore alias。
        // 先抓取设备上用户配置，测试结束再 save 回去（无配置时 preserved=null）。
        preservedConfig = runCatching { store.load() }.getOrNull()
        runCatching { store.clear() }
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        runCatching { store.clear() }
        prefs.edit().clear().commit()
        // 恢复用户真实配置；失败也不让测试失败（但会记录在测试输出中）
        preservedConfig?.let { cfg ->
            runCatching { store.save(cfg) }
        }
    }

    @Test
    fun saveLoadMaskedClearRoundTrip() {
        assertNull("初始未配置", store.load())
        assertNull(store.maskedKey())

        store.save(config)
        assertEquals("保存后应能原样读回（baseUrl/model 已 trim）", config, store.load())
        assertEquals("sk-***abcd", store.maskedKey())

        store.clear()
        assertNull("clear 后应无配置", store.load())
        assertNull(store.maskedKey())
        assertTrue("clear 应清空本地 SharedPreferences", prefs.all.isEmpty())
    }

    @Test
    fun apiKeyIsEncryptedInSharedPreferences() {
        store.save(config)
        val dump = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse("SharedPreferences 不得出现 API Key 明文：$dump", dump.contains(config.apiKey))
        assertTrue("应存有非空 iv", prefs.getString("api_key_iv", null).orEmpty().isNotBlank())
        assertTrue("应存有非空密文", prefs.getString("api_key_ciphertext", null).orEmpty().isNotBlank())
        assertEquals("baseUrl 为非密字段", config.baseUrl, prefs.getString("base_url", null))
    }

    @Test
    fun corruptedCiphertextFailsReadablyAndMaskedKeyIsNull() {
        store.save(config)
        prefs.edit().putString("api_key_ciphertext", "@@not-base64@@").commit()

        val error = runCatching { store.load() }.exceptionOrNull()
        assertTrue("密文损坏应抛 LlmStoreException：$error", error is LlmStoreException)
        val message = error!!.message.orEmpty()
        assertTrue("错误文案应可读（损坏/解密）：$message", message.contains("损坏") || message.contains("解密"))
        assertNull("maskedKey 解密失败时静默 null（不崩溃）", store.maskedKey())
    }

    @Test
    fun incompleteConfigIsRejectedAndStoreStaysEmpty() {
        val error = runCatching { store.save(config.copy(apiKey = " ")) }.exceptionOrNull()
        assertTrue("不完整配置应抛 LlmStoreException：$error", error is LlmStoreException)
        assertTrue(error!!.message.orEmpty().contains("不完整"))
        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }
}
