package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.tan
import com.zhibox.memory.R
import com.zhibox.memory.core.graph.ExploreGraph
import com.zhibox.memory.core.graph.ExploreNode
import com.zhibox.memory.core.graph.ExploreNodeKind
import com.zhibox.memory.core.graph.ExploreProjection
import com.zhibox.memory.core.graph.Vec3
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

    // P1.2：实体别名管理对话框（仅记录 entityId；实体内容从 selectedEvent 实时取，增删后自动刷新）
    var aliasEntityId by remember { mutableStateOf<Long?>(null) }
    val aliasEntity = aliasEntityId?.let { id ->
        state.selectedEvent?.entities?.firstOrNull { it.id == id }
    }
    if (aliasEntityId != null && aliasEntity != null) {
        AliasManageDialog(
            entity = aliasEntity,
            saving = state.aliasSaving,
            message = state.aliasMessage,
            error = state.aliasError,
            onAdd = { alias -> viewModel.addEntityAlias(aliasEntity.id, alias) },
            onRemove = { alias -> viewModel.removeEntityAlias(aliasEntity.id, alias) },
            onDismiss = {
                if (!state.aliasSaving) {
                    aliasEntityId = null
                    viewModel.clearAliasFeedback()
                }
            },
        )
    }

    val detail = state.selectedEvent
    if (detail != null) {
        BackHandler { viewModel.closeEvent() }
        GraphDetailScreen(
            detail = detail,
            onBack = viewModel::closeEvent,
            embedded = true,
            onOpenRelated = viewModel::openRelatedEvent,
            onManageAliases = { entityId -> aliasEntityId = entityId },
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
        onToggleExplore = viewModel::toggleExplore,
        onExploreQueryChange = viewModel::setExploreQuery,
        onLoadExplore = viewModel::loadExplore,
        onSelectExploreNode = viewModel::selectExploreNode,
        onOpenEventById = viewModel::openEventById,
        onToggleExploreAutoRotate = viewModel::setExploreAutoRotate,
        onPrevExplorePage = viewModel::prevExplorePage,
        onNextExplorePage = viewModel::nextExplorePage,
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
    // P1.3：3D 探索（带默认值兼容既有 Preview/测试）
    onToggleExplore: () -> Unit = {},
    onExploreQueryChange: (String) -> Unit = {},
    onLoadExplore: (Boolean) -> Unit = {},
    onSelectExploreNode: (String?) -> Unit = {},
    onOpenEventById: (Long) -> Unit = {},
    onToggleExploreAutoRotate: (Boolean) -> Unit = {},
    onPrevExplorePage: () -> Unit = {},
    onNextExplorePage: () -> Unit = {},
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

        // ---- P1.3：列表 / 3D 探索切换 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.exploreMode,
                onClick = { if (state.exploreMode) onToggleExplore() },
                label = { Text(stringResource(R.string.graph_mode_list)) },
            )
            FilterChip(
                selected = state.exploreMode,
                onClick = { if (!state.exploreMode) onToggleExplore() },
                label = { Text(stringResource(R.string.graph_mode_explore)) },
            )
        }

        // ---- 来源过滤 chips（全部 + active sources；两种模式共用）----
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

        if (state.exploreMode) {
            ExploreSection(
                state = state,
                onLoadExplore = onLoadExplore,
                onExploreQueryChange = onExploreQueryChange,
                onSelectExploreNode = onSelectExploreNode,
                onOpenEventById = onOpenEventById,
                onToggleAutoRotate = onToggleExploreAutoRotate,
                onSwitchToList = onToggleExplore,
                onPrevExplorePage = onPrevExplorePage,
                onNextExplorePage = onNextExplorePage,
                modifier = Modifier.weight(1f),
            )
        } else {
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
        }

        if (state.detailLoading) {
            LoadingState()
        }
    }
}

// ───────────────────────── P1.3：3D 探索 ─────────────────────────

/**
 * P1.3 探索区：关键词筛选（防抖）、自动旋转开关、3D Canvas、选中节点信息与列表替代入口。
 *
 * 列表模式由顶部「列表 / 3D 探索」切换保证可访问性；本区在空/错误/选中实体时也提供
 * 「查看列表模式」入口。
 */
