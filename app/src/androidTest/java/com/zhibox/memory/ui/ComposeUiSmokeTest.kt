package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.MainActivity
import com.zhibox.memory.R
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 B5-2 Compose UI 冒烟测试（instrumentation / 真机，`connectedDebugAndroidTest`）。
 *
 * 覆盖三条主链路（数据全部由测试 fake，**不访问 DB/模型/网络**，用例间无顺序依赖）：
 * 1. [SearchContent]：fake [SearchService.SearchResult] + 2 条命中 → 断言标题/命中文本，
 *    点击命中卡片触发 `onHitClick`（B5-5 之后 Content 已无状态，可直接渲染）；
 * 2. [EvidenceScreen]：fake hit 渲染证据详情，点击顶栏返回图标触发 `onBack`；
 * 3. [SourcesContent] 空态：断言空态文案 +「去导入」按钮点击触发 `onOpenImport`；
 *    另覆盖 [SearchContent] 拒绝态（错误文案 + 「知道了」动作回调）。
 * 4. 重建：`ActivityScenario` 启动真实 [MainActivity] 后 `recreate()`，断言首页仍渲染——
 *    MainActivity 在 `onCreate` 中自行 `setContent`，内容不依赖 DB/模型（首页为静态文案）。
 *
 * 依赖：`androidx.compose.ui:ui-test-junit4` + `debugImplementation androidx.compose.ui:ui-test-manifest`
 * （后者为 `createComposeRule` 提供宿主 ComponentActivity，由 build-fixer 引入）。
 */
@RunWith(AndroidJUnit4::class)
class ComposeUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    // ---------------- 1) 搜索 → 命中点击 ----------------

    @Test
    fun searchContent_渲染两条命中_点击命中卡片触发回调() {
        val clicked = AtomicReference<SearchService.SearchHit?>()
        composeRule.setContent {
            ZhiBoxTheme {
                SearchContent(
                    query = FAKE_QUERY,
                    running = false,
                    loadingModel = false,
                    result = fakeSearchResult(),
                    onQueryChange = {},
                    onSearch = {},
                    onBack = {},
                    onHitClick = { clicked.set(it) },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.search_title)).assertIsDisplayed()
        // 2 条命中都在 LazyColumn 可见区（首屏 2 条）
        composeRule.onNodeWithText(DOC_TITLE_A).assertIsDisplayed()
        composeRule.onNodeWithText(DOC_TITLE_B).assertIsDisplayed()

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
                    result = fakeRefusedResult(refusal),
                    onQueryChange = {},
                    onSearch = {},
                    onBack = {},
                    onHitClick = {},
                    onDismissError = { dismissed.set(true) },
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.search_refused_title)).assertIsDisplayed()
        composeRule.onNodeWithText(refusal).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.search_refused_hint)).assertIsDisplayed()
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

        composeRule.onNodeWithText(str(R.string.sources_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.sources_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.sources_go_import)).performClick()
        composeRule.waitForIdle()
        assertTrue("空态「去导入」应触发 onOpenImport", importRequested.get())
    }
}

/**
 * P1-8/B5-2 重建冒烟：真实 [MainActivity] 被 `recreate()`（等价旋转/配置变更）后仍渲染首页。
 *
 * 使用 [createEmptyComposeRule] + [ActivityScenario]：Activity 由测试自己启动/重建，
 * 规则只负责 Compose 语义树与 idle 同步。MainActivity 在 `onCreate` 里自行 `setContent`，
 * 因此重建后会自动重新渲染——不依赖任何模型/DB 数据（首页为静态文案）。
 */
@RunWith(AndroidJUnit4::class)
class ComposeRecreateSmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recreate_后首页仍可渲染() {
        val homeTitle = context.getString(R.string.home_title)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithText(homeTitle).assertIsDisplayed()

            // 等价旋转/深色切换/分屏：Activity 销毁重建，onCreate 重新注入内容
            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(homeTitle).assertIsDisplayed()
        }
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

private fun fakeSearchResult(): SearchService.SearchResult =
    SearchService.SearchResult(
        query = FAKE_QUERY,
        spaceId = "mp-universal/egemma2-440m/app-prefix-v1@test",
        providerId = "mp-universal",
        modelId = "egemma2-440m",
        vectorCandidates = 2,
        keywordCandidates = 2,
        elapsedMs = 12,
        hits = listOf(
            fakeHit(index = 0, title = DOC_TITLE_A),
            fakeHit(index = 1, title = DOC_TITLE_B),
        ),
        refused = null,
    )

private fun fakeRefusedResult(message: String): SearchService.SearchResult =
    SearchService.SearchResult(
        query = FAKE_QUERY,
        spaceId = null,
        providerId = null,
        modelId = null,
        vectorCandidates = 0,
        keywordCandidates = 0,
        elapsedMs = 0,
        hits = emptyList(),
        refused = message,
    )
