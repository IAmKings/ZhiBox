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

/** ML Kit 单行识别结果（boundingBox 用于阅读顺序排序；坐标单位 px）。 */
internal data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * OCR 引擎抽象（JVM/Robolectric 用 fake；生产为 ML Kit 中文识别器）。
 *
 * [rotationDegrees] = 0/90/180/270，表示把位图旋转多少度后识别（ML Kit InputImage rotation）；
 * 返回**未排序**的行（含 boundingBox），由调用方做阅读顺序排序。
 */
internal interface OcrEngine : AutoCloseable {
    fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): List<OcrLine>

    override fun close()
}

/** PDF 页面光栅化抽象（生产用 [PdfRenderer]；JVM 测试用 fake，避免 native pdfium 依赖）。 */
internal interface PdfPageRasterizer : AutoCloseable {
    val pageCount: Int

    /** 渲染第 [index] 页（0-based），宽度约 [widthPx]（实现可等比缩放）；返回 ARGB_8888 位图。 */
    fun renderPage(index: Int, widthPx: Int): Bitmap
}

/**
 * 页级 OCR 抽象（P1.1b）：只识别指定的 0-based 页，返回 pageIndex→文本（空白页可缺失或空串）。
 *
 * 与整文档 fallback 相比，页级接口让 [PdfTextExtractor] 能实现"逐页文本层 + 仅空页 OCR"的混合策略，
 * 且一次调用只初始化一次 recognizer / PDFRenderer。
 */
internal fun interface PdfPageOcr {
    suspend fun ocrPages(context: Context, file: File, pageIndices: IntArray): Map<Int, String>
}

/**
 * P1.1b：扫描件 PDF 的 OCR（ML Kit 中文识别 + [PdfRenderer] 逐页渲染，离线）。
 *
 * 质量/预算：
 * - **不再固定 20 页上限**：[OcrLimits.maxPages] 默认 0 = **全部页**；>0 时仅处理前 N 页（测试/降级配置）；
 * - 渲染宽度 [OcrLimits.maxPageWidthPx] 默认 **2400**，实现按源页宽 3x 放大并封顶（低分辨率页优先放大）；
 *   高度封顶 [OcrLimits.maxPageHeightPx] 防超长页 OOM；
 * - 阅读顺序：ML Kit block/line 的 boundingBox → 简单"行（top 聚类）内按 left"排序；
 *   **多栏文档可能按视觉行交错，不保证栏顺序**（注释说明限制）；
 * - 旋转二遍：首遍 0°；若文本过少（< [OcrLimits.rotationMinChars]），第二遍按页面方向尝试
 *   90°（横向/方图）或 180°（纵向），取文本更多者；每页最多 [OcrLimits.maxRecognizePassesPerPage]=2 遍；
 * - 文本字符预算 [OcrLimits.maxTextChars]（超限可读失败，不 OOM）；
 * - 每页后回收 Bitmap；`finally` 关闭 recognizer / PDFRenderer / ParcelFileDescriptor；
 * - 每页检查协程取消；`CancellationException` 原样传播；不写日志/不联网/不持久化页面图像。
 *
 * 错误码（[PdfOcrErrorCodes]）：渲染/识别失败 = `PDF_OCR_FAILED`；整文档无可识别文字 = `PDF_OCR_NO_TEXT`。
 */
