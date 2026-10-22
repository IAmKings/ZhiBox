package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.core.sag.SagHit
import com.zhibox.memory.core.sag.SagResult
import com.zhibox.memory.core.sag.SagTrace
import com.zhibox.memory.core.sag.SagTraceStage
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.Locale

/**
 * 检索屏（B10）：SAG 三模式检索（「混合」/「SAG 快速」/「SAG 精排」）。
 *
 * - 顶部模式选择带工具提示；结果区显示 `effectiveMode` 与降级原因；
 * - `refused`（如 multi 无可用 LLM）给出切换 fast/vector 的可执行提示；
 * - 精简 trace（seedEvents/seedEntities/candidates/selected/elapsed + 阶段耗时）常驻结果区；
 * - 「导出 trace JSON」导出最近 50 条脱敏 trace（仅 hash/耗时/降级原因，无正文）；
 * - 命中点击进入既有 [EvidenceScreen]（[SagHit.toEvidenceSearchHit] 映射），返回不丢检索状态。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    /** 首页输入岛提交的检索词：进入检索屏时预填一次（空串表示沿用上次输入）。 */
    initialQuery: String = "",
    viewModel: SearchViewModel = viewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    // applied 存 Bundle：证据页覆盖层返回会重新进入本组合，避免二次覆盖用户已改的输入
    var initialQueryApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialQueryApplied && initialQuery.isNotBlank()) {
            viewModel.onQueryChange(initialQuery)
            initialQueryApplied = true
        }
    }
    val running by viewModel.running.collectAsStateWithLifecycle()
    val loadingModel by viewModel.loadingModel.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val exportingTrace by viewModel.exportingTrace.collectAsStateWithLifecycle()
    val traceExportMessage by viewModel.traceExportMessage.collectAsStateWithLifecycle()
    val selectedHit by viewModel.selectedHit.collectAsStateWithLifecycle()

    // 证据详情覆盖层：系统返回键先关闭证据页（而不是退出检索屏）。
    // 选中项在 ViewModel 中：旋转/进程重建后仍停留在证据页（P1-8）。
    val hit = selectedHit
    if (hit != null) {
        BackHandler { viewModel.clearSelectedHit() }
        EvidenceScreen(
            hit = hit.toEvidenceSearchHit(),
            onBack = { viewModel.clearSelectedHit() },
        )
        return
    }

    SearchContent(
        query = query,
        running = running,
        loadingModel = loadingModel,
        mode = mode,
        result = result,
        errorMessage = errorMessage,
        exportingTrace = exportingTrace,
        traceExportMessage = traceExportMessage,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::search,
        onModeChange = viewModel::setMode,
        onBack = onBack,
        onHitClick = viewModel::selectHit,
        onExportTrace = viewModel::exportTrace,
        onDismissError = viewModel::retryAfterError,
    )
}

