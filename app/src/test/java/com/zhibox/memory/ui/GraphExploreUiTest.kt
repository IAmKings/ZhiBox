package com.zhibox.memory.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.3：3D 探索 Canvas 的近大远小半径换算（纯 JVM，无 Android/DB/网络）。
 *
 * 覆盖 [exploreNodeRadiusPx]：近距离放大/远距离缩小、上下限夹取、非法输入保护。
 */
class GraphExploreUiTest {

    @Test
    fun 近节点半径大于远节点() {
        val base = 10f
        val reference = 100f
        val near = exploreNodeRadiusPx(base, scale = 180f, referenceScale = reference)
        val center = exploreNodeRadiusPx(base, scale = reference, referenceScale = reference)
        val far = exploreNodeRadiusPx(base, scale = 40f, referenceScale = reference)

        assertTrue("近节点应大于中心节点", near > center)
        assertTrue("中心节点应大于远节点", center > far)
        assertEquals(base, center, 0.001f)
    }

    @Test
    fun 半径夹取在0_5倍到1_8倍之间() {
        val base = 10f
        val reference = 100f
        assertEquals(base * 0.5f, exploreNodeRadiusPx(base, 1f, reference), 0.001f)
        assertEquals(base * 1.8f, exploreNodeRadiusPx(base, 1000f, reference), 0.001f)
    }

    @Test
    fun 非法输入返回安全值() {
        assertEquals(0f, exploreNodeRadiusPx(0f, 1f, 1f), 0.001f)
        assertEquals(10f, exploreNodeRadiusPx(10f, 1f, 0f), 0.001f)
        assertEquals(10f, exploreNodeRadiusPx(10f, -1f, 1f), 0.001f)
    }
}
