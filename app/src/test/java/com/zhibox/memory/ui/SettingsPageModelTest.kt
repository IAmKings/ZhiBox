package com.zhibox.memory.ui

import com.zhibox.memory.core.model.PathPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页简化（简单首页 + 二级页 + 高级与诊断）的纯 JVM 契约测试。
 *
 * 覆盖三件在评审里被反复强调、且最容易在后续改动中漂移的事：
 * 1. 高级页仍然是 17 个 section 的全集（"零缺失"）；
 * 2. 聚焦页只是高级页的子集（不会出现"某个能力只存在于聚焦页、高级页却丢了"）；
 * 3. 首页/档位摘要不产生任何实现术语（术语黑名单的纯逻辑部分）。
 */
class SettingsPageModelTest {

    @Test
    fun advancedPageCoversAllSeventeenSections() {
        val advanced = SettingsPage.ADVANCED.sectionKeys
        assertEquals("高级与诊断页应包含全部 17 个 section", 17, advanced.size)
        IntelligenceSection.entries.forEach { section ->
            assertTrue("缺少 section ${section.name}(${section.itemKey})", section.itemKey in advanced)
        }
    }

    @Test
    fun focusedPagesAreSubsetsOfAdvanced() {
        listOf(
            SettingsPage.MODELS,
            SettingsPage.LIBRARY,
            SettingsPage.NETWORK,
            SettingsPage.PRIVACY,
        ).forEach { page ->
            assertTrue("$page 不应包含未知 section", SettingsPage.ADVANCED.sectionKeys.containsAll(page.sectionKeys))
            assertTrue("$page 不应是空页面", page.sectionKeys.isNotEmpty())
        }
    }

    @Test
    fun homePageEmitsNoSection() {
        assertTrue(SettingsPage.HOME.sectionKeys.isEmpty())
    }

    @Test
    fun deepLinkAlwaysLandsOnAdvancedPage() {
        IntelligenceSection.entries.forEach { section ->
            assertEquals(SettingsPage.ADVANCED, settingsPageFor(section))
        }
    }

    @Test
    fun backFromHomeExitsSettings() {
        assertEquals(
            SettingsBackAction.EXIT_SETTINGS,
            settingsBackAction(SettingsPage.HOME, enteredViaDeepLink = false),
        )
    }

    @Test
    fun backFromFocusedPageReturnsHome() {
        listOf(
            SettingsPage.MODELS,
            SettingsPage.LIBRARY,
            SettingsPage.NETWORK,
            SettingsPage.PRIVACY,
        ).forEach { page ->
            assertEquals(
                "$page 返回应回简单首页",
                SettingsBackAction.TO_HOME,
                settingsBackAction(page, enteredViaDeepLink = false),
            )
        }
    }

    @Test
    fun backFromDeepLinkAdvancedExitsSettings() {
        assertEquals(
            SettingsBackAction.EXIT_SETTINGS,
            settingsBackAction(SettingsPage.ADVANCED, enteredViaDeepLink = true),
        )
    }

    @Test
    fun backFromNormalAdvancedReturnsHome() {
        assertEquals(
            SettingsBackAction.TO_HOME,
            settingsBackAction(SettingsPage.ADVANCED, enteredViaDeepLink = false),
        )
    }

    // ── 模型档位映射 ──────────────────────────────────────────────

    @Test
    fun modelTierMapping_knownCatalogFiles() {
        assertEquals(
            SettingsModelTier.LIGHT_270,
            settingsModelTier("embeddinggemma-2-text-270m.litertlm"),
        )
        assertEquals(
            SettingsModelTier.STANDARD_440,
            settingsModelTier("embeddinggemma-2-text-vision-440m.litertlm"),
        )
        assertEquals(
            SettingsModelTier.STANDARD_440,
            settingsModelTier("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"),
        )
        assertEquals(
            SettingsModelTier.STRONG_740,
            settingsModelTier("embeddinggemma-2-740m.litertlm"),
        )
        assertEquals(SettingsModelTier.CUSTOM, settingsModelTier("my-custom-model.litertlm"))
        assertEquals(SettingsModelTier.NONE, settingsModelTier(null))
        assertEquals(SettingsModelTier.NONE, settingsModelTier(""))
    }

