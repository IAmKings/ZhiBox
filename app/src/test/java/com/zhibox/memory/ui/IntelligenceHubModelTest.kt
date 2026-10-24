package com.zhibox.memory.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 面板首屏关键行（§4 E1–E15）与附加 pill 的纯 JVM 单测。
 *
 * 锁定三件事：
 * 1) [resolvePrimary] 的短路顺序：`E1 > E2 > E15 > E12 > E13 > E3 > NORMAL`，
 *    特别是"空态(exact) > busy"与"busy > 空库"两个容易写反的边界；
 * 2) E3 用 **chunkCount** 判定（不是 spaces==None），能与"有数据但还没建向量空间"区分；
 * 3) [infoPills] 的 E4/E8/E14 组合与「未知（null）不展示」降级。
 */
class IntelligenceHubModelTest {

    // ── E1 / E2（缺模型：候选文件数区分） ───────────────────────────

    @Test
    fun e1_whenModelMissingAndNoCandidateFiles() {
        val primary = resolvePrimary(
            status(model = ModelStatus.Missing, modelFileCount = 0),
        )
        assertEquals(HubPrimary.E1, primary)
    }

    @Test
    fun e1_winsOverDownloading_priorityConflict() {
        // §4 显式优先级：E1（全新安装）排在 E12（下载中）之前。
        val primary = resolvePrimary(
            status(
                level = IntelligenceLevel.NOT_READY,
                busy = IntelligenceBusy.DOWNLOADING,
                busyPct = 42,
                busyLabel = "embeddinggemma-2-440m",
                model = ModelStatus.Missing,
                modelFileCount = 0,
            ),
        )
        assertEquals(HubPrimary.E1, primary)
    }

    @Test
    fun e2_whenModelMissingButCandidateFilesExist() {
        // 真实可达路径：候选文件存在但 resolve 失败（权限/IO）→ 模型仍为 Missing。
        val primary = resolvePrimary(
            status(model = ModelStatus.Missing, modelFileCount = 3),
        )
        assertEquals(HubPrimary.E2, primary)
    }

    @Test
    fun e2_notWhenCandidateFileCountUnknown() {
        // 读取失败 -1：既不是 E1 也不是 E2，退回普通结论，不伪装成"全新安装"。
        val primary = resolvePrimary(
            status(model = ModelStatus.Missing, modelFileCount = -1),
        )
        assertEquals(HubPrimary.NORMAL, primary)
    }

    // ── E15（模型损坏） ─────────────────────────────────────────────

    @Test
    fun e15_whenModelCorrupt() {
        assertEquals(
            HubPrimary.E15,
            resolvePrimary(
                status(
                    level = IntelligenceLevel.ERROR,
                    model = ModelStatus.Corrupt,
                    modelFileCount = 1,
                ),
            ),
        )
    }

    @Test
    fun e15_winsOverDownloading() {
        // 损坏优先于下载中：先重新下载损坏模型，进度行在此场景没有意义。
        assertEquals(
            HubPrimary.E15,
            resolvePrimary(
                status(
                    level = IntelligenceLevel.ERROR,
                    busy = IntelligenceBusy.DOWNLOADING,
                    busyPct = 10,
                    model = ModelStatus.Corrupt,
                    modelFileCount = 1,
                ),
            ),
        )
    }

    // ── E12（下载中）──
    @Test
    fun e12_whenDownloadingWithReadyModel() {
        val primary = resolvePrimary(
            status(busy = IntelligenceBusy.DOWNLOADING, busyPct = 42),
        )
        assertEquals(HubPrimary.E12, primary)
    }

    @Test
    fun e12_beatsE3_whenDownloadingAndKnowledgeBaseEmpty() {
        // busy 排在 E3 之前：进行中先报进度，不报"知识库还是空的"。
        val primary = resolvePrimary(
            status(busy = IntelligenceBusy.DOWNLOADING, busyPct = 5, chunkCount = 0),
        )
        assertEquals(HubPrimary.E12, primary)
    }

