package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B12 Graph Compose 冒烟测试（Robolectric，不占用真机窗口系统）。
 *
 * 只渲染无状态 [GraphContent] / [GraphDetailScreen] + fake state，**不启动 ViewModel/DB/网络**，
 * 用例间无顺序依赖。覆盖：
 * 1. source 过滤 chips（全部来源/具体来源）→ onSelectSource（null / id）；
 * 2. 事件卡：标题/摘要/来源·文档/实体 chips + 超过 8 个实体的 `+N`，点击卡 → onOpenEvent；
 * 3. 详情：标题/摘要/实体名+类型/描述/chunk 原文/定位，返回回调；
 * 4. 空态文案；5. 错误 + 重试/知道了 双回调；6. hasMore 时「加载更多」可见 + 分页回调。
 *
 * GraphContent 的事件区为 LazyColumn，断言/点击前置 `performScrollToNode`（小屏安全）；
 * 详情页为 verticalScroll，使用 `performScrollTo`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w407dp-h853dp-xxhdpi")
class GraphUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun str(id: Int, vararg args: Any?): String = context.getString(id, *args)

    // ---------------- 1) 来源过滤 chips ----------------

    @Test
    fun sourceChips_全部与来源A分别回调() {
        val calls = Collections.synchronizedList(mutableListOf<String?>())
        val state = GraphUiState(
            sources = listOf(
                GraphSourceFilter(id = null, title = str(R.string.graph_filter_all)),
                GraphSourceFilter(id = "src_a", title = "来源A"),
            ),
            events = listOf(fakeEvent()),
            totalCount = 1,
        )
        composeRule.setContent {
            ZhiBoxTheme {
                GraphContent(
                    state = state,
                    onSelectSource = { calls.add(it) },
                    onLoadMore = {},
                    onOpenEvent = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.graph_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithText("来源A").assertIsDisplayed()

        composeRule.onNodeWithText("来源A").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.graph_filter_all)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf<String?>("src_a", null), calls)
    }

    // ---------------- 2) 事件卡内容 + 点击 ----------------

    @Test
    fun eventCard_显示标题摘要实体chip与_N_点击回传event() {
        val opened = AtomicReference<GraphEventUi?>()
        val entityNames = (1..8).map { "实体$it" }
        val event = fakeEvent(entityNames = entityNames, entityTotal = 10)
        val state = GraphUiState(
            sources = emptyList(),
            events = listOf(event),
            totalCount = 1,
        )
        composeRule.setContent {
            ZhiBoxTheme {
                GraphContent(
                    state = state,
                    onSelectSource = {},
                    onLoadMore = {},
                    onOpenEvent = { opened.set(it) },
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        // 事件区是唯一可滚动容器（fake 无 sources → 无 LazyRow）
        val moreChip = str(R.string.graph_entity_more, 2) // "+2"
        composeRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(moreChip))

        composeRule.onNodeWithText(event.title).assertIsDisplayed()
        composeRule.onNodeWithText(event.summary).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(R.string.graph_source_doc, event.sourceTitle, event.documentTitle),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(entityNames.first()).assertIsDisplayed()
        composeRule.onNodeWithText(moreChip).assertIsDisplayed()

        composeRule.onNodeWithText(event.title).performClick()
        composeRule.waitForIdle()
        assertEquals(event.id, opened.get()?.id)
    }

    // ---------------- 3) 详情内容 + 返回 ----------------

    @Test
    fun detail_显示实体与原文定位_返回回调() {
        val backed = AtomicBoolean(false)
        val detail = fakeDetail()
        composeRule.setContent {
            ZhiBoxTheme {
                GraphDetailScreen(detail = detail, onBack = { backed.set(true) })
            }
        }

        composeRule.onNodeWithText(str(R.string.graph_event_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText(detail.event.title).assertIsDisplayed()
        composeRule.onNodeWithText(detail.event.summary).assertIsDisplayed()
        composeRule.onNodeWithText(
            str(R.string.graph_source_doc, detail.event.sourceTitle, detail.event.documentTitle),
        ).assertIsDisplayed()

        // 下方内容在 verticalScroll 内：先滚动再断言
        val entitiesTitle = str(R.string.graph_detail_entities, detail.entities.size)
        val entityLine = str(
            R.string.graph_detail_entity_line,
            detail.entities.first().name,
            detail.entities.first().type ?: "—",
        )
        val location = str(
            R.string.graph_detail_location,
            detail.chunkOrdinal!!,
            detail.charStart!!,
            detail.charEnd!!,
        )
        composeRule.onNodeWithText(entityLine).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(entitiesTitle).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(detail.entities.first().description)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.graph_detail_content))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(location).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(detail.chunkContent!!).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        composeRule.waitForIdle()
        assertTrue("详情返回应触发 onBack", backed.get())
    }

    // ---------------- 4) 空态 ----------------

    @Test
    fun emptyState_显示空态文案() {
        composeRule.setContent {
            ZhiBoxTheme {
                GraphContent(
                    state = GraphUiState(),
                    onSelectSource = {},
                    onLoadMore = {},
                    onOpenEvent = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.graph_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.graph_empty_message)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.graph_summary, 0, 0)).assertIsDisplayed()
    }

    // ---------------- 5) 错误 + 重试/关闭 ----------------

    @Test
    fun error_显示失败文案_重试与知道了回调() {
        val retried = AtomicBoolean(false)
        val dismissed = AtomicBoolean(false)
        val errorText = str(R.string.graph_load_failed, "模拟错误（测试桩）")
        composeRule.setContent {
            ZhiBoxTheme {
                GraphContent(
                    state = GraphUiState(error = errorText),
                    onSelectSource = {},
                    onLoadMore = {},
                    onOpenEvent = {},
                    onRetry = { retried.set(true) },
                    onDismissError = { dismissed.set(true) },
                )
            }
        }

        composeRule.onNodeWithText(errorText).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.graph_retry)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("错误卡片动作应触发 onRetry", retried.get())

        composeRule.onNodeWithText(str(R.string.action_dismiss)).performClick()
        composeRule.waitForIdle()
        assertTrue("「知道了」应触发 onDismissError", dismissed.get())
    }

    // ---------------- 6) 加载更多 ----------------

    @Test
    fun hasMore_显示加载更多_点击分页回调() {
        val loadMore = AtomicBoolean(false)
        val state = GraphUiState(
            sources = emptyList(),
            events = listOf(fakeEvent()),
            totalCount = 120,
            hasMore = true,
        )
        composeRule.setContent {
            ZhiBoxTheme {
                GraphContent(
                    state = state,
                    onSelectSource = {},
                    onLoadMore = { loadMore.set(true) },
                    onOpenEvent = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.graph_summary, 120, 1)).assertIsDisplayed()
        composeRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(str(R.string.graph_load_more)))
        composeRule.onNodeWithText(str(R.string.graph_load_more)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("hasMore 时「加载更多」应触发 onLoadMore", loadMore.get())
    }
}

// ---------------- fake 数据（不触碰 ViewModel/DB/网络） ----------------

private fun fakeEvent(
    id: Long = 1,
    entityNames: List<String> = listOf("蓝牙耳机", "协议栈"),
    entityTotal: Int = entityNames.size,
): GraphEventUi = GraphEventUi(
    id = id,
    chunkId = id * 10,
    title = "蓝牙耳机连接问题排查",
    summary = "断开通常由协议栈重置或省电策略触发；可关闭省电优化后重试。",
    sourceId = "src_a",
    sourceTitle = "来源A",
    documentId = "doc_1",
    documentTitle = "蓝牙使用指南",
    entityNames = entityNames,
    entityTotal = entityTotal,
)

private fun fakeDetail(): GraphEventDetailUi = GraphEventDetailUi(
    event = fakeEvent(),
    entities = listOf(
        GraphEntityUi(1, "蓝牙耳机", "product", "待配对设备"),
        GraphEntityUi(2, "协议栈", null, "系统蓝牙协议实现"),
    ),
    chunkContent = "先关闭耳机电源再长按配对键 5 秒，进入可发现状态后重新配对。",
    chunkOrdinal = 2,
    charStart = 300,
    charEnd = 420,
)
