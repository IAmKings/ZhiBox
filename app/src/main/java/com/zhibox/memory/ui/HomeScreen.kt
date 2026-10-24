package com.zhibox.memory.ui

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    /**
     * 智能状态面板的深链出口：null = 打开设置页顶部（「智能」分组）。
     *
     * M2 起左上角状态点不再直接跳设置页，而是先打开「智能状态中枢」面板
     * （[IntelligenceHubSheet]），面板内动作再经此回调落到设置页对应锚点。
     */
    onOpenSettingsSection: (IntelligenceSection?) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val intelligence by viewModel.intelligence.collectAsStateWithLifecycle()
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()

    // 每次回到首页刷新一次（从设置页返回会重新进入组合 → 状态点颜色立即跟上）
    LaunchedEffect(Unit) { viewModel.refreshIntelligence() }
    // 输入框内容属于用户输入：rememberSaveable 保证旋转/进程重建后不丢
    var draft by rememberSaveable { mutableStateOf("") }
    // 状态中枢面板的展开态：rememberSaveable 保证旋转/进程重建后仍保留（规格 §11 B7）
    var hubOpen by rememberSaveable { mutableStateOf(false) }

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
        intelligence = intelligence,
        showOnboarding = showOnboarding,
        // 点状态点 = 打开面板（幂等：已开时再点不会叠加 sheet，规格 §11 B9）
        onOpenHub = { hubOpen = true },
        onDismissOnboarding = { viewModel.onOnboardingSeen() },
        onMarkNewContentSeen = viewModel::markNewContentSeen,
    )

    if (hubOpen) {
        IntelligenceHubSheet(
            status = intelligence,
            onDismissRequest = { hubOpen = false },
            // 面板内部先收起再回调（dismiss-then-navigate），这里只负责转发目标锚点。
            onOpenSection = onOpenSettingsSection,
            counts = IntelligenceHubCounts(
                legacySpaces = intelligence.legacySpaceCount,
                extractionFailed = intelligence.extractionFailedCount,
            ),
            // E3「去导入」：先收面板再打开导入屏。
            onOpenImport = onOpenImport,
            // E10/E11/E13 的幂等写操作：面板内就地执行，不关闭面板、不跳设置页。
            onRetryExtraction = viewModel::retryExtractionFailed,
            onRepairIndex = viewModel::repairIndexNow,
            onCancelSync = viewModel::cancelReembedSync,
        )
    }
}

