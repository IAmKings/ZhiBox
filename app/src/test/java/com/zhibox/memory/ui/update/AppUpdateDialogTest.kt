package com.zhibox.memory.ui.update

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zhibox.memory.core.update.UpdateFailure
import com.zhibox.memory.core.update.UpdateInfo
import com.zhibox.memory.core.update.UpdatePhase
import com.zhibox.memory.core.update.UpdateTrigger
import com.zhibox.memory.core.update.UpdateUiState
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 更新弹窗/设置行的 Robolectric 渲染验收（文案、按钮、下载态不可取消的可见证据）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh")
class AppUpdateDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun info() = UpdateInfo(
        versionName = "0.1.7",
        tagName = "v0.1.7",
        title = "ZhiBox v0.1.7",
        notes = "· 设置页简化\n· 修复已知问题",
        publishedAt = "2026-10-27T00:00:00Z",
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/x-release-signed.apk",
        apkSizeBytes = 70L * 1024 * 1024,
        sha256 = "a".repeat(64),
        sha256Url = null,
    )

    private fun render(phase: UpdatePhase) {
        composeRule.setContent {
            ZhiBoxTheme {
                AppUpdateDialog(
                    state = UpdateUiState(phase = phase, currentVersionName = "0.1.6"),
                    onDownload = {},
                    onDismiss = {},
                    onRetry = {},
                    onOpenPermissionSettings = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun availableShowsVersionNotesAndActions() {
        render(UpdatePhase.Available(UpdateTrigger.MANUAL, info()))
        composeRule.onNodeWithText("发现新版本").assertExists()
        composeRule.onNodeWithText("当前 0.1.6 → 新版本 0.1.7").assertExists()
        composeRule.onNodeWithText("更新内容").assertExists()
        composeRule.onNodeWithText("立即更新").assertExists()
        composeRule.onNodeWithText("稍后").assertExists()
    }

    @Test
    fun downloadingShowsProgressAndHidesActionButtons() {
        render(UpdatePhase.Downloading(info(), 35L * 1024 * 1024, 70L * 1024 * 1024))
        composeRule.onNodeWithText("正在下载更新").assertExists()
        composeRule.onNodeWithText("下载完成后将自动打开系统安装界面").assertExists()
        composeRule.onNodeWithText("立即更新").assertDoesNotExist()
        composeRule.onNodeWithText("稍后").assertDoesNotExist()
    }

    @Test
    fun verifyingHasNoActionButtons() {
        render(UpdatePhase.Verifying(info()))
        composeRule.onNodeWithText("正在校验安装包").assertExists()
        composeRule.onNodeWithText("立即更新").assertDoesNotExist()
        composeRule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test
    fun failedShowsReadableReasonAndRetry() {
        render(UpdatePhase.DownloadFailed(info(), UpdateFailure.ChecksumMismatch))
        composeRule.onNodeWithText("更新失败").assertExists()
        composeRule.onNodeWithText("安装包校验失败，已删除").assertExists()
        composeRule.onNodeWithText("重试").assertExists()
        composeRule.onNodeWithText("关闭").assertExists()
    }

    @Test
    fun permissionShowsSettingsEntry() {
        render(UpdatePhase.NeedInstallPermission(info()))
        composeRule.onNodeWithText("需要安装权限").assertExists()
        composeRule.onNodeWithText("去设置").assertExists()
        composeRule.onNodeWithText("取消").assertExists()
    }

    @Test
    fun idleShowsNoDialog() {
        render(UpdatePhase.Idle)
        composeRule.onNodeWithText("发现新版本").assertDoesNotExist()
    }

    @Test
    fun settingsRowShowsCurrentVersionAndStates() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateSettingsRow(
                        state = UpdateUiState(currentVersionName = "0.1.6"),
                        onCheck = {},
                    )
                    UpdateSettingsRow(
                        state = UpdateUiState(
                            phase = UpdatePhase.UpToDate(UpdateTrigger.MANUAL, "0.1.6"),
                            currentVersionName = "0.1.6",
                        ),
                        onCheck = {},
                    )
                    UpdateSettingsRow(
                        state = UpdateUiState(
                            phase = UpdatePhase.Available(UpdateTrigger.MANUAL, info()),
                            currentVersionName = "0.1.6",
                        ),
                        onCheck = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前版本 0.1.6").assertExists()
        composeRule.onNodeWithText("已是最新版本（0.1.6）").assertExists()
        composeRule.onNodeWithText("发现新版本 0.1.7").assertExists()
    }
}
