package com.zhibox.memory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.agent.AgentFallbackReasons
import com.zhibox.memory.core.agent.AgentToolCall
import com.zhibox.memory.core.chat.ChatCitation
import com.zhibox.memory.core.chat.ChatConversation
import com.zhibox.memory.core.chat.ChatMessage
import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * 带引用问答屏（B11 P0 单轮）：会话列表/新建/切换 → 输入问题 → 检索证据 → 流式回答 →
 * 引用 chips 点击回溯原文（复用 [EvidenceScreen]）。
 *
 * - 离线/未配置 LLM 时明确提示（问答生成不可用，检索与其它功能不受影响）；
 * - 首次联网前必须确认隐私说明（[ChatViewModel.state].consentRequired 弹窗）；
 * - 所有答案通过引用校验后才会持久化；未引用/无效引用显示警示；
 * - 小屏可滚动：消息区 LazyColumn(weight=1f)，输入区固定底部。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
    }

    // 证据详情覆盖层（引用 chip 点开）：系统返回键先关闭证据页。
    val evidenceHit = state.selectedEvidenceHit
    if (evidenceHit != null) {
        BackHandler { viewModel.clearSelectedEvidence() }
        EvidenceScreen(hit = evidenceHit, onBack = { viewModel.clearSelectedEvidence() })
        return
    }

    if (state.consentRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text(stringResource(R.string.chat_consent_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_consent_message_host,
                        state.llmHost.ifBlank { stringResource(R.string.chat_configured_llm) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmConsentAndSend) {
                    Text(stringResource(R.string.chat_consent_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConsent) {
                    Text(stringResource(R.string.chat_consent_cancel))
                }
            },
        )
    }

    ChatContent(
        state = state,
        onInputChange = viewModel::setInput,
        onSend = viewModel::send,
        onStop = viewModel::stopGeneration,
        onRetry = viewModel::retry,
        onNewConversation = viewModel::newConversation,
        onSelectConversation = viewModel::selectConversation,
        onModeChange = viewModel::setRetrievalMode,
        onSourceScopeChange = viewModel::setSourceScope,
        onCitationClick = viewModel::openCitation,
        onDismissError = viewModel::clearError,
        onDismissWarning = viewModel::clearWarning,
        onBack = onBack,
        onAgentEnabledChange = viewModel::setAgentEnabled,
    )
}

/** 无状态问答内容：供 [ChatScreen] 与 @Preview / UI 测试直接渲染。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatContent(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onModeChange: (RetrievalMode?) -> Unit = {},
    onSourceScopeChange: (List<String>) -> Unit = {},
    onCitationClick: (ChatCitation) -> Unit,
    onDismissError: () -> Unit,
    onDismissWarning: () -> Unit,
    onBack: () -> Unit,
    onAgentEnabledChange: (Boolean) -> Unit = {},
) {
    ZhiBoxScreen(title = stringResource(R.string.chat_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- 会话列表 / 新建 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onNewConversation, enabled = !state.streaming) {
                    Text(stringResource(R.string.chat_new_conversation))
                }
                Text(
                    state.current?.title
                        ?: if (state.conversations.isEmpty()) {
                            stringResource(R.string.chat_no_conversations)
                        } else {
                            stringResource(R.string.chat_conversations)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.conversations.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        FilterChip(
                            selected = state.current?.id == conversation.id,
                            onClick = { onSelectConversation(conversation.id) },
                            enabled = !state.streaming,
                            label = {
                                Text(conversation.title.take(CONVERSATION_CHIP_CHARS))
                            },
                        )
                    }
                }
            }

            // ---- B11：检索模式（自动/混合/SAG 快速/SAG 精排） ----
            Text(
                stringResource(R.string.chat_mode_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.retrievalMode == null,
                    onClick = { onModeChange(null) },
                    label = { Text(stringResource(R.string.chat_mode_auto)) },
                )
                RetrievalMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = state.retrievalMode == candidate,
                        onClick = { onModeChange(candidate) },
                        label = { Text(modeLabel(candidate)) },
                    )
                }
            }

            // ---- B11：来源范围（空=全库） ----
            if (state.availableSources.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_source_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.sourceScope.isEmpty(),
                        onClick = { onSourceScopeChange(emptyList()) },
                        label = { Text(stringResource(R.string.chat_source_all)) },
                    )
                    state.availableSources.forEach { source ->
                        val selected = source.id in state.sourceScope
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) state.sourceScope - source.id
                                else state.sourceScope + source.id
                                onSourceScopeChange(next)
                            },
                            label = { Text(source.title.take(12)) },
                        )
                    }
                }
            }

            // ---- 可用性/隐私提示 ----
            if (!state.llmReady) {
                Text(
                    stringResource(R.string.chat_llm_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.errorMessage?.let {
                ErrorState(
                    message = it,
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = onDismissError,
                )
            }
            state.warning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissWarning) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
            state.infoMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.degradedReason != null) {
                Text(
                    stringResource(R.string.chat_degraded_info, state.degradedReason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // ---- P1.4：Agent 工具（默认关；仅 llmReady 可用）+ 工具步骤/回退原因 ----
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.chat_agent_toggle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = state.agentEnabled,
                        onCheckedChange = onAgentEnabledChange,
                        enabled = state.llmReady && !state.streaming,
                    )
                }
                if (!state.llmReady) {
                    Text(
                        stringResource(R.string.chat_agent_unavailable_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.agentAttempted || state.agentToolCalls.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.chat_agent_steps_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.agentRunning) {
                                Text(
                                    stringResource(R.string.chat_agent_running),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // 只展示工具名/成功失败/错误码，不展示 observation/结果正文
                            state.agentToolCalls.forEach { call ->
                                Text(
                                    stringResource(
                                        R.string.chat_agent_step_line,
                                        agentToolLabel(call),
                                        if (call.errorCode == null) {
                                            stringResource(R.string.chat_agent_step_ok)
                                        } else {
                                            stringResource(
                                                R.string.chat_agent_step_failed,
                                                call.errorCode,
                                            )
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (call.errorCode == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            val fallbackReason = state.agentFallbackReason
                            if (!state.agentRunning && fallbackReason != null) {
                                Text(
                                    stringResource(
                                        R.string.chat_agent_fallback,
                                        agentFallbackReasonText(fallbackReason),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 消息区（可滚动，避免小屏挤压）----
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty() && !state.streaming) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(R.string.chat_empty_message),
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageBubble(
                        message = message,
                        citations = state.citations[message.id].orEmpty(),
                        onCitationClick = onCitationClick,
                    )
                }
                if (state.streaming) {
                    item(key = "streaming") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.chat_generating),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    state.streamingText.ifBlank { "…" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                state.stoppedPartial?.let { partial ->
                    item(key = "stopped") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.chat_stopped_partial),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(partial, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ---- 输入区（固定底部）+ 停止/重试 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 3,
                    enabled = !state.streaming,
                )
                if (state.streaming) {
                    OutlinedButton(onClick = onStop) {
                        Text(stringResource(R.string.chat_stop))
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = state.input.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
            if (!state.streaming && state.canRetry) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
        }
    }
}

/** P1.4：Agent 工具名可读标签（未知工具名原样展示；不展示 input/结果正文）。 */
@Composable
private fun agentToolLabel(call: AgentToolCall): String = when (call.name) {
    "search_knowledge" -> stringResource(R.string.chat_agent_tool_search_knowledge)
    "lookup_entities" -> stringResource(R.string.chat_agent_tool_lookup_entities)
    else -> call.name
}

/** P1.4：Agent fallback 原因码 → 可读文案（未知码原样展示）。 */
@Composable
private fun agentFallbackReasonText(reason: String): String = when (reason) {
    AgentFallbackReasons.NOT_CONFIGURED -> stringResource(R.string.chat_agent_fallback_not_configured)
    AgentFallbackReasons.CONFIG_ERROR -> stringResource(R.string.chat_agent_fallback_config_error)
    AgentFallbackReasons.NO_TOOLS -> stringResource(R.string.chat_agent_fallback_no_tools)
    AgentFallbackReasons.EMPTY_QUERY -> stringResource(R.string.chat_agent_fallback_empty_query)
    AgentFallbackReasons.PROVIDER_ERROR -> stringResource(R.string.chat_agent_fallback_provider_error)
    AgentFallbackReasons.PROVIDER_EXCEPTION ->
        stringResource(R.string.chat_agent_fallback_provider_exception)
    AgentFallbackReasons.BAD_JSON -> stringResource(R.string.chat_agent_fallback_bad_json)
    AgentFallbackReasons.UNKNOWN_ACTION -> stringResource(R.string.chat_agent_fallback_unknown_action)
    AgentFallbackReasons.BAD_INPUT -> stringResource(R.string.chat_agent_fallback_bad_input)
    AgentFallbackReasons.TOOL_EXCEPTION -> stringResource(R.string.chat_agent_fallback_tool_exception)
    AgentFallbackReasons.TOOL_ERROR -> stringResource(R.string.chat_agent_fallback_tool_error)
    AgentFallbackReasons.MAX_TOOL_CALLS -> stringResource(R.string.chat_agent_fallback_max_calls)
    else -> reason
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    citations: List<ChatCitation>,
    onCitationClick: (ChatCitation) -> Unit,
) {
    val isUser = message.role == ChatRole.USER
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isUser) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (isUser) "Q" else "A",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
            if (!isUser) {
                if (citations.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_no_citation_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        citations.forEach { citation ->
                            AssistChip(
                                onClick = { onCitationClick(citation) },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.chat_citation_chip,
                                            citation.evidenceId,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                message.model?.let { model ->
                    Text(
                        model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val CONVERSATION_CHIP_CHARS = 12

// ───────────────────────── B11 @Preview（浅色/深色/空态/不可用/流式） ─────────────────────────

private val previewConversation = ChatConversation(
    id = "conv_1",
    title = "蓝牙断开怎么恢复",
    sourceScope = emptyList(),
    createdAt = 1_700_000_000_000,
    updatedAt = 1_700_000_100_000,
)

private val previewMessages = listOf(
    ChatMessage(
        id = "msg_1",
        conversationId = "conv_1",
        role = ChatRole.USER,
        content = "蓝牙断开怎么恢复？",
        createdAt = 1_700_000_000_000,
        model = null,
    ),
    ChatMessage(
        id = "msg_2",
        conversationId = "conv_1",
        role = ChatRole.ASSISTANT,
        content = "先关闭耳机电源再长按配对键 5 秒，进入可发现状态后重新配对 [S1]。若仍失败，可关闭省电优化后重试 [S2]。",
        createdAt = 1_700_000_100_000,
        model = "gpt-4o-mini",
    ),
)

private val previewCitations = mapOf(
    "msg_2" to listOf(
        ChatCitation(evidenceId = "S1", chunkId = 1, rank = 0),
        ChatCitation(evidenceId = "S2", chunkId = 2, rank = 1),
    ),
)

private fun previewChatState(
    messages: List<ChatMessage> = previewMessages,
    citations: Map<String, List<ChatCitation>> = previewCitations,
) = ChatUiState(
    conversations = listOf(previewConversation),
    current = previewConversation,
    messages = messages,
    citations = citations,
    answerEnabled = true,
    networkAllowed = true,
    llmConfigured = true,
    consentGranted = true,
    canRetry = true,
    lastQuery = "蓝牙断开怎么恢复？",
    effectiveMode = "multi",
)

@Preview(showBackground = true, name = "Chat · Light")
@Composable
private fun ChatLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ChatContent(
            state = previewChatState(),
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSelectConversation = {},
            onCitationClick = {},
            onDismissError = {},
            onDismissWarning = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Chat · Dark")
@Composable
private fun ChatDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        ChatContent(
            state = previewChatState().copy(
                streaming = true,
                streamingText = "正在根据证据生成回答…",
                infoMessage = "生成中…",
            ),
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSelectConversation = {},
            onCitationClick = {},
            onDismissError = {},
            onDismissWarning = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Chat · Empty")
@Composable
private fun ChatEmptyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ChatContent(
            state = ChatUiState(
                answerEnabled = true,
                networkAllowed = true,
                llmConfigured = true,
            ),
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSelectConversation = {},
            onCitationClick = {},
            onDismissError = {},
            onDismissWarning = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Chat · LLM not ready")
@Composable
private fun ChatNotReadyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ChatContent(
            state = ChatUiState(answerEnabled = false, networkAllowed = false),
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSelectConversation = {},
            onCitationClick = {},
            onDismissError = {},
            onDismissWarning = {},
            onBack = {},
        )
    }
}

/** Chat 模式标签（复用 Search 的模式资源，避免重复文案）。 */
@Composable
private fun modeLabel(mode: RetrievalMode): String = when (mode) {
    RetrievalMode.VECTOR -> stringResource(R.string.search_mode_vector)
    RetrievalMode.MULTI_ES_FAST -> stringResource(R.string.search_mode_multi_es_fast)
    RetrievalMode.MULTI -> stringResource(R.string.search_mode_multi)
}