/**
 * 无状态内容层：所有数据来自 [HomeUiState]，所有意图通过 lambda 上抛。
 *
 * `WindowInsets.isImeVisible` 目前标注为实验 API，但它是判断"键盘是否展开"的唯一
 * 官方入口（避免自己猜窗口高度）；仅用于输入岛上方那层透明点击拦截。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    intelligence: IntelligenceStatus,
    showOnboarding: Boolean,
    onOpenHub: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onMarkNewContentSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.zhiBoxColors
    val filter = when (state) {
        is HomeUiState.Loaded -> state.filter
        else -> HomeFilter.ALL
    }
    // 每个分类一份滚动状态：切 tab 不再共用同一个 LazyListState（否则索引被裁剪、位置丢失）
    val listStates = rememberFilterListStates()

    // 输入法打开时：点输入岛以外的任何地方只收键盘，不触发下层动作（列表卡片/筛选/顶栏）
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

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
                status = intelligence,
                onOpenHub = onOpenHub,
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
            if (showOnboarding) {
                IntelligenceOnboardingBanner(
                    // CTA = "我看到了 + 打开面板"：先落标记（不再打扰），再开面板；
                    // 关闭同理只落标记（规格 §5.3）。
                    onOpen = {
                        onDismissOnboarding()
                        onOpenHub()
                    },
                    onDismiss = onDismissOnboarding,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (state) {
                    HomeUiState.Loading -> HomeLoading()
                    is HomeUiState.Error -> HomeError(state.message, onRetry = onRetry)
                    is HomeUiState.Loaded -> HomeTimeline(
                        state = state,
                        listState = listStates.getValue(filter),
                        onOpenDocument = onOpenDocument,
                        onOpenImport = onOpenImport,
                    )
                }

                // 「新内容胶囊」：浮在列表上方，不打断当前阅读位置
                if (state is HomeUiState.Loaded) {
                    val listState = listStates.getValue(filter)
                    val scope = rememberCoroutineScope()
                    val elsewhereFilter = state.newElsewhereCategory?.toFilterOrNull()
                    when {
                        state.newInFilterCount > 0 -> NewContentPill(
                            text = stringResource(
                                R.string.home_new_content_pill,
                                state.newInFilterCount,
                            ),
                            contentDescription = stringResource(R.string.home_new_content_jump),
                            onClick = {
                                onMarkNewContentSeen()
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        )
                        state.newElsewhereCount > 0 && state.newElsewhereCategory != null ->
                            NewContentPill(
                                text = stringResource(
                                    R.string.home_new_content_elsewhere,
                                    stringResource(state.newElsewhereCategory.typeLabelRes),
                                    state.newElsewhereCount,
                                ),
                                contentDescription = stringResource(R.string.home_new_content_jump),
                                onClick = {
                                    onMarkNewContentSeen()
                                    onSelectFilter(elsewhereFilter ?: HomeFilter.ALL)
                                },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                            )
                    }
                }
            }
        }

        // 键盘遮罩：必须画在输入岛之前（之下），这样点输入岛仍可继续编辑，
        // 点其它区域则先收起键盘，避免"想收键盘却跳进了文档/换了筛选"。
        if (imeVisible) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 用 pointerInput 而不是 clickable：不产生无障碍焦点节点/水波纹，
                    // 纯拦截触摸（键盘收起也可用系统返回键，读屏用户不受影响）。
                    .pointerInput(imeVisible) {
                        detectTapGestures {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        }
                    },
            )
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
    status: IntelligenceStatus,
    onOpenHub: () -> Unit,
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
            OfflineReadyDot(status = status, onClick = onOpenHub)
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
            // 无障碍：标签必须落在可点击节点上（图标自身 contentDescription=null），
            // 否则 TalkBack 会拆成"无标签按钮 + 无动作描述"两个节点。
            val searchLabel = stringResource(R.string.home_action_search)
            IconButton(
                onClick = onOpenSearch,
                modifier = Modifier.semantics { contentDescription = searchLabel },
            ) {
                Icon(
                    imageVector = ZhiBoxIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.ink,
                )
            }
            Box {
                val moreLabel = stringResource(R.string.home_action_more)
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = moreLabel },
                ) {
                    Icon(
                        imageVector = ZhiBoxIcons.More,
                        contentDescription = null,
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

// ── 智能状态点（M1：让绿点说真话） ─────────────────────────────────

/** 命中区：视觉点只有 10dp，但可点区域必须 ≥48dp。 */
private val DotTouchTarget = 48.dp
private val DotVisual = 10.dp
private val PendingBadgeSize = 14.dp

/** 动效参数：就绪=慢呼吸；有任务在跑=快脉冲；未就绪/异常=静止（见规格 §3.4）。 */
private data class PulseSpec(
    val durationMs: Int,
    val maxScale: Float,
    val startAlpha: Float,
    val endAlpha: Float,
)
private val CalmBreath = PulseSpec(4_000, 1.15f, 0.35f, 0.12f)
private val BusyPulse = PulseSpec(1_400, 1.6f, 0.45f, 0f)

/** 四态文案（同时用于 stateDescription 与无障碍播报；可见性放宽到 internal 供 Robolectric 断言）。 */
@Composable
internal fun intelligenceStatusLabel(status: IntelligenceStatus): String {
    // 有进行中的任务时播报"正在下载/同步/抽取"，比四态更能解释"为什么在闪"
    val busyLabel = when (status.busy) {
        IntelligenceBusy.DOWNLOADING -> status.busyPct?.let {
            stringResource(R.string.intelligence_status_downloading, it)
        }
        IntelligenceBusy.SYNCING -> status.busyPct?.let {
            stringResource(R.string.intelligence_status_syncing, it)
        }
        IntelligenceBusy.EXTRACTING -> stringResource(R.string.intelligence_status_extracting)
        IntelligenceBusy.NONE -> null
    }
    if (busyLabel != null) return busyLabel
    return when (status.level) {
        IntelligenceLevel.READY -> stringResource(R.string.intelligence_status_ready)
        IntelligenceLevel.PARTIAL -> stringResource(
            R.string.intelligence_status_partial,
            status.pendingCount,
        )
        IntelligenceLevel.NOT_READY -> stringResource(R.string.intelligence_status_not_ready)
        IntelligenceLevel.ERROR -> stringResource(R.string.intelligence_status_error)
    }
}

