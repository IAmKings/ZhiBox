package com.zhibox.memory.core.text

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStore

/**
 * P1.1b 真实 Android Keystore（AES/GCM/NoPadding）加密持久化（instrumentation）：
 * - 真实 key alias 生成/复用、随机 IV：同一密码两次写入密文不同且均可解密；
 * - put/get/remove/clearAll、TTL 过期清理、密文损坏/篡改 → null 且清理条目；
 * - SharedPreferences 只存 Base64 密文/IV/时间戳，不含明文；
 * - Keystore 不可用（无安全锁的模拟器/异常设备）时整体 skip（Assume）。
 *
 * 本轮按任务要求不跑真机，仅编译验证；设备可用后由门禁执行。
 */
class PdfPasswordStoreKeystoreTest {

    private lateinit var context: Context
    private lateinit var cipher: KeystorePdfPasswordCipher
    private lateinit var store: PdfPasswordStore
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).edit().clear().commit()
        now = 1_000_000L
        cipher = KeystorePdfPasswordCipher()

        // Keystore 可用性探测：无安全锁的模拟器可能不支持 Keystore AES/GCM（整体 skip 并报告）。
        val keystoreAvailable = runCatching {
            val encrypted = cipher.encrypt(byteArrayOf(1, 2, 3))
            cipher.decrypt(encrypted.iv, encrypted.ciphertext)
        }.isSuccess
        assumeTrue("Android Keystore AES/GCM 不可用（设备无安全锁/模拟器限制），跳过真实 Keystore 测试", keystoreAvailable)

        store = PdfPasswordStore(context, cipher, clock = { now }, ttlMs = PdfPasswordStore.DEFAULT_TTL_MS)
    }

    @After
    fun tearDown() {
        PdfPasswordVault.clearAll()
        context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun keystoreRoundTripAndPrefsContainNoPlaintext() {
        store.put("doc-keystore", "keystore-secret-密码".toCharArray())
        val loaded = store.get("doc-keystore")!!
        assertArrayEquals("keystore-secret-密码".toCharArray(), loaded)
        loaded.fill('\u0000')

        val prefs = context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0)
        assertTrue("应写入密文/IV/时间戳", prefs.all.isNotEmpty())
        prefs.all.values.filterIsInstance<String>().forEach { value ->
            assertFalse("prefs 不得含明文", value.contains("keystore-secret"))
            assertFalse(value.contains("密码"))
        }
        assertNotNull("Keystore key alias 应已生成", keystoreEntry())
    }

    @Test
    fun randomIvProducesDifferentCiphertextForSamePassword() {
        store.put("doc-iv", "same-password".toCharArray())
        val prefs = context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0)
        val firstIv = prefs.getString("doc-iv.iv", null)
        val firstCiphertext = prefs.getString("doc-iv.ciphertext", null)

        store.put("doc-iv", "same-password".toCharArray())
        val secondIv = prefs.getString("doc-iv.iv", null)
        val secondCiphertext = prefs.getString("doc-iv.ciphertext", null)

        assertNotEquals("随机 IV：IV 应变化", firstIv, secondIv)
        assertNotEquals("随机 IV：密文应变化", firstCiphertext, secondCiphertext)
        val loaded = store.get("doc-iv")!!
        assertArrayEquals("same-password".toCharArray(), loaded)
        loaded.fill('\u0000')
    }

    @Test
    fun removeAndClearAllDeleteEntries() {
        store.put("doc-1", "one".toCharArray())
        store.put("doc-2", "two".toCharArray())

        store.remove("doc-1")
        assertNull(store.get("doc-1"))
        assertNotNull(store.get("doc-2"))

        store.clearAll()
        assertNull(store.get("doc-2"))
        assertTrue(
            "clearAll 后 prefs 应为空",
            context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).all.isEmpty(),
        )
    }

    @Test
    fun ttlExpiryDeletesEntry() {
        store.put("doc-ttl", "pw".toCharArray())
        now += PdfPasswordStore.DEFAULT_TTL_MS + 1
        assertNull("超过 TTL 应返回 null", store.get("doc-ttl"))
        assertNull(
            "过期条目应删除",
            context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).getString("doc-ttl.iv", null),
        )
    }

    @Test
    fun tamperedCiphertextReturnsNullAndRemovesEntry() {
        store.put("doc-tamper", "pw".toCharArray())
        val prefs = context.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0)
        val original = prefs.getString("doc-tamper.ciphertext", null)!!
        val bytes = Base64.decode(original, Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        prefs.edit()
            .putString("doc-tamper.ciphertext", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .commit()

        assertNull("GCM 认证失败应返回 null（不抛异常）", store.get("doc-tamper"))
        assertNull("篡改条目应清理", prefs.getString("doc-tamper.iv", null))
    }

    @Test
    fun keyAliasIsIndependentAndPersistent() {
        store.put("doc-alias", "pw".toCharArray())
        assertNotNull(keystoreEntry())
        assertEquals(
            "独立 alias 固定",
            KeystorePdfPasswordCipher.DEFAULT_KEY_ALIAS,
            "zhibox_pdf_password_store_v1",
        )
    }

    private fun keystoreEntry() = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getEntry(KeystorePdfPasswordCipher.DEFAULT_KEY_ALIAS, null)
    }.getOrNull()
}
