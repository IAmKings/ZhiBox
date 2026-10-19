package com.zhibox.memory.core.chat

import kotlinx.coroutines.flow.Flow

/**
 * B11/B13：带引用问答的核心契约（P0 单轮；P1 多轮上下文）。
 *
 * 分层：
 * - `core/chat` 只依赖 `core/llm` 与检索结果的纯数据，不依赖 UI/DB；
 * - [AnswerGenerator] 输入本轮 query + 证据快照，输出流事件；
 * - 会话/消息/引用持久化由 `ChatRepository`（data 层实现）负责；
 * - 引用校验 [CitationValidator] 是生成后的强制门禁，任何 UI/持久化路径都必须经过它。
 */
enum class ChatRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromWire(value: String): ChatRole = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** 一个证据 chunk（由 B10 检索结果映射；[evidenceId] 固定为 `S1`…`Sn`）。 */
data class ChatEvidence(
    val evidenceId: String,
    val chunkId: Long,
    val documentId: Long,
    val sourceId: Long,
    val docTitle: String,
    val sourceTitle: String,
    val sourceUri: String,
    val content: String,
    val ordinal: Int,
    val charStart: Int,
    val charEnd: Int,
    val eventId: Long? = null,
    val score: Double? = null,
)

data class ChatConversation(
    val id: String,
    val title: String,
    val sourceScope: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val model: String?,
)

/** 稳定引用：`[S1]` → evidenceId `S1` → chunkId。 */
data class ChatCitation(
    val evidenceId: String,
    val chunkId: Long,
    val rank: Int,
)

/**
 * 历史对话轮次（P1 多轮上下文的最小输入）。
 *
 * 历史只用于理解指代/上下文，**不是证据**；调用方必须去掉历史里的 `[S#]` 标记，
 * 防止模型把旧证据编号当成本轮可引用证据。
 */
data class ChatTurn(
    val role: ChatRole,
    val content: String,
)

/** 一次问答请求（证据快照只在本轮使用；history 仅作上下文，不参与引用校验）。 */
data class AnswerRequest(
    val query: String,
    val evidence: List<ChatEvidence>,
    val history: List<ChatTurn> = emptyList(),
)

/** 流式生成事件：`Delta*` → (`Completed` | `Failed`)，或调用方取消。 */
sealed interface AnswerEvent {
    data class Delta(val text: String) : AnswerEvent

    data class Completed(
        /** 已通过引用校验的最终文本（非法引用 token 已替换为警示标记）。 */
        val text: String,
        /** 有效引用（去重、按正文出现顺序）。 */
        val citations: List<ChatCitation>,
        /** 被移除的非法 token（如 `[S9]` 无对应证据）。 */
        val invalidCitationTokens: List<String>,
        /** 没有任何有效引用但存在非空答案：UI 必须显示“未通过证据校验/未引用”警示。 */
        val unverified: Boolean,
        /** 通过规则检测的“无引用断言句”数量（0 为逐句有引用）。 */
        val unreferencedSentenceCount: Int = 0,
        val model: String,
        val latencyMs: Long,
    ) : AnswerEvent

    data class Failed(val code: String, val message: String) : AnswerEvent

    /** 用户主动停止（已生成的部分文本由调用方决定是否保留）。 */
    data object Stopped : AnswerEvent
}

/** 生成器：只使用本轮 [AnswerRequest.evidence] 作为事实依据；history 仅作上下文。 */
interface AnswerGenerator {
    fun generate(request: AnswerRequest): Flow<AnswerEvent>
}

/** 生成器构造参数（模型/超时等由 provider 配置承载，这里只放提示词预算）。 */
data class AnswerGeneratorConfig(
    val maxEvidence: Int = 10,
    val maxCharsPerEvidence: Int = 1200,
    val maxTokens: Int = 768,
    val systemPromptVersion: String = "b13-multiturn-v1",
    val maxHistoryTurns: Int = 6,
    val maxCharsPerHistoryTurn: Int = 600,
)