/**
 * 系统"减少动画"是否关闭：读 `ANIMATOR_DURATION_SCALE`（开发者选项/无障碍动画关闭时为 0）。
 * 读失败按"允许动画"处理，避免误伤正常设备。
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
 * 状态点是否播放动效（纯函数，便于 JVM/Robolectric 断言）。
 *
 * 两个条件同时成立才动：系统允许动画（`ANIMATOR_DURATION_SCALE > 0`）**且**状态本身需要动效
 * （就绪/部分就绪慢呼吸；下载/同步/抽取中快脉冲；未就绪/异常且空闲时静止，§3.4）。
 */
internal fun shouldAnimateDot(status: IntelligenceStatus, animationsEnabled: Boolean): Boolean =
    animationsEnabled && status.animates

/**
 * 智能状态点：
 * - 颜色只表达 L1（就绪=呼吸绿 / 未就绪=灰 / 异常=琥珀），L3 可选能力不用灰色惩罚；
 * - L4 待处理项用琥珀角标（数字=项数）；
 * - 可点击（≥48dp），TalkBack 播报「智能状态 + 四态文案」；
 * - 未就绪/异常或系统关闭动画时完全静止。
 *
 * 可见性 internal + [animationsEnabledOverride] 是为了让 Robolectric（D 阶段）能直接断言
 * 文案/动效分支，而不必依赖真机的系统动画设置。
 */
