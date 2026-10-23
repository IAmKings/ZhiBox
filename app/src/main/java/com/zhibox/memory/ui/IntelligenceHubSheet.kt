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
 * 智能状态中枢面板（M2 P1-1）。
 *
 * 规格：`docs/product/INTELLIGENCE_HUB_SPEC.md` §6（IA/线框）、§8.3/§8.4（文案）、§9（视觉令牌）。
 *
 * 信息架构（首屏"三问"，§6.2）：
 * 1. 能不能用？ → 标题 + 一句话结论（按 [IntelligenceLevel] 四态）；
 * 2. 哪里没配好？ → 4 个只读 pill（模型 / 空间 / GPU / LLM），超长横向滚动；
 * 3. 下一步点什么？ → 快捷动作 4 行（嵌入模型 / 嵌入路径 / GPU 加速 / LLM 配置）+ 底部「打开设置 · 智能」。
 *
 * 禁令（§6.4）：面板内不放任何输入框/表单/开关组；需要输入的（LLM Key、目录选择）一律跳设置。
 *
 * **与规格的偏差（有意降级）**：§6.4/§6.5 允许"就地写"的幂等动作（重试抽取 / 修复索引）在 M2 不做，
 * 因为面板只拿到 [IntelligenceStatus] 快照，没有 ViewModel/仓库引用。待处理行统一降级为深链
 * （旧空间 → [IntelligenceSection.Migration]，抽取失败 → [IntelligenceSection.Sag]，
 * 索引 → [IntelligenceSection.Diagnostics]），满足"每个写操作 ≤1 击到达目标"的验收口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceHubSheet(
    status: IntelligenceStatus,
    onDismissRequest: () -> Unit,
    onOpenSection: (IntelligenceSection?) -> Unit,
    counts: IntelligenceHubCounts = IntelligenceHubCounts(),
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
            HubConclusion(status = status)
            HubPills(status = status)

            HubSectionTitle(stringResource(R.string.intelligence_section_quick))
            HubQuickActions(status = status, onOpenSection = onOpenSection)

            if (status.pending.isNotEmpty()) {
                HubSectionTitle(
                    stringResource(R.string.intelligence_section_pending, status.pendingCount),
                )
                HubPendingList(
                    rows = pendingRows(status = status, counts = counts),
                    onOpenSection = onOpenSection,
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
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = ZhiBoxIcons.Close,
                contentDescription = stringResource(R.string.intelligence_close),
                modifier = Modifier.size(18.dp),
                tint = colors.inkMeta,
            )
        }
    }
}

/** 一句话结论 + 空态补充行（§6.2 问题 1；§8.4 E1/E3/E15）。 */
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

    // 空态/异常补充：只加一行说明，不放输入框（§6.4 禁令）。
    val hint = when {
        // E15：模型文件损坏 → 需要重新下载
        status.model is ModelStatus.Corrupt -> stringResource(R.string.intelligence_model_corrupt)
        // E1：没有任何模型 → 给出下一步（下载后可导入/离线检索）
        status.model is ModelStatus.Missing -> stringResource(R.string.intelligence_onboarding_message)
        // E3：模型就绪但知识库为空（空库仍是 READY，不做灰色惩罚）
        status.spaces is SpaceStatus.None -> stringResource(R.string.intelligence_empty_kb)
        else -> null
    }
    if (hint != null) {
        Text(
            text = hint,
            fontSize = 12.sp,
            color = colors.inkMeta,
            modifier = Modifier.padding(top = 6.dp),
        )
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
    val section: IntelligenceSection,
)

/**
 * 待处理行：旧空间 / 抽取失败 / 索引 / 授权丢失 / LLM 读取失败。
 *
 * 数量来自 [IntelligenceHubCounts]（= IntelligenceStatus 的两个计数字段），
 * 行文案与 §8.3 的 `%1$d` 文案一一对应；枚举与计数不一致时（理论上不会发生）
 * 以计数为准，0 表示该行不展示。
 */
@Composable
private fun pendingRows(
    status: IntelligenceStatus,
    counts: IntelligenceHubCounts,
): List<HubPendingRowData> = buildList {
    val handle = stringResource(R.string.intelligence_pending_action_handle)
    val detail = stringResource(R.string.intelligence_pending_action_detail)
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
                    section = IntelligenceSection.Migration,
                ),
            )
            PendingItem.EXTRACTION_FAILED -> add(
                HubPendingRowData(
                    text = stringResource(
                        R.string.intelligence_pending_extraction,
                        counts.extractionFailed,
                    ),
                    action = detail,
                    section = IntelligenceSection.Sag,
                ),
            )
            PendingItem.INDEX_UNHEALTHY -> add(
                HubPendingRowData(
                    text = stringResource(R.string.intelligence_pending_index),
                    action = detail,
                    section = IntelligenceSection.Diagnostics,
                ),
            )
            PendingItem.STORE_PERMISSION_LOST -> add(
                HubPendingRowData(
                    text = stringResource(R.string.settings_store_permission_lost),
                    action = handle,
                    section = IntelligenceSection.ModelStoreShared,
                ),
            )
            PendingItem.LLM_LOAD_ERROR -> add(
                HubPendingRowData(
                    text = stringResource(
                        R.string.settings_llm_load_failed,
                        stringResource(R.string.intelligence_pill_value_unknown),
                    ),
                    action = detail,
                    section = IntelligenceSection.Llm,
                ),
            )
        }
    }
}

@Composable
private fun HubPendingList(
    rows: List<HubPendingRowData>,
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
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onOpenSection(row.section) }
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
                    color = colors.inkSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
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
    )

    /** 未就绪：无模型（E1）。 */
    val notReady = IntelligenceStatus(
        level = IntelligenceLevel.NOT_READY,
        busy = IntelligenceBusy.NONE,
        model = ModelStatus.Missing,
        spaces = SpaceStatus.None,
        gpu = GpuStatus.UNKNOWN,
        llm = LlmStatus.Unknown,
        pending = emptyList(),
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
