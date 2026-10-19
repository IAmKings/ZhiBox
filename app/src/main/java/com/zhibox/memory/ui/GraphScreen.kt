package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * B12 来源图谱（含事件详情覆盖层）。
 *
 * - 数据/分页/上限逻辑在 [GraphViewModel]（每页 50、总渲染 ≤200、source 过滤）；
 * - 本文件只有无状态 [GraphContent] / [EventDetailContent]（可 @Preview / UI 测试）；
 * - 详情覆盖层：点击事件卡打开，系统返回键先关闭详情。
 */
@Composable
fun GraphScreen(
    viewModel: GraphViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
    }

    val detail = state.selectedEvent
    if (detail != null) {
        BackHandler { viewModel.closeEvent() }
        GraphDetailScreen(
            detail = detail,
            onBack = viewModel::closeEvent,
            embedded = true,
            onOpenRelated = viewModel::openRelatedEvent,
        )
        return
    }

    GraphContent(
        state = state,
        onSelectSource = viewModel::selectSource,
        onLoadMore = viewModel::loadMore,
        onOpenEvent = viewModel::openEvent,
        onRetry = viewModel::retry,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/** 无状态图谱内容（source 过滤 chips + 事件分页列表 + 空态/错误）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraphContent(
    state: GraphUiState,
    onSelectSource: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onOpenEvent: (GraphEventUi) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let {
            ErrorState(
                message = it,
                actionLabel = stringResource(R.string.graph_retry),
                onAction = onRetry,
            )
            OutlinedButton(onClick = onDismissError) {
                Text(stringResource(R.string.action_dismiss))
            }
        }

        // ---- 来源过滤 chips（全部 + active sources）----
        if (state.sources.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.sources, key = { it.id ?: "__all__" }) { filter ->
                    FilterChip(
                        selected = state.selectedSourceId == filter.id,
                        onClick = { onSelectSource(filter.id) },
                        enabled = !state.loading && !state.loadingMore,
                        label = { Text(filter.title) },
                    )
                }
            }
        }

        Text(
            stringResource(R.string.graph_summary, state.totalCount, state.events.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            LoadingState()
        } else if (state.events.isEmpty() && state.error == null) {
            EmptyState(
                title = stringResource(R.string.graph_empty_title),
                message = stringResource(R.string.graph_empty_message),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.events, key = { it.id }) { event ->
                    GraphEventCard(event = event, onClick = { onOpenEvent(event) })
                }
                if (state.loadingMore) {
                    item(key = "__loading_more__") {
                        LoadingState(message = stringResource(R.string.graph_loading_more))
                    }
                } else if (state.hasMore) {
                    item(key = "__load_more__") {
                        OutlinedButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.graph_load_more))
                        }
                    }
                }
            }
        }
        if (state.detailLoading) {
            LoadingState()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphEventCard(event: GraphEventUi, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (event.summary.isNotBlank()) {
                Text(event.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            if (event.sourceTitle.isNotBlank() || event.documentTitle.isNotBlank()) {
                Text(
                    stringResource(
                        R.string.graph_source_doc,
                        event.sourceTitle.ifBlank { "—" },
                        event.documentTitle.ifBlank { "—" },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.entityNames.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    event.entityNames.forEach { name ->
                        AssistChip(
                            onClick = onClick,
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    if (event.entityTotal > event.entityNames.size) {
                        Text(
                            stringResource(
                                R.string.graph_entity_more,
                                event.entityTotal - event.entityNames.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 事件详情覆盖层（不依赖脚手架：标题+摘要+实体+owning chunk 原文）。 */
@Composable
fun GraphDetailScreen(
    detail: GraphEventDetailUi,
    onBack: () -> Unit,
    embedded: Boolean = false,
    onOpenRelated: (Long) -> Unit = {},
) {
    if (embedded) {
        // B12：SourcesScreen 已有 Scaffold；详情作为内容层渲染，避免双层 TopBar。
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.action_back))
                }
            }
            GraphDetailBody(
                detail = detail,
                modifier = Modifier.weight(1f),
                onOpenRelated = onOpenRelated,
            )
        }
        return
    }
    ZhiBoxScreen(
        title = stringResource(R.string.graph_event_detail_title),
        onBack = onBack,
    ) { padding ->
        GraphDetailBody(
            detail = detail,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onOpenRelated = onOpenRelated,
        )
    }
}

