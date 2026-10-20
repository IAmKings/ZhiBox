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

/** PDF 抽取结果（P1.1）：正文 + 实际使用的 parserId（`pdf-text-v1` / `pdf-ocr-v1`）。 */
data class PdfExtractionResult(
    val text: String,
    val parserId: String,
)

/**
 * P1：PDF 文本抽取（PDFBox-Android [com.tom-roush:pdfbox-android]，Apache-2.0）。
 *
 * 能力边界（P1.1 起，见 [extractWithMetadata]）：
 * - 优先抽取**文本层**（parserId=`pdf-text-v1`）；
 * - 文本层为空（扫描件/纯图片）且 [PdfOcrPreferences] 开启时，回退到 [PdfOcrExtractor] OCR
 *   （parserId=`pdf-ocr-v1`）；OCR 关闭时保持 [PdfExtractionErrorCodes.NO_TEXT] 可读失败；
 * - 加密 PDF：**owner-password-only**（user password 为空且权限允许抽取）支持；
 *   需要 **user password**（打开密码）或权限禁止复制/抽取文本（owner password 限制）→
 *   `PDF_ENCRYPTED` 可读失败；本类**不记录/不持久化密码**（仅用空 user password 尝试打开，不接收用户输入）；
 * - 损坏/畸形 PDF 返回可读失败；不记录正文/日志；
 * - 预算：文件大小上限、页数上限、输出字符上限，超限返回可读失败（不静默截断）；
 * - 取消：每页之间检查协程取消，`CancellationException` 原样传播（导入 worker 按取消语义处理）。
 *
 * PDFBox 初始化：`PDFBoxResourceLoader.init(context)` 幂等（`isReady` 判定 + 双检锁），
 * 只加载 fontbox 资源（标准 14 字体/CJK cmap），不联网。
 */
object PdfTextExtractor {

    /** document.parserId（文本层抽取成功写入）。 */
    const val PARSER_ID: String = "pdf-text-v1"

    /** 扫描件 OCR 回退成功时写入的 parserId。 */
    const val OCR_PARSER_ID: String = "pdf-ocr-v1"

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
     * 旧兼容入口：**仅文本层**抽取（不做 OCR 回退）；失败抛可读 [DocumentParseException]。
     *
     * @param context Android Context（PDFBox 资源初始化用；内部取 applicationContext）
     * @param file 待解析 PDF 文件
     */
    suspend fun extract(context: Context, file: File): String =
        extractTextLayer(context, file, password = null, limits = Limits.DEFAULT)

    /** 文本层抽取（可带 user password；仍是兼容入口，不做 OCR 回退）。 */
    suspend fun extract(context: Context, file: File, password: CharArray?): String =
        extractTextLayer(context, file, password = password, limits = Limits.DEFAULT)

    /** 单测用：文本层抽取 + 自定义预算（不做 OCR 回退；public 兼容旧测试的 internal 入口）。 */
    internal suspend fun extract(context: Context, file: File, limits: Limits): String =
        extractTextLayer(context, file, password = null, limits = limits)

    /**
     * P1.1 主入口：文本层优先；文本层为空且 OCR 开启时回退 [PdfOcrExtractor]。
     *
     * @param password 用户输入的 PDF 打开密码（可为 null）；由调用方负责清零
     *   （[com.zhibox.memory.core.ingest.IngestRepository] 从 [PdfPasswordVault] 取出后 finally 清零）；
     *   本类不记录/不持久化密码，只把它转成 PDFBox `load` 所需字符串。
     * @return [PdfExtractionResult]（parserId 区分 `pdf-text-v1` / `pdf-ocr-v1`）
     */
    suspend fun extractWithMetadata(
        context: Context,
        file: File,
        password: CharArray? = null,
    ): PdfExtractionResult = extractWithMetadata(
        context = context,
        file = file,
        password = password,
        ocrFallback = null,
        ocrEnabledOverride = null,
    )

    /**
     * 内部可注入版本（单测用 fake OCR/enabled override；生产入口走 [extractWithMetadata]）。
     *
     * @param ocrFallback 非 null 时覆盖默认 [PdfOcrExtractor]（JVM fake）
     * @param ocrEnabledOverride 非 null 时覆盖 [PdfOcrPreferences]（测试无需真实 SharedPreferences）
     */
    internal suspend fun extractWithMetadata(
        context: Context,
        file: File,
        password: CharArray?,
        ocrFallback: PdfOcrFallback?,
        ocrEnabledOverride: Boolean?,
    ): PdfExtractionResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val textResult = try {
            PdfExtractionResult(
                text = extractTextLayer(context, file, password, Limits.DEFAULT),
                parserId = PARSER_ID,
            )
        } catch (e: DocumentParseException) {
            if (e.errorCode != PdfExtractionErrorCodes.NO_TEXT) throw e
            val ocrEnabled = ocrEnabledOverride ?: PdfOcrPreferences.enabled(context)
            if (!ocrEnabled) {
                throw DocumentParseException(
                    "PDF 没有可提取的文本（可能是扫描件/纯图片 PDF）：可在设置中开启 PDF OCR 后重试",
                    PARSER_ID,
                    PdfExtractionErrorCodes.NO_TEXT,
                )
            }
            val fallback = ocrFallback ?: PdfOcrExtractor.default(context)
            PdfExtractionResult(
                text = fallback.extract(context, file),
                parserId = OCR_PARSER_ID,
            )
        }
        textResult
    }

    /** 文本层抽取（原 [extract] 实现；P1.1 拆出以便 OCR fallback 捕获 NO_TEXT）。 */
    private suspend fun extractTextLayer(
        context: Context,
        file: File,
        password: CharArray?,
        limits: Limits,
    ): String =
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
                val loadPassword = password?.concatToString() ?: ""
                document = try {
                    // 传入用户提供的 user password（无则空字符串，owner-password-only PDF 可直接打开）；
                    // 密码不记录/不持久化/不进错误文案，仅用于本次 PDFBox load。
                    PDDocument.load(file, loadPassword)
                } catch (e: InvalidPasswordException) {
                    val message = if (password != null) {
                        "PDF 打开密码不正确：请重新输入 user password 后重试"
                    } else {
                        "PDF 需要打开密码（user password），无法抽取文本：请输入密码后重试"
                    }
                    throw DocumentParseException(
                        message,
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
                    // owner-password-only：已用空 user password 打开；是否允许抽取取决于权限位。
                    val permission = try {
                        document.currentAccessPermission
                    } catch (e: RuntimeException) {
                        null
                    }
                    if (permission?.canExtractContent() != true) {
                        throw DocumentParseException(
                            "PDF 已加密且权限禁止复制/抽取文本（owner password 限制）：" +
                                "请用 PDF 工具解除限制后重新导入",
                            PARSER_ID,
                            PdfExtractionErrorCodes.ENCRYPTED,
                        )
                    }
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
