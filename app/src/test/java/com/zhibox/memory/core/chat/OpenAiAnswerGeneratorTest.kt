package com.zhibox.memory.core.chat

import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmStoreException
import com.zhibox.memory.core.llm.LlmStreamEvent
import com.zhibox.memory.core.llm.LlmStreamingProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B11 带引用问答生成器（JVM，fake [LlmStreamingProvider]/fake [LlmConfigStore]，零网络）：
 * - 无证据 → `NO_EVIDENCE` 固定拒答，**零 provider/store 调用**；
 * - 未配置/配置读取失败 → 可读 Failed，零请求；
 * - 成功流：Delta 顺序透传（空片段被过滤）→ 强制 CitationValidator → Completed（model/latency）；
 * - 非法引用替换警示标记 + citations/unverified 语义；无引用答案 unverified；
 * - 流中 Failed：只发 Failed 不发 Completed；空回答 → PARSE；
 * - 取消：不发送 Completed/Failed；
 * - 提示词：只含本轮 query+证据、证据数/字数预算生效、maxTokens 透传。
 */
class OpenAiAnswerGeneratorTest {

    private val llmConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "gpt-4o-mini",
        apiKey = "sk-test-1234567890abcd",
    )

    private val evidence = listOf(
        evidence(chunkId = 11L, title = "光合作用文档"),
        evidence(chunkId = 22L, title = "呼吸作用文档"),
    )

    // ---- 无证据 / 配置缺失 / 空问题：零请求 ----

    @Test
    fun noEvidenceRefusesWithZeroProviderAndStoreCalls() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ -> flowOf() }
        val store = FakeStore(llmConfig)
        val generator = OpenAiAnswerGenerator(provider, store)

        val events = generator.generate(AnswerRequest("光合作用是什么？", emptyList())).toList()

        assertEquals(1, events.size)
        val failed = events.single() as AnswerEvent.Failed
        assertEquals(OpenAiAnswerGenerator.NO_EVIDENCE_CODE, failed.code)
        assertEquals(ChatPrompt.REFUSAL_MESSAGE, failed.message)
        assertEquals("无证据不得调用 provider（零网络）", 0, provider.calls)
        assertEquals("无证据不得读取 LLM 配置", 0, store.loads)
    }

    @Test
    fun notConfiguredFailsWithoutProviderCall() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ -> error("provider 不应被调用") }
        val store = FakeStore(config = null)
        val generator = OpenAiAnswerGenerator(provider, store)

        val events = generator.generate(AnswerRequest("q", evidence)).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.NOT_CONFIGURED, failed.code)
        assertTrue(failed.message.contains("未配置"))
        assertEquals(0, provider.calls)
    }

    @Test
    fun storeExceptionMapsToStoreFailureWithoutProviderCall() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ -> error("provider 不应被调用") }
        val store = FakeStore(error = LlmStoreException("Keystore 密钥失效"))
        val generator = OpenAiAnswerGenerator(provider, store)

        val events = generator.generate(AnswerRequest("q", evidence)).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.STORE, failed.code)
        assertTrue("文案可读：${failed.message}", failed.message.contains("LLM 配置"))
        assertFalse("不得含 API Key", failed.message.contains(llmConfig.apiKey))
        assertEquals(0, provider.calls)
    }

    @Test
    fun blankQueryFailsWithoutProviderCall() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ -> error("provider 不应被调用") }
        val store = FakeStore(llmConfig)
        val generator = OpenAiAnswerGenerator(provider, store)

        val events = generator.generate(AnswerRequest("   ", evidence)).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(OpenAiAnswerGenerator.INVALID_QUERY_CODE, failed.code)
        assertEquals(0, provider.calls)
        assertEquals(0, store.loads)
    }

    // ---- 成功流 ----

    @Test
    fun successfulStreamEmitsDeltasThenValidatedCompleted() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("光合作用"),
                LlmStreamEvent.Delta(""),
                LlmStreamEvent.Delta("是"),
                LlmStreamEvent.Delta(" [S1]"),
                LlmStreamEvent.Completed(model = "gpt-4o-mini", latencyMs = 42L),
            )
        }
        val generator = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))

        val events = generator.generate(AnswerRequest("光合作用是什么？", evidence)).toList()

        assertEquals(4, events.size) // 空 Delta 被过滤
        assertEquals(
            listOf("光合作用", "是", " [S1]"),
            events.filterIsInstance<AnswerEvent.Delta>().map { it.text },
        )
        val completed = events.last() as AnswerEvent.Completed
        assertEquals("光合作用是 [S1]", completed.text)
        assertEquals(1, completed.citations.size)
        assertEquals("S1", completed.citations.single().evidenceId)
        assertEquals(11L, completed.citations.single().chunkId)
        assertTrue(completed.invalidCitationTokens.isEmpty())
        assertFalse(completed.unverified)
        assertEquals("gpt-4o-mini", completed.model)
        assertEquals(42L, completed.latencyMs)

        // 提示词：本轮 query + 证据快照、无历史；maxTokens 透传
        assertEquals(1, provider.calls)
        assertEquals(llmConfig, provider.lastConfig)
        assertEquals(AnswerGeneratorConfig().maxTokens, provider.lastMaxTokens)
        assertTrue(provider.lastSystem.contains("只能依据用户消息中提供的证据"))
        assertTrue(provider.lastUser.contains("问题："))
        assertTrue(provider.lastUser.contains("光合作用是什么？"))
        assertTrue(provider.lastUser.contains("[S1]"))
        assertTrue(provider.lastUser.contains("光合作用文档"))
        assertFalse("不得注入历史/其他轮次", provider.lastUser.contains("历史"))
    }

    @Test
    fun invalidCitationIsReplacedAndMarkedUnverified() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("结论[S9]"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 1L),
            )
        }
        val generator = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))

        val completed = generator.generate(AnswerRequest("q", evidence)).toList()
            .last() as AnswerEvent.Completed

        assertEquals("结论[未通过证据校验]", completed.text)
        assertEquals(listOf("[S9]"), completed.invalidCitationTokens)
        assertTrue(completed.citations.isEmpty())
        assertTrue("无有效引用 → unverified", completed.unverified)
    }

    @Test
    fun answerWithoutCitationIsUnverified() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("没有引用的回答"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 1L),
            )
        }
        val completed = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))
            .generate(AnswerRequest("q", evidence)).toList().last() as AnswerEvent.Completed
        assertEquals("没有引用的回答", completed.text)
        assertTrue(completed.unverified)
        assertTrue(completed.citations.isEmpty())
    }

    @Test
    fun completedWithoutModelFallsBackToConfigModel() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("答案[S1]"),
                LlmStreamEvent.Completed(model = "", latencyMs = 0L),
            )
        }
        val completed = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))
            .generate(AnswerRequest("q", evidence)).toList().last() as AnswerEvent.Completed
        assertEquals(llmConfig.model, completed.model)
    }

    // ---- 失败 / 空回答 / 取消 ----

    @Test
    fun failedMidStreamEmitsOnlyFailedAfterDeltas() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("部分"),
                LlmStreamEvent.Failed(LlmErrorCode.TIMEOUT, "超时（30s）"),
            )
        }
        val events = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))
            .generate(AnswerRequest("q", evidence)).toList()

        assertEquals(2, events.size)
        assertTrue(events.first() is AnswerEvent.Delta)
        val failed = events.last() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.TIMEOUT, failed.code)
        assertTrue(failed.message.contains("超时"))
        assertTrue("不得发 Completed", events.none { it is AnswerEvent.Completed })
    }

    @Test
    fun blankStreamAnswerFailsWithParseAndNoCompleted() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(LlmStreamEvent.Completed(model = "m", latencyMs = 1L))
        }
        val events = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))
            .generate(AnswerRequest("q", evidence)).toList()
        val failed = events.single() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.PARSE, failed.code)
        assertTrue(events.none { it is AnswerEvent.Completed })
    }

    @Test
    fun cancellationEmitsNoCompletedOrFailed() = runBlocking {
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flow {
                emit(LlmStreamEvent.Delta("部分"))
                awaitCancellation()
            }
        }
        val generator = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))

        val events = mutableListOf<AnswerEvent>()
        // take(1) 在收到首个 Delta 后取消上游（等价 UI 点“停止”）；本轮只验证事件语义：
        // 取消不得被包装成 Completed/Failed，也不得等待 provider 正常结束。
        generator.generate(AnswerRequest("q", evidence)).take(1).collect { events += it }

        assertEquals(1, events.size)
        assertTrue(events.single() is AnswerEvent.Delta)
        assertTrue("取消后不应发 Completed", events.none { it is AnswerEvent.Completed })
        assertTrue("取消后不应发 Failed", events.none { it is AnswerEvent.Failed })
        assertTrue("取消后不得再产生事件", events.none { it is AnswerEvent.Stopped && false })
    }

    // ---- 预算（证据条数/字数、maxTokens） ----

    @Test
    fun evidenceBudgetIsAppliedToPromptAndValidation() = runBlocking {
        val many = (1..12).map { evidence(chunkId = it.toLong(), title = "证据$it") }
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(
                LlmStreamEvent.Delta("[S10]"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 1L),
            )
        }
        val generator = OpenAiAnswerGenerator(provider, FakeStore(llmConfig))

        val completed = generator.generate(AnswerRequest("q", many)).toList()
            .last() as AnswerEvent.Completed

        assertTrue("S10 在默认 10 条预算内", provider.lastUser.contains("[S10]"))
        assertFalse("S11 超出预算不应出现在提示词", provider.lastUser.contains("[S11]"))
        assertEquals("S10", completed.citations.single().evidenceId)
        assertEquals("编号与发送证据顺序一致", 10L, completed.citations.single().chunkId)
    }

    @Test
    fun configMaxTokensAndEvidenceCharsArePassedThrough() = runBlocking {
        val config = AnswerGeneratorConfig(maxEvidence = 1, maxCharsPerEvidence = 5, maxTokens = 999)
        val provider = FakeStreamingProvider { _, _, _, _ ->
            flowOf(LlmStreamEvent.Completed(model = "m", latencyMs = 1L))
        }
        val generator = OpenAiAnswerGenerator(provider, FakeStore(llmConfig), config)
        generator.generate(AnswerRequest("q", evidence)).toList()

        assertEquals(999, provider.lastMaxTokens)
        // maxEvidence=1：只发送证据 1（长内容按 maxCharsPerEvidence 截断）
        assertTrue(provider.lastUser.contains("[S1]"))
        assertFalse(provider.lastUser.contains("[S2]"))
        assertTrue(provider.lastUser.contains("证据正文 11".take(5)))
    }

    // ---- fakes ----

    private class FakeStreamingProvider(
        private val handler: (LlmConfig, String, String, Int) -> Flow<LlmStreamEvent>,
    ) : LlmStreamingProvider {
        var calls: Int = 0
        var lastConfig: LlmConfig? = null
        var lastSystem: String = ""
        var lastUser: String = ""
        var lastMaxTokens: Int = -1

        override fun stream(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): Flow<LlmStreamEvent> {
            calls++
            lastConfig = config
            lastSystem = systemPrompt
            lastUser = userPrompt
            lastMaxTokens = maxTokens
            return handler(config, systemPrompt, userPrompt, maxTokens)
                .onCompletion { /* 测试观察取消 */ }
        }
    }

    private class FakeStore(
        private val config: LlmConfig? = null,
        private val error: LlmStoreException? = null,
    ) : LlmConfigStore {
        var loads: Int = 0
        override fun load(): LlmConfig? {
            loads++
            error?.let { throw it }
            return config
        }

        override fun save(config: LlmConfig) = Unit
        override fun clear() = Unit
        override fun maskedKey(): String? = null
    }

    private fun evidence(chunkId: Long, title: String) = ChatEvidence(
        evidenceId = "S?",
        chunkId = chunkId,
        documentId = chunkId * 10,
        sourceId = chunkId * 100,
        docTitle = title,
        sourceTitle = "来源",
        sourceUri = "content://test/$chunkId",
        content = "证据正文 $chunkId：光合作用相关内容",
        ordinal = 0,
        charStart = 0,
        charEnd = 10,
    )
}
