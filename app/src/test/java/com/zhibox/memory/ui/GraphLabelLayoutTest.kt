package com.zhibox.memory.ui

import com.zhibox.memory.core.graph.ExploreLoadResult
import com.zhibox.memory.core.graph.ExploreLoadSample
import com.zhibox.memory.core.graph.ExplorePerfStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.3c：ExplRE 标签碰撞避让（纯 JVM）与性能 stats 映射。
 *
 * 覆盖 [placeExploreLabels]（选中优先/重叠跳过/越界 clamp/上限/空）与
 * [explorePerfUi]（last/p50/p95/count 与 FTS/LIKE 通道标记）。
 */
class GraphLabelLayoutTest {

    private fun candidate(
        id: String,
        x: Float = 100f,
        baselineY: Float = 100f,
        width: Float = 40f,
        height: Float = 14f,
        selected: Boolean = false,
    ) = ExploreLabelCandidate(
        nodeId = id,
        centerX = x,
        baselineY = baselineY,
        width = width,
        height = height,
        isSelected = selected,
    )

    @Test
    fun 空输入与非法画布返回空() {
        assertTrue(placeExploreLabels(emptyList(), 300f, 300f).isEmpty())
        assertTrue(placeExploreLabels(listOf(candidate("a")), 0f, 300f).isEmpty())
        assertTrue(placeExploreLabels(listOf(candidate("a")), 300f, -1f).isEmpty())
        assertTrue(placeExploreLabels(listOf(candidate("a")), 300f, 300f, limit = 0).isEmpty())
    }

    @Test
    fun 选中标签优先放置_即使传入顺序靠后() {
        // 两个候选重叠：非选中在前，但选中必须优先占位
        val placed = placeExploreLabels(
            candidates = listOf(
                candidate("other", x = 100f, baselineY = 100f),
                candidate("selected", x = 100f, baselineY = 100f, selected = true),
            ),
            canvasWidth = 400f,
            canvasHeight = 400f,
        )
        assertEquals(1, placed.size)
        assertEquals("selected", placed.single().nodeId)
    }

    @Test
    fun 重叠标签跳过_不重叠保留() {
        val placed = placeExploreLabels(
            candidates = listOf(
                candidate("a", x = 100f, baselineY = 100f),
                candidate("b", x = 102f, baselineY = 100f), // 与 a 重叠
                candidate("c", x = 300f, baselineY = 100f), // 不重叠
            ),
            canvasWidth = 600f,
            canvasHeight = 400f,
        )
        assertEquals(listOf("a", "c"), placed.map { it.nodeId })
    }

    @Test
    fun 越界标签clamp到画布内() {
        val placed = placeExploreLabels(
            candidates = listOf(candidate("edge", x = 5f, baselineY = 5f)),
            canvasWidth = 400f,
            canvasHeight = 300f,
        ).single()
        assertTrue("left 应 clamp 到 0", placed.left >= 0f)
        assertTrue("top 应 clamp 到 0", placed.top >= 0f)
        assertTrue(placed.right <= 400f)
        assertTrue(placed.bottom <= 300f)
    }

    @Test
    fun 尺寸大于画布的标签跳过() {
        val placed = placeExploreLabels(
            candidates = listOf(candidate("huge", width = 500f)),
            canvasWidth = 400f,
            canvasHeight = 300f,
        )
        assertTrue(placed.isEmpty())
    }

    @Test
    fun 最多放置12个() {
        val candidates = (1..30).map { index ->
            candidate(
                id = "n:$index",
                x = (index * 60f) % 800f,
                baselineY = (index * 40f) % 500f,
            )
        }
        val placed = placeExploreLabels(candidates, canvasWidth = 1000f, canvasHeight = 800f)
        assertTrue("不应超过 12 个", placed.size <= 12)
        assertTrue(placed.isNotEmpty())
    }

    @Test
    fun padding_使近邻标签跳过() {
        val noPadding = placeExploreLabels(
            candidates = listOf(
                candidate("a", baselineY = 100f),
                candidate("b", baselineY = 120f), // a: top=86,bottom=100; b: top=106,bottom=120 → 间隙 6px
            ),
            canvasWidth = 400f,
            canvasHeight = 300f,
            paddingPx = 0f,
        )
        assertEquals(2, noPadding.size)

        val withPadding = placeExploreLabels(
            candidates = listOf(
                candidate("a", baselineY = 100f),
                candidate("b", baselineY = 120f),
            ),
            canvasWidth = 400f,
            canvasHeight = 300f,
            paddingPx = 8f,
        )
        assertEquals(1, withPadding.size)
    }

    @Test
    fun perfUi_空stats返回null_通道映射() {
        assertNull(explorePerfUi(null))
        assertNull(explorePerfUi(ExplorePerfStats(0, 0, 0, 0, null)))

        fun stats(usedFts: Boolean, usedLike: Boolean) = ExplorePerfStats(
            count = 3,
            p50Ms = 12,
            p95Ms = 40,
            maxMs = 55,
            last = ExploreLoadSample(
                offset = 0,
                pageSize = 100,
                result = ExploreLoadResult.OK,
                total = 100,
                durationMs = 18,
                usedFts = usedFts,
                usedLike = usedLike,
                truncated = false,
            ),
        )
        assertEquals(ExplorePerfChannel.FTS, explorePerfUi(stats(true, false))?.channel)
        assertEquals(ExplorePerfChannel.LIKE, explorePerfUi(stats(false, true))?.channel)
        assertEquals(ExplorePerfChannel.FTS_AND_LIKE, explorePerfUi(stats(true, true))?.channel)
        assertEquals(ExplorePerfChannel.NONE, explorePerfUi(stats(false, false))?.channel)

        val ui = explorePerfUi(stats(true, false))
        assertNotNull(ui)
        assertEquals(18L, ui!!.lastMs)
        assertEquals(12L, ui.p50Ms)
        assertEquals(40L, ui.p95Ms)
        assertEquals(3, ui.count)
    }
}
