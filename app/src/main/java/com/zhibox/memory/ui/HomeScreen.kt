package com.zhibox.memory.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.BrandSerif
import com.zhibox.memory.ui.theme.ZhiBoxExtendedColors
import com.zhibox.memory.ui.theme.ZhiBoxRadius
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import com.zhibox.memory.ui.theme.zhiBoxColors

/**
 * 首页（UI 改版）：暖纸时间线 + 底部悬浮输入岛。
 *
 * 设计基准：
 * - 布局/组件尺寸：`design/main_screen/code.html`（`screen.png` 的实际渲染源）；
 * - 品牌色/字体：根目录 `DESIGN.md`；
 * - 卡片数据：真实 Room 数据（文档 + 活跃来源 + 每文档首块摘要），见 [HomeViewModel]。
 *
 * 路由映射（设计稿只有「搜索 / 更多」两个图标 + 悬浮输入岛，这里把既有五个入口收进去）：
 * - 顶栏搜索 → 检索屏（携带输入岛里的检索词，可为空）；
 * - 顶栏更多 → 下拉菜单：来源 / 带引用问答 / 设置 /（debug）实验·诊断；
 * - 输入岛左键 → 导入屏；输入岛提交 → 检索屏（**暂未接入「笔记落库」**，先复用检索，
 *   等本地笔记存储落地后再换成快速捕获）；
 * - 药丸「图谱」→ 来源屏（图谱 tab 在来源屏内）。
 *
 * 与参考稿的刻意偏差（可访问性/规范）：
 * 1. 顶栏与输入岛按钮的**触控目标**统一 48dp（参考稿 p-1 / 44dp），图标视觉尺寸不变；
 * 2. 时间戳/元信息用 `inkMeta`(#736B63) 而非参考稿 #9E948A（2.98:1 不达 AA）；
 * 3. 徽标文字统一压暗到 ≥4.5:1（见 Color.kt 注释）。
 */
@Composable
fun HomeScreen(
    onOpenSearch: (query: String) -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** 仅 debug 构建传入；release 为 null 时不渲染实验入口（P0-3 实验隔离）。 */
    onOpenDiagnostics: (() -> Unit)? = null,
    onOpenDocument: (documentId: String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 输入框内容属于用户输入：rememberSaveable 保证旋转/进程重建后不丢
    var draft by rememberSaveable { mutableStateOf("") }

    HomeContent(
        state = state,
        draft = draft,
        onDraftChange = { draft = it },
        onSelectFilter = viewModel::selectFilter,
        onRetry = viewModel::retry,
        onOpenGraph = onOpenSources,
        onSubmit = {
            onOpenSearch(draft.trim())
            draft = ""
        },
        onOpenImport = onOpenImport,
        onOpenChat = onOpenChat,
        onOpenSources = onOpenSources,
        onOpenSettings = onOpenSettings,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenDocument = onOpenDocument,
    )
}

/** 无状态内容层：所有数据来自 [HomeUiState]，所有意图通过 lambda 上抛。 */
@Composable
private fun HomeContent(
    state: HomeUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSelectFilter: (HomeFilter) -> Unit,
    onRetry: () -> Unit,
    onOpenGraph: () -> Unit,
    onSubmit: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.zhiBoxColors
    val filter = when (state) {
        is HomeUiState.Loaded -> state.filter
        else -> HomeFilter.ALL
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        Column(
            // 顺序要紧：先 widthIn 再 fillMaxSize，否则 fillMaxSize 把 minWidth 顶满、
            // widthIn 失效（平板/折叠屏上内容会被拉满整屏）
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = MaxContentWidth)
                .fillMaxSize(),
        ) {
            HomeTopBar(
                onOpenSearch = { onSubmit() },
                onOpenChat = onOpenChat,
                onOpenSources = onOpenSources,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
            )
            HomeFilterRow(
                filter = filter,
                onSelect = onSelectFilter,
                onOpenGraph = onOpenGraph,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (state) {
                    HomeUiState.Loading -> HomeLoading()
                    is HomeUiState.Error -> HomeError(state.message, onRetry = onRetry)
                    is HomeUiState.Loaded -> HomeTimeline(
                        state = state,
                        onOpenDocument = onOpenDocument,
                        onOpenImport = onOpenImport,
                    )
                }
            }
        }

        HomeOmniBar(
            draft = draft,
            onDraftChange = onDraftChange,
            onSubmit = onSubmit,
            onOpenImport = onOpenImport,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = MaxContentWidth),
        )
    }
}

