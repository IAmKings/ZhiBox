package com.zhibox.memory.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * B11 SSE 流式生成（JVM，真实 `HttpsURLConnection` + 本地 TLS mock，无外网）：
 * - 200 SSE：多个 Delta 按顺序 + `[DONE]` → 末尾 Completed(model/latency)；
 * - 429/500：Failed 稳定码可读、不含 API Key/正文；
 * - SSE 坏 JSON / 缺 choices / error 对象：Failed PARSE/HTTP，不崩溃；
 * - EOF 无 `[DONE]` 但已有内容 → Completed（兼容部分 OpenAI-compatible 服务端，文档化行为）；
 * - 空流 → Failed PARSE；
 * - NetworkPolicy 禁网 → Failed OFFLINE 且**零连接**（本地 ServerSocket 观测）；
 * - 空提示词/非法配置：Failed INVALID_CONFIG 且零请求。
 */
class LlmStreamingMockHttpsTest {

    private val validConfig = LlmConfig(
        baseUrl = "https://127.0.0.1:1/v1",
        model = "gpt-4o-mini",
        apiKey = "sk-test-abcdefghijklmnop",
        timeoutSeconds = 5,
    )

    private val systemPrompt = "SYSTEM RULES: 只根据证据回答"
    private val userPrompt = "USER BODY: 光合作用是什么？[S1]"

    // ---- 200 SSE 正常路径 ----

    @Test
    fun sse200EmitsDeltasInOrderThenCompleted() {
        val body = sse(
            """{"model":"gpt-4o-mini","choices":[{"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"delta":{"content":"光合"}}]}""",
            """{"choices":[{"delta":{"content":"作用"}}]}""",
            """{"choices":[{"delta":{"content":" [S1]"}}]}""",
            "[DONE]",
        )
        MockHttpsServer { MockResponse(200, body, contentType = "text/event-stream; charset=utf-8") }.use { server ->
            val events = collect(server)

            assertEquals("3 段 Delta + Completed", 4, events.size)
            assertEquals(
                listOf("光合", "作用", " [S1]"),
                events.filterIsInstance<LlmStreamEvent.Delta>().map { it.text },
            )
            val completed = events.last() as LlmStreamEvent.Completed
            assertEquals("gpt-4o-mini", completed.model)
            assertTrue(completed.latencyMs >= 0)
            assertFalse("正常流不得失败", events.any { it is LlmStreamEvent.Failed })

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer " + validConfig.apiKey, request.headers["authorization"])
            assertTrue(request.body.contains("\"stream\":true"))
            assertTrue(request.body.contains("\"role\":\"system\""))
            assertTrue(request.body.contains(systemPrompt))
            assertTrue(request.body.contains("\"role\":\"user\""))
            assertTrue(request.body.contains(userPrompt))
            assertTrue(request.body.contains("\"max_tokens\":256"))
            assertFalse("请求体不得包含 API Key", request.body.contains(validConfig.apiKey))
        }
    }

    @Test
    fun sseEofWithoutDoneAfterContentStillCompletes() {
        // 部分 OpenAI-compatible 服务端在内容后直接断流（无 [DONE]）：按文档化行为视为正常完成。
        val body = sse("""{"choices":[{"delta":{"content":"内容"}}]}""")
        MockHttpsServer { MockResponse(200, body, contentType = "text/event-stream") }.use { server ->
            val events = collect(server)
            assertEquals(2, events.size)
            assertEquals("内容", (events.first() as LlmStreamEvent.Delta).text)
            assertTrue("EOF 且已有内容应 Completed", events.last() is LlmStreamEvent.Completed)
            assertFalse(events.any { it is LlmStreamEvent.Failed })
        }
    }

    // ---- 非 2xx ----

