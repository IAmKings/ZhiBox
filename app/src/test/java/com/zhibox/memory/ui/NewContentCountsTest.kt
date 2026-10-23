package com.zhibox.memory.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新内容胶囊」计数（纯 JVM）。
 *
 * 规则：新条目属于当前分类 → 计入 inFilter（胶囊「N 条新内容」）；
 * 不属于当前分类 → 取数量最多的那个分类作为 elsewhere（胶囊「「念头」新增 N 条」）。
 */
class NewContentCountsTest {

    private fun card(id: String, category: HomeCategory) = HomeCardItem(
        id = id,
        category = category,
        createdAt = 0L,
        timeLabel = "1/1 00:00",
        title = id,
        preview = "",
        sourceLabel = "",
        status = null,
    )

    @Test
    fun noUnseen_returnsZeros() {
        val cards = listOf(card("a", HomeCategory.NOTE), card("b", HomeCategory.DOCUMENT))
        val counts = countNewContent(cards, seenIds = setOf("a", "b"), filter = HomeFilter.ALL)
        assertEquals(0, counts.inFilter)
        assertNull(counts.elsewhere)
        assertEquals(0, counts.elsewhereCount)
        assertTrue(!counts.hasAny)
    }

    @Test
    fun allFilter_countsEveryUnseen() {
        val cards = listOf(
            card("a", HomeCategory.NOTE),
            card("b", HomeCategory.DOCUMENT),
            card("c", HomeCategory.SNAPSHOT),
        )
        val counts = countNewContent(cards, seenIds = setOf("a"), filter = HomeFilter.ALL)
        assertEquals(2, counts.inFilter)
        assertNull("全部视图下不存在其它分类", counts.elsewhere)
    }

    @Test
    fun noteFilter_countsOnlyNotes_andReportsElsewhere() {
        val cards = listOf(
            card("a", HomeCategory.NOTE),
            card("b", HomeCategory.NOTE),
            card("c", HomeCategory.DOCUMENT),
            card("d", HomeCategory.DOCUMENT),
            card("e", HomeCategory.DOCUMENT),
            card("f", HomeCategory.IMAGE),
        )
        val counts = countNewContent(cards, seenIds = emptySet(), filter = HomeFilter.NOTE)
        assertEquals("念头分类内 2 条", 2, counts.inFilter)
        assertEquals("其它分类最多的是文档 3 条", HomeCategory.DOCUMENT, counts.elsewhere)
        assertEquals(3, counts.elsewhereCount)
    }

    @Test
    fun noteFilter_allUnseenAreNotes_noElsewhere() {
        val cards = listOf(card("a", HomeCategory.NOTE), card("b", HomeCategory.NOTE))
        val counts = countNewContent(cards, seenIds = emptySet(), filter = HomeFilter.NOTE)
        assertEquals(2, counts.inFilter)
        assertNull(counts.elsewhere)
        assertTrue(counts.hasAny)
    }

    @Test
    fun filterWithNoUnseenButOtherCategoryNew_reportsElsewhereOnly() {
        val cards = listOf(card("a", HomeCategory.NOTE), card("b", HomeCategory.IMAGE))
        val counts = countNewContent(cards, seenIds = setOf("a"), filter = HomeFilter.NOTE)
        assertEquals("当前分类没有新内容", 0, counts.inFilter)
        assertEquals(HomeCategory.IMAGE, counts.elsewhere)
        assertEquals(1, counts.elsewhereCount)
    }

    @Test
    fun seenIdsNotInCards_areIgnored() {
        // 已看过的旧条目已被 40 条上限裁掉时不应报错
        val cards = listOf(card("new", HomeCategory.NOTE))
        val counts = countNewContent(cards, seenIds = setOf("old-1", "old-2"), filter = HomeFilter.ALL)
        assertEquals(1, counts.inFilter)
    }
}
