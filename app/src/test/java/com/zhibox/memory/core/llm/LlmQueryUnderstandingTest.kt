package com.zhibox.memory.core.llm

import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.sag.QueryEntity
import com.zhibox.memory.core.sag.SagHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * B10 查询理解/上下文选择 LLM 实现纯 JVM 单测（fake [LlmCompletionProvider] + stub store，无网络）：
 * - 查询实体：合法 JSON（类型/归一化/score 排序/去重/截断/fence 容错）、坏 JSON/未知类型/重复、
 *   未配置/禁网/超时/异常 → **空列表**（调用方词法兜底），CancellationException 上抛；
 * - 上下文选择：按 LLM 顺序返回候选子集（≤maxEvents/去重/忽略未知 id）、坏 JSON/超时/未配置 →
 *   确定性返回前 K（原顺序）、合法空选择→空、无 eventId 候选零 LLM 调用、候选上限/正文截断；
 * - 隐私：prompt 不含 API Key；selector prompt 只含必要摘要（长 chunk 截断、单行）。
 */
class LlmQueryUnderstandingTest {

    private val config = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-abcdefghijklmnop",
    )

    // ======================= LlmQueryEntityExtractor =======================

    @Test
    fun `valid query entities parsed with normalization and llm source`() = runBlocking {
        val json = """
            {"entities":[
              {"type":"person","name":"  张三  ","score":0.8},
              {"type":"organization","name":"OpenAI  Inc"},
              {"type":"location","name":"上海"}
            ]}
        """.trimIndent()
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(json, 5, "m"))
        val extractor = LlmQueryEntityExtractor(provider, StubStore(config))

        val entities = extractor.extract("张三在上海的 OpenAI 工作")

        assertEquals(3, entities.size)
        // score 降序：OpenAI(1.0)/上海(1.0) 在前，张三(0.8) 最后；同分保持输入顺序
        assertEquals(listOf("OpenAI  Inc", "上海", "张三"), entities.map { it.name })
        val person = entities.last()
        assertEquals(LlmQueryEntityExtractor.LLM_QUERY_ENTITY_ID, person.id)
        assertEquals(EntityType.PERSON.wire, person.type)
        assertEquals("张三", person.name)
        assertEquals("张三", person.normalizedName)
        assertEquals(LlmQueryEntityExtractor.SOURCE_LLM, person.source)
        assertEquals(0.8, person.score, 1e-9)
        assertEquals("openai inc", entities[0].normalizedName)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `query entity output is capped by maxEntities`() = runBlocking {
        val json = """{"entities":[
            {"type":"person","name":"A"},{"type":"person","name":"B"},{"type":"person","name":"C"}
        ]}"""
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(json, 1, "m"))
        val extractor = LlmQueryEntityExtractor(provider, StubStore(config), maxEntities = 2)
        assertEquals(listOf("A", "B"), extractor.extract("q").map { it.name })
    }

    @Test
    fun `query entity duplicates by normalized name are dropped`() = runBlocking {
        // 归一化=trim+lowercase+连续空白合一（不删除内部空格）：trim/大小写差异应去重
        val json = """{"entities":[
            {"type":"person","name":"张三"},{"type":"person","name":"  张三  "},
            {"type":"organization","name":"OpenAI Inc"},{"type":"organization","name":"openai  inc"}
        ]}"""
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(json, 1, "m"))
        val extractor = LlmQueryEntityExtractor(provider, StubStore(config))
        val entities = extractor.extract("q")
        assertEquals(2, entities.size)
        assertEquals(listOf("张三", "OpenAI Inc"), entities.map { it.name })
        assertEquals(listOf("张三", "openai inc"), entities.map { it.normalizedName })
    }

    @Test
    fun `query entity unknown type and blank name are skipped`() = runBlocking {
        val json = """{"entities":[
            {"type":"unknown_type","name":"X"},
            {"type":"person","name":"   "},
            {"type":"person","name":"有效"}
        ]}"""
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(json, 1, "m"))
        val entities = LlmQueryEntityExtractor(provider, StubStore(config)).extract("q")
        assertEquals(listOf("有效"), entities.map { it.name })
    }

    @Test
    fun `query entity fence tolerated and bad json degrades to empty`() = runBlocking {
        val fenced = "```json\n{\"entities\":[{\"type\":\"person\",\"name\":\"张三\"}]}\n```"
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(fenced, 1, "m"))
        assertEquals(1, LlmQueryEntityExtractor(provider, StubStore(config)).extract("q").size)

        assertTrue(
            LlmQueryEntityExtractor(FakeCompletionProvider(LlmCompletionResult.Success("not-json", 1, "m")), StubStore(config))
                .extract("q")
                .isEmpty(),
        )
        assertTrue(
            LlmQueryEntityExtractor(FakeCompletionProvider(LlmCompletionResult.Success("""{"entities":"x"}""", 1, "m")), StubStore(config))
                .extract("q")
                .isEmpty(),
        )
    }

    @Test
    fun `query entity offline and provider errors degrade to empty without throwing`() = runBlocking {
        val offline = FakeCompletionProvider(LlmCompletionResult.Failure(LlmErrorCode.OFFLINE, "网络已禁用"))
        assertTrue(LlmQueryEntityExtractor(offline, StubStore(config)).extract("q").isEmpty())

        val failing = FakeCompletionProvider(LlmCompletionResult.Success("{}", 1, "m"), throwable = IOException("boom"))
        assertTrue(LlmQueryEntityExtractor(failing, StubStore(config)).extract("q").isEmpty())

        // store 未配置 / 损坏 → 空集合，且不调用 provider
        val notCalled = FakeCompletionProvider(LlmCompletionResult.Success("{}", 1, "m"))
        assertTrue(LlmQueryEntityExtractor(notCalled, StubStore(null)).extract("q").isEmpty())
        assertTrue(LlmQueryEntityExtractor(notCalled, StubStore(config, storeFailure = LlmStoreException("坏配置"))).extract("q").isEmpty())
        assertEquals(0, notCalled.calls)
    }

    @Test
    fun `query entity blank query does not call llm`() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("{}", 1, "m"))
        assertTrue(LlmQueryEntityExtractor(provider, StubStore(config)).extract("   ").isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test
    fun `query entity cancellation is propagated`() = runBlocking {
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("{}", 1, "m"),
            throwable = CancellationException("cancelled"),
        )
        try {
            LlmQueryEntityExtractor(provider, StubStore(config)).extract("q")
            fail("CancellationException 必须向上传播")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun `query entity prompt contains schema and query but never api key`() = runBlocking {
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("""{"entities":[]}""", 1, "m"),
        )
        val extractor = LlmQueryEntityExtractor(provider, StubStore(config), maxEntities = 3)
        val query = "张三在上海学数学"
        extractor.extract(query)

        assertTrue(provider.lastSystemPrompt.contains("11 类"))
        assertTrue(provider.lastSystemPrompt.contains("person"))
        assertTrue(provider.lastSystemPrompt.contains("只输出"))
        assertTrue(provider.lastUserPrompt.contains(query))
        assertFalse(provider.lastSystemPrompt.contains(config.apiKey))
        assertFalse(provider.lastUserPrompt.contains(config.apiKey))
        assertEquals(
            LlmQueryEntityExtractor.DEFAULT_COMPLETION_MAX_TOKENS_FOR_QUERY,
            provider.lastMaxTokens,
        )
    }

    // ======================= LlmSagContextSelector =======================

    @Test
    fun `selector returns candidates in llm order and caps to maxEvents`() = runBlocking {
        val candidates = listOf(
            hit(eventId = 1, title = "事件1"),
            hit(eventId = 2, title = "事件2"),
            hit(eventId = 3, title = "事件3"),
        )
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("""{"selected":[3,1,2]}""", 4, "m"),
        )
        val selector = LlmSagContextSelector(provider, StubStore(config), maxEvents = 2)

        val selected = selector.select("q", candidates)

        // LLM 顺序 [3,1,2] 截到 maxEvents=2 → [3,1]（不是排序，而是尊重模型选择顺序）
        assertEquals(listOf(3L, 1L), selected.map { it.eventId })
    }

    @Test
    fun `selector ignores unknown ids and duplicates`() = runBlocking {
        val candidates = listOf(hit(eventId = 1), hit(eventId = 2))
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("""{"selected":[2,99,2,1]}""", 4, "m"),
        )
        val selector = LlmSagContextSelector(provider, StubStore(config), maxEvents = 5)
        assertEquals(listOf(2L, 1L), selector.select("q", candidates).map { it.eventId })
    }

    @Test
    fun `selector bad json or timeout or no config falls back to first K in original order`() = runBlocking {
        val candidates = listOf(hit(eventId = 1), hit(eventId = 2), hit(eventId = 3))

        val badJson = FakeCompletionProvider(LlmCompletionResult.Success("not-json", 1, "m"))
        assertEquals(
            listOf(1L, 2L),
            LlmSagContextSelector(badJson, StubStore(config), maxEvents = 2)
                .select("q", candidates).map { it.eventId },
        )

        val timeout = FakeCompletionProvider(
            LlmCompletionResult.Failure(LlmErrorCode.TIMEOUT, "超时"),
        )
        assertEquals(
            listOf(1L, 2L),
            LlmSagContextSelector(timeout, StubStore(config), maxEvents = 2)
                .select("q", candidates).map { it.eventId },
        )

        val notConfigured = FakeCompletionProvider(LlmCompletionResult.Success("""{"selected":[3]}""", 1, "m"))
        assertEquals(
            listOf(1L, 2L),
            LlmSagContextSelector(notConfigured, StubStore(null), maxEvents = 2)
                .select("q", candidates).map { it.eventId },
        )
        assertEquals(0, notConfigured.calls)
    }

    @Test
    fun `selector valid empty selection returns empty list`() = runBlocking {
        val candidates = listOf(hit(eventId = 1), hit(eventId = 2))
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("""{"selected":[]}""", 1, "m"))
        assertTrue(
            LlmSagContextSelector(provider, StubStore(config), maxEvents = 2).select("q", candidates).isEmpty(),
        )
    }

    @Test
    fun `selector candidates without event id fall back without llm`() = runBlocking {
        val candidates = listOf(hit(eventId = null), hit(eventId = null))
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("""{"selected":[1]}""", 1, "m"))
        val selected = LlmSagContextSelector(provider, StubStore(config), maxEvents = 2).select("q", candidates)
        assertEquals(candidates, selected)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `selector empty candidates returns empty without llm`() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("""{"selected":[1]}""", 1, "m"))
        assertTrue(LlmSagContextSelector(provider, StubStore(config)).select("q", emptyList()).isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test
    fun `selector bounds candidates and truncates context in prompt`() = runBlocking {
        val longContent = "长正文".repeat(300) // 900+ 字
        val candidates = listOf(
            hit(eventId = 1, title = "标题1", summary = "摘要1", entities = listOf("张三", "上海"), docTitle = "文档1", content = longContent),
            hit(eventId = 2, title = "标题2", content = "正文2"),
            hit(eventId = 3, title = "标题3", content = "正文3"),
        )
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("""{"selected":[1]}""", 1, "m"))
        val selector = LlmSagContextSelector(
            provider,
            StubStore(config),
            maxEvents = 2,
            maxCandidates = 2,
        )
        selector.select("查询", candidates)

        val prompt = provider.lastUserPrompt
        assertTrue(prompt.contains("eventId=1"))
        assertTrue(prompt.contains("eventId=2"))
        assertFalse("超过 maxCandidates 的候选不得进入 prompt", prompt.contains("eventId=3"))
        assertTrue(prompt.contains("eventTitle=标题1"))
        assertTrue(prompt.contains("eventSummary=摘要1"))
        assertTrue(prompt.contains("entities=张三/上海"))
        assertTrue(prompt.contains("docTitle=文档1"))
        assertFalse("长 chunk 正文必须截断", prompt.contains(longContent))
        assertTrue(prompt.contains(longContent.take(200)))
        assertTrue(prompt.contains("查询"))
        assertFalse(prompt.contains(config.apiKey))
        assertEquals(
            LlmSagContextSelector.DEFAULT_COMPLETION_MAX_TOKENS_FOR_SELECTION,
            provider.lastMaxTokens,
        )
    }

    @Test
    fun `selector cancellation is propagated`() = runBlocking {
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("""{"selected":[]}""", 1, "m"),
            throwable = CancellationException("cancelled"),
        )
        try {
            LlmSagContextSelector(provider, StubStore(config)).select("q", listOf(hit(eventId = 1)))
            fail("CancellationException 必须向上传播")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    // ======================= mocks =======================

    @Test
    fun `mock extractors behave deterministically`() = runBlocking {
        val entity = QueryEntity(0L, EntityType.PERSON.wire, "张三", "张三", 1.0, "llm")
        val mockExtractor = MockLlmQueryEntityExtractor(listOf(entity))
        assertEquals(listOf(entity), mockExtractor.extract("q"))
        assertEquals(1, mockExtractor.calls)
        assertEquals("q", mockExtractor.lastQuery)

        val candidates = listOf(hit(eventId = 1), hit(eventId = 2), hit(eventId = 3))
        val fallbackSelector = MockSagContextSelector(maxEvents = 2)
        assertEquals(listOf(1L, 2L), fallbackSelector.select("q", candidates).map { it.eventId })
        val fixedSelector = MockSagContextSelector(result = listOf(candidates[2]))
        assertEquals(listOf(3L), fixedSelector.select("q", candidates).map { it.eventId })
        assertEquals(1, fixedSelector.calls)
    }

    // ======================= helpers =======================

    private fun hit(
        eventId: Long?,
        title: String = "",
        summary: String = "",
        entities: List<String> = emptyList(),
        docTitle: String = "文档",
        content: String = "正文",
    ): SagHit = SagHit(
        chunkId = eventId ?: 0L,
        documentId = "doc-$eventId",
        sourceId = "src",
        ordinal = 0,
        content = content,
        charStart = 0,
        charEnd = content.length,
        docTitle = docTitle,
        sourceTitle = "来源",
        sourceUri = "content://test/$eventId",
        eventId = eventId,
        entityIds = emptyList(),
        channels = emptySet(),
        score = 0.5,
        fusedScore = null,
        eventTitle = title,
        eventSummary = summary,
        entityNames = entities,
    )

    private class StubStore(
        private val config: LlmConfig?,
        private val storeFailure: Throwable? = null,
    ) : LlmConfigStore {
        override fun load(): LlmConfig? {
            storeFailure?.let { throw it }
            return config
        }

        override fun save(config: LlmConfig) = Unit

        override fun clear() = Unit

        override fun maskedKey(): String? = config?.let { LlmConfig.maskSecret(it.apiKey) }
    }

    private class FakeCompletionProvider(
        private val result: LlmCompletionResult,
        private val throwable: Throwable? = null,
    ) : LlmCompletionProvider {
        var calls: Int = 0
        var lastSystemPrompt: String = ""
        var lastUserPrompt: String = ""
        var lastMaxTokens: Int = -1

        override suspend fun complete(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): LlmCompletionResult {
            calls++
            lastSystemPrompt = systemPrompt
            lastUserPrompt = userPrompt
            lastMaxTokens = maxTokens
            throwable?.let { throw it }
            return result
        }
    }
}
