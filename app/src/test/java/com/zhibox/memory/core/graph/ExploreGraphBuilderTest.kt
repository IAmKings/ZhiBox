package com.zhibox.memory.core.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * P1.3 Explore 确定性布局 Builder（纯 JVM）：
 * 节点 id/类型/文案、Fibonacci 球面、事件关联/无关联球面、边去重/仅存在节点、上限截断、空输入、确定性。
 */
class ExploreGraphBuilderTest {

    @Test
    fun emptyInputReturnsEmptyGraph() {
        val graph = ExploreGraphBuilder.build(emptyList(), emptyList())
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertFalse(graph.truncated)
        assertEquals(0, graph.totalEvents)
        assertEquals(ExploreGraphBuilder.MAX_EVENTS, graph.maxEvents)
    }

    @Test
    fun buildIsDeterministicForSameInput() {
        val events = (1L..20L).map { event(it, entityIds = listOf((it % 5) + 1)) }
        val entities = (1L..5L).map { entity(it) }

        val first = ExploreGraphBuilder.build(events, entities)
        val second = ExploreGraphBuilder.build(events, entities)

        assertEquals(first, second)
    }

    @Test
    fun nodeIdsKindsAndLabelsAreStable() {
        val graph = ExploreGraphBuilder.build(
            events = listOf(event(7, title = "上海学习", summary = "张三在上海")),
            entities = listOf(entity(3, name = "上海", type = "location")),
        )

        val eventNode = graph.nodes.single { it.kind == ExploreNodeKind.EVENT }
        val entityNode = graph.nodes.single { it.kind == ExploreNodeKind.ENTITY }
        assertEquals("e:7", eventNode.id)
        assertEquals(ExploreNodeKind.EVENT, eventNode.kind)
        assertEquals(7L, eventNode.eventId)
        assertEquals("上海学习", eventNode.label)
        assertEquals("张三在上海", eventNode.subtitle)
        assertEquals("t:3", entityNode.id)
        assertEquals(ExploreNodeKind.ENTITY, entityNode.kind)
        assertEquals(3L, entityNode.entityId)
        assertEquals("上海", entityNode.label)
        assertEquals("location", entityNode.subtitle)
        assertEquals("location", entityNode.entityType)
    }

    @Test
    fun entityPositionsLieOnUnitSphereAndWithinBounds() {
        val entities = (1L..50L).map { entity(it) }
        val graph = ExploreGraphBuilder.build(emptyList(), entities)

        graph.entityNodes.forEach { node ->
            val pos = node.position
            assertTrue("x ∈ [-1,1]", pos.x in -1.0..1.0)
            assertTrue("y ∈ [-1,1]", pos.y in -1.0..1.0)
            assertTrue("z ∈ [-1,1]", pos.z in -1.0..1.0)
            assertEquals("单位球", 1.0, pos.length(), 1e-9)
        }
        assertEquals("实体互不重叠", entities.size, graph.entityNodes.map { it.position }.toSet().size)
    }

    @Test
    fun eventWithLinkedEntityIsPlacedNearEntityDirection() {
        val entities = (1L..5L).map { entity(it) }
        val events = listOf(event(100, entityIds = listOf(2L)))
        val graph = ExploreGraphBuilder.build(events, entities)

        val eventPos = graph.nodes.single { it.kind == ExploreNodeKind.EVENT }.position
        val entityPos = graph.nodes.single { it.entityId == 2L }.position
        assertEquals("关联事件仍在单位球", 1.0, eventPos.length(), 1e-9)
        val dot = eventPos.x * entityPos.x + eventPos.y * entityPos.y + eventPos.z * entityPos.z
        assertTrue("关联事件方向应靠近实体（dot=$dot）", dot > 0.8)
        assertEquals("应生成 e→t 一条边", 1, graph.edges.size)
        assertEquals("e:100", graph.edges.single().from)
        assertEquals("t:2", graph.edges.single().to)
    }