    @Test
    fun sse429MapsRateLimitedWithoutLeakingKeyOrBody() {
        MockHttpsServer {
            MockResponse(429, """{"error":{"message":"too many requests"}}""")
        }.use { server ->
            val events = collect(server)
            assertEquals(1, events.size)
            val failed = events.single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.RATE_LIMITED, failed.code)
            assertTrue("文案应含 HTTP 状态：${failed.message}", failed.message.contains("429"))
            assertTrue("文案应含服务端 detail", failed.message.contains("too many requests"))
            assertFalse("不得泄漏 API Key", failed.message.contains(validConfig.apiKey))
        }
    }

    @Test
    fun sse500MapsHttpFailure() {
        MockHttpsServer {
            MockResponse(500, """{"error":{"message":"upstream exploded"}}""")
        }.use { server ->
            val failed = collect(server).single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.HTTP, failed.code)
            assertTrue(failed.message.contains("500"))
            assertTrue(failed.message.contains("upstream exploded"))
            assertFalse(failed.message.contains(validConfig.apiKey))
        }
    }

    @Test
    fun sse401MapsAuthFailure() {
        MockHttpsServer { MockResponse(401, """{"error":{"message":"invalid api key"}}""") }.use { server ->
            val failed = collect(server).single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.AUTH, failed.code)
            assertTrue(failed.message.contains("401"))
            assertTrue(failed.message.contains("invalid api key"))
            assertFalse(failed.message.contains(validConfig.apiKey))
        }
    }

    // ---- 坏 SSE ----

    @Test
    fun sseBadJsonLineFailsParseWithoutCrash() {
        MockHttpsServer {
            MockResponse(200, sse("{not-json}"), contentType = "text/event-stream")
        }.use { server ->
            val events = collect(server)
            assertEquals(1, events.size)
            val failed = events.single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.PARSE, failed.code)
            assertTrue("文案应可读：${failed.message}", failed.message.contains("非法 JSON"))
            assertFalse(failed.message.contains(validConfig.apiKey))
        }
    }

    @Test
    fun sseMissingChoicesFailsParse() {
        MockHttpsServer {
            MockResponse(200, sse("""{"foo":1}"""), contentType = "text/event-stream")
        }.use { server ->
            val failed = collect(server).single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.PARSE, failed.code)
            assertTrue(failed.message.contains("choices"))
        }
    }

    @Test
    fun sseErrorObjectMapsHttpFailure() {
        MockHttpsServer {
            MockResponse(200, sse("""{"error":{"message":"invalid key"}}"""), contentType = "text/event-stream")
        }.use { server ->
            val failed = collect(server).single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.HTTP, failed.code)
            assertTrue(failed.message.contains("invalid key"))
            assertFalse(failed.message.contains(validConfig.apiKey))
        }
    }

    @Test
    fun sseEmptyStreamFailsParse() {
        MockHttpsServer { MockResponse(200, "", contentType = "text/event-stream") }.use { server ->
            val failed = collect(server).single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.PARSE, failed.code)
            assertTrue("文案应说明空流：${failed.message}", failed.message.contains("流式响应为空"))
        }
    }

    // ---- 禁网 / 本地前置校验（零连接） ----

    @Test
    fun offlinePolicyMakesZeroConnections() {
        ServerSocket(0).use { observer ->
            observer.soTimeout = 300
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysBlocked())

            val events = runBlocking {
                provider.stream(validConfig, systemPrompt, userPrompt, maxTokens = 256).toList()
            }

            val failed = events.single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.OFFLINE, failed.code)
            assertTrue(failed.message.contains("网络访问已禁用"))
            assertFalse(failed.message.contains(validConfig.apiKey))
            val connected = try {
                observer.accept().use { true }
            } catch (e: SocketTimeoutException) {
                false
            } catch (e: IOException) {
                false
            }
            assertFalse("禁网时不得发起任何连接", connected)
        }
    }

    @Test
    fun blankPromptsFailWithoutRequest() {
        MockHttpsServer {
            MockResponse(200, sse("[DONE]"), contentType = "text/event-stream")
        }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val events = runBlocking {
                provider.stream(validConfig.copy(baseUrl = server.baseUrl()), "", "user", 256).toList()
            }
            val failed = events.single() as LlmStreamEvent.Failed
            assertEquals(LlmErrorCode.INVALID_CONFIG, failed.code)
            assertTrue(server.requests.isEmpty())
        }
    }

    // ---- 工具 ----

    private fun collect(server: MockHttpsServer): List<LlmStreamEvent> = runBlocking {
        OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            .stream(validConfig.copy(baseUrl = server.baseUrl()), systemPrompt, userPrompt, maxTokens = 256)
            .toList()
    }

    private fun sse(vararg payloads: String): String =
        payloads.joinToString("") { payload -> "data: $payload\n\n" }
}
