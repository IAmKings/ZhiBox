package com.zhibox.memory.core.text

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * P1：PDF 文本抽取（PDFBox-Android [com.tom-roush:pdfbox-android]，Apache-2.0）。
 *
 * 能力边界（与产品约定一致）：
 * - **仅支持文本型 PDF**（有可提取文字层）；扫描件/纯图片 PDF 无法抽取（不引入 OCR），
 *   返回可读失败（code [PdfExtractionErrorCodes.NO_TEXT]）；
 * - 加密/受密码保护 PDF（`InvalidPasswordException` 或 `isEncrypted`）返回可读失败；
 * - 损坏/畸形 PDF 返回可读失败；不记录正文/日志；
 * - 预算：文件大小上限、页数上限、输出字符上限，超限返回可读失败（不静默截断）；
 * - 取消：每页之间检查协程取消，`CancellationException` 原样传播（导入 worker 按取消语义处理）。
 *
 * PDFBox 初始化：`PDFBoxResourceLoader.init(context)` 幂等（`isReady` 判定 + 双检锁），
 * 只加载 fontbox 资源（标准 14 字体/CJK cmap），不联网。
 */
object PdfTextExtractor {

    /** document.parserId（PDF 成功后写入）。 */
    const val PARSER_ID: String = "pdf-text-v1"

    internal data class Limits(
        val maxFileBytes: Long = 50L * 1024 * 1024,
        val maxPages: Int = 500,
        val maxTextChars: Int = TextExtractionLimits.MAX_TEXT_CHARS,
    ) {
        companion object {
            val DEFAULT: Limits = Limits()
        }
    }

    private val initLock = Any()

    /**
     * 抽取 PDF 文本；失败抛可读 [DocumentParseException]（带稳定 [DocumentParseException.errorCode]）。
     *
     * @param context Android Context（PDFBox 资源初始化用；内部取 applicationContext）
     * @param file 待解析 PDF 文件
     */
    suspend fun extract(context: Context, file: File): String =
        extract(context, file, Limits.DEFAULT)

