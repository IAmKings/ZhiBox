package com.zhibox.memory.ui

import com.zhibox.memory.core.graph.ExploreNode
import com.zhibox.memory.core.graph.ExploreNodeKind
import com.zhibox.memory.core.graph.ExploreProjection
import com.zhibox.memory.core.graph.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.3b：Explore 分页与 Canvas 标签选择（纯 JVM，无 Android/DB/网络）。
 *
 * 覆盖 [explorePageCount]/[explorePageNumber]（0 保护/越界 offset）与
 * [exploreLabelNodeIds]（选中优先、近节点优先、数量上限、空输入）。
 */
class GraphExplorePagingTest {

    private fun projected(id: String, depth: Double, kind: ExploreNodeKind = ExploreNodeKind.EVENT) =
        ExploreProjection.ProjectedNode(
            node = ExploreNode(
                id = id,
                kind = kind,
                label = "label-$id",
                subtitle = "",
                entityType = null,
                position = Vec3(0.0, 0.0, 0.5),
            ),
            screenX = 0f,
            screenY = 0f,
            depth = depth,
            scale = 100f,
        )

    @Test
    fun pageCount_0保护与向上取整() {
        assertEquals(0, explorePageCount(0, 100))
        assertEquals(0, explorePageCount(-5, 100))
        assertEquals(0, explorePageCount(10, 0))
        assertEquals(1, explorePageCount(1, 100))
        assertEquals(1, explorePageCount(100, 100))
        assertEquals(2, explorePageCount(101, 100))
        assertEquals(3, explorePageCount(250, 100))
    }

    @Test
    fun pageNumber_1based与非法输入() {
        assertEquals(1, explorePageNumber(0, 100))
        assertEquals(2, explorePageNumber(100, 100))
        assertEquals(3, explorePageNumber(250, 100))
        assertEquals(1, explorePageNumber(-10, 100))
        assertEquals(1, explorePageNumber(50, 0))
    }

    @Test
    fun labelNodeIds_选中优先且数量受限() {
        val projected = listOf(
            projected("e:1", depth = 2.0),
            projected("e:2", depth = 4.0),
            projected("e:3", depth = 1.0),
        )
        val ids = exploreLabelNodeIds(projected, selectedNodeId = "e:2", limit = 2)
        assertEquals("选中节点必须优先", "e:2", ids.first())
        assertEquals(2, ids.size)
        assertTrue("其余按近节点优先（e:3 depth=1）", ids.contains("e:3"))
    }

    @Test
    fun labelNodeIds_无选中按depth近到远() {
        val projected = listOf(
            projected("e:far", depth = 4.0),
            projected("e:near", depth = 1.0),
            projected("e:mid", depth = 2.0),
        )
        assertEquals(
            listOf("e:near", "e:mid", "e:far"),
            exploreLabelNodeIds(projected, selectedNodeId = null, limit = 10),
        )
    }

    @Test
    fun labelNodeIds_边界与选中不存在() {
        assertTrue(exploreLabelNodeIds(emptyList(), "e:1", 5).isEmpty())
        assertTrue(exploreLabelNodeIds(listOf(projected("e:1", 1.0)), "e:1", 0).isEmpty())

        val projected = listOf(projected("e:1", depth = 3.0), projected("e:2", depth = 1.0))
        val ids = exploreLabelNodeIds(projected, selectedNodeId = "missing", limit = 1)
        assertEquals(listOf("e:2"), ids)
    }
}
