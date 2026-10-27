package com.zhibox.memory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.zhiBoxColors

/**
 * 简单模式设置首页（默认进入的页面）。
 *
 * 设计规则（docs/product/设置页简化方案 审查结论 §3.2 / §4.8）：
 * - 只回答 4 个用户问题：有没有模型、资料怎么进/怎么备份、要不要联网问答、隐私是什么；
 * - 每张卡 = 一句话结论（结果语言）+ 当前值 + 1 个主按钮，不出现实现术语；
 * - 所有原始标识（文件名/spaceId/错误码/维度）一律不出现在本页；
 * - 第 5 项是「高级与诊断」入口，原 17 项能力全部可达。
 */
@Composable
internal fun SettingsHomeContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    val summary = settingsHomeSummary(state)
    ZhiBoxScreen(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = ZhiBoxIcons.Search,
                    contentDescription = stringResource(R.string.settings_search_title),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = SettingsListContentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (summary.loading) {
                item(key = "home-loading") {
                    LoadingState(message = stringResource(R.string.settings_loading))
                }
            }
            if (summary.hasError) {
                item(key = "home-error") {
                    HomeErrorLine(onRetry = actions.retryRefresh)
                }
            }
            item(key = "home-ai") {
                HomeAiCard(
                    summary = summary,
                    onManage = { onOpenPage(SettingsPage.MODELS) },
                )
            }
            item(key = "home-library") {
                HomeLibraryCard(
                    summary = summary,
                    onImport = onOpenImport,
                    onExport = actions.exportBackup,
                    onOpenLibrary = { onOpenPage(SettingsPage.LIBRARY) },
                )
            }
            item(key = "home-network") {
                HomeNetworkCard(
                    summary = summary,
                    onToggleAnswer = { enabled ->
                        if (enabled) {
                            // 先放行网络，再开问答：问答开关的 shell 包装会在未同意时弹
                            // 首次联网说明并保持 answerEnabled=false，同意后一并生效。
                            actions.setNetworkDisabled(false)
                            actions.setAnswerEnabled(true)
                        } else {
                            actions.setAnswerEnabled(false)
                        }
                    },
                    onConfigure = { onOpenPage(SettingsPage.NETWORK) },
                )
            }
            item(key = "home-privacy") {
                HomePrivacyCard(onOpen = { onOpenPage(SettingsPage.PRIVACY) })
            }
            item(key = "home-advanced") {
                HomeAdvancedRow(onOpen = { onOpenPage(SettingsPage.ADVANCED) })
            }
        }
    }
}

// ───────────────────────── 卡片 ─────────────────────────

/** 首页统一的卡片骨架：可点击的标题行（结论 + 当前值）+ 可选动作行。 */
@Composable
private fun HomeCard(
    title: String,
    status: String,
    onClick: (() -> Unit)? = null,
    testTag: String = SETTINGS_HOME_CARD_TAG,
    actions: @Composable () -> Unit = {},
) {
    ZhiBoxCard(
        Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val headerModifier = if (onClick != null) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            } else {
                Modifier.fillMaxWidth()
            }
            Row(
                modifier = headerModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onClick != null) {
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.zhiBoxColors.inkMeta,
                    )
                }
            }
            actions()
        }
    }
}

@Composable
private fun HomeAiCard(summary: SettingsHomeSummary, onManage: () -> Unit) {
    val tierLabel = stringResource(summary.modelTier.labelRes())
    val status = when {
        summary.downloading -> stringResource(
            R.string.settings_home_ai_downloading,
            summary.downloadPct.coerceAtLeast(0),
        )
        summary.modelTier == SettingsModelTier.NONE -> stringResource(R.string.settings_home_ai_status_missing)
        summary.modelTier == SettingsModelTier.CUSTOM ->
            stringResource(R.string.settings_home_ai_status_custom)
        summary.indexed -> stringResource(R.string.settings_home_ai_status_ready, tierLabel)
        else -> stringResource(R.string.settings_home_ai_status_not_indexed, tierLabel)
    }
    HomeCard(
        title = stringResource(R.string.settings_home_ai_title),
        status = status,
        onClick = onManage,
    ) {
        if (summary.downloading) {
            LinearProgressIndicator(
                progress = { summary.downloadPct.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(onClick = onManage) {
            Text(stringResource(R.string.settings_home_manage))
        }
    }
}

@Composable
private fun HomeLibraryCard(
    summary: SettingsHomeSummary,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val status = when {
        !summary.hasLibraryContent -> stringResource(R.string.settings_home_library_empty)
        summary.indexed -> stringResource(
            R.string.settings_home_library_status,
            summary.docsReady,
            summary.chunks,
        )
        else -> stringResource(R.string.settings_home_library_pending, summary.docsReady)
    }
    HomeCard(
        title = stringResource(R.string.settings_home_library_title),
        status = status,
        onClick = onOpenLibrary,
    ) {
        if (summary.legacyCount > 0) {
            Text(
                stringResource(R.string.settings_home_library_legacy, summary.legacyCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport) { Text(stringResource(R.string.settings_home_import)) }
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.settings_home_export)) }
        }
    }
}

@Composable
private fun HomeNetworkCard(
    summary: SettingsHomeSummary,
    onToggleAnswer: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    val status = when {
        !summary.llmLoaded -> stringResource(R.string.settings_home_network_loading)
        !summary.llmConfigured -> stringResource(R.string.settings_home_network_no_service)
        !summary.networkAllowed -> stringResource(R.string.settings_home_network_blocked)
        summary.answerEnabled -> stringResource(R.string.settings_home_network_on)
        else -> stringResource(R.string.settings_home_network_off)
    }
    HomeCard(
        title = stringResource(R.string.settings_home_network_title),
        status = status,
        onClick = onConfigure,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val switchLabel = stringResource(R.string.settings_home_network_switch)
            Text(
                switchLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = summary.networkAnswerOn,
                onCheckedChange = onToggleAnswer,
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        }
        TextButton(onClick = onConfigure) {
            Text(stringResource(R.string.settings_home_network_config))
        }
    }
}

@Composable
private fun HomePrivacyCard(onOpen: () -> Unit) {
    HomeCard(
        title = stringResource(R.string.settings_home_privacy_title),
        status = stringResource(R.string.settings_home_privacy_status),
        onClick = onOpen,
    ) {
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.settings_home_privacy_more))
        }
    }
}

/** 高级与诊断入口：整行可点、最小 56dp、右侧文本 chevron（与 HubActionRow 同构，不新增图标）。 */
@Composable
private fun HomeAdvancedRow(onOpen: () -> Unit) {
    ZhiBoxCard(
        Modifier
            .fillMaxWidth()
            .testTag(SETTINGS_HOME_CARD_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = null, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_home_advanced_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.settings_home_advanced_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.zhiBoxColors.inkMeta,
            )
        }
    }
}

/** 首页错误只给固定文案 + 重试，不展示原始异常（错误原文只在高级页可见）。 */
@Composable
private fun HomeErrorLine(onRetry: () -> Unit) {
    ZhiBoxCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_home_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** 首页卡片统一 testTag：验收要求「首屏可见卡片 ≤5」。 */
internal const val SETTINGS_HOME_CARD_TAG = "settings_home_card"
