package com.zhibox.memory.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P1 Office OOXML 文本抽取（纯 JVM）：
 * DOCX 段落/实体、PPTX 按 slide 序号、XLSX 共享字符串/内联/数值/多 sheet、
 * 恶意 zip（路径穿越）、条目数/解压上限、损坏 zip、空文档可读失败。
 */
class OfficeTextExtractorTest {

    // ---- DOCX ----

    @Test
    fun `docx extracts paragraph text in order with entity decoding`() {
        val doc = docx(documentXml("Hello", "world", "&amp; test"))
        val text = OfficeTextExtractor.extractDocx(doc.inputStream())

        assertTrue("应包含第一段：$text", text.contains("Hello"))
        assertTrue("应包含第二段：$text", text.contains("world"))
        assertTrue("XML 实体应解码：$text", text.contains("& test"))
        assertEquals("三段应保留三个段落行：$text", 3, text.lines().count { it.isNotBlank() })
    }

    @Test
    fun `docx missing document xml fails readable`() {
        val bytes = zipOf(mapOf("word/styles.xml" to "<x/>".toByteArray()))
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream()) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示缺少 document.xml：${error.message}", error.message!!.contains("word/document.xml"))
    }

    @Test
    fun `docx tab and line break tokens are preserved`() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p></w:body></w:document>"""
        val text = OfficeTextExtractor.extractDocx(docx(xml).inputStream())
        assertTrue("tab 应保留为制表符：$text", text.contains("A\tB"))
        assertTrue("br 应保留为换行：$text", text.contains("B\nC"))
    }

    @Test
    fun `unmatched zip entry still consumes size budget`() {
        val limits = OfficeExtractionLimits(maxEntryBytes = 32)
        val bytes = zipOf(
            mapOf(
                "word/media/blob.bin" to ByteArray(256) { 1 },
                "word/document.xml" to documentXml("ok").toByteArray(),
            ),
        )
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream(), limits) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("非匹配条目也必须受条目预算：${error.message}", error.message!!.contains("过大"))
    }

    @Test
    fun `docx blank content fails readable`() {
        val bytes = docx(documentXml("   ", ""))
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream()) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示无可提取文本：${error.message}", error.message!!.contains("没有可提取"))
    }

    // ---- PPTX ----

    @Test
    fun `pptx extracts slides sorted by numeric index`() {
        val bytes = zipOf(
            mapOf(
                "ppt/slides/slide10.xml" to pptxSlideXml("Tenth").toByteArray(),
                "ppt/slides/slide2.xml" to pptxSlideXml("Second").toByteArray(),
            ),
        )
        val text = OfficeTextExtractor.extractPptx(bytes.inputStream())

        val second = text.indexOf("Second")
        val tenth = text.indexOf("Tenth")
        assertTrue("应包含两页文本：$text", second >= 0 && tenth >= 0)
        assertTrue("slide2 应排在 slide10 前（数字序）：$text", second < tenth)
    }

    @Test
    fun `pptx without slides fails readable`() {
        val bytes = zipOf(mapOf("ppt/presentation.xml" to "<x/>".toByteArray()))
        val error = runCatching { OfficeTextExtractor.extractPptx(bytes.inputStream()) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示无幻灯片：${error.message}", error.message!!.contains("幻灯片"))
    }

    // ---- XLSX ----

    @Test
    fun `xlsx extracts shared strings inline strings and numbers by row`() {
        val bytes = zipOf(
            mapOf(
                "xl/sharedStrings.xml" to sharedStringsXml(listOf("Name", "Hello World")).toByteArray(),
                "xl/worksheets/sheet1.xml" to worksheetXml(
                    row(
                        cell("A1", type = "s", value = "0"),
                        cell("B1", type = "inlineStr", inline = "Inline"),
                    ),
                    row(cell("A2", value = "42")),
                ).toByteArray(),
            ),
        )
        val text = OfficeTextExtractor.extractXlsx(bytes.inputStream())

        assertTrue("共享字符串应解析：$text", text.contains("Name"))
        assertTrue("内联字符串应解析：$text", text.contains("Inline"))
        assertTrue("数值应保留：$text", text.contains("42"))
        assertEquals("应按行输出：$text", 2, text.lines().count { it.isNotBlank() })
        assertTrue("单元格间应为 Tab：$text", text.contains("Name\tInline"))
    }

    @Test
    fun `xlsx without sheets fails readable`() {
        val bytes = zipOf(
            mapOf("xl/sharedStrings.xml" to sharedStringsXml(listOf("A")).toByteArray()),
        )
        val error = runCatching { OfficeTextExtractor.extractXlsx(bytes.inputStream()) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示无工作表：${error.message}", error.message!!.contains("工作表"))
    }

    // ---- 安全 / 预算 ----

    @Test
    fun `zip entry path traversal is rejected`() {
        val bytes = zipOf(
            mapOf(
                "../evil.txt" to "x".toByteArray(),
                "word/document.xml" to documentXml("ok").toByteArray(),
            ),
        )
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream()) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应拒绝路径穿越：${error.message}", error.message!!.contains("路径"))
    }

    @Test
    fun `too many zip entries rejected with readable limit`() {
        val limits = OfficeExtractionLimits(maxEntries = 0)
        val bytes = docx(documentXml("ok"))
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream(), limits) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示条目过多：${error.message}", error.message!!.contains("条目"))
    }

    @Test
    fun `oversized entry rejected before unbounded decompression`() {
        val limits = OfficeExtractionLimits(maxEntryBytes = 16)
        val bytes = docx(documentXml("x".repeat(500)))
        val error = runCatching { OfficeTextExtractor.extractDocx(bytes.inputStream(), limits) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示条目过大：${error.message}", error.message!!.contains("过大"))
    }

    @Test
    fun `total uncompressed budget enforced`() {
        val limits = OfficeExtractionLimits(maxTotalUncompressedBytes = 8)
        val bytes = zipOf(
            mapOf(
                "ppt/slides/slide1.xml" to pptxSlideXml("AAAAAAAAAAAAAAAAAAAA").toByteArray(),
            ),
        )
        val error = runCatching { OfficeTextExtractor.extractPptx(bytes.inputStream(), limits) }
            .exceptionOrNull() as DocumentParseException
        assertTrue("应提示总大小超限：${error.message}", error.message!!.contains("总大小"))
    }

    @Test
    fun `invalid zip bytes fail readable`() {
        val error = runCatching {
            OfficeTextExtractor.extractDocx("not a zip at all".toByteArray().inputStream())
        }.exceptionOrNull() as DocumentParseException
        assertTrue("应提示 ZIP 结构损坏：${error.message}", error.message!!.contains("ZIP"))
    }

    // ---- fixtures ----

    private fun docx(documentXml: String): ByteArray = zipOf(
        mapOf("word/document.xml" to documentXml.toByteArray(Charsets.UTF_8)),
    )

    private fun documentXml(vararg paragraphs: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        for (paragraph in paragraphs) {
            append("<w:p><w:r><w:t xml:space=\"preserve\">").append(paragraph).append("</w:t></w:r></w:p>")
        }
        append("</w:body></w:document>")
    }

    private fun pptxSlideXml(text: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""")
        append("<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>")
        append(text)
        append("</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>")
    }

    private fun sharedStringsXml(items: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        for (item in items) append("<si><t>").append(item).append("</t></si>")
        append("</sst>")
    }

    private fun worksheetXml(vararg rows: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        for (row in rows) append(row)
        append("</sheetData></worksheet>")
    }

    private fun row(vararg cells: String): String = "<row>" + cells.joinToString("") + "</row>"

    private fun cell(ref: String, value: String = "", type: String? = null, inline: String = ""): String =
        buildString {
            append("<c r=\"").append(ref).append("\"")
            if (type != null) append(" t=\"").append(type).append("\"")
            append(">")
            when {
                inline.isNotEmpty() -> append("<is><t>").append(inline).append("</t></is>")
                value.isNotEmpty() -> append("<v>").append(value).append("</v>")
            }
            append("</c>")
        }

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
