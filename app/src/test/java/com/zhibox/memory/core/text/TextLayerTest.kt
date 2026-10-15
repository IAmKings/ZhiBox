package com.zhibox.memory.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class TextNormalizerTest {
    @Test
    fun `null and empty to empty`() {
        assertEquals("", TextNormalizer.normalize(null))
        assertEquals("", TextNormalizer.normalize(""))
    }

    @Test
    fun `CRLF and CR unified to LF`() {
        assertEquals("a\nb\nc", TextNormalizer.normalize("a\r\nb\rc"))
    }

    @Test
    fun `NFC composition`() {
        val decomposed = "e" + "\u0301" // e + combining acute
        assertEquals(
            Normalizer.normalize(decomposed, Normalizer.Form.NFC),
            TextNormalizer.normalize(decomposed),
        )
    }

    @Test
    fun `no trim`() {
        assertEquals("  x  ", TextNormalizer.normalize("  x  "))
    }
}

class BigramTokenizerTest {
    @Test
    fun `known bigrams`() {
        assertEquals(listOf("向量", "量检", "检索"), BigramTokenizer.bigrams("向量检索"))
    }

    @Test
    fun `whitespace stripped before bigram`() {
        assertEquals(listOf("向量", "量检", "检索"), BigramTokenizer.bigrams("向量 检索"))
    }

    @Test
    fun `single char yields single token`() {
        assertEquals(listOf("蓝"), BigramTokenizer.bigrams(" 蓝 "))
    }

    @Test
    fun `empty yields nothing`() {
        assertTrue(BigramTokenizer.bigrams("  ").isEmpty())
    }

    @Test
    fun `match query quotes grams`() {
        assertEquals("\"向量\" \"量检\" \"检索\"", BigramTokenizer.toMatchQuery("向量检索"))
    }
}

class ChunkerTest {
    private fun assertSubstringInvariant(text: String, chunks: List<Chunker.Chunk>) {
        chunks.forEach {
            assertEquals(
                "chunk ${it.ordinal} must equal source substring",
                text.substring(it.charStart, it.charEnd),
                it.content,
            )
        }
    }

    @Test
    fun `empty text yields no chunks`() {
        assertTrue(Chunker().chunk("").isEmpty())
    }

    @Test
    fun `substring invariant on markdown with headings and blank lines`() {
        val text = """
            # 标题一
            第一段内容甲，讲的是向量检索的基本概念与用途。

            第二段内容乙，讲的是导入管线的幂等性与恢复策略，包括进程被杀后的重跑安全。

            ## 标题二
            第三段内容丙，包含 English mixed words and punctuation!

            尾段。
        """.trimIndent().replace("\r\n", "\n")
        val chunks = Chunker().chunk(text)
        assertTrue(chunks.isNotEmpty())
        assertSubstringInvariant(text, chunks)
        // 标题强制开新块：含"## 标题二"的 chunk 是新块的起始内容
        val headingChunk = chunks.first { it.content.startsWith("## 标题二") }
        assertTrue(headingChunk.charStart > 0)
    }

    @Test
    fun `oversize block hard split at maxChars`() {
        val line = "字".repeat(2000)
        val chunks = Chunker(Chunker.Options(maxChars = 800)).chunk(line)
        assertEquals(3, chunks.size)
        assertEquals(listOf(800, 800, 400), chunks.map { it.charEnd - it.charStart })
        assertSubstringInvariant(line, chunks)
    }

    @Test
    fun `small paragraphs packed up to limit`() {
        val text = (1..60).joinToString("\n\n") { "段落第$it 节" }
        val chunks = Chunker(Chunker.Options(maxChars = 100)).chunk(text)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        assertTrue(chunks.all { it.charEnd - it.charStart <= 100 })
        assertSubstringInvariant(text, chunks)
    }

    @Test
    fun `token estimate cjk heavier than latin`() {
        val cjk = Chunker.estimateTokens("向量检索")
        val latin = Chunker.estimateTokens("abcd")
        assertTrue(cjk > latin)
        // 校准系数 1.35：ceil(4×1.35)=6；纯拉丁 4 字符=1
        assertEquals(6, cjk)
        assertEquals(1, latin)
    }
}

class DocumentParserTest {
    @Test
    fun `BOM stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "正文".toByteArray()
        assertEquals("正文", DocumentParser.decodeUtf8(bytes))
    }

    @Test
    fun `md detected by file name`() {
        val parsed = DocumentParser.parse("x".byteInputStream(), null, "notes.md")
        assertEquals("md-utf8-v1", parsed.parserId)
    }

    @Test
    fun `default txt`() {
        val parsed = DocumentParser.parse("x".byteInputStream())
        assertEquals("txt-utf8-v1", parsed.parserId)
    }
}
