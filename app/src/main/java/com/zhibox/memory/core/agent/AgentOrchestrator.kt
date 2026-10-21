package com.zhibox.memory.core.agent

import com.zhibox.memory.core.chat.ChatEvidence
import com.zhibox.memory.core.chat.ChatTurn
import com.zhibox.memory.core.llm.LlmCompletionProvider
import com.zhibox.memory.core.llm.LlmCompletionResult
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * P1.4 / B16：受控 Agent 工具规划器 v1（纯 JVM，不依赖 Android/DB）。
 *
 * 流程：
 * 1. 加载 [LlmConfigStore]：未配置/读取失败/无工具/空 query → 确定性 [AgentRunResult] fallback；
 * 2. 每步用 [LlmCompletionProvider.complete] + 固定 [AgentPrompt] 生成严格 JSON 规划；
 * 3. 执行注册工具（最多 [maxToolCalls] 次），observation（限长）进入下一轮 prompt，
 *    工具 evidence 收集并在最终结果按 chunkId 去重；
 * 4. 收到 `{"action":"final"}` → 返回 `usedAgent=true` + calls/observations/evidence；
 * 5. 任何规划/工具异常/坏 JSON/未知 action/超预算 → 确定性 fallback（`usedAgent=false`，
 *    evidence 为空，调用方退回普通检索），**不向问答主流程抛业务异常**；
 * 6. `CancellationException` 始终原样传播；不记录日志、不持久化 query/输入/观测/正文。
 *
 * 边界：本类**不生成最终答案**；调用方必须把 [AgentRunResult.evidence] 传给
 * `AnswerGenerator` 并经 `CitationValidator` 校验引用。
 *
 * @param provider LLM completion（实现需保证禁网/HTTPS/错误值返回约定）
 * @param store 配置读取（仅 load；不写）
 * @param tools 允许的工具 map（key=action 名；未注册工具一律拒绝）
 * @param maxToolCalls 工具调用次数上限（默认 2；0 表示只允许 final）
 * @param plannerMaxTokens planner 输出 token 上限（JSON 很短，默认 256）
 */
