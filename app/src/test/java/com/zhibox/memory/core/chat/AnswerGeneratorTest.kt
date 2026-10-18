package com.zhibox.memory.core.chat

import com.zhibox.memory.core.llm.InMemoryLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmStoreException
import com.zhibox.memory.core.llm.LlmStreamEvent
import com.zhibox.memory.core.llm.LlmStreamingProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B11：带引用回答生成器单测（JVM，无网络/无 Android）：
 * 无证据零请求拒答、未配置、流式 Delta/Completed 映射、强制引用校验（合法/非法/未引用）、
 * Provider 失败透传、空回答、证据上限、取消（不冒 Failed）与不注入历史。
 */
class AnswerGeneratorTest {

    @Test
    fun `no evidence refuses with fixed message and zero provider calls`() = runBlocking {
        val provider = FakeStreamingProvider(emptyList())
        val generator = generator(provider)

        val events = generator.generate(AnswerRequest(query = "问题", evidence = emptyList())).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(OpenAiAnswerGenerator.NO_EVIDENCE_CODE, failed.code)
        assertEquals(ChatPrompt.REFUSAL_MESSAGE, failed.message)
        assertEquals("无证据不得调用 LLM", 0, provider.calls)
        assertEquals("无证据不得读取配置", 0, generator.storeAccessCount)
    }

    @Test
    fun `missing config fails readable before any stream call`() = runBlocking {
        val provider = FakeStreamingProvider(emptyList())
        val store = InMemoryLlmConfigStore(initial = null)
        val generator = OpenAiAnswerGenerator(provider, store, AnswerGeneratorConfig())

        val events = generator.generate(AnswerRequest("q", listOf(evidence()))).toList()

        assertEquals(LlmErrorCode.NOT_CONFIGURED, (events.single() as AnswerEvent.Failed).code)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `store failure fails readable without stream call`() = runBlocking {
        val provider = FakeStreamingProvider(emptyList())
        val store = object : LlmConfigStore {
            override fun load(): LlmConfig? = throw LlmStoreException("配置损坏，请重新保存 API Key")
            override fun save(config: LlmConfig) = Unit
            override fun clear() = Unit
            override fun maskedKey(): String? = null
        }
        val generator = OpenAiAnswerGenerator(provider, store, AnswerGeneratorConfig())

        val events = generator.generate(AnswerRequest("q", listOf(evidence()))).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.STORE, failed.code)
        assertTrue("应可读：${failed.message}", failed.message.contains("配置损坏"))
        assertEquals(0, provider.calls)
    }