@Composable
private fun ExploreSection(
    state: GraphUiState,
    onLoadExplore: (Boolean) -> Unit,
    onExploreQueryChange: (String) -> Unit,
    onSelectExploreNode: (String?) -> Unit,
    onOpenEventById: (Long) -> Unit,
    onToggleAutoRotate: (Boolean) -> Unit,
    onSwitchToList: () -> Unit,
    onPrevExplorePage: () -> Unit,
    onNextExplorePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 关键词防抖：停止输入 400ms 后查询（避免每键一次全量 SQL；进入探索时的首查由 ViewModel 触发）
    var lastRequestedQuery by remember { mutableStateOf(state.exploreQuery) }
    LaunchedEffect(state.exploreQuery) {
        if (state.exploreQuery == lastRequestedQuery) return@LaunchedEffect
        delay(EXPLORE_QUERY_DEBOUNCE_MS)
        lastRequestedQuery = state.exploreQuery
        onLoadExplore(true)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.exploreQuery,
            onValueChange = onExploreQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.graph_explore_search_label)) },
            placeholder = { Text(stringResource(R.string.graph_explore_search_hint)) },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.graph_explore_auto_rotate),
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = state.exploreAutoRotate,
                onCheckedChange = onToggleAutoRotate,
            )
            if (state.exploreLoading) {
                Text(
                    stringResource(R.string.graph_explore_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        val graph = state.exploreGraph
        if (graph == null) {
            if (state.exploreError == null) {
                LoadingState(message = stringResource(R.string.graph_explore_loading))
            }
        } else if (graph.nodes.isEmpty() && graph.totalEvents > 0) {
            // 越界页（例如总数变小后停留在旧页码）：可读提示 + 回到第一页
            Text(
                stringResource(R.string.graph_explore_page_out_of_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { onLoadExplore(true) }) {
                Text(stringResource(R.string.graph_explore_first_page))
            }
        } else if (graph.nodes.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.graph_explore_empty_title),
                message = stringResource(R.string.graph_explore_empty_message),
            )
        } else {
            Text(
                stringResource(
                    R.string.graph_explore_summary,
                    graph.nodes.size,
                    graph.edges.size,
                    graph.eventNodes.size,
                    graph.totalEvents,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (graph.truncated) {
                Text(
                    stringResource(
                        R.string.graph_explore_truncated,
                        graph.totalEvents,
                        graph.maxEvents,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // P1.3b：分页控件（第 X / 共 Y 页 · 共 N 事件；加载中禁用，不无限翻页）
            val pageSize = if (state.explorePageSize > 0) {
                state.explorePageSize
            } else {
                EXPLORE_PAGE_SIZE
            }
            val totalPages = explorePageCount(graph.totalEvents, pageSize)
            val currentPage = explorePageNumber(graph.pageOffset, pageSize)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPrevExplorePage,
                    enabled = !state.exploreLoading && graph.pageOffset > 0,
                ) { Text(stringResource(R.string.graph_explore_prev_page)) }
                Text(
                    stringResource(
                        R.string.graph_explore_page_info,
                        currentPage,
                        totalPages.coerceAtLeast(1),
                        graph.totalEvents,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onNextExplorePage,
                    enabled = !state.exploreLoading && graph.hasMore,
                ) { Text(stringResource(R.string.graph_explore_next_page)) }
            }
            if (graph.hasMore) {
                Text(
                    stringResource(R.string.graph_explore_has_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // P1.3c：连续性能观测（最近/p50/p95/N 次 + 通道；失败保留最近成功值）
            explorePerfUi(state.explorePerf)?.let { perf ->
                Text(
                    stringResource(
                        R.string.graph_explore_perf,
                        perf.lastMs,
                        perf.p50Ms,
                        perf.p95Ms,
                        perf.count,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.graph_explore_perf_channels,
                        when (perf.channel) {
                            ExplorePerfChannel.FTS -> stringResource(R.string.graph_explore_channel_fts)
                            ExplorePerfChannel.LIKE -> stringResource(R.string.graph_explore_channel_like)
                            ExplorePerfChannel.FTS_AND_LIKE ->
                                stringResource(R.string.graph_explore_channel_both)
                            ExplorePerfChannel.NONE ->
                                stringResource(R.string.graph_explore_channel_none)
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExploreCanvas(
                graph = graph,
                autoRotate = state.exploreAutoRotate,
                selectedNodeId = state.exploreSelectedNodeId,
                onSelectNode = onSelectExploreNode,
                a11yDescription = stringResource(
                    R.string.graph_explore_canvas_a11y_paged,
                    currentPage,
                    totalPages.coerceAtLeast(1),
                    graph.eventNodes.size,
                    graph.entityNodes.size,
                    graph.edges.size,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            )
            val selected = graph.nodes.firstOrNull { it.id == state.exploreSelectedNodeId }
            if (selected == null) {
                Text(
                    stringResource(R.string.graph_explore_no_selection),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExploreSelectedCard(
                    node = selected,
                    sourceTitle = state.sources.firstOrNull { it.id == selected.sourceId }?.title,
                    onOpenEventById = onOpenEventById,
                    onSwitchToList = onSwitchToList,
                )
            }
        }

        state.exploreError?.let {
            ErrorState(
                message = it,
                actionLabel = stringResource(R.string.graph_explore_retry),
                onAction = { onLoadExplore(true) },
            )
        }
        // 可访问性/兜底：任何探索状态下都可回到列表模式
        OutlinedButton(onClick = onSwitchToList) {
            Text(stringResource(R.string.graph_explore_list_fallback))
        }
    }
}

/** 拖拽 yaw/pitch、点击 hitTest、近大远小、选中高亮的 3D Canvas（无外部依赖）。 */
@Composable
private fun ExploreCanvas(
    graph: ExploreGraph,
    autoRotate: Boolean,
    selectedNodeId: String?,
    onSelectNode: (String?) -> Unit,
    a11yDescription: String,
    modifier: Modifier = Modifier,
) {
    var yaw by remember { mutableStateOf(0.35) }
    var pitch by remember { mutableStateOf(-0.15) }
    val density = LocalDensity.current
    val eventRadiusPx = with(density) { 7.dp.toPx() }
    val entityRadiusPx = with(density) { 4.5f.dp.toPx() }
    val eventColor = MaterialTheme.colorScheme.primary
    val entityColor = MaterialTheme.colorScheme.tertiary
    val edgeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val selectedColor = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f).toArgb()
    val labelSelectedColor = MaterialTheme.colorScheme.primary.toArgb()
    // 自动旋转（仅 UI 状态，不触发网络/DB）
    LaunchedEffect(autoRotate) {
        if (!autoRotate) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            yaw += EXPLORE_AUTO_ROTATE_STEP
        }
    }
    val projection = remember(yaw, pitch) {
        ExploreProjection(yawRadians = yaw, pitchRadians = pitch)
    }
    Canvas(
        modifier = modifier
            .pointerInput(projection, graph) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    yaw += dragAmount.x * EXPLORE_DRAG_YAW_FACTOR
                    pitch = (pitch - dragAmount.y * EXPLORE_DRAG_PITCH_FACTOR)
                        .coerceIn(-EXPLORE_MAX_PITCH, EXPLORE_MAX_PITCH)
                }
            }
            .pointerInput(projection, graph) {
                detectTapGestures { offset ->
                    val projected = projection.project(
                        nodes = graph.nodes,
                        width = size.width,
                        height = size.height,
                    )
                    val hit = projection.hitTest(projected, offset.x, offset.y)
                    onSelectNode(hit?.node?.id)
                }
            }
            .semantics { contentDescription = a11yDescription },
    ) {
        val projected = projection.project(graph.nodes, size.width.toInt(), size.height.toInt())
        val byId = projected.associateBy { it.node.id }
        // 先画边（低 alpha），再画节点（远→近由 project 排序保证）
        graph.edges.forEach { edge ->
            val from = byId[edge.from] ?: return@forEach
            val to = byId[edge.to] ?: return@forEach
            drawLine(
                color = edgeColor,
                start = Offset(from.screenX, from.screenY),
                end = Offset(to.screenX, to.screenY),
                strokeWidth = 1f,
            )
        }
        // 参考缩放：相机距离 3、fov 60 时位于原点的节点 scale
        // 参考缩放：与 ExploreProjection 默认相机参数一致（cameraDistance=3.0、fov=60°）
        val focal = (size.height / 2.0) / tan(Math.toRadians(30.0))
        val referenceScale = (focal / 3.0).toFloat()
        val radiusById = HashMap<String, Float>(projected.size)
        projected.forEach { item ->
            val isEvent = item.node.kind == ExploreNodeKind.EVENT
            val radius = exploreNodeRadiusPx(
                basePx = if (isEvent) eventRadiusPx else entityRadiusPx,
                scale = item.scale,
                referenceScale = referenceScale,
            )
            radiusById[item.node.id] = radius
            val center = Offset(item.screenX, item.screenY)
            drawCircle(
                color = if (isEvent) eventColor else entityColor,
                radius = radius,
                center = center,
            )
            if (item.node.id == selectedNodeId) {
                drawCircle(
                    color = selectedColor,
                    radius = radius + 3f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }
        }

        // P1.3c：节点文字标签（实际文本 rect + padding 碰撞避让；选中优先，重叠/越界跳过）
        val labelIds = exploreLabelNodeIds(
            projected = projected,
            selectedNodeId = selectedNodeId,
            limit = EXPLORE_LABEL_CANDIDATE_LIMIT,
        ).toHashSet()
        if (labelIds.isNotEmpty()) {
            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                color = labelColor
                textSize = 10.sp.toPx()
            }
            val selectedLabelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                color = labelSelectedColor
                textSize = 11.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val textById = HashMap<String, String>(labelIds.size)
            val candidates = projected
                .filter { it.node.id in labelIds }
                .sortedWith(
                    compareByDescending<ExploreProjection.ProjectedNode> {
                        it.node.id == selectedNodeId
                    }.thenBy { it.depth },
                )
                .mapNotNull { item ->
                    val radius = radiusById[item.node.id] ?: return@mapNotNull null
                    val isSelected = item.node.id == selectedNodeId
                    val paint = if (isSelected) selectedLabelPaint else labelPaint
                    val label = item.node.label
                    val text = if (label.length > EXPLORE_LABEL_MAX_CHARS) {
                        label.take(EXPLORE_LABEL_MAX_CHARS) + "…"
                    } else {
                        label
                    }
                    val metrics = paint.fontMetrics
                    textById[item.node.id] = text
                    ExploreLabelCandidate(
                        nodeId = item.node.id,
                        centerX = item.screenX,
                        baselineY = item.screenY - radius - 4f,
                        width = paint.measureText(text),
                        height = (metrics.descent - metrics.ascent).coerceAtLeast(1f),
                        isSelected = isSelected,
                    )
                }
            val boxes = placeExploreLabels(
                candidates = candidates,
                canvasWidth = size.width,
                canvasHeight = size.height,
                limit = EXPLORE_LABEL_LIMIT,
                paddingPx = EXPLORE_LABEL_PADDING_PX,
            )
            boxes.forEach { box ->
                val text = textById[box.nodeId] ?: return@forEach
                val paint = if (box.nodeId == selectedNodeId) selectedLabelPaint else labelPaint
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    box.centerX,
                    box.bottom,
                    paint,
                )
            }
        }
    }
}

/** 选中节点信息卡：label/subtitle/类型/来源；事件节点可打开详情，实体节点提示去列表模式。 */
@Composable
private fun ExploreSelectedCard(
    node: ExploreNode,
    sourceTitle: String?,
    onOpenEventById: (Long) -> Unit,
    onSwitchToList: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.graph_explore_selected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        if (node.kind == ExploreNodeKind.EVENT) {
                            R.string.graph_explore_kind_event
                        } else {
                            R.string.graph_explore_kind_entity
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (node.subtitle.isNotBlank()) {
                Text(node.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            node.entityType?.takeIf { it.isNotBlank() }?.let { type ->
                Text(
                    stringResource(R.string.graph_detail_entity_line, node.label, type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    R.string.graph_explore_node_source,
                    sourceTitle ?: node.sourceId ?: node.documentId
                        ?: stringResource(R.string.graph_explore_node_source_unknown),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val eventId = node.eventId
            if (node.kind == ExploreNodeKind.EVENT && eventId != null) {
                Button(onClick = { onOpenEventById(eventId) }) {
                    Text(stringResource(R.string.graph_explore_open_event))
                }
            } else {
                Text(
                    stringResource(R.string.graph_explore_entity_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSwitchToList) {
                    Text(stringResource(R.string.graph_explore_list_fallback))
                }
            }
        }
    }
}

private const val EXPLORE_QUERY_DEBOUNCE_MS = 400L
private const val EXPLORE_LABEL_LIMIT = 12
private const val EXPLORE_LABEL_CANDIDATE_LIMIT = 24
private const val EXPLORE_LABEL_PADDING_PX = 2f
private const val EXPLORE_LABEL_MAX_CHARS = 18
private const val EXPLORE_AUTO_ROTATE_STEP = 0.004
private const val EXPLORE_DRAG_YAW_FACTOR = 0.01
private const val EXPLORE_DRAG_PITCH_FACTOR = 0.01
private const val EXPLORE_MAX_PITCH = 1.2

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

/** P1.2：实体别名管理对话框（展示/删除已有别名 + 输入新增；保存中禁用防连点）。 */
@Composable
private fun AliasManageDialog(
    entity: GraphEntityUi,
    saving: Boolean,
    message: String?,
    error: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var aliasInput by remember(entity.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graph_alias_dialog_title, entity.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.graph_alias_section, entity.aliases.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entity.aliases.isEmpty()) {
                    Text(
                        stringResource(R.string.graph_alias_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    entity.aliases.forEach { alias ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(alias, style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = { onRemove(alias) },
                                enabled = !saving,
                            ) { Text(stringResource(R.string.graph_alias_delete)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                    label = { Text(stringResource(R.string.graph_alias_input_label)) },
                )
                if (saving) {
                    Text(
                        stringResource(R.string.graph_alias_saving),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && aliasInput.isNotBlank(),
                onClick = {
                    val value = aliasInput
                    aliasInput = ""
                    onAdd(value)
                },
            ) { Text(stringResource(R.string.graph_alias_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 详情实体行内别名展示上限（其余显示 +N；完整列表在「别名」对话框）。 */
private const val GRAPH_ALIAS_INLINE_LIMIT = 3

/** 事件详情覆盖层（不依赖脚手架：标题+摘要+实体+owning chunk 原文）。 */
@Composable
fun GraphDetailScreen(
    detail: GraphEventDetailUi,
    onBack: () -> Unit,
    embedded: Boolean = false,
    onOpenRelated: (Long) -> Unit = {},
    onManageAliases: (Long) -> Unit = {},
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
                onManageAliases = onManageAliases,
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
            onManageAliases = onManageAliases,
        )
    }
}

@Composable
private fun GraphDetailBody(
    detail: GraphEventDetailUi,
    modifier: Modifier = Modifier,
    onOpenRelated: (Long) -> Unit = {},
    onManageAliases: (Long) -> Unit = {},
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
                            // P1.2：别名行（最多展示 3 个，其余 +N）+ 管理入口
                            if (entity.aliases.isNotEmpty()) {
                                val shown = entity.aliases.take(GRAPH_ALIAS_INLINE_LIMIT)
                                val hidden = entity.aliases.size - shown.size
                                val aliasText = buildString {
                                    append(shown.joinToString("、"))
                                    if (hidden > 0) {
                                        append(" ")
                                        append(stringResource(R.string.graph_alias_more, hidden))
                                    }
                                }
                                Text(
                                    stringResource(R.string.graph_alias_inline, aliasText),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onManageAliases(entity.id) }) {
                                Text(stringResource(R.string.graph_alias_manage_button))
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

@Preview(showBackground = true, name = "Graph · Explore 3D")
@Composable
private fun GraphExplorePreview() {
    val eventNode = ExploreNode(
        id = "e:1",
        kind = ExploreNodeKind.EVENT,
        label = "蓝牙耳机连接问题排查",
        subtitle = "先关闭耳机电源再长按配对键 5 秒重新配对。",
        entityType = null,
        position = Vec3(0.2, 0.3, 0.8),
        eventId = 1,
        sourceId = "src_1",
    )
    val entityNode = ExploreNode(
        id = "t:10",
        kind = ExploreNodeKind.ENTITY,
        label = "蓝牙耳机",
        subtitle = "product",
        entityType = "product",
        position = Vec3(-0.4, -0.2, 0.6),
        entityId = 10,
    )
    ZhiBoxTheme(darkTheme = false) {
        GraphContent(
            state = previewGraphState.copy(
                exploreMode = true,
                exploreGraph = ExploreGraph(
                    nodes = listOf(eventNode, entityNode),
                    edges = listOf(com.zhibox.memory.core.graph.ExploreEdge("e:1", "t:10")),
                    truncated = false,
                    totalEvents = 1,
                    maxEvents = 200,
                ),
                exploreSelectedNodeId = "e:1",
            ),
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
                    GraphEntityUi(
                        1,
                        "蓝牙耳机",
                        "product",
                        "待配对设备",
                        aliases = listOf("BT 耳机", "earbuds", "TWS"),
                    ),
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
