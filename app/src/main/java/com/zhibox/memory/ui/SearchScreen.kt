package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.Locale

/**
 * 检索屏（P1-8 + B3-2/B3-5）：状态在 [SearchViewModel]；证据详情为**本屏内覆盖展示**，
 * 打开/返回证据页不再使检索屏离开组合，query/结果/滚动状态全部保留。
 *
 * UI 拆为无状态 [SearchContent]（B3-5 统一 Loading/Empty/Error 组件 + B3-7 Preview 可直接渲染），
 * 状态收集、证据覆盖层与系统返回键处理留在 [SearchScreen]。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(),
) {
    val query by viewModel.query.collectAsState()
    val running by viewModel.running.collectAsState()
    val loadingModel by viewModel.loadingModel.collectAsState()
    val result by viewModel.result.collectAsState()
    val selectedHit by viewModel.selectedHit.collectAsState()

    // 证据详情覆盖层：系统返回键先关闭证据页（而不是退出检索屏）。
    // 选中项在 ViewModel 中：旋转/进程重建后仍停留在证据页（P1-8）。
    val hit = selectedHit
    if (hit != null) {
        BackHandler { viewModel.clearSelectedHit() }
        EvidenceScreen(hit = hit, onBack = { viewModel.clearSelectedHit() })
        return
    }

    SearchContent(
        query = query,
        running = running,
        loadingModel = loadingModel,
        result = result,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::search,
        onBack = onBack,
        onHitClick = viewModel::selectHit,
        onDismissError = viewModel::retryAfterError,
    )
}

/** 无状态检索内容：供 [SearchScreen] 与 @Preview / UI 测试直接渲染（B3-7）。 */
@Composable
internal fun SearchContent(
    query: String,
    running: Boolean,
    loadingModel: Boolean,
    result: SearchService.SearchResult?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onHitClick: (SearchService.SearchHit) -> Unit,
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
                .padding(16.dp),
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

            result?.let { r ->
                if (r.refused != null) {
                    ErrorState(
                        title = stringResource(R.string.search_refused_title),
                        message = r.refused,
                        hint = stringResource(R.string.search_refused_hint),
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissError,
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.search_summary,
                            r.hits.size,
                            r.elapsedMs,
                            r.vectorCandidates,
                            r.keywordCandidates,
                            r.modelId ?: r.spaceId ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (r.hits.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.search_empty_title),
                            message = stringResource(R.string.search_empty_message),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(r.hits) { h ->
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
                                                "[${h.channels}]",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
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
                                                String.format(Locale.ROOT, "%.4f", h.fusedScore),
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

// ───────────────────────── B3-7 @Preview（浅色/深色/空态/错误态/首查加载） ─────────────────────────

private fun previewHit(
    chunkId: Long,
    title: String,
    content: String,
    ordinal: Int = 0,
    chars: IntRange = 0..120,
    score: Double = 0.82,
) = SearchService.SearchHit(
    chunkId = chunkId,
    documentId = "doc_$chunkId",
    ordinal = ordinal,
    content = content,
    charStart = chars.first,
    charEnd = chars.last,
    docTitle = title,
    sourceTitle = "示例来源",
    sourceUri = "content://demo/doc_$chunkId",
    vectorRank = 1,
    keywordRank = 2,
    fusedScore = score,
    channels = "向量+关键词",
)

private fun previewResult(hits: List<SearchService.SearchHit>) = SearchService.SearchResult(
    query = "蓝牙断开怎么恢复",
    spaceId = "mp-universal/egemma2-440m/app-prefix-v1@abc123def456",
    providerId = "mp-universal",
    modelId = "egemma2-440m",
    vectorCandidates = 2,
    keywordCandidates = 3,
    elapsedMs = 842,
    hits = hits,
    refused = null,
)

private val previewHits = listOf(
    previewHit(1, "蓝牙耳机连接问题排查", "先关闭耳机电源再长按配对键 5 秒，进入可发现状态后重新配对。", 2, 300..420),
    previewHit(2, "安卓蓝牙协议栈说明", "断开通常由协议栈重置或省电策略触发，可关闭省电优化后重试。", 5, 120..260, 0.71),
)

@Preview(showBackground = true, name = "Search · Light")
@Composable
private fun SearchLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = false,
            loadingModel = false,
            result = previewResult(previewHits),
            onQueryChange = {},
            onSearch = {},
            onBack = {},
            onHitClick = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Search · Dark")
@Composable
private fun SearchDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        SearchContent(
            query = "",
            running = false,
            loadingModel = false,
            result = null,
            onQueryChange = {},
            onSearch = {},
            onBack = {},
            onHitClick = {},
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
            result = previewResult(emptyList()),
            onQueryChange = {},
            onSearch = {},
            onBack = {},
            onHitClick = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true, name = "Search · Error")
@Composable
private fun SearchErrorPreview() {
    ZhiBoxTheme(darkTheme = false) {
        SearchContent(
            query = "蓝牙断开怎么恢复",
            running = false,
            loadingModel = false,
            result = SearchService.SearchResult(
                query = "蓝牙断开怎么恢复",
                spaceId = null,
                providerId = null,
                modelId = null,
                vectorCandidates = 0,
                keywordCandidates = 0,
                elapsedMs = 0,
                hits = emptyList(),
                refused = "未找到可用模型（filesDir/models）——请先下载或投放模型",
            ),
            onQueryChange = {},
            onSearch = {},
            onBack = {},
            onHitClick = {},
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
            result = null,
            onQueryChange = {},
            onSearch = {},
            onBack = {},
            onHitClick = {},
            onDismissError = {},
        )
    }
}
