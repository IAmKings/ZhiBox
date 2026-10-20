package com.zhibox.memory.core.agent

import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.chat.ChatTurn
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1.4 / B16：planner 提示词（版本化/封闭 schema/数据边界/预算/长度限制）。 */
class AgentPromptTest {

    private val tools = listOf(
        AgentToolSpec("search_knowledge", "检索知识库证据"),
        AgentToolSpec("lookup_entities", "查询实体/别名/关联事件摘要"),
    )

    @Test
    fun `system prompt is versioned and lists tools and strict schema`() {
        val prompt = AgentPrompt.systemPrompt(tools, maxToolCalls = 2)

        assertTrue(prompt.contains(AgentPrompt.PROMPT_VERSION))
        assertTrue(prompt.contains("search_knowledge"))
        assertTrue(prompt.contains("lookup_entities"))
        assertTrue(prompt.contains("""{"action":"final"}"""))
        assertTrue("应声明工具最多 2 次", prompt.contains("最多 2 次"))
        assertTrue("应声明只读/禁网约束", prompt.contains("只读"))
        assertTrue("应声明输入长度上限", prompt.contains(AgentResponseParser.MAX_TOOL_INPUT_CHARS.toString()))
    }

    @Test
    fun `user prompt wraps query and history as data`() {
        val prompt = AgentPrompt.userPrompt(
            query = "张三在哪学习？",
            history = listOf(
                ChatTurn(ChatRole.USER, "张三是谁？"),
                ChatTurn(ChatRole.ASSISTANT, "张三是学生[^1]"),
            ),
            observations = listOf("找到 2 条证据"),
            remainingToolCalls = 1,
        )

        assertTrue(prompt.contains("<query>"))
        assertTrue(prompt.contains("张三在哪学习？"))
        assertTrue(prompt.contains("<history>"))
        assertTrue(prompt.contains("user: 张三是谁？"))
        assertTrue(prompt.contains("<observations>"))
        assertTrue(prompt.contains("找到 2 条证据"))
        assertTrue("应给剩余预算", prompt.contains("剩余工具调用次数：1"))
    }

    @Test
    fun `history and observations are bounded`() {
        val manyTurns = (1..10).map { ChatTurn(ChatRole.USER, "历史$it") }
        val manyObservations = (1..10).map { "观察$it " + "x".repeat(300) }
        val prompt = AgentPrompt.userPrompt(
            query = "问题",
            history = manyTurns,
            observations = manyObservations,
            remainingToolCalls = 0,
        )

        assertTrue("只保留最近 MAX_HISTORY_TURNS 轮", !prompt.contains("历史2") && prompt.contains("历史10"))
        assertTrue(
            "observations 拼接总长受预算限制",
            prompt.length <= AgentPrompt.MAX_OBSERVATIONS_TOTAL_CHARS + 2_000,
        )
        assertTrue(prompt.contains("预算为 0 时必须输出 final"))
    }

    @Test
    fun `tool description is truncated`() {
        val long = "描".repeat(300)
        assertTrue(AgentPrompt.safeToolDescription(long).length <= MAX_TOOL_DESCRIPTION_CHARS)
    }
}