    @Test
    fun only270And740AreExperimental() {
        assertTrue(SettingsModelTier.LIGHT_270.isExperimental)
        assertTrue(SettingsModelTier.STRONG_740.isExperimental)
        assertFalse(SettingsModelTier.STANDARD_440.isExperimental)
        assertFalse(SettingsModelTier.CUSTOM.isExperimental)
        assertFalse(SettingsModelTier.NONE.isExperimental)
    }

    // ── 首页摘要 ──────────────────────────────────────────────────

    private fun state(
        model: String? = null,
        preference: String = PathPreferences.AUTO,
        docs: Int = 0,
        chunks: Int = 0,
        models: Int = 0,
        answerEnabled: Boolean = false,
        networkDisabled: Boolean = true,
        llmLoaded: Boolean = false,
        llmConfigured: Boolean = false,
        legacy: Int = 0,
        reembedDone: Int = 0,
        reembedTotal: Int = -1,
        backup: Boolean = false,
    ) = SettingsUiState(
        loading = false,
        model = model,
        preference = preference,
        docReady = docs,
        chunkCount = chunks,
        models = List(models) { i ->
            ModelFileItem(java.io.File("/tmp/model-$i.litertlm"), "模型 $i", 1L)
        },
        answerEnabled = answerEnabled,
        networkDisabled = networkDisabled,
        llmLoaded = llmLoaded,
        llm = if (llmConfigured) {
            LlmConfigUi(
                baseUrl = "https://example.com/v1",
                model = "m",
                timeoutSeconds = 30,
                maskedKey = "sk-***abcd",
            )
        } else {
            null
        },
        legacySpaces = List(legacy) { LegacySpaceUi("legacy-$it", 1) },
        reembedDone = reembedDone,
        reembedTotal = reembedTotal,
        hasAutoBackup = backup,
    )

    @Test
    fun summary_emptyLibraryAndMissingModel() {
        val summary = settingsHomeSummary(state())
        assertEquals(SettingsModelTier.NONE, summary.modelTier)
        assertFalse(summary.hasLibraryContent)
        assertFalse(summary.indexed)
        assertFalse(summary.networkAnswerOn)
        assertFalse(summary.llmLoaded)
    }

    @Test
    fun summary_indexedLibrary() {
        val summary = settingsHomeSummary(
            state(
                model = "embeddinggemma-2-text-vision-440m.litertlm",
                docs = 3,
                chunks = 12,
                backup = true,
            ),
        )
        assertEquals(SettingsModelTier.STANDARD_440, summary.modelTier)
        assertTrue(summary.hasLibraryContent)
        assertTrue(summary.indexed)
        assertTrue(summary.backupAvailable)
        assertEquals(3, summary.docsReady)
        assertEquals(12, summary.chunks)
    }

    @Test
    fun summary_networkCompositeState() {
        // 问答开 + 禁网开 → 复合开关仍为关（而不是"已允许"）
        val blocked = settingsHomeSummary(state(answerEnabled = true, networkDisabled = true, llmLoaded = true, llmConfigured = true))
        assertFalse(blocked.networkAnswerOn)

        val allowed = settingsHomeSummary(state(answerEnabled = true, networkDisabled = false, llmLoaded = true, llmConfigured = true))
        assertTrue(allowed.networkAnswerOn)
        assertTrue(allowed.llmConfigured)
        assertTrue(allowed.llmLoaded)
    }

    @Test
    fun summary_legacyAndReembedWarnings() {
        val summary = settingsHomeSummary(
            state(legacy = 2, reembedDone = 1, reembedTotal = 5),
        )
        assertEquals(2, summary.legacyCount)
        assertTrue(summary.reembedActive)
    }

    @Test
    fun summary_doesNotExposeRawIdentifiers() {
        // 摘要只带枚举/布尔/计数，不带任何原始文件名或 spaceId。
        val summary = settingsHomeSummary(
            state(model = "embeddinggemma-2-text-vision-440m.litertlm"),
        )
        val rendered = summary.toString()
        assertFalse(rendered.contains("litertlm"))
        assertFalse(rendered.contains("440m"))
    }
}
