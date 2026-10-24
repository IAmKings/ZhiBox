package com.zhibox.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.ZhiBoxRadius
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import com.zhibox.memory.ui.theme.zhiBoxColors
import kotlinx.coroutines.launch

/**
 * 智能状态中枢面板（M2 P1-1；M4 补齐 §4 空态/busy 行与就地动作）。
 *
 * 规格：`docs/product/INTELLIGENCE_HUB_SPEC.md` §4（E1–E15 矩阵）、§6（IA/线框）、
 * §8.3/§8.4（文案）、§9（视觉令牌）。
 *
 * 信息架构（首屏"三问"，§6.2）：
 * 1. 能不能用？ → 标题 + 一句话结论（按 [IntelligenceLevel] 四态）+ 首屏关键行；
 * 2. 哪里没配好？ → 4 个只读 pill（模型 / 空间 / GPU / LLM）+ 附加 pill（E4/E8/E14）；
 * 3. 下一步点什么？ → 空态主 CTA / 快捷动作 4 行 + 底部「打开设置 · 智能」。
 *
 * 首屏关键行由纯逻辑 [resolvePrimary]（[HubPrimary]）决定，渲染顺序与 §4 一致：
 * E1 下载模型 / E2 去启用 / E15 重新下载 / E12 下载进度 / E13 同步进度+停止 / E3 去导入。
 *
 * 禁令（§6.4）：面板内不放任何输入框/表单/开关组；需要输入的（LLM Key、目录选择）一律跳设置。
 * 允许的"就地写"只有幂等动作（§6.4）：重试抽取 / 修复索引 / 停止同步——通过
 * [IntelligenceHubSheet] 的对应回调在面板内直接执行，不关闭面板、不跳设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceHubSheet(
    status: IntelligenceStatus,
    onDismissRequest: () -> Unit,
    onOpenSection: (IntelligenceSection?) -> Unit,
    counts: IntelligenceHubCounts = IntelligenceHubCounts(),
    /** E3「去导入」：关闭面板后打开导入屏。 */
    onOpenImport: () -> Unit = {},
    /** E10 待处理行「重试」：就地重新排队失败抽取（不关闭面板）。 */
    onRetryExtraction: () -> Unit = {},
    /** E11 待处理行「修复」：就地修复索引（不关闭面板）。 */
    onRepairIndex: () -> Unit = {},
    /** E13「停止同步」：就地取消重嵌入（不关闭面板）。 */
    onCancelSync: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    // 统一"先收起面板、再执行深链"：先让收起动画跑完再跳页，避免设置页在面板还悬着时出现。
    // 连点/重复点击也安全（B9：dismiss 幂等，动作只会被触发一次）。
    fun closeThen(action: () -> Unit) {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismissRequest()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = ZhiBoxRadius.omniBar,
            topEnd = ZhiBoxRadius.omniBar,
        ),
        // 面板底 = 卡片白/深色 #1B1A18（§9.1）；文字走扩展色令牌，禁止裸色值。
        containerColor = MaterialTheme.zhiBoxColors.card,
        contentColor = MaterialTheme.zhiBoxColors.ink,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        // 底部安全区由内容自身的 navigationBarsPadding 处理，避免 M3 默认 systemBars 内边距叠加。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        IntelligenceHubContent(
            status = status,
            counts = counts,
            onClose = { closeThen {} },
            onOpenSection = { section -> closeThen { onOpenSection(section) } },
            // 深链类动作（下载模型/去启用/去导入）先收面板再跳页；
            // 就地动作（重试/修复/停止同步）原样下发——不关闭面板，状态随 Flow 实时刷新。
            onOpenImport = { closeThen { onOpenImport() } },
            onRetryExtraction = onRetryExtraction,
            onRepairIndex = onRepairIndex,
            onCancelSync = onCancelSync,
        )
    }
}

/**
 * 面板待处理行的数量快照。
 *
 * 来源：[IntelligenceStatus.legacySpaceCount] / [IntelligenceStatus.extractionFailedCount]
 * （由 HomeScreen 构造传入；0 = 无）。单独成类型是为了让 sheet 内容层保持无状态、
 * 可被 @Preview / 单测直接构造。
 */
data class IntelligenceHubCounts(
    val legacySpaces: Int = 0,
    val extractionFailed: Int = 0,
)

// ── 面板内容（无 sheet 依赖，便于 @Preview / 单测） ──────────────────