/** 无状态检索内容：供 [SearchScreen] 与 @Preview / UI 测试直接渲染。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchContent(
    query: String,
    running: Boolean,
    loadingModel: Boolean,
    mode: RetrievalMode,
    result: SagResult?,
    errorMessage: String?,
    exportingTrace: Boolean,
    traceExportMessage: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onModeChange: (RetrievalMode) -> Unit,
    onBack: () -> Unit,
    onHitClick: (SagHit) -> Unit,
    onExportTrace: () -> Unit,
    onDismissError: () -> Unit,
) {
    ZhiBoxScreen(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        backEnabled = !running,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // B10 真机修复：小屏 + trace/错误态内容高于视口时，结果/提示需可滚动；
                // 不使用嵌套 LazyColumn（外层滚动 + 内层 lazy 会测量冲突）。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                )
                Button(
                    onClick = onSearch,
                    enabled = !running && query.isNotBlank(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(stringResource(R.string.search_submit))
                }
            }

            // ---- B10：检索模式（三模式 + 工具提示） ----
            Text(
                stringResource(R.string.search_mode_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RetrievalMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { onModeChange(candidate) },
                        label = { Text(modeLabel(candidate)) },
                    )
                }
            }
            Text(
                modeHint(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (running) {
                // B3-2：进程内首次检索需构建端侧模型时给出阶段反馈；warm 检索只显示进度条。
                LoadingState(
                    message = if (loadingModel) {
                        stringResource(R.string.search_loading_model)
                    } else {
                        null
                    },
                )
            }

            errorMessage?.let {
                ErrorState(
                    title = stringResource(R.string.search_refused_title),
                    message = it,
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = onDismissError,
                )
            }

            result?.let { r ->
                if (r.refused != null) {
                    ErrorState(
                        title = stringResource(R.string.search_refused_title),
                        message = r.refused,
                        hint = if (r.mode == RetrievalMode.MULTI) {
                            stringResource(R.string.search_multi_refused_hint)
                        } else {
                            stringResource(R.string.search_refused_hint)
                        },
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissError,
                    )
                } else {
                    // 生效模式 + 降级原因（例如事件层无数据 → vector）
                    Text(
                        stringResource(
                            R.string.search_effective_mode,
                            modeLabel(r.effectiveMode),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (r.degraded || r.effectiveMode != r.mode) {
                        Text(
                            stringResource(
                                R.string.search_degraded_reason,
                                r.trace.degradedReason ?: "—",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.search_summary,
                            r.hits.size,
                            r.trace.elapsedMs,
                            r.trace.candidates,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    // ---- B10：精简 trace（默认折叠，避免挤占结果区）+ 脱敏导出 ----
                    var traceExpanded by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(R.string.search_trace_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                TextButton(onClick = { traceExpanded = !traceExpanded }) {
                                    Text(
                                        stringResource(
                                            if (traceExpanded) R.string.search_trace_collapse
                                            else R.string.search_trace_expand,
                                        ),
                                    )
                                }
                            }
                            Text(
                                stringResource(
                                    R.string.search_trace_counts,
                                    r.trace.seedEvents,
                                    r.trace.seedEntities,
                                    r.trace.expandedEvents,
                                    r.trace.candidates,
                                    r.trace.selected,
                                    r.trace.elapsedMs,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (traceExpanded) {
                                r.trace.stages.forEach { stage ->
                                    Text(
                                        stringResource(
                                            R.string.search_trace_stage,
                                            stage.name,
                                            stage.count,
                                            stage.ms,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(
                                    onClick = onExportTrace,
                                    enabled = !exportingTrace,
                                ) {
                                    Text(
                                        if (exportingTrace) {
                                            stringResource(R.string.search_trace_exporting)
                                        } else {
                                            stringResource(R.string.search_trace_export)
                                        },
                                    )
                                }
                            }
                            traceExportMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    if (r.hits.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.search_empty_title),
                            message = stringResource(R.string.search_empty_message),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.hits.forEach { h ->
                                Card(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onHitClick(h) },
                                ) {
                                    Column(
                                        Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                h.docTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                "[${h.channels.joinToString("+")}]",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            h.eventId?.let { eventId ->
                                                Text(
                                                    stringResource(R.string.search_hit_event_tag, eventId),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                )
                                            }
                                        }
                                        Text(
                                            h.content.take(120) + if (h.content.length > 120) "…" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                        )
                                        Text(
                                            stringResource(
                                                R.string.search_hit_meta,
                                                h.ordinal,
                                                h.charStart,
                                                h.charEnd,
                                                String.format(
                                                    Locale.ROOT,
                                                    "%.4f",
                                                    h.fusedScore ?: h.score,
                                                ),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: RetrievalMode): String = when (mode) {
    RetrievalMode.VECTOR -> stringResource(R.string.search_mode_vector)
    RetrievalMode.MULTI_ES_FAST -> stringResource(R.string.search_mode_multi_es_fast)
    RetrievalMode.MULTI -> stringResource(R.string.search_mode_multi)
}

@Composable
private fun modeHint(mode: RetrievalMode): String = when (mode) {
    RetrievalMode.VECTOR -> stringResource(R.string.search_mode_vector_hint)
    RetrievalMode.MULTI_ES_FAST -> stringResource(R.string.search_mode_multi_es_fast_hint)
    RetrievalMode.MULTI -> stringResource(R.string.search_mode_multi_hint)
}

// ───────────────────────── B10 @Preview（浅色/深色/降级/空态/拒绝/加载） ─────────────────────────

private fun previewHit(
    chunkId: Long,
    title: String,
    content: String,
    eventId: Long? = null,
    ordinal: Int = 0,
    chars: IntRange = 0..120,
    score: Double = 0.82,
    channels: Set<String> = setOf("vector", "event"),
) = SagHit(
    chunkId = chunkId,
    documentId = "doc_$chunkId",
    sourceId = "src_$chunkId",
    ordinal = ordinal,
    content = content,
    charStart = chars.first,
    charEnd = chars.last,
    docTitle = title,
    sourceTitle = "示例来源",
    sourceUri = "content://demo/doc_$chunkId",
    eventId = eventId,
    entityIds = listOf(1L, 2L),
    channels = channels,
    score = score,
    fusedScore = score,
)

private fun previewTrace(
    degradedReason: String? = null,
) = SagTrace(
    mode = "multi_es_fast",
    queryHash = "hash_preview",
    stages = listOf(
        SagTraceStage("seed", 5, 12),
        SagTraceStage("expand", 32, 28),
        SagTraceStage("fuse", 10, 6),
    ),
    seedEvents = 5,
    seedEntities = 3,
    expandedEvents = 32,
    candidates = 42,
    selected = 10,
    degradedReason = degradedReason,
    elapsedMs = 46,
)

private fun previewResult(
    hits: List<SagHit>,
    mode: RetrievalMode = RetrievalMode.MULTI_ES_FAST,
    effectiveMode: RetrievalMode = mode,
    degraded: Boolean = false,
    degradedReason: String? = null,
    refused: String? = null,
) = SagResult(
    query = "蓝牙断开怎么恢复",
    mode = mode,
    hits = hits,
    trace = previewTrace(degradedReason),
    refused = refused,
    degraded = degraded,
    effectiveMode = effectiveMode,
)

private val previewHits = listOf(
    previewHit(
        1,
        "蓝牙耳机连接问题排查",
        "先关闭耳机电源再长按配对键 5 秒，进入可发现状态后重新配对。",
        eventId = 101,
        ordinal = 2,
        chars = 300..420,
    ),
    previewHit(
        2,
        "安卓蓝牙协议栈说明",
        "断开通常由协议栈重置或省电策略触发，可关闭省电优化后重试。",
        ordinal = 5,
        chars = 120..260,
        score = 0.71,
        channels = setOf("vector"),
    ),
)

@Preview(showBackground = true, name = "Search · Light (SAG fast)")
@Composable
private fun SearchLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = false,
            loadingModel = false,
            mode = RetrievalMode.MULTI_ES_FAST,
            result = previewResult(previewHits),
            errorMessage = null,
            exportingTrace = false,
            traceExportMessage = null,
            onQueryChange = {},
            onSearch = {},
            onModeChange = {},
            onBack = {},
            onHitClick = {},
            onExportTrace = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Search · Dark (degraded)")
@Composable
private fun SearchDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = false,
            loadingModel = false,
            mode = RetrievalMode.MULTI_ES_FAST,
            result = previewResult(
                hits = previewHits,
                effectiveMode = RetrievalMode.VECTOR,
                degraded = true,
                degradedReason = "事件层无数据（SAG 抽取未完成）——已回退混合检索",
            ),
            errorMessage = null,
            exportingTrace = false,
            traceExportMessage = null,
            onQueryChange = {},
            onSearch = {},
            onModeChange = {},
            onBack = {},
            onHitClick = {},
            onExportTrace = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Search · Empty")
@Composable
private fun SearchEmptyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "不存在的主题",
            running = false,
            loadingModel = false,
            mode = RetrievalMode.VECTOR,
            result = previewResult(emptyList(), mode = RetrievalMode.VECTOR),
            errorMessage = null,
            exportingTrace = false,
            traceExportMessage = null,
            onQueryChange = {},
            onSearch = {},
            onModeChange = {},
            onBack = {},
            onHitClick = {},
            onExportTrace = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Search · Refused (multi)")
@Composable
private fun SearchRefusedPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = false,
            loadingModel = false,
            mode = RetrievalMode.MULTI,
            result = previewResult(
                hits = emptyList(),
                mode = RetrievalMode.MULTI,
                refused = "multi（SAG 精排）需要配置并允许联网的 LLM；当前未配置或已禁网",
            ),
            errorMessage = null,
            exportingTrace = false,
            traceExportMessage = null,
            onQueryChange = {},
            onSearch = {},
            onModeChange = {},
            onBack = {},
            onHitClick = {},
            onExportTrace = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Search · Loading (cold model)")
@Composable
private fun SearchLoadingPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = true,
            loadingModel = true,
            mode = RetrievalMode.MULTI_ES_FAST,
            result = null,
            errorMessage = null,
            exportingTrace = false,
            traceExportMessage = null,
            onQueryChange = {},
            onSearch = {},
            onModeChange = {},
            onBack = {},
            onHitClick = {},
            onExportTrace = {},
            onDismissError = {},
        )
    }
}
