package com.zhibox.memory.core.llm

import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.extract.ExtractionInput
import com.zhibox.memory.core.extract.ExtractionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B9 Remote LLM 抽取器（JVM，无网络）：
 * - 配置读取：未配置 → `LLM_NOT_CONFIGURED`；store 抛 `LlmStoreException` → `LLM_STORE_ERROR`；
 *   两种本地失败都**不调用** Provider（零请求语义在 Provider 层另有测试）。
 * - 调用映射：成功文本 → `ExtractionJson.parse`（合法 Success / 非法 JSON/缺字段 Failure）；
 *   Provider Failure → 透传错误码 + readableMessage。
 * - Prompt 传递：systemPrompt 来自 `ExtractionPrompts`（含 11 类/单 chunk 单 event），userPrompt
 *   含 chunk 正文与 sourceTitle，maxTokens 可注入。
 * - 隐私：失败 message 不含 API Key；chunk 正文不作为错误文案回传。
 */
class RemoteLlmEventEntityExtractorTest {

    private val chunkContent = "张三在上海学习数学。"
    private val input = ExtractionInput(
        chunkId = 42L,
        content = chunkContent,
        sourceTitle = "学习笔记",
        entityTypes = listOf(EntityType.PERSON, EntityType.LOCATION, EntityType.SUBJECT),
    )

    private val config = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "gpt-4o-mini",
        apiKey = "sk-test-1234567890abcd",
    )

    private val validEventJson = """
        {
          "title": "上海学习数学",
          "summary": "张三在上海学习数学",
          "content": "张三在上海学习数学。",
          "language": "zh",
          "entities": [
            {"type": "person", "name": "张三", "description": "学生"}
          ],
          "isValid": true
        }
    """.trimIndent()

    @Test
    fun configuredAndValidOutputParsesToSuccess() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(validEventJson, 12, "gpt-4o-mini"))
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore(config))

        val result = extractor.extract(input)

        assertTrue("应成功：$result", result is ExtractionResult.Success)
        val event = (result as ExtractionResult.Success).event
        assertEquals("上海学习数学", event.title)
        assertEquals("zh", event.language)
        assertEquals(1, event.entities.size)
        assertEquals(EntityType.PERSON, event.entities.first().type)

        assertEquals("应调用一次 completion", 1, provider.calls)
        assertEquals(config, provider.lastConfig)
        assertEquals("maxTokens 默认应为 1024", 1024, provider.lastMaxTokens)
        val system = provider.lastSystem.orEmpty()
        assertTrue("systemPrompt 应含 11 类约束", system.contains("单 chunk 单 event"))
        assertTrue(system.contains("person") && system.contains("location") && system.contains("subject"))
        val user = provider.lastUser.orEmpty()
        assertTrue("userPrompt 应含 chunk 正文", user.contains(chunkContent))
        assertTrue("userPrompt 应含来源标题", user.contains("学习笔记"))
        assertFalse("userPrompt 不得包含 API Key", user.contains(config.apiKey))
    }

    @Test
    fun missingConfigFailsNotConfiguredWithoutCallingProvider() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(validEventJson, 1, "m"))
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore())

        val result = extractor.extract(input)

        assertTrue(result is ExtractionResult.Failure)
        assertEquals(LlmErrorCode.NOT_CONFIGURED, (result as ExtractionResult.Failure).code)
        assertEquals("未配置时不得调用 Provider", 0, provider.calls)
    }

    @Test
    fun storeFailureMapsToStoreErrorWithoutCallingProvider() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(validEventJson, 1, "m"))
        val brokenStore = object : LlmConfigStore {
            override fun load(): LlmConfig? = throw LlmStoreException("Keystore 密钥失效")
            override fun save(config: LlmConfig) = Unit
            override fun clear() = Unit
            override fun maskedKey(): String? = null
        }
        val extractor = RemoteLlmEventEntityExtractor(provider, brokenStore)

        val result = extractor.extract(input)

        assertTrue(result is ExtractionResult.Failure)
        val failure = result as ExtractionResult.Failure
        assertEquals(LlmErrorCode.STORE, failure.code)
        assertTrue("错误文案应可读：${failure.message}", failure.message.contains("LLM 配置读取失败"))
        assertFalse("错误文案不得含 API Key", failure.message.contains(config.apiKey))
        assertEquals(0, provider.calls)
    }

    @Test
    fun providerFailureCodeAndReadableMessageArePropagated() = runBlocking {
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Failure(LlmErrorCode.RATE_LIMITED, "请求被限流（HTTP 429）：请稍后重试"),
        )
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore(config))

        val result = extractor.extract(input)

        assertTrue(result is ExtractionResult.Failure)
        val failure = result as ExtractionResult.Failure
        assertEquals(LlmErrorCode.RATE_LIMITED, failure.code)
        assertTrue("应保留可读信息与错误码：${failure.message}", failure.message.contains("429"))
        assertTrue(failure.message.contains(LlmErrorCode.RATE_LIMITED))
        assertFalse(failure.message.contains(config.apiKey))
        assertFalse("chunk 正文不得出现在错误文案", failure.message.contains(chunkContent))
    }

    @Test
    fun invalidJsonOutputMapsToExtractInvalidJson() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success("this-is-not-json", 5, "m"))
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore(config))

        val result = extractor.extract(input)
        assertTrue(result is ExtractionResult.Failure)
        assertEquals(
            ExtractionErrorCodes.INVALID_JSON,
            (result as ExtractionResult.Failure).code,
        )
    }

    @Test
    fun missingFieldOutputKeepsExtractionErrorCode() = runBlocking {
        val provider = FakeCompletionProvider(
            LlmCompletionResult.Success("""{"title":"只有标题"}""", 5, "m"),
        )
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore(config))

        val result = extractor.extract(input)
        assertTrue(result is ExtractionResult.Failure)
        assertEquals(
            ExtractionErrorCodes.MISSING_FIELD,
            (result as ExtractionResult.Failure).code,
        )
    }

    @Test
    fun isValidFalseStillReturnsSuccessEvent() = runBlocking {
        val json = validEventJson.replace("\"isValid\": true", "\"isValid\": false")
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(json, 5, "m"))
        val extractor = RemoteLlmEventEntityExtractor(provider, InMemoryLlmConfigStore(config))

        val result = extractor.extract(input)
        assertTrue(result is ExtractionResult.Success)
        assertFalse((result as ExtractionResult.Success).event.isValid)
    }

    @Test
    fun customMaxTokensIsPassedToProvider() = runBlocking {
        val provider = FakeCompletionProvider(LlmCompletionResult.Success(validEventJson, 5, "m"))
        val extractor = RemoteLlmEventEntityExtractor(
            provider,
            InMemoryLlmConfigStore(config),
            maxTokens = 256,
        )

        val result = extractor.extract(input)
        assertTrue(result is ExtractionResult.Success)
        assertEquals(256, provider.lastMaxTokens)
    }

    private class FakeCompletionProvider(
        private val result: LlmCompletionResult,
    ) : LlmCompletionProvider {
        var calls: Int = 0
        var lastConfig: LlmConfig? = null
        var lastSystem: String? = null
        var lastUser: String? = null
        var lastMaxTokens: Int = -1

        override suspend fun complete(
            config: LlmConfig,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): LlmCompletionResult {
            calls++
            lastConfig = config
            lastSystem = systemPrompt
            lastUser = userPrompt
            lastMaxTokens = maxTokens
            return result
        }
    }
}
