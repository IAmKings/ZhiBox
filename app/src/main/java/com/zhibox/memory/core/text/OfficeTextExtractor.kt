package com.zhibox.memory.core.text

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * P1：Office Open XML（DOCX/PPTX/XLSX）纯 JVM 文本抽取（不引入 POI，避免额外依赖/体积）。
 *
 * 实现方式：用 [ZipInputStream] 流式读取 OOXML zip，只解析需要的 XML 条目，再用 **SAX**
 * 流式提取文本（B14.3 起替代正则；见 [OfficeXmlSax]）：
 * - DOCX：`word/document.xml` 的 `<w:t>`，`</w:p>`→换行、`</w:tc>`→Tab、`</w:tr>`→换行；
 * - PPTX：`ppt/slides/slideN.xml` 按**数字序号**排序，`<a:t>`，`</a:p>`→换行，slide 间空行；
 * - XLSX：`xl/sharedStrings.xml` 解析共享字符串，`xl/worksheets/sheetN.xml` 按行输出单元格
 *   （共享字符串/内联字符串/数值/布尔），**忽略公式 `<f>` 与二进制**。
 *
 * 安全/预算（见 [TextExtractionLimits] 与 [OfficeXmlSax]）：
 * - **DTD/外部实体禁用**（XXE 防护），畸形 XML 可读失败；
 * - 拒绝路径穿越/绝对路径/反斜杠/NUL 的 zip 条目名（即使不落盘也防御解析器被利用）；
 * - 条目数、单条目解压字节、总解压字节上限（防 zip bomb）——超限抛可读 [DocumentParseException]；
 * - 输出字符预算在 SAX 解析过程中生效：超过上限**抛可读失败**（不继续解析、不 OOM；不做静默截断）；
 * - [onCheck] 在 zip 读取/SAX 每个元素回调时调用：DocumentParser 用它做协程取消检查
 *   （抛 [kotlinx.coroutines.CancellationException] 原样传播，不包装成解析失败）。
 *
 * 不写日志、不依赖 Android、不落盘。
 */
object OfficeTextExtractor {

    private const val DOCX_PARSER_ID = "docx-ooxml-v1"
    private const val PPTX_PARSER_ID = "pptx-ooxml-v1"
    private const val XLSX_PARSER_ID = "xlsx-ooxml-v1"

    /** DOCX → 纯文本（段落换行、单元格 Tab）；失败抛可读 [DocumentParseException]。 */
    fun extractDocx(input: InputStream): String = extractDocx(input, OfficeExtractionLimits.DEFAULT, {})

    /** PPTX → 纯文本（按 slide 序号，幻灯片间空行）。 */
    fun extractPptx(input: InputStream): String = extractPptx(input, OfficeExtractionLimits.DEFAULT, {})

    /** XLSX → 纯文本（行/单元格，Tab 分隔）。 */
    fun extractXlsx(input: InputStream): String = extractXlsx(input, OfficeExtractionLimits.DEFAULT, {})

    internal fun extractDocx(
        input: InputStream,
        limits: OfficeExtractionLimits,
        onCheck: () -> Unit = {},
    ): String {
        val entries = readZipEntries(input, limits, onCheck) { name -> name == "word/document.xml" }
        val document = entries["word/document.xml"]
            ?: throw DocumentParseException(
                "DOCX 缺少 word/document.xml（文件损坏或不是 Word .docx 文档）",
                DOCX_PARSER_ID,
            )
        val handler = runSax(
            xml = decodeXml(document),
            handler = DocxSaxHandler(limits.maxTextChars, onCheck),
            parserId = DOCX_PARSER_ID,
            maxTextChars = limits.maxTextChars,
        )
        return finalizeText(handler.result(), DOCX_PARSER_ID, limits)
    }

