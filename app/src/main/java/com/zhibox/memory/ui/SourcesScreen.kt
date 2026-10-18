package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * 来源列表屏（P1-8 + B3-5/B3-7）：source → document 状态一览（导入进度与就绪数量）。
 * Flow 订阅、删除与错误边界在 [SourcesViewModel]；空态给出“去导入”的下一步动作。
 *
 * UI 拆为无状态 [SourcesContent]（可 @Preview 渲染），删除确认对话框留在 [SourcesScreen]。
 */
@Composable
fun SourcesScreen(
    onBack: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    viewModel: SourcesViewModel = viewModel(),
) {
    val sources by viewModel.sources.collectAsState()
    val documents by viewModel.documents.collectAsState()
    val extractionByDocument by viewModel.extractionByDocument.collectAsState()

    // B9：进入页面时刷新一次逐文档抽取状态（后台任务变化时避免长期陈旧）
    LaunchedEffect(Unit) {
        viewModel.refreshExtractionStatuses()
    }
    val loaded by viewModel.loaded.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()

    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) } // sourceId to title

    val pendingDeleteValue = pendingDelete
    if (pendingDeleteValue != null) {
        val (sourceId, title) = pendingDeleteValue
        val message = stringResource(R.string.sources_delete_message, title)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sources_delete_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        viewModel.deleteSource(sourceId) { pendingDelete = null }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // B12：来源 / 图谱切换（图谱数据/分页独立在 GraphViewModel）
    var tab by remember { mutableStateOf(SOURCES_TAB_SOURCES) }
    val graphViewModel: GraphViewModel = viewModel()

    ZhiBoxScreen(title = stringResource(R.string.sources_title), onBack = onBack) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tab == SOURCES_TAB_SOURCES,
                    onClick = { tab = SOURCES_TAB_SOURCES },
                    label = { Text(stringResource(R.string.sources_tab_sources)) },
                )
                FilterChip(
                    selected = tab == SOURCES_TAB_GRAPH,
                    onClick = { tab = SOURCES_TAB_GRAPH },
                    label = { Text(stringResource(R.string.sources_tab_graph)) },
                )
            }
            if (tab == SOURCES_TAB_GRAPH) {
                GraphScreen(graphViewModel, modifier = Modifier.weight(1f))
            } else {
                SourcesContent(
                    sources = sources,
                    documents = documents,
                    extractionByDocument = extractionByDocument,
                    loading = !loaded,
                    deleteError = deleteError,
                    onOpenImport = onOpenImport,
                    onDeleteRequest = { source -> pendingDelete = source.id to source.title },
                    onDismissError = viewModel::clearDeleteError,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val SOURCES_TAB_SOURCES = "sources"
private const val SOURCES_TAB_GRAPH = "graph"

/** 无状态来源内容：供 [SourcesScreen] 与 @Preview / UI 测试直接渲染（B3-7）。 */
@Composable
internal fun SourcesContent(
    sources: List<SourceEntity>,
    documents: List<DocumentEntity>,
    /** B9：documentId → SAG 抽取聚合；缺省不变（Preview 兼容） */
    extractionByDocument: Map<String, ExtractionDocUi> = emptyMap(),
    loading: Boolean = false,
    deleteError: String?,
    onOpenImport: () -> Unit,
    onDeleteRequest: (SourceEntity) -> Unit,
    onDismissError: () -> Unit,
    // 兼容既有 androidTest/preview 调用（B12 起 Scaffold 由 SourcesScreen 统一持有）
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val docsBySource = remember(documents) { documents.groupBy { it.sourceId } }
    val readyCount = remember(documents) {
        documents.count { DocStatus.fromValue(it.status) == DocStatus.READY }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Text(
                stringResource(R.string.sources_summary, sources.size, readyCount, documents.size),
                style = MaterialTheme.typography.bodySmall,
            )
            deleteError?.let {
                ErrorState(
                    message = it,
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = onDismissError,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    item { LoadingState(message = stringResource(R.string.sources_loading)) }
                } else if (sources.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.sources_empty),
                            actionLabel = stringResource(R.string.sources_go_import),
                            onAction = onOpenImport,
                        )
                    }
                }
                items(sources, key = { it.id }) { src ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    src.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(
                                    onClick = { onDeleteRequest(src) },
                                    modifier = Modifier.padding(0.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.sources_delete_title),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            docsBySource[src.id]?.forEach { doc ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("· ${doc.title}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        statusLabel(doc.status),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (DocStatus.fromValue(doc.status) == DocStatus.READY) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                    )

                                    // B9：SAG 抽取状态与导入/文档状态并列展示（失败不影响文档可检索）
                                    Text(
                                        extractionLabel(extractionByDocument[doc.id]),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * B9：逐文档 SAG 抽取状态文案（total 含取消；active 含 queued+running）。
 * - 无任务 → “SAG 未索引”（空 SAG 也给出明确空态）；
 * - 有在途 → “抽取中 done+failed/total”；失败 → “抽取失败 f/total”；全部完成 → “抽取 n/n”。
 */
@Composable
private fun extractionLabel(ui: ExtractionDocUi?): String {
    if (ui == null || !ui.hasJobs) return stringResource(R.string.sources_sag_not_indexed)
    val total = ui.total
    return when {
        ui.active > 0 -> stringResource(R.string.sources_sag_active, ui.done + ui.failed, total)
        ui.done == total -> stringResource(R.string.sources_sag_done, ui.done, total)
        ui.failed > 0 -> stringResource(R.string.sources_sag_failed, ui.failed, total)
        ui.notIndexed > 0 && ui.done == 0 -> stringResource(R.string.sources_sag_not_indexed)
        else -> stringResource(R.string.sources_sag_mixed, ui.done, total, ui.failed)
    }
}

@Composable
private fun statusLabel(status: String): String = when (DocStatus.fromValue(status)) {
    DocStatus.READY -> stringResource(R.string.sources_status_ready)
    DocStatus.PROCESSING -> stringResource(R.string.sources_status_processing)
    DocStatus.FAILED -> stringResource(R.string.sources_status_failed)
    DocStatus.CANCELLED -> stringResource(R.string.sources_status_cancelled)
    DocStatus.PENDING -> stringResource(R.string.sources_status_queued)
    // 未知/历史值：原样展示（B1-5 读取路径兼容）
    null -> status
}

// ───────────────────────── B3-7 @Preview（浅色/深色/空态/错误态） ─────────────────────────

private fun previewSource(id: String, title: String) = SourceEntity(
    id = id,
    title = title,
    uri = "content://demo/$id",
    mimeType = "text/markdown",
    createdAt = 1_700_000_000_000,
    status = "active",
)

private fun previewDocument(
    id: String,
    sourceId: String,
    title: String,
    status: String,
) = DocumentEntity(
    id = id,
    sourceId = sourceId,
    title = title,
    checksum = "preview-checksum",
    parserId = "markdown",
    createdAt = 1_700_000_000_000,
    status = status,
    sizeBytes = 2048,
)

private val previewSources = listOf(
    previewSource("src_1", "蓝牙排查手册"),
    previewSource("src_2", "端侧检索设计笔记"),
)

private val previewDocuments = listOf(
    previewDocument("doc_1", "src_1", "耳机连接问题排查", DocStatus.READY.value),
    previewDocument("doc_2", "src_1", "协议栈重置步骤", DocStatus.PROCESSING.value),
    previewDocument("doc_3", "src_2", "向量检索实现", DocStatus.FAILED.value),
)

@Preview(showBackground = true, name = "Sources · Light")
@Composable
private fun SourcesLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SourcesContent(
            sources = previewSources,
            documents = previewDocuments,
            deleteError = null,
            onOpenImport = {},
            onDeleteRequest = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Sources · Dark")
@Composable
private fun SourcesDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        SourcesContent(
            sources = previewSources,
            documents = previewDocuments,
            deleteError = null,
            onOpenImport = {},
            onDeleteRequest = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Sources · Empty")
@Composable
private fun SourcesEmptyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SourcesContent(
            sources = emptyList(),
            documents = emptyList(),
            deleteError = null,
            onOpenImport = {},
            onDeleteRequest = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Sources · Error")
@Composable
private fun SourcesErrorPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SourcesContent(
            sources = previewSources,
            documents = previewDocuments,
            deleteError = "删除失败：数据库被占用",
            onOpenImport = {},
            onDeleteRequest = {},
            onDismissError = {},
        )
    }
}
