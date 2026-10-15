package com.zhibox.memory.core.text

import java.io.InputStream

/** TXT/MD 解析：UTF-8 读取（去 BOM）；PDF/Office 为后续阶段非目标 */
object DocumentParser {

    data class Parsed(val text: String, val parserId: String)

    fun parse(input: InputStream, mimeType: String? = null, fileName: String? = null): Parsed {
        val bytes = input.use { it.readBytes() }
        val text = decodeUtf8(bytes)
        val kind = when {
            (mimeType ?: "").contains("markdown", ignoreCase = true) -> "md"
            fileName?.endsWith(".md", ignoreCase = true) == true -> "md"
            fileName?.endsWith(".txt", ignoreCase = true) == true -> "txt"
            else -> "txt"
        }
        return Parsed(text, parserId = "$kind-utf8-v1")
    }

    internal fun decodeUtf8(bytes: ByteArray): String {
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }
}