@Composable
private fun GraphDetailBody(
    detail: GraphEventDetailUi,
    modifier: Modifier = Modifier,
    onOpenRelated: (Long) -> Unit = {},
) {
        Column(
            modifier = modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                detail.event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                detail.event.summary.ifBlank { stringResource(R.string.graph_detail_summary_empty) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.graph_source_doc,
                    detail.event.sourceTitle.ifBlank { "—" },
                    detail.event.documentTitle.ifBlank { "—" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.graph_detail_entities, detail.entities.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (detail.entities.isEmpty()) {
                Text(
                    stringResource(R.string.graph_detail_summary_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                detail.entities.forEach { entity ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(
                                    R.string.graph_detail_entity_line,
                                    entity.name,
                                    entity.type ?: stringResource(R.string.graph_detail_entity_unknown_type),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (entity.description.isNotBlank()) {
                                Text(
                                    entity.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // B12：entity 共享连接 —— 相关事件（点击可跳转详情）
            HorizontalDivider()
            Text(
                stringResource(
                    R.string.graph_detail_related_events,
                    detail.relatedEvents.size,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (detail.relatedEvents.isEmpty()) {
                Text(
                    stringResource(R.string.graph_detail_no_related),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detail.relatedEvents.forEach { related ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRelated(related.id) },
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                related.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(
                                    R.string.graph_detail_shared_entity,
                                    related.sharedEntityName,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.graph_detail_content),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val content = detail.chunkContent
            if (content.isNullOrBlank()) {
                Text(
                    stringResource(R.string.graph_detail_no_content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                detail.chunkOrdinal?.let { ordinal ->
                    Text(
                        stringResource(
                            R.string.graph_detail_location,
                            ordinal,
                            detail.charStart ?: 0,
                            detail.charEnd ?: 0,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        content,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
}

// ───────────────────────── B12 @Preview ─────────────────────────

private val previewGraphEvent = GraphEventUi(
    id = 1,
    chunkId = 10,
    title = "蓝牙耳机连接问题排查",
    summary = "断开通常由协议栈重置或省电策略触发；可关闭省电优化后重试。",
    sourceId = "src_1",
    sourceTitle = "产品手册",
    documentId = "doc_1",
    documentTitle = "蓝牙使用指南",
    entityNames = listOf("蓝牙耳机", "协议栈", "省电策略"),
    entityTotal = 5,
)

private val previewGraphState = GraphUiState(
    sources = listOf(
        GraphSourceFilter(id = null, title = "全部来源"),
        GraphSourceFilter(id = "src_1", title = "产品手册"),
    ),
    events = listOf(previewGraphEvent),
    totalCount = 1,
    hasMore = false,
)

@Preview(showBackground = true, name = "Graph · Light")
@Composable
private fun GraphLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        GraphContent(
            state = previewGraphState,
            onSelectSource = {},
            onLoadMore = {},
            onOpenEvent = {},
            onRetry = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Graph · Dark empty")
@Composable
private fun GraphDarkEmptyPreview() {
    ZhiBoxTheme(darkTheme = true) {
        GraphContent(
            state = GraphUiState(sources = previewGraphState.sources),
            onSelectSource = {},
            onLoadMore = {},
            onOpenEvent = {},
            onRetry = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Graph · Event detail")
@Composable
private fun GraphDetailPreview() {
    ZhiBoxTheme(darkTheme = false) {
        GraphDetailScreen(
            detail = GraphEventDetailUi(
                event = previewGraphEvent,
                entities = listOf(
                    GraphEntityUi(1, "蓝牙耳机", "product", "待配对设备"),
                    GraphEntityUi(2, "协议栈", "concept", "系统蓝牙协议实现"),
                ),
                chunkContent = "先关闭耳机电源再长按配对键 5 秒，进入可发现状态后重新配对。",
                chunkOrdinal = 2,
                charStart = 300,
                charEnd = 420,
                relatedEvents = listOf(
                    GraphRelatedEventUi(
                        id = 2,
                        title = "省电策略导致蓝牙断连",
                        sharedEntityName = "蓝牙耳机",
                    ),
                    GraphRelatedEventUi(
                        id = 3,
                        title = "协议栈重置排查",
                        sharedEntityName = "协议栈",
                    ),
                ),
            ),
            onBack = {},
        )
    }
}
