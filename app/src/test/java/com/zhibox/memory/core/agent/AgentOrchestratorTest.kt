package com.zhibox.memory.core.agent

import com.zhibox.memory.core.chat.ChatEvidence
import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.chat.ChatTurn
import com.zhibox.memory.core.llm.LlmCompletionProvider
import com.zhibox.memory.core.llm.LlmCompletionResult
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmStoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.4 / B16：AgentOrchestrator 纯 JVM 测试（fake provider/store/tools）。
 *
 * 覆盖：search→final、entity→search→final、未知工具、坏 JSON、未配置/配置异常、无工具、
 * provider 失败/异常、工具异常/错误码、maxToolCalls 超预算、重复 observation/evidence 去重、
 * final evidence 仅来自工具、无 evidence final、取消传播、提示词数据边界（query 不进 system）。
 */
class AgentOrchestratorTest {

    private val config = LlmConfig(
        baseUrl = "https://example.com/v1",
        model = "mock-model",
        apiKey = "sk-test-0123456789abcdef",
    )

    // ---- happy paths ----

    @Test
    fun `search then final returns used agent with deduped evidence`() = runBlocking {
        val search = FakeTool(
            spec = AgentToolSpec("search_knowledge", "检索知识库"),
            result = AgentToolResult(
                observation = "找到 2 条与张三相关的证据",
                evidence = listOf(evidence(1), evidence(2), evidence(1)),
            ),
        )
        val provider = ScriptedProvider(
            toolCall("search_knowledge", "张三"),
            final(),
        )
        val orchestrator = AgentOrchestrator(provider, FakeStore(config), mapOf("search_knowledge" to search))

        val result = orchestrator.plan("张三在哪学习？")

        assertTrue("显式 final 后 usedAgent=true", result.usedAgent)
        assertNull(result.fallbackReason)
        assertEquals(listOf(AgentToolCall("search_knowledge", "张三")), result.calls)
        assertEquals(listOf("找到 2 条与张三相关的证据"), result.observations)
        assertEquals("evidence 仅来自工具且按 chunkId 去重", listOf(1L, 2L), result.evidence.map { it.chunkId })
        assertEquals(2, provider.calls)
        assertEquals("只应执行一次工具", 1, search.inputs.size)
        assertEquals("张三", search.inputs.single())

        // 提示词数据边界：query 只在 user prompt；system 不含 query 原文
        val firstSystem = provider.prompts[0].first
        val firstUser = provider.prompts[0].second
        assertTrue("system 应含提示词版本", firstSystem.contains(AgentPrompt.PROMPT_VERSION))
        assertTrue("system 应含工具名", firstSystem.contains("search_knowledge"))
        assertTrue("system 不应包含用户 query", !firstSystem.contains("张三在哪学习"))
        assertTrue("user prompt 应包含 query", firstUser.contains("张三在哪学习"))
    }

    @Test
    fun `entity then search then final respects two tool calls`() = runBlocking {
        val lookup = FakeTool(
            AgentToolSpec("lookup_entities", "查实体"),
            AgentToolResult("实体：张三（person）"),
        )
        val search = FakeTool(
            AgentToolSpec("search_knowledge", "检索知识库"),
            AgentToolResult("检索到证据", listOf(evidence(7))),
        )
        val provider = ScriptedProvider(
            toolCall("lookup_entities", "张三"),
            toolCall("search_knowledge", "张三 学习"),
            final(),
        )
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("lookup_entities" to lookup, "search_knowledge" to search),
        )

        val result = orchestrator.plan("张三的导师是谁？")

