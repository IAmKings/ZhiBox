package com.zhibox.memory.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** planner 严格 JSON 解析结果。 */
sealed interface AgentPlanParseResult {
    data class Success(val plan: AgentPlan) : AgentPlanParseResult
    data class Failure(val reason: String) : AgentPlanParseResult
}

/** 解析后的规划动作（封闭集合）。 */
sealed interface AgentPlan {
    /** 调用已注册工具。 */
    data class ToolCall(val name: String, val input: String) : AgentPlan

    /** 结束规划（final 只能出现一次，由 orchestrator 终止循环）。 */
    data object Final : AgentPlan
}

/**
 * P1.4 / B16：planner 严格 JSON 解析器。
 *
 * 协议（只有两种合法对象，字段封闭）：
 * - `{"action":"<toolName>","input":"..."}`（toolName 必须已注册）
 * - `{"action":"final"}`（不允许 input/其它字段）
 *
 * 严格性：
 * - 顶层必须是单个 JSON 对象；允许模型输出 ```json fence（只在最外层剥离一次）；
 * - 出现额外字段/非字符串 action/非字符串 input/空 input/超长 input/控制字符 → Failure；
 * - final 带 input 或工具带缺失 input → Failure；
 * - 未知 action（含未注册工具名）→ [AgentFallbackReasons.UNKNOWN_ACTION]；
 * - 任何解析失败都不抛异常，返回 Failure 供 orchestrator 确定性 fallback。
 */
object AgentResponseParser {

    /** planner 原始输出长度上限（防超长输出撑内存；超出即坏 JSON）。 */
    const val MAX_RAW_LENGTH: Int = 1_000

    /** 工具 input 长度上限（字符）。 */
    const val MAX_TOOL_INPUT_CHARS: Int = 200

    /** final 动作字面量。 */
    const val ACTION_FINAL: String = "final"

    private val json = Json

    fun parse(raw: String, toolNames: Collection<String>): AgentPlanParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_RAW_LENGTH) {
            return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)
        }
        val jsonText = stripOuterCodeFence(trimmed)
        val root = try {
            json.parseToJsonElement(jsonText) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)

        // 字段封闭：只允许 action/input
        if (root.keys.any { it != "action" && it != "input" }) {
            return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)
        }
        val action = root["action"].stringValue() ?: return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)
        if (action == ACTION_FINAL) {
            // final 不允许携带 input
            return if ("input" in root) {
                AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)
            } else {
                AgentPlanParseResult.Success(AgentPlan.Final)
            }
        }
        if (action !in toolNames) {
            return AgentPlanParseResult.Failure(AgentFallbackReasons.UNKNOWN_ACTION)
        }
        val input = root["input"].stringValue()?.trim()
            ?: return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON)
        if (input.isEmpty() || input.length > MAX_TOOL_INPUT_CHARS || input.any { it.isISOControl() }) {
            return AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_INPUT)
        }
        return AgentPlanParseResult.Success(AgentPlan.ToolCall(name = action, input = input))
    }

    /** 只在整体被 ```json ... ``` 包裹时剥离一次；其它情况原样返回。 */
    private fun stripOuterCodeFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        val body = text.substring(firstNewline + 1)
        val close = body.lastIndexOf("```")
        return if (close >= 0) body.substring(0, close).trim() else text
    }

    private fun kotlinx.serialization.json.JsonElement?.stringValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }
}
