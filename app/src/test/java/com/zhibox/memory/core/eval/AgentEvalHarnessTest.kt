package com.zhibox.memory.core.eval

import com.zhibox.memory.core.agent.AgentFallbackReasons
import com.zhibox.memory.core.agent.AgentOrchestrator
import com.zhibox.memory.core.agent.AgentTool
import com.zhibox.memory.core.agent.AgentToolResult
import com.zhibox.memory.core.agent.AgentToolSpec
import com.zhibox.memory.core.chat.ChatEvidence
import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.chat.ChatTurn
import com.zhibox.memory.core.chat.CitationValidator
import com.zhibox.memory.core.llm.LlmCompletionProvider
import com.zhibox.memory.core.llm.LlmCompletionResult
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmStoreException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B17 / P1.4：Agent 评测集离线 harness（纯 JVM，fake planner/tools，**无真实 LLM/网络**）。
 *
 * 做什么：读取 `scripts/eval/agent_eval_cases.json` 的 20 条 case，按 case 的 expected route/fallback 脚本化
 * `LlmCompletionProvider`，用 fake `AgentTool` 返回可控 evidence，断言：
 * - 工具路由（calls 名称顺序）与 expectedToolRoute 一致；
 * - fallback 原因码与 expectedFallback 一致（fallback 时 evidence 必须为空）；
 * - evidence 按 chunkId 去重；
 * - 最终引用契约：用 `CitationValidator` 以 expectedCitationIds 构造引用，引用必须全部来自本轮 evidence、
 *   去重且顺序稳定；无引用 case 不得产生引用。
 *
 * **不做什么**：不调用真实 LLM、不产生真实回答；本 harness 通过仅代表 planner/工具路由/引用契约回归通过，
 * **不能宣称真实回答质量达标**。真实质量需用户 LLM key + 人工抽检（见 docs/review/P1_4_QUALITY_EVAL_PLAN.md）。
 */
class AgentEvalHarnessTest {

    private val config = LlmConfig(
        baseUrl = "https://eval.example.com/v1",
        model = "eval-mock-model",
        apiKey = "sk-eval-not-a-real-key",
    )

    private data class EvalCase(
        val id: String,
        val query: String,
        val history: List<Pair<String, String>>,
        val expectedToolRoute: List<String>,
        val expectedFallback: String?,
        val expectedCitationIds: List<String>,
        val notes: String,
    )

    @Test
    fun offlineHarnessMatchesAll20Cases() = runBlocking {
        val cases = loadCases()
        assertEquals("评测集应为 20 条", 20, cases.size)
        assertEquals("case id 不得重复", cases.size, cases.map { it.id }.toSet().size)

        var routeOk = 0
        var fallbackOk = 0
        var citationOk = 0
        cases.forEach { case ->
            val result = runCase(case)

            assertEquals(
                "[${case.id}] 工具路由不符（notes=${case.notes}）",
                case.expectedToolRoute.filter { it != "final" },
                result.calls.map { it.name },
            )
            routeOk++

            if (case.expectedFallback != null) {
                assertFalse("[${case.id}] 应走确定性 fallback", result.usedAgent)
                assertEquals("[${case.id}] fallback 原因码", case.expectedFallback, result.fallbackReason)
                assertTrue("[${case.id}] fallback 不得携带 evidence", result.evidence.isEmpty())
                fallbackOk++
            } else {
                assertTrue("[${case.id}] 应正常以 final 结束", result.usedAgent)
                assertNull("[${case.id}] 正常路径 fallbackReason 应为 null", result.fallbackReason)
            }

            if (case.expectedCitationIds.isEmpty()) {
                assertTrue("[${case.id}] 无预期引用时 evidence 应为空", result.evidence.isEmpty())
                val validation = CitationValidator.validate("根据现有证据无法回答", result.evidence)
                assertTrue("[${case.id}] 无 evidence 不得产生引用", validation.citations.isEmpty())
                assertTrue(validation.invalidCitationTokens.isEmpty())
            } else {
                val answer = case.expectedCitationIds.joinToString("") { "依据[$it]" }
                val validation = CitationValidator.validate(answer, result.evidence)
                assertEquals(
                    "[${case.id}] 引用必须来自本轮 evidence 且去重/顺序稳定",
                    case.expectedCitationIds,
                    validation.citations.map { it.evidenceId },
                )
                assertTrue("[${case.id}] 不得有非法引用 token：${validation.invalidCitationTokens}", validation.invalidCitationTokens.isEmpty())
                assertTrue("[${case.id}] 引用校验应为 valid", validation.valid)
                assertEquals(
                    "[${case.id}] evidence 应按 chunkId 去重",
                    result.evidence.size,
                    result.evidence.map { it.chunkId }.toSet().size,
                )
            }
            citationOk++
        }

        println(
            "B17_AGENT_EVAL_OFFLINE cases=${cases.size} route_ok=$routeOk fallback_ok=$fallbackOk " +
                "citation_ok=$citationOk contract_only=true real_quality=false " +
                "note=offline_fake_planner_harness_must_not_be_claimed_as_quality",
        )
    }

