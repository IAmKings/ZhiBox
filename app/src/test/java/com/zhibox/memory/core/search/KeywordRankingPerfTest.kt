package com.zhibox.memory.core.search

import com.zhibox.memory.core.search.SearchService.Companion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B2-1 审查补强：10k 场景的关键词重排（Kotlin 侧）成本基线。
 *
 * `Fts10kPerfTest` 只测 DAO（SQL 取回 5000 行），未覆盖 `loadChunks + rankKeywordHits`
 * 的覆盖分排序。这里用纯 JVM 模拟最坏情况：5000 个候选 × 600 字内容（≈ 300 万 bigram），
 * 打印耗时并做病态兜底断言（防止退化为 O(n²)）。
 */
class KeywordRankingPerfTest {

    @Test
    fun rank5000CandidatesWith600CharContent_withinPathologicalBound() {
        val candidates = 5_000
        val contentById = HashMap<Long, String>(candidates)
        for (i in 0 until candidates) {
            contentById[i.toLong()] = buildString { repeat(300) { append("测试") } } // 600 字
        }
        val queryGrams = setOf("测试", "试测")
        val hitIds = (0 until candidates).map { it.toLong() }

        // 预热
        repeat(2) { Companion.rankKeywordHits(hitIds, contentById, queryGrams, 120) }

        val times = LongArray(5)
        var result: List<Long> = emptyList()
        for (i in times.indices) {
            val t0 = System.nanoTime()
            result = Companion.rankKeywordHits(hitIds, contentById, queryGrams, 120)
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        println(
            "B2_RANK_PERF candidates=$candidates content_chars=600 " +
                "p50_ms=${times[times.size / 2]} max_ms=${times.last()} result=${result.size}",
        )
        assertEquals("候选截断为 topK×12", 120, result.size)
        assertTrue("关键词重排不得退化（>2s 视为病态）", times.last() < 2_000)
    }
}
