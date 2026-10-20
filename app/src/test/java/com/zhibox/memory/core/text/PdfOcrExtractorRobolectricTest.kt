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
 * P1.1b：PDF OCR 提取器（Robolectric + fake OcrEngine/PdfPageRasterizer）：
 * 全页 OCR（25+ 页无 20 截断）、maxPages 配置（0=全部）、页级 ocrPages 只处理指定页、
 * boundingBox 阅读顺序、旋转二遍取更多文本、字符预算、无文字/识别失败/渲染失败可读错误、
 * 取消传播、资源关闭与 Bitmap 回收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfOcrExtractorRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `extracts text from every page and closes resources`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf(lines("第一页文字"), lines("第二页文字")))
        val text = extractor(rasterizer, engine).extract(app, pdfFile())

        assertTrue("应包含第一页：$text", text.contains("第一页文字"))
        assertTrue("应包含第二页：$text", text.contains("第二页文字"))
        assertEquals("两页都应识别", 2, engine.calls)
        assertTrue("engine 必须关闭", engine.closed)
        assertTrue("rasterizer 必须关闭", rasterizer.closed)
        assertTrue("所有渲染位图必须回收", rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun `all pages are ocr-ed by default without 20 page cap`() = runBlocking {
        val pageCount = 30
        val rasterizer = FakeRasterizer(pageCount = pageCount)
        val engine = FakeEngine((0 until pageCount).map { lines("page-$it") })

        val text = extractor(
            rasterizer,
            engine,
            PdfOcrExtractor.OcrLimits(maxPages = 0, rotationMinChars = 0),
        ).extract(app, pdfFile())

        assertEquals("默认应 OCR 全部 30 页（无 20 页截断）", pageCount, engine.calls)
        for (index in 0 until pageCount) {
            assertTrue("缺少第 ${index + 1} 页文本：$text", text.contains("page-$index"))
        }
    }

    @Test
    fun `configurable maxPages still caps pages when non-zero`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 5)
        val engine = FakeEngine((0 until 5).map { lines("page-$it") })
        val limits = PdfOcrExtractor.OcrLimits(maxPages = 2, rotationMinChars = 0)
        val text = extractor(rasterizer, engine, limits).extract(app, pdfFile())

        assertEquals("maxPages=2 时只应识别前 2 页", 2, engine.calls)
        assertTrue(text.contains("page-0"))
        assertTrue(text.contains("page-1"))
        assertTrue("超页配置的页不应出现", !text.contains("page-2"))
    }

    @Test
    fun `page level ocrPages only processes requested indices`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 30)
        val engine = FakeEngine(listOf(lines("page-0"), lines("page-5"), lines("page-29")))
        val extractor = extractor(rasterizer, engine)

        val map = extractor.ocrPages(app, pdfFile(), intArrayOf(0, 5, 29))

        assertEquals(setOf(0, 5, 29), map.keys)
        assertEquals("只应识别指定页", 3, engine.calls)
        assertTrue(map.getValue(0).contains("page-0"))
        assertTrue(map.getValue(29).contains("page-29"))
    }

    @Test
    fun `lines are sorted by boundingBox reading order`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 1)
        // 故意乱序提供：同一行内 B 在 A 右边；第二行 C 在下
        val engine = FakeEngine(
            listOf(
                listOf(
                    OcrLine("B", left = 200, top = 10, right = 260, bottom = 30),
                    OcrLine("C", left = 10, top = 60, right = 60, bottom = 80),
                    OcrLine("A", left = 10, top = 12, right = 60, bottom = 32),
                ),
            ),
        )
        val text = extractor(rasterizer, engine).extract(app, pdfFile())
        assertEquals("应按行（top 聚类）→ 行内 left 排序：$text", "A\nB\nC", text)
    }

    @Test
    fun `rotation second pass picks text with more content`() = runBlocking {
        val rasterizer = FakeRasterizer(pageCount = 1)
        // 首遍 0° 无文字；第二遍（方图按实现选 90°）有文字 → 取第二遍
        val engine = FakeEngine(
            byRotation = mapOf(
                0 to emptyList(),
                90 to lines("旋转后的文字内容"),
            ),
        )
        val text = extractor(rasterizer, engine, PdfOcrExtractor.OcrLimits.DEFAULT).extract(app, pdfFile())

        assertTrue("应使用旋转二遍结果：$text", text.contains("旋转后的文字内容"))
        assertEquals("最多两遍", 2, engine.calls)
        assertEquals(listOf(0, 90), engine.rotations)
    }

    @Test
    fun `blank ocr result fails no text`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf(emptyList(), emptyList()))
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
        val engine = FakeEngine(listOf(lines("p1")), failureAtCall = 0)
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
        val engine = FakeEngine(listOf(lines("p1"), lines("p2")))
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
        val engine = FakeEngine(listOf(lines("0123456789")))
        val limits = PdfOcrExtractor.OcrLimits(maxTextChars = 5)
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine, limits).extract(app, pdfFile()) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
        assertTrue("应提示文本上限：${error.message}", error.message!!.contains("上限"))
    }

    @Test
    fun `page level cancellation is propagated`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf(lines("p1"), lines("p2")))
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
    fun `cancellation thrown from engine propagates and closes resources`() {
        val rasterizer = FakeRasterizer(pageCount = 2)
        val engine = FakeEngine(listOf(lines("p1"), lines("p2")), cancellationAtCall = 1)
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, pdfFile()) }
        }.exceptionOrNull()

        assertTrue("页级取消应原样传播：$error", error is CancellationException)
        assertTrue("取消也要关闭 engine", engine.closed)
        assertTrue("取消也要关闭 rasterizer", rasterizer.closed)
    }

    @Test
    fun `missing file fails readable`() {
        val rasterizer = FakeRasterizer(pageCount = 1)
        val engine = FakeEngine(listOf(lines("x")))
        val missing = File(app.cacheDir, "missing-${UUID.randomUUID()}.pdf")
        val error = runCatching {
            runBlocking { extractor(rasterizer, engine).extract(app, missing) }
        }.exceptionOrNull() as DocumentParseException

        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
    }

    // ---- fixtures ----

    private fun lines(vararg texts: String): List<OcrLine> =
        texts.mapIndexed { index, text ->
            OcrLine(text, left = 10, top = index * 24, right = 200, bottom = index * 24 + 18)
        }

    private fun pdfFile(): File =
        File(app.cacheDir, "ocr-${UUID.randomUUID()}.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    private fun extractor(
        rasterizer: PdfPageRasterizer,
        engine: OcrEngine,
        // 默认关闭旋转二遍，避免短测试文本触发额外 recognize 调用干扰计数；
        // 旋转二遍用例显式传 DEFAULT（rotationMinChars=8）。
        limits: PdfOcrExtractor.OcrLimits = PdfOcrExtractor.OcrLimits(rotationMinChars = 0),
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
        val bitmaps = mutableListOf<Bitmap>()

        override fun renderPage(index: Int, widthPx: Int): Bitmap {
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
        private val results: List<List<OcrLine>> = emptyList(),
        private val byRotation: Map<Int, List<OcrLine>>? = null,
        private val failureAtCall: Int = -1,
        private val cancellationAtCall: Int = -1,
    ) : OcrEngine {
        var calls = 0
        var closed = false
        val rotations = mutableListOf<Int>()

        override fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine> {
            val call = calls
            calls++
            rotations += rotationDegrees
            if (call == cancellationAtCall) throw CancellationException("fake page cancellation at $call")
            if (call == failureAtCall) error("fake ocr failure at $call")
            if (byRotation != null) return byRotation[rotationDegrees].orEmpty()
            return results.getOrElse(call) { emptyList() }
        }

        override fun close() {
            closed = true
        }
    }
}