    // ---- case 执行（fake provider/tools） ----

    private suspend fun runCase(case: EvalCase): com.zhibox.memory.core.agent.AgentRunResult {
        val expectedEvidence = case.expectedCitationIds.mapIndexed { index, evidenceId ->
            ChatEvidence(
                evidenceId = evidenceId,
                chunkId = (index + 1).toLong(),
                documentId = 1000L + index,
                sourceId = 7L,
                docTitle = "评测文档${index + 1}",
                sourceTitle = "评测来源",
                sourceUri = "eval://doc/${index + 1}",
                content = "评测证据占位（不含真实答案）",
                ordinal = index,
                charStart = 0,
                charEnd = 10,
                score = 0.9,
            )
        }
        var searchCalls = 0
        val tools = linkedMapOf<String, AgentTool>()
        var toolError: String? = null
        var toolShouldThrow = false
        tools["search_knowledge"] = object : AgentTool {
            override val spec = AgentToolSpec("search_knowledge", "本地只读知识检索")
            override suspend fun execute(input: String): AgentToolResult {
                searchCalls++
                if (toolShouldThrow) error("eval tool boom")
                toolError?.let { return AgentToolResult(observation = "", errorCode = it) }
                val evidence = if (case.id == "ref-dedupe-duplicate-evidence") {
                    expectedEvidence + expectedEvidence // 同一 chunk 重复出现，验证去重
                } else {
                    expectedEvidence
                }
                return AgentToolResult(observation = "检索观察$searchCalls", evidence = evidence)
            }
        }
        tools["lookup_entities"] = object : AgentTool {
            override val spec = AgentToolSpec("lookup_entities", "本地实体/别名查询（观察不直接作为引用）")
            override suspend fun execute(input: String): AgentToolResult =
                AgentToolResult(observation = "实体观察：张三（person）")
        }

        var store: LlmConfigStore = FakeStore(config)
        var provider: LlmCompletionProvider = ScriptedProvider(*successScripts(case))
        when (case.expectedFallback) {
            null -> Unit // success scripts
            AgentFallbackReasons.EMPTY_QUERY -> provider = ScriptedProvider() // 不应被调用
            AgentFallbackReasons.NOT_CONFIGURED -> store = FakeStore(null)
            AgentFallbackReasons.CONFIG_ERROR -> store = FakeStore(config, LlmStoreException("eval store failure"))
            AgentFallbackReasons.NO_TOOLS -> tools.clear()
            AgentFallbackReasons.PROVIDER_ERROR -> provider = ScriptedProvider(
                LlmCompletionResult.Failure("LLM_TIMEOUT", "评测超时"),
            )
            AgentFallbackReasons.UNKNOWN_ACTION -> provider = ScriptedProvider(
                """{"action":"write_file","input":"x"}""",
            )
            AgentFallbackReasons.BAD_JSON -> provider = ScriptedProvider(
                if (case.id.contains("extra-field")) """{"action":"final","input":"x"}""" else "这不是 JSON",
            )
            AgentFallbackReasons.BAD_INPUT -> provider = ScriptedProvider(
                """{"action":"search_knowledge","input":""}""",
            )
            AgentFallbackReasons.MAX_TOOL_CALLS -> provider = ScriptedProvider(
                toolCall("search_knowledge", "a"),
                toolCall("search_knowledge", "b"),
                toolCall("search_knowledge", "c"),
            )
            AgentFallbackReasons.TOOL_ERROR -> toolError = "SEARCH_FAILED"
            AgentFallbackReasons.TOOL_EXCEPTION -> toolShouldThrow = true
            AgentFallbackReasons.PROVIDER_EXCEPTION -> provider = ScriptedProvider(IllegalStateException("provider boom"))
            else -> error("unsupported eval fallback: ${case.expectedFallback}")
        }

        val orchestrator = AgentOrchestrator(
            provider = provider,
            store = store,
            tools = tools,
            maxToolCalls = AgentOrchestrator.DEFAULT_MAX_TOOL_CALLS,
        )
        val history = case.history.map { (role, content) ->
            ChatTurn(role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT, content = content)
        }
        return orchestrator.plan(query = case.query, history = history)
    }

