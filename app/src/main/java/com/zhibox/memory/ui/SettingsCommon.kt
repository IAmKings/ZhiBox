package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhibox.memory.R

/**
 * 设置页（简化后）的公共小部件：技术细节开关、可折叠说明。
 *
 * 只放跨页面复用且无业务状态的组件；卡片骨架仍留在各自页面文件里，避免过度抽象。
 */

/**
 * 高级页顶部固定行：「显示技术细节」总开关。
 *
 * 放在 LazyColumn 之外（固定不滚动），因此不会改变高级页任何 item 的下标——
 * `SettingsItemOrder` 的深链索引与 `SettingsItemOrderTest` 保持原样。
 */
@Composable
internal fun SettingsTechnicalDetailsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val technicalLabel = stringResource(R.string.settings_advanced_technical)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                technicalLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_advanced_technical_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = technicalLabel },
        )
    }
}

/**
 * 可折叠说明（长文案默认收起）。
 *
 * 交互与 SearchScreen 的 trace 折叠一致：点「说明 ▾」展开，展开后显示正文与「收起 ▴」。
 * 展开态用 rememberSaveable 保留（页面内滚动/重组不丢，切页后重置）。
 */
@Composable
internal fun SettingsExpandableHint(
    text: String,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(defaultExpanded) }
    Column(modifier.fillMaxWidth()) {
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { expanded = false },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Text(
                    stringResource(R.string.settings_hint_hide),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Text(
                    stringResource(R.string.settings_hint_show),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