    @Test
    fun unassociatedEventsUseSecondarySphereWithoutEdges() {
        val events = listOf(event(1), event(2), event(3))
        val graph = ExploreGraphBuilder.build(events, emptyList())

        assertTrue(graph.edges.isEmpty())
        val positions = graph.eventNodes.map { it.position }
        assertEquals(3, positions.toSet().size)
        positions.forEach { pos ->
            assertEquals(
                "无关联事件在另一球面（半径 ${ExploreGraphBuilder.UNASSOCIATED_RADIUS}）",
                ExploreGraphBuilder.UNASSOCIATED_RADIUS,
                pos.length(),
                1e-9,
            )
        }
    }

    @Test
    fun eventLinkedToMissingEntityFallsBackToSecondarySphere() {
        val graph = ExploreGraphBuilder.build(
            events = listOf(event(9, entityIds = listOf(999L))),
            entities = listOf(entity(1)),
        )
        assertEquals("不存在的实体不生成边", 0, graph.edges.size)
        val eventPos = graph.eventNodes.single().position
        assertEquals(ExploreGraphBuilder.UNASSOCIATED_RADIUS, eventPos.length(), 1e-9)
    }

    @Test
    fun edgesAreDedupedAndSorted() {
        val graph = ExploreGraphBuilder.build(
            events = listOf(event(5, entityIds = listOf(2, 1, 2, 1))),
            entities = listOf(entity(1), entity(2)),
        )
        assertEquals(2, graph.edges.size)
        assertEquals(listOf("e:5", "e:5"), graph.edges.map { it.from })
        assertEquals(listOf("t:1", "t:2"), graph.edges.map { it.to })
    }

    @Test
    fun eventLimitCapsNodesAndSetsTruncated() {
        val events = (1L..205L).map { event(it) }
        val graph = ExploreGraphBuilder.build(events, emptyList())

        assertEquals("事件 ≤ 200", ExploreGraphBuilder.MAX_EVENTS, graph.eventNodes.size)
        assertEquals(200, graph.maxEvents)
        assertEquals("totalEvents 保留未截断总数", 205, graph.totalEvents)
        assertTrue(graph.truncated)
    }

    @Test
    fun entityLimitCapsNodesAndEdges() {
        val events = listOf(event(1, entityIds = (1L..401L).toList()))
        val entities = (1L..401L).map { entity(it) }
        val graph = ExploreGraphBuilder.build(events, entities)

        assertEquals("实体 ≤ 400", ExploreGraphBuilder.MAX_ENTITIES, graph.entityNodes.size)
        assertEquals("事件 1 个", 1, graph.eventNodes.size)
        assertTrue("超出实体上限 truncated", graph.truncated)
        assertEquals("边只连存在节点（截断后 400）", ExploreGraphBuilder.MAX_ENTITIES, graph.edges.size)
    }

    @Test
    fun customSmallerLimitsAreClampedAndRespected() {
        val events = (1L..10L).map { event(it) }
        val graph = ExploreGraphBuilder.build(events, emptyList(), maxEvents = 3)
        assertEquals(3, graph.eventNodes.size)
        assertEquals(3, graph.maxEvents)
        assertEquals(10, graph.totalEvents)
        assertTrue(graph.truncated)

        val zeroLimit = ExploreGraphBuilder.build(events, emptyList(), maxEvents = 0)
        assertEquals("maxEvents 最小夹紧到 1", 1, zeroLimit.eventNodes.size)
        assertEquals(1, zeroLimit.maxEvents)
    }

    @Test
    fun jitterIsDeterministicPerEventAndDifferentAcrossEvents() {
        val events = listOf(event(1, entityIds = listOf(1L)), event(2, entityIds = listOf(1L)))
        val entities = listOf(entity(1))
        val graph = ExploreGraphBuilder.build(events, entities)
        val positions = graph.eventNodes.associate { it.eventId to it.position }
        assertNotEquals("不同事件抖动不同", positions[1L], positions[2L])
    }

    private fun event(
        id: Long,
        title: String = "事件$id",
        summary: String = "摘要$id",
        entityIds: List<Long> = emptyList(),
    ) = ExploreEventInput(
        eventId = id,
        title = title,
        summary = summary,
        sourceId = "src-1",
        documentId = "doc-1",
        entityIds = entityIds,
    )

    private fun entity(
        id: Long,
        name: String = "实体$id",
        type: String? = "person",
    ) = ExploreEntityInput(entityId = id, name = name, entityType = type)
}
