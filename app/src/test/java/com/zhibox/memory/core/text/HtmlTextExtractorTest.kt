package com.zhibox.memory.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 HTML → 标题+纯文本（纯 JVM）：去脚本/样式、块级换行、实体解码、charset 探测。 */
class HtmlTextExtractorTest {

    @Test
    fun `extracts title and visible text without script or style`() {
        val html = """
            <!DOCTYPE html>
            <html><head>
              <title>测试 标题</title>
              <style>body { color: red; }</style>
              <script>alert('do not leak')</script>
            </head><body>
              <h1>第一节</h1>
              <p>正文 &amp; 实体 &lt;tag&gt; &#20320;&#x597D;</p>
              <div>第二段<br>换行</div>
            </body></html>
        """.trimIndent()

        val extracted = HtmlTextExtractor.extract(html)

        assertEquals("测试 标题", extracted.title)
        assertTrue("应含标题正文：${extracted.text}", extracted.text.contains("第一节"))
        assertTrue("应解码 HTML 实体：${extracted.text}", extracted.text.contains("正文 & 实体 <tag> 你好"))
        assertTrue("br 应转换行：${extracted.text}", extracted.text.contains("换行"))
        assertFalse("不得包含脚本：${extracted.text}", extracted.text.contains("do not leak"))
        assertFalse("不得包含样式：${extracted.text}", extracted.text.contains("color: red"))
    }

    @Test
    fun `table cells become tabs and rows are separated`() {
        val html = "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>"
        val text = HtmlTextExtractor.extract(html).text

        assertTrue("单元格应 Tab 分隔：$text", text.contains("A\tB"))
        assertTrue("第二行应保留：$text", text.contains("C\tD"))
    }

    @Test
    fun `decodes bytes with meta charset`() {
        val html = """<html><head><meta charset="utf-8"><title>中文</title></head><body>正文</body></html>"""
        val extracted = HtmlTextExtractor.extract(html.toByteArray(Charsets.UTF_8))
        assertEquals("中文", extracted.title)
        assertTrue(extracted.text.contains("正文"))
    }

    @Test
    fun `plain text decode keeps content and strips bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "纯文本".toByteArray()
        assertEquals("纯文本", HtmlTextExtractor.decodePlainText(bytes, null))
    }

    @Test
    fun `malformed html does not throw`() {
        val extracted = HtmlTextExtractor.extract("<p>未闭合 <b>标签 <script>bad")
        assertTrue(extracted.text.contains("未闭合"))
        assertFalse(extracted.text.contains("bad"))
    }

    @Test
    fun `empty html yields empty text`() {
        val extracted = HtmlTextExtractor.extract("")
        assertEquals("", extracted.title)
        assertEquals("", extracted.text)
    }
}
