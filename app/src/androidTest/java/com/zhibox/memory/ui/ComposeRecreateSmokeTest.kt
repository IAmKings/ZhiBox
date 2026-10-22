package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.zhibox.memory.MainActivity
import com.zhibox.memory.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 Activity 重建校准项；部分 OEM 的窗口同步会挂起，因此只在独占设备上按需运行。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ComposeRecreateSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun recreate_后首页仍可渲染() {
        val homeTitle = composeRule.activity.getString(R.string.home_title)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(homeTitle).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(homeTitle).assertIsDisplayed()
    }
}
