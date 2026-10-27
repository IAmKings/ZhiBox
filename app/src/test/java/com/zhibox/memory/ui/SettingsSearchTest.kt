package com.zhibox.memory.ui

import com.zhibox.memory.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置搜索索引完整性 + 搜索逻辑（纯 JVM）。 */
class SettingsSearchTest {

    private val titleMap: Map<Int, String> = mapOf(
        R.string.settings_section_embedding to "嵌入路径",
        R.string.settings_section_store to "模型商店",
        R.string.settings_store_shared_title to "共享模型目录",
        R.string.settings_section_gpu to "GPU 加速",
        R.string.settings_section_models to "模型文件",
        R.string.settings_section_spaces to "向量空间注册表",
        R.string.settings_section_llm to "SAG 增强 / LLM",
        R.string.settings_llm_behavior_title to "SAG 增强与问答",
        R.string.settings_budget_section to "检索预算档位",
        R.string.settings_sag_section to "SAG 抽取状态",
        R.string.settings_section_migration to "知识库迁移",
        R.string.settings_reembed_section to "旧数据同步",
        R.string.settings_section_kb to "知识库",
        R.string.settings_section_diagnostics to "索引诊断",
        R.string.settings_trace_section to "检索 / 问答 trace",
        R.string.settings_quality_section to "抽取质量",
        R.string.settings_section_privacy to "隐私与离线声明",
        R.string.settings_home_manage to "管理模型",
        R.string.settings_hero_current_model to "当前模型",
        R.string.settings_home_import to "导入资料",
        R.string.settings_home_export to "导出备份",
        R.string.settings_restore_latest to "恢复最近备份",
        R.string.settings_hero_library_overview to "资料库概览",
        R.string.settings_home_network_title to "联网问答",
        R.string.settings_home_network_config to "配置服务",
        R.string.settings_home_privacy_title to "隐私与关于",
        R.string.update_row_title to "检查更新",
        R.string.update_settings_title to "更新设置",
        R.string.settings_privacy_version to "版本",
    )

    private fun titleOf(id: Int): String = titleMap[id] ?: "unknown-$id"

    @Test
    fun indexCoversAllAdvancedSectionsExactly() {
        val advancedKeys = SettingsSearchIndex.ENTRIES
            .filter { it.page == SettingsPage.ADVANCED }
            .mapNotNull { it.sectionKey }
            .toSet()
        IntelligenceSection.entries.forEach { section ->
            assertTrue(
                "高级页 section 缺少搜索索引：${section.itemKey}",
                section.itemKey in advancedKeys,
            )
        }
    }

    @Test
    fun entriesHaveUniqueIdsAndValidNavigation() {
        val ids = SettingsSearchIndex.ENTRIES.map { it.id }
        assertEquals("搜索 entry id 重复", ids.size, ids.toSet().size)
        SettingsSearchIndex.ENTRIES.forEach { entry ->
            if (entry.page == SettingsPage.ADVANCED) {
                assertTrue("ADVANCED entry 必须有 sectionKey", entry.sectionKey != null)
            } else {
                assertTrue("聚焦页 entry 应有 itemKey 或 sectionKey", entry.itemKey != null || entry.sectionKey != null)
            }
        }
    }

    @Test
    fun searchMatchesTitleBeforeKeywords() {
        val results = searchSettings("检查更新", titleOf = ::titleOf)
        assertTrue(results.isNotEmpty())
        assertEquals("检查更新", titleOf(results.first().titleRes))

        val keywordResults = searchSettings("备份", titleOf = ::titleOf)
        assertTrue(keywordResults.any { titleOf(it.titleRes).contains("备份") || it.keywords.any { k -> k.contains("备份") } })
    }

    @Test
    fun searchIsCaseInsensitive() {
        val lower = searchSettings("gpu", titleOf = ::titleOf)
        val upper = searchSettings("GPU", titleOf = ::titleOf)
        assertEquals(lower.map { it.id }, upper.map { it.id })
        assertTrue(lower.isNotEmpty())
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(searchSettings("", titleOf = ::titleOf).isEmpty())
        assertTrue(searchSettings("   ", titleOf = ::titleOf).isEmpty())
    }

    @Test
    fun unknownQueryReturnsNothing() {
        assertTrue(searchSettings("不存在的设置项xyz", titleOf = ::titleOf).isEmpty())
    }

    @Test
    fun focusedPageItemIndexMatchesEmitOrder() {
        val state = SettingsUiState(loading = false, reembedTotal = -1, lastReembed = null)
        assertEquals(0, settingsPageItemIndex(SettingsPage.MODELS, "models-hero", state))
        assertEquals(1, settingsPageItemIndex(SettingsPage.MODELS, "section-1-0", state))
        assertEquals(0, settingsPageItemIndex(SettingsPage.PRIVACY, "privacy-hero", state))
        assertEquals(1, settingsPageItemIndex(SettingsPage.PRIVACY, "update-row", state))
        assertEquals(2, settingsPageItemIndex(SettingsPage.PRIVACY, "update-settings", state))
        assertEquals(-1, settingsPageItemIndex(SettingsPage.HOME, "anything", state))
    }
}
