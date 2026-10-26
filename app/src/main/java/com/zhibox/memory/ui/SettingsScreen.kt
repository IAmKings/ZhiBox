package com.zhibox.memory.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.model.SharedModelStore
import com.zhibox.memory.core.sag.SagBudgetTier
import com.zhibox.memory.ui.theme.ZhiBoxRadius
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import com.zhibox.memory.ui.theme.zhiBoxColors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置屏（P1-8 + B3-3/B3-5/B3-7/B3-14；设置页简化 2026-10）：
 * 默认进入「简单首页」（4 张主卡 + 高级入口），模型管理/资料库/联网问答/隐私与关于/高级与诊断
 * 为屏内二级页（与 SearchScreen 的证据详情页同构：同一路由 + 页面状态切换），MainActivity
 * 仍只有一条 `"settings"` 路由。原 17 项能力全部在「高级与诊断」页可达，深链先到高级页再落位。
 *
 * UI 拆为无状态 [SettingsPageContent] + [SettingsHomeContent] + [SettingsActions]（可 @Preview 渲染）；
 * SAF launcher、二次确认对话框与页面状态机留在有状态的 [SettingsScreen]。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    initialSection: IntelligenceSection? = null,
    /** 简单首页「导入资料」→ 导入屏（MainActivity 负责路由）。 */
    onOpenImport: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // R2（P2-8）：进场刷新——VM 常驻 Activity，导入/删源/切模型后再进设置页不再展示陈旧计数
    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
    }
    // 简单首页需要展示「联网问答服务是否已配置」；每进程仅一次 Keystore 解密（VM 内部缓存），
    // 替代原「滚到 LLM 段才解密」——高级页的 LLM 卡仍保留同一个幂等调用。
    LaunchedEffect(Unit) {
        viewModel.ensureLlmLoaded()
    }

    // ---- 设置页简化：屏内页面状态机（纯决策见 SettingsPageModel.kt） ----
    // 注意：rememberSaveable 的初值只读一次 initialSection；不能用它当 input key——
    // MainActivity 的 settingsSection 是 remember（非 saveable），旋转后会变 null，
    // 作为 key 会导致已保存的页面状态被丢弃、用户被踢回首页。
    var page by rememberSaveable {
        mutableStateOf(
            if (initialSection != null) SettingsPage.ADVANCED else SettingsPage.HOME,
        )
    }
    // 深链只消费一次：高级页落位后回调清零，返回/再次进入不重复滚动高亮。
    var deepLinkTarget by remember { mutableStateOf(initialSection) }
    // 深链进入的高级页返回时直接退出设置（旋转后仍保留该语义）。
    var enteredViaDeepLink by rememberSaveable { mutableStateOf(initialSection != null) }
    // 高级页「显示技术细节」开关：默认关，仅本次页面状态（不持久化）。
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    // 切换模型二次确认（切换 = 重建索引，见 SettingsViewModel.setPreference）。
    var pendingPreference by remember { mutableStateOf<String?>(null) }

    var pendingDeleteModel by remember { mutableStateOf<File?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var importDropGpu by remember { mutableStateOf(true) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var pendingClearLlmKey by remember { mutableStateOf(false) }
    var showAnswerConsentDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::previewImport) }
    // B15.2：抽取质量脱敏 JSON 导出（SAF CreateDocument）
    val qualityExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportExtractionQuality) }
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::setTreeUri) }

    // 供 actions remember 复用的"最新值"引用：lambda 在调用时读取，避免捕获旧状态
    val downloadingNow by rememberUpdatedState(state.downloading)
    val storeStateNow by rememberUpdatedState(state.storeState)
    val answerConsentNow by rememberUpdatedState(state.answerNetworkConsent)
    val preferenceNow by rememberUpdatedState(state.preference)
    val chunkCountNow by rememberUpdatedState(state.chunkCount)
    val onBackNow by rememberUpdatedState(onBack)

    fun attemptExit() {
        if (downloadingNow) showLeaveDialog = true else onBackNow()
    }

    // 单一返回入口：子页回首页；首页（或深链直接进入的高级页）退出设置。
    // 下载中离开仍走 attemptExit 的确认对话框（任务在 ViewModel 中继续）。
    fun navigateUp() {
        when (settingsBackAction(page, enteredViaDeepLink)) {
            SettingsBackAction.EXIT_SETTINGS -> attemptExit()
            SettingsBackAction.TO_HOME -> page = SettingsPage.HOME
        }
    }

    BackHandler { navigateUp() }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.settings_leave_title)) },
            text = { Text(stringResource(R.string.settings_leave_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    onBack()
                }) { Text(stringResource(R.string.settings_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.settings_leave_stay))
                }
            },
        )
    }

    state.importPreview?.let { pv ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportPreview,
            title = { Text(stringResource(R.string.settings_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // B12：备份格式与 v3 SAG 数据概览（v1/v2 包计数为 0）
                    Text(
                        stringResource(R.string.settings_import_format_version, pv.formatVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.settings_import_sag_counts,
                            pv.eventCount,
                            pv.entityCount,
                            pv.eventEntityCount,
                            pv.sagVectorCount,
                            pv.extractionJobCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pv.formatVersion < 3) {
                        Text(
                            stringResource(R.string.settings_import_legacy_sag_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } else if (pv.eventCount == 0 && pv.extractionJobCount == 0) {
                        Text(
                            stringResource(R.string.settings_import_sag_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_import_spaces_requirements),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    pv.spaces.forEach { sp ->
                        val mark = stringResource(
                            when {
                                sp.present -> R.string.settings_import_space_ok
                                sp.catalogDownloadable -> R.string.settings_import_space_need_download
                                else -> R.string.settings_import_space_missing
                            },
                        )
                        Text(
                            stringResource(
                                R.string.settings_import_space_line,
                                sp.modelId,
                                sp.accelerator,
                                mark,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (pv.blocked) {
                        Text(
                            stringResource(R.string.settings_import_blocked),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (pv.incompatibleGpu.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = importDropGpu,
                                onCheckedChange = { importDropGpu = it },
                            )
                            Text(
                                stringResource(R.string.settings_import_drop_gpu),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.settings_import_replace_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                if (!pv.blocked) {
                    TextButton(onClick = {
                        viewModel.confirmImport(importDropGpu && pv.incompatibleGpu.isNotEmpty())
                    }) { Text(stringResource(R.string.settings_import_confirm)) }
                } else {
                    TextButton(onClick = viewModel::dismissImportPreview) {
                        Text(stringResource(R.string.settings_import_got_it))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissImportPreview) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text(stringResource(R.string.settings_clear_all_title)) },
            text = { Text(stringResource(R.string.settings_clear_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearAll = false
                    viewModel.clearShared()
                }) { Text(stringResource(R.string.settings_clear_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingDeleteModel?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModel = null },
            title = { Text(stringResource(R.string.settings_delete_model_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_delete_model_message,
                        target.name,
                        PathPreferences.pathLabel(target.name),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteModel = null
                    viewModel.deleteModel(target)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteModel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (pendingClearLlmKey) {
        AlertDialog(
            onDismissRequest = { pendingClearLlmKey = false },
            title = { Text(stringResource(R.string.settings_llm_clear_key_title)) },
            text = { Text(stringResource(R.string.settings_llm_clear_key_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearLlmKey = false
                    viewModel.clearLlmKey()
                }) { Text(stringResource(R.string.settings_llm_clear_key)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearLlmKey = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // B11：首次开启「问答生成」前必须阅读并同意联网隐私说明
    if (showAnswerConsentDialog) {
        AlertDialog(
            onDismissRequest = { showAnswerConsentDialog = false },
            title = { Text(stringResource(R.string.chat_consent_title)) },
            text = { Text(stringResource(R.string.chat_consent_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAnswerConsentDialog = false
                    viewModel.setAnswerNetworkConsent(true)
                    viewModel.setAnswerEnabled(true)
                }) { Text(stringResource(R.string.chat_consent_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showAnswerConsentDialog = false }) {
                    Text(stringResource(R.string.chat_consent_cancel))
                }
            },
        )
    }

    // 切换模型 = 切换向量空间并重建索引（SettingsViewModel.setPreference 会入队重嵌）；
    // 有已索引数据时先确认，避免用户误触后触发长时间后台任务。
    pendingPreference?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingPreference = null },
            title = { Text(stringResource(R.string.settings_model_switch_title)) },
            text = { Text(stringResource(R.string.settings_model_switch_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPreference = null
                    viewModel.setPreference(target)
                }) { Text(stringResource(R.string.settings_model_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreference = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 设置页 section 很多：actions 若每次重组都新建（普通 class + 新 lambda），
    // 会让所有 section 无法跳过重组。这里只构造一次，内部读取上面的 *Now 引用。
    val actions = remember(viewModel) {
        SettingsActions(
        onBack = ::attemptExit,
        setPreference = viewModel::setPreference,
        requestPreference = { target ->
            // 已选中的档位不弹窗；有已索引数据时切换模型会触发重建索引，先确认；
            // 空库/无索引直接生效。
            when {
                target == preferenceNow -> Unit
                chunkCountNow > 0 -> pendingPreference = target
                else -> viewModel.setPreference(target)
            }
        },
        setGpuMode = viewModel::setGpuMode,
        setPerfMode = viewModel::setPerfMode,
        chooseDirectory = { dirPicker.launch(null) },
        retryRefresh = viewModel::refresh,
        download = { entry ->
            if (storeStateNow != SharedModelStore.State.CONNECTED) {
                dirPicker.launch(null)
            } else {
                viewModel.startDownload(entry)
            }
        },
        enableShared = viewModel::enableShared,
        requestClearShared = { pendingClearAll = true },
        requestDeleteModel = { pendingDeleteModel = it },
        exportBackup = { exportLauncher.launch("zhibox-knowledge-base.zip") },
        importBackup = { importLauncher.launch(arrayOf("application/zip")) },
        restoreBackup = viewModel::previewLatestSnapshot,
        repairIndex = viewModel::repairIndex,
        reembedNow = viewModel::reembedNow,
        cancelReembed = viewModel::cancelReembed,
        saveLlmConfig = { baseUrl, model, apiKey, timeoutSeconds ->
            viewModel.saveLlmConfig(baseUrl, model, apiKey, timeoutSeconds)
        },
        testLlmConnection = viewModel::testLlmConnection,
        clearLlmKey = { pendingClearLlmKey = true },
        setSagEnabled = viewModel::setSagEnabled,
        setAnswerEnabled = { enabled ->
            if (enabled && !answerConsentNow) {
                showAnswerConsentDialog = true
            } else {
                viewModel.setAnswerEnabled(enabled)
            }
        },
        setAnswerNetworkConsent = viewModel::setAnswerNetworkConsent,
        setBudgetTier = viewModel::setBudgetTier,
        setPdfOcrEnabled = viewModel::setPdfOcrEnabled,
        setNetworkDisabled = viewModel::setNetworkDisabled,
        backfillExtraction = viewModel::backfillExtraction,
        retryFailedExtraction = viewModel::retryFailedExtraction,
        setExtractionParallelism = viewModel::setExtractionParallelism,
        exportTrace = viewModel::exportTraceJson,
        refreshQuality = viewModel::refreshExtractionQuality,
        exportQualityJson = { qualityExportLauncher.launch(QUALITY_EXPORT_FILE_NAME) },
        ensureLlmLoaded = viewModel::ensureLlmLoaded,
        )
    }
    when (page) {
        SettingsPage.HOME -> SettingsHomeContent(
            state = state,
            actions = actions,
            onOpenPage = { page = it },
            onOpenImport = onOpenImport,
            onBack = ::attemptExit,
        )
        else -> SettingsPageContent(
            page = page,
            state = state,
            actions = actions,
            onBack = ::navigateUp,
            deepLinkTarget = deepLinkTarget,
            onDeepLinkConsumed = { deepLinkTarget = null },
            showTechnical = showTechnical,
            onShowTechnicalChange = { showTechnical = it },
            onOpenPage = { page = it },
            onOpenImport = onOpenImport,
        )
    }
}

/** 设置屏动作集合：无状态内容组件通过它回调有状态包装层（便于 @Preview 传默认空实现）。 */
class SettingsActions(
    val onBack: () -> Unit = {},
    val setPreference: (String) -> Unit = {},
    /** 切模型前请求二次确认（shell 弹窗，确认后才写偏好）。 */
    val requestPreference: (String) -> Unit = {},
    val setGpuMode: (String) -> Unit = {},
    val setPerfMode: (String) -> Unit = {},
    val chooseDirectory: () -> Unit = {},
    val retryRefresh: () -> Unit = {},
    val download: (ModelCatalog.Entry) -> Unit = {},
    val enableShared: (String) -> Unit = {},
    val requestClearShared: () -> Unit = {},
    val requestDeleteModel: (File) -> Unit = {},
    val exportBackup: () -> Unit = {},
    val importBackup: () -> Unit = {},
    val restoreBackup: () -> Unit = {},
    val repairIndex: () -> Unit = {},
    val reembedNow: () -> Unit = {},
    val cancelReembed: () -> Unit = {},
    // B8：SAG 增强 / LLM
    val saveLlmConfig: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    val testLlmConnection: () -> Unit = {},
    val clearLlmKey: () -> Unit = {},
    val setSagEnabled: (Boolean) -> Unit = {},
    val setAnswerEnabled: (Boolean) -> Unit = {},
    val setAnswerNetworkConsent: (Boolean) -> Unit = {},
    val setNetworkDisabled: (Boolean) -> Unit = {},
    // B9：SAG 两阶段抽取
    val backfillExtraction: () -> Unit = {},
    val retryFailedExtraction: () -> Unit = {},
    // B12：抽取并发度
    val setExtractionParallelism: (Int) -> Unit = {},
    // B12：trace 诊断导出
    val exportTrace: () -> Unit = {},
    // B15.2：抽取质量诊断
    val refreshQuality: () -> Unit = {},
    val exportQualityJson: () -> Unit = {},
    /** P1：LLM 段可见时按需加载配置（Keystore 解密）。 */
    val ensureLlmLoaded: () -> Unit = {},
    // B12：检索预算档位
    val setBudgetTier: (SagBudgetTier) -> Unit = {},
    // P1.1：扫描件 OCR 开关
    val setPdfOcrEnabled: (Boolean) -> Unit = {},
)

/**
 * 按页面门控的 LazyColumn item（设置页简化）。
 *
 * 只替换 `item(key = ...) {` 的声明行、卡片内容一行不动：高级页 `visible = true` 时 emit
 * 顺序与历史完全一致（深链下标不变），聚焦页按 [SettingsPage.sectionKeys] emit 子集。
 */
private inline fun LazyListScope.settingsItem(
    visible: Boolean,
    key: String,
    crossinline content: @Composable () -> Unit,
) {
    if (visible) {
        item(key = key) { content() }
    }
}

/** 设置页分组标题（智能 / 数据 / 诊断 / 隐私与关于）。 */
@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.zhiBoxColors.inkMeta,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** 设置页分组内二级小标题（模型与算力 / 问答与增强 / 检索质量）。 */
@Composable
private fun SettingsGroupSubHeader(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.zhiBoxColors.inkMeta,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 0.dp),
    )
}

/**
 * 无状态设置二级页内容：模型管理 / 资料库 / 联网问答 / 隐私与关于 / 高级与诊断。
 *
 * - 按 [page] 从现有 17 个 section 里 emit 子集（见 [SettingsPage.sectionKeys]），
 *   **同一张卡只有一份 UI 代码**，不存在两份实现漂移；
 * - 高级页（[SettingsPage.ADVANCED]）的 emit 顺序与历史完全一致 → `SettingsItemOrder`
 *   深链下标与 `SettingsItemOrderTest` 不变；
 * - 深链 [deepLinkTarget] 只在高级页消费一次，落位后回调 [onDeepLinkConsumed]；
 * - 「显示技术细节」开关在 LazyColumn 之外，不改变任何 item 下标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPageContent(
    page: SettingsPage,
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit = {},
    deepLinkTarget: IntelligenceSection? = null,
    onDeepLinkConsumed: () -> Unit = {},
    showTechnical: Boolean = false,
    onShowTechnicalChange: (Boolean) -> Unit = {},
    onOpenPage: (SettingsPage) -> Unit = {},
    onOpenImport: () -> Unit = {},
) {
    ZhiBoxScreen(
        title = stringResource(page.titleRes),
        onBack = onBack,
    ) { padding ->
        // P0：17 个 section 一次性组合 + 全内容测量是设置页卡顿主因（实测单帧 compose+layout 375–465ms）。
        // 改 LazyColumn：只组合可见段，其余滚动到再组合。
        val listState = rememberLazyListState()
        // 深链：等数据加载完成 + 首帧布局后再滚动，避免闪跳/滚到错误位置。
        val visibility = SettingsItemVisibility(
            loading = state.loading,
            hasError = state.error != null,
            hasLegacySpacesError = state.legacySpacesError != null,
            hasLegacySpaces = state.legacySpaces.isNotEmpty(),
        )
        var deepLinkApplied by remember { mutableStateOf(false) }
        // 规格 §6.5：深链落位后对目标 section 高亮 1.2s；仅深链进入触发一次（普通进入不高亮）。
        var deepLinkHighlightKey by remember { mutableStateOf<String?>(null) }
        var deepLinkHighlightVisible by remember { mutableStateOf(false) }
        val animationsEnabled = rememberAnimationsEnabled()
        LaunchedEffect(deepLinkTarget, state.loading) {
            val section = deepLinkTarget
            if (section != null && !deepLinkApplied && !state.loading) {
                withFrameNanos { }
                listState.scrollToItem(
                    SettingsItemOrder.settingsItemIndex(section, visibility),
                )
                deepLinkApplied = true
                // 落位后布局信息可能滞后一帧：等目标 item 真正进入可见区再点亮；超时拿不到
                // （极端布局 / 目标不在可见区）就跳过高亮——不影响滚动结果，也不闪。
                val appeared = withTimeoutOrNull(DEEP_LINK_HIGHLIGHT_LAYOUT_WAIT_MS) {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.any { it.key == section.itemKey }
                    }.first { it }
                }
                if (appeared == true) {
                    // 两个状态同帧写入：覆盖层首次组合即取 alpha=1，避免多一次 300ms 淡入。
                    deepLinkHighlightVisible = true
                    deepLinkHighlightKey = section.itemKey
                }
                // 消费一次：host 清空 target 后本页不再重复滚动；返回/再进高级页也不重复。
                onDeepLinkConsumed()
            }
        }
        // 高亮生命周期：亮 1.2s → 淡出 300ms（系统关闭动画时直接移除）→ 撤销覆盖层。
        LaunchedEffect(deepLinkHighlightKey) {
            if (deepLinkHighlightKey == null) return@LaunchedEffect
            delay(DEEP_LINK_HIGHLIGHT_HOLD_MS)
            deepLinkHighlightVisible = false
            if (animationsEnabled) {
                delay(DEEP_LINK_HIGHLIGHT_FADE_MS.toLong())
            }
            deepLinkHighlightKey = null
        }
        // 覆盖块必须与 LazyColumn 同坐标系：padding 放在外层 Column/Box，覆盖块作为 Box 的
        // 第二个子节点叠在列表之上（无 pointerInput，不拦截触摸）。技术细节行在列表外，
        // 因此不参与下标计算。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (page == SettingsPage.ADVANCED) {
                SettingsTechnicalDetailsRow(
                    checked = showTechnical,
                    onCheckedChange = onShowTechnicalChange,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = SettingsListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                // B3-5：统一加载/错误组件
                if (state.loading) {
                    item(key = "loading") {
                        LoadingState(message = stringResource(R.string.settings_loading))

                    }
                }
                state.error?.let {
                    item(key = "error") {
                        ErrorState(
                            message = it,
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = actions.retryRefresh,
                        )

                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "header-intelligence") {
                    SettingsGroupHeader(stringResource(R.string.settings_group_intelligence))
                }

                settingsItem(page == SettingsPage.ADVANCED, "sub-models") {
                    SettingsGroupSubHeader(stringResource(R.string.settings_sub_models))
                }


                // ---- 嵌入路径 ----
                if (page == SettingsPage.MODELS) {
                    item(key = "models-hero") {
                        SettingsModelsHero(
                            state = state,
                            actions = actions,
                        )
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Embedding.itemKey),
                    IntelligenceSection.Embedding.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_embedding),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                state.model?.let { fileName ->
                                    if (showTechnical) {
                                        stringResource(R.string.settings_current_model, fileName)
                                    } else {
                                        stringResource(settingsModelTier(fileName).labelRes())
                                    }
                                } ?: stringResource(
                                    if (showTechnical) {
                                        R.string.settings_no_model
                                    } else {
                                        R.string.settings_no_model_simple
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // B7 审查修复：新增 D·740M 后 4 个 chip 在窄屏会溢出，改 FlowRow 自动换行
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    PathPreferences.AUTO to stringResource(R.string.settings_path_auto),
                                    PathPreferences.A to stringResource(R.string.settings_path_a),
                                    PathPreferences.B to stringResource(R.string.settings_path_b),
                                    PathPreferences.D to stringResource(R.string.settings_path_d),
                                ).forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.preference == value,
                                        onClick = { actions.requestPreference(value) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_path_hint),
                            )
                        }
                    }
                }

                // ---- 模型商店（SOC 感知推荐 + SAF 共享目录）----
                settingsItem(
                    page.shows(IntelligenceSection.ModelStore.itemKey),
                    IntelligenceSection.ModelStore.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_store),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val socText = state.socTag.ifBlank {
                                stringResource(R.string.settings_soc_unknown)
                            }
                            Text(
                                stringResource(R.string.settings_soc_line, socText),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    ModelCatalog.PERF_BALANCED to stringResource(R.string.settings_perf_balanced),
                                    ModelCatalog.PERF_FAST to stringResource(R.string.settings_perf_fast),
                                    ModelCatalog.PERF_LIGHT to stringResource(R.string.settings_perf_light),
                                ).forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.perfPref == value,
                                        onClick = { actions.setPerfMode(value) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            ModelCatalog.recommend(state.perfPref, state.socTag)?.let { entry ->
                                val inLibrary = state.sharedNames.contains(entry.fileName) ||
                                    state.models.any { it.file.name == entry.fileName }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.settings_recommended,
                                            entry.label,
                                            entry.sizeBytes / 1024 / 1024,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        ModelCatalog.recommendReason(entry, state.socTag),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (state.downloading && state.downloadEntry == entry && state.downloadPct >= 0) {
                                        LinearProgressIndicator(
                                            progress = { state.downloadPct / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            stringResource(R.string.settings_downloading, state.downloadPct),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else if (!inLibrary) {
                                        Button(
                                            onClick = { actions.download(entry) },
                                            enabled = !state.downloading,
                                        ) { Text(stringResource(R.string.settings_download)) }
                                    } else {
                                        Text(
                                            stringResource(R.string.settings_in_library),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.ModelStoreShared.itemKey),
                    IntelligenceSection.ModelStoreShared.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_store_shared_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when (state.storeState) {
                                    SharedModelStore.State.CONNECTED ->
                                        stringResource(R.string.settings_store_connected, state.sharedNames.size)
                                    SharedModelStore.State.PERMISSION_LOST ->
                                        stringResource(R.string.settings_store_permission_lost)
                                    SharedModelStore.State.NOT_CONFIGURED ->
                                        stringResource(R.string.settings_store_not_configured)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = actions.chooseDirectory) {
                                    Text(
                                        stringResource(
                                            if (state.storeState == SharedModelStore.State.CONNECTED) {
                                                R.string.settings_store_change_dir
                                            } else {
                                                R.string.settings_store_choose_dir
                                            },
                                        ),
                                    )
                                }
                                if (state.sharedNames.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = actions.requestClearShared,
                                        enabled = !state.downloading,
                                    ) {
                                        Text(
                                            stringResource(R.string.settings_clear_shared),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            if (state.sharedNames.isNotEmpty()) {
                                if (showTechnical) {
                                    Text(
                                        stringResource(
                                            R.string.settings_shared_list,
                                            state.sharedNames.joinToString("、") {
                                                it.removeSuffix(".litertlm")
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            R.string.settings_shared_count_simple,
                                            state.sharedNames.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            val linkable = state.sharedNames.filter { name ->
                                state.models.none { it.file.name == name }
                            }
                            if (linkable.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.settings_enable_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                linkable.forEach { name ->
                                    val shownName = if (showTechnical) {
                                        name.removeSuffix(".litertlm")
                                    } else {
                                        stringResource(settingsModelTier(name).labelRes())
                                    }
                                    OutlinedButton(onClick = { actions.enableShared(name) }) {
                                        Text(
                                            stringResource(
                                                R.string.settings_enable,
                                                shownName,
                                            ),
                                        )
                                    }
                                }
                            }
                            state.message?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // ---- GPU 加速 ----
                settingsItem(
                    page.shows(IntelligenceSection.Gpu.itemKey),
                    IntelligenceSection.Gpu.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_gpu),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    PathPreferences.GPU_AUTO to stringResource(R.string.settings_gpu_auto),
                                    PathPreferences.GPU_OFF to stringResource(R.string.settings_gpu_cpu),
                                    PathPreferences.GPU_ON to stringResource(R.string.settings_gpu_gpu),
                                ).forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.gpuPref == value,
                                        onClick = { actions.setGpuMode(value) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_gpu_hint),
                            )
                        }
                    }
                }

                // ---- 模型文件 ----
                settingsItem(
                    page.shows(IntelligenceSection.ModelFiles.itemKey),
                    IntelligenceSection.ModelFiles.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_models, state.models.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.models.isEmpty()) {
                                EmptyState(
                                    title = stringResource(
                                        if (showTechnical) {
                                            R.string.settings_models_empty
                                        } else {
                                            R.string.settings_models_empty_simple
                                        },
                                    ),
                                )
                            }
                            state.models.forEach { item ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (showTechnical) {
                                                item.file.name
                                            } else {
                                                stringResource(
                                                    settingsModelTier(item.file.name).labelRes(),
                                                )
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            if (showTechnical) {
                                                stringResource(
                                                    R.string.settings_model_size_line,
                                                    item.label,
                                                    item.sizeBytes / 1024 / 1024,
                                                )
                                            } else {
                                                stringResource(
                                                    R.string.settings_model_size_only,
                                                    item.sizeBytes / 1024 / 1024,
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    OutlinedButton(onClick = { actions.requestDeleteModel(item.file) }) {
                                        Text(stringResource(R.string.action_delete))
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 向量空间注册表（维度来自 embedding_meta.dimensions，P1-6）----
                settingsItem(
                    page.shows(IntelligenceSection.VectorSpace.itemKey),
                    IntelligenceSection.VectorSpace.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.settings_section_spaces),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.spaces.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_spaces_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (showTechnical) {
                                state.spaces.forEach { space ->
                                    Text(
                                        stringResource(
                                            R.string.settings_space_line,
                                            space.id,
                                            space.count,
                                            space.dimensions,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(
                                        R.string.settings_spaces_count_simple,
                                        state.spaces.size,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "sub-ask") {
                    SettingsGroupSubHeader(stringResource(R.string.settings_sub_ask))
                }


                // ---- B8：SAG 增强 / LLM（骨架：配置/测试/开关；未配置时离线检索零回归） ----
                if (page == SettingsPage.NETWORK) {
                    item(key = "network-hero") {
                        SettingsNetworkPageHero(
                            state = state,
                            actions = actions,
                            onOpenAdvanced = { onOpenPage(SettingsPage.ADVANCED) },
                        )
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Llm.itemKey),
                    IntelligenceSection.Llm.itemKey,
                ) {
                    // P1：滚到该段才解密 Keystore 读 LLM 配置（进场不做）
                    LaunchedEffect(Unit) { actions.ensureLlmLoaded() }
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_llm),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val llm = state.llm
                            if (llm == null) {
                                Text(
                                    stringResource(R.string.settings_llm_disabled_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    llm.maskedKey?.let { stringResource(R.string.settings_llm_masked_key, it) }
                                        ?: stringResource(R.string.settings_llm_no_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (llm.maskedKey != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }

                            state.llmError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            // 表单本地状态：baseUrl/model/timeout 可跨重建保留；apiKey 不用 rememberSaveable
                            //（避免明文密钥进入 saved instance state）。
                            var llmBaseUrl by rememberSaveable { mutableStateOf(llm?.baseUrl.orEmpty()) }
                            var llmModel by rememberSaveable { mutableStateOf(llm?.model.orEmpty()) }
                            var llmTimeout by rememberSaveable {
                                mutableStateOf((llm?.timeoutSeconds ?: 30).toString())
                            }
                            var llmApiKey by remember { mutableStateOf("") }
                            var llmFormMessage by remember { mutableStateOf<String?>(null) }
                            val invalidConfigMessage = stringResource(R.string.settings_llm_invalid_config)
                            val invalidTimeoutMessage = stringResource(R.string.settings_llm_invalid_timeout)
                            LaunchedEffect(llm) {
                                if (llm != null) {
                                    llmBaseUrl = llm.baseUrl
                                    llmModel = llm.model
                                    llmTimeout = llm.timeoutSeconds.toString()
                                    llmApiKey = ""
                                }
                            }

                            OutlinedTextField(
                                value = llmBaseUrl,
                                onValueChange = { llmBaseUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.settings_llm_base_url)) },
                                placeholder = { Text(stringResource(R.string.settings_llm_base_url_hint)) },
                                singleLine = true,
                            )
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_llm_opencode_hint),
                            )
                            OutlinedTextField(
                                value = llmModel,
                                onValueChange = { llmModel = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.settings_llm_model)) },
                                placeholder = { Text(stringResource(R.string.settings_llm_model_hint)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = llmApiKey,
                                onValueChange = { llmApiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.settings_llm_api_key)) },
                                placeholder = { Text(stringResource(R.string.settings_llm_api_key_hint)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            OutlinedTextField(
                                value = llmTimeout,
                                onValueChange = { input -> llmTimeout = input.filter { it.isDigit() } },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.settings_llm_timeout)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val timeout = llmTimeout.trim().toIntOrNull()
                                        llmFormMessage = when {
                                            llmBaseUrl.isBlank() || llmModel.isBlank() -> invalidConfigMessage
                                            timeout == null || timeout !in 1..120 -> invalidTimeoutMessage
                                            else -> {
                                                actions.saveLlmConfig(
                                                    llmBaseUrl.trim(),
                                                    llmModel.trim(),
                                                    llmApiKey,
                                                    timeout,
                                                )
                                                null
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.settings_llm_save)) }
                                OutlinedButton(
                                    onClick = actions.testLlmConnection,
                                    enabled = !state.llmTesting,
                                ) {
                                    Text(
                                        if (state.llmTesting) {
                                            stringResource(R.string.settings_llm_testing)
                                        } else {
                                            stringResource(R.string.settings_llm_test)
                                        },
                                    )
                                }
                                OutlinedButton(
                                    onClick = actions.clearLlmKey,
                                    // 读取/解密失败（llmError）时也允许清除，作为恢复入口
                                    enabled = state.llm?.maskedKey != null || state.llmError != null,
                                ) { Text(stringResource(R.string.settings_llm_clear_key)) }
                            }
                            llmFormMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            state.llmTestMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.LlmBehavior.itemKey),
                    IntelligenceSection.LlmBehavior.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_llm_behavior_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            SwitchRow(
                                label = stringResource(R.string.settings_sag_toggle),
                                checked = state.sagEnabled,
                                onCheckedChange = actions.setSagEnabled,
                            )
                            SwitchRow(
                                label = stringResource(R.string.settings_answer_toggle),
                                checked = state.answerEnabled,
                                onCheckedChange = actions.setAnswerEnabled,
                            )
                            Text(
                                stringResource(R.string.settings_answer_consent_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.settings_answer_consent_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.answerNetworkConsent) {
                                Text(
                                    stringResource(R.string.settings_answer_consent_agreed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.settings_answer_consent_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = { actions.setAnswerNetworkConsent(true) }) {
                                    Text(stringResource(R.string.settings_answer_consent_agree))
                                }
                            }
                            SwitchRow(
                                label = stringResource(R.string.settings_network_disabled_toggle),
                                checked = state.networkDisabled,
                                onCheckedChange = actions.setNetworkDisabled,
                            )
                            Text(
                                stringResource(R.string.settings_network_disabled_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.settings_llm_privacy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.settings_llm_remote_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "sub-retrieval") {
                    SettingsGroupSubHeader(stringResource(R.string.settings_sub_retrieval))
                }

                settingsItem(
                    page.shows(IntelligenceSection.Budget.itemKey),
                    IntelligenceSection.Budget.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_budget_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SagBudgetTier.entries.forEach { tier ->
                                    FilterChip(
                                        selected = state.budgetTier == tier,
                                        onClick = { actions.setBudgetTier(tier) },
                                        enabled = !state.budgetSaving,
                                        label = { Text(budgetTierLabel(tier)) },
                                    )
                                }
                            }
                            val tierBudget = state.budgetTier.budget
                            if (showTechnical) {
                                Text(
                                    stringResource(
                                        R.string.settings_budget_current,
                                        tierBudget.kPool,
                                        tierBudget.kSeed,
                                        tierBudget.frontier,
                                        tierBudget.kCand,
                                        tierBudget.kEvent,
                                        tierBudget.kOut,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_budget_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // ---- P1.1：扫描件 PDF 端侧 OCR（默认开启；仅文本层为空时前 20 页）----
                            HorizontalDivider()
                            SwitchRow(
                                label = stringResource(R.string.settings_pdf_ocr_toggle),
                                checked = state.pdfOcrEnabled,
                                onCheckedChange = actions.setPdfOcrEnabled,
                            )
                            Text(
                                stringResource(R.string.settings_pdf_ocr_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ---- B9：SAG 两阶段抽取状态（与导入状态独立；失败不影响离线检索） ----
                settingsItem(
                    page.shows(IntelligenceSection.Sag.itemKey),
                    IntelligenceSection.Sag.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_sag_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val counts = state.extractionCounts
                            Text(
                                stringResource(
                                    R.string.settings_sag_counts,
                                    counts.queued,
                                    counts.running,
                                    counts.done,
                                    counts.failed,
                                    counts.notIndexed,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!counts.hasJobs) {
                                Text(
                                    stringResource(R.string.settings_sag_no_jobs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!state.sagAvailable) {
                                Text(
                                    stringResource(R.string.settings_sag_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            // B12：抽取并发度（1..4；Worker 下一批领取任务时生效）
                            Text(
                                stringResource(R.string.settings_extraction_parallelism_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (value in 1..4) {
                                    FilterChip(
                                        selected = state.extractionParallelism == value,
                                        onClick = { actions.setExtractionParallelism(value) },
                                        enabled = !state.extractionParallelismSaving,
                                        label = {
                                            Text(
                                                stringResource(
                                                    R.string.settings_extraction_parallelism_value,
                                                    value,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.settings_extraction_parallelism_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = actions.backfillExtraction,
                                    enabled = state.sagAvailable && !state.extractionWorking,
                                ) {
                                    Text(
                                        if (state.extractionWorking) {
                                            stringResource(R.string.settings_sag_working)
                                        } else {
                                            stringResource(R.string.settings_sag_backfill)
                                        },
                                    )
                                }
                                OutlinedButton(
                                    onClick = actions.retryFailedExtraction,
                                    enabled = state.sagAvailable &&
                                        !state.extractionWorking &&
                                        counts.failed > 0,
                                ) {
                                    Text(stringResource(R.string.settings_sag_retry_failed))
                                }
                            }
                            state.extractionMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "header-data") {
                    SettingsGroupHeader(stringResource(R.string.settings_group_data))
                }


                // ---- 知识库迁移 + 自动重嵌入 ----
                if (page == SettingsPage.LIBRARY) {
                    item(key = "library-hero") {
                        SettingsLibraryPageHero(
                            state = state,
                            actions = actions,
                            onImport = onOpenImport,
                        )
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Migration.itemKey),
                    IntelligenceSection.Migration.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_migration),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = actions.exportBackup) {
                                    Text(stringResource(R.string.settings_export))
                                }
                                OutlinedButton(onClick = actions.importBackup) {
                                    Text(stringResource(R.string.settings_import))
                                }
                            }
                            OutlinedButton(
                                onClick = actions.restoreBackup,
                                enabled = state.hasAutoBackup,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_restore_latest))
                            }
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_migration_hint),
                            )
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_backup_privacy_note),
                            )
                            state.migrationMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                settingsItem(
                    page.shows(IntelligenceSection.Reembed.itemKey) &&
                        (
                            page != SettingsPage.LIBRARY ||
                                state.reembedTotal > 0 ||
                                state.lastReembed != null ||
                                state.legacySpaces.isNotEmpty()
                            ),
                    IntelligenceSection.Reembed.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_reembed_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val showProgress = state.reembedTotal > 0 && state.reembedDone < state.reembedTotal
                            if (showProgress) {
                                LinearProgressIndicator(
                                    progress = { state.reembedDone.toFloat() / state.reembedTotal },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(
                                        R.string.settings_reembed_progress,
                                        state.reembedDone,
                                        state.reembedTotal,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = actions.cancelReembed) {
                                    Text(stringResource(R.string.settings_reembed_stop))
                                }
                            } else {
                                val lastDoneText = if (state.reembedTotal > 0) {
                                    stringResource(
                                        R.string.settings_reembed_last_done,
                                        state.reembedDone,
                                    )
                                } else {
                                    stringResource(R.string.settings_reembed_none)
                                }
                                Text(
                                    state.lastReembed?.let { "$lastDoneText · $it" } ?: lastDoneText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = actions.reembedNow) {
                                    Text(stringResource(R.string.settings_reembed_manual))
                                }
                            }
                        }
                    }
                }

                // ---- B7：旧模型空间警告（检测到非 EGemma2 空间时提示重新导入；不自动删数据）----
                // 审查修复：报表失败不再静默（错误可见、可重试），警告卡逐空间展示 modelId + vectorCount。
                state.legacySpacesError
                    ?.takeIf { page == SettingsPage.ADVANCED || page == SettingsPage.LIBRARY }
                    ?.let { error ->
                    item(key = "legacy-spaces-error") {
                        ErrorState(
                            message = error,
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = actions.retryRefresh,
                        )

                    }
                }
                if ((page == SettingsPage.ADVANCED || page == SettingsPage.LIBRARY) &&
                    state.legacySpaces.isNotEmpty()
                ) {
                    item(key = "legacy-spaces") {
                        ZhiBoxCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(
                                        R.string.settings_legacy_spaces_warning,
                                        state.legacySpaces.size,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                if (showTechnical) {
                                    state.legacySpaces.forEach { space ->
                                        val modelLabel = space.modelId.ifBlank {
                                            stringResource(R.string.settings_legacy_unknown_model)
                                        }
                                        Text(
                                            stringResource(
                                                R.string.settings_legacy_spaces_row,
                                                modelLabel,
                                                space.vectorCount,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(
                                            R.string.settings_legacy_spaces_simple,
                                            state.legacySpaces.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    stringResource(R.string.settings_legacy_spaces_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(R.string.settings_legacy_spaces_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                    }
                }

                // ---- 知识库 ----
                settingsItem(
                    page.shows(IntelligenceSection.Knowledge.itemKey),
                    IntelligenceSection.Knowledge.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.settings_section_kb),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(
                                    R.string.settings_kb_summary,
                                    state.docReady,
                                    state.chunkCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "header-diagnostics") {
                    SettingsGroupHeader(stringResource(R.string.settings_group_diagnostics))
                }


                // ---- 索引诊断（B3-14：三表计数 / 孤儿明细 / 修复 before-after）----
                settingsItem(
                    page.shows(IntelligenceSection.Diagnostics.itemKey),
                    IntelligenceSection.Diagnostics.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_section_diagnostics),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val health = state.indexHealth
                            if (health == null) {
                                // P1：进场不再自动做全表健康检查；未检查时给中性提示而不是"检查中…"
                                Text(
                                    stringResource(R.string.settings_index_not_checked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                if (showTechnical) {
                                    Text(
                                        stringResource(
                                            R.string.settings_index_counts,
                                            health.chunkCount,
                                            health.ftsCount,
                                            health.embeddingCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    if (health.healthy) {
                                        stringResource(
                                            if (showTechnical) {
                                                R.string.settings_index_healthy
                                            } else {
                                                R.string.settings_index_healthy_simple
                                            },
                                        )
                                    } else {
                                        stringResource(
                                            if (showTechnical) {
                                                R.string.settings_index_unhealthy
                                            } else {
                                                R.string.settings_index_unhealthy_simple
                                            },
                                            health.reasons.size,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (health.healthy) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                if (!health.healthy && showTechnical) {
                                    Text(
                                        stringResource(
                                            R.string.settings_index_orphans,
                                            health.orphanFtsChunkIds.size,
                                            health.orphanEmbeddingChunkIds.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    health.reasons.take(5).forEach { reason ->
                                        Text(
                                            "· $reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = actions.repairIndex,
                                    enabled = !state.indexRepairing,
                                ) {
                                    Text(
                                        if (state.indexRepairing) {
                                            stringResource(R.string.settings_index_repairing)
                                        } else {
                                            stringResource(R.string.settings_index_repair)
                                        },
                                    )
                                }
                            }
                            state.diagMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_fts_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Trace.itemKey),
                    IntelligenceSection.Trace.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_trace_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val traceTotal = state.traceTotalCount
                            if (traceTotal == null) {
                                LoadingState(message = stringResource(R.string.settings_index_checking))
                            } else {
                                Text(
                                    stringResource(R.string.settings_trace_summary, traceTotal),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (showTechnical && state.traceRecentModes.isNotEmpty()) {
                                    Text(
                                        stringResource(
                                            R.string.settings_trace_modes,
                                            state.traceRecentModes.entries.joinToString(" · ") {
                                                "${it.key} ${it.value}"
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = actions.exportTrace,
                                enabled = !state.traceExporting,
                            ) {
                                Text(
                                    if (state.traceExporting) {
                                        stringResource(R.string.settings_trace_exporting)
                                    } else {
                                        stringResource(R.string.settings_trace_export)
                                    },
                                )
                            }
                            state.traceExportMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_trace_privacy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Quality.itemKey),
                    IntelligenceSection.Quality.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.settings_quality_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val quality = state.qualityReport
                            // P1：滚到该段才跑重查询；已有报告则不重复
                            LaunchedEffect(quality == null) {
                                if (quality == null && !state.qualityLoading) actions.refreshQuality()
                            }
                            if (quality == null) {
                                Text(
                                    if (state.qualityLoading) {
                                        stringResource(R.string.settings_quality_refreshing)
                                    } else {
                                        stringResource(R.string.settings_quality_empty)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (
                                quality.documentsTotal == 0 &&
                                quality.eventsTotal == 0 &&
                                quality.entitiesTotal == 0
                            ) {
                                Text(
                                    stringResource(R.string.settings_quality_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    stringResource(
                                        R.string.settings_quality_counts,
                                        quality.documentsTotal,
                                        quality.readyDocuments,
                                        quality.eventsTotal,
                                        quality.eventsActive,
                                        quality.eventsDiscarded,
                                        quality.entitiesTotal,
                                        quality.eventEntityLinks,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.settings_quality_blank,
                                        quality.blankEventFields.title,
                                        quality.blankEventFields.summary,
                                        quality.blankEventFields.content,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.settings_quality_coverage,
                                        coveragePercent(
                                            quality.documentsWithEvents,
                                            quality.documentsTotal,
                                        ),
                                        quality.documentsWithEvents,
                                        quality.documentsTotal,
                                        quality.documentsWithoutEvents,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.settings_quality_alias_coverage,
                                        ratioPercent(quality.aliasCoverage),
                                        quality.entitiesWithAliases,
                                        quality.entitiesTotal,
                                        quality.aliasesTotal,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val statusText = formatDistribution(quality.jobsByStatus, limit = 6)
                                Text(
                                    stringResource(
                                        R.string.settings_quality_jobs,
                                        quality.extractionJobsTotal,
                                        statusText,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (showTechnical) {
                                    val errorCodeText =
                                        formatDistribution(quality.jobsByErrorCode, limit = 6)
                                    Text(
                                        stringResource(
                                            R.string.settings_quality_error_codes,
                                            errorCodeText,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                val typeText = formatDistribution(quality.entityTypeCounts, limit = 8)
                                Text(
                                    stringResource(R.string.settings_quality_types, typeText),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = actions.refreshQuality,
                                    enabled = !state.qualityLoading,
                                ) {
                                    Text(
                                        if (state.qualityLoading) {
                                            stringResource(R.string.settings_quality_refreshing)
                                        } else {
                                            stringResource(R.string.settings_quality_refresh)
                                        },
                                    )
                                }
                                OutlinedButton(
                                    onClick = actions.exportQualityJson,
                                    enabled = state.qualityReport != null && !state.qualityExporting,
                                ) {
                                    Text(
                                        if (state.qualityExporting) {
                                            stringResource(R.string.settings_quality_exporting)
                                        } else {
                                            stringResource(R.string.settings_quality_export)
                                        },
                                    )
                                }
                            }
                            state.qualityMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_quality_privacy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                settingsItem(page == SettingsPage.ADVANCED, "header-privacy") {
                    SettingsGroupHeader(stringResource(R.string.settings_group_privacy))
                }


                // ---- 隐私与离线声明 ----
                if (page == SettingsPage.PRIVACY) {
                    item(key = "privacy-hero") {
                        SettingsPrivacyPageHero()
                    }
                }
                settingsItem(
                    page.shows(IntelligenceSection.Privacy.itemKey),
                    IntelligenceSection.Privacy.itemKey,
                ) {
                    ZhiBoxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.settings_section_privacy),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            SettingsExpandableHint(
                                text = stringResource(R.string.settings_privacy_text),
                                defaultExpanded = page == SettingsPage.PRIVACY,
                            )
                        }
                    }
                }
            }
            deepLinkHighlightKey?.let { targetKey ->
                // 高亮覆盖块：与 LazyColumn 同处一个 Box（同坐标系），叠在列表之上；
                // clip 到列表视口，避免超长 section（如 LLM 配置卡）的高亮画到顶栏上。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clipToBounds(),
                ) {
                    SettingsDeepLinkHighlight(
                        listState = listState,
                        targetKey = targetKey,
                        visible = deepLinkHighlightVisible,
                        animationsEnabled = animationsEnabled,
                    )
                }
            }
        }
        } // 关闭 Column（技术细节固定行 + 列表 Box）
    }
}

// ───────────────────────── 深链落位高亮（规格 §6.5） ─────────────────────────

/** 深链高亮：显示时长（规格 §6.5「滚到锚点后高亮 1.2s」）。 */
private const val DEEP_LINK_HIGHLIGHT_HOLD_MS = 1_200L

/** 深链高亮：淡出时长（淡入立即，避免二次等待）。 */
private const val DEEP_LINK_HIGHLIGHT_FADE_MS = 300

/** 深链高亮：等目标 item 进入布局信息的上限；通常 1 帧内就绪，超时则跳过高亮。 */
private const val DEEP_LINK_HIGHLIGHT_LAYOUT_WAIT_MS = 500L

/** 深链高亮覆盖块透明度：主色叠加，浅色/深色下都可辨识且不刺眼。 */
private const val DEEP_LINK_HIGHLIGHT_ALPHA = 0.12f

/** 设置页 LazyColumn 内容内边距（高亮覆盖块按它对齐卡片左右边界）。 */
internal val SettingsListPadding = 16.dp

/** 设置页 LazyColumn contentPadding（与 [SettingsListPadding] 同源；简单首页复用）。 */
internal val SettingsListContentPadding = PaddingValues(SettingsListPadding)

/**
 * 系统「减少动画」是否关闭：读 `ANIMATOR_DURATION_SCALE`（开发者选项/无障碍关闭动画时为 0）。
 * 读失败按「允许动画」处理，避免误伤正常设备；与 HomeScreen 内同名工具一致（各屏自包含）。
 */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

/**
 * 深链落位后的目标 section 高亮覆盖块（规格 §6.5）。
 *
 * - 坐标：与 [LazyColumn] 同坐标系（外层 Box），直接取 [LazyListState.layoutInfo] 中目标 item 的
 *   `offset`/`size` 定位；用户在 1.2s 内滑动列表时覆盖块跟随目标卡片，不会留下悬空色块。
 * - 尺寸：与目标卡片同宽同高、圆角 [ZhiBoxRadius.card]；超长 section（如 LLM 配置卡）由外层
 *   Box 的 `clipToBounds` 裁到列表视口，不会画到顶栏。
 * - 目标不在可见区或布局信息未就绪时什么都不画（不崩溃、不闪烁），下次布局变化自动补画。
 */
@Composable
private fun SettingsDeepLinkHighlight(
    listState: LazyListState,
    targetKey: String,
    visible: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = listState.layoutInfo
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey } ?: return
    // LazyListItemInfo 只有主轴 offset/size（size 是主轴像素高度），横向宽度与纵向视觉位置需换算：
    // - 纵向：offset 相对列表容器，place() 时还会叠加 visualItemOffset（= 上 contentPadding），
    //   且 viewportStartOffset = -beforeContentPadding，故视觉 y = offset - viewportStartOffset；
    // - 横向：深链目标都是 fillMaxWidth 卡片，宽度 = viewport 宽 - 两侧 contentPadding，
    //   左边界 = 起始 contentPadding（依赖 contentPadding 左右等宽，见 [SettingsListContentPadding]）。
    val density = LocalDensity.current
    val startPaddingPx = with(density) { SettingsListPadding.roundToPx() }
    val leftPx = startPaddingPx
    val topPx = target.offset - layoutInfo.viewportStartOffset
    val widthPx = layoutInfo.viewportSize.width - startPaddingPx * 2
    val heightPx = target.size
    if (widthPx <= 0 || heightPx <= 0) return
    val highlightAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (animationsEnabled) DEEP_LINK_HIGHLIGHT_FADE_MS else 0,
        ),
        label = "settingsDeepLinkHighlight",
    )
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = DEEP_LINK_HIGHLIGHT_ALPHA)
    Box(
        modifier = modifier
            .offset {
                IntOffset(x = leftPx, y = topPx)
            }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() },
            )
            .alpha(highlightAlpha)
            .background(
                color = highlightColor,
                shape = RoundedCornerShape(ZhiBoxRadius.card),
            ),
    )
}

/** B12：预算档位展示名（高/中/低端设备，PRD §7.2）。 */
@Composable
private fun budgetTierLabel(tier: SagBudgetTier): String = when (tier) {
    SagBudgetTier.HIGH -> stringResource(R.string.settings_budget_tier_high)
    SagBudgetTier.MID -> stringResource(R.string.settings_budget_tier_mid)
    SagBudgetTier.LOW -> stringResource(R.string.settings_budget_tier_low)
}

/** B15.2：抽取质量分布展示（最多 [limit] 项；其余显示总数，空显示“无”）。 */
@Composable
private fun formatDistribution(counts: Map<String, Int>, limit: Int): String {
    if (counts.isEmpty()) return stringResource(R.string.settings_quality_none)
    val entries = counts.entries.toList()
    val shown = entries.take(limit).joinToString(" · ") { "${it.key} ${it.value}" }
    return if (entries.size > limit) {
        stringResource(R.string.settings_quality_distribution_more, shown, entries.size)
    } else {
        shown
    }
}

/** B15.2：质量 JSON 导出默认文件名。 */
private const val QUALITY_EXPORT_FILE_NAME = "zhibox-extraction-quality.json"

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

// ───────────────────────── B3-7 @Preview（浅色/深色/索引异常） ─────────────────────────

private fun previewSettingsState(
    indexHealth: IngestRepository.IndexHealth?,
    error: String? = null,
) = SettingsUiState(
    loading = false,
    model = "egemma2-440m.litertlm",
    preference = PathPreferences.AUTO,
    gpuPref = PathPreferences.GPU_AUTO,
    perfPref = ModelCatalog.PERF_BALANCED,
    socTag = "qcom/sm8650",
    models = listOf(
        ModelFileItem(File("/data/user/0/com.zhibox.memory/files/models/egemma2-440m.litertlm"), "A·440M", 352L * 1024 * 1024),
    ),
    spaces = listOf(SpaceInfo("mp-universal/egemma2-440m/app-prefix-v1", 1234, 768)),
    chunkCount = 1234,
    docReady = 42,
    storeState = SharedModelStore.State.NOT_CONFIGURED,
    sharedNames = emptyList(),
    downloading = false,
    message = null,
    error = error,
    migrationMessage = null,
    reembedDone = 0,
    reembedTotal = -1,
    lastReembed = "10-16 12:30",
    indexHealth = indexHealth,
    diagMessage = null,
)

@Preview(showBackground = true, name = "Settings · Light")
@Composable
private fun SettingsLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SettingsPageContent(
            page = SettingsPage.ADVANCED,
            state = previewSettingsState(
                IngestRepository.IndexHealth(1234, 1234, 1234, emptyList(), emptyList()),
            ),
            actions = SettingsActions(),
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Settings · Dark")
@Composable
private fun SettingsDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        SettingsPageContent(
            page = SettingsPage.ADVANCED,
            state = previewSettingsState(
                IngestRepository.IndexHealth(1234, 1200, 1210, listOf(7L, 9L), listOf(11L)),
            ).copy(
                legacySpaces = listOf(
                    LegacySpaceUi("legacy-model-a", 123),
                    LegacySpaceUi("", 45),
                ),
                // B8：已配置 LLM 样例（只展示掩码 key）
                llm = LlmConfigUi(
                    baseUrl = "https://api.example.com/v1",
                    model = "gpt-4o-mini",
                    timeoutSeconds = 30,
                    maskedKey = "sk-***abcd",
                ),
                llmLoaded = true,
                sagEnabled = true,
                answerEnabled = false,
                networkDisabled = true,
                // B9：抽取状态样例
                sagAvailable = true,
                extractionCounts = ExtractionCountsUi(
                    queued = 2,
                    running = 1,
                    done = 45,
                    failed = 1,
                    notIndexed = 3,
                ),
                // B12：trace 诊断样例
                traceTotalCount = 12,
                traceRecentModes = mapOf("qa" to 3, "multi_es_fast" to 7, "vector" to 2),
                budgetTier = SagBudgetTier.MID,
                extractionParallelism = 2,
                pdfOcrEnabled = false,
                // B15.2：抽取质量样例
                qualityReport = com.zhibox.memory.core.extraction.ExtractionQualityReport(
                    documentsTotal = 42,
                    readyDocuments = 40,
                    eventsTotal = 130,
                    eventsActive = 120,
                    eventsDiscarded = 10,
                    blankEventFields = com.zhibox.memory.core.extraction.BlankEventFields(
                        title = 2,
                        summary = 3,
                        content = 0,
                    ),
                    entitiesTotal = 88,
                    entitiesWithAliases = 12,
                    aliasesTotal = 20,
                    entityTypeCounts = mapOf(
                        "concept" to 30,
                        "product" to 20,
                        "unknown" to 8,
                    ),
                    eventEntityLinks = 210,
                    documentsWithEvents = 35,
                    documentsWithoutEvents = 7,
                    extractionJobsTotal = 42,
                    jobsByStatus = mapOf("done" to 40, "failed" to 2),
                    jobsByErrorCode = mapOf("PDF_ENCRYPTED" to 2),
                ),
            ),
            actions = SettingsActions(),
        )
    }
}

@Preview(showBackground = true, name = "Settings · Error")
@Composable
private fun SettingsErrorPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SettingsPageContent(
            page = SettingsPage.ADVANCED,
            state = previewSettingsState(
                indexHealth = null,
                error = "刷新设置失败：数据库被占用",
            ).copy(
                legacySpacesError = "旧模型空间检查失败：数据库被占用",
            ),
            actions = SettingsActions(),
        )
    }
}
