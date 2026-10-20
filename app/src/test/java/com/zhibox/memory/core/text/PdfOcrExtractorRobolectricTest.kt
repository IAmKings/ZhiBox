package com.zhibox.memory.core.text

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * P1.1：PDF OCR 提取器（Robolectric + fake OcrEngine/PdfPageRasterizer，不依赖 ML Kit/native pdfium）：
 * 逐页渲染 + 识别文本拼接、页数上限、文本预算、无文字/识别失败/渲染失败可读错误、
 * 取消传播、资源关闭（engine/rasterizer）与 Bitmap 回收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfOcrExtractorRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `extracts text from every page and closes resources`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf("第一页文字", "第二页文字"))
        val extractor = extractor(rasterizer, engine)

        val text = extractor.extract(app, pdfFile())

        assertTrue("应包含第一页：$text", text.contains("第一页文字"))
        assertTrue("应包含第二页：$text", text.contains("第二页文字"))
        assertEquals("两页都应识别", 2, engine.calls)
        assertTrue("engine 必须关闭", engine.closed)
        assertTrue("rasterizer 必须关闭", rasterizer.closed)
        assertTrue("所有渲染位图必须回收", rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun `page count is capped by limits`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 5)
        val engine = FakeEngine(listOf("p1", "p2", "p3", "p4", "p5"))
        val extractor = extractor(rasterizer, engine, PdfOcrExtractor.OcrLimits(maxPages = 2))

        val text = extractor.extract(app, pdfFile())

        assertEquals("只应识别前 2 页", 2, engine.calls)
        assertTrue(text.contains("p1"))
        assertTrue(text.contains("p2"))
        assertTrue("超页上限的页不应出现", !text.contains("p3"))
    }

    @Test
    fun `blank ocr result fails no text`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf("", ""))
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, pdfFile()) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.NO_TEXT, error.errorCode)
        assertTrue("engine 失败路径也要关闭", engine.closed)
        assertTrue(rasterizer.closed)
    }

    @Test
    fun `recognizer failure fails readable and closes resources`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf("p1", "p2"), failureAt = 0)
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, pdfFile()) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
        assertTrue("应提示识别失败：${error.message}", error.message!!.contains("识别失败"))
        assertTrue(engine.closed)
        assertTrue(rasterizer.closed)
        assertTrue("失败页位图也要回收", rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun `render failure fails readable and closes resources`() {
        val rasterizer = FakeRasterizer(pageCount = 2, renderFailureAt = 1)
        val engine = FakeEngine(listOf("p1", "p2"))
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, pdfFile()) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
        assertTrue("应提示渲染失败：${error.message}", error.message!!.contains("渲染失败"))
        assertTrue(engine.closed)
        assertTrue(rasterizer.closed)
    }

    @Test
    fun `text budget exceeding fails readable`() {
        val rasterizer = FakeRasterizer(pageCount = 1)
        val engine = FakeEngine(listOf("0123456789"))
        val limits = PdfOcrExtractor.OcrLimits(maxTextChars = 5)
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine, limits).extract(app, pdfFile()) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
        assertTrue("应提示文本上限：${error.message}", error.message!!.contains("上限"))
    }

    @Test
    fun `cancellation is propagated`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf("p1", "p2"))
        val cancelledParent = Job().apply { cancel() }
        val error = runCatching {
            runBlocking {
                withContext(cancelledParent) {
                    extractor(rasterizer, engine).extract(app, pdfFile())
                }
            }
        }.exceptionOrNull()

        assertTrue("应为 CancellationException：$error", error is CancellationException)
    }

    @Test
    fun `missing file fails readable`() {
        val rasterizer = FakeRasterizer(pageCount = 1)
        val engine = FakeEngine(listOf("x"))
        val missing = File(app.cacheDir, "missing-${UUID.randomUUID()}.pdf")
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, missing) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
    }

    // ---- fixtures ----

    private fun pdfFile(): File =
        File(app.cacheDir, "ocr-${UUID.randomUUID()}.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    private fun extractor(
        rasterizer: PdfPageRasterizer,
        engine: OcrEngine,
        limits: PdfOcrExtractor.OcrLimits = PdfOcrExtractor.OcrLimits.DEFAULT,
    ): PdfOcrExtractor = PdfOcrExtractor(
        engineFactory = { engine },
        rasterizerFactory = { rasterizer },
        limits = limits,
    )

    private class FakeRasterizer(
        override val pageCount: Int,
        private val renderFailureAt: Int = -1,
    ) : PdfPageRasterizer {
        var closed = false
        val rendered = mutableListOf<Int>()
        val bitmaps = mutableListOf<Bitmap>()

        override fun renderPage(index: Int, widthPx: Int): Bitmap {
            rendered += index
            if (index == renderFailureAt) error("fake render failure at $index")
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                bitmaps += it
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeEngine(
        private val results: List<String>,
        private val failureAt: Int = -1,
    ) : OcrEngine {
        var calls = 0
        var closed = false

        override fun recognize(bitmap: Bitmap): String {
            val index = calls
            calls++
            if (index == failureAt) error("fake ocr failure at $index")
            return results.getOrElse(index) { "" }
        }

        override fun close() {
            closed = true
        }
    }
}
