package com.zhibox.memory.core.text

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream

/**
 * 文档解析失败（message 面向用户可读、单行；[parserId] 便于诊断/日志定位）。
 *
 * 导入层会按 [errorCode] 归入 `PipelineError.DOCUMENT_PARSE`（确定性失败不自动重试；
 * PDF 初始化瞬时失败除外），因此文案应说明"怎么了 + 怎么办"，且不得包含文件正文/敏感信息。
 */
class DocumentParseException(
    message: String,
    val parserId: String? = null,
    /** 稳定错误码（如 [PdfExtractionErrorCodes]；导入诊断/UI 可分支；可为 null）。 */
    val errorCode: String? = null,
) : Exception(message)

/** 文档解析稳定错误码（配合 [DocumentParseException.errorCode]；导入诊断/UI 可分支）。 */
object DocumentParseErrorCodes {
    /** 旧版 Office 二进制格式（.doc/.ppt/.xls）明确不支持（请另存为 OOXML）。 */
    const val OFFICE_LEGACY_UNSUPPORTED: String = "OFFICE_LEGACY_UNSUPPORTED"

    /** 其他不支持的文件类型（图片/压缩包/音视频等）。 */
    const val UNSUPPORTED_TYPE: String = "DOCUMENT_UNSUPPORTED_TYPE"
}

/**
 * P1：按扩展名/MIME 分派文档解析（txt/md/docx/pptx/xlsx/html/pdf）。
 *
 * 契约：
 * - [parse]（InputStream 版本）保持向后兼容：无文件名/无 MIME 时按纯文本解析（默认 `txt-utf8-v1`）；
 * - [parse]（Context + File 版本）是**导入流水线唯一入口**（suspend：PDF 需 PDFBox 初始化/逐页取消）；
 *   PDF 走 [PdfTextExtractor]，其余类型直接委托 InputStream 版本，行为完全一致；
 * - 未知/不支持类型抛可读 [DocumentParseException]（导入失败可重试/由用户换文件）；
 * - 安全/预算：Office 走 [OfficeTextExtractor] 的 zip 预算；HTML/txt 有输入字节上限；
 *   所有文本输出统一截断到 [TextExtractionLimits.MAX_TEXT_CHARS] 字符（防超大文档拖垮设备，
 *   截断语义在任务/发布说明中注明）。
 *
 * parserId 稳定值（写入 document.parserId）：
 * `txt-utf8-v1` / `md-utf8-v1` / `docx-ooxml-v1` / `pptx-ooxml-v1` / `xlsx-ooxml-v1` /
 * `html-text-v1` / `pdf-text-v1`。
 */
object DocumentParser {

    data class Parsed(val text: String, val parserId: String)

    private enum class Kind { TXT, MD, HTML, DOCX, PPTX, XLSX, PDF, LEGACY_OFFICE, UNSUPPORTED }

    /**
     * 兼容入口（既有调用方/测试）：无名称无 MIME 时按 txt 解析。
     *
     * 注意：PDF 需要 Android Context（PDFBox 初始化），此入口遇到 PDF/Office 时：
     * - Office 可直接解析（纯 JVM）；
     * - PDF 抛可读异常，请改用 [parse]（Context + File）。
     */
    fun parse(input: InputStream, mimeType: String? = null, fileName: String? = null): Parsed =
        when (val kind = detectKind(mimeType, fileName)) {
            Kind.TXT -> parsePlainText(input, "txt")
            Kind.MD -> parsePlainText(input, "md")
            Kind.HTML -> parseHtml(input)
            Kind.DOCX -> Parsed(
                capText(OfficeTextExtractor.extractDocx(input)),
                "docx-ooxml-v1",
            )
            Kind.PPTX -> Parsed(
                capText(OfficeTextExtractor.extractPptx(input)),
                "pptx-ooxml-v1",
            )
            Kind.XLSX -> Parsed(
                capText(OfficeTextExtractor.extractXlsx(input)),
                "xlsx-ooxml-v1",
            )
            Kind.PDF -> throw DocumentParseException(
                "PDF 解析需要 Context/文件入口（请通过应用内导入流程重试）",
                PdfTextExtractor.PARSER_ID,
                PdfExtractionErrorCodes.REQUIRES_CONTEXT,
            )
            Kind.LEGACY_OFFICE -> throw legacyOfficeException(mimeType, fileName.orEmpty())
            Kind.UNSUPPORTED -> {
                // 可选兜底：扩展名/MIME 未识别但文件头是 OLE 复合文档（旧 .doc/.ppt/.xls/.msg 等）
                // → 明确 legacy Office 错误，而不是笼统"不支持"或当 txt 读出乱码。
                val header = runCatching {
                    readHeader(input, OLE_MAGIC.size)
                }.getOrNull()
                if (header != null && header.size == OLE_MAGIC.size && header.contentEquals(OLE_MAGIC)) {
                    throw legacyOfficeException(mimeType, fileName.orEmpty(), detectedByMagic = true)
                }
                throw unsupportedException(mimeType, fileName.orEmpty())
            }
        }