    internal fun extractPptx(
        input: InputStream,
        limits: OfficeExtractionLimits,
        onCheck: () -> Unit = {},
    ): String {
        val entries = readZipEntries(input, limits, onCheck) { name -> SLIDE_ENTRY.matches(name) }
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
        val raw = StringBuilder()
        for ((_, bytes) in slides) {
            if (raw.length >= limits.maxTextChars) break
            val remaining = limits.maxTextChars - raw.length
            val handler = runSax(
                xml = decodeXml(bytes),
                handler = PptxSaxHandler(remaining, onCheck),
                parserId = PPTX_PARSER_ID,
                maxTextChars = limits.maxTextChars,
            )
            val slideText = handler.result()
            if (slideText.isEmpty()) continue
            if (raw.isNotEmpty()) raw.append("\n\n")
            raw.append(slideText)
        }
        return finalizeText(raw.toString(), PPTX_PARSER_ID, limits)
    }

    internal fun extractXlsx(
        input: InputStream,
        limits: OfficeExtractionLimits,
        onCheck: () -> Unit = {},
    ): String {
        val entries = readZipEntries(input, limits, onCheck) { name ->
            name == "xl/sharedStrings.xml" || SHEET_ENTRY.matches(name)
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let { bytes ->
                val handler = runSharedStrings(decodeXml(bytes), limits.maxTextChars, onCheck, XLSX_PARSER_ID)
                handler.strings
            }
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
        val raw = StringBuilder()
        for ((_, bytes) in sheets) {
            if (raw.length >= limits.maxTextChars) break
            val remaining = limits.maxTextChars - raw.length
            val handler = runSax(
                xml = decodeXml(bytes),
                handler = SheetSaxHandler(sharedStrings, remaining, onCheck),
                parserId = XLSX_PARSER_ID,
                maxTextChars = limits.maxTextChars,
            )
            val sheetText = handler.result()
            if (sheetText.isEmpty()) continue
            if (raw.isNotEmpty()) raw.append('\n')
            raw.append(sheetText)
        }
        return finalizeText(raw.toString(), XLSX_PARSER_ID, limits)
    }

    /** 运行 SAX handler；输出预算触顶 → 可读失败（安全阀：不继续解析、不 OOM、不静默丢内容）。 */
    private fun <T : OfficeBudgetedSaxHandler> runSax(
        xml: String,
        handler: T,
        parserId: String,
        maxTextChars: Int,
    ): T {
        try {
            OfficeXmlSax.parse(xml, handler, parserId)
        } catch (e: OfficeOutputBudgetReached) {
            throw outputBudgetException(parserId, maxTextChars)
        }
        return handler
    }

    /** sharedStrings 解析（预算触顶同样可读失败，避免超大共享字符串表占内存）。 */
    private fun runSharedStrings(
        xml: String,
        maxChars: Int,
        onCheck: () -> Unit,
        parserId: String,
    ): SharedStringsSaxHandler {
        val handler = SharedStringsSaxHandler(maxChars, onCheck)
        try {
            OfficeXmlSax.parse(xml, handler, parserId)
        } catch (e: OfficeOutputBudgetReached) {
            throw outputBudgetException(parserId, maxChars)
        }
        return handler
    }

    private fun outputBudgetException(parserId: String, maxTextChars: Int): DocumentParseException =
        DocumentParseException(
            "文档文本超过上限（$maxTextChars 字符）：请精简或拆分文档后重新导入",
            parserId,
        )

    // ---- zip 读取（安全/预算） ----

    private fun readZipEntries(
        input: InputStream,
        limits: OfficeExtractionLimits,
        onCheck: () -> Unit,
        matcher: (String) -> Boolean,
    ): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    onCheck()
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
                    val bytes = readEntryBounded(zip, limits.maxEntryBytes, name, onCheck)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
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

    private fun readEntryBounded(
        zip: ZipInputStream,
        maxBytes: Long,
        name: String,
        onCheck: () -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            onCheck()
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

    private val SLIDE_ENTRY = Regex("^ppt/slides/slide(\\d+)\\.xml$")
    private val SHEET_ENTRY = Regex("^xl/worksheets/sheet(\\d+)\\.xml$")
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:")
}
