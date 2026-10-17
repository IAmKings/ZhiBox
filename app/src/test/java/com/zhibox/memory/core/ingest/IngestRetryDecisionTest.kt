package com.zhibox.memory.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 取消/重试决策单测（纯 JVM）：attempt 上限决策抽成纯函数，避免必须拉起 Room/Worker。
 *
 * B7 起仅保留 EGemma2 三模型：删除了 Path C（`.task`/TextEmbedder）与 Granite/LFM 路线，
 * 本测试同步收敛为 EGemma2-only 的接口契约（不再断言已删除的 isPathCModel/SPACE_ID_B）。
 */
class IngestRetryDecisionTest {

    @Test
    fun `失败后 attempt 小于上限自动重排队，达到上限转终态 failed`() {
        assertEquals(3, IngestRepository.MAX_ATTEMPTS)
        assertTrue("第 1 次失败应自动重试", IngestRepository.shouldAutoRetry(1))
        assertTrue("第 2 次失败应自动重试", IngestRepository.shouldAutoRetry(2))
        assertFalse("第 3 次失败应为终态", IngestRepository.shouldAutoRetry(3))
        assertFalse(IngestRepository.shouldAutoRetry(4))
    }

    @Test
    fun `不可自动重试的错误不进入 attempt 重排队（B2-7 审查）`() {
        // failOrRequeue 的决策 = error.retryable && shouldAutoRetry(attempt)
        val corrupt = PipelineError.ModelCorrupt("sha mismatch")
        val io = PipelineError.Io("disk")
        assertFalse("模型损坏不应自动重试", corrupt.retryable && IngestRepository.shouldAutoRetry(1))
        assertTrue("IO 瞬态失败可自动重试", io.retryable && IngestRepository.shouldAutoRetry(1))
        assertFalse("达到 attempt 上限后仍终止", io.retryable && IngestRepository.shouldAutoRetry(3))
    }

    @Test
    fun `路径名单保持 EGemma2-only 契约（PathPreferences 依赖 companion 方法）`() {
        // 路径 A（UniversalEmbedder/UE）：440M 通用/特化版
        assertTrue(IngestRepository.isPathAModel("embeddinggemma-2-text-vision-440m.litertlm"))
        assertTrue(IngestRepository.isPathAModel("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"))
        assertFalse(IngestRepository.isPathAModel("embeddinggemma-2-text-270m.litertlm"))
        assertFalse(IngestRepository.isPathAModel("embeddinggemma-2-740m.litertlm"))
        // 路径 B（litertlm EmbeddingEngine）：仅 270M（Granite/LFM 已下线）
        assertTrue(IngestRepository.isPathBModel("embeddinggemma-2-text-270m.litertlm"))
        assertFalse(IngestRepository.isPathBModel("embeddinggemma-2-text-vision-440m.litertlm"))
        assertFalse(IngestRepository.isPathBModel("embeddinggemma-2-740m.litertlm"))
        assertFalse(IngestRepository.isPathBModel("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertFalse(IngestRepository.isPathBModel("LFM2.5-Embedding-350M_wi8fc.litertlm"))
        // 440M 通用 CPU 的存量兼容空间 id 保持不变（向量兼容契约）
        assertEquals("mp-universal/egemma2-440m/app-prefix-v1", IngestRepository.SPACE_ID)
    }
}