    // ── E13（同步中）──
    @Test
    fun e13_whenSyncing() {
        val primary = resolvePrimary(
            status(
                busy = IntelligenceBusy.SYNCING,
                busyPct = 30,
                busyDone = 30,
                busyTotal = 100,
            ),
        )
        assertEquals(HubPrimary.E13, primary)
    }

    @Test
    fun e13_beatsE3_whenSyncingAndKnowledgeBaseEmpty() {
        val primary = resolvePrimary(
            status(busy = IntelligenceBusy.SYNCING, busyDone = 0, busyTotal = 100, chunkCount = 0),
        )
        assertEquals(HubPrimary.E13, primary)
    }

    // ── E3（模型就绪、库为空） ──────────────────────────────────────

    @Test
    fun e3_whenModelReadyAndChunkCountZero() {
        assertEquals(
            HubPrimary.E3,
            resolvePrimary(status(model = ModelStatus.Ready("m"), chunkCount = 0)),
        )
    }

    @Test
    fun e3_notWhenChunkCountUnknown() {
        // chunk 数读取失败（-1）不能判"空库"。
        assertEquals(
            HubPrimary.NORMAL,
            resolvePrimary(status(chunkCount = -1)),
        )
    }

    @Test
    fun e3_notWhenSpacesEmptyButChunksExist() {
        // 与旧实现（spaces==None）的区别：有 chunk 但还没建向量空间 ≠ 空库。
        val primary = resolvePrimary(
            status(
                model = ModelStatus.Ready("m"),
                spaces = SpaceStatus.None,
                chunkCount = 5,
            ),
        )
        assertEquals(HubPrimary.NORMAL, primary)
    }

    // ── NORMAL ────────────────────────────────────────────────────

    @Test
    fun normal_whenReadyWithChunks() {
        assertEquals(
            HubPrimary.NORMAL,
            resolvePrimary(status(chunkCount = 128, modelFileCount = 1)),
        )
    }

    @Test
    fun normal_whenOnlyPendingItemsButNoEmptyState() {
        assertEquals(
            HubPrimary.NORMAL,
            resolvePrimary(
                status(
                    level = IntelligenceLevel.PARTIAL,
                    pending = listOf(PendingItem.EXTRACTION_FAILED),
                ),
            ),
        )
    }

    // ── infoPills（E4 / E8 / E14） ─────────────────────────────────

    @Test
    fun infoPills_emptyWhenAllUnknown() {
        // 读取失败（null）/未触发 → 不展示任何 pill（§10.3 降级）。
        val pills = infoPills(
            status(
                storeNotConfigured = false,
                sagEnabled = null,
                networkDisabled = null,
                answerConsentGranted = null,
            ),
        )
        assertTrue(pills.isEmpty())
    }

    @Test
    fun infoPills_e4StoreNotConfigured() {
        val pills = infoPills(status(storeNotConfigured = true))
        assertEquals(listOf(HubInfoPill.Kind.STORE_NOT_CONFIGURED), pills.map { it.kind })
    }

    @Test
    fun infoPills_e8BothSagDisabledAndNetworkDisabled() {
        // E8 的两个条件互相独立，可同时出现。
        val pills = infoPills(status(sagEnabled = false, networkDisabled = true))
        assertEquals(
            listOf(HubInfoPill.Kind.SAG_DISABLED, HubInfoPill.Kind.NETWORK_DISABLED),
            pills.map { it.kind },
        )
    }

    @Test
    fun infoPills_e8NotShownWhenSagEnabledAndNetworkAllowed() {
        val pills = infoPills(status(sagEnabled = true, networkDisabled = false))
        assertTrue(pills.isEmpty())
    }

    @Test
    fun infoPills_e14AnswerConsentRequired() {
        val pills = infoPills(status(answerConsentGranted = false))
        assertEquals(
            listOf(HubInfoPill.Kind.ANSWER_CONSENT_REQUIRED),
            pills.map { it.kind },
        )
    }

