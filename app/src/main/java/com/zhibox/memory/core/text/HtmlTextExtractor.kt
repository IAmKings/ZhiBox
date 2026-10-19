package com.zhibox.memory.core.text

import java.nio.charset.Charset

/**
 * P1：HTML → 标题 + 纯文本（无第三方依赖；供本地 .html 文件与 [WebPageFetcher] 复用）。
 *
 * 规则：
 * - 去掉 `<script>`/`<style>`/`<noscript>`/`<template>`/`<iframe>`/`<svg>`/注释，避免脚本/样式噪声；
 * - 块级标签（p/div/li/tr/h1-6/section/...）结束转行，`<br>` 转行，`<td>/<th>` 转 Tab；
 * - 解码常见 HTML 实体（含数字实体，未知实体原样保留）；
 * - 输出行/空白规范化，字符上限 [TextExtractionLimits.MAX_TEXT_CHARS]（超出截断）。
 *
 * 纯函数、不写日志、不依赖 Android；对畸形 HTML 尽量容错（正则解析，不以抛异常为常态）。
 */
object HtmlTextExtractor {

    data class Extracted(val title: String, val text: String)

    /** 解析 HTML 字符串（charset 已由调用方解码）。 */
    fun extract(html: String): Extracted {
        val title = extractTitle(html)
        val body = extractText(html)
        return Extracted(title = title, text = body)
    }

    /**
     * 解析 HTML 字节：优先 BOM，其次 [charsetHint]（如 Content-Type 的 charset），
     * 再次 `<meta charset>` 声明，最后 UTF-8。解码失败回退 UTF-8（不崩溃）。
     */
    fun extract(bytes: ByteArray, charsetHint: String? = null): Extracted =
        extract(decode(bytes, charsetHint))

    /** 纯文本响应解码（非 HTML 页面/文本文件；BOM 优先、其次 charset 提示、最后 UTF-8）。 */
    internal fun decodePlainText(bytes: ByteArray, charsetHint: String?): String =
        decode(bytes, charsetHint).removePrefix("\uFEFF")

    internal fun decode(bytes: ByteArray, charsetHint: String?): String {
        val (bomCharset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2
            else -> null to 0
        }
        val charset = bomCharset
            ?: charsetHint?.trim()?.takeIf { it.isNotEmpty() }?.let(::charsetOrNull)
            ?: metaCharset(bytes)
            ?: Charsets.UTF_8
        val decoded = try {
            String(bytes, offset, bytes.size - offset, charset)
        } catch (e: Exception) {
            String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
        }
        return decoded.removePrefix("\uFEFF")
    }

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()

    private fun metaCharset(bytes: ByteArray): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = META_CHARSET.find(prefix)?.groupValues?.get(1) ?: return null
        return charsetOrNull(declared)
    }

    private fun extractTitle(html: String): String {
        val raw = TITLE.find(html)?.groupValues?.get(1) ?: return ""
        return cleanInline(ExtractedTextCleanup.unescapeHtml(TAG.replace(raw, " ")))
    }

    private fun extractText(html: String): String {
        var s = html
        s = COMMENT.replace(s, " ")
        s = HEAD.replace(s, " ")
        s = SCRIPT.replace(s, " ")
        s = SCRIPT_UNCLOSED.replace(s, " ")
        s = STYLE.replace(s, " ")
        s = STYLE_UNCLOSED.replace(s, " ")
        s = NOSCRIPT.replace(s, " ")
        s = TEMPLATE.replace(s, " ")
        s = FRAME.replace(s, " ")
        s = SVG.replace(s, " ")
        s = BR.replace(s, "\n")
        s = CELL_OPEN.replace(s, "\t")
        s = CELL_CLOSE.replace(s, "")
        s = LIST_ITEM.replace(s, "\n")
        s = BLOCK_CLOSE.replace(s, "\n")
        s = HR.replace(s, "\n")
        s = TAG.replace(s, "")
        val decoded = ExtractedTextCleanup.unescapeHtml(s)
            .replace('\u00A0', ' ')
            .replace('\r', '\n')
        val out = StringBuilder()
        var blankRun = 0
        for (rawLine in decoded.lineSequence()) {
            val line = rawLine.replace(MULTI_SPACE, " ").trim()
            if (line.isEmpty()) {
                blankRun++
                if (blankRun <= 1 && out.isNotEmpty()) out.append('\n')
            } else {
                out.append(line).append('\n')
                blankRun = 0
            }
        }
        val text = out.toString().trim()
        return ExtractedTextCleanup.truncate(text, TextExtractionLimits.MAX_TEXT_CHARS)
    }

    private fun cleanInline(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private val TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val HEAD = Regex("(?is)<head\\b.*?</head\\s*>")
    private val SCRIPT = Regex("(?is)<script\\b.*?</script\\s*>")

    /** 畸形 HTML：未闭合 `<script>` 之后的全部内容都应视为不可见（删除到结尾）。 */
    private val SCRIPT_UNCLOSED = Regex("(?is)<script\\b.*$")
    private val STYLE = Regex("(?is)<style\\b.*?</style\\s*>")
    private val STYLE_UNCLOSED = Regex("(?is)<style\\b.*$")
    private val NOSCRIPT = Regex("(?is)<noscript\\b.*?</noscript\\s*>")
    private val TEMPLATE = Regex("(?is)<template\\b.*?</template\\s*>")
    private val FRAME = Regex("(?is)<(?:iframe|object|embed)\\b.*?</(?:iframe|object|embed)\\s*>")
    private val SVG = Regex("(?is)<svg\\b.*?</svg\\s*>")
    private val BR = Regex("(?is)<br\\b[^>]*>")
    private val CELL_OPEN = Regex("(?is)<(?:td|th)\\b[^>]*>")
    private val CELL_CLOSE = Regex("(?is)</(?:td|th)\\s*>")
    private val LIST_ITEM = Regex("(?is)<li\\b[^>]*>")
    private val BLOCK_CLOSE = Regex(
        "(?is)</(?:p|div|li|ul|ol|tr|table|thead|tbody|tfoot|section|article|header|footer|nav|aside|" +
            "main|blockquote|pre|h[1-6]|dd|dt|figure|figcaption|form|fieldset|address|details|summary)\\s*>",
    )
    private val HR = Regex("(?is)<hr\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val MULTI_SPACE = Regex(" {2,}")
    private val META_CHARSET = Regex(
        "(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)",
    )
}
