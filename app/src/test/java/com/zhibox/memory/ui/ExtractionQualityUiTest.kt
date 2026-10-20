package com.zhibox.memory.ui

import com.zhibox.memory.core.extraction.BlankEventFields
import com.zhibox.memory.core.extraction.ExtractionQualityReport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B15.2：抽取质量卡片展示辅助（纯 JVM，无 Android/DB/网络）。
 *
 * 覆盖 UI 使用的覆盖率换算（[coveragePercent]/[ratioPercent] 的 0 保护/夹取）与空报告
 * 的覆盖率属性；不重复 core analyzer 的 SQL 统计测试。
 */
class ExtractionQualityUiTest {

    @Test
    fun coveragePercent_0保护与夹取() {
        assertEquals(0, coveragePercent(0, 0))
        assertEquals(0, coveragePercent(0, 10))
        assertEquals(50, coveragePercent(5, 10))
        assertEquals(33, coveragePercent(1, 3))
        assertEquals(100, coveragePercent(10, 10))
        // 数据异常（covered>total）不导致 >100%
        assertEquals(100, coveragePercent(12, 10))
    }

    @Test
    fun ratioPercent_边界与NaN() {
        assertEquals(0, ratioPercent(0.0))
        assertEquals(0, ratioPercent(-0.2))
        assertEquals(50, ratioPercent(0.5))
        assertEquals(13, ratioPercent(0.126))
        assertEquals(100, ratioPercent(1.0))
        assertEquals(100, ratioPercent(1.7))
        assertEquals(0, ratioPercent(Double.NaN))
    }

    @Test
    fun 空报告_覆盖率为0且分布为空() {
        val report = emptyReport()
        assertEquals(0.0, report.extractionCoverage, 0.0)
        assertEquals(0.0, report.aliasCoverage, 0.0)
        assertEquals(0, coveragePercent(report.documentsWithEvents, report.documentsTotal))
    }

    @Test
    fun 非空报告_覆盖率与UI换算一致() {
        val report = report(
            documentsTotal = 40,
            documentsWithEvents = 30,
            entitiesTotal = 8,
            entitiesWithAliases = 2,
        )
        assertEquals(0.75, report.extractionCoverage, 1e-9)
        assertEquals(0.25, report.aliasCoverage, 1e-9)
        assertEquals(75, ratioPercent(report.extractionCoverage))
        assertEquals(25, coveragePercent(report.entitiesWithAliases, report.entitiesTotal))
    }

    private fun emptyReport() = report(
        documentsTotal = 0,
        documentsWithEvents = 0,
        entitiesTotal = 0,
        entitiesWithAliases = 0,
    )

    private fun report(
        documentsTotal: Int,
        documentsWithEvents: Int,
        entitiesTotal: Int,
        entitiesWithAliases: Int,
    ) = ExtractionQualityReport(
        documentsTotal = documentsTotal,
        readyDocuments = documentsTotal,
        eventsTotal = 0,
        eventsActive = 0,
        eventsDiscarded = 0,
        blankEventFields = BlankEventFields(0, 0, 0),
        entitiesTotal = entitiesTotal,
        entitiesWithAliases = entitiesWithAliases,
        aliasesTotal = 0,
        entityTypeCounts = emptyMap(),
        eventEntityLinks = 0,
        documentsWithEvents = documentsWithEvents,
        documentsWithoutEvents = (documentsTotal - documentsWithEvents).coerceAtLeast(0),
        extractionJobsTotal = 0,
        jobsByStatus = emptyMap(),
        jobsByErrorCode = emptyMap(),
    )
}