class AgentOrchestrator(
    private val provider: LlmCompletionProvider,
    private val store: LlmConfigStore,
    private val tools: Map<String, AgentTool>,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    private val plannerMaxTokens: Int = DEFAULT_PLANNER_MAX_TOKENS,
    /** 单次 planner 调用超时；超时按 PROVIDER_ERROR 确定性回退。 */
    private val plannerTimeoutMs: Long = DEFAULT_PLANNER_TIMEOUT_MS,
    /** 单次工具执行超时；超时按 TOOL_EXCEPTION 确定性回退。 */
    private val toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
) {

    private val maxCalls: Int = maxToolCalls.coerceAtLeast(0)
    private val plannerTimeout: Long = plannerTimeoutMs.coerceAtLeast(1L)
    private val toolTimeout: Long = toolTimeoutMs.coerceAtLeast(1L)
    private val toolSpecs: List<AgentToolSpec> = tools.map { (name, tool) ->
        AgentToolSpec(name = name, description = AgentPrompt.safeToolDescription(tool.spec.description))
    }

    /**
     * 规划一次问答所需的工具调用（不生成答案）。
     *
     * @param query 用户问题（只作数据；空 → fallback）
     * @param history 历史对话（仅上下文，不是证据）
     */
    suspend fun plan(query: String, history: List<ChatTurn> = emptyList()): AgentRunResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return fallback(AgentFallbackReasons.EMPTY_QUERY)
        }
        if (tools.isEmpty()) {
            return fallback(AgentFallbackReasons.NO_TOOLS)
        }
        var config: LlmConfig? = null
        var configLoadFailed = false
        try {
            config = store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            configLoadFailed = true
        }
        if (config == null) {
            return fallback(
                if (configLoadFailed) AgentFallbackReasons.CONFIG_ERROR else AgentFallbackReasons.NOT_CONFIGURED,
            )
        }

        val calls = ArrayList<AgentToolCall>()
        val observations = ArrayList<String>()
        val evidenceByChunk = LinkedHashMap<Long, ChatEvidence>()
        while (true) {
            val systemPrompt = AgentPrompt.systemPrompt(toolSpecs, maxCalls)
            val userPrompt = AgentPrompt.userPrompt(
                query = trimmedQuery,
                history = history,
                observations = observations,
                remainingToolCalls = (maxCalls - calls.size).coerceAtLeast(0),
            )
            val completion = try {
                withTimeout(plannerTimeout) {
                    provider.complete(
                        config = config,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = plannerMaxTokens,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // planner 超时：不拖垮问答主流程，回退普通检索。
                return fallback(AgentFallbackReasons.PROVIDER_ERROR, calls, observations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fallback(AgentFallbackReasons.PROVIDER_EXCEPTION, calls, observations)
            }
            val plannedText = when (completion) {
                is LlmCompletionResult.Success -> completion.text
                is LlmCompletionResult.Failure -> return fallback(
                    AgentFallbackReasons.PROVIDER_ERROR, calls, observations,
                )
            }
            val planResult = AgentResponseParser.parse(plannedText, tools.keys)
            val plan = when (planResult) {
                is AgentPlanParseResult.Success -> planResult.plan
                is AgentPlanParseResult.Failure -> return fallback(planResult.reason, calls, observations)
            }
            when (plan) {
                AgentPlan.Final -> {
                    return AgentRunResult(
                        usedAgent = true,
                        calls = calls.toList(),
                        observations = observations.toList(),
                        evidence = evidenceByChunk.values.toList(),
                        fallbackReason = null,
                    )
                }
                is AgentPlan.ToolCall -> {
                    if (calls.size >= maxCalls) {
                        // 超出工具预算：确定性 fallback（不执行第 N+1 次工具）
                        return fallback(AgentFallbackReasons.MAX_TOOL_CALLS, calls, observations)
                    }
                    val tool = tools[plan.name]
                        ?: return fallback(AgentFallbackReasons.UNKNOWN_ACTION, calls, observations)
                    val toolResult = try {
                        withTimeout(toolTimeout) { tool.execute(plan.input) }
                    } catch (e: TimeoutCancellationException) {
                        calls += AgentToolCall(plan.name, plan.input, AgentFallbackReasons.TOOL_EXCEPTION)
                        return fallback(AgentFallbackReasons.TOOL_EXCEPTION, calls, observations)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        calls += AgentToolCall(plan.name, plan.input, AgentFallbackReasons.TOOL_EXCEPTION)
                        return fallback(AgentFallbackReasons.TOOL_EXCEPTION, calls, observations)
                    }
                    if (toolResult.errorCode != null) {
                        calls += AgentToolCall(plan.name, plan.input, toolResult.errorCode)
                        return fallback(AgentFallbackReasons.TOOL_ERROR, calls, observations)
                    }
                    calls += AgentToolCall(plan.name, plan.input, errorCode = null)
                    val observation = toolResult.observation.trim().take(MAX_OBSERVATION_CHARS)
                    if (observation.isNotEmpty() && observations.none { it == observation }) {
                        observations += observation
                    }
                    toolResult.evidence.forEach { chatEvidence ->
                        evidenceByChunk.putIfAbsent(chatEvidence.chunkId, chatEvidence)
                    }
                }
            }
        }
    }

    private fun fallback(
        reason: String,
        calls: List<AgentToolCall> = emptyList(),
        observations: List<String> = emptyList(),
    ): AgentRunResult = AgentRunResult(
        usedAgent = false,
        calls = calls.toList(),
        observations = observations.toList(),
        evidence = emptyList(),
        fallbackReason = reason,
    )

    companion object {
        /** 默认最多工具调用次数（B16 v1 契约：2 次工具 + 1 次 final）。 */
        const val DEFAULT_MAX_TOOL_CALLS: Int = 2

        /** planner JSON 输出 token 上限。 */
        const val DEFAULT_PLANNER_MAX_TOKENS: Int = 256

        /** planner 单次调用默认超时（毫秒）；防止 provider 挂起导致问答无限等待。 */
        const val DEFAULT_PLANNER_TIMEOUT_MS: Long = 20_000

        /** 工具单次执行默认超时（毫秒）；本地检索/DB 异常时不无限挂起。 */
        const val DEFAULT_TOOL_TIMEOUT_MS: Long = 20_000

        /** 单条 observation 进入下一轮 prompt 的最大字符数。 */
        const val MAX_OBSERVATION_CHARS: Int = 500
    }
}
