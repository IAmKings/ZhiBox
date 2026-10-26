package com.zhibox.memory.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 高级与诊断页「17 项零缺失」的可执行验收：逐个 scrollToNode 断言 17 张卡标题可达。
 *
 * 标题取自 `values/strings.xml` 的中文默认值；文案改名时本测试同步更新——验收要求的是
 * "用户能在页面上看到这 17 件事"，所以这层耦合是刻意的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h1600dp-xxhdpi")
class SettingsAdvancedRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sectionTitles = listOf(
        "嵌入路径",
        "模型商店",
        "共享模型目录",
        "GPU 加速",
        "模型文件（1）",
        "向量空间注册表",
        "SAG 增强 / LLM",
        "SAG 增强与问答",
        "检索预算档位",
        "SAG 抽取状态",
        "知识库迁移",
        "旧数据同步（切换模型后自动补齐）",
        "知识库",
        "资料索引检查",
        "检索 / 问答 trace",
        "抽取质量（只读）",
        "隐私与离线声明",
    )

    private fun fixture() = SettingsUiState(
        loading = false,
        model = "embeddinggemma-2-text-vision-440m.litertlm",
        docReady = 3,
        chunkCount = 12,
        models = listOf(
            ModelFileItem(
                File("/data/user/0/com.zhibox.memory/files/models/embeddinggemma-2-text-vision-440m.litertlm"),
                "A · EGemma2-440M",
                369L * 1024 * 1024,
            ),
        ),
    )

    private fun render(showTechnical: Boolean, state: SettingsUiState = fixture()) {
        composeRule.setContent {
            ZhiBoxTheme {
                SettingsPageContent(
                    page = SettingsPage.ADVANCED,
                    state = state,
                    actions = SettingsActions(),
                    showTechnical = showTechnical,
                    onShowTechnicalChange = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollTo(title: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
    }

    @Test
    fun allSeventeenSectionsAreReachable() {
        render(showTechnical = false)
        sectionTitles.forEach { title ->
            scrollTo(title)
            composeRule.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun technicalDetailsOffHidesRawFileNames() {
        render(showTechnical = false)
        composeRule
            .onNodeWithText("embeddinggemma-2-text-vision-440m.litertlm")
            .assertDoesNotExist()
    }

    @Test
    fun technicalDetailsOnShowsRawFileNames() {
        render(showTechnical = true)
        scrollTo("模型文件（1）")
        composeRule
            .onNodeWithText("embeddinggemma-2-text-vision-440m.litertlm")
            .assertExists()
    }

    @Test
    fun technicalDetailsOffHidesInternalPaths() {
        render(
            showTechnical = false,
            state = fixture().copy(model = null, models = emptyList()),
        )
        val tree = composeRule.onRoot().printToString()
        assertFalse("技术细节关闭时不应出现内部路径：\n$tree", tree.contains("filesDir"))
        assertFalse("技术细节关闭时不应出现模型扩展名：\n$tree", tree.contains("litertlm"))
        assertFalse("技术细节关闭时不应出现空模型内部说明：\n$tree", tree.contains("无候选模型"))
    }

    @Test
    fun technicalDetailsRowIsAlwaysVisible() {
        render(showTechnical = false)
        composeRule.onNodeWithText("显示技术细节").assertExists()
    }

    @Test
    fun pageTitleComesFromPageEnum() {
        render(showTechnical = false)
        composeRule.onNodeWithText("高级与诊断").assertExists()
    }
}
