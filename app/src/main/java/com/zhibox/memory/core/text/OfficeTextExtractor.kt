package com.zhibox.memory.core.text

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * P1：Office Open XML（DOCX/PPTX/XLSX）纯 JVM 文本抽取（不引入 POI，避免额外依赖/体积）。
 *
 * 实现方式：用 [ZipInputStream] 流式读取 OOXML zip，只解析需要的 XML 条目；用轻量正则按
 * **文档顺序**提取文本节点（`w:t` / `a:t` / 共享字符串 / 单元格值），保留段落/行/单元格边界：
 * - DOCX：`word/document.xml` 的 `<w:t>`，`</w:p>`→换行、`</w:tc>`→Tab、`</w:tr>`→换行；
 * - PPTX：`ppt/slides/slideN.xml` 按**数字序号**排序，`<a:t>`，`</a:p>`→换行，slide 间空行；
 * - XLSX：`xl/sharedStrings.xml` 解析共享字符串，`xl/worksheets/sheetN.xml` 按行输出单元格
 *   （共享字符串/内联字符串/数值/布尔），**忽略公式 `<f>` 与二进制**。
 *
 * 安全/预算（见 [TextExtractionLimits]）：
 * - 拒绝路径穿越/绝对路径/反斜杠/NUL 的 zip 条目名（即使我们不落盘，也防御解析器被利用）；
 * - 条目数、单条目解压字节、总解压字节上限（防 zip bomb）——超限抛可读 [DocumentParseException]；
 * - 输出字符上限，超出由调用方截断（[DocumentParser]）。
 *
 * 不写日志、不依赖 Android、不落盘。
 */
object OfficeTextExtractor {

    private const val DOCX_PARSER_ID = "docx-ooxml-v1"
    private const val PPTX_PARSER_ID = "pptx-ooxml-v1"
    private const val XLSX_PARSER_ID = "xlsx-ooxml-v1"

    /** DOCX → 纯文本（段落换行、单元格 Tab）；失败抛可读 [DocumentParseException]。 */
    fun extractDocx(input: InputStream): String = extractDocx(input, OfficeExtractionLimits.DEFAULT)

    /** PPTX → 纯文本（按 slide 序号，幻灯片间空行）。 */
    fun extractPptx(input: InputStream): String = extractPptx(input, OfficeExtractionLimits.DEFAULT)

    /** XLSX → 纯文本（行/单元格，Tab 分隔）。 */
    fun extractXlsx(input: InputStream): String = extractXlsx(input, OfficeExtractionLimits.DEFAULT)

    internal fun extractDocx(input: InputStream, limits: OfficeExtractionLimits): String {
        val entries = readZipEntries(input, limits) { name -> name == "word/document.xml" }
        val document = entries["word/document.xml"]
            ?: throw DocumentParseException(
                "DOCX 缺少 word/document.xml（文件损坏或不是 Word .docx 文档）",
                DOCX_PARSER_ID,
            )
        val text = extractDocxText(decodeXml(document))
        return finalizeText(text, DOCX_PARSER_ID, limits)
    }

    internal fun extractPptx(input: InputStream, limits: OfficeExtractionLimits): String {
        val entries = readZipEntries(input, limits) { name -> SLIDE_ENTRY.matches(name) }
        val slides = entries.entries
            .mapNotNull { (name, bytes) ->
                val number = SLIDE_ENTRY.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                number to bytes
            }
            .sortedBy { it.first }
        if (slides.isEmpty()) {
            throw DocumentParseException(
                "PPTX 中没有找到幻灯片文本（文件损坏或不是 PowerPoint .pptx 文档）",
                PPTX_PARSER_ID,
            )
        }
        val text = slides.joinToString("\n\n") { (_, bytes) -> extractPptxSlideText(decodeXml(bytes)) }
        return finalizeText(text, PPTX_PARSER_ID, limits)
    }

    internal fun extractXlsx(input: InputStream, limits: OfficeExtractionLimits): String {
        val entries = readZipEntries(input, limits) { name ->
            name == "xl/sharedStrings.xml" || SHEET_ENTRY.matches(name)
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(decodeXml(it)) }
            ?: emptyList()
        val sheets = entries.entries
            .mapNotNull { (name, bytes) ->
                val number = SHEET_ENTRY.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                number to bytes
            }
            .sortedBy { it.first }
        if (sheets.isEmpty()) {
            throw DocumentParseException(
                "XLSX 中没有找到工作表（文件损坏或不是 Excel .xlsx 文档）",
                XLSX_PARSER_ID,
            )
        }
        val text = sheets.joinToString("\n") { (_, bytes) -> extractSheetText(decodeXml(bytes), sharedStrings) }
        return finalizeText(text, XLSX_PARSER_ID, limits)
    }

