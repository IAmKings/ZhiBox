package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.R
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.core.sag.SagChannels
import com.zhibox.memory.core.sag.SagHit
import com.zhibox.memory.core.sag.SagResult
import com.zhibox.memory.core.sag.SagTrace
import com.zhibox.memory.core.sag.SagTraceStage
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.ui.theme.ZhiBoxTheme
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
 * P2 B5-2 Compose UI 冒烟测试（Robolectric，不占用真机窗口系统）。
 *
 * 覆盖三条主链路（数据全部由测试 fake，**不访问 DB/模型/网络**，用例间无顺序依赖）：
 * 1. [SearchContent]：fake [SearchService.SearchResult] + 2 条命中 → 断言标题/命中文本，
 *    点击命中卡片触发 `onHitClick`（B5-5 之后 Content 已无状态，可直接渲染）；
 * 2. [EvidenceScreen]：fake hit 渲染证据详情，点击顶栏返回图标触发 `onBack`；
 * 3. [SourcesContent] 空态：断言空态文案 +「去导入」按钮点击触发 `onOpenImport`；
 *    另覆盖 [SearchContent] 拒绝态（错误文案 + 「知道了」动作回调）。
 * 依赖：`androidx.compose.ui:ui-test-junit4` + `debugImplementation androidx.compose.ui:ui-test-manifest`
 * （后者为 `createComposeRule` 提供宿主 ComponentActivity，由 build-fixer 引入）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w407dp-h853dp-xxhdpi")
class ComposeUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun str(id: Int): String = context.getString(id)

    // ---------------- 1) 搜索 → 命中点击 ----------------

    @Test
    fun searchContent_渲染两条命中_点击命中卡片触发回调() {
        val clicked = AtomicReference<SagHit?>()
        composeRule.setContent {
            ZhiBoxTheme {
                SearchContent(
                    query = FAKE_QUERY,
                    running = false,
                    loadingModel = false,
                    mode = RetrievalMode.MULTI_ES_FAST,
                    result = fakeSagResult(),
                    errorMessage = null,
                    exportingTrace = false,
                    traceExportMessage = null,
                    onQueryChange = {},
                    onSearch = {},
                    onModeChange = {},
                    onBack = {},
                    onHitClick = { clicked.set(it) },
                    onExportTrace = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.search_title)).assertIsDisplayed()
        // 结果区在可滚动 Column 内；小屏需滚动到第 2 条
        composeRule.onNodeWithText(DOC_TITLE_A).assertIsDisplayed()
        composeRule.onNodeWithText(DOC_TITLE_B).performScrollTo().assertIsDisplayed()

        // 命中卡片 = Card + Modifier.clickable：merged semantics 上 click 动作
        composeRule.onNodeWithText(DOC_TITLE_A).performClick()
        composeRule.waitForIdle()
        assertEquals("doc-a", clicked.get()?.documentId)
    }

    // ---------------- 2) 拒绝态（错误态）动作 ----------------

    @Test
    fun searchContent_拒绝态_显示可读错误并回调关闭() {
        val dismissed = AtomicBoolean(false)
        val refusal = "未找到可用模型（测试桩，不访问真实模型）"
        composeRule.setContent {
            ZhiBoxTheme {
                SearchContent(
                    query = FAKE_QUERY,
                    running = false,
                    loadingModel = false,
                    mode = RetrievalMode.MULTI,
                    result = fakeRefusedSagResult(refusal),
                    errorMessage = null,
                    exportingTrace = false,
                    traceExportMessage = null,
                    onQueryChange = {},
                    onSearch = {},
                    onModeChange = {},
                    onBack = {},
                    onHitClick = {},
                    onExportTrace = {},
                    onDismissError = { dismissed.set(true) },
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.search_refused_title)).assertIsDisplayed()
        composeRule.onNodeWithText(refusal).assertIsDisplayed()
        // 拒绝态 mode=MULTI：B10 起提示为 multi 专用文案
        composeRule.onNodeWithText(str(R.string.search_multi_refused_hint)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.action_dismiss)).performClick()
        composeRule.waitForIdle()
        assertTrue("拒绝态动作应触发 onDismissError", dismissed.get())
    }

    // ---------------- 3) 证据详情 → 返回 ----------------

    @Test
    fun evidenceScreen_渲染证据详情_点击返回触发回调() {
        val hit = fakeHit(index = 0, title = DOC_TITLE_A)
        val backed = AtomicBoolean(false)
        composeRule.setContent {
            ZhiBoxTheme {
                EvidenceScreen(hit = hit, onBack = { backed.set(true) })
            }
        }

        composeRule.onNodeWithText(str(R.string.evidence_title)).assertIsDisplayed()
        composeRule.onNodeWithText(hit.docTitle).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.evidence_content)).assertIsDisplayed()
        composeRule.onNodeWithText(hit.content).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        composeRule.waitForIdle()
        assertTrue("证据详情返回应触发 onBack", backed.get())
    }

    // ---------------- 4) 来源空态 → 去导入 ----------------

    @Test
    fun sourcesContent_空态_显示去导入按钮并触发回调() {
        val importRequested = AtomicBoolean(false)
        composeRule.setContent {
            ZhiBoxTheme {
                SourcesContent(
                    sources = emptyList(),
                    documents = emptyList(),
                    loading = false,
                    deleteError = null,
                    onBack = {},
                    onOpenImport = { importRequested.set(true) },
                    onDeleteRequest = {},
                    onDismissError = {},
                )
            }
        }

        // B12：SourcesContent 不再自带 Scaffold/title（标题由 SourcesScreen 统一持有），
        // 这里直接测内容空态与动作；标题由 SourcesScreen 的顶部栏负责。
        composeRule.onNodeWithText(context.getString(R.string.sources_summary, 0, 0, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.sources_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.sources_go_import)).performClick()
        composeRule.waitForIdle()
        assertTrue("空态「去导入」应触发 onOpenImport", importRequested.get())
    }
}

