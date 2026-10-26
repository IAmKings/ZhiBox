package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhibox.memory.BuildConfig
import com.zhibox.memory.R
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.ui.theme.zhiBoxColors

/**
 * 聚焦二级页顶部的「结论卡」（hero）。
 *
 * 这些卡片只回答该页对应的一件事，并把需要细节的操作留在同页下方的原始卡片里；
 * 不展示原始文件名/spaceId/错误码（术语黑名单见 `SettingsCopyAuditTest`）。
 */

/** 模型管理页 hero：当前档位 + 自动/轻量/标准/更强四档切换（切换前由 shell 二次确认）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsModelsHero(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val tier = settingsModelTier(state.model)
    ZhiBoxCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.settings_hero_current_model),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(tier.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.downloadPct.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.settings_home_ai_downloading,
                        state.downloadPct.coerceAtLeast(0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    PathPreferences.AUTO to R.string.settings_tier_auto,
                    PathPreferences.B to R.string.settings_tier_270,
                    PathPreferences.A to R.string.settings_tier_440,
                    PathPreferences.D to R.string.settings_tier_740,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = state.preference == value,
                        onClick = { actions.requestPreference(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            Text(
                stringResource(R.string.settings_model_switch_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 资料库页 hero：资料/片段计数、备份入口、旧数据同步进度。 */
@Composable
internal fun SettingsLibraryPageHero(
    state: SettingsUiState,
    actions: SettingsActions,
    onImport: () -> Unit = {},
) {
    val summary = settingsHomeSummary(state)
    ZhiBoxCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.settings_hero_library_overview),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    !summary.hasLibraryContent -> stringResource(R.string.settings_home_library_empty)
                    summary.indexed -> stringResource(
                        R.string.settings_home_library_status,
                        summary.docsReady,
                        summary.chunks,
                    )
                    else -> stringResource(R.string.settings_home_library_pending, summary.docsReady)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (summary.legacyCount > 0) {
                Text(
                    stringResource(R.string.settings_home_library_legacy, summary.legacyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (summary.reembedActive) {
                LinearProgressIndicator(
                    progress = {
                        state.reembedDone.toFloat() / state.reembedTotal.coerceAtLeast(1)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.settings_reembed_progress,
                        state.reembedDone,
                        state.reembedTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) {
                    Text(stringResource(R.string.settings_home_import))
                }
                OutlinedButton(onClick = actions.exportBackup) {
                    Text(stringResource(R.string.settings_home_export))
                }
            }
            OutlinedButton(
                onClick = actions.restoreBackup,
                enabled = state.hasAutoBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_restore_latest))
            }
        }
    }
}

/** 联网问答页 hero：复合开关（问答生成 + 允许联网）与服务状态。 */
@Composable
internal fun SettingsNetworkPageHero(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenAdvanced: () -> Unit = {},
) {
    val summary = settingsHomeSummary(state)
    val status = when {
        !summary.llmLoaded -> stringResource(R.string.settings_home_network_loading)
        !summary.llmConfigured -> stringResource(R.string.settings_home_network_no_service)
        !summary.networkAllowed -> stringResource(R.string.settings_home_network_blocked)
        summary.answerEnabled -> stringResource(R.string.settings_home_network_on)
        else -> stringResource(R.string.settings_home_network_off)
    }
    ZhiBoxCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.settings_home_network_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
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
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            actions.setNetworkDisabled(false)
                            actions.setAnswerEnabled(true)
                        } else {
                            actions.setAnswerEnabled(false)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenAdvanced) {
                Text(stringResource(R.string.settings_network_more_advanced))
            }
        }
    }
}

/** 隐私与关于页 hero：应用名与版本（隐私声明全文由下方原始卡片展示）。 */
@Composable
internal fun SettingsPrivacyPageHero() {
    ZhiBoxCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_privacy_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.zhiBoxColors.inkMeta,
            )
        }
    }
}
