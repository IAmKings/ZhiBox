package com.zhibox.memory.core.text

import android.app.Application
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.1b PDF 密码加密持久化（Robolectric + fake cipher，纯 JVM）：
 * - put/get 往返、覆盖、remove 只删目标、clearAll、不存在 → null；
 * - TTL 24h（读取时过期删除；边界值保留）；
 * - 解密失败/密文损坏 → null 且删除条目（不抛异常）；
 * - 加密失败 → 抛异常且不落盘；空 docId/空密码 no-op；
 * - 不写明文进 SharedPreferences；调用方 CharArray 不被修改；非 ASCII 密码往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfPasswordStoreRobolectricTest {

    private lateinit var app: Application
    private lateinit var cipher: FakePdfPasswordCipher
    private lateinit var store: PdfPasswordStore
    private var now: Long = 1_000_000L

    private val prefs get() = app.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        cipher = FakePdfPasswordCipher()
        now = 1_000_000L
        store = PdfPasswordStore(
            context = app,
            cipher = cipher,
            clock = { now },
            ttlMs = PdfPasswordStore.DEFAULT_TTL_MS,
        )
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        PdfPasswordVault.clearAll()
    }

    @Test
    fun putGetRoundTripDoesNotModifyCallerArray() {
        val password = "secret-pass-123".toCharArray()
        val original = password.copyOf()
        store.put("doc-1", password)

        assertArrayEquals("put 不得修改调用方 CharArray", original, password)
        val loaded = store.get("doc-1")!!
        assertArrayEquals("secret-pass-123".toCharArray(), loaded)
        loaded.fill('\u0000')
        cipher.encryptCalls.let { assertEquals(1, it) }
        assertEquals(1, cipher.decryptCalls)
    }

    @Test
    fun missingEntryReturnsNull() {
        assertNull(store.get("never"))
        assertFalse(store.has("never"))
    }

    @Test
    fun putOverwritesExistingEntryAndRotatesIv() {
        store.put("doc-1", "old".toCharArray())
        val oldCiphertext = prefs.getString("doc-1.ciphertext", null)
        store.put("doc-1", "new".toCharArray())
        val newCiphertext = prefs.getString("doc-1.ciphertext", null)

        assertNotEquals("随机 IV 下同一位置密文应变化", oldCiphertext, newCiphertext)
        val loaded = store.get("doc-1")!!
        assertArrayEquals("new".toCharArray(), loaded)
        loaded.fill('\u0000')
    }

    @Test
    fun removeDeletesOnlyTargetDocument() {
        store.put("doc-1", "one".toCharArray())
        store.put("doc-2", "two".toCharArray())

        store.remove("doc-1")
        assertNull(store.get("doc-1"))
        val other = store.get("doc-2")!!
        assertArrayEquals("two".toCharArray(), other)
        other.fill('\u0000')
    }

    @Test
    fun clearAllDeletesAllEntries() {
        store.put("doc-1", "one".toCharArray())
        store.put("doc-2", "two".toCharArray())

        store.clearAll()
        assertNull(store.get("doc-1"))
        assertNull(store.get("doc-2"))
        assertTrue("clearAll 后 prefs 应为空", prefs.all.isEmpty())
    }

    @Test
    fun ttlExpiredEntryIsDeletedOnRead() {
        store.put("doc-ttl", "pw".toCharArray())
        now += PdfPasswordStore.DEFAULT_TTL_MS
        assertEquals("TTL 边界内仍有效", 2, store.get("doc-ttl")!!.size)

        now += 1
        assertNull("超过 TTL 应返回 null", store.get("doc-ttl"))
        assertNull("过期条目应被删除", prefs.getString("doc-ttl.iv", null))
        assertNull(prefs.getString("doc-ttl.ciphertext", null))
        assertNull(prefs.getString("doc-ttl.createdAt", null))
    }

    @Test
    fun expiredEntryDoesNotAffectOtherDocuments() {
        store.put("old", "old-pw".toCharArray())
        now += 100L
        store.put("fresh", "fresh-pw".toCharArray())
        now += PdfPasswordStore.DEFAULT_TTL_MS

        assertNull("old 应过期", store.get("old"))
        val fresh = store.get("fresh")!!
        assertArrayEquals("fresh-pw".toCharArray(), fresh)
        fresh.fill('\u0000')
    }

    @Test
    fun corruptedBase64ReturnsNullAndRemovesEntry() {
        store.put("doc-corrupt", "pw".toCharArray())
        // fake cipher 无认证标签，用"非法 Base64"模拟损坏（真实 GCM 篡改由 instrumented 测试覆盖）
        prefs.edit().putString("doc-corrupt.ciphertext", "!!!not-base64!!!").commit()

        assertNull(store.get("doc-corrupt"))
        assertNull("损坏条目应被清理", prefs.getString("doc-corrupt.iv", null))
    }

    @Test
    fun decryptFailureReturnsNullAndRemovesEntry() {
        store.put("doc-fail", "pw".toCharArray())
        cipher.failDecrypt = true

        assertNull(store.get("doc-fail"))
        assertNull("解密失败条目应被清理", prefs.getString("doc-fail.iv", null))
    }

    @Test
    fun encryptFailurePropagatesAndDoesNotPersist() {
        cipher.failEncrypt = true
        assertThrows(IllegalStateException::class.java) {
            store.put("doc-fail", "pw".toCharArray())
        }
        assertTrue("加密失败不得落盘", prefs.all.isEmpty())
    }

    @Test
    fun blankDocumentIdOrEmptyPasswordIsNoOp() {
        store.put("", "pw".toCharArray())
        store.put("doc-empty", CharArray(0))
        assertTrue(prefs.all.isEmpty())
        assertNull(store.get("doc-empty"))
    }

    @Test
    fun prefsContainNoPlaintextPassword() {
        store.put("doc-plain", "SuperSecret!密码".toCharArray())
        val allValues = prefs.all.values.filterIsInstance<String>()
        assertTrue("prefs 中应有密文条目", allValues.isNotEmpty())
        allValues.forEach { value ->
            assertFalse("prefs 不得出现明文密码：$value", value.contains("SuperSecret"))
            assertFalse(value.contains("密码"))
        }
        val loaded = store.get("doc-plain")!!
        assertArrayEquals("SuperSecret!密码".toCharArray(), loaded)
        loaded.fill('\u0000')
    }

    @Test
    fun unicodePasswordRoundTrips() {
        val password = "päss🔒中文密".toCharArray()
        store.put("doc-unicode", password)
        val loaded = store.get("doc-unicode")!!
        assertArrayEquals(password, loaded)
        loaded.fill('\u0000')
    }
}
