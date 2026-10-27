package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.zhiBoxColors

/**
 * 设置搜索页（设置首页顶栏入口进入；屏内页面，不新增顶级路由）。
 *
 * 结果只做导航：高级页跳到对应 section 并高亮；聚焦页滚动到对应卡片。
 */
@Composable
internal fun SettingsSearchContent(
    onBack: () -> Unit,
    onSelect: (SettingsSearchEntry) -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, context) {
        searchSettings(query, titleOf = context::getString)
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    ZhiBoxScreen(
        title = stringResource(R.string.settings_search_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("settings_search_field"),
                placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                singleLine = true,
                leadingIcon = {
                    Icon(ZhiBoxIcons.Search, contentDescription = stringResource(R.string.settings_search_action))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                ZhiBoxIcons.Close,
                                contentDescription = stringResource(R.string.settings_search_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            when {
                query.isBlank() -> {
                    // 无输入时只展示提示，不铺结果。
                }

                results.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.settings_search_empty),
                        message = stringResource(R.string.settings_search_empty_hint),
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp),
                    ) {
                        items(results, key = { it.id }) { entry ->
                            SettingsSearchResultRow(entry = entry, onClick = { onSelect(entry) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    entry: SettingsSearchEntry,
    onClick: () -> Unit,
) {
    ZhiBoxCard(
        Modifier
            .fillMaxWidth()
            .testTag("settings_search_result"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = null, onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.settings_search_page,
                    stringResource(entry.page.titleRes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.zhiBoxColors.inkMeta,
            )
        }
    }
}
