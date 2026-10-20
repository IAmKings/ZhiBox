package com.zhibox.memory.core.text

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 加密后的密码负载（随机 IV + GCM 密文；均不含明文）。 */
data class EncryptedPassword(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * P1.1b：PDF 密码加解密抽象（生产 = Android Keystore；测试可注入 fake）。
 *
 * 实现约定：加密使用随机 IV；同一明文多次加密结果不同；解密失败抛异常（由
 * [PdfPasswordStore] 捕获并清理条目，不向 UI 泄漏细节）。
 */
interface PdfPasswordCipher {
    fun encrypt(plaintext: ByteArray): EncryptedPassword

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

/**
 * 生产加密实现：Android Keystore `AES/GCM/NoPadding`（API 24+），独立 key alias。
 *
 * - key 在首次使用时生成并常驻 Keystore（不可导出，随应用卸载/清除数据销毁）；
 * - 随机 IV（`setRandomizedEncryptionRequired(true)`），IV 随密文写 SharedPreferences；
 * - 不在本类写任何日志；明文只在入参 ByteArray/返回 ByteArray 生命周期内存在。
 */
class KeystorePdfPasswordCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : PdfPasswordCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedPassword {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPassword(iv = cipher.iv, ciphertext = ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** 独立 alias：与 LLM API Key 等其他 Keystore 用途隔离。 */
        const val DEFAULT_KEY_ALIAS: String = "zhibox_pdf_password_store_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}

/**
 * P1.1b：PDF 打开密码的**加密持久化**（解决"密码仅进程内存、进程死亡需重输"）。
 *
 * 安全约定：
 * - 只存密文+随机 IV+时间戳（Base64）于 SharedPreferences `pdf_password_store`，**不写明文/不写日志**；
 * - SharedPreferences 位于应用私有目录，且 `AndroidManifest` 已 `allowBackup=false`（系统备份不含）；
 *   [com.zhibox.memory.core.backup.KnowledgeBackup] 只导出 DB 内容，不含本 prefs；
 * - 默认 TTL 24h：过期条目在读取时删除并返回 null（密码是临时重试凭据，不是长期保险箱）；
 * - get 返回新 CharArray（调用方负责 `fill('\u0000')`）；解密失败/密文损坏 → 删除条目并返回 null，不抛异常；
 * - put 会覆盖旧值（随机 IV）；调用方传入的 CharArray 不被修改。
 *
 * 生命周期（[com.zhibox.memory.core.ingest.IngestRepository] 使用）：
 * retry 写入 → worker 优先内存 Vault、否则本 store 取回（进程重启可恢复）→ 解析成功/密码错误后 remove；
 * 取消/IO/进程被杀保留，供下次 worker 自动重试。默认不进入备份、不被清库删除。
 */
class PdfPasswordStore(
    context: Context,
    private val cipher: PdfPasswordCipher = KeystorePdfPasswordCipher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private val appContext: Context = context.applicationContext

    private val prefs get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 加密保存该文档的密码。空 documentId/空密码为 no-op；加密失败（Keystore 不可用等）会抛出，
     * 由调用方决定降级（[com.zhibox.memory.core.ingest.IngestRepository] 会退回进程内 Vault）。
     */
    fun put(documentId: String, password: CharArray) {
        if (documentId.isBlank() || password.isEmpty()) return
        val plaintext = encodeUtf8(password)
        try {
            val encrypted = cipher.encrypt(plaintext)
            val iv = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP)
            val ciphertext = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)
            prefs.edit()
                .putString(keyIv(documentId), iv)
                .putString(keyCiphertext(documentId), ciphertext)
                .putLong(keyCreatedAt(documentId), clock())
                .commit()
        } finally {
            plaintext.fill(0)
        }
    }

    /** 取回并解密密码；不存在/过期/损坏 → null（过期/损坏会顺手删除条目）。 */
    fun get(documentId: String): CharArray? {
        if (documentId.isBlank()) return null
        val ivBase64 = prefs.getString(keyIv(documentId), null)
        val ciphertextBase64 = prefs.getString(keyCiphertext(documentId), null)
        if (ivBase64 == null || ciphertextBase64 == null) {
            if (ivBase64 != null || ciphertextBase64 != null) remove(documentId)
            return null
        }
        val createdAt = prefs.getLong(keyCreatedAt(documentId), 0L)
        if (ttlMs > 0 && clock() - createdAt > ttlMs) {
            remove(documentId)
            return null
        }
        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            val plaintext = cipher.decrypt(iv, ciphertext)
            try {
                decodeUtf8(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (e: Exception) {
            // 密钥失效/密文损坏/被篡改：删除条目，让 UI 重新询问密码；不上报细节（无日志）
            remove(documentId)
            null
        }
    }

    /** 删除该文档密码（成功/密码错误/文档删除后调用）；不存在也无副作用。 */
    fun remove(documentId: String) {
        if (documentId.isBlank()) return
        prefs.edit()
            .remove(keyIv(documentId))
            .remove(keyCiphertext(documentId))
            .remove(keyCreatedAt(documentId))
            .commit()
    }

    /** 清空所有已存密码（如用户退出/安全重置）；不删除 Keystore key（下次复用）。 */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    /** 当前是否有未过期条目（测试/诊断用；不返回明文）。 */
    @VisibleForTesting
    internal fun has(documentId: String): Boolean = get(documentId)?.let { password ->
        password.fill('\u0000')
        true
    } ?: false

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer: ByteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.clear()
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val buffer: CharBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(buffer.remaining())
        buffer.get(chars)
        return chars
    }

    companion object {
        /** SharedPreferences 文件名（独立文件；不进 KnowledgeBackup）。 */
        const val PREFS_NAME: String = "pdf_password_store"

        /** 默认 TTL：24h（临时重试凭据）。 */
        const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L

        private fun keyIv(documentId: String): String = "$documentId.iv"
        private fun keyCiphertext(documentId: String): String = "$documentId.ciphertext"
        private fun keyCreatedAt(documentId: String): String = "$documentId.createdAt"
    }
}
