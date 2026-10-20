package com.zhibox.memory.core.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P1 DocumentParser 分派（纯 JVM）：扩展名/MIME → parserId、Office/HTML 分派、
 * 不支持类型可读失败、PDF 占位可读失败、字符上限截断、Context+File 入口。
 */
class DocumentParserDispatchTest {

    @Test
    fun `txt and md keep legacy parser ids`() {
        assertEquals("txt-utf8-v1", DocumentParser.parse("x".byteInputStream()).parserId)
        assertEquals("md-utf8-v1", DocumentParser.parse("x".byteInputStream(), null, "notes.md").parserId)
        assertEquals("txt-utf8-v1", DocumentParser.parse("x".byteInputStream(), "text/plain", null).parserId)
    }

    @Test
    fun `docx dispatch by extension`() {
        val parsed = DocumentParser.parse(docx("Hello DOCX").inputStream(), null, "a.docx")
        assertEquals("docx-ooxml-v1", parsed.parserId)
        assertTrue(parsed.text.contains("Hello DOCX"))
    }

    @Test
    fun `pptx dispatch by mime`() {
        val bytes = zipOf(mapOf("ppt/slides/slide1.xml" to slideXml("Slide text").toByteArray()))
        val parsed = DocumentParser.parse(
            bytes.inputStream(),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "slides.pptx",
        )
        assertEquals("pptx-ooxml-v1", parsed.parserId)
        assertTrue(parsed.text.contains("Slide text"))
    }