// ── 顶栏 ────────────────────────────────────────────────────────────

@Composable
private fun HomeTopBar(
    onOpenSearch: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
) {
    val colors = MaterialTheme.zhiBoxColors
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：端侧离线就绪指示（呼吸绿点 + ping 扩散）
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            OfflineReadyDot()
        }

        // 中：品牌锁版（中文用系统 serif 复现参考稿宋体观感，拉丁用 Plus Jakarta Sans）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_brand_title),
                fontFamily = BrandSerif,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.home_brand_wordmark),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.2.sp,
                color = colors.inkMeta,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // 右：搜索 + 更多（48dp 触控目标）
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = ZhiBoxIcons.Search,
                    contentDescription = stringResource(R.string.home_action_search),
                    modifier = Modifier.size(20.dp),
                    tint = colors.ink,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = ZhiBoxIcons.More,
                        contentDescription = stringResource(R.string.home_action_more),
                        modifier = Modifier.size(20.dp),
                        tint = colors.ink,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    HomeMenuItem(R.string.home_more_sources) {
                        menuOpen = false
                        onOpenSources()
                    }
                    HomeMenuItem(R.string.home_more_chat) {
                        menuOpen = false
                        onOpenChat()
                    }
                    HomeMenuItem(R.string.home_more_settings) {
                        menuOpen = false
                        onOpenSettings()
                    }
                    // P0-3：release 构建不渲染实验/诊断入口
                    onOpenDiagnostics?.let { open ->
                        HomeMenuItem(R.string.home_more_diagnostics) {
                            menuOpen = false
                            open()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMenuItem(labelRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
    )
}

/** 呼吸绿状态点：外层 ping 扩散 + 内层实心点（对应参考稿 animate-ping）。 */
@Composable
private fun OfflineReadyDot() {
    val description = stringResource(R.string.home_status_offline_ready)
    val transition = rememberInfiniteTransition(label = "offline-ping")
    val pingScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_800), RepeatMode.Restart),
        label = "ping-scale",
    )
    val pingAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_800), RepeatMode.Restart),
        label = "ping-alpha",
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = pingScale
                    scaleY = pingScale
                    alpha = pingAlpha
                }
                .background(MaterialTheme.colorScheme.secondary, CircleShape),
        )
        Box(
            Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.secondary, CircleShape),
        )
    }
}

// ── 分类药丸 ────────────────────────────────────────────────────────

@Composable
private fun HomeFilterRow(
    filter: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onOpenGraph: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(HomeFilter.entries, key = { it.name }) { item ->
            FilterPill(
                label = stringResource(item.labelRes),
                icon = item.pillIcon(),
                selected = item == filter,
                onClick = { onSelect(item) },
            )
        }
        // 「图谱」是入口而非过滤（Design 里与分类同排，视觉一致）
        item(key = "graph") {
            FilterPill(
                label = stringResource(R.string.home_filter_graph),
                icon = ZhiBoxIcons.Graph,
                selected = false,
                onClick = onOpenGraph,
            )
        }
    }
}

private fun HomeFilter.pillIcon(): ImageVector = when (this) {
    HomeFilter.ALL -> ZhiBoxIcons.Grid
    HomeFilter.NOTE -> ZhiBoxIcons.Pencil
    HomeFilter.IMAGE -> ZhiBoxIcons.Image
    HomeFilter.DOCUMENT -> ZhiBoxIcons.Document
}