    internal suspend fun extract(context: Context, file: File, limits: Limits): String =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!file.isFile) {
                throw DocumentParseException(
                    "PDF 文件不存在或不可读：${file.name}",
                    PARSER_ID,
                    PdfExtractionErrorCodes.NOT_FOUND,
                )
            }
            if (file.length() > limits.maxFileBytes) {
                throw DocumentParseException(
                    "PDF 文件过大（${file.length() / (1024 * 1024)}MB，上限 " +
                        "${limits.maxFileBytes / (1024 * 1024)}MB）：请拆分后重试",
                    PARSER_ID,
                    PdfExtractionErrorCodes.TOO_LARGE,
                )
            }
            ensureInitialized(context)
            currentCoroutineContext().ensureActive()

            var document: PDDocument? = null
            try {
                document = try {
                    PDDocument.load(file)
                } catch (e: InvalidPasswordException) {
                    throw DocumentParseException(
                        "PDF 已加密/受密码保护，无法抽取文本：请解除密码后重试（本版本不支持加密 PDF）",
                        PARSER_ID,
                        PdfExtractionErrorCodes.ENCRYPTED,
                    )
                } catch (e: IOException) {
                    throw DocumentParseException(
                        "PDF 文件损坏或无法解析（${e.javaClass.simpleName}）：请确认文件完整后重试",
                        PARSER_ID,
                        PdfExtractionErrorCodes.CORRUPT,
                    )
                } catch (e: RuntimeException) {
                    throw DocumentParseException(
                        "PDF 文件损坏或格式不受支持（${e.javaClass.simpleName}）：请确认文件完整后重试",
                        PARSER_ID,
                        PdfExtractionErrorCodes.CORRUPT,
                    )
                }
                if (document.isEncrypted) {
                    throw DocumentParseException(
                        "PDF 已加密/受密码保护，无法抽取文本：请解除密码后重试（本版本不支持加密 PDF）",
                        PARSER_ID,
                        PdfExtractionErrorCodes.ENCRYPTED,
                    )
                }
                val pageCount = try {
                    document.numberOfPages
                } catch (e: RuntimeException) {
                    0
                }
                if (pageCount <= 0) {
                    throw DocumentParseException(
                        "PDF 没有页面或页面结构损坏，无法抽取文本",
                        PARSER_ID,
                        PdfExtractionErrorCodes.CORRUPT,
                    )
                }
                if (pageCount > limits.maxPages) {
                    throw DocumentParseException(
                        "PDF 页数过多（$pageCount 页，上限 ${limits.maxPages} 页）：请拆分后重试",
                        PARSER_ID,
                        PdfExtractionErrorCodes.TOO_MANY_PAGES,
                    )
                }

                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val text = StringBuilder()
                for (page in 1..pageCount) {
                    // 逐页检查取消：PDFBox 单页解析不可中断，但页面粒度足以让用户取消及时生效
                    currentCoroutineContext().ensureActive()
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = try {
                        stripper.getText(document)
                    } catch (e: IOException) {
                        throw DocumentParseException(
                            "PDF 第 $page 页解析失败（${e.javaClass.simpleName}）：文件可能损坏",
                            PARSER_ID,
                            PdfExtractionErrorCodes.CORRUPT,
                        )
                    }
                    text.append(pageText)
                    if (text.length > limits.maxTextChars) {
                        throw DocumentParseException(
                            "PDF 文本超过上限（${limits.maxTextChars} 字符）：请拆分文档后重试",
                            PARSER_ID,
                            PdfExtractionErrorCodes.TEXT_TOO_LONG,
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
                val extracted = ExtractedTextCleanup.normalize(text.toString())
                if (extracted.isBlank()) {
                    throw DocumentParseException(
                        "PDF 没有可提取的文本（可能是扫描件/纯图片 PDF）：本版本仅支持文本型 PDF",
                        PARSER_ID,
                        PdfExtractionErrorCodes.NO_TEXT,
                    )
                }
                extracted
            } catch (e: CancellationException) {
                throw e // 取消原样传播（不包装成解析失败）
            } catch (e: LinkageError) {
                // PDFBox 的 JPX/JPEG2000 可选解码器未打包时，含该图片的 PDF 可能抛
                // NoClassDefFoundError；不能让它穿透 worker 杀进程（R8 仅 -dontwarn）。
                throw DocumentParseException(
                    "PDF 含不支持的图片编码（JPEG2000/JPX），本版本仅支持文本抽取：请换用其他 PDF",
                    PARSER_ID,
                    PdfExtractionErrorCodes.CORRUPT,
                )
            } finally {
                runCatching { document?.close() }
            }
        }

    /** 幂等初始化 PDFBox 资源；失败给可读错误（不泄漏内部细节）。 */
    private fun ensureInitialized(context: Context) {
        if (PDFBoxResourceLoader.isReady()) return
        synchronized(initLock) {
            if (PDFBoxResourceLoader.isReady()) return
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
            } catch (e: Exception) {
                throw DocumentParseException(
                    "PDF 解析初始化失败：请重启应用后重试",
                    PARSER_ID,
                    PdfExtractionErrorCodes.INIT,
                )
            }
        }
    }
}

/** PDF 抽取稳定错误码（写入 DocumentParseException.errorCode；导入层可诊断/可读展示）。 */
object PdfExtractionErrorCodes {
    const val NOT_FOUND: String = "PDF_NOT_FOUND"
    const val TOO_LARGE: String = "PDF_TOO_LARGE"
    const val TOO_MANY_PAGES: String = "PDF_TOO_MANY_PAGES"
    const val TEXT_TOO_LONG: String = "PDF_TEXT_TOO_LONG"
    const val ENCRYPTED: String = "PDF_ENCRYPTED"
    const val CORRUPT: String = "PDF_CORRUPT"
    const val NO_TEXT: String = "PDF_NO_TEXT"
    const val INIT: String = "PDF_INIT_FAILED"
    const val REQUIRES_CONTEXT: String = "PDF_REQUIRES_CONTEXT"
}