    /**
     * 导入流水线入口（P1-6）：按扩展名/MIME 分派；PDF 走 [PdfTextExtractor]（需要 Context，
     * 且逐页可取消，因此本入口是 suspend）。
     *
     * @param context Android Context（PDF 解析需要；非 PDF 可为 null）
     * @param file 文档文件（staging 已复制到 filesDir/documents）
     */
    suspend fun parse(
        context: Context?,
        file: File,
        mimeType: String? = null,
        fileName: String = file.name,
    ): Parsed {
        val kind = detectKind(mimeType, fileName)
        if (kind == Kind.PDF) return parsePdf(context, file)
        // P1 审查修复：Office 走内存 zip 解压，必须有源文件大小上限；避免低端机长时间卡死/OOM。
        if (kind in setOf(Kind.DOCX, Kind.PPTX, Kind.XLSX) &&
            file.length() > TextExtractionLimits.MAX_OFFICE_SOURCE_BYTES
        ) {
            throw DocumentParseException(
                "Office 文件过大（${file.length() / (1024 * 1024)}MB，上限 " +
                    "${TextExtractionLimits.MAX_OFFICE_SOURCE_BYTES / (1024 * 1024)}MB）：请精简后重试",
                "office-zip",
            )
        }
        currentCoroutineContext().ensureActive()
        val parsed = file.inputStream().use { parse(it, mimeType, fileName) }
        currentCoroutineContext().ensureActive()
        return parsed
    }

    /** PDF 解析：委托 [PdfTextExtractor]（PDFBox 初始化 + 逐页抽取/取消/预算）。 */
    internal suspend fun parsePdf(context: Context?, file: File): Parsed {
        val appContext = context ?: throw DocumentParseException(
            "PDF 解析需要 Android 上下文（内部错误）：请通过应用内导入流程重试",
            PdfTextExtractor.PARSER_ID,
            PdfExtractionErrorCodes.REQUIRES_CONTEXT,
        )
        val text = PdfTextExtractor.extract(appContext, file)
        return Parsed(capText(text), PdfTextExtractor.PARSER_ID)
    }

    internal fun capText(text: String): String =
        ExtractedTextCleanup.truncate(text, TextExtractionLimits.MAX_TEXT_CHARS)

