package com.zhibox.memory.core.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * P1.3 Explore 相机投影（纯 JVM）：yaw/pitch 旋转、透视到 width/height、深度排序/近大远小、hitTest、
 * 边界/空列表安全。所有断言不依赖 Android。
 */
class ExploreProjectionTest {

    private fun node(id: String, x: Double, y: Double, z: Double) = ExploreNode(
        id = id,
        kind = ExploreNodeKind.EVENT,
        label = id,
        subtitle = "",
        entityType = null,
        position = Vec3(x, y, z),
        eventId = 1L,
    )

    @Test
    fun originProjectsToScreenCenterWithExpectedDepthAndScale() {
        val projection = ExploreProjection(cameraDistance = 3.0)
        val projected = projection.project(listOf(node("n", 0.0, 0.0, 0.0)), width = 200, height = 100)

        val p = projected.single()
        assertEquals(100f, p.screenX, 1e-4f)
        assertEquals(50f, p.screenY, 1e-4f)
        assertEquals(3.0, p.depth, 1e-9)
        val focal = (100.0 / 2.0) / tan(60.0 * PI / 360.0)
        assertEquals((focal / 3.0).toFloat(), p.scale, 1e-4f)
    }

    @Test
    fun yawAndPitchRotateBeforeProjection() {
        val projection = ExploreProjection(yawRadians = PI / 2.0, cameraDistance = 3.0)
        val projected = projection.project(listOf(node("z", 0.0, 0.0, 1.0)), width = 200, height = 100)
        val p = projected.single()
        assertTrue("yaw=90° 后 z=1 移到屏幕右侧：${p.screenX}", p.screenX > 100f)

        val pitched = ExploreProjection(pitchRadians = PI / 2.0, cameraDistance = 3.0)
            .project(listOf(node("y", 0.0, 1.0, 0.0)), width = 200, height = 100)
            .single()
        assertEquals("pitch 后节点仍在水平中心", 100f, pitched.screenX, 1e-4f)
        assertEquals("pitch 把 y=1 转向相机 → depth 变小", 2.0, pitched.depth, 1e-9)
    }

    @Test
    fun resultsAreSortedFarToNearAndNearerNodesHaveLargerScale() {
        val nodes = listOf(
            node("far", 0.0, 0.0, -0.5), // depth 3.5
            node("near", 0.0, 0.0, 0.5), // depth 2.5
            node("mid", 0.0, 0.0, 0.0), // depth 3.0
        )
        val projected = ExploreProjection().project(nodes, width = 200, height = 100)

        assertEquals(listOf("far", "mid", "near"), projected.map { it.node.id })
        assertTrue("深度降序（远→近）", projected[0].depth > projected[1].depth)
        assertTrue(projected[1].depth > projected[2].depth)
        assertTrue("近大远小", projected.last().scale > projected.first().scale)
    }

    @Test
    fun nodesBehindCameraAreCulled() {
        val projected = ExploreProjection(cameraDistance = 3.0)
            .project(listOf(node("behind", 0.0, 0.0, 6.0), node("ok", 0.0, 0.0, 0.0)), width = 100, height = 100)
        assertEquals(listOf("ok"), projected.map { it.node.id })
    }

    @Test
    fun emptyAndDegenerateViewportAreSafe() {
        val projection = ExploreProjection()
        assertTrue(projection.project(emptyList(), 100, 100).isEmpty())
        assertTrue(projection.project(listOf(node("n", 0.0, 0.0, 0.0)), 0, 100).isEmpty())
        assertTrue(projection.project(listOf(node("n", 0.0, 0.0, 0.0)), 100, 0).isEmpty())
        assertTrue(projection.project(listOf(node("n", 0.0, 0.0, 0.0)), -20, 100).isEmpty())
        assertNull(projection.hitTest(emptyList(), 10f, 10f))
    }

    @Test
    fun hitTestPicksNearestWithinThresholdAndNullOutside() {
        val nodes = listOf(
            node("left", -0.5, 0.0, 0.0),
            node("right", 0.5, 0.0, 0.0),
            node("far", 0.0, 0.0, -0.5),
        )
        val projection = ExploreProjection()
        val projected = projection.project(nodes, width = 400, height = 200)
        val left = projected.single { it.node.id == "left" }

        val hit = projection.hitTest(projected, left.screenX + 3f, left.screenY - 2f, thresholdPx = 20f)
        assertEquals("命中最近的 left", "left", hit?.node?.id)
        assertNull("阈值外无命中", projection.hitTest(projected, 10_000f, 10_000f, thresholdPx = 20f))
        assertNull("负阈值安全返回 null", projection.hitTest(projected, left.screenX, left.screenY, -1f))
    }

    @Test
    fun hitTestOverlappingNodesPrefersNearerDepth() {
        // 两个节点投影到同一点（x/y 相同但 z 不同 → depth 不同；屏幕位置相同）
        val near = node("near", 0.0, 0.0, 0.5)
        val far = node("far", 0.0, 0.0, -0.5)
        val projected = ExploreProjection().project(listOf(far, near), width = 200, height = 100)
        // 人为让屏幕坐标相同（模拟重叠绘制）：直接构造 ProjectedNode 列表
        val overlapping = listOf(
            ExploreProjection.ProjectedNode(far, screenX = 100f, screenY = 50f, depth = 3.5, scale = 5f),
            ExploreProjection.ProjectedNode(near, screenX = 100f, screenY = 50f, depth = 2.5, scale = 7f),
        )
        val hit = ExploreProjection().hitTest(overlapping, 100f, 50f, thresholdPx = 30f)
        assertEquals("重叠时取 depth 更小（更近）", "near", hit?.node?.id)
    }

    @Test
    fun boundaryNodeStillProjectsFiniteCoordinates() {
        val projected = ExploreProjection()
            .project(listOf(node("corner", 1.0, 1.0, 1.0)), width = 320, height = 180)
            .single()
        assertTrue(projected.screenX.isFinite())
        assertTrue(projected.screenY.isFinite())
        assertTrue(projected.scale.isFinite())
    }

    @Test
    fun rotateHelperIsDeterministicAndInvertibleAtZeroAngles() {
        val p = Vec3(0.3, -0.4, 0.5)
        val same = ExploreProjection.rotate(p, yawRadians = 0.0, pitchRadians = 0.0)
        assertEquals(p.x, same.x, 1e-12)
        assertEquals(p.y, same.y, 1e-12)
        assertEquals(p.z, same.z, 1e-12)

        val rotated = ExploreProjection.rotate(p, yawRadians = PI / 3.0, pitchRadians = PI / 5.0)
        assertTrue(abs(rotated.length() - p.length()) < 1e-9)
        assertEquals(
            "同一角度可复现",
            rotated,
            ExploreProjection.rotate(p, yawRadians = PI / 3.0, pitchRadians = PI / 5.0),
        )
    }
}
