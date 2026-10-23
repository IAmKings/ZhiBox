package com.zhibox.memory.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能状态判定矩阵（P0-1 纯 JVM 单测）。
 *
 * 锁定 `docs/product/INTELLIGENCE_HUB_SPEC.md` §3.1 的短路顺序与 §4 的空态口径：
 * 1) 四态判定优先级：损坏(ERROR) > 缺失(NOT_READY) > 有待处理(PARTIAL) > 否则 READY；
 * 2) 读取失败必须降级为 Unknown/-1，不能被当成"正常"；
 * 3) 只有"未就绪"才触发引导条；未就绪/异常不动画；
 * 4) 待处理项顺序稳定（角标数字 = 项数，不是分块数）。
 */
class IntelligenceStatusTest {

    // ── 四态 ────────────────────────────────────────────────────

    @Test
    fun ready_whenModelPresentAndNoPending() {
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = "embeddinggemma-2-440m",
                spaceCount = 2,
                gpuMode = "auto",
                llmConfigured = true,
                llmReadSucceeded = true,
                legacySpaceCount = 0,
                extractionFailedCount = 0,
            ),
        )
        assertEquals(IntelligenceLevel.READY, status.level)
        assertTrue(status.animates)
        assertFalse(status.needsOnboarding)
        assertEquals(0, status.pendingCount)
    }

    @Test
    fun partial_whenPendingButModelReady() {
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = "m",
                spaceCount = 1,
                legacySpaceCount = 3,
                extractionFailedCount = 0,
            ),
        )
        assertEquals(IntelligenceLevel.PARTIAL, status.level)
        assertEquals(listOf(PendingItem.LEGACY_SPACES), status.pending)
        assertEquals(1, status.pendingCount)
        assertTrue(status.animates)
    }

    @Test
    fun notReady_whenModelMissing_evenWithPendingItems() {
        // 缺模型时即便有旧空间/抽取失败，也只能是"未就绪"（缺失优先于待处理）
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = null,
                legacySpaceCount = 5,
                extractionFailedCount = 9,
                storePermissionLost = true,
            ),
        )
        assertEquals(IntelligenceLevel.NOT_READY, status.level)
        assertThatOnboarding(status)
        assertTrue("未就绪不应有动效", !status.animates)
    }

    @Test
    fun error_whenModelFileBroken_beatsEverything() {
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = "m",
                modelFileBroken = true,
                legacySpaceCount = 2,
                extractionFailedCount = 4,
            ),
        )
        assertEquals(IntelligenceLevel.ERROR, status.level)
        assertEquals(ModelStatus.Corrupt, status.model)
        assertFalse(status.animates)
        assertFalse("异常态不弹首次引导", status.needsOnboarding)
    }

    // ── 读取失败降级 ─────────────────────────────────────────────

    @Test
    fun unknownReadings_degradeWithoutFakingHealthy() {
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = "m",
                spaceCount = -1,       // 空间读取失败
                gpuMode = null,        // GPU 偏好读取失败
                llmReadSucceeded = false, // LLM 配置读取失败
                legacySpaceCount = -1, // 旧空间读取失败
                extractionFailedCount = -1,
            ),
        )
        assertEquals(IntelligenceLevel.READY, status.level) // 模型可读 -> 仍就绪
        assertEquals(SpaceStatus.Unknown, status.spaces)
        assertEquals(GpuStatus.UNKNOWN, status.gpu)
        assertEquals(LlmStatus.Unknown, status.llm)
        // 读取失败不能被当成"有待处理"
        assertEquals(0, status.pendingCount)
    }

    @Test
    fun llmLoadError_isPartialNotError() {
        val status = evaluateIntelligence(
            IntelligenceReadings(modelName = "m", llmLoadError = true),
        )
        assertEquals(IntelligenceLevel.PARTIAL, status.level)
        assertEquals(LlmStatus.LoadError, status.llm)
        assertTrue(status.pending.contains(PendingItem.LLM_LOAD_ERROR))
    }

    @Test
    fun llmConfiguredAndNotConfigured() {
        assertEquals(
            LlmStatus.Configured,
            evaluateIntelligence(IntelligenceReadings(modelName = "m", llmConfigured = true)).llm,
        )
        assertEquals(
            LlmStatus.NotConfigured,
            evaluateIntelligence(IntelligenceReadings(modelName = "m", llmConfigured = false)).llm,
        )
    }

    @Test
    fun spaces_noneVsReadyVsUnknown() {
        assertEquals(SpaceStatus.None, evaluateIntelligence(IntelligenceReadings(modelName = "m", spaceCount = 0)).spaces)
        assertEquals(SpaceStatus.Ready(3), evaluateIntelligence(IntelligenceReadings(modelName = "m", spaceCount = 3)).spaces)
        assertEquals(SpaceStatus.Unknown, evaluateIntelligence(IntelligenceReadings(modelName = "m", spaceCount = -1)).spaces)
    }

    // ── GPU 档位映射（只表达偏好） ───────────────────────────────

    @Test
    fun gpuModeMapsToPreferenceOnly() {
        assertEquals(GpuStatus.AUTO, evaluateIntelligence(IntelligenceReadings(modelName = "m", gpuMode = "auto")).gpu)
        assertEquals(GpuStatus.ON, evaluateIntelligence(IntelligenceReadings(modelName = "m", gpuMode = "on")).gpu)
        assertEquals(GpuStatus.OFF, evaluateIntelligence(IntelligenceReadings(modelName = "m", gpuMode = "off")).gpu)
        assertEquals(GpuStatus.UNKNOWN, evaluateIntelligence(IntelligenceReadings(modelName = "m", gpuMode = "garbage")).gpu)
        assertEquals(GpuStatus.UNKNOWN, evaluateIntelligence(IntelligenceReadings(modelName = "m", gpuMode = null)).gpu)
    }

    // ── 待处理项集合与顺序 ───────────────────────────────────────

    @Test
    fun pendingItems_haveStableOrderAndCount() {
        val status = evaluateIntelligence(
            IntelligenceReadings(
                modelName = "m",
                legacySpaceCount = 2,
                extractionFailedCount = 7,
                indexUnhealthy = true,
                storePermissionLost = true,
                llmLoadError = true,
            ),
        )
        assertEquals(IntelligenceLevel.PARTIAL, status.level)
        assertEquals(
            listOf(
                PendingItem.LEGACY_SPACES,
                PendingItem.EXTRACTION_FAILED,
                PendingItem.INDEX_UNHEALTHY,
                PendingItem.STORE_PERMISSION_LOST,
                PendingItem.LLM_LOAD_ERROR,
            ),
            status.pending,
        )
        // 角标显示项数，而不是 2+7 的分块数
        assertEquals(5, status.pendingCount)
    }

    // ── 进行中任务只改动效，不改底色 ─────────────────────────────

    @Test
    fun extracting_keepsLevelButMarksBusy() {
        val status = evaluateIntelligence(
            IntelligenceReadings(modelName = "m", extracting = true),
        )
        assertEquals(IntelligenceLevel.READY, status.level)
        assertEquals(IntelligenceBusy.EXTRACTING, status.busy)
        assertTrue(status.animates)
    }

    // ── 冷启动占位 ───────────────────────────────────────────────

    @Test
    fun loadingPlaceholder_isQuietGreyNotGreen() {
        assertEquals(IntelligenceLevel.NOT_READY, IntelligenceStatus.Loading.level)
        assertFalse("首帧占位不应有呼吸动画", IntelligenceStatus.Loading.animates)
    }

    // ── 空模型名视为缺失 ────────────────────────────────────────

    @Test
    fun blankModelNameIsTreatedAsMissing() {
        val status = evaluateIntelligence(IntelligenceReadings(modelName = "   "))
        assertEquals(IntelligenceLevel.NOT_READY, status.level)
        assertEquals(ModelStatus.Missing, status.model)
    }

    private fun assertThatOnboarding(status: IntelligenceStatus) {
        assertTrue(status.needsOnboarding)
    }
}
