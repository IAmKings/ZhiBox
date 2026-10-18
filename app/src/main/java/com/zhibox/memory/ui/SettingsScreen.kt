package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.model.SharedModelStore
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.io.File

/**
 * 设置屏（P1-8 + B3-3/B3-5/B3-7/B3-14）：状态与长任务在 [SettingsViewModel]；
 * 下载中离开页面会提示（任务不随组合取消）。B3-14 展示索引三表计数/孤儿明细，并提供修复入口。
 *
 * UI 拆为无状态 [SettingsContent] + [SettingsActions]（可 @Preview 渲染）；SAF launcher 与
 * 二次确认对话框留在有状态的 [SettingsScreen]。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var pendingDeleteModel by remember { mutableStateOf<File?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var importDropGpu by remember { mutableStateOf(true) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var pendingClearLlmKey by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::previewImport) }
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::setTreeUri) }

    fun attemptBack() {
        if (state.downloading) showLeaveDialog = true else onBack()
    }

    // 下载中按系统返回：给出确认提示（下载任务在 ViewModel 中继续进行）。
    BackHandler { attemptBack() }

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

    val actions = SettingsActions(
        onBack = ::attemptBack,
        setPreference = viewModel::setPreference,
        setGpuMode = viewModel::setGpuMode,
        setPerfMode = viewModel::setPerfMode,
        chooseDirectory = { dirPicker.launch(null) },
        retryRefresh = viewModel::refresh,
        download = { entry ->
            if (state.storeState != SharedModelStore.State.CONNECTED) {
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
        repairIndex = viewModel::repairIndex,
        reembedNow = viewModel::reembedNow,
        cancelReembed = viewModel::cancelReembed,
        saveLlmConfig = { baseUrl, model, apiKey, timeoutSeconds ->
            viewModel.saveLlmConfig(baseUrl, model, apiKey, timeoutSeconds)
        },
        testLlmConnection = viewModel::testLlmConnection,
        clearLlmKey = { pendingClearLlmKey = true },
        setSagEnabled = viewModel::setSagEnabled,
        setAnswerEnabled = viewModel::setAnswerEnabled,
        setNetworkDisabled = viewModel::setNetworkDisabled,
        backfillExtraction = viewModel::backfillExtraction,
        retryFailedExtraction = viewModel::retryFailedExtraction,
    )
    SettingsContent(state = state, actions = actions)
}

/** 设置屏动作集合：无状态内容组件通过它回调有状态包装层（便于 @Preview 传默认空实现）。 */
class SettingsActions(
    val onBack: () -> Unit = {},
    val setPreference: (String) -> Unit = {},
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
    val repairIndex: () -> Unit = {},
    val reembedNow: () -> Unit = {},
    val cancelReembed: () -> Unit = {},
    // B8：SAG 增强 / LLM
    val saveLlmConfig: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    val testLlmConnection: () -> Unit = {},
    val clearLlmKey: () -> Unit = {},
    val setSagEnabled: (Boolean) -> Unit = {},
    val setAnswerEnabled: (Boolean) -> Unit = {},
    val setNetworkDisabled: (Boolean) -> Unit = {},
    // B9：SAG 两阶段抽取
    val backfillExtraction: () -> Unit = {},
    val retryFailedExtraction: () -> Unit = {},
)

/** 无状态设置内容：供 [SettingsScreen] 与 @Preview / UI 测试直接渲染（B3-7）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    ZhiBoxScreen(
        title = stringResource(R.string.settings_title),
        onBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // B3-5：统一加载/错误组件
            if (state.loading) {
                LoadingState(message = stringResource(R.string.settings_loading))
            }
            state.error?.let {
                ErrorState(
                    message = it,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = actions.retryRefresh,
                )
            }

            // ---- 嵌入路径 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.settings_section_embedding),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.model?.let { stringResource(R.string.settings_current_model, it) }
                            ?: stringResource(R.string.settings_no_model),
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
                                onClick = { actions.setPreference(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.settings_path_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- 模型商店（SOC 感知推荐 + SAF 共享目录）----
            Card(Modifier.fillMaxWidth()) {
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
                    HorizontalDivider()
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
                        Text(
                            stringResource(
                                R.string.settings_shared_list,
                                state.sharedNames.joinToString("、") {
                                    it.removeSuffix(".litertlm")
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                            OutlinedButton(onClick = { actions.enableShared(name) }) {
                                Text(
                                    stringResource(
                                        R.string.settings_enable,
                                        name.removeSuffix(".litertlm"),
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

            // ---- GPU 加速 ----
            Card(Modifier.fillMaxWidth()) {
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
                    Text(
                        stringResource(R.string.settings_gpu_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- 模型文件 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.settings_section_models, state.models.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.models.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.settings_models_empty),
                        )
                    }
                    state.models.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(
                                        R.string.settings_model_size_line,
                                        item.label,
                                        item.sizeBytes / 1024 / 1024,
                                    ),
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

            // ---- 向量空间注册表（维度来自 embedding_meta.dimensions，P1-6）----
            Card(Modifier.fillMaxWidth()) {
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
                    } else {
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
                    }
                }
            }

            // ---- 知识库迁移 + 自动重嵌入 ----
            Card(Modifier.fillMaxWidth()) {
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
                    Text(
                        stringResource(R.string.settings_migration_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.migrationMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    HorizontalDivider()
                    Text(
                        stringResource(R.string.settings_reembed_section),
                        style = MaterialTheme.typography.titleSmall,
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

            // ---- B7：旧模型空间警告（检测到非 EGemma2 空间时提示重新导入；不自动删数据）----
            // 审查修复：报表失败不再静默（错误可见、可重试），警告卡逐空间展示 modelId + vectorCount。
            state.legacySpacesError?.let { error ->
                ErrorState(
                    message = error,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = actions.retryRefresh,
                )
            }
            if (state.legacySpaces.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
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

            // ---- 索引诊断（B3-14：三表计数 / 孤儿明细 / 修复 before-after）----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.settings_section_diagnostics),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val health = state.indexHealth
                    if (health == null) {
                        LoadingState(message = stringResource(R.string.settings_index_checking))
                    } else {
                        Text(
                            stringResource(
                                R.string.settings_index_counts,
                                health.chunkCount,
                                health.ftsCount,
                                health.embeddingCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (health.healthy) {
                                stringResource(R.string.settings_index_healthy)
                            } else {
                                stringResource(R.string.settings_index_unhealthy, health.reasons.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (health.healthy) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        if (!health.healthy) {
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

            // ---- 知识库 ----
            Card(Modifier.fillMaxWidth()) {
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

            // ---- B8：SAG 增强 / LLM（骨架：配置/测试/开关；未配置时离线检索零回归） ----
            Card(Modifier.fillMaxWidth()) {
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
                    Text(
                        text = stringResource(R.string.settings_llm_opencode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                    HorizontalDivider()
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

            // ---- B9：SAG 两阶段抽取状态（与导入状态独立；失败不影响离线检索） ----
            Card(Modifier.fillMaxWidth()) {
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

            // ---- 隐私与离线声明 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.settings_section_privacy),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.settings_privacy_text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        SettingsContent(
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
        SettingsContent(
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
            ),
            actions = SettingsActions(),
        )
    }
}

@Preview(showBackground = true, name = "Settings · Error")
@Composable
private fun SettingsErrorPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SettingsContent(
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
