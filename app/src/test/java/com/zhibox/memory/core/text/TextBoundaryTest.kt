package com.zhibox.memory.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-4 emoji/代理对边界单测：BigramTokenizer 按 code point 迭代、Chunker 硬切不劈 surrogate。
 * 对 BMP 文本要求与旧实现逐位一致（220 评测基线不受影响）。
 */
class CodePointBigramTest {

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i += 1
            }
        }
        return false
    }

    @Test
    fun `emoji 参与 bigram 时不产生孤立代理`() {
        val rocket = "🚀" // U+1F680, surrogate pair
        val grams = BigramTokenizer.bigrams("a${rocket}b")
        assertEquals(listOf("a$rocket", "${rocket}b"), grams)
        assertTrue(grams.none(::hasLoneSurrogate))
        // FTS 文本/查询串同样不含孤立代理
        assertFalse(hasLoneSurrogate(BigramTokenizer.toFtsText("蓝${rocket}天")))
        assertFalse(hasLoneSurrogate(BigramTokenizer.toOrMatchQuery("蓝${rocket}天")))
    }

    @Test
    fun `CJK 扩展区（补充平面）按 code point 成对`() {
        val extB = String(Character.toChars(0x20000)) // 𠀀，长度 2（surrogate pair）
        assertEquals(2, extB.length)
        val grams = BigramTokenizer.bigrams("中${extB}文")
        assertEquals(listOf("中$extB", "${extB}文"), grams)
        assertTrue(grams.none(::hasLoneSurrogate))
        assertEquals(2, grams.size)
    }

    @Test
    fun `BMP 文本与旧实现逐位一致（评测基线不受影响）`() {
        assertEquals(listOf("向量", "量检", "检索"), BigramTokenizer.bigrams("向量 检索"))
        val text = "abc中文123"
        val legacy = (0 until text.length - 1).map { text.substring(it, it + 2) }
        assertEquals(legacy, BigramTokenizer.bigrams(text))
        // whitespace 口径未变
        assertEquals(BigramTokenizer.bigrams("向量检索"), BigramTokenizer.bigrams(" 向量\n检索 "))
    }

    @Test
    fun `单字符与空文本`() {
        assertEquals(listOf("🚀"), BigramTokenizer.bigrams("🚀"))
        assertEquals(listOf("蓝"), BigramTokenizer.bigrams(" 蓝 "))
        assertTrue(BigramTokenizer.bigrams("  ").isEmpty())
        assertTrue(BigramTokenizer.bigrams("").isEmpty())
    }
}

class SurrogateSafeChunkTest {

    private fun assertNoLoneSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                assertTrue("高代理后必须跟低代理：index=$i", i + 1 < text.length && text[i + 1].isLowSurrogate())
                i += 2
            } else {
                assertFalse("出现孤立低代理：index=$i", c.isLowSurrogate())
                i += 1
            }
        }
    }

    @Test
    fun `硬切边界不劈开 surrogate pair`() {
        // 偏移 1 个字符后用 emoji 填满，强制切点落在 pair 中间
        val rocket = "🚀"
        val text = "字" + rocket.repeat(5)
        val chunks = Chunker(Chunker.Options(maxChars = 4)).chunk(text)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(text.substring(chunk.charStart, chunk.charEnd), chunk.content)
            assertNoLoneSurrogate(chunk.content)
        }
        // 不变量：相邻 chunk 首尾相接、覆盖全文（不因回退导致丢字符/重叠）
        assertEquals(text.length, chunks.sumOf { it.content.length })
        assertEquals(0, chunks.first().charStart)
        assertEquals(text.length, chunks.last().charEnd)
        for (i in 1 until chunks.size) assertEquals(chunks[i - 1].charEnd, chunks[i].charStart)
    }

    @Test
    fun `纯普通文本的硬切边界不变（评测基线）`() {
        val line = "字".repeat(2000)
        val chunks = Chunker(Chunker.Options(maxChars = 800)).chunk(line)
        assertEquals(3, chunks.size)
        assertEquals(listOf(800, 800, 400), chunks.map { it.charEnd - it.charStart })
    }

    @Test
    fun `估计 token 时 surrogate pair 计 1 个字符（B2-4）`() {
        // emoji 与单个 CJK 字符同为 1 个 code point → 同一估算
        assertEquals(Chunker.estimateTokens("字"), Chunker.estimateTokens("🚀"))
        // 旧实现按 UTF-16 char 计会把 emoji 记成 2 个 CJK：ceil(2×1.35)=3
        assertTrue(Chunker.estimateTokens("🚀") < 3)
    }
}
