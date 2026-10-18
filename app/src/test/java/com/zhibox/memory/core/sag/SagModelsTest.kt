package com.zhibox.memory.core.sag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10 检索数据契约锁定（JVM 纯数据）：
 * - `RetrievalMode` wire 稳定 + `fromWire` 未知/null 返回 null；
 * - `SagBudget` 默认值 = MVP 生产值，且各预算必须为正；
 * - `SagQuery` 默认（topK=10/sourceIds 空/默认预算）；
 * - `SagHit` 必选字段 + 事件层/融合/通道扩展字段默认值；
 * - `SagChannels` 常量字符串（UI/评测/Retriever 共用，禁止改名）；
 * - `SagTrace`/`SagResult` 构造与 refused/degraded/effectiveMode 语义占位。
 */
class SagModelsTest {

    // ---- RetrievalMode ----

    @Test
    fun retrievalModeWiresAreStableAndResolvable() {
        assertEquals(listOf("vector", "multi_es_fast", "multi"), RetrievalMode.entries.map { it.wire })
        assertEquals(RetrievalMode.VECTOR, RetrievalMode.fromWire("vector"))
        assertEquals(RetrievalMode.MULTI_ES_FAST, RetrievalMode.fromWire("multi_es_fast"))
        assertEquals(RetrievalMode.MULTI, RetrievalMode.fromWire("multi"))
        assertNull(RetrievalMode.fromWire("unknown"))
        assertNull(RetrievalMode.fromWire(null))
    }

    // ---- SagBudget ----

    @Test
    fun budgetDefaultsMatchProductionContract() {
        val budget = SagBudget()
        assertEquals(50, budget.kPool)
        assertEquals(50, budget.kSeed)
        assertEquals(50, budget.frontier)
        assertEquals(100, budget.kCand)
        assertEquals(5, budget.kEvent)
        assertEquals(10, budget.kOut)
    }

    @Test
    fun budgetRequiresPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kPool = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kSeed = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(frontier = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kCand = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kEvent = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kOut = 0) }
        assertThrows(IllegalArgumentException::class.java) { SagBudget(kOut = -1) }
    }

    @Test
    fun budgetIsValueComparable() {
        assertEquals(SagBudget(), SagBudget())
        assertTrue(SagBudget(kOut = 20) != SagBudget())
    }

    // ---- SagQuery ----

    @Test
    fun queryDefaults() {
        val query = SagQuery(query = "张三 上海", mode = RetrievalMode.MULTI_ES_FAST)
        assertEquals(10, query.topK)
        assertEquals(emptyList<String>(), query.sourceIds)
        assertEquals(SagBudget(), query.budget)
    }

    // ---- QueryEntity ----

    @Test
    fun queryEntityCarriesBindingAndSeedMetadata() {
        val bound = QueryEntity(
            id = 42L,
            type = "person",
            name = "张三",
            normalizedName = "张三",
            score = 1.0,
            source = "exact",
        )
        assertEquals(42L, bound.id)
        assertEquals("exact", bound.source)
        val unbound = bound.copy(id = 0L, source = "llm", score = 0.8)
        assertEquals("LLM 实体未绑定 DB 用 id=0", 0L, unbound.id)
        assertTrue(unbound.score in 0.0..1.0)
    }

    // ---- SagHit ----

    @Test
    fun sagHitRequiredFieldsAndOptionalDefaults() {
        val hit = SagHit(
            chunkId = 7L,
            documentId = "doc-1",
            sourceId = "src-1",
            ordinal = 3,
            content = "张三在上海学习数学。",
            charStart = 0,
            charEnd = 11,
            docTitle = "学习笔记",
            sourceTitle = "来源",
            sourceUri = "file:///x.txt",
        )
        assertEquals(7L, hit.chunkId)
        assertEquals("src-1", hit.sourceId)
        assertNull("纯 chunk 命中无事件", hit.eventId)
        assertEquals(emptyList<Long>(), hit.entityIds)
        assertEquals(emptySet<String>(), hit.channels)
        assertEquals(0.0, hit.score, 0.0)
        assertNull(hit.fusedScore)
        assertEquals("", hit.eventTitle)
        assertEquals("", hit.eventSummary)
        assertEquals(emptyList<String>(), hit.entityNames)

        val eventHit = hit.copy(
            eventId = 100L,
            entityIds = listOf(1L, 2L),
            channels = setOf(SagChannels.EVENT_VECTOR, SagChannels.EVENT_ENTITY),
            score = 0.75,
            fusedScore = 0.5,
            eventTitle = "上海学习数学",
            eventSummary = "张三在上海学习数学",
            entityNames = listOf("张三", "上海"),
        )
        assertEquals(100L, eventHit.eventId)
        assertEquals(listOf(1L, 2L), eventHit.entityIds)
        assertTrue(eventHit.channels.contains(SagChannels.EVENT_VECTOR))
        assertEquals(0.75, eventHit.score, 0.0)
        assertEquals(0.5, eventHit.fusedScore!!, 0.0)
    }

    // ---- SagChannels ----

    @Test
    fun channelConstantsAreStableAndDistinct() {
        assertEquals(
            listOf("vector", "keyword", "event_vector", "event_entity", "semantic_fill", "llm_selected"),
            listOf(
                SagChannels.VECTOR,
                SagChannels.KEYWORD,
                SagChannels.EVENT_VECTOR,
                SagChannels.EVENT_ENTITY,
                SagChannels.SEMANTIC_FILL,
                SagChannels.LLM_SELECTED,
            ),
        )
        val all = listOf(
            SagChannels.VECTOR, SagChannels.KEYWORD, SagChannels.EVENT_VECTOR,
            SagChannels.EVENT_ENTITY, SagChannels.SEMANTIC_FILL, SagChannels.LLM_SELECTED,
        )
        assertEquals("通道常量不得重复", all.size, all.toSet().size)
    }

    // ---- SagTrace / SagResult ----

    @Test
    fun traceAndResultCarryModeDegradationAndRefusal() {
        val trace = SagTrace(
            mode = RetrievalMode.MULTI_ES_FAST.wire,
            queryHash = "a".repeat(64),
            stages = listOf(
                SagTraceStage("vector_search", 3, 12),
                SagTraceStage("vector_fallback", 3, 1),
            ),
            seedEvents = 0,
            seedEntities = 2,
            expandedEvents = 0,
            candidates = 3,
            selected = 3,
            degradedReason = "事件层不可用：未找到派生空间",
            elapsedMs = 15,
        )
        val result = SagResult(
            query = "张三",
            mode = RetrievalMode.MULTI_ES_FAST,
            hits = emptyList(),
            trace = trace,
            refused = null,
            degraded = true,
            effectiveMode = RetrievalMode.VECTOR,
        )
        assertEquals(RetrievalMode.MULTI_ES_FAST, result.mode)
        assertEquals("降级后实际模式", RetrievalMode.VECTOR, result.effectiveMode)
        assertTrue(result.degraded)
        assertNull(result.refused)
        assertEquals(64, trace.queryHash.length)
        assertTrue(trace.degradedReason!!.startsWith("事件层不可用："))
        assertEquals(listOf("vector_search", "vector_fallback"), trace.stages.map { it.name })

        val refused = result.copy(
            mode = RetrievalMode.MULTI,
            refused = "multi 需要配置并允许联网的 LLM，可切换到 vector / multi_es_fast 离线模式",
        )
        assertTrue(refused.refused!!.contains("multi 需要配置并允许联网的 LLM"))
    }
}
