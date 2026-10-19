package com.zhibox.memory.core.text

/**
 * 文本抽取后的通用清洗（Office/HTML 共用，纯函数、无依赖）。
 *
 * - [normalize]：统一换行、去掉控制字符、折叠多余空行、去行尾空格；
 * - [truncate]：严格字符上限（超出直接截断，调用方在 KDoc 说明截断语义）；
 * - [unescapeXml]：OOXML 文本节点解码（5 个 XML 实体 + 数字实体；未知实体原样保留）。
 */
internal object ExtractedTextCleanup {

    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var pendingNewlines = 0
        for (ch in raw) {
            when {
                ch == '\r' -> Unit // CRLF/CR 由后续 \n 统一处理；孤立 CR 由下一分支兜底
                ch == '\n' -> {
                    pendingNewlines++
                    if (pendingNewlines <= 2) sb.append('\n')
                }
                ch == '\t' -> {
                    sb.append('\t')
                    pendingNewlines = 0
                }
                ch.isISOControl() -> Unit // NUL 等控制字符（OOXML/HTML 常见噪声）丢弃
                else -> {
                    sb.append(ch)
                    pendingNewlines = 0
                }
            }
        }
        // 孤立 CR（旧 Mac 换行）替换为换行（上一步被跳过）
        val normalized = sb.toString().replace('\r', '\n')
        return normalized.lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    fun truncate(text: String, maxChars: Int): String =
        if (maxChars <= 0) "" else if (text.length <= maxChars) text else text.take(maxChars)

    fun unescapeXml(raw: String): String = unescape(raw, xmlOnly = true)

    fun unescapeHtml(raw: String): String = unescape(raw, false)

    private fun unescape(raw: String, xmlOnly: Boolean): String {
        if (raw.indexOf('&') < 0) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                sb.append(c)
                i++
                continue
            }
            val body = raw.substring(i + 1, end)
            val decoded = decodeEntity(body, xmlOnly)
            if (decoded == null) {
                sb.append(c)
                i++
            } else {
                sb.append(decoded)
                i = end + 1
            }
        }
        return sb.toString()
    }

    private fun decodeEntity(body: String, xmlOnly: Boolean): String? {
        if (body.isEmpty()) return null
        if (body.startsWith("#")) {
            val spec = body.substring(1)
            val code = when {
                spec.startsWith("x", ignoreCase = true) -> spec.substring(1).toIntOrNull(16)
                else -> spec.toIntOrNull(10)
            } ?: return null
            if (code <= 0 || code > 0x10FFFF) return null
            return String(Character.toChars(code))
        }
        XML_ENTITIES[body]?.let { return it }
        if (xmlOnly) return null
        return HTML_ENTITIES[body]
    }

    private val XML_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

    /** 常见 HTML 实体（Office/网页抽取够用；未知实体保留原文，不猜）。 */
    private val HTML_ENTITIES: Map<String, String> = mapOf(
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "laquo" to "«",
        "raquo" to "»",
        "bull" to "•",
        "middot" to "·",
        "times" to "×",
        "divide" to "÷",
        "plusmn" to "±",
        "deg" to "°",
        "frac12" to "½",
        "frac14" to "¼",
        "frac34" to "¾",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "euro" to "€",
        "pound" to "£",
        "yen" to "¥",
        "cent" to "¢",
        "sect" to "§",
        "para" to "¶",
        "prime" to "′",
        "Prime" to "″",
    )
}