    @Test
    fun infoPills_orderMatchesSpec() {
        // 顺序固定：E4 → E8(SAG) → E8(禁网) → E14。
        val pills = infoPills(
            status(
                storeNotConfigured = true,
                sagEnabled = false,
                networkDisabled = true,
                answerConsentGranted = false,
            ),
        )
        assertEquals(
            listOf(
                HubInfoPill.Kind.STORE_NOT_CONFIGURED,
                HubInfoPill.Kind.SAG_DISABLED,
                HubInfoPill.Kind.NETWORK_DISABLED,
                HubInfoPill.Kind.ANSWER_CONSENT_REQUIRED,
            ),
            pills.map { it.kind },
        )
    }

    // ── 测试构造器 ─────────────────────────────────────────────────

    /** 默认值 = 「就绪、有 10 个 chunk、1 个候选模型文件、无附加 pill」。 */
    private fun status(
        level: IntelligenceLevel = IntelligenceLevel.READY,
        busy: IntelligenceBusy = IntelligenceBusy.NONE,
        busyPct: Int? = null,
        busyLabel: String? = null,
        busyDone: Int? = null,
        busyTotal: Int? = null,
        model: ModelStatus = ModelStatus.Ready("embeddinggemma-2-440m"),
        spaces: SpaceStatus = SpaceStatus.Ready(1),
        chunkCount: Int = 10,
        modelFileCount: Int = 1,
        storeNotConfigured: Boolean = false,
        sagEnabled: Boolean? = null,
        networkDisabled: Boolean? = null,
        answerConsentGranted: Boolean? = null,
        pending: List<PendingItem> = emptyList(),
    ): IntelligenceStatus = IntelligenceStatus(
        level = level,
        busy = busy,
        busyPct = busyPct,
        model = model,
        spaces = spaces,
        gpu = GpuStatus.AUTO,
        llm = LlmStatus.NotConfigured,
        pending = pending,
        chunkCount = chunkCount,
        modelFileCount = modelFileCount,
        storeNotConfigured = storeNotConfigured,
        sagEnabled = sagEnabled,
        networkDisabled = networkDisabled,
        answerConsentGranted = answerConsentGranted,
        busyLabel = busyLabel,
        busyDone = busyDone,
        busyTotal = busyTotal,
    )
    // ── E10「重试」可用性（SAG 不可用时改深链，避免空操作） ───────────

    private fun retryStatus(
        llm: LlmStatus = LlmStatus.Configured,
        sagEnabled: Boolean? = true,
        networkDisabled: Boolean? = false,
    ) = IntelligenceStatus(
        level = IntelligenceLevel.READY,
        busy = IntelligenceBusy.NONE,
        model = ModelStatus.Ready("m.litertlm"),
        spaces = SpaceStatus.Ready(1),
        gpu = GpuStatus.AUTO,
        llm = llm,
        pending = emptyList(),
        sagEnabled = sagEnabled,
        networkDisabled = networkDisabled,
    )

    @Test
    fun canRetryExtraction_requiresLlmSagAndNetwork() {
        assertTrue(canRetryExtraction(retryStatus()))
        assertFalse(canRetryExtraction(retryStatus(llm = LlmStatus.NotConfigured)))
        assertFalse(canRetryExtraction(retryStatus(sagEnabled = false)))
        assertFalse("SAG 状态未知时按不可用处理（不猜）", canRetryExtraction(retryStatus(sagEnabled = null)))
        assertFalse(canRetryExtraction(retryStatus(networkDisabled = true)))
        assertFalse("网络状态未知时不做空操作重试", canRetryExtraction(retryStatus(networkDisabled = null)))
    }

}

// ── 面板渲染 + 就地动作接线（Robolectric Compose 冒烟） ────────────────

