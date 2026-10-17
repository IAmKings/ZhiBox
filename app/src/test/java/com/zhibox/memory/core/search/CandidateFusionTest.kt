package com.zhibox.memory.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateFusionTest {

    @Test
    fun `双通道同一 chunk 融合加分且名次保留`() {
        val fused = CandidateFusion.fuse(
            vectorIds = listOf(10L, 20L),
            keywordIds = listOf(20L, 30L),
            topK = 10,
        )
        val byId = fused.associateBy { it.chunkId }
        val c20 = byId.getValue(20L)
        // chunk 20：向量第 2 + 关键词第 1 → 分数 1/(K+2)+1/(K+1)，高于单通道的 10/30
        assertEquals(1.0 / (CandidateFusion.RRF_K + 2) + 1.0 / (CandidateFusion.RRF_K + 1), c20.score, 1e-12)
        assertEquals(2, c20.vectorRank)
        assertEquals(1, c20.keywordRank)
        assertEquals(20L, fused.first().chunkId)
        assertNull(byId.getValue(30L).vectorRank)
        assertNull(byId.getValue(10L).keywordRank)
    }

    @Test
    fun `仅向量通道时不合成关键词名次`() {
        val fused = CandidateFusion.fuse(listOf(7L, 8L), emptyList(), topK = 10)
        assertEquals(listOf(7L, 8L), fused.map { it.chunkId })
        assertNull(fused[0].keywordRank)
    }

    @Test
    fun `topK 截断且分数并列按 chunkId 稳定排序`() {
        // 30=向量第1（1/(K+1)）、40=关键词第1（1/(K+1)）并列 → chunkId 升序 30 在前；10=1/(K+2) 最低
        val fused = CandidateFusion.fuse(
            vectorIds = listOf(30L, 10L),
            keywordIds = listOf(40L),
            topK = 3,
        )
        assertEquals(3, fused.size)
        assertEquals(listOf(30L, 40L, 10L), fused.map { it.chunkId })
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(CandidateFusion.fuse(emptyList(), emptyList(), 5).isEmpty())
    }
}