    private fun successScripts(case: EvalCase): Array<Any> {
        val toolNames = case.expectedToolRoute.filter { it != "final" }
        val calls = toolNames.mapIndexed { index, name -> toolCall(name, "eval-input-${index + 1}") }
        return (calls + finalAction()).toTypedArray()
    }

    private fun toolCall(name: String, input: String): String = """{"action":"$name","input":"$input"}"""

    private fun finalAction(): String = """{"action":"final"}"""

    // ---- case 加载 ----

    private fun loadCases(): List<EvalCase> {
        val file = locateCasesFile()
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val casesJson = root["cases"]?.jsonArray ?: error("agent_eval_cases.json 缺少 cases 数组")
        val cases = casesJson.map { element ->
            val obj = element.jsonObject
            EvalCase(
                id = obj.requireString("id"),
                query = obj.requireString("query"),
                history = obj["history"]?.jsonArray.orEmpty().map { entry ->
                    val historyObj = entry.jsonObject
                    historyObj.requireString("role") to historyObj.requireString("content")
                },
                expectedToolRoute = obj["expectedToolRoute"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                expectedFallback = obj["expectedFallback"]?.jsonPrimitive?.contentOrNull,
                expectedCitationIds = obj["expectedCitationIds"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                notes = obj["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).also {
                assertTrue("case 不得包含真实答案正文（答案泄漏防护）", "answer" !in obj)
                assertTrue("history 行不得包含额外正文答案字段", obj["history"]?.jsonArray.orEmpty().all { entry ->
                    "answer" !in entry.jsonObject
                })
            }
        }
        return cases
    }

    private fun JsonObject.requireString(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("case 缺少字段 $key")

    private fun locateCasesFile(): File {
        val candidates = mutableListOf<File>()
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            candidates += File(dir, "scripts/eval/agent_eval_cases.json")
            dir = dir.parentFile ?: return@repeat
        }
        candidates += File("../scripts/eval/agent_eval_cases.json")
        return candidates.firstOrNull { it.isFile } ?: error(
            "找不到 scripts/eval/agent_eval_cases.json（user.dir=${System.getProperty("user.dir")}）",
        )
    }

    // ---- fakes ----

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

    /** 脚本化 provider：String→Success、LlmCompletionResult→原样、Throwable→抛出；队尾返回 Failure。 */
    private class ScriptedProvider(vararg scripts: Any) : LlmCompletionProvider {
        private val queue = java.util.ArrayDeque<Any>().apply { scripts.forEach { add(it) } }

        override suspend fun complete(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): LlmCompletionResult {
            val next = if (queue.isEmpty()) {
                LlmCompletionResult.Failure("LLM_UNEXPECTED_CALL", "评测脚本已耗尽")
            } else {
                queue.removeFirst()
            }
            return when (next) {
                is Throwable -> throw next
                is LlmCompletionResult -> next
                is String -> LlmCompletionResult.Success(next, latencyMs = 1, model = "eval-mock-model")
                else -> error("unsupported scripted eval response")
            }
        }
    }
}