// ───────────────────────── fake 数据（不触碰真实 DB/模型） ─────────────────────────

private const val FAKE_QUERY = "蓝牙断开怎么恢复"
private const val DOC_TITLE_A = "示例文档 A · 蓝牙排查"
private const val DOC_TITLE_B = "示例文档 B · 协议栈说明"

private fun fakeHit(index: Int, title: String = DOC_TITLE_A): SearchService.SearchHit =
    SearchService.SearchHit(
        chunkId = 1000L + index,
        documentId = if (index == 0) "doc-a" else "doc-b",
        ordinal = index,
        content = "第 ${index + 1} 条命中片段：测试用假数据，仅用于验证命中卡片与证据详情渲染，不读取知识库。",
        charStart = index * 100,
        charEnd = index * 100 + 48,
        docTitle = title,
        sourceTitle = "示例来源",
        sourceUri = "content://com.zhibox.memory.test/source-${index + 1}",
        vectorRank = index + 1,
        keywordRank = index + 1,
        fusedScore = 0.9 - index * 0.1,
        channels = "向量+关键词",
    )

private fun fakeSagHit(index: Int, title: String = DOC_TITLE_A): SagHit =
    SagHit(
        chunkId = 1000L + index,
        documentId = if (index == 0) "doc-a" else "doc-b",
        sourceId = "src-${index + 1}",
        ordinal = index,
        content = "第 ${index + 1} 条命中片段：测试用假数据，仅用于验证命中卡片与证据详情渲染，不读取知识库。",
        charStart = index * 100,
        charEnd = index * 100 + 48,
        docTitle = title,
        sourceTitle = "示例来源",
        sourceUri = "content://com.zhibox.memory.test/source-${index + 1}",
        eventId = 2000L + index,
        entityIds = listOf(3000L + index),
        channels = setOf(SagChannels.EVENT_VECTOR, SagChannels.EVENT_ENTITY),
        score = 0.9 - index * 0.1,
        fusedScore = 0.9 - index * 0.1,
        eventTitle = "示例事件 ${index + 1}",
    )

private fun fakeTrace(): SagTrace = SagTrace(
    mode = RetrievalMode.MULTI_ES_FAST.wire,
    queryHash = "a".repeat(64),
    stages = listOf(
        SagTraceStage("normalize", 1, 0),
        SagTraceStage("supplement", 2, 1),
        SagTraceStage("trace", 1, 0),
    ),
    seedEvents = 1,
    seedEntities = 1,
    expandedEvents = 1,
    candidates = 2,
    selected = 2,
    degradedReason = null,
    elapsedMs = 12,
)

private fun fakeSagResult(): SagResult = SagResult(
    query = FAKE_QUERY,
    mode = RetrievalMode.MULTI_ES_FAST,
    hits = listOf(
        fakeSagHit(index = 0, title = DOC_TITLE_A),
        fakeSagHit(index = 1, title = DOC_TITLE_B),
    ),
    trace = fakeTrace(),
    refused = null,
    degraded = false,
    effectiveMode = RetrievalMode.MULTI_ES_FAST,
)

private fun fakeRefusedSagResult(message: String): SagResult = SagResult(
    query = FAKE_QUERY,
    mode = RetrievalMode.MULTI,
    hits = emptyList(),
    trace = fakeTrace().copy(mode = RetrievalMode.MULTI.wire, degradedReason = null),
    refused = message,
    degraded = true,
    effectiveMode = RetrievalMode.VECTOR,
)