    internal fun decodeUtf8(bytes: ByteArray): String {
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    // ---- 纯文本 / HTML ----

    private fun parsePlainText(input: InputStream, kind: String): Parsed {
        val parserId = "$kind-utf8-v1"
        val bytes = readBoundedInput(input, TextExtractionLimits.MAX_TEXT_INPUT_BYTES)
            ?: throw DocumentParseException(
                "文件过大（txt/md 超过 ${TextExtractionLimits.MAX_TEXT_INPUT_BYTES / (1024 * 1024)}MB）：" +
                    "请拆分后重试",
                parserId,
            )
        val text = capText(decodeUtf8(bytes))
        if (text.isBlank()) {
            throw DocumentParseException("文档没有可提取的文本（空文件或全是空白）", parserId)
        }
        return Parsed(text, parserId)
    }

    private fun parseHtml(input: InputStream): Parsed {
        val parserId = "html-text-v1"
        val bytes = readBoundedInput(input, TextExtractionLimits.MAX_HTML_DOCUMENT_BYTES)
            ?: throw DocumentParseException(
                "HTML 文件过大（超过 ${TextExtractionLimits.MAX_HTML_DOCUMENT_BYTES / (1024 * 1024)}MB）：" +
                    "请精简后重试",
                parserId,
            )
        val extracted = HtmlTextExtractor.extract(bytes)
        val text = buildString {
            if (extracted.title.isNotBlank()) append(extracted.title).append("\n\n")
            append(extracted.text)
        }.trim()
        if (text.isBlank()) {
            throw DocumentParseException("HTML 没有可提取的正文（可能是纯脚本/样式/空页面）", parserId)
        }
        return Parsed(capText(text), parserId)
    }

    private fun readBoundedInput(input: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---- 分派 ----

    private fun detectKind(mimeType: String?, fileName: String?): Kind {
        val ext = fileName.orEmpty().substringAfterLast('.', "").trim().lowercase()
        if (ext.isNotEmpty()) {
            when (ext) {
                "txt", "text", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "conf" -> return Kind.TXT
                "md", "markdown" -> return Kind.MD
                "html", "htm", "xhtml" -> return Kind.HTML
                "docx" -> return Kind.DOCX
                "pptx" -> return Kind.PPTX
                "xlsx" -> return Kind.XLSX
                "pdf" -> return Kind.PDF
                "doc", "ppt", "xls" -> return Kind.LEGACY_OFFICE
                "odt", "ods", "odp", "rtf", "epub", "pages",
                "zip", "7z", "rar", "tar", "gz", "bz2", "xz",
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg",
                "mp3", "wav", "flac", "aac", "m4a", "mp4", "mov", "avi", "mkv", "webm",
                "exe", "apk", "bin", "so", "dex", "litertlm", "task",
                -> return Kind.UNSUPPORTED
                else -> Unit
            }
        }
        val mime = mimeType.orEmpty().lowercase()
        if (mime.isNotBlank()) {
            when {
                mime.contains("markdown") -> return Kind.MD
                mime.contains("html") -> return Kind.HTML
                mime == "application/msword" ||
                    mime == "application/vnd.ms-powerpoint" ||
                    mime == "application/vnd.ms-excel" -> return Kind.LEGACY_OFFICE
                mime.contains("wordprocessingml") -> return Kind.DOCX
                mime.contains("presentationml") -> return Kind.PPTX
                mime.contains("spreadsheetml") -> return Kind.XLSX
                mime.contains("pdf") -> return Kind.PDF
                mime.startsWith("text/") -> return Kind.TXT
            }
        }
        // 兼容既有行为：完全没有类型线索时按 txt（如旧调用 parse(input)）；有文件名但扩展名未知则拒绝。
        return if (ext.isEmpty() && mime.isBlank()) Kind.TXT else Kind.UNSUPPORTED
    }

    /** OLE 复合文档文件头（旧 .doc/.ppt/.xls/.msg 等共用）：D0 CF 11 E0 A1 B1 1A E1。 */
    private val OLE_MAGIC: ByteArray = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    private fun unsupportedException(mimeType: String?, fileName: String): DocumentParseException {
        val ext = fileName.substringAfterLast('.', "").trim()
        val detail = when {
            ext.isNotBlank() -> "不支持的文件类型：.$ext"
            mimeType?.isNotBlank() == true -> "不支持的文件类型：$mimeType"
            else -> "不支持的文件类型"
        }
        return DocumentParseException(
            "$detail。支持 txt / md / pdf / docx / pptx / xlsx / html；" +
                "请转换为支持的格式后重试",
            "unsupported",
            DocumentParseErrorCodes.UNSUPPORTED_TYPE,
        )
    }

    /**
     * 旧版 Office（.doc/.ppt/.xls 二进制）明确不支持：给可读迁移指引 + 稳定错误码，
     * 避免被当作 OOXML 或纯文本解析。
     *
     * @param detectedByMagic 扩展名/MIME 未识别、由 OLE 文件头（D0 CF 11 E0）兜底判定
     */
    private fun legacyOfficeException(
        mimeType: String?,
        fileName: String,
        detectedByMagic: Boolean = false,
    ): DocumentParseException {
        val ext = fileName.substringAfterLast('.', "").trim().lowercase()
        val format = when {
            ext == "doc" || mimeType.equals("application/msword", ignoreCase = true) -> ".doc"
            ext == "ppt" || mimeType.equals("application/vnd.ms-powerpoint", ignoreCase = true) -> ".ppt"
            ext == "xls" || mimeType.equals("application/vnd.ms-excel", ignoreCase = true) -> ".xls"
            detectedByMagic -> "旧版 Office 二进制文档"
            else -> "旧版 Office 二进制格式"
        }
        return DocumentParseException(
            "$format 不支持（.doc/.ppt/.xls 为已淘汰的 Office 二进制格式）：" +
                "请用 Office/WPS 另存为 .docx / .pptx / .xlsx 后重新导入",
            "office-legacy",
            DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED,
        )
    }

    /** 读取输入流前 [size] 字节（用于 OLE 文件头兜底；输入流未消费部分不再使用，随后即抛错）。 */
    private fun readHeader(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

}
