package com.zhibox.memory.core.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生产用 [LlmConfigStore]（B8）：API Key 使用 Android Keystore 的 AES-256/GCM 加密后存
 * SharedPreferences；baseUrl/model/timeoutSeconds/maxTokens 等非密字段明文存储。
 *
 * 安全设计：
 * - **密钥材料在 AndroidKeyStore**（不可导出），SharedPreferences 只存 iv + ciphertext；
 * - 开启 `allowBackup=false`（P1-10）后该 SharedPreferences 不进入系统备份；
 *   本配置也**不进入** KnowledgeBackup（备份只包含数据库）；
 * - 本类不写任何日志，异常文案不拼接 key 明文；
 * - API 24+ 兼容（`KeyGenParameterSpec` API 23+、AES/GCM API 23+）。
 *
 * 失败语义：数据损坏/Keystore 密钥失效时 [load]/[save] 抛 [LlmStoreException]（可读），
 * UI 捕获后提示"请重新保存 API Key"；[maskedKey] 则静默返回 null（避免设置页因解密失败崩溃）。
 *
 * 测试注意：本类依赖 AndroidKeyStore 与真实 Context；JVM 单测用 [InMemoryLlmConfigStore]，
 * Keystore 行为由 Robolectric/instrumentation 测试覆盖（data-reviewer 负责）。
 */
class KeystoreLlmConfigStore(context: Context) : LlmConfigStore {
    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): LlmConfig? {
        // 无任何配置 -> null（正常"未配置"）；一旦存在 baseUrl 但其余字段缺失，
        // 说明存储被破坏（例如清除到一半/迁移异常），抛可读错误让 UI 引导重新保存。
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val model = prefs.getString(KEY_MODEL, null)
        val ciphertext = prefs.getString(KEY_API_KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_API_KEY_IV, null)
        if (model.isNullOrBlank() || ciphertext.isNullOrBlank() || iv.isNullOrBlank()) {
            throw LlmStoreException("LLM 配置不完整或已损坏（缺少 model/密钥材料），请重新保存 API Key 后再试")
        }
        return LlmConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = decrypt(ciphertext, iv),
            timeoutSeconds = prefs.getInt(KEY_TIMEOUT_SECONDS, LlmConfig.DEFAULT_TIMEOUT_SECONDS),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, LlmConfig.DEFAULT_MAX_TOKENS),
        )
    }

    @Synchronized
    override fun save(config: LlmConfig) {
        if (config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            throw LlmStoreException("LLM 配置不完整：baseUrl / model / API Key 均不能为空")
        }
        val (iv, ciphertext) = encrypt(config.apiKey)
        val saved = prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putInt(KEY_TIMEOUT_SECONDS, config.timeoutSeconds)
            .putInt(KEY_MAX_TOKENS, config.maxTokens)
            .putString(KEY_API_KEY_IV, iv)
            .putString(KEY_API_KEY_CIPHERTEXT, ciphertext)
            // 用 commit 而非 apply：保存密钥失败必须能反馈给用户（apply 无法报错）
            .commit()
        if (!saved) {
            throw LlmStoreException("保存 LLM 配置失败（本地存储不可写），请重试")
        }
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        // 尽力删除 Keystore key（失败不阻塞配置清除；下次保存会重建 key）。
        runCatching {
            val keyStore = androidKeyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    @Synchronized
    override fun maskedKey(): String? = runCatching {
        load()?.apiKey?.takeIf { it.isNotBlank() }?.let(LlmConfig::maskSecret)
    }.getOrNull()

    // ---- 加密实现（Android Keystore AES/GCM） ----

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        val keyStore = androidKeyStore()
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): Pair<String, String> = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        encode(cipher.iv) to encode(ciphertext)
    } catch (e: GeneralSecurityException) {
        throw LlmStoreException(
            "API Key 加密失败（Keystore 不可用或已被重置），请重试或清除配置后重新保存",
            e,
        )
    } catch (e: IllegalStateException) {
        throw LlmStoreException("API Key 加密失败（Keystore 状态异常），请重试或清除配置后重新保存", e)
    }

    private fun decrypt(ciphertextB64: String, ivB64: String): String = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_SIZE_BITS, decode(ivB64)),
        )
        String(cipher.doFinal(decode(ciphertextB64)), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        throw LlmStoreException("API Key 解密失败（Keystore 密钥已失效/被重置），请重新保存 API Key", e)
    } catch (e: IllegalArgumentException) {
        throw LlmStoreException("LLM 配置已损坏（加密数据格式非法），请清除配置后重新保存 API Key", e)
    } catch (e: IllegalStateException) {
        throw LlmStoreException("LLM 配置读取失败（Keystore 状态异常），请重试或清除配置", e)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "llm_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_API_KEY_IV = "api_key_iv"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"

        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "zhibox_llm_api_key_v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_SIZE_BITS = 128
        const val AES_KEY_SIZE_BITS = 256
    }
}