@Composable
private fun FilterPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.zhiBoxColors
    val container = if (selected) MaterialTheme.colorScheme.primary else colors.pillInactive
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else colors.pillInactiveText
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = content,
        )
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
    }
}

// ── 时间线 ──────────────────────────────────────────────────────────

@Composable
private fun HomeTimeline(
    state: HomeUiState.Loaded,
    onOpenDocument: (String) -> Unit,
    onOpenImport: () -> Unit,
) {
    val colors = MaterialTheme.zhiBoxColors
    if (state.shownCount == 0) {
        // 区分「空库」与「当前分类为空」：两者的下一步动作不同
        val libraryEmpty = state.totalCount == 0
        EmptyState(
            title = stringResource(
                if (libraryEmpty) R.string.home_empty_title else R.string.home_filter_empty_title,
            ),
            message = stringResource(
                if (libraryEmpty) R.string.home_empty_message
                else R.string.home_filter_empty_message,
            ),
            actionLabel = if (libraryEmpty) stringResource(R.string.home_go_import) else null,
            onAction = if (libraryEmpty) onOpenImport else null,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 132.dp),
    ) {
        state.groups.forEachIndexed { index, group ->
            item(key = "group-${group.label.name}") {
                TimelineGroupHeader(
                    label = stringResource(group.label.labelRes),
                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 24.dp),
                )
            }
            items(group.items, key = { it.id }) { card ->
                HomeCard(
                    item = card,
                    typeLabel = stringResource(card.category.typeLabelRes),
                    tagLabel = stringResource(card.category.tagRes),
                    onClick = { onOpenDocument(card.id) },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        item(key = "footnote") {
            Text(
                text = stringResource(R.string.home_footnote),
                fontSize = 12.sp,
                color = colors.inkMeta,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
            )
        }
    }
}

@Composable
private fun TimelineGroupHeader(label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.zhiBoxColors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colors.inkMeta,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(colors.divider))
    }
}

@Composable
private fun HomeCard(
    item: HomeCardItem,
    typeLabel: String,
    tagLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.zhiBoxColors
    val shape = RoundedCornerShape(ZhiBoxRadius.card)
    val badge = colors.badgeFor(item.category)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = colors.shadow,
                spotColor = colors.shadow,
            )
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                // 左：内容徽标（56dp 圆角方块，图形 + 类型标签）
                Badge(category = item.category, label = typeLabel, colors = badge)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    // 元信息：时间 · 类型 ·（非 ready 时的状态）
                    val statusText = item.status?.let { statusLabel(it) }
                    val meta = if (statusText == null) {
                        stringResource(R.string.home_meta_line, item.timeLabel, typeLabel)
                    } else {
                        stringResource(
                            R.string.home_meta_line_status,
                            item.timeLabel,
                            typeLabel,
                            statusText,
                        )
                    }
                    Text(
                        text = meta,
                        fontSize = 11.sp,
                        color = colors.inkMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                        letterSpacing = (-0.1).sp,
                        lineHeight = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (item.preview.isNotBlank()) {
                        Text(
                            text = item.preview,
                            fontSize = 13.sp,
                            color = colors.inkSecondary,
                            lineHeight = 19.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // 页脚：上发丝线 + 来源 + 分类标签
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(1.dp)
                    .background(colors.divider.copy(alpha = 0.6f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.sourceLabel,
                    fontSize = 11.sp,
                    color = colors.inkMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TagPill(label = tagLabel, colors = badge)
            }
        }
    }
}

@Composable
private fun Badge(
    category: HomeCategory,
    label: String,
    colors: BadgeColors,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(ZhiBoxRadius.badge))
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = category.badgeIcon(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.content,
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TagPill(label: String, colors: BadgeColors) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = colors.tagContent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.tagContainer)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

private fun HomeCategory.badgeIcon(): ImageVector = when (this) {
    HomeCategory.NOTE -> ZhiBoxIcons.Pencil
    HomeCategory.IMAGE -> ZhiBoxIcons.Image
    HomeCategory.DOCUMENT -> ZhiBoxIcons.Book
    HomeCategory.SNAPSHOT -> ZhiBoxIcons.Snapshot
}

// ── 悬浮输入岛 ──────────────────────────────────────────────────────

@Composable
private fun HomeOmniBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.zhiBoxColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(ZhiBoxRadius.omniBar),
            color = colors.omniBar,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 附件 → 导入屏
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(ZhiBoxRadius.attachment))
                        .background(colors.paper)
                        .clickable(onClick = onOpenImport),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ZhiBoxIcons.Attachment,
                        contentDescription = stringResource(R.string.home_omni_attachment),
                        modifier = Modifier.size(20.dp),
                        tint = colors.ink,
                    )
                }

                // 输入区（含占位与清空）
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 13.sp,
                            color = colors.ink,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.home_omni_placeholder),
                                        fontSize = 13.sp,
                                        color = colors.inkMeta,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (draft.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable { onDraftChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = ZhiBoxIcons.Clear,
                                contentDescription = stringResource(R.string.home_omni_clear),
                                modifier = Modifier.size(16.dp),
                                tint = colors.inkFaint,
                            )
                        }
                    }
                }

                // 提交（48dp 圆钮，参考稿 44dp）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ZhiBoxIcons.ArrowUp,
                        contentDescription = stringResource(R.string.home_omni_send),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