@Composable
internal fun OfflineReadyDot(
    status: IntelligenceStatus,
    onClick: () -> Unit,
    /** 测试注入：null = 读系统设置；传 false 可关闭无限动画（Robolectric 主线程不排队）。 */
    animationsEnabledOverride: Boolean? = null,
) {
    val colors = MaterialTheme.zhiBoxColors
    val label = intelligenceStatusLabel(status)
    val title = stringResource(R.string.intelligence_hub_title)
    val openLabel = stringResource(R.string.intelligence_open_status)
    val dotColor = when (status.level) {
        IntelligenceLevel.READY, IntelligenceLevel.PARTIAL -> MaterialTheme.colorScheme.secondary
        IntelligenceLevel.NOT_READY -> colors.inkMeta
        IntelligenceLevel.ERROR -> colors.warning
    }
    val animationsEnabled = if (animationsEnabledOverride != null) {
        animationsEnabledOverride
    } else {
        rememberAnimationsEnabled()
    }
    val animate = shouldAnimateDot(status, animationsEnabled)
    val busy = status.busy != IntelligenceBusy.NONE

    // 外层不裁剪：角标要能压到命中区边缘之外；裁剪只作用于内层（水波纹仍是圆形）
    Box(
        modifier = Modifier.size(DotTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                // clearAndSetSemantics：把"状态描述"合并到可点击节点本身，并屏蔽内部小圆/角标
                // 的独立语义，保证 TalkBack 只落到一个节点（否则会读出"按钮（无标签）"）。
                .clearAndSetSemantics {
                    contentDescription = title
                    stateDescription = label
                },
            contentAlignment = Alignment.Center,
        ) {
            if (animate) {
                val spec = if (busy) BusyPulse else CalmBreath
                val transition = rememberInfiniteTransition(label = "intelligence-pulse")
                val pulseScale by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = spec.maxScale,
                    animationSpec = infiniteRepeatable(
                        tween(durationMillis = spec.durationMs),
                        RepeatMode.Restart,
                    ),
                    label = "pulse-scale",
                )
                val pulseAlpha by transition.animateFloat(
                    initialValue = spec.startAlpha,
                    targetValue = spec.endAlpha,
                    animationSpec = infiniteRepeatable(
                        tween(durationMillis = spec.durationMs),
                        RepeatMode.Restart,
                    ),
                    label = "pulse-alpha",
                )
                Box(
                    Modifier
                        .size(DotVisual)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .background(dotColor, CircleShape),
                )
            }
            Box(Modifier.size(DotVisual).background(dotColor, CircleShape))
        }

        if (status.pendingCount > 0) {
            Box(
                modifier = Modifier
                    // 压在圆点右上沿（相对中心 +8/-8dp），而不是跑到 48dp 命中区的角上
                    .align(Alignment.Center)
                    .offset(x = 7.dp, y = (-7).dp)
                    .size(PendingBadgeSize)
                    .clip(CircleShape)
                    .background(colors.warning)
                    // 角标数字已并入状态描述（"就绪，N 项待处理"），不再单独朗读
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (status.pendingCount > 9) "9+" else status.pendingCount.toString(),
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.paper,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── 首次引导条（M1：无模型时给出正确下一步） ────────────────────────

/**
 * 一次性引导条：只在「未就绪」且用户从未处理过时出现。
 * 之所以需要它：裸点的发现性太低，新用户不会主动去点左上角。
 */
@Composable
private fun IntelligenceOnboardingBanner(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.zhiBoxColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ZhiBoxRadius.card),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.warning))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.intelligence_onboarding_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
                Text(
                    text = stringResource(R.string.intelligence_onboarding_message),
                    fontSize = 11.sp,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(onClick = onOpen) {
                Text(
                    text = stringResource(R.string.intelligence_onboarding_cta),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    // 线条 ×（不是 Clear 的实心圆挖空 ×）：与整套图标同风格，16dp 下不会糊
                    imageVector = ZhiBoxIcons.Close,
                    contentDescription = stringResource(R.string.intelligence_onboarding_dismiss),
                    modifier = Modifier.size(18.dp),
                    tint = colors.inkMeta,
                )
            }
        }
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

// ── 新内容胶囊 ──────────────────────────────────────────────────────

/**
 * 顶部「N 条新内容」胶囊：新条目不会把列表顶走（LazyColumn 按 key 锚定当前可见项），
 * 想立刻看新内容时点它回顶部；点后标记为已读。
 */
@Composable
private fun NewContentPill(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = ZhiBoxIcons.ArrowUp,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ── 时间线滚动状态（按分类记忆） ─────────────────────────────────────

/**
 * 分类 → 滚动位置。切 tab 时每个分类保留自己的位置；用 [rememberSaveable] 存
 * (firstVisibleItemIndex, offset)，旋转/进程重建后也能恢复。
 *
 * 为什么不能共用一个 LazyListState：各分类列表长度不同，共用时索引会被裁剪
 * （全部滑到第 20 项 → 切到只有 3 项的念头 → 索引被压到 2 → 切回全部停在顶部附近）；
 * 切到空分类时列表还会被空态替换、状态被销毁，回来必然从头开始。
 */
private val FilterScrollSaver = listSaver<Map<HomeFilter, LazyListState>, Any>(
    save = { states ->
        states.entries.flatMap { (filter, state) ->
            listOf(filter.name, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    },
    restore = { flat ->
        buildMap {
            var i = 0
            while (i + 2 < flat.size) {
                val filter = HomeFilter.entries.firstOrNull { it.name == flat[i] }
                val index = flat[i + 1] as? Int
                val offset = flat[i + 2] as? Int
                if (filter != null && index != null && offset != null) {
                    put(filter, LazyListState(index, offset))
                }
                i += 3
            }
        }
    },
)

@Composable
private fun rememberFilterListStates(): Map<HomeFilter, LazyListState> =
    rememberSaveable(saver = FilterScrollSaver) {
        HomeFilter.entries.associateWith { LazyListState() }
    }

// ── 时间线 ──────────────────────────────────────────────────────────

@Composable
private fun HomeTimeline(
    state: HomeUiState.Loaded,
    listState: LazyListState,
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
        state = listState,
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

/** 分类 → 顶部分类药丸（快照没有独立 tab，落到「全部」）。 */
private fun HomeCategory.toFilterOrNull(): HomeFilter? = when (this) {
    HomeCategory.NOTE -> HomeFilter.NOTE
    HomeCategory.IMAGE -> HomeFilter.IMAGE
    HomeCategory.DOCUMENT -> HomeFilter.DOCUMENT
    HomeCategory.SNAPSHOT -> null
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
    /** 预览用：模型就绪、2 个向量空间、GPU 自动。 */
    val ready: IntelligenceStatus = evaluateIntelligence(
        IntelligenceReadings(
            modelName = "embeddinggemma-2-440m",
            spaceCount = 2,
            gpuMode = "auto",
        ),
    )

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
            intelligence = FakeHome.ready,
            showOnboarding = false,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
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
            intelligence = FakeHome.ready,
            showOnboarding = false,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
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
            intelligence = FakeHome.ready,
            showOnboarding = false,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
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
            intelligence = FakeHome.ready,
            showOnboarding = false,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
        )
    }
}

@Preview(name = "Home · AI 未就绪（引导条）", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentNotReadyPreview() {
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
            intelligence = IntelligenceStatus.Loading,
            showOnboarding = true,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
        )
    }
}

@Preview(name = "Home · 新内容胶囊", showBackground = true, heightDp = 900)
@Composable
private fun HomeContentNewContentPreview() {
    ZhiBoxTheme(darkTheme = false) {
        HomeContent(
            state = FakeHome.loaded.copy(newInFilterCount = 3),
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
            intelligence = FakeHome.ready,
            showOnboarding = false,
            onOpenHub = {},
            onDismissOnboarding = {},
            onMarkNewContentSeen = {},
        )
    }
}