        assertTrue(result.usedAgent)
        assertEquals(
            listOf("lookup_entities", "search_knowledge"),
            result.calls.map { it.name },
        )
        assertEquals(listOf(7L), result.evidence.map { it.chunkId })
        assertEquals(3, provider.calls)
        // 第二轮 prompt 应包含第一轮 observation（只进入规划，不是引用）
        assertTrue(provider.prompts[1].second.contains("实体：张三（person）"))
    }

    @Test
    fun `final immediately is used agent with no evidence`() = runBlocking {
        val provider = ScriptedProvider(final())
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan("你好")

        assertTrue(result.usedAgent)
        assertTrue(result.calls.isEmpty())
        assertTrue(result.evidence.isEmpty())
        assertNull(result.fallbackReason)
    }

    @Test
    fun `duplicate observations and evidence are deduped`() = runBlocking {
        val tool = FakeTool(
            AgentToolSpec("search_knowledge", "检索"),
            AgentToolResult("相同观察", listOf(evidence(1))),
        )
        val provider = ScriptedProvider(
            toolCall("search_knowledge", "a"),
            toolCall("search_knowledge", "b"),
            final(),
        )
        val orchestrator = AgentOrchestrator(provider, FakeStore(config), mapOf("search_knowledge" to tool))

        val result = orchestrator.plan("问题")

        assertTrue(result.usedAgent)
        assertEquals(2, result.calls.size)
        assertEquals("重复 observation 只保留一条", listOf("相同观察"), result.observations)
        assertEquals("重复 evidence 按 chunkId 去重", listOf(1L), result.evidence.map { it.chunkId })
    }

    // ---- fallbacks ----

    @Test
    fun `not configured falls back without calling provider`() = runBlocking {
        val provider = ScriptedProvider(final())
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(null),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan("问题")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.NOT_CONFIGURED, result.fallbackReason)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `config load failure falls back readable`() = runBlocking {
        val provider = ScriptedProvider(final())
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config, failure = LlmStoreException("配置损坏")),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan("问题")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.CONFIG_ERROR, result.fallbackReason)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `no tools falls back`() = runBlocking {
        val provider = ScriptedProvider(final())
        val result = AgentOrchestrator(provider, FakeStore(config), emptyMap()).plan("问题")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.NO_TOOLS, result.fallbackReason)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `empty query falls back`() = runBlocking {
        val provider = ScriptedProvider(final())
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan("   ")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.EMPTY_QUERY, result.fallbackReason)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `unknown tool action falls back`() = runBlocking {
        val provider = ScriptedProvider(toolCall("delete_everything", "x"))
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan("问题")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.UNKNOWN_ACTION, result.fallbackReason)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `bad json and extra fields fall back`() = runBlocking {
        val tools = mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x")))
        val bad = AgentOrchestrator(ScriptedProvider("not-json-at-all"), FakeStore(config), tools).plan("问题")
        assertEquals(false, bad.usedAgent)
        assertEquals(AgentFallbackReasons.BAD_JSON, bad.fallbackReason)

        val extra = AgentOrchestrator(
            ScriptedProvider("""{"action":"final","extra":1}"""),
            FakeStore(config),
            tools,
        ).plan("问题")
        assertEquals(false, extra.usedAgent)
        assertEquals(AgentFallbackReasons.BAD_JSON, extra.fallbackReason)
    }

    @Test
    fun `provider failure and exception fall back`() = runBlocking {
        val tools = mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x")))
        val failure = AgentOrchestrator(
            ScriptedProvider(LlmCompletionResult.Failure("LLM_TIMEOUT", "超时")),
            FakeStore(config),
            tools,
        ).plan("问题")
        assertEquals(false, failure.usedAgent)
        assertEquals(AgentFallbackReasons.PROVIDER_ERROR, failure.fallbackReason)

        val exception = AgentOrchestrator(
            ScriptedProvider(RuntimeException("boom")),
            FakeStore(config),
            tools,
        ).plan("问题")
        assertEquals(false, exception.usedAgent)
        assertEquals(AgentFallbackReasons.PROVIDER_EXCEPTION, exception.fallbackReason)
    }

    @Test
    fun `tool exception and tool error code fall back with attempt recorded`() = runBlocking {
        val throwing = object : AgentTool {
            override val spec = AgentToolSpec("search_knowledge", "检索")
            override suspend fun execute(input: String): AgentToolResult = error("tool boom")
        }
        val exception = AgentOrchestrator(
            ScriptedProvider(toolCall("search_knowledge", "x")),
            FakeStore(config),
            mapOf("search_knowledge" to throwing),
        ).plan("问题")
        assertEquals(false, exception.usedAgent)
        assertEquals(AgentFallbackReasons.TOOL_EXCEPTION, exception.fallbackReason)
        assertEquals(AgentFallbackReasons.TOOL_EXCEPTION, exception.calls.single().errorCode)

        val failing = FakeTool(
            AgentToolSpec("search_knowledge", "检索"),
            AgentToolResult("", errorCode = "SEARCH_FAILED"),
        )
        val toolError = AgentOrchestrator(
            ScriptedProvider(toolCall("search_knowledge", "x")),
            FakeStore(config),
            mapOf("search_knowledge" to failing),
        ).plan("问题")
        assertEquals(false, toolError.usedAgent)
        assertEquals(AgentFallbackReasons.TOOL_ERROR, toolError.fallbackReason)
        assertEquals("SEARCH_FAILED", toolError.calls.single().errorCode)
    }

    @Test
    fun `exceeding max tool calls falls back without executing extra tool`() = runBlocking {
        val tool = FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("obs"))
        val provider = ScriptedProvider(
            toolCall("search_knowledge", "a"),
            toolCall("search_knowledge", "b"),
            toolCall("search_knowledge", "c"),
        )
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("search_knowledge" to tool),
            maxToolCalls = 1,
        )

        val result = orchestrator.plan("问题")

        assertEquals(false, result.usedAgent)
        assertEquals(AgentFallbackReasons.MAX_TOOL_CALLS, result.fallbackReason)
        assertEquals("只执行 1 次工具", 1, tool.inputs.size)
        assertEquals(1, result.calls.size)
        assertEquals("预算耗尽后的下一次规划仍会调用 provider，但不再执行工具", 2, provider.calls)
    }

    // ---- cancellation ----

    @Test
    fun `provider cancellation propagates`() {
        val tools = mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x")))
        val orchestrator = AgentOrchestrator(ScriptedProvider(CancellationException("cancelled")), FakeStore(config), tools)

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.plan("问题") }
        }
    }

    @Test
    fun `tool cancellation propagates`() {
        val tool = object : AgentTool {
            override val spec = AgentToolSpec("search_knowledge", "检索")
            override suspend fun execute(input: String): AgentToolResult = throw CancellationException("cancelled")
        }
        val orchestrator = AgentOrchestrator(
            ScriptedProvider(toolCall("search_knowledge", "x")),
            FakeStore(config),
            mapOf("search_knowledge" to tool),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.plan("问题") }
        }
    }

    // ---- prompt/history ----

    @Test
    fun `history is passed to user prompt but not treated as evidence`() = runBlocking {
        val provider = ScriptedProvider(final())
        val orchestrator = AgentOrchestrator(
            provider,
            FakeStore(config),
            mapOf("search_knowledge" to FakeTool(AgentToolSpec("search_knowledge", "检索"), AgentToolResult("x"))),
        )

        val result = orchestrator.plan(
            query = "他后来去哪了？",
            history = listOf(
                ChatTurn(ChatRole.USER, "张三在哪学习？"),
                ChatTurn(ChatRole.ASSISTANT, "张三在上海学习[^1]"),
            ),
        )

        assertTrue(result.usedAgent)
        assertTrue("历史不是证据", result.evidence.isEmpty())
        val userPrompt = provider.prompts.single().second
        assertTrue(userPrompt.contains("张三在哪学习？"))
        assertTrue(userPrompt.contains("他后来去哪了？"))
        assertNotNull(result.fallbackReason == null)
    }

    // ---- fixtures ----

    private fun toolCall(name: String, input: String): String =
        """{"action":"$name","input":"$input"}"""

    private fun final(): String = """{"action":"final"}"""

    private fun evidence(chunkId: Long): ChatEvidence = ChatEvidence(
        evidenceId = "S$chunkId",
        chunkId = chunkId,
        documentId = chunkId * 10,
        sourceId = 1,
        docTitle = "文档$chunkId",
        sourceTitle = "来源",
        sourceUri = "file:///doc$chunkId",
        content = "证据正文$chunkId",
        ordinal = 0,
        charStart = 0,
        charEnd = 5,
        score = 0.9,
    )

    private class FakeStore(
        private val config: LlmConfig?,
        private val failure: Throwable? = null,
    ) : LlmConfigStore {
        override fun load(): LlmConfig? {
            failure?.let { throw it }
            return config
        }

        override fun save(config: LlmConfig) = Unit

        override fun clear() = Unit

        override fun maskedKey(): String? = null
    }

    private class FakeTool(
        override val spec: AgentToolSpec,
        private val result: AgentToolResult,
    ) : AgentTool {
        val inputs = mutableListOf<String>()

        override suspend fun execute(input: String): AgentToolResult {
            inputs += input
            return result
        }
    }

    /** 按脚本返回：String→Success、LlmCompletionResult→原样、Throwable→抛出；队尾返回 Failure。 */
    private class ScriptedProvider(vararg responses: Any) : LlmCompletionProvider {
        private val queue = java.util.ArrayDeque<Any>().apply { responses.forEach { add(it) } }
        var calls = 0
            private set
        val prompts = mutableListOf<Pair<String, String>>()

        override suspend fun complete(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): LlmCompletionResult {
            calls++
            prompts += systemPrompt to userPrompt
            val next = if (queue.isEmpty()) {
                LlmCompletionResult.Failure("LLM_UNEXPECTED_CALL", "脚本已耗尽")
            } else {
                queue.removeFirst()
            }
            return when (next) {
                is Throwable -> throw next
                is LlmCompletionResult -> next
                is String -> LlmCompletionResult.Success(next, latencyMs = 1, model = "mock-model")
                else -> error("unsupported scripted response")
            }
        }
    }
}