// ── 状态占位 ────────────────────────────────────────────────────────

@Composable
private fun HomeLoading() {
    LoadingState(
        message = stringResource(R.string.home_loading),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
    )
}

@Composable
private fun HomeError(message: String, onRetry: () -> Unit) {
    ErrorState(
        message = message,
        title = stringResource(R.string.home_error_title),
        actionLabel = stringResource(R.string.action_retry),
        onAction = onRetry,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    )
}

// ── 主题映射辅助 ────────────────────────────────────────────────────

private class BadgeColors(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val tagContainer: androidx.compose.ui.graphics.Color,
    val tagContent: androidx.compose.ui.graphics.Color,
)

private fun ZhiBoxExtendedColors.badgeFor(category: HomeCategory): BadgeColors = when (category) {
    HomeCategory.NOTE -> BadgeColors(
        container = badgeNoteContainer,
        content = badgeNoteContent,
        tagContainer = tagInspirationContainer,
        tagContent = tagInspirationContent,
    )
    HomeCategory.DOCUMENT -> BadgeColors(
        container = badgeKnowledgeContainer,
        content = badgeKnowledgeContent,
        tagContainer = tagLocalContainer,
        tagContent = tagLocalContent,
    )
    HomeCategory.IMAGE -> BadgeColors(
        container = badgeInsightContainer,
        content = badgeInsightContent,
        tagContainer = tagConfidenceContainer,
        tagContent = tagConfidenceContent,
    )
    HomeCategory.SNAPSHOT -> BadgeColors(
        container = badgeSnapshotContainer,
        content = badgeSnapshotContent,
        tagContainer = tagArchiveContainer,
        tagContent = tagArchiveContent,
    )
}

/** 卡片阴影近似参考稿 `0 4px 20px -2px rgba(45,35,25,0.03)` 的暖色低透明度。 */
private val ZhiBoxExtendedColors.shadow: androidx.compose.ui.graphics.Color
    get() = androidx.compose.ui.graphics.Color(0x142D2319)

@Composable
private fun statusLabel(status: HomeStatus): String = stringResource(status.labelRes)

/** 大屏时内容宽度收敛，保持手机端阅读比例（DESIGN.md Fluid Grid / max-w-md）。 */
private val MaxContentWidth = 448.dp

// ── 预览 ────────────────────────────────────────────────────────────

