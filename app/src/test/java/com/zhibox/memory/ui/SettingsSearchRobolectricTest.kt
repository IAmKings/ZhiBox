package com.zhibox.memory.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置搜索页 Robolectric：输入→结果→点击导航回调。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh")
class SettingsSearchRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typingFindsSettingAndClickSelects() {
        var selected: SettingsSearchEntry? = null
        composeRule.setContent {
            ZhiBoxTheme {
                SettingsSearchContent(
                    onBack = {},
                    onSelect = { selected = it },
                )
            }
        }
        composeRule.onNodeWithTag("settings_search_field").performTextInput("更新")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("检查更新").assertExists()
        composeRule.onAllNodesWithTag("settings_search_result")[0].performClick()
        composeRule.waitForIdle()
        assertNotNull("点击结果应回调 onSelect", selected)
    }

    @Test
    fun unknownQueryShowsEmptyState() {
        composeRule.setContent {
            ZhiBoxTheme {
                SettingsSearchContent(onBack = {}, onSelect = {})
            }
        }
        composeRule.onNodeWithTag("settings_search_field").performTextInput("不存在xyz")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有找到相关设置").assertExists()
    }
}