@Composable
private fun IntelligenceHubContent(
    status: IntelligenceStatus,
    counts: IntelligenceHubCounts,
    onClose: () -> Unit,
    onOpenSection: (IntelligenceSection?) -> Unit,
    onOpenImport: () -> Unit = {},
    onRetryExtraction: () -> Unit = {},
    onRepairIndex: () -> Unit = {},
    onCancelSync: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // §6.1：内容自适应，最大屏高 70%；超出内部滚动。
        val maxPanelHeight = maxHeight * 0.7f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HubHeader(onClose = onClose)
            // 首屏关键行只解析一次：E1 的标题与四态结论句完全相同（「还没有可用的本地模型」），
            // 同时渲染会重复播报/重复占行，因此 E1 时由卡片单独承担该句。
            val primary = resolvePrimary(status)
            if (primary != HubPrimary.E1) {
                HubConclusion(status = status)
            }
            // §4 首屏关键行（E1/E2/E15/E12/E13/E3）：宁可多一行可执行提示，也不让用户猜下一步。
            HubPrimaryCard(
                content = hubPrimaryContent(
                    primary = primary,
                    status = status,
                    onOpenSection = onOpenSection,
                    onOpenImport = onOpenImport,
                    onCancelSync = onCancelSync,
                ),
            )
            HubPills(status = status)
            HubInfoPills(status = status, onOpenSection = onOpenSection)

            HubSectionTitle(stringResource(R.string.intelligence_section_quick))
            HubQuickActions(status = status, onOpenSection = onOpenSection)

            if (status.pending.isNotEmpty()) {
                HubSectionTitle(
                    stringResource(R.string.intelligence_section_pending, status.pendingCount),
                )
                HubPendingList(
                    rows = pendingRows(
                        status = status,
                        counts = counts,
                        onOpenSection = onOpenSection,
                        onRetryExtraction = onRetryExtraction,
                        onRepairIndex = onRepairIndex,
                    ),
                )
            }

            Button(
                onClick = { onOpenSection(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 12.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(ZhiBoxRadius.pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.intelligence_open_settings),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 标题 18sp/600 + 关闭按钮；副标题为结论句（13sp/inkSecondary，§9.2）。 */
@Composable
private fun HubHeader(onClose: () -> Unit) {
    val colors = MaterialTheme.zhiBoxColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.intelligence_hub_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        val closeLabel = stringResource(R.string.intelligence_close)
        // 无障碍：标签落在可点击节点上（图标 contentDescription=null），且命中区 ≥48dp。
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = closeLabel },
        ) {
            Icon(
                imageVector = ZhiBoxIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.inkMeta,
            )
        }
    }
}

/** 一句话结论（§6.2 问题 1）。空态/异常的具体补充行交给 [HubPrimaryCard]（§4 首屏关键行）。 */
@Composable
private fun HubConclusion(status: IntelligenceStatus) {
    val colors = MaterialTheme.zhiBoxColors
    val conclusion = when (status.level) {
        IntelligenceLevel.READY -> stringResource(R.string.intelligence_conclusion_ready)
        IntelligenceLevel.PARTIAL -> stringResource(
            R.string.intelligence_conclusion_partial,
            status.pendingCount,
        )
        IntelligenceLevel.NOT_READY -> stringResource(R.string.intelligence_conclusion_not_ready)
        IntelligenceLevel.ERROR -> stringResource(R.string.intelligence_conclusion_error)
    }
    Text(
        text = conclusion,
        fontSize = 13.sp,
        color = colors.inkSecondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ── §4 首屏关键行（E1/E2/E15/E12/E13/E3） ────────────────────────────

/**
 * 首屏关键行的纯展示数据（文案已在 @Composable 内解析成 String）。
 *
 * [progressValue] 语义：`null` = 不展示进度条；`< 0` = 不确定进度（总量未知）；`0..1` = 确定进度。
 */
private data class HubPrimaryContent(
    val title: String,
    val message: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val progressValue: Float? = null,
    val warning: Boolean = false,
)

/** 把 [resolvePrimary] 的结果映射为面板首屏关键行；[HubPrimary.NORMAL] 不展示。 */
@Composable
private fun hubPrimaryContent(
    primary: HubPrimary,
    status: IntelligenceStatus,
    onOpenSection: (IntelligenceSection?) -> Unit,
    onOpenImport: () -> Unit,
    onCancelSync: () -> Unit,
): HubPrimaryContent? = when (primary) {
    // E1：全新安装、全空 → 下载模型
    HubPrimary.E1 -> HubPrimaryContent(
        title = stringResource(R.string.intelligence_empty_title),
        message = stringResource(R.string.intelligence_empty_message),
        actionLabel = stringResource(R.string.intelligence_action_download),
        onAction = { onOpenSection(IntelligenceSection.ModelStore) },
    )

    // E2：已有模型文件但未启用 → 去启用（选择嵌入路径）
    HubPrimary.E2 -> HubPrimaryContent(
        title = stringResource(
            R.string.intelligence_models_found_not_enabled,
            status.modelFileCount,
        ),
        actionLabel = stringResource(R.string.intelligence_action_enable),
        onAction = { onOpenSection(IntelligenceSection.Embedding) },
    )

    // E15：模型文件损坏 → 重新下载（可修复问题用 warning 色，不吓人，§8.5）
    HubPrimary.E15 -> HubPrimaryContent(
        title = stringResource(R.string.intelligence_model_corrupt),
        warning = true,
        actionLabel = stringResource(R.string.intelligence_action_redownload),
        onAction = { onOpenSection(IntelligenceSection.ModelStore) },
    )

    // E12：下载中（§4 要求「无 CTA，禁用其他行」；这里只展示进度，不做额外动作）
    HubPrimary.E12 -> {
        val pct = status.busyPct
        HubPrimaryContent(
            title = status.busyLabel ?: stringResource(R.string.intelligence_row_model),
            message = pct?.let { stringResource(R.string.settings_downloading, it) },
            progressValue = pct?.let { it / 100f } ?: -1f,
        )
    }

    // E13：重嵌入同步中 → 就地停止
    HubPrimary.E13 -> {
        val total = status.busyTotal
        val done = status.busyDone ?: 0
        // 总量未知（null / <=0）时用不确定进度条，不编造分母（§10.3）。
        if (total == null || total <= 0) {
            HubPrimaryContent(
                title = stringResource(R.string.intelligence_syncing_unknown),
                progressValue = -1f,
                actionLabel = stringResource(R.string.settings_reembed_stop),
                onAction = onCancelSync,
            )
        } else {
            HubPrimaryContent(
                title = stringResource(R.string.settings_reembed_progress, done, total),
                progressValue = (done.toFloat() / total).coerceIn(0f, 1f),
                actionLabel = stringResource(R.string.settings_reembed_stop),
                onAction = onCancelSync,
            )
        }
    }

    // E3：模型就绪、知识库为空 → 去导入（空库仍是 READY，不做灰色惩罚）
    HubPrimary.E3 -> HubPrimaryContent(
        title = stringResource(R.string.intelligence_ready_empty_kb),
        actionLabel = stringResource(R.string.home_go_import),
        onAction = onOpenImport,
    )

    HubPrimary.NORMAL -> null
}

/** 首屏关键行卡片：发丝线 + 14sp 标题 + 可选说明/进度/主 CTA（§9.2）。 */
@Composable
private fun HubPrimaryCard(content: HubPrimaryContent?) {
    if (content == null) return
    val colors = MaterialTheme.zhiBoxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .border(
                width = 1.dp,
                color = colors.border,
                shape = RoundedCornerShape(ZhiBoxRadius.card),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = content.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            // 可修复问题用 warning 令牌（不是错误红）；其余用主墨色。
            color = if (content.warning) colors.warning else colors.ink,
        )
        content.message?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = colors.inkMeta,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        content.progressValue?.let { value ->
            // §4 E12/E13：确定总量用确定进度条；总量未知（-1f）用不确定进度条。
            if (value < 0f) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            } else {
                LinearProgressIndicator(
                    progress = { value.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
        }
        val actionLabel = content.actionLabel
        val onAction = content.onAction
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .height(36.dp),
                shape = RoundedCornerShape(ZhiBoxRadius.pill),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── §4 附加信息 pill（E4/E8/E14，只读但可点击跳对应设置锚点） ────────

/** E4/E8/E14 的额外 pill；顺序与 §4 一致，未知（null）不展示（§10.3）。 */
@Composable
private fun HubInfoPills(
    status: IntelligenceStatus,
    onOpenSection: (IntelligenceSection?) -> Unit,
) {
    val colors = MaterialTheme.zhiBoxColors
    val pills = infoPills(status)
    if (pills.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(pills.size) { index ->
            val pill = pills[index]
            val text = when (pill.kind) {
                HubInfoPill.Kind.STORE_NOT_CONFIGURED ->
                    stringResource(R.string.settings_store_not_configured)
                HubInfoPill.Kind.SAG_DISABLED ->
                    stringResource(R.string.settings_sag_unavailable)
                HubInfoPill.Kind.NETWORK_DISABLED ->
                    stringResource(R.string.intelligence_network_disabled)
                HubInfoPill.Kind.ANSWER_CONSENT_REQUIRED ->
                    stringResource(R.string.settings_answer_consent_required)
            }
            val section = when (pill.kind) {
                // E4：共享目录在「共享模型目录」里配置（§6.5）
                HubInfoPill.Kind.STORE_NOT_CONFIGURED -> IntelligenceSection.ModelStoreShared
                // E8/E14：SAG/联网/问答同意都在「SAG 增强 / LLM」分组
                HubInfoPill.Kind.SAG_DISABLED,
                HubInfoPill.Kind.NETWORK_DISABLED,
                HubInfoPill.Kind.ANSWER_CONSENT_REQUIRED,
                -> IntelligenceSection.Llm
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .background(colors.pillInactive, RoundedCornerShape(ZhiBoxRadius.pill))
                    .clickable(role = Role.Button) { onOpenSection(section) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.pillInactiveText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 4 个只读 pill（§9.2：高 28dp / 左右 10dp / 圆角 999dp / 11sp-500），超长横向滚动。 */
@Composable
private fun HubPills(status: IntelligenceStatus) {
    val colors = MaterialTheme.zhiBoxColors
    // 每个 pill 的文本 = 标签 + 值（"模型 EGemma2" / "空间 2" / "GPU 自动" / "LLM 未启用"）。
    val pills = listOf(
        stringResource(R.string.intelligence_pill_model) to modelPillValue(status.model),
        stringResource(R.string.intelligence_pill_space) to spacePillValue(status.spaces),
        stringResource(R.string.intelligence_pill_gpu) to gpuPillValue(status.gpu),
        stringResource(R.string.intelligence_pill_llm) to llmPillValue(status.llm),
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(pills.size) { index ->
            val (label, value) = pills[index]
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .background(colors.pillInactive, RoundedCornerShape(ZhiBoxRadius.pill))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$label $value",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.pillInactiveText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 分组标题：13sp/500/inkMeta，上 16 下 8（§9.2）。 */
@Composable
private fun HubSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.zhiBoxColors.inkMeta,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** 快捷动作：内层卡片 16dp 圆角 + 发丝线，4 行各 56dp（§9.2/§9.3）。 */
@Composable
private fun HubQuickActions(
    status: IntelligenceStatus,
    onOpenSection: (IntelligenceSection?) -> Unit,
) {
    val colors = MaterialTheme.zhiBoxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = colors.border,
                shape = RoundedCornerShape(ZhiBoxRadius.card),
            ),
    ) {
        val rows = listOf(
            HubActionRowData(
                title = stringResource(R.string.intelligence_row_model),
                subtitle = modelRowSubtitle(status.model),
                section = IntelligenceSection.Embedding,
            ),
            HubActionRowData(
                title = stringResource(R.string.intelligence_row_path),
                subtitle = pathRowSubtitle(status.model),
                section = IntelligenceSection.Embedding,
            ),
            HubActionRowData(
                title = stringResource(R.string.intelligence_row_gpu),
                subtitle = gpuPillValue(status.gpu),
                section = IntelligenceSection.Gpu,
            ),
            HubActionRowData(
                title = stringResource(R.string.intelligence_row_llm),
                subtitle = llmPillValue(status.llm),
                section = IntelligenceSection.Llm,
            ),
        )
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = colors.divider)
            HubActionRow(row = row, onClick = { onOpenSection(row.section) })
        }
    }
}

private data class HubActionRowData(
    val title: String,
    val subtitle: String,
    val section: IntelligenceSection,
)

/** 行：高 56dp，标题 14sp/500，副标题 12sp/inkMeta，右侧 chevron（文本「›」，不新增图标）。 */
@Composable
private fun HubActionRow(row: HubActionRowData, onClick: () -> Unit) {
    val colors = MaterialTheme.zhiBoxColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 用 heightIn 而不是固定 height：200% 字体缩放时允许长高，不截断（§12.2）。
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            // 无障碍：整行一个节点播报"标题，副标题"，避免拆成"无标签按钮 + 两段文字"。
            .clearAndSetSemantics { contentDescription = "${row.title}，${row.subtitle}" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.subtitle,
                fontSize = 12.sp,
                color = colors.inkMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "›",
            fontSize = 18.sp,
            color = colors.inkFaint,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ── 待处理区（§6.4 ③：无告警时整区隐藏） ────────────────────────────

private data class HubPendingRowData(
    val text: String,
    val action: String,
    val onClick: () -> Unit,
    /** 就地动作（重试/修复）不显示「›」：点了直接执行，而不是"跳到别处"。 */
    val inPlace: Boolean = false,
)

/**
 * 待处理行：旧空间 / 抽取失败 / 索引 / 授权丢失 / LLM 读取失败。
 *
 * 数量来自 [IntelligenceHubCounts]（= IntelligenceStatus 的两个计数字段），
 * 行文案与 §8.3 的 `%1$d` 文案一一对应；枚举与计数不一致时（理论上不会发生）
 * 以计数为准，0 表示该行不展示。
 *
 * 动作口径（§6.4 ③ / §6.5）：抽取失败「重试」与索引不一致「修复」是**就地执行**的
 * 幂等写操作（不关闭面板）；旧空间/授权丢失/LLM 读取失败仍深链到设置页对应锚点。
 */
@Composable
private fun pendingRows(
    status: IntelligenceStatus,
    counts: IntelligenceHubCounts,
    onOpenSection: (IntelligenceSection?) -> Unit,
    onRetryExtraction: () -> Unit,
    onRepairIndex: () -> Unit,
): List<HubPendingRowData> = buildList {
    val handle = stringResource(R.string.intelligence_pending_action_handle)
    val detail = stringResource(R.string.intelligence_pending_action_detail)
    val retry = stringResource(R.string.intelligence_pending_action_retry)
    val repair = stringResource(R.string.intelligence_pending_action_repair)
    val configure = stringResource(R.string.intelligence_pending_action_configure)
    status.pending.forEach { item ->
        when (item) {
            PendingItem.LEGACY_SPACES -> add(
                HubPendingRowData(
                    text = stringResource(
                        R.string.intelligence_pending_legacy_spaces,
                        counts.legacySpaces,
                    ),
                    action = handle,
                    // 旧空间数据在「知识库迁移」卡（备份/旧数据同步入口）里处理。
                    onClick = { onOpenSection(IntelligenceSection.Migration) },
                ),
            )
            PendingItem.EXTRACTION_FAILED -> {
                val retryable = canRetryExtraction(status)
                add(
                    HubPendingRowData(
                        text = stringResource(
                            R.string.intelligence_pending_extraction,
                            counts.extractionFailed,
                        ),
                        // §6.4：SAG 可用时就地重试（幂等）；不可用时重试是空操作，
                        // 改跳 LLM 配置（否则用户点了没反应）。
                        action = if (retryable) retry else configure,
                        onClick = if (retryable) {
                            onRetryExtraction
                        } else {
                            { onOpenSection(IntelligenceSection.Llm) }
                        },
                        inPlace = retryable,
                    ),
                )
            }
            PendingItem.INDEX_UNHEALTHY -> add(
                HubPendingRowData(
                    text = stringResource(R.string.intelligence_pending_index),
                    action = repair,
                    // §6.4：面板内就地修复索引（清理孤儿行 + 必要时重建 FTS），不跳设置页。
                    onClick = onRepairIndex,
                    inPlace = true,
                ),
            )
            PendingItem.STORE_PERMISSION_LOST -> add(
                HubPendingRowData(
                    text = stringResource(R.string.settings_store_permission_lost),
                    action = handle,
                    onClick = { onOpenSection(IntelligenceSection.ModelStoreShared) },
                ),
            )
            PendingItem.LLM_LOAD_ERROR -> add(
                HubPendingRowData(
                    text = stringResource(
                        R.string.settings_llm_load_failed,
                        stringResource(R.string.intelligence_pill_value_unknown),
                    ),
                    action = detail,
                    onClick = { onOpenSection(IntelligenceSection.Llm) },
                ),
            )
        }
    }
}

@Composable
private fun HubPendingList(rows: List<HubPendingRowData>) {
    val colors = MaterialTheme.zhiBoxColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = colors.border,
                shape = RoundedCornerShape(ZhiBoxRadius.card),
            ),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 就地动作也是整行可点（≥48dp 命中区），比小按钮更易触达（§12.2）。
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { row.onClick() }
                    .clearAndSetSemantics { contentDescription = "${row.text}，${row.action}" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.text,
                    fontSize = 13.sp,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.action,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    // 就地动作更醒目（点了就执行），深链动作弱一档。
                    color = if (row.inPlace) colors.ink else colors.inkSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (!row.inPlace) {
                    Text(
                        text = "›",
                        fontSize = 16.sp,
                        color = colors.inkFaint,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

// ── pill / 副标题取值（§10.3：不可知项只显示允许的措辞） ─────────────

@Composable
private fun modelPillValue(model: ModelStatus): String = when (model) {
    is ModelStatus.Ready -> model.name
    ModelStatus.Missing -> stringResource(R.string.intelligence_pill_value_none)
    // 损坏是可修复问题，用"需要修复"而不是吓人的"失败"（§8.5）。
    ModelStatus.Corrupt -> stringResource(R.string.intelligence_status_error)
}

@Composable
private fun spacePillValue(spaces: SpaceStatus): String = when (spaces) {
    is SpaceStatus.Ready -> spaces.count.toString()
    // 空库仍是就绪（§2），显示 0 而不是"未配置"。
    SpaceStatus.None -> "0"
    SpaceStatus.Unknown -> stringResource(R.string.intelligence_pill_value_unknown)
}

@Composable
private fun gpuPillValue(gpu: GpuStatus): String = when (gpu) {
    // 只表达"档位偏好"，禁止显示"GPU 可用/已启用"（§10.3）。
    GpuStatus.AUTO -> stringResource(R.string.intelligence_pill_gpu_auto)
    GpuStatus.ON -> stringResource(R.string.settings_gpu_gpu)
    GpuStatus.OFF -> stringResource(R.string.settings_gpu_cpu)
    GpuStatus.UNKNOWN -> stringResource(R.string.intelligence_pill_value_unknown)
}

@Composable
private fun llmPillValue(llm: LlmStatus): String = when (llm) {
    // "已配置"只能表达配置存在，不能说"已连接"（§10.3）；复用设置页既有措辞。
    LlmStatus.Configured -> stringResource(R.string.settings_llm_saved)
    LlmStatus.NotConfigured -> stringResource(R.string.intelligence_pill_value_not_enabled)
    LlmStatus.LoadError -> stringResource(R.string.chat_agent_fallback_config_error)
    LlmStatus.Unknown -> stringResource(R.string.intelligence_pill_value_unknown)
}

/** 嵌入模型行副标题：模型名 · 就绪态（§6.3 线框 "EGemma2 · 440M" 的可得字段版本）。 */
@Composable
private fun modelRowSubtitle(model: ModelStatus): String = when (model) {
    is ModelStatus.Ready -> stringResource(
        R.string.intelligence_row_model_value,
        model.name,
        stringResource(R.string.sources_status_ready),
    )
    ModelStatus.Missing -> stringResource(R.string.intelligence_pill_value_none)
    ModelStatus.Corrupt -> stringResource(R.string.intelligence_model_corrupt)
}

/**
 * 嵌入路径行副标题。
 *
 * 说明：`IntelligenceStatus` 不含 `PathPreferences.preferred()`（AUTO/A/B/D），
 * 因此这里显示"当前生效：<模型名>"这个**可证实**的事实，而不是猜测"自动/440M 优先"。
 * 等状态模型补上路径偏好字段后可换成偏好文案（§6.3 线框写法）。
 */
@Composable
private fun pathRowSubtitle(model: ModelStatus): String = when (model) {
    is ModelStatus.Ready -> stringResource(R.string.settings_current_model, model.name)
    ModelStatus.Missing -> stringResource(R.string.intelligence_pill_value_none)
    ModelStatus.Corrupt -> stringResource(R.string.intelligence_model_corrupt)
}

// ── 预览（浅色/深色；构造 IntelligenceStatus 样例） ──────────────────

private object FakeHub {
    /** 部分就绪：模型就绪 + 2 个空间 + GPU 自动 + LLM 未配置 + 2 项待处理。 */
    val partial = IntelligenceStatus(
        level = IntelligenceLevel.PARTIAL,
        busy = IntelligenceBusy.NONE,
        model = ModelStatus.Ready("embeddinggemma-2-440m"),
        spaces = SpaceStatus.Ready(2),
        gpu = GpuStatus.AUTO,
        llm = LlmStatus.NotConfigured,
        pending = listOf(PendingItem.LEGACY_SPACES, PendingItem.EXTRACTION_FAILED),
        extractionFailedCount = 12,
        legacySpaceCount = 3,
        modelFileCount = 1,
        chunkCount = 128,
    )

    /** 未就绪：无模型 + 无候选文件（E1）。 */
    val notReady = IntelligenceStatus(
        level = IntelligenceLevel.NOT_READY,
        busy = IntelligenceBusy.NONE,
        model = ModelStatus.Missing,
        spaces = SpaceStatus.None,
        gpu = GpuStatus.UNKNOWN,
        llm = LlmStatus.Unknown,
        pending = emptyList(),
        modelFileCount = 0,
        chunkCount = 0,
        sagEnabled = false,
        networkDisabled = true,
    )

    /** 异常：模型文件损坏（E15）。 */
    val corrupt = IntelligenceStatus(
        level = IntelligenceLevel.ERROR,
        busy = IntelligenceBusy.NONE,
        model = ModelStatus.Corrupt,
        spaces = SpaceStatus.Unknown,
        gpu = GpuStatus.OFF,
        llm = LlmStatus.LoadError,
        pending = listOf(PendingItem.INDEX_UNHEALTHY),
        modelFileCount = 1,
        chunkCount = 64,
    )

    /** 就绪 + 下载中（E12）：带确定进度。 */
    val downloading = IntelligenceStatus(
        level = IntelligenceLevel.READY,
        busy = IntelligenceBusy.DOWNLOADING,
        busyPct = 42,
        busyLabel = "embeddinggemma-2-440m",
        model = ModelStatus.Ready("embeddinggemma-2-440m"),
        spaces = SpaceStatus.Ready(2),
        gpu = GpuStatus.AUTO,
        llm = LlmStatus.Configured,
        pending = emptyList(),
        modelFileCount = 1,
        chunkCount = 128,
    )

    /** 就绪 + 同步中（E13）：带确定进度。 */
    val syncing = IntelligenceStatus(
        level = IntelligenceLevel.READY,
        busy = IntelligenceBusy.SYNCING,
        busyPct = 30,
        busyDone = 30,
        busyTotal = 100,
        model = ModelStatus.Ready("embeddinggemma-2-440m"),
        spaces = SpaceStatus.Ready(2),
        gpu = GpuStatus.AUTO,
        llm = LlmStatus.Configured,
        pending = emptyList(),
        modelFileCount = 1,
        chunkCount = 128,
    )
}

@Preview(name = "智能状态中枢 · Light", showBackground = true, heightDp = 720)
@Composable
private fun IntelligenceHubLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.partial,
                counts = IntelligenceHubCounts(legacySpaces = 3, extractionFailed = 12),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}

@Preview(name = "智能状态中枢 · Dark", showBackground = true, heightDp = 720)
@Composable
private fun IntelligenceHubDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.partial,
                counts = IntelligenceHubCounts(legacySpaces = 3, extractionFailed = 12),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}

@Preview(name = "智能状态中枢 · 未就绪（空态）", showBackground = true, heightDp = 640)
@Composable
private fun IntelligenceHubNotReadyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.notReady,
                counts = IntelligenceHubCounts(),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}

@Preview(name = "智能状态中枢 · 异常（模型损坏）", showBackground = true, heightDp = 640)
@Composable
private fun IntelligenceHubCorruptPreview() {
    ZhiBoxTheme(darkTheme = true) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.corrupt,
                counts = IntelligenceHubCounts(),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}

@Preview(name = "智能状态中枢 · 下载中（E12）", showBackground = true, heightDp = 640)
@Composable
private fun IntelligenceHubDownloadingPreview() {
    ZhiBoxTheme(darkTheme = false) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.downloading,
                counts = IntelligenceHubCounts(),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}

@Preview(name = "智能状态中枢 · 同步中（E13）", showBackground = true, heightDp = 640)
@Composable
private fun IntelligenceHubSyncingPreview() {
    ZhiBoxTheme(darkTheme = true) {
        Surface(color = MaterialTheme.zhiBoxColors.card) {
            IntelligenceHubContent(
                status = FakeHub.syncing,
                counts = IntelligenceHubCounts(),
                onClose = {},
                onOpenSection = {},
            )
        }
    }
}
