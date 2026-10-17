package com.zhibox.memory.core.search

import com.zhibox.memory.core.search.SearchService.Companion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B2-10 审查补强：大空间流式扫描门限（纯 JVM）。
 *
 * 背景：缓存上限只保证"不常驻"，不保证"不构建"——100k×768 整矩阵 ≈ 300MB 仍可能 OOM。
 * 实现改为：预估矩阵字节数 > 门限时走分页流式扫描 + 有界 top-K。这里锁定门限纯函数契约。
 */
class SearchStreamingTest {

    @After
    fun tearDown() {
        Companion.setStreamThresholdBytesForTest(null)
    }

    @Test
    fun estimatedMatrixBytes_matchesRowDimFormula() {
        assertEquals(0L, Companion.estimatedMatrixBytes(0, 768))
        assertEquals(768L * 4, Companion.estimatedMatrixBytes(1, 768))
        assertEquals(10_000L * 768 * 4, Companion.estimatedMatrixBytes(10_000, 768))
    }

    @Test
    fun shouldStreamMatrix_onlyAboveDefaultCap() {
        Companion.setStreamThresholdBytesForTest(null) // 默认 64MB
        // 10k×768 ≈ 30.7MB < 64MB → 矩阵路径
        assertFalse(Companion.shouldStreamMatrix(10_000, 768))
        // 25k×768 ≈ 76.8MB > 64MB → 流式
        assertTrue(Companion.shouldStreamMatrix(25_000, 768))
        // 100k×768 ≈ 307MB → 流式（避免 ~300MB 分配）
        assertTrue(Companion.shouldStreamMatrix(100_000, 768))
    }

    @Test
    fun thresholdInjection_changesDecisionForSmallData() {
        Companion.setStreamThresholdBytesForTest(1_000)
        assertTrue("小数据也可强制流式（用于测试覆盖）", Companion.shouldStreamMatrix(1_000, 768))
        Companion.setStreamThresholdBytesForTest(100L * 1024 * 1024)
        assertFalse("大数据在更高门限下走矩阵", Companion.shouldStreamMatrix(25_000, 768))
    }
}