private object FakeHome {
    val loaded = HomeUiState.Loaded(
        filter = HomeFilter.ALL,
        groups = listOf(
            HomeTimelineGroup(
                label = HomeGroup.TODAY,
                items = listOf(
                    HomeCardItem(
                        id = "1",
                        createdAt = 0L,
                        category = HomeCategory.NOTE,
                        timeLabel = "5/7 19:05",
                        title = "随手记下灵感与待整理内容",
                        preview = "这是一条用于记录临时灵感和需要后续整理的待办内容的笔记，来源为文本…",
                        sourceLabel = "Obsidian",
                        status = null,
                    ),
                    HomeCardItem(
                        id = "2",
                        createdAt = 0L,
                        category = HomeCategory.DOCUMENT,
                        timeLabel = "5/7 16:40",
                        title = "2026_Q3_离线研发技术规格",
                        preview = "4-bit 端侧量化模型常驻 RAM 仅 280MB，142 篇离线索引已就绪，已…",
                        sourceLabel = "SAF 直读 · PDF",
                        status = null,
                    ),
                    HomeCardItem(
                        id = "3",
                        createdAt = 0L,
                        category = HomeCategory.DOCUMENT,
                        timeLabel = "5/7 14:20",
                        title = "“NPU 向量加速与飞行模式下问答表现”",
                        preview = "基于端侧 RAG + Agent 实体链，完全切断外网连接时余弦召回精度达…",
                        sourceLabel = "引用 [S1·PRD_ZhiBox.md]",
                        status = HomeStatus.PROCESSING,
                    ),
                ),
            ),
            HomeTimelineGroup(
                label = HomeGroup.YESTERDAY,
                items = listOf(
                    HomeCardItem(
                        id = "4",
                        createdAt = 0L,
                        category = HomeCategory.SNAPSHOT,
                        timeLabel = "5/6 21:15",
                        title = "Android Jetpack Compose 离线知识拓扑",
                        preview = "离线保存渲染树状态管线与轻量重绘…",
                        sourceLabel = "本地 HTML 快照",
                        status = null,
                    ),
                ),
            ),
        ),
        totalCount = 4,
        shownCount = 4,
    )
}

@Preview(name = "Home · Light", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentPreview() {
    ZhiBoxTheme(darkTheme = false) {
        HomeContent(
            state = FakeHome.loaded,
            draft = "",
            onDraftChange = {},
            onSelectFilter = {},
            onRetry = {},
            onOpenGraph = {},
            onSubmit = {},
            onOpenImport = {},
            onOpenChat = {},
            onOpenSources = {},
            onOpenSettings = {},
            onOpenDiagnostics = null,
            onOpenDocument = {},
        )
    }
}

@Preview(name = "Home · Dark", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        HomeContent(
            state = FakeHome.loaded,
            draft = "",
            onDraftChange = {},
            onSelectFilter = {},
            onRetry = {},
            onOpenGraph = {},
            onSubmit = {},
            onOpenImport = {},
            onOpenChat = {},
            onOpenSources = {},
            onOpenSettings = {},
            onOpenDiagnostics = {},
            onOpenDocument = {},
        )
    }
}

@Preview(name = "Home · Empty", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentEmptyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        HomeContent(
            state = HomeUiState.Loaded(HomeFilter.ALL, emptyList(), totalCount = 0, shownCount = 0),
            draft = "",
            onDraftChange = {},
            onSelectFilter = {},
            onRetry = {},
            onOpenGraph = {},
            onSubmit = {},
            onOpenImport = {},
            onOpenChat = {},
            onOpenSources = {},
            onOpenSettings = {},
            onOpenDiagnostics = null,
            onOpenDocument = {},
        )
    }
}

@Preview(name = "Home · Loading", showBackground = true, heightDp = 600)
@Composable
private fun HomeContentLoadingPreview() {
    ZhiBoxTheme(darkTheme = false) {
        HomeContent(
            state = HomeUiState.Loading,
            draft = "检索词",
            onDraftChange = {},
            onSelectFilter = {},
            onRetry = {},
            onOpenGraph = {},
            onSubmit = {},
            onOpenImport = {},
            onOpenChat = {},
            onOpenSources = {},
            onOpenSettings = {},
            onOpenDiagnostics = null,
            onOpenDocument = {},
        )
    }
}
