package com.zhibox.memory.core.text

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * B14.3：Office SAX 安全/健壮性（纯 JVM）：
 * - XXE：DTD/外部实体声明在解析前拒绝（不得读取本机文件内容）；
 * - 畸形 XML：可读失败（不得崩溃/挂死）；
 * - 取消：onCheck 抛 CancellationException 原样传播（不被包装为 DocumentParseException）；
 * - 输出预算：SAX 解析过程中触顶后返回前缀文本（保持既有截断语义）；
 * - onCheck 确实在解析过程中被调用（DocumentParser 取消接线依赖）。
 */
class OfficeSaxSecurityTest {

    // ---- XXE ----

    @Test
    fun `docx with external entity is rejected without leaking local files`() {
        val xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
        val bytes = docx(xxeXml)

        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractDocx(bytes.inputStream())
        }
        assertTrue("应提示 DTD/实体安全限制：${error.message}", error.message!!.contains("DTD") || error.message!!.contains("实体"))
        assertFalse("不得读取/包含本机文件内容", error.message!!.contains("root:"))
    }

    @Test
    fun `xlsx sharedStrings with external entity is rejected`() {
        val shared = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE sst [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>&xxe;</t></si>
            </sst>
        """.trimIndent()
        val bytes = zipOf(
            mapOf(
                "xl/sharedStrings.xml" to shared.toByteArray(),
                "xl/worksheets/sheet1.xml" to (
                    """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                        """<sheetData><row><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>"""
                    ).toByteArray(),
            ),
        )

        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractXlsx(bytes.inputStream())
        }
        assertTrue("应提示安全限制：${error.message}", error.message!!.contains("DTD") || error.message!!.contains("实体"))
    }

    // ---- 畸形 XML ----

    @Test
    fun `malformed docx xml fails readable`() {
        val bytes = docx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                """<w:body><w:p><w:r><w:t>unclosed""",
        )
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractDocx(bytes.inputStream())
        }
        assertTrue("应提示 XML 不合法：${error.message}", error.message!!.contains("XML"))
    }

    @Test
    fun `malformed sheet xml fails readable`() {
        val bytes = zipOf(
            mapOf(
                "xl/worksheets/sheet1.xml" to (
                    """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                        """<sheetData><row><c r="A1"><v>1</v></row></sheetData>"""
                    ).toByteArray(),
            ),
        )
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractXlsx(bytes.inputStream())
        }
        assertTrue("应提示 XML 不合法：${error.message}", error.message!!.contains("XML"))
    }

    // ---- 取消 ----

    @Test
    fun `cancellation from onCheck propagates for docx`() {
        var checks = 0
        val cancel = {
            checks++
            throw CancellationException("cancel-test")
        }
        val thrown = assertThrows(CancellationException::class.java) {
            OfficeTextExtractor.extractDocx(docx(documentXml("Hello")).inputStream(), OfficeExtractionLimits.DEFAULT, cancel)
        }
        assertTrue("onCheck 应在解析过程中被调用", checks > 0)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `cancellation from onCheck propagates for pptx`() {
        val cancel = { throw CancellationException("cancel-test") }
        assertThrows(CancellationException::class.java) {
            OfficeTextExtractor.extractPptx(
                zipOf(mapOf("ppt/slides/slide1.xml" to slideXml("Hello").toByteArray())).inputStream(),
                OfficeExtractionLimits.DEFAULT,
                cancel,
            )
        }
    }

    @Test
    fun `cancellation from onCheck propagates for xlsx`() {
        val cancel = { throw CancellationException("cancel-test") }
        assertThrows(CancellationException::class.java) {
            OfficeTextExtractor.extractXlsx(
                zipOf(mapOf("xl/worksheets/sheet1.xml" to worksheetXml("<row><c><v>1</v></c></row>").toByteArray())).inputStream(),
                OfficeExtractionLimits.DEFAULT,
                cancel,
            )
        }
    }

    @Test
    fun `onCheck is invoked during successful docx parsing`() {
        var checks = 0
        val text = OfficeTextExtractor.extractDocx(
            docx(documentXml("Hello", "World")).inputStream(),
            OfficeExtractionLimits.DEFAULT,
            { checks++ },
        )
        assertTrue(text.contains("Hello"))
        assertTrue("onCheck 应被周期性调用：$checks", checks > 2)
    }

    // ---- 输出预算（超过上限 = 可读失败，不 OOM/不静默截断） ----

    @Test
    fun `output budget exceeding fails readable`() {
        val limits = OfficeExtractionLimits(maxTextChars = 40)
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractDocx(docx(documentXml("x".repeat(500))).inputStream(), limits)
        }
        assertTrue("应提示文本上限：${error.message}", error.message!!.contains("上限"))
    }

    @Test
    fun `large text fails fast within budget without oom`() {
        val limits = OfficeExtractionLimits(maxTextChars = 2_000)
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractDocx(docx(documentXml("x".repeat(3_000_000))).inputStream(), limits)
        }
        assertTrue("应提示文本上限：${error.message}", error.message!!.contains("上限"))
    }

    @Test
    fun `xlsx output budget exceeding fails readable`() {
        val limits = OfficeExtractionLimits(maxTextChars = 30)
        val rows = (1..20).joinToString("") { row(cell(value = "row-$it")) }
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractXlsx(
                zipOf(mapOf("xl/worksheets/sheet1.xml" to worksheetXml(rows).toByteArray())).inputStream(),
                limits,
            )
        }
        assertTrue("应提示文本上限：${error.message}", error.message!!.contains("上限"))
    }

    // ---- 畸形 XML：大量未闭合标签（超时级恶意输入要快速可读失败） ----

    @Test
    fun `malformed docx with many unclosed text tags fails readable quickly`() {
        val unclosed = buildString {
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            repeat(2_000) { append("<w:p><w:r><w:t>未闭合") }
            // 不闭合 body/document：SAX 在结构错误处立即失败，不会尝试恢复
        }
        val error = assertThrows(DocumentParseException::class.java) {
            OfficeTextExtractor.extractDocx(docx(unclosed).inputStream())
        }
        assertTrue("应提示 XML 不合法：${error.message}", error.message!!.contains("XML"))
    }

    // ---- fixtures ----

    private fun docx(documentXml: String): ByteArray =
        zipOf(mapOf("word/document.xml" to documentXml.toByteArray()))

    private fun documentXml(vararg paragraphs: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        for (paragraph in paragraphs) {
            append("<w:p><w:r><w:t xml:space=\"preserve\">").append(paragraph).append("</w:t></w:r></w:p>")
        }
        append("</w:body></w:document>")
    }

    private fun slideXml(text: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p>""" +
            """</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""

    private fun worksheetXml(rows: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<sheetData>$rows</sheetData></worksheet>"""

    private fun row(vararg cells: String): String = "<row>" + cells.joinToString("") + "</row>"

    private fun cell(value: String): String = "<c><v>$value</v></c>"

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
