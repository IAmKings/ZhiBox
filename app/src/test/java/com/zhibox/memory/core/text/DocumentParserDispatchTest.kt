package com.zhibox.memory.core.text

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
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
