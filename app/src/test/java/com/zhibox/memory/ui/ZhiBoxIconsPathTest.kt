package com.zhibox.memory.ui

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图标 path 解析回归（踩过的坑）：
 *
 * Compose 的 `PathParser` **不支持 SVG 的隐式重复 arc 参数**——`a9 9 0 110-18 9 9 0 010 18`
 * 只解析出第一个弧，剩余参数被静默丢弃，圆就只剩半弧 + 直线闭合。
 * 网页存档（Snapshot）图标右半圆缺失就是这个原因；More/Graph/Attachment 的小圆同病。
 *
 * 约定：两段半圆弧拼圆时，第二个弧必须显式写 `a`；每个圆 = 2 个 RelativeArcTo 节点。
 */
class ZhiBoxIconsPathTest {

    private fun nodes(path: String): List<PathNode> =
        PathParser().parsePathString(path).toNodes()

    private fun arcs(path: String): Int = nodes(path).count { it is PathNode.RelativeArcTo }

    @Test
    fun snapshot_outerCircleHasBothArcs() {
        // 外圆 2 + 两条经线 2 = 4
        assertEquals("Snapshot 外圆必须两段半圆弧都在（右半圆缺失回归）", 4, arcs(ZhiBoxIcons.snapshotPath))
    }

    @Test
    fun more_threeDotsAreFullCircles() {
        assertEquals("3 个点 × 2 段弧", 6, arcs(ZhiBoxIcons.morePath))
    }

    @Test
    fun graph_threeNodesAreFullCircles() {
        assertEquals("3 个节点 × 2 段弧", 6, arcs(ZhiBoxIcons.graphPath))
    }

    @Test
    fun attachment_dotIsFullCircle() {
        assertEquals("附件小圆 2 段弧", 2, arcs(ZhiBoxIcons.attachmentPath))
    }

    @Test
    fun closedCircleNeedsExplicitSecondArc() {
        // 反例锁死：隐式重复会被 PathParser 丢弃，只剩 1 个弧
        val implicitRepeat = "M12 21a9 9 0 110-18 9 9 0 010 18z"
        assertEquals("隐式重复 arc 只解析出 1 段（说明回归测试有效）", 1, arcs(implicitRepeat))
    }

    @Test
    fun allCriticalPaths_haveNonEmptyNodes() {
        listOf(
            "more" to ZhiBoxIcons.morePath,
            "graph" to ZhiBoxIcons.graphPath,
            "attachment" to ZhiBoxIcons.attachmentPath,
            "snapshot" to ZhiBoxIcons.snapshotPath,
        ).forEach { (name, path) ->
            assertEquals("$name 应有 path 节点", true, nodes(path).isNotEmpty())
        }
    }
}
