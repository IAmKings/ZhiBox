package com.zhibox.memory.core.agent

import com.zhibox.memory.core.chat.ChatEvidence

/**
 * P1.4 / B16：Agent 工具规划 v1 的纯 JVM 契约（不依赖 Android/DB/网络实现）。
 *
 * 设计边界：
 * - 本包只负责"规划 + 工具执行 + 证据收集"，**不生成最终回答**：最终答案仍由
 *   `AnswerGenerator` + `CitationValidator`（core/chat）负责，引用只能来自本轮 evidence；
 * - 工具仅允许调用方注册的本地只读工具；不提供 Shell/网络/写操作能力；
 * - 规划输出严格 JSON（见 [AgentPrompt] / [AgentResponseParser]）；
 * - 不写日志、不持久化 query/工具输入/观测/证据正文（trace 只应由上层记录工具名/次数/错误码）。
 */

/** 工具声明（name 必须与注册 map 的 key 一致；description 进入 planner system prompt）。 */
data class AgentToolSpec(
    val name: String,
    val description: String,
)

/**
 * 本地只读 Agent 工具。
 *
 * 实现约定：
 * - [execute] 的 input 来自 planner JSON 的 `input` 字段（已校验长度/非空/工具名已注册）；
 * - 失败以 [AgentToolResult.errorCode] 值返回（可读错误码），不要抛业务异常；
 *   `CancellationException` 必须原样向上抛；
 * - [AgentToolResult.observation] 只用于下一轮规划（会被限长），**不是最终引用**；
 * - [AgentToolResult.evidence] 是本轮检索证据快照；最终引用只能来自这些 evidence。
 */
interface AgentTool {
    val spec: AgentToolSpec

    suspend fun execute(input: String): AgentToolResult
}

/** 单次工具执行结果（失败用 errorCode 表达；observation 不含密钥/日志用途）。 */
data class AgentToolResult(
    val observation: String,
    val evidence: List<ChatEvidence> = emptyList(),
    val errorCode: String? = null,
)

/** 一次已尝试的工具调用（成功/失败都记录；errorCode=null 表示成功）。 */
data class AgentToolCall(
    val name: String,
    val input: String,
    val errorCode: String? = null,
)

/**
 * Agent 规划运行结果（不含最终答案文本）。
 *
 * @param usedAgent true = planner 正常以 `final` 结束（调用方应把 evidence 交给 AnswerGenerator）；
 *   false = 确定性 fallback（调用方必须退回普通检索/问答，不阻断主流程）
 * @param calls 已执行/尝试的工具调用（按时间序；fallback 时也可能有部分调用用于 trace）
 * @param observations 已执行工具观察（已限长/去重；只用于 trace/诊断，不作为引用）
 * @param evidence 工具返回的检索证据（仅 usedAgent=true 时非空可能；按 chunkId 去重）
 * @param fallbackReason fallback 稳定原因码（[AgentFallbackReasons]）；usedAgent=true 时为 null
 */
data class AgentRunResult(
    val usedAgent: Boolean,
    val calls: List<AgentToolCall> = emptyList(),
    val observations: List<String> = emptyList(),
    val evidence: List<ChatEvidence> = emptyList(),
    val fallbackReason: String? = null,
)

/** Agent 确定性 fallback 原因码（稳定字符串，供上层 trace/UI 提示；不含正文）。 */
object AgentFallbackReasons {
    /** LLM 未配置（store.load() 返回 null）。 */
    const val NOT_CONFIGURED: String = "AGENT_NOT_CONFIGURED"

    /** 配置读取失败（存储损坏/Keystore 异常等）。 */
    const val CONFIG_ERROR: String = "AGENT_CONFIG_ERROR"

    /** 没有任何注册工具。 */
    const val NO_TOOLS: String = "AGENT_NO_TOOLS"

    /** query 为空。 */
    const val EMPTY_QUERY: String = "AGENT_EMPTY_QUERY"

    /** provider 返回 Failure（超时/HTTP/解析等）。 */
    const val PROVIDER_ERROR: String = "AGENT_PROVIDER_ERROR"

    /** provider.complete 抛异常（非取消）。 */
    const val PROVIDER_EXCEPTION: String = "AGENT_PROVIDER_EXCEPTION"

    /** planner 输出不是严格 JSON（含额外字段/类型错误/超长）。 */
    const val BAD_JSON: String = "AGENT_BAD_JSON"

    /** planner action 未注册（未知工具或非法 action）。 */
    const val UNKNOWN_ACTION: String = "AGENT_UNKNOWN_ACTION"

    /** planner 工具 input 非法（空/超长/含控制字符）。 */
    const val BAD_INPUT: String = "AGENT_BAD_INPUT"

    /** 工具执行抛异常。 */
    const val TOOL_EXCEPTION: String = "AGENT_TOOL_EXCEPTION"

    /** 工具返回 errorCode（工具自身失败）。 */
    const val TOOL_ERROR: String = "AGENT_TOOL_ERROR"

    /** 达到工具调用次数上限后 planner 仍请求调用工具。 */
    const val MAX_TOOL_CALLS: String = "AGENT_MAX_TOOL_CALLS"
}