    @Test
    fun `successful stream emits deltas and completed with validated citations`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(
                LlmStreamEvent.Delta("[S1] 张三"),
                LlmStreamEvent.Delta(" 在上海学习数学。"),
                LlmStreamEvent.Completed(model = "test-model", latencyMs = 42L),
            ),
        )
        val generator = generator(provider)

        val events = generator.generate(AnswerRequest("张三在哪里学习？", listOf(evidence(chunkId = 7L)))).toList()

        assertEquals(3, events.size)
        assertEquals("[S1] 张三", (events[0] as AnswerEvent.Delta).text)
        assertEquals(" 在上海学习数学。", (events[1] as AnswerEvent.Delta).text)
        val completed = events[2] as AnswerEvent.Completed
        assertEquals("[S1] 张三 在上海学习数学。", completed.text)
        assertEquals(1, completed.citations.size)
        assertEquals("S1", completed.citations.single().evidenceId)
        assertEquals(7L, completed.citations.single().chunkId)
        assertEquals("test-model", completed.model)
        assertEquals(42L, completed.latencyMs)
        assertTrue(completed.invalidCitationTokens.isEmpty())
        assertFalse(completed.unverified)

        // 提示词/参数契约
        assertTrue(provider.lastSystem.orEmpty().contains(ChatPrompt.REFUSAL_MESSAGE))
        assertTrue(provider.lastSystem.orEmpty().contains("[S1]"))
        assertTrue(provider.lastUser.orEmpty().contains("[S1]"))
        assertTrue(provider.lastUser.orEmpty().contains("张三在哪里学习？"))
        assertEquals(768, provider.lastMaxTokens)
        assertEquals("test-model", provider.lastConfig?.model)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `invalid citation token is replaced and marked unverified`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(
                LlmStreamEvent.Delta("[S9] 编造答案"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 1L),
            ),
        )
        val events = generator(provider).generate(AnswerRequest("q", listOf(evidence()))).toList()

        val completed = events.last() as AnswerEvent.Completed
        assertTrue("非法引用应被移除/替换：${completed.text}", completed.text.contains("[未通过证据校验]"))
        assertEquals(listOf("[S9]"), completed.invalidCitationTokens)
        assertTrue("无合法引用 → unverified", completed.unverified)
        assertTrue(completed.citations.isEmpty())
    }

    @Test
    fun `answer without citation is marked unverified`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(
                LlmStreamEvent.Delta("没有任何引用的答案"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 2L),
            ),
        )
        val completed = generator(provider)
            .generate(AnswerRequest("q", listOf(evidence())))
            .toList()
            .last() as AnswerEvent.Completed

        assertTrue(completed.unverified)
        assertTrue(completed.citations.isEmpty())
        assertTrue(completed.invalidCitationTokens.isEmpty())
    }

    @Test
    fun `provider failure is passed through as failed without completed`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(LlmStreamEvent.Failed(LlmErrorCode.RATE_LIMITED, "请求被限流（HTTP 429）：请稍后重试")),
        )
        val events = generator(provider).generate(AnswerRequest("q", listOf(evidence()))).toList()

        val failed = events.single() as AnswerEvent.Failed
        assertEquals(LlmErrorCode.RATE_LIMITED, failed.code)
        assertTrue(failed.message.contains("429"))
    }

    @Test
    fun `blank model answer fails parse instead of empty completed`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(LlmStreamEvent.Completed(model = "m", latencyMs = 3L)),
        )
        val events = generator(provider).generate(AnswerRequest("q", listOf(evidence()))).toList()

        assertEquals(LlmErrorCode.PARSE, (events.single() as AnswerEvent.Failed).code)
    }

    @Test
    fun `evidence beyond maxEvidence is not sent nor accepted as citation`() = runBlocking {
        val twelve = (1..12).map { evidence(chunkId = it.toLong()) }
        val provider = FakeStreamingProvider(
            listOf(
                LlmStreamEvent.Delta("[S10] 第十条"),
                LlmStreamEvent.Completed(model = "m", latencyMs = 4L),
            ),
        )
        val generator = OpenAiAnswerGenerator(
            provider,
            InMemoryLlmConfigStore(validConfig()),
            AnswerGeneratorConfig(maxEvidence = 10),
        )

        val events = generator.generate(AnswerRequest("q", twelve)).toList()

        val userPrompt = provider.lastUser.orEmpty()
        assertTrue(userPrompt.contains("[S10]"))
        assertFalse("S11 不应发送", userPrompt.contains("[S11]"))
        val completed = events.last() as AnswerEvent.Completed
        assertEquals(listOf(10L), completed.citations.map { it.chunkId })
    }

    @Test
    fun `cancellation stops stream without emitting failed event`() = runBlocking {
        val provider = FakeStreamingProvider(
            listOf(LlmStreamEvent.Delta("部分回答")),
            hangAfterEvents = true,
        )
        val generator = generator(provider)

        val events = generator.generate(AnswerRequest("q", listOf(evidence())))
            .take(1)
            .toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AnswerEvent.Delta)
        assertTrue("取消应传播到 provider", provider.cancelled)
    }

    // ---- helpers ----

    private fun validConfig(): LlmConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
        timeoutSeconds = 5,
    )

    private fun evidence(chunkId: Long = 1L, ordinal: Int = 0): ChatEvidence = ChatEvidence(
        evidenceId = "S1",
        chunkId = chunkId,
        documentId = 2L,
        sourceId = 3L,
        docTitle = "文档标题",
        sourceTitle = "来源标题",
        sourceUri = "file:///doc.txt",
        content = "张三在上海学习数学。",
        ordinal = ordinal,
        charStart = 0,
        charEnd = 10,
    )

    private fun generator(provider: FakeStreamingProvider): CountingStoreGenerator =
        CountingStoreGenerator(provider)

    /** 记录 store 访问次数的生成器包装（断言"无证据不读配置"）。 */
    private class CountingStoreGenerator(
        provider: LlmStreamingProvider,
        private val delegate: LlmConfigStore = CountingStore(),
    ) : AnswerGenerator {
        private val inner = OpenAiAnswerGenerator(provider, delegate, AnswerGeneratorConfig())
        val storeAccessCount: Int get() = (delegate as CountingStore).loadCount
        override fun generate(request: AnswerRequest): Flow<AnswerEvent> = inner.generate(request)
    }

    private class CountingStore : LlmConfigStore {
        var loadCount: Int = 0
            private set
        private val delegate = InMemoryLlmConfigStore(
            LlmConfig(
                baseUrl = "https://api.example.com/v1",
                model = "test-model",
                apiKey = "sk-test-1234567890abcd",
                timeoutSeconds = 5,
            ),
        )

        override fun load(): LlmConfig? {
            loadCount++
            return delegate.load()
        }

        override fun save(config: LlmConfig) = delegate.save(config)
        override fun clear() = delegate.clear()
        override fun maskedKey(): String? = delegate.maskedKey()
    }

    private class FakeStreamingProvider(
        private val events: List<LlmStreamEvent>,
        private val hangAfterEvents: Boolean = false,
    ) : LlmStreamingProvider {
        var calls: Int = 0
            private set
        var lastConfig: LlmConfig? = null
        var lastSystem: String? = null
        var lastUser: String? = null
        var lastMaxTokens: Int? = null

        @Volatile
        var cancelled: Boolean = false
            private set

        override fun stream(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): Flow<LlmStreamEvent> = flow {
            calls++
            lastConfig = config
            lastSystem = systemPrompt
            lastUser = userPrompt
            lastMaxTokens = maxTokens
            try {
                events.forEach { emit(it) }
                if (hangAfterEvents) awaitCancellation()
            } finally {
                cancelled = true
            }
        }
    }
}