internal class PdfOcrExtractor(
    private val engineFactory: () -> OcrEngine,
    private val rasterizerFactory: (File) -> PdfPageRasterizer,
    private val limits: OcrLimits = OcrLimits.DEFAULT,
) : PdfPageOcr {

    internal data class OcrLimits(
        /** 0 = 不限制（默认全部页）；>0 时仅处理前 N 页（测试/诊断/降级）。 */
        val maxPages: Int = 0,
        /** 渲染目标宽度（实现同时按源页 3x 放大并封顶该值）。 */
        val maxPageWidthPx: Int = 2400,
        /** 渲染目标高度上限，防止超长页位图 OOM。 */
        val maxPageHeightPx: Int = 4096,
        val maxTextChars: Int = TextExtractionLimits.MAX_TEXT_CHARS,
        /** 首遍文本少于该字符数时触发旋转二遍。 */
        val rotationMinChars: Int = 8,
        /** 每页最多识别遍数（含首遍 0°）；上限 2 控制耗时/预算。 */
        val maxRecognizePassesPerPage: Int = 2,
    ) {
        companion object {
            val DEFAULT: OcrLimits = OcrLimits()
        }
    }

    /**
     * 页级 OCR：只处理 [pageIndices]（0-based，调用方保证合法）；返回 pageIndex→非空文本。
     * 空白页不出现在结果中；任一页渲染/识别失败抛可读 [DocumentParseException]（PDF_OCR_FAILED）。
     */
    override suspend fun ocrPages(
        context: Context,
        file: File,
        pageIndices: IntArray,
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        validateFile(file)
        if (pageIndices.isEmpty()) return@withContext emptyMap()
        val session = openSession(file)
        try {
            session.ocr(pageIndices)
        } finally {
            session.close()
        }
    }

    /**
     * 整文档兼容入口（P1.1 旧 API）：默认 OCR 全部页（[OcrLimits.maxPages]=0），返回按页序拼接文本；
     * 无文字抛 PDF_OCR_NO_TEXT。混合 PDF 请走 [PdfPageOcr.ocrPages]（[PdfTextExtractor] 使用）。
     */
    suspend fun extract(context: Context, file: File): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        validateFile(file)
        val session = openSession(file)
        try {
            val allIndices = IntArray(session.pageCount) { it }
            val indices = if (limits.maxPages <= 0) allIndices else allIndices.copyOfRange(
                0, minOf(allIndices.size, limits.maxPages),
            )
            val texts = session.ocr(indices)
            val normalized = ExtractedTextCleanup.normalize(
                indices.toList()
                    .mapNotNull { texts[it]?.takeIf { text -> text.isNotBlank() } }
                    .joinToString("\n"),
            )
            if (normalized.isBlank()) {
                throw DocumentParseException(
                    "PDF OCR 未识别到文字（扫描件可能过模糊或为空白页）：请确认文件清晰后重试",
                    PARSER_ID,
                    PdfOcrErrorCodes.NO_TEXT,
                )
            }
            normalized
        } finally {
            session.close()
        }
    }

    // ---- 内部会话（一次打开 renderer+engine，供页级/整文档共用） ----

    private fun validateFile(file: File) {
        if (!file.isFile) {
            throw DocumentParseException(
                "PDF OCR 失败：文件不存在或不可读（${file.name}）",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
    }

    private fun openSession(file: File): Session {
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
        return try {
            Session(rasterizer, engine, limits)
        } catch (e: Exception) {
            runCatching { engine.close() }
            runCatching { rasterizer.close() }
            throw DocumentParseException(
                "PDF OCR 初始化失败：PDF 页面不可用（文件可能已损坏）",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
    }

    private inner class Session(
        private val rasterizer: PdfPageRasterizer,
        private val engine: OcrEngine,
        private val limits: OcrLimits,
    ) : AutoCloseable {

        val pageCount: Int = try {
            rasterizer.pageCount
        } catch (e: Exception) {
            0
        }

        suspend fun ocr(pageIndices: IntArray): Map<Int, String> {
            currentCoroutineContext().ensureActive()
            if (pageCount <= 0) {
                throw DocumentParseException(
                    "PDF OCR 未识别到文字：PDF 没有可渲染页面",
                    PARSER_ID,
                    PdfOcrErrorCodes.NO_TEXT,
                )
            }
            val result = LinkedHashMap<Int, String>()
            var totalChars = 0
            for (index in pageIndices) {
                if (index < 0 || index >= pageCount) continue
                currentCoroutineContext().ensureActive()
                val bitmap = renderPage(index)
                try {
                    val lines = recognizeWithRotation(bitmap)
                    val pageText = linesToText(lines)
                    if (pageText.isNotBlank()) {
                        totalChars += pageText.length
                        if (totalChars > limits.maxTextChars) {
                            throw DocumentParseException(
                                "PDF OCR 文本超过上限（${limits.maxTextChars} 字符）：请拆分文档后重试",
                                PARSER_ID,
                                PdfOcrErrorCodes.FAILED,
                            )
                        }
                        result[index] = pageText
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            return result
        }

        private fun renderPage(index: Int): Bitmap = try {
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

        /**
         * 识别一遍或两遍：首遍 0°；文本过少时按方向做第二遍（横向/方图 90°，纵向 180°），
         * 取文本更多者（最多 [OcrLimits.maxRecognizePassesPerPage] 遍）。
         */
        private fun recognizeWithRotation(bitmap: Bitmap): List<OcrLine> {
            var best = recognize(bitmap, 0)
            var bestLength = linesToText(best).length
            var passes = 1
            while (passes < limits.maxRecognizePassesPerPage && bestLength < limits.rotationMinChars) {
                val rotation = if (passes == 1 && bitmap.width >= bitmap.height) 90 else 180
                val candidate = recognize(bitmap, rotation)
                passes++
                val candidateLength = linesToText(candidate).length
                if (candidateLength > bestLength) {
                    best = candidate
                    bestLength = candidateLength
                }
            }
            return best
        }

        private fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine> = try {
            engine.recognize(bitmap, rotationDegrees)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DocumentParseException(
                "PDF OCR 失败：文字识别失败，请重试",
                PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }

        override fun close() {
            runCatching { engine.close() }
            runCatching { rasterizer.close() }
        }
    }

    companion object {
        const val PARSER_ID: String = "pdf-ocr-v1"

        /** 生产实现：ML Kit 中文识别 + Android PDFRenderer（离线，不联网）。 */
        fun default(@Suppress("UNUSED_PARAMETER") context: Context): PdfOcrExtractor =
            PdfOcrExtractor(
                engineFactory = { MlKitOcrEngine() },
                rasterizerFactory = { file -> PdfRendererRasterizer(file) },
            )

        /**
         * boundingBox 阅读顺序（简单行列）：
         * 1) 按 top 升序，按"垂直重叠/行高一半"聚类成行；
         * 2) 行内按 left 升序；
         * 3) 行按首行 top 升序输出。
         *
         * 限制：多栏文档同一水平带的两栏行会被拼成视觉行（栏间交错）；复杂版式（表格/浮动元素）
         * 仅做保守排序，不保证与原阅读意图完全一致。单栏文档效果稳定。
         */
        fun sortLinesForReadingOrder(lines: List<OcrLine>): List<OcrLine> {
            val nonBlank = lines.filter { it.text.isNotBlank() }
            if (nonBlank.size <= 1) return nonBlank
            val sortedByTop = nonBlank.sortedWith(compareBy({ it.top }, { it.left }))
            val rows = ArrayList<MutableList<OcrLine>>()
            for (line in sortedByTop) {
                val current = rows.lastOrNull()
                if (current != null) {
                    val anchor = current.first()
                    val tolerance = maxOf(anchor.height, line.height) / 2
                    if (kotlin.math.abs(line.top - anchor.top) <= tolerance) {
                        current.add(line)
                        continue
                    }
                }
                rows.add(mutableListOf(line))
            }
            return rows.flatMap { row -> row.sortedBy { it.left } }
        }

        /** 行 → 文本（阅读顺序 + 去掉空行）。 */
        fun linesToText(lines: List<OcrLine>): String =
            sortLinesForReadingOrder(lines)
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
    }
}

/** PDF OCR 稳定错误码。 */
object PdfOcrErrorCodes {
    const val FAILED: String = "PDF_OCR_FAILED"
    const val NO_TEXT: String = "PDF_OCR_NO_TEXT"
}

/** ML Kit 中文文字识别引擎（bundled 离线模型；无网络）。 */
private class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val text = Tasks.await(recognizer.process(image))
        return text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                OcrLine(
                    text = line.text,
                    left = box?.left ?: 0,
                    top = box?.top ?: 0,
                    right = box?.right ?: 0,
                    bottom = box?.bottom ?: 0,
                )
            }
        }
    }

    override fun close() {
        recognizer.close()
    }
}

/** 生产 PDF 页面光栅化：PdfRenderer + ParcelFileDescriptor（ARGB_8888、白底、3x 放大封顶 2400x4096）。 */
private class PdfRendererRasterizer(file: File) : PdfPageRasterizer {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    override val pageCount: Int
        get() = renderer.pageCount

    override fun renderPage(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val sourceWidth = maxOf(page.width, 1)
            val sourceHeight = maxOf(page.height, 1)
            // 低分辨率页优先放大（3x），宽度封顶 widthPx（默认 2400）；超长页按高度上限回退
            var targetWidth = minOf(widthPx, sourceWidth * 3)
            var scale = targetWidth.toFloat() / sourceWidth
            val maxHeight = MAX_RENDER_HEIGHT_PX
            val scaledHeight = (sourceHeight * scale).toInt()
            if (scaledHeight > maxHeight) {
                scale = maxHeight.toFloat() / sourceHeight
                targetWidth = maxOf(1, (sourceWidth * scale).toInt())
            }
            val targetHeight = maxOf(1, (sourceHeight * scale).toInt())
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

    private companion object {
        const val MAX_RENDER_HEIGHT_PX = 4096
    }
}
