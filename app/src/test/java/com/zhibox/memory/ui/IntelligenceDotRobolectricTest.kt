package com.zhibox.memory.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D 阶段验收：状态点「文案 + 无动画分支」的 Robolectric 覆盖（规格 §12.5）。
 *
 * 说明：
 * - 文案走真实 Compose 渲染（stringResource），而不是断言纯字符串常量；
 * - 无动画分支同时验证纯函数 [shouldAnimateDot] 与真实 Dot 组合（`animationsEnabledOverride=false`
 *   时不会启动 `rememberInfiniteTransition`，`waitForIdle` 可正常返回）；
 * - 有动画分支无法在 Robolectric 里稳定断言帧（无限动画会阻塞 idle），由 [IntelligenceStatusTest]
 *   的 `animates` 判定 + 真机 reduce-motion 截图覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh")
class IntelligenceDotRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun status(
        level: IntelligenceLevel = IntelligenceLevel.NOT_READY,
        busy: IntelligenceBusy = IntelligenceBusy.NONE,
        busyPct: Int? = null,
        pending: List<PendingItem> = emptyList(),
        model: ModelStatus = if (level == IntelligenceLevel.NOT_READY) {
            ModelStatus.Missing
        } else {
            ModelStatus.Ready("embeddinggemma-2-440m")
        },
    ): IntelligenceStatus = IntelligenceStatus(
        level = level,
        busy = busy,
        busyPct = busyPct,
        model = model,
        spaces = SpaceStatus.Ready(1),
        gpu = GpuStatus.AUTO,
        llm = LlmStatus.NotConfigured,
        pending = pending,
    )

    // ── 文案 ──────────────────────────────────────────────────────

    @Test
    fun label_notReady_saysUnconfiguredModel() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(intelligenceStatusLabel(status()))
            }
        }
        composeRule.onNodeWithText("未配置模型").assertExists()
    }

    @Test
    fun label_partial_includesPendingCount() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(
                    intelligenceStatusLabel(
                        status(
                            level = IntelligenceLevel.PARTIAL,
                            pending = listOf(
                                PendingItem.LEGACY_SPACES,
                                PendingItem.EXTRACTION_FAILED,
                            ),
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("就绪，2 项待处理").assertExists()
    }

    @Test
    fun label_busyDownloading_prefersProgressOverLevel() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(
                    intelligenceStatusLabel(
                        status(
                            busy = IntelligenceBusy.DOWNLOADING,
                            busyPct = 42,
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("正在下载模型，42%").assertExists()
    }

    @Test
    fun label_busySyncing_prefersProgressOverLevel() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(
                    intelligenceStatusLabel(
                        status(
                            busy = IntelligenceBusy.SYNCING,
                            busyPct = 30,
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("正在同步数据，30%").assertExists()
    }

    @Test
    fun label_busyExtracting_saysExtracting() {
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(
                    intelligenceStatusLabel(status(busy = IntelligenceBusy.EXTRACTING)),
                )
            }
        }
        composeRule.onNodeWithText("正在抽取内容").assertExists()
    }

    @Test
    fun label_busyWithoutPct_fallsBackToLevelText() {
        // 下载/同步百分比未知时不能显示 "null%"；回落到四态文案（这里是未配置模型）
        composeRule.setContent {
            ZhiBoxTheme {
                androidx.compose.material3.Text(
                    intelligenceStatusLabel(status(busy = IntelligenceBusy.DOWNLOADING)),
                )
            }
        }
        composeRule.onNodeWithText("未配置模型").assertExists()
    }

    // ── 无动画分支 ────────────────────────────────────────────────

    @Test
    fun shouldAnimateDot_matrix() {
        val ready = status(level = IntelligenceLevel.READY)
        val notReady = status(level = IntelligenceLevel.NOT_READY)
        val error = status(level = IntelligenceLevel.ERROR, model = ModelStatus.Corrupt)
        val downloading = status(busy = IntelligenceBusy.DOWNLOADING, busyPct = 1)

        assertTrue(shouldAnimateDot(ready, animationsEnabled = true))
        assertFalse("减少动画时全静止", shouldAnimateDot(ready, animationsEnabled = false))
        assertFalse("未就绪且无任务应静止", shouldAnimateDot(notReady, animationsEnabled = true))
        assertFalse("异常且无任务应静止", shouldAnimateDot(error, animationsEnabled = true))
        assertTrue("进行中任务应脉冲（即使未就绪）", shouldAnimateDot(downloading, animationsEnabled = true))
        assertFalse(
            "减少动画时进行中任务也静止",
            shouldAnimateDot(downloading, animationsEnabled = false),
        )
    }

    @Test
    fun dot_rendersSingleAccessibilityNode_whenAnimationsDisabled() {
        composeRule.setContent {
            ZhiBoxTheme {
                OfflineReadyDot(
                    status = status(level = IntelligenceLevel.READY),
                    onClick = {},
                    animationsEnabledOverride = false,
                )
            }
        }
        // 无动画 → idle 可正常返回；语义节点合并为一个带标签的按钮（M1 验收项）
        composeRule.onNodeWithContentDescription("智能状态").assertExists()
    }
}