    // ---- zip 读取（安全/预算） ----

    private fun readZipEntries(
        input: InputStream,
        limits: OfficeExtractionLimits,
        matcher: (String) -> Boolean,
    ): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        throw DocumentParseException(
                            "Office 压缩包条目过多（超过 ${limits.maxEntries} 个），已拒绝解析",
                            "office-zip",
                        )
                    }
                    val name = entry.name ?: continue
                    validateEntryName(name)
                    if (entry.isDirectory) continue
                    // P1 审查修复：所有非目录条目都要按预算读取（即使不匹配目标文件），
                    // 否则 ZipInputStream 跳过未匹配条目时仍可能解压超大内容，形成 zip-bomb/CPU DoS。
                    if (entry.size > limits.maxEntryBytes) {
                        throw DocumentParseException(
                            "Office 文档内部条目过大（$name 超过 ${limits.maxEntryBytes / (1024 * 1024)}MB），已拒绝解析",
                            "office-zip",
                        )
                    }
                    val bytes = readEntryBounded(zip, limits.maxEntryBytes, name)
                    totalBytes += bytes.size.toLong()
                    if (totalBytes > limits.maxTotalUncompressedBytes) {
                        throw DocumentParseException(
                            "Office 文档解压后总大小超过上限" +
                                "（${limits.maxTotalUncompressedBytes / (1024 * 1024)}MB），已拒绝解析",
                            "office-zip",
                        )
                    }
                    if (matcher(name)) result[name] = bytes
                }
                if (entryCount == 0) {
                    throw DocumentParseException(
                        "文件不是有效的 Office 压缩包（未找到任何 ZIP 条目，可能已损坏）",
                        "office-zip",
                    )
                }
            }
        } catch (e: DocumentParseException) {
            throw e
        } catch (e: ZipException) {
            throw DocumentParseException(
                "文件不是有效的 Office 压缩包（ZIP 结构损坏或被加密）：请确认文件未损坏后重试",
                "office-zip",
            )
        } catch (e: java.io.IOException) {
            // 底层 IO 错误（源文件被删/磁盘语义）保留 IOException 语义，由导入层分类为 IO 可重试
            throw e
        }
        return result
    }

    private fun validateEntryName(name: String) {
        val unsafe = name.isEmpty() ||
            name.length > 512 ||
            name.contains('\u0000') ||
            name.startsWith("/") ||
            name.startsWith("\\") ||
            name.contains('\\') ||
            WINDOWS_ABSOLUTE.containsMatchIn(name) ||
            name.split('/').any { it == ".." }
        if (unsafe) {
            throw DocumentParseException(
                "Office 压缩包包含非法路径（路径穿越/绝对路径）：${name.take(80)}",
                "office-zip",
            )
        }
    }

    private fun readEntryBounded(zip: ZipInputStream, maxBytes: Long, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw DocumentParseException(
                    "Office 文档内部条目过大（$name 解压后超过 ${maxBytes / (1024 * 1024)}MB），已拒绝解析",
                    "office-zip",
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---- DOCX ----

    /** 文档顺序 token：段落结束/单元格结束/行结束/文本/制表/换行。 */
    private val DOCX_TOKEN = Regex(
        "(?s)<w:p(?:\\s[^>]*)?>|</w:p>|<w:t(?:\\s[^>]*)?>(.*?)</w:t>|" +
            "<w:tab(?:\\s[^>]*)?/>|<w:br(?:\\s[^>]*)?/>|<w:cr(?:\\s[^>]*)?/>|" +
            "</w:tc>|</w:tr>",
    )

    internal fun extractDocxText(xml: String): String {
        val sb = StringBuilder()
        for (match in DOCX_TOKEN.findAll(xml)) {
            val token = match.value
            when {
                token == "</w:p>" -> sb.append('\n')
                token == "</w:tc>" -> sb.append('\t')
                token == "</w:tr>" -> sb.append('\n')
                // 注意：必须先判 <w:tab/>，否则 startsWith("<w:t") 会把 tab 误当文本且组为空。
                token.startsWith("<w:tab") -> sb.append('\t')
                token.startsWith("<w:t") -> {
                    val text = match.groupValues.getOrNull(1).orEmpty()
                    if (text.isNotEmpty()) sb.append(ExtractedTextCleanup.unescapeXml(text))
                }
                token.startsWith("<w:br") || token.startsWith("<w:cr") -> sb.append('\n')
                else -> Unit
            }
        }
        return sb.toString()
    }

    // ---- PPTX ----

    private val SLIDE_ENTRY = Regex("^ppt/slides/slide(\\d+)\\.xml$")

    private val PPTX_TOKEN = Regex(
        "(?s)<a:p(?:\\s[^>]*)?>|</a:p>|<a:t(?:\\s[^>]*)?>(.*?)</a:t>|<a:br(?:\\s[^>]*)?/>",
    )

    internal fun extractPptxSlideText(xml: String): String {
        val sb = StringBuilder()
        for (match in PPTX_TOKEN.findAll(xml)) {
            val token = match.value
            when {
                token == "</a:p>" || token.startsWith("<a:br") -> sb.append('\n')
                token.startsWith("<a:t") -> {
                    val text = match.groupValues.getOrNull(1).orEmpty()
                    if (text.isNotEmpty()) sb.append(ExtractedTextCleanup.unescapeXml(text))
                }
                else -> Unit
            }
        }
        return sb.toString()
    }

    // ---- XLSX ----

    private val SHEET_ENTRY = Regex("^xl/worksheets/sheet(\\d+)\\.xml$")
    private val SHARED_STRING_ITEM = Regex("(?s)<si(?:\\s[^>]*)?>(.*?)</si>")
    private val TEXT_ELEMENT = Regex("(?s)<t(?:\\s[^>]*)?>(.*?)</t>")
    private val ROW_ELEMENT = Regex("(?s)<row(?:\\s[^>]*)?>(.*?)</row>")
    private val CELL_ELEMENT = Regex("(?s)<c\\b([^>]*)>(.*?)</c>")
    private val CELL_TYPE = Regex("\\bt=\"([^\"]*)\"")
    private val VALUE_ELEMENT = Regex("(?s)<v(?:\\s[^>]*)?>(.*?)</v>")
    private val INLINE_STRING = Regex("(?s)<is(?:\\s[^>]*)?>(.*?)</is>")

    internal fun parseSharedStrings(xml: String): List<String> =
        SHARED_STRING_ITEM.findAll(xml).map { item ->
            TEXT_ELEMENT.findAll(item.groupValues[1])
                .joinToString("") { ExtractedTextCleanup.unescapeXml(it.groupValues[1]) }
        }.toList()

    internal fun extractSheetText(xml: String, sharedStrings: List<String>): String {
        val rows = ROW_ELEMENT.findAll(xml).mapNotNull { row ->
            val cells = CELL_ELEMENT.findAll(row.groupValues[1]).mapNotNull { cell ->
                cellText(cell.groupValues[1], cell.groupValues[2], sharedStrings)
            }.toList()
            if (cells.isEmpty()) null else cells.joinToString("\t")
        }.toList()
        return rows.joinToString("\n")
    }

    private fun cellText(attributes: String, inner: String, sharedStrings: List<String>): String? {
        val type = CELL_TYPE.find(attributes)?.groupValues?.get(1).orEmpty()
        val raw = when (type) {
            "s" -> {
                val index = VALUE_ELEMENT.find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                index?.let { sharedStrings.getOrNull(it) } ?: ""
            }
            "inlineStr" -> {
                val inline = INLINE_STRING.find(inner)?.groupValues?.get(1) ?: return null
                TEXT_ELEMENT.findAll(inline)
                    .joinToString("") { ExtractedTextCleanup.unescapeXml(it.groupValues[1]) }
            }
            else -> VALUE_ELEMENT.find(inner)?.groupValues?.get(1)
                ?.let { ExtractedTextCleanup.unescapeXml(it) }
                ?.trim()
                .orEmpty()
        }
        return raw.takeIf { it.isNotBlank() }
    }

    // ---- 公共收尾 ----

    private fun finalizeText(raw: String, parserId: String, limits: OfficeExtractionLimits): String {
        val normalized = ExtractedTextCleanup.normalize(raw)
        if (normalized.isBlank()) {
            throw DocumentParseException(
                "文档没有可提取的文本（可能是空文档、纯图片或扫描件）；请转换为含文本的文件后重试",
                parserId,
            )
        }
        return ExtractedTextCleanup.truncate(normalized, limits.maxTextChars)
    }

    /** OOXML XML 字节解码：UTF-8（默认）/UTF-16 BOM；去掉 BOM。 */
    private fun decodeXml(bytes: ByteArray): String {
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return String(bytes, offset, bytes.size - offset, charset)
    }

    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:")
}
