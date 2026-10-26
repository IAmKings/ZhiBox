package com.zhibox.memory.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 简单首页的真机无关渲染验收（Robolectric + Compose）：
 * - 首屏可见卡片 ≤ 5（4 主卡 + 高级入口）；
 * - 页面上不出现任何实现术语（含从 state 泄露的原始文件名）；
 * - 缺模型/空库/联网三态有正确结论文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h1600dp-xxhdpi")
class SettingsHomeRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fixture(
        model: String? = "embeddinggemma-2-text-vision-440m.litertlm",
        docs: Int = 3,
        chunks: Int = 12,
        answerEnabled: Boolean = false,
        networkDisabled: Boolean = true,
        llmLoaded: Boolean = true,
        llmConfigured: Boolean = true,
    ) = SettingsUiState(
        loading = false,
        model = model,
        docReady = docs,
        chunkCount = chunks,
        models = listOf(
            ModelFileItem(
                File("/data/user/0/com.zhibox.memory/files/models/embeddinggemma-2-text-vision-440m.litertlm"),
                "A · EGemma2-440M",
                369L * 1024 * 1024,
            ),
        ),
        llmLoaded = llmLoaded,
        llm = if (llmConfigured) {
            LlmConfigUi(
                baseUrl = "https://api.example.com/v1",
                model = "gpt-4o-mini",
                timeoutSeconds = 30,
                maskedKey = "sk-***abcd",
            )
        } else {
            null
        },
        answerEnabled = answerEnabled,
        networkDisabled = networkDisabled,
    )

    private fun render(state: SettingsUiState) {
        composeRule.setContent {
            ZhiBoxTheme {
                SettingsHomeContent(
                    state = state,
                    actions = SettingsActions(),
                    onOpenPage = {},
                    onOpenImport = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun showsAtMostFiveCards() {
        render(fixture())
        val cards = composeRule
            .onAllNodesWithTag(SETTINGS_HOME_CARD_TAG)
            .fetchSemanticsNodes()
        assertEquals("简单首页应恰好 4 主卡 + 1 高级入口", 5, cards.size)
    }

    @Test
    fun homeTextHasNoImplementationJargon() {
        render(fixture())
        val tree = composeRule.onRoot().printToString()
        listOf(
            "嵌入", "向量", "分块", "重嵌入", "FTS", "SAG", "GPU", "LLM", "并发",
            "孤儿", "queryHash", "litertlm", "B3-14", "错误码",
        ).forEach { term ->
            assertFalse("首页出现实现术语「$term」：\n$tree", tree.contains(term))
        }
    }

    @Test
    fun emptyLibraryAndMissingModelShowNextStep() {
        render(fixture(model = null, docs = 0, chunks = 0, llmConfigured = false))
        composeRule.onNodeWithText("还没有模型").assertExists()
        composeRule.onNodeWithText("还没有资料").assertExists()
        composeRule.onNodeWithText("未配置服务").assertExists()
        composeRule.onNodeWithText("导入资料").assertExists()
    }

    @Test
    fun indexedLibraryShowsCounts() {
        render(fixture())
        composeRule.onNodeWithText("3 篇资料 · 12 个片段").assertExists()
        composeRule.onNodeWithText("标准 440M（推荐） · 已索引").assertExists()
    }
}
