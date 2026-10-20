package com.zhibox.memory.core.text

/**
 * P1.1b 测试用 fake cipher（不依赖 Android Keystore，纯 JVM）：
 * - XOR 实现可逆加解密；IV 单调递增保证"同一明文多次加密结果不同"；
 * - 可注入 encrypt/decrypt 失败，验证 store 的防御性清理。
 */
internal class FakePdfPasswordCipher(
    private val key: Byte = 0x5A,
) : PdfPasswordCipher {

    var encryptCalls: Int = 0
        private set
    var decryptCalls: Int = 0
        private set
    var failEncrypt: Boolean = false
    var failDecrypt: Boolean = false

    private var ivCounter: Int = 0

    override fun encrypt(plaintext: ByteArray): EncryptedPassword {
        encryptCalls++
        if (failEncrypt) throw IllegalStateException("fake encrypt failure")
        ivCounter++
        val iv = byteArrayOf(
            (ivCounter ushr 24).toByte(),
            (ivCounter ushr 16).toByte(),
            (ivCounter ushr 8).toByte(),
            ivCounter.toByte(),
        )
        val ciphertext = xor(plaintext)
        return EncryptedPassword(iv = iv, ciphertext = ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        decryptCalls++
        if (failDecrypt) throw IllegalStateException("fake decrypt failure")
        return xor(ciphertext)
    }

    private fun xor(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor key.toInt()).toByte()
        }
        return out
    }
}
