package com.zhibox.memory.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-8 索引健康纯函数单测（检测逻辑的三表计数/孤儿 → 原因文案）。
 * 真正的 DB 检测/清理（indexHealthCheck/repairIndex）依赖 Room，由集成门禁 + 真机验收覆盖。
 */
class IndexHealthTest {

    private fun reasons(
        chunkCount: Int,
        ftsCount: Int,
        embeddingCount: Int,
        orphanFts: List<Long> = emptyList(),
        orphanEmbedding: List<Long> = emptyList(),
    ) = IngestRepository.buildIndexHealthReasons(
        chunkCount, ftsCount, embeddingCount, orphanFts, orphanEmbedding,
    )

    @Test
    fun `三表一致且无孤儿时健康（无原因）`() {
        assertTrue(reasons(10, 10, 10).isEmpty())
        // 向量数可以少于 chunk 数（文档未 ready/部分空间），不算损坏
        assertTrue(reasons(10, 10, 3).isEmpty())
    }

    @Test
    fun `FTS 行数与 chunk 数不一致时报告`() {
        val missing = reasons(10, 8, 10)
        assertEquals(1, missing.size)
        assertTrue(missing[0].contains("FTS 行数 8"))
        assertTrue(missing[0].contains("chunk 数 10"))
        // 重复行/缺行都表现为计数不一致（FTS 重复无法仅凭集合区分 → 重建兜底）
        assertTrue(reasons(10, 12, 10).single().contains("FTS 行数 12"))
    }

    @Test
    fun `FTS 孤儿与向量孤儿分别报告`() {
        val r = reasons(10, 12, 11, orphanFts = listOf(101, 102), orphanEmbedding = listOf(201))
        // 1) 计数不一致 + 2) FTS 孤儿 + 3) 向量孤儿
        assertEquals(3, r.size)
        assertTrue(r.any { it.contains("FTS 孤儿 2 行") })
        assertTrue(r.any { it.contains("向量孤儿 1 行") })
    }

    @Test
    fun `孤儿集合去重后只计数一次（调用方保证 distinct，这里锁定契约）`() {
        val r = reasons(5, 6, 6, orphanFts = listOf(1, 2, 3), orphanEmbedding = listOf(9))
        assertEquals(3, r.size)
        assertTrue(r.any { it.contains("FTS 孤儿 3 行") })
        assertTrue(r.any { it.contains("向量孤儿 1 行") })
    }

    @Test
    fun `IndexHealth 的 healthy 与 reasons 由同一纯函数驱动`() {
        val healthy = IngestRepository.IndexHealth(5, 5, 5, emptyList(), emptyList())
        assertTrue(healthy.healthy)
        assertTrue(healthy.reasons.isEmpty())

        val bad = IngestRepository.IndexHealth(5, 6, 7, listOf(9L), listOf(10L, 11L))
        assertFalse(bad.healthy)
        assertEquals(3, bad.reasons.size)

        // 仅向量孤儿也是不健康（可修复项）
        val orphanVectorOnly = IngestRepository.IndexHealth(5, 5, 6, emptyList(), listOf(42L))
        assertFalse(orphanVectorOnly.healthy)
        assertTrue(orphanVectorOnly.reasons.single().contains("向量孤儿 1 行"))
    }
}
