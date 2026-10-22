package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B14.1：导入屏「网页 URL 导入」输入/IME/按钮交互的 Compose UI 测试（instrumentation）。
 *
 * 覆盖（全部通过 [ImportContent] 无状态渲染 + 回调捕获，不访问 DB/网络）：
 * 1. 输入 URL 触发 `onUrlInputChange`；
 * 2. IME Go（`ImeAction.Go` + `KeyboardActions.onGo`）触发 `onImportUrl`；
 * 3. 点击「抓取并导入」按钮触发 `onImportUrl`；
 * 4. 空 URL 时按钮禁用；
 * 5. `urlFetching=true` 时按钮禁用（防重复点击）。
 *
 * 依赖既有 `androidx.compose.ui:ui-test-junit4` + debug `ui-test-manifest`（B5 基线）。
 */
@RunWith(AndroidJUnit4::class)
class ImportUrlInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    private fun setImportContent(
        urlInput: String,
        urlFetching: Boolean = false,
        onUrlInputChange: (String) -> Unit = {},
        onImportUrl: () -> Unit = {},
    ) {
        composeRule.setContent {
            ZhiBoxTheme {
                ImportContent(
                    stagingCount = 0,
                    importing = false,
                    cancelling = false,
                    retrying = false,
                    message = null,
                    errorMessage = null,
                    jobs = emptyList(),
                    staging = false,
                    urlFetching = urlFetching,
                    urlInput = urlInput,
                    onUrlInputChange = onUrlInputChange,
                    onPickFiles = {},
                    onImportUrl = onImportUrl,
                    onImportAll = {},
                    onCancelImport = {},
                    onRetryFailed = {},
                    onDismissError = {},
                    onBack = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun urlField_输入触发onUrlInputChange() {
        val changed = AtomicReference("")
        setImportContent(
            urlInput = "",
            onUrlInputChange = { changed.set(it) },
        )

        composeRule.onNodeWithTag(ImportUrlInputTag).performTextInput(VALID_URL)

        assertEquals(VALID_URL, changed.get())
    }

    @Test
    fun urlField_触发IME_Go回调onImportUrl() {
        val goTriggered = AtomicBoolean(false)
        setImportContent(
            urlInput = VALID_URL,
            onImportUrl = { goTriggered.set(true) },
        )

        // performClick 先让 TextField 获得焦点，再发送 IME 动作
        composeRule.onNodeWithTag(ImportUrlInputTag).performClick()
        composeRule.onNodeWithTag(ImportUrlInputTag).performImeAction()

        assertTrue("IME Go 应触发 onImportUrl", goTriggered.get())
    }

    @Test
    fun fetchButton_点击触发onImportUrl() {
        val clicked = AtomicBoolean(false)
        setImportContent(
            urlInput = VALID_URL,
            onImportUrl = { clicked.set(true) },
        )

        composeRule.onNodeWithTag(ImportUrlFetchTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ImportUrlFetchTag).performClick()

        assertTrue("抓取按钮应触发 onImportUrl", clicked.get())
    }

    @Test
    fun fetchButton_空URL禁用() {
        setImportContent(urlInput = "")

        composeRule.onNodeWithTag(ImportUrlFetchTag).assertIsNotEnabled()
    }

    @Test
    fun fetchButton_抓取中禁用() {
        setImportContent(urlInput = VALID_URL, urlFetching = true)

        composeRule.onNodeWithTag(ImportUrlFetchTag).assertIsNotEnabled()
    }

    @Test
    fun urlSection_可见() {
        setImportContent(urlInput = "")

        composeRule.onNodeWithText(str(R.string.import_url_section)).assertIsDisplayed()
    }

    private companion object {
        const val VALID_URL = "https://example.com/article"
    }
}