/**
 * 面板渲染冒烟测试（JVM + Robolectric + Compose UI Test）。
 *
 * 覆盖 §12.1「§4 的 E1–E15 全部可达」里最关键的六条空态/busy 行，
 * 以及 §6.4 允许的三类"就地写"动作是否真的接在面板按钮上（点击回调被触发、不跳设置页）。
 *
 * 只断言"可见文案 + 回调触发"，不做像素比对：视觉细节由 @Preview 与真机走查负责。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh")
class IntelligenceHubSheetRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        status: IntelligenceStatus,
        onOpenSection: (IntelligenceSection?) -> Unit = {},
        onOpenImport: () -> Unit = {},
        onRetryExtraction: () -> Unit = {},
        onRepairIndex: () -> Unit = {},
        onCancelSync: () -> Unit = {},
    ) {
        composeRule.setContent {
            ZhiBoxTheme(darkTheme = false) {
                IntelligenceHubSheet(
                    status = status,
                    onDismissRequest = {},
                    onOpenSection = onOpenSection,
                    // 与 HomeScreen 一致：counts 来自 status 的两个计数字段。
                    counts = IntelligenceHubCounts(
                        legacySpaces = status.legacySpaceCount,
                        extractionFailed = status.extractionFailedCount,
                    ),
                    onOpenImport = onOpenImport,
                    onRetryExtraction = onRetryExtraction,
                    onRepairIndex = onRepairIndex,
                    onCancelSync = onCancelSync,
                )
            }
        }
    }

    @Test
    fun e1_rendersEmptyStateAndDownloadCta() {
        render(hubStatus(level = IntelligenceLevel.NOT_READY, model = ModelStatus.Missing, modelFileCount = 0))
        composeRule.onNodeWithText("还没有可用的本地模型").assertIsDisplayed()
        composeRule.onNodeWithText("下载模型后即可导入文档并离线检索").assertIsDisplayed()
        composeRule.onNodeWithText("下载模型").assertIsDisplayed()
    }

    @Test
    fun e2_rendersModelsFoundNotEnabled() {
        render(hubStatus(level = IntelligenceLevel.NOT_READY, model = ModelStatus.Missing, modelFileCount = 3))
        composeRule.onNodeWithText("已检测到 3 个模型文件，尚未启用").assertIsDisplayed()
        composeRule.onNodeWithText("去启用").assertIsDisplayed()
    }

    @Test
    fun e15_rendersModelCorruptWithRedownloadCta() {
        render(
            hubStatus(
                level = IntelligenceLevel.ERROR,
                model = ModelStatus.Corrupt,
                modelFileCount = 1,
            ),
        )
        composeRule.onNodeWithText("模型文件需要重新下载").assertIsDisplayed()
        composeRule.onNodeWithText("重新下载").assertIsDisplayed()
    }

    @Test
    fun e12_rendersDownloadingProgress() {
        render(
            hubStatus(
                busy = IntelligenceBusy.DOWNLOADING,
                busyPct = 42,
                busyLabel = "embeddinggemma-2-440m",
                chunkCount = 128,
            ),
        )
        // 标题 = 模型名；说明 = settings_downloading 的百分比文案
        composeRule.onNodeWithText("embeddinggemma-2-440m").assertIsDisplayed()
        composeRule.onNodeWithText("下载中 42%").assertIsDisplayed()
    }

    @Test
    fun e13_rendersSyncingProgressAndStopIsWiredInPlace() {
        var cancelled = false
        render(
            hubStatus(
                busy = IntelligenceBusy.SYNCING,
                busyPct = 30,
                busyDone = 30,
                busyTotal = 100,
                chunkCount = 128,
            ),
            onCancelSync = { cancelled = true },
        )
        composeRule.onNodeWithText("重嵌入中 30/100").assertIsDisplayed()
        composeRule.onNodeWithText("停止同步").performClick()
        composeRule.waitForIdle()
        assertTrue("E13「停止同步」应就地回调 onCancelSync", cancelled)
    }

    @Test
    fun e3_rendersEmptyKbWithImportCta() {
        render(
            hubStatus(
                model = ModelStatus.Ready("embeddinggemma-2-440m"),
                chunkCount = 0,
            ),
        )
        composeRule.onNodeWithText("模型就绪 · 知识库还是空的").assertIsDisplayed()
        composeRule.onNodeWithText("去导入").assertIsDisplayed()
    }

    @Test
    fun e10_pendingRowRetryIsWiredInPlace() {
        var retried = false
        render(
            hubStatus(
                level = IntelligenceLevel.PARTIAL,
                pending = listOf(PendingItem.EXTRACTION_FAILED),
                extractionFailedCount = 12,
                chunkCount = 128,
                llm = LlmStatus.Configured,
                sagEnabled = true,
                networkDisabled = false,
            ),
            onRetryExtraction = { retried = true },
        )
        // 行内动作就地执行：整行可点（contentDescription = "行文案，动作"），不跳设置页。
        composeRule.onNodeWithContentDescription("12 个分块抽取失败，重试").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue("E10「重试」应就地回调 onRetryExtraction", retried)
    }

    @Test
    fun e10_whenSagUnavailable_actionIsConfigureDeepLink() {
        var retried = false
        var opened: IntelligenceSection? = null
        render(
            hubStatus(
                level = IntelligenceLevel.PARTIAL,
                pending = listOf(PendingItem.EXTRACTION_FAILED),
                extractionFailedCount = 12,
                chunkCount = 128,
                // 默认 LlmStatus.NotConfigured → SagAvailability=false：重试是空操作，
                // 面板应显示「去配置」并深链 LLM 配置，而不是让用户点了没反应。
            ),
            onOpenSection = { opened = it },
            onRetryExtraction = { retried = true },
        )
        composeRule.onNodeWithContentDescription("12 个分块抽取失败，去配置")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("SAG 不可用时不应就地重试", false, retried)
        assertEquals(IntelligenceSection.Llm, opened)
    }

    @Test
    fun e11_pendingRowRepairIsWiredInPlace() {
        var repaired = false
        render(
            hubStatus(
                level = IntelligenceLevel.PARTIAL,
                pending = listOf(PendingItem.INDEX_UNHEALTHY),
                chunkCount = 128,
            ),
            onRepairIndex = { repaired = true },
        )
        // 行内文案被 clearAndSetSemantics 合并成整行 contentDescription「行文案，动作」；
        // 待处理区在快捷动作之后，面板首屏（70% 高）可能不在视口内：先滚动再点。
        composeRule
            .onNodeWithContentDescription("索引可能有孤儿行，修复")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertTrue("E11「修复」应就地回调 onRepairIndex", repaired)
    }

    @Test
    fun infoPills_rendersE8SagAndNetwork() {
        render(
            hubStatus(
                sagEnabled = false,
                networkDisabled = true,
                chunkCount = 128,
            ),
        )
        composeRule.onNodeWithText("网络已禁用").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "SAG 抽取不可用：请先在「SAG 增强 / LLM」配置 LLM、开启「SAG 增强」并关闭「禁止网络」。",
            )
            .assertIsDisplayed()
    }

    /** 渲染用构造器：只暴露本类需要的字段，其余给"正常就绪"默认值。 */
    private fun hubStatus(
        level: IntelligenceLevel = IntelligenceLevel.READY,
        busy: IntelligenceBusy = IntelligenceBusy.NONE,
        busyPct: Int? = null,
        busyLabel: String? = null,
        busyDone: Int? = null,
        busyTotal: Int? = null,
        model: ModelStatus = ModelStatus.Ready("embeddinggemma-2-440m"),
        chunkCount: Int = 128,
        modelFileCount: Int = 1,
        sagEnabled: Boolean? = null,
        networkDisabled: Boolean? = null,
        llm: LlmStatus = LlmStatus.NotConfigured,
        extractionFailedCount: Int = 0,
        pending: List<PendingItem> = emptyList(),
    ): IntelligenceStatus = IntelligenceStatus(
        level = level,
        busy = busy,
        busyPct = busyPct,
        model = model,
        spaces = SpaceStatus.Ready(2),
        gpu = GpuStatus.AUTO,
        llm = llm,
        pending = pending,
        extractionFailedCount = extractionFailedCount,
        chunkCount = chunkCount,
        modelFileCount = modelFileCount,
        sagEnabled = sagEnabled,
        networkDisabled = networkDisabled,
        busyLabel = busyLabel,
        busyDone = busyDone,
        busyTotal = busyTotal,
    )
}
