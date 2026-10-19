package com.zhibox.memory.core.text

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
 * P1 PDF 文本抽取（Robolectric + PDFBox-Android，本地 JVM，无真机/无网络）：
 * 最小文本 PDF 抽取、DocumentParser 分派、损坏/无文本/文件/页数/字符超限、取消传播。
 *
 * 说明：PDFBox-Android 需要 Android Context 做资源初始化，故用 Robolectric；扫描件 OCR/加密
 * PDF 解密不在本版本能力范围（加密直接返回可读失败，测试用损坏文件覆盖失败分支）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextExtractorRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `extracts text from minimal text pdf`() = runBlocking {
        val text = PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello PDF"))))
        assertTrue("应抽取到文本：$text", text.contains("Hello PDF"))
    }

    @Test
    fun `document parser dispatches pdf to extractor`() = runBlocking {
        val file = pdfFile(buildPdf(listOf("Dispatch PDF")))
        val parsed = DocumentParser.parse(app, file, null, file.name)
        assertEquals(PdfTextExtractor.PARSER_ID, parsed.parserId)
        assertTrue(parsed.text.contains("Dispatch PDF"))
    }

    @Test
    fun `corrupt pdf fails with readable code`() {
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile("not a pdf at all".toByteArray())) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.CORRUPT, error.errorCode)
        assertTrue("错误应可读：${error.message}", error.message!!.contains("损坏"))
    }

    @Test
    fun `textless pdf fails no text`() {
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("")))) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.NO_TEXT, error.errorCode)
        assertTrue(error.message!!.contains("扫描件"))
    }

    @Test
    fun `file size limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxFileBytes = 10)
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello"))), limits) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TOO_LARGE, error.errorCode)
    }

    @Test
    fun `page limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxPages = 1)
        val error = runCatching {
            runBlocking {
                PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Page one", "Page two"))), limits)
            }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TOO_MANY_PAGES, error.errorCode)
    }

    @Test
    fun `text char limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxTextChars = 5)
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello PDF"))), limits) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TEXT_TOO_LONG, error.errorCode)
    }

    @Test
    fun `cancellation is propagated`() {
        val file = pdfFile(buildPdf(listOf("Hello PDF")))
        val cancelledParent = Job().apply { cancel() }
        val error = runCatching {
            runBlocking {
                withContext(cancelledParent) { PdfTextExtractor.extract(app, file) }
            }
        }.exceptionOrNull()
        assertTrue("应为 CancellationException：$error", error is CancellationException)
    }

    // ---- fixtures ----

    private fun pdfFile(bytes: ByteArray): File =
        File(app.cacheDir, "p1-pdf-${UUID.randomUUID()}.pdf").apply { writeBytes(bytes) }

    /** 构造最小合法 PDF（Helvetica 文本页；content="" 生成无文本页）。 */
    private fun buildPdf(pageTexts: List<String>): ByteArray {
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>" // object 1
        objects += "" // object 2: pages（占位，稍后回填）
        val fontObjectNumber = 3 + pageTexts.size * 2
        val pageRefs = ArrayList<Int>()
        pageTexts.forEachIndexed { index, pageText ->
            val pageObjectNumber = 3 + index * 2
            val contentObjectNumber = pageObjectNumber + 1
            pageRefs += pageObjectNumber
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents $contentObjectNumber 0 R " +
                "/Resources << /Font << /F1 $fontObjectNumber 0 R >> >> >>"
            val content = if (pageText.isEmpty()) {
                ""
            } else {
                "BT /F1 12 Tf 72 720 Td (${escapePdfString(pageText)}) Tj ET"
            }
            val contentLength = content.toByteArray(Charsets.ISO_8859_1).size
            objects += "<< /Length $contentLength >>\nstream\n$content\nendstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects[1] = "<< /Type /Pages /Kids [" +
            pageRefs.joinToString(" ") { "$it 0 R" } + "] /Count ${pageTexts.size} >>"

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>(objects.size)
        objects.forEachIndexed { index, body ->
            offsets += out.length
            out.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefStart = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { offset -> out.append(String.format("%010d 00000 n \n", offset)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        out.append("startxref\n$xrefStart\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun escapePdfString(raw: String): String =
        raw.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
