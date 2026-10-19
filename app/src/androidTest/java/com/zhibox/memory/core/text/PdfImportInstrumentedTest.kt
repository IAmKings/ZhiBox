package com.zhibox.memory.core.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 PDF 真机/Android runtime 冒烟：验证 PDFBox-Android 资源打包、初始化、文本抽取与
 * DocumentParser Context+File 分派在 Android 上可用（Robolectric 之外的补充）。
 */
@RunWith(AndroidJUnit4::class)
class PdfImportInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "p1-pdf-instrumented").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun textPdf_extractsThroughDocumentParser() = runBlocking {
        val file = File(dir, "p1.pdf").apply { writeBytes(minimalTextPdf()) }
        val parsed = DocumentParser.parse(context, file, mimeType = null, fileName = file.name)
        assertEquals("pdf-text-v1", parsed.parserId)
        assertTrue("应抽取 PDF 文本：${parsed.text}", parsed.text.contains("P1 PDF text extraction test"))
        assertTrue(parsed.text.contains("Zhang San studies math in Shanghai."))
    }

    @Test
    fun corruptPdf_failsReadableWithStableCode() = runBlocking {
        val file = File(dir, "bad.pdf").apply { writeBytes("not a pdf".toByteArray()) }
        val error = runCatching {
            DocumentParser.parse(context, file, mimeType = null, fileName = file.name)
        }.exceptionOrNull() as DocumentParseException
        assertEquals("pdf-text-v1", error.parserId)
        assertEquals(PdfExtractionErrorCodes.CORRUPT, error.errorCode)
        assertTrue("失败文案应可读：${error.message}", error.message!!.contains("PDF"))
    }

    /** 手工构造最小合法 PDF（Helvetica 文本页），不依赖测试库/网络。 */
    private fun minimalTextPdf(): ByteArray {
        val stream = "BT /F1 18 Tf 72 700 Td (P1 PDF text extraction test) Tj " +
            "0 -24 Td (Zhang San studies math in Shanghai.) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${stream.toByteArray(StandardCharsets.ISO_8859_1).size} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(StandardCharsets.ISO_8859_1))
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, body ->
            offsets += out.size()
            out.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray())
        out.write("0000000000 65535 f \n".toByteArray())
        offsets.drop(1).forEach { out.write("%010d 00000 n \n".format(it).toByteArray()) }
        out.write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(),
        )
        return out.toByteArray()
    }
}
