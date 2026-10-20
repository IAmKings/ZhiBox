package com.zhibox.memory.core.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** OCR 引擎抽象（JVM/Robolectric 用 fake；生产为 ML Kit 中文识别器）。 */
internal interface OcrEngine : AutoCloseable {
    /** 识别单页位图文字；失败抛异常（调用方转 PDF_OCR_FAILED 可读错误）。 */
    fun recognize(bitmap: Bitmap): String

    /** 释放识别器资源（实现可空操作）。 */
    override fun close()
}

/** PDF 页面光栅化抽象（生产用 [PdfRenderer]；JVM 测试用 fake，避免 native pdfium 依赖）。 */
internal interface PdfPageRasterizer : AutoCloseable {
    /** 总页数（>0）。 */
    val pageCount: Int

    /** 渲染第 [index] 页（0-based），宽度约 [widthPx]（实现可等比缩放）；返回 ARGB_8888 位图。 */
    fun renderPage(index: Int, widthPx: Int): Bitmap
}

/** OCR fallback 抽象：PdfTextExtractor 在"文本层为空"时调用（测试可注入 fake）。 */
internal fun interface PdfOcrFallback {
    suspend fun extract(context: Context, file: File): String
}

/**
 * P1.1：扫描件 PDF 的 OCR 文本回退（ML Kit 中文识别 + [PdfRenderer] 逐页渲染）。
 *
 * 流程：PDFRenderer 逐页渲染（宽 ≤ [OcrLimits.maxPageWidthPx] = 1600，ARGB_8888，白底）→
 * ML Kit `TextRecognition.getClient(ChineseTextRecognizerOptions)` 识别 → 拼接文本。
 *
 * 预算/资源：
 * - 页数上限 [OcrLimits.maxPages]=20（避免大文档长时间占用），超出的页忽略（不是错误）；
 * - 文本字符上限 [OcrLimits.maxTextChars]（超限可读失败，不 OOM）；
 * - 每页后回收 Bitmap；`finally` 关闭 recognizer / PDFRenderer / ParcelFileDescriptor；
 * - 每页检查协程取消；`CancellationException` 原样传播。
 *
 * 错误码（[PdfOcrErrorCodes]）：渲染/识别失败 = `PDF_OCR_FAILED`；全部页无可识别文字 = `PDF_OCR_NO_TEXT`。
 * 不写日志、不联网（ML Kit bundled 离线模型）、不持久化页面图像。
 */
internal class PdfOcrExtractor(
    private val engineFactory: () -> OcrEngine,
    private val rasterizerFactory: (File) -> PdfPageRasterizer,
    private val limits: OcrLimits = OcrLimits.DEFAULT,
) : PdfOcrFallback {

    internal data class OcrLimits(
        val maxPages: Int = 20,
        val maxPageWidthPx: Int = 1600,
        val maxTextChars: Int = TextExtractionLimits.MAX_TEXT_CHARS,
    ) {
        companion object {
            val DEFAULT: OcrLimits = OcrLimits()
        }
    }

    override suspend fun extract(context: Context, file: File): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!file.isFile) {
            throw DocumentParseException(
                "PDF OCR 失败：文件不存在或不可读（${file.name}）",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
        val rasterizer = try {
            rasterizerFactory(file)
        } catch (e: Exception) {
            throw DocumentParseException(
                "PDF OCR 初始化失败：无法读取 PDF 页面（文件可能已损坏）",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
        val engine = try {
            engineFactory()
        } catch (e: Exception) {
            runCatching { rasterizer.close() }
            throw DocumentParseException(
                "PDF OCR 初始化失败：文字识别组件不可用，请重启应用后重试",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
        try {
            val pageCount = try {
                rasterizer.pageCount
            } catch (e: Exception) {
                0
            }
            if (pageCount <= 0) {
                throw DocumentParseException(
                    "PDF OCR 未识别到文字：PDF 没有可渲染页面",
                    PARSER_ID,
                    PdfOcrErrorCodes.NO_TEXT,
                )
            }
            val pagesToOcr = minOf(pageCount, limits.maxPages)
            val text = StringBuilder()
            for (index in 0 until pagesToOcr) {
                currentCoroutineContext().ensureActive()
                val bitmap = try {
                    rasterizer.renderPage(index, limits.maxPageWidthPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw DocumentParseException(
                        "PDF OCR 失败：第 ${index + 1} 页渲染失败（文件可能已损坏）",
                        PARSER_ID,
                        PdfOcrErrorCodes.FAILED,
                    )
                }
                try {
                    val pageText = try {
                        engine.recognize(bitmap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw DocumentParseException(
                            "PDF OCR 失败：第 ${index + 1} 页文字识别失败，请重试",
                            PARSER_ID,
                            PdfOcrErrorCodes.FAILED,
                        )
                    }
                    if (pageText.isNotBlank()) {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(pageText)
                        if (text.length > limits.maxTextChars) {
                            throw DocumentParseException(
                                "PDF OCR 文本超过上限（${limits.maxTextChars} 字符）：请拆分文档后重试",
                                PARSER_ID,
                                PdfOcrErrorCodes.FAILED,
                            )
                        }
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            currentCoroutineContext().ensureActive()
            val normalized = ExtractedTextCleanup.normalize(text.toString())
            if (normalized.isBlank()) {
                throw DocumentParseException(
                    "PDF OCR 未识别到文字（扫描件可能过模糊或为空白页）：请确认文件清晰后重试",
                    PARSER_ID,
                    PdfOcrErrorCodes.NO_TEXT,
                )
            }
            normalized
        } finally {
            runCatching { engine.close() }
            runCatching { rasterizer.close() }
        }
    }

    companion object {
        const val PARSER_ID: String = "pdf-ocr-v1"

        /** 生产实现：ML Kit 中文识别 + Android PDFRenderer（识别全程离线，不联网）。 */
        fun default(@Suppress("UNUSED_PARAMETER") context: Context): PdfOcrExtractor =
            PdfOcrExtractor(
                engineFactory = { MlKitOcrEngine() },
                rasterizerFactory = { file -> PdfRendererRasterizer(file) },
            )
    }
}

/** PDF OCR 稳定错误码。 */
object PdfOcrErrorCodes {
    const val FAILED: String = "PDF_OCR_FAILED"
    const val NO_TEXT: String = "PDF_OCR_NO_TEXT"
}

/** ML Kit 中文文字识别引擎（bundled 离线模型；无网络）。 */
private class MlKitOcrEngine : OcrEngine, AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(bitmap: Bitmap): String =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text

    override fun close() {
        recognizer.close()
    }
}

/** 生产 PDF 页面光栅化：PdfRenderer + ParcelFileDescriptor（ARGB_8888、白底、宽 ≤ 1600）。 */
private class PdfRendererRasterizer(file: File) : PdfPageRasterizer {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    override val pageCount: Int
        get() = renderer.pageCount

    override fun renderPage(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val sourceWidth = maxOf(page.width, 1)
            // 约 2x 渲染提升 OCR 精度，但宽度不超过上限（文本型小页面也能被识别）
            val targetWidth = minOf(widthPx, maxOf(sourceWidth, 1) * 2)
            val targetHeight = maxOf(1, (page.height * (targetWidth.toFloat() / sourceWidth)).toInt())
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
