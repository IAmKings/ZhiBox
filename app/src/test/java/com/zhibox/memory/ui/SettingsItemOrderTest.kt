package com.zhibox.memory.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页深链索引（纯 JVM）。
 *
 * 深链必须按"当前实际 emit 的 item"计数：loading/error/旧空间告警都是条件项，
 * 若按固定顺序硬编码索引，会在告警存在时滚到错误位置。
 */
class SettingsItemOrderTest {

    private val nothing = SettingsItemVisibility()

    @Test
    fun everySectionKeyAppearsExactlyOnceInOrder() {
        val keys = IntelligenceSection.entries.map { it.itemKey }
        keys.forEach { key ->
            assertEquals("section key 重复或缺失: $key", 1, SettingsItemOrder.order.count { it == key })
        }
    }

    @Test
    fun embeddingIndex_skipsConditionalItemsWhenAbsent() {
        // header-intelligence(0), sub-models(1), section-0(2)
        assertEquals(2, SettingsItemOrder.settingsItemIndex(IntelligenceSection.Embedding, nothing))
    }

    @Test
    fun loadingAndErrorShiftIndex() {
        val visibility = SettingsItemVisibility(loading = true, hasError = true)
        // loading(0), error(1), header-intelligence(2), sub-models(3), section-0(4)
        assertEquals(4, SettingsItemOrder.settingsItemIndex(IntelligenceSection.Embedding, visibility))
    }

    @Test
    fun legacySpacesShiftKnowledgeIndex() {
        val without = SettingsItemOrder.settingsItemIndex(IntelligenceSection.Knowledge, nothing)
        val with = SettingsItemOrder.settingsItemIndex(
            IntelligenceSection.Knowledge,
            SettingsItemVisibility(hasLegacySpacesError = true, hasLegacySpaces = true),
        )
        assertEquals("两个旧空间条件项各占 1 个 index", without + 2, with)
    }

    @Test
    fun indexAlwaysWithinOrderBounds() {
        val visibility = SettingsItemVisibility(
            loading = true,
            hasError = true,
            hasLegacySpacesError = true,
            hasLegacySpaces = true,
        )
        IntelligenceSection.entries.forEach { section ->
            val index = SettingsItemOrder.settingsItemIndex(section, visibility)
            assertTrue("$section index=$index", index in 0 until SettingsItemOrder.order.size)
        }
    }

    @Test
    fun deepLinkTargetsPointToSettingsSectionKeys() {
        IntelligenceSection.entries.forEach { section ->
            assertTrue("${section.name} 应指向 section-* item", section.itemKey.startsWith("section-"))
        }
    }
}
