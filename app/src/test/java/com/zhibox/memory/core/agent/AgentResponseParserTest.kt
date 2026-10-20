package com.zhibox.memory.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1.4 / B16：planner 严格 JSON 解析（封闭 schema / 工具白名单 / 长度与控制字符 / final 终止）。 */
class AgentResponseParserTest {

    private val tools = setOf("search_knowledge", "lookup_entities")

    @Test
    fun `parses registered tool call`() {
        val result = AgentResponseParser.parse("""{"action":"search_knowledge","input":"张三"}""", tools)
        assertEquals(
            AgentPlanParseResult.Success(AgentPlan.ToolCall("search_knowledge", "张三")),
            result,
        )
    }

    @Test
    fun `parses final`() {
        assertEquals(AgentPlanParseResult.Success(AgentPlan.Final), AgentResponseParser.parse("""{"action":"final"}""", tools))
    }

    @Test
    fun `accepts single outer json code fence`() {
        val raw = "```json\n{\"action\":\"lookup_entities\",\"input\":\"上海\"}\n```"
        assertEquals(
            AgentPlanParseResult.Success(AgentPlan.ToolCall("lookup_entities", "上海")),
            AgentResponseParser.parse(raw, tools),
        )
    }

    @Test
    fun `trims tool input`() {
        val result = AgentResponseParser.parse("""{"action":"search_knowledge","input":"  张三  "}""", tools)
        assertEquals(AgentPlanParseResult.Success(AgentPlan.ToolCall("search_knowledge", "张三")), result)
    }

    @Test
    fun `rejects unknown action`() {
        val result = AgentResponseParser.parse("""{"action":"delete_all","input":"x"}""", tools)
        assertEquals(AgentPlanParseResult.Failure(AgentFallbackReasons.UNKNOWN_ACTION), result)
    }

    @Test
    fun `rejects non-object and invalid json`() {
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("not json", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""["final"]""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("x".repeat(AgentResponseParser.MAX_RAW_LENGTH + 1), tools),
        )
    }

    @Test
    fun `rejects extra fields for tool and final`() {
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":"search_knowledge","input":"x","extra":1}""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":"final","input":"x"}""", tools),
        )
    }

    @Test
    fun `rejects missing or blank or non-string tool input`() {
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":"search_knowledge"}""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_INPUT),
            AgentResponseParser.parse("""{"action":"search_knowledge","input":"   "}""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":"search_knowledge","input":123}""", tools),
        )
    }

    @Test
    fun `rejects non-string action and non-object root`() {
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":123,"input":"x"}""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_JSON),
            AgentResponseParser.parse("""{"action":["final"]}""", tools),
        )
    }

    @Test
    fun `rejects oversize and control-char tool input`() {
        val tooLong = "x".repeat(AgentResponseParser.MAX_TOOL_INPUT_CHARS + 1)
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_INPUT),
            AgentResponseParser.parse("""{"action":"search_knowledge","input":"$tooLong"}""", tools),
        )
        assertEquals(
            AgentPlanParseResult.Failure(AgentFallbackReasons.BAD_INPUT),
            AgentResponseParser.parse("""{"action":"search_knowledge","input":"a\u0001b"}""", tools),
        )
        assertTrue("200 字以内应接受", AgentResponseParser.MAX_TOOL_INPUT_CHARS >= 200)
    }
}
