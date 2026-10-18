package com.zhibox.memory.core.sag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10 检索纯函数（JVM，无 Android/DB）：`SagSupport` 的归一化/哈希/实体去噪/融合分/通道映射/trace JSON。
 *
 * 锁定口径（UI/评测/Retriever/Lexical 抽取器共用，避免多处各写一份导致漂移）：
 * query 规范化与截断、entity normalizedName 口径、SHA-256 小写 hex、bigram OR MATCH 串、
 * 泛词过滤、事件融合分权重（0.7 向量 + 0.3 实体）、SearchHit.channels 中文→常量映射、
 * timingJson 只含 name/count/ms 不含正文。
 */
class SagSupportTest {

    // ---- 归一化 ----

    @Test
    fun normalizeQueryNfcTrimAndCrlf() {
        assertEquals("张三\n上海", SagSupport.normalizeQuery("  张三\r\n上海  "))
        assertEquals("caf\u00e9", SagSupport.normalizeQuery("cafe\u0301"))
        assertEquals("", SagSupport.normalizeQuery("   "))
    }

    @Test
    fun normalizeQueryTruncatesTo512Chars() {
        val long = "x".repeat(600)
        assertEquals(SagSupport.MAX_QUERY_CHARS, SagSupport.normalizeQuery(long).length)
        assertEquals(512, SagSupport.MAX_QUERY_CHARS)
    }

    @Test
    fun normalizeEntityNameMatchesEntityTableContract() {
        assertEquals("张三", SagSupport.normalizeEntityName(" 张三 "))
        assertEquals("math subject", SagSupport.normalizeEntityName("Math   Subject"))
        assertEquals(
            SagSupport.normalizeEntityName("caf\u00e9"),
            SagSupport.normalizeEntityName("cafe\u0301"),
        )
    }

    // ---- 哈希（trace.queryHash，不落正文） ----

    @Test
    fun queryHashIsLowercaseSha256HexAndDeterministic() {
        val hash = SagSupport.queryHash("上海")
        assertEquals(64, hash.length)
        assertEquals("4906a49adc5c472b34483976104ec1d76992745367cc22d5fbc2a4db8e8e1ed7", hash)
        assertEquals(hash, SagSupport.queryHash("上海"))
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(hash == SagSupport.queryHash("上海学习"))
    }

    // ---- bigram OR MATCH ----

    @Test
    fun queryGramsAreDistinctAndBounded() {
        assertEquals(listOf("上海"), SagSupport.queryGrams("上海"))
        assertTrue(SagSupport.queryGrams("ab".repeat(200)).size <= SagSupport.MAX_OR_GRAMS)
        assertEquals(64, SagSupport.MAX_OR_GRAMS)
        val grams = SagSupport.queryGrams("上海上海")
        assertEquals("bigram 去重", grams.size, grams.toSet().size)
    }

    @Test
    fun ftsOrMatchQuotesGramsAndEscapes() {
        assertEquals("\"上海\" OR \"海学\"", SagSupport.ftsOrMatch(listOf("上海", "海学")))
        // 实现口径：gram 内引号被移除（防 FTS 表达式注入），不是转义
        assertEquals("\"ab\"", SagSupport.ftsOrMatch(listOf("a\"b")))
        assertNull(SagSupport.ftsOrMatch(emptyList()))
    }

    // ---- 泛词/噪声过滤 ----

    @Test
    fun genericEntityNamesAreFiltered() {
        listOf("什么", "怎么", "如何", "为什么", "哪些", "介绍", "内容", "知识", "问题", "学习")
            .forEach { word -> assertTrue("应为泛词：$word", SagSupport.isGenericEntityName(word)) }
        listOf("上海", "张三", "数学", "Java").forEach { word ->
            assertFalse("不应为泛词：$word", SagSupport.isGenericEntityName(word))
        }
        assertTrue("空名视为泛词", SagSupport.isGenericEntityName(" "))
    }

    // ---- 事件融合分 ----

    @Test
    fun fuseEventScoreWeightsVectorAndEntity() {
        assertEquals(0.0, SagSupport.fuseEventScore(null, null), 1e-9)
        assertEquals(0.7, SagSupport.fuseEventScore(1.0, null), 1e-9)
        assertEquals(0.3, SagSupport.fuseEventScore(null, 1.0), 1e-9)
        assertEquals(1.0, SagSupport.fuseEventScore(1.0, 1.0), 1e-9)
        assertEquals(0.7 * 0.8 + 0.3 * 0.5, SagSupport.fuseEventScore(0.8, 0.5), 1e-9)
        // clamp：越界输入不溢出
        assertEquals(0.7, SagSupport.fuseEventScore(2.0, -1.0), 1e-9)
    }

    @Test
    fun clamp01() {
        assertEquals(0.0, SagSupport.clamp01(-0.5), 1e-9)
        assertEquals(0.5, SagSupport.clamp01(0.5), 1e-9)
        assertEquals(1.0, SagSupport.clamp01(1.5), 1e-9)
    }

    // ---- SearchHit.channels → SagChannels ----

    @Test
    fun channelsFromSearchHitMapsChineseChannelText() {
        assertEquals(setOf(SagChannels.VECTOR, SagChannels.KEYWORD), SagSupport.channelsFromSearchHit("向量+关键词"))
        assertEquals(setOf(SagChannels.VECTOR), SagSupport.channelsFromSearchHit("向量"))
        assertEquals(setOf(SagChannels.KEYWORD), SagSupport.channelsFromSearchHit("关键词"))
        assertEquals("未知文案回退 vector", setOf(SagChannels.VECTOR), SagSupport.channelsFromSearchHit("other"))
    }

    // ---- trace timingJson ----

    @Test
    fun timingJsonOnlyContainsStageMetadata() {
        val stages = listOf(
            SagTraceStage("vector_search", 3, 12),
            SagTraceStage("event_vector_seed", 2, 7),
        )
        val json = JSONObject(SagSupport.encodeTimingJson(stages))
        val array = json.getJSONArray("stages")
        assertEquals(2, array.length())
        assertEquals("vector_search", array.getJSONObject(0).getString("name"))
        assertEquals(3, array.getJSONObject(0).getInt("count"))
        assertEquals(12L, array.getJSONObject(0).getLong("ms"))
        assertEquals("event_vector_seed", array.getJSONObject(1).getString("name"))
        assertFalse("timingJson 不得包含 query 正文", json.toString().contains("上海"))
    }

    // ---- 常量契约 ----

    @Test
    fun retrievalConstants() {
        assertEquals(0.4, SagSupport.EVENT_VECTOR_TAU, 1e-9)
        assertEquals(50, SagSupport.TRACE_KEEP)
        assertEquals(5_000, SagSupport.EVENT_VECTOR_SCAN_LIMIT)
    }
}