    @Test
    fun `xlsx dispatch and html dispatch`() {
        val xlsx = zipOf(
            mapOf(
                "xl/worksheets/sheet1.xml" to (
                    """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
                        """<row><c r="A1"><v>123</v></c></row></sheetData></worksheet>"""
                    ).toByteArray(),
            ),
        )
        val xlsxParsed = DocumentParser.parse(xlsx.inputStream(), null, "t.xlsx")
        assertEquals("xlsx-ooxml-v1", xlsxParsed.parserId)
        assertTrue(xlsxParsed.text.contains("123"))

        val html = "<html><head><title>标题</title></head><body><p>正文</p></body></html>"
        val htmlParsed = DocumentParser.parse(html.byteInputStream(), "text/html", "page.html")
        assertEquals("html-text-v1", htmlParsed.parserId)
        assertTrue("HTML 标题应包含在文本中：${htmlParsed.text}", htmlParsed.text.contains("标题"))
        assertTrue(htmlParsed.text.contains("正文"))
    }

    @Test
    fun `pdf via stream requires context file entry`() {
        val error = runCatching {
            DocumentParser.parse("pdf-bytes".byteInputStream(), null, "a.pdf")
        }.exceptionOrNull() as DocumentParseException
        assertTrue("应提示 PDF 需要导入入口：${error.message}", error.message!!.contains("PDF"))
        assertEquals("pdf-text-v1", error.parserId)
        assertEquals(PdfExtractionErrorCodes.REQUIRES_CONTEXT, error.errorCode)
    }

    @Test
    fun `unsupported types fail readable with format hint`() {
        val docError = runCatching {
            DocumentParser.parse("x".byteInputStream(), null, "old.doc")
        }.exceptionOrNull() as DocumentParseException
        assertTrue(docError.message!!.contains("不支持"))
        assertTrue(docError.message!!.contains("docx"))

        val unknownError = runCatching {
            DocumentParser.parse("x".byteInputStream(), "application/octet-stream", "weird.xyz")
        }.exceptionOrNull() as DocumentParseException
        assertTrue(unknownError.message!!.contains("不支持"))
    }

    @Test
    fun `empty text document fails readable`() {
        val error = runCatching {
            DocumentParser.parse("   ".byteInputStream(), null, "empty.txt")
        }.exceptionOrNull() as DocumentParseException
        assertTrue("应提示空文档：${error.message}", error.message!!.contains("没有可提取"))
    }

    @Test
    fun `text output is truncated at the configured character cap`() {
        val raw = "x".repeat(TextExtractionLimits.MAX_TEXT_CHARS + 17)
        val parsed = DocumentParser.parse(raw.byteInputStream(), null, "big.txt")
        assertEquals(TextExtractionLimits.MAX_TEXT_CHARS, parsed.text.length)
    }

    @Test
    fun `context file overload dispatches office and rejects pdf`() {
        val docxFile = File.createTempFile("dispatch", ".docx").apply {
            writeBytes(docx("File DOCX"))
            deleteOnExit()
        }
        val parsed = runBlocking { DocumentParser.parse(null, docxFile, null, docxFile.name) }
        assertEquals("docx-ooxml-v1", parsed.parserId)
        assertTrue(parsed.text.contains("File DOCX"))

        val pdfFile = File.createTempFile("dispatch", ".pdf").apply {
            writeBytes("not really pdf".toByteArray())
            deleteOnExit()
        }
        val error = runCatching {
            runBlocking { DocumentParser.parse(null, pdfFile, null, pdfFile.name) }
        }.exceptionOrNull() as DocumentParseException
        assertTrue(error.message!!.contains("PDF"))
        assertEquals(PdfExtractionErrorCodes.REQUIRES_CONTEXT, error.errorCode)
    }

    // ---- B14.1：旧版 Office 明确错误 ----

    @Test
    fun `legacy office extensions map to dedicated error`() {
        for (name in listOf("a.doc", "b.ppt", "c.xls")) {
            val error = runCatching {
                DocumentParser.parse("old-binary".byteInputStream(), null, name)
            }.exceptionOrNull() as DocumentParseException
            assertEquals("扩展名 $name", DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED, error.errorCode)
            assertEquals("office-legacy", error.parserId)
            assertTrue("应提示另存为：${error.message}", error.message!!.contains("另存为"))
            assertTrue("应列出 docx/pptx/xlsx：${error.message}", error.message!!.contains(".docx"))
        }
    }

    @Test
    fun `legacy office mime maps to dedicated error`() {
        val mimes = listOf(
            "application/msword",
            "application/vnd.ms-powerpoint",
            "application/vnd.ms-excel",
        )
        for (mime in mimes) {
            val error = runCatching {
                DocumentParser.parse("old-binary".byteInputStream(), mime, "legacy-file")
            }.exceptionOrNull() as DocumentParseException
            assertEquals("MIME $mime 不应映射到 OOXML", DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED, error.errorCode)
            assertEquals("office-legacy", error.parserId)
            assertTrue("应提示另存为：${error.message}", error.message!!.contains("另存为"))
        }
    }

    @Test
    fun `ole magic header fallback reports legacy office`() {
        val oleMagic = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        val bytes = oleMagic + ByteArray(64) { it.toByte() }
        val error = runCatching {
            DocumentParser.parse(bytes.inputStream(), "application/octet-stream", "mystery.bin")
        }.exceptionOrNull() as DocumentParseException
        assertEquals(DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED, error.errorCode)
        assertTrue("应提示旧版二进制：${error.message}", error.message!!.contains("旧版"))
    }

    @Test
    fun `legacy office failure does not abort batch parsing`() {
        val files = listOf(
            "ok.txt" to "hello".toByteArray(),
            "old.doc" to "x".toByteArray(),
            "ok.md" to "# md".toByteArray(),
            "old.xls" to "x".toByteArray(),
        )
        val parsedNames = mutableListOf<String>()
        val legacyCodes = mutableListOf<String>()
        for ((name, bytes) in files) {
            runCatching { DocumentParser.parse(bytes.inputStream(), null, name) }
                .onSuccess { parsedNames += name }
                .onFailure { throwable ->
                    val error = throwable as DocumentParseException
                    legacyCodes += error.errorCode.orEmpty()
                }
        }
        assertEquals("合法文件应继续解析（单文件失败不中断）", listOf("ok.txt", "ok.md"), parsedNames)
        assertEquals(
            listOf(
                DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED,
                DocumentParseErrorCodes.OFFICE_LEGACY_UNSUPPORTED,
            ),
            legacyCodes,
        )
    }

    @Test
    fun `generic unsupported message lists pdf and has no stale note`() {
        val error = runCatching {
            DocumentParser.parse("zip-bytes".byteInputStream(), null, "archive.zip")
        }.exceptionOrNull() as DocumentParseException
        assertEquals(DocumentParseErrorCodes.UNSUPPORTED_TYPE, error.errorCode)
        assertTrue("应列出支持格式（含 pdf）：${error.message}", error.message!!.contains("pdf"))
        assertFalse("不应再有'PDF 支持即将提供'过期文案", error.message!!.contains("即将提供"))
    }

    // ---- B14.3：DocumentParser（Context+File）取消接线 ----

    @Test
    fun `context file parse honors cancellation before parsing`() {
        val docxFile = File.createTempFile("cancel", ".docx").apply {
            writeBytes(docx("Hello"))
            deleteOnExit()
        }
        val cancelledParent = Job().apply { cancel() }
        val error = runCatching {
            runBlocking {
                withContext(cancelledParent) {
                    DocumentParser.parse(null, docxFile, null, docxFile.name)
                }
            }
        }.exceptionOrNull()
        assertTrue("取消应原样传播：$error", error is CancellationException)
    }

    // ---- fixtures ----

    private fun docx(text: String): ByteArray = zipOf(
        mapOf(
            "word/document.xml" to (
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                    """<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""
                ).toByteArray(),
        ),
    )

    private fun slideXml(text: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p>""" +
            """</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

}
