package com.zhibox.memory.core.search

import com.zhibox.memory.core.text.BigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-3 查询规范化（与入库一致的 NFC/CRLF 口径）+ B2-1/B2-2 关键词覆盖分/排序纯函数单测。
 */
class SearchQueryNormalizationTest {

    @Test
    fun `NFD 输入归一为 NFC（与入库一致）`() {
        // "é" 的 NFD = e + U+0301；NFC = U+00E9
        val nfd = "cafe\u0301"
        val nfc = "café"
        assertEquals(nfc, com.zhibox.memory.core.text.TextNormalizer.normalize(nfd))
        assertEquals(nfc, SearchService.normalizedQuery(nfd))
        assertEquals(nfc, SearchService.normalizedQuery(nfc))
        // 两种输入规范化后 bigram 序列一致（否则同一查询换输入法会搜不到）
        assertEquals(
            BigramTokenizer.bigrams(nfc),
            BigramTokenizer.bigrams(SearchService.normalizedQuery(nfd)),
        )
    }

    @Test
    fun `CRLF 与 CR 归一为 LF`() {
        assertEquals("a\nb\nc", SearchService.normalizedQuery("a\r\nb\rc"))
        // bigram 口径本身会移除空白，此处锁定规范化结果供 FTS/embedding 共用
        assertEquals("向量\n检索", SearchService.normalizedQuery("向量\r\n检索"))
    }

    @Test
    fun `trim 与超长截断仍然生效`() {
        assertEquals("query", SearchService.normalizedQuery("  query \r\n"))
        assertEquals("", SearchService.normalizedQuery("   "))
        val long = "中".repeat(2_000)
        assertEquals(SearchService.MAX_QUERY_CHARS, SearchService.normalizedQuery(long).length)
    }
}

class KeywordCoverageTest {

    private fun queryGrams(query: String): Set<String> =
        BigramTokenizer.bigrams(SearchService.normalizedQuery(query)).toHashSet()

    @Test
    fun `覆盖分使用与入库一致的 bigram 口径（空格与换行等价）`() {
        val grams = queryGrams("machine learning")
        assertEquals(grams.size, SearchService.bigramCoverageScore(grams, "machine learning"))
        assertEquals(grams.size, SearchService.bigramCoverageScore(grams, "machine\nlearning"))
        assertEquals(grams.size, SearchService.bigramCoverageScore(grams, "machinelearning"))
    }

    @Test
    fun `原实现 String contains 漏计的跨空格 bigram 现在计分（B2-2）`() {
        // query 的 "machine learning" 入库时先移除空白，产生跨空格 bigram "el"（machine 尾 e + learning 首 l）
        val crossSpaceGram = "el"
        assertTrue(crossSpaceGram in queryGrams("machine learning"))
        // 新口径：content 侧 bigram 也去空白，跨空格 gram 命中
        assertNotEquals(0, SearchService.bigramCoverageScore(setOf(crossSpaceGram), "machine learning"))
        // 旧口径 content.contains("el") 为 false —— 这正是 P2-3 的丢分点
        assertFalse("machine learning".contains(crossSpaceGram))
    }

    @Test
    fun `ASCII 大小写与 FTS simple tokenizer 对齐（审查 J5）`() {
        val grams = com.zhibox.memory.core.text.BigramTokenizer.bigrams("Machine Learning").toHashSet()
        // FTS4 simple tokenizer 折叠 ASCII 大小写：查询大写/内容小写仍应计分
        assertEquals(grams.size, SearchService.bigramCoverageScore(grams, "machine learning"))
        assertEquals(grams.size, SearchService.bigramCoverageScore(grams, "MACHINE LEARNING"))
        // 中文/emoji 不受影响
        assertEquals(2, SearchService.bigramCoverageScore(setOf("蓝牙", "牙断"), "蓝牙断开"))
    }

    @Test
    fun `空查询集合与空内容返回 0`() {
        assertEquals(0, SearchService.bigramCoverageScore(emptySet(), "任意内容"))
        assertEquals(0, SearchService.bigramCoverageScore(setOf("ab"), ""))
        assertEquals(0, SearchService.bigramCoverageScore(setOf("ab"), null))
        // 单字符内容：bigram 退化为单字符 token
        assertEquals(1, SearchService.bigramCoverageScore(setOf("蓝"), "蓝"))
    }
}

class KeywordRankingTest {

    private val queryGrams =
        BigramTokenizer.bigrams(SearchService.normalizedQuery("向量检索")).toHashSet()

    private val contentById = mapOf(
        1L to "向量检索",   // 覆盖 3 个 gram
        2L to "向量",       // 覆盖 1 个
        3L to "检索",       // 覆盖 1 个
        4L to "无关内容",   // 覆盖 0 个（被过滤）
    )

    @Test
    fun `按覆盖分降序、同分按 chunkId 升序、过滤零覆盖`() {
        val ranked = SearchService.rankKeywordHits(
            hitChunkIds = listOf(4L, 3L, 2L, 1L),
            contentById = contentById,
            queryGrams = queryGrams,
            maxCandidates = 10,
        )
        assertEquals(listOf(1L, 2L, 3L), ranked)
    }

    @Test
    fun `候选截断在排序之后（长尾不再被 rowid 顺序截掉）`() {
        val limited = SearchService.rankKeywordHits(
            hitChunkIds = listOf(3L, 2L, 1L),
            contentById = contentById,
            queryGrams = queryGrams,
            maxCandidates = 2,
        )
        // 若按原实现 rowid 截断，会先丢掉 chunk 1（最高覆盖分）
        assertEquals(listOf(1L, 2L), limited)
    }

    @Test
    fun `重复命中 chunkId 去重`() {
        val ranked = SearchService.rankKeywordHits(
            hitChunkIds = listOf(1L, 1L, 2L, 2L),
            contentById = contentById,
            queryGrams = queryGrams,
            maxCandidates = 10,
        )
        assertEquals(listOf(1L, 2L), ranked)
    }

    @Test
    fun `空输入与非法上限`() {
        assertTrue(SearchService.rankKeywordHits(emptyList(), contentById, queryGrams, 10).isEmpty())
        assertTrue(SearchService.rankKeywordHits(listOf(1L), contentById, emptySet(), 10).isEmpty())
        assertTrue(SearchService.rankKeywordHits(listOf(1L), contentById, queryGrams, 0).isEmpty())
    }

    @Test
    fun `候选上限与旧实现倍数一致（topK×12）`() {
        assertEquals(120, SearchService.keywordCandidateLimit(10))
        assertEquals(12, SearchService.keywordCandidateLimit(1))
    }

    @Test
    fun `全命中安全上限为 5000`() {
        assertEquals(5_000, SearchService.KEYWORD_FULL_HIT_LIMIT)
    }
}
