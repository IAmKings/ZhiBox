package com.zhibox.memory.core.chat

import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmHttpConnectionOpener
import com.zhibox.memory.core.llm.LlmStreamEvent
import com.zhibox.memory.core.llm.MAX_STREAM_CHARS
import com.zhibox.memory.core.llm.NetworkPolicy
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.llm.SseLine
import com.zhibox.memory.core.llm.parseSseLine
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B11：OpenAI-compatible SSE 流式 Provider 单测（JVM，fake HttpURLConnection，无网络/无 Robolectric）：
 * Delta/[DONE]/EOF、请求体 stream=true、401/429/超时/坏 JSON/空流、禁网零请求、HTTPS-only、
 * x-opencode-session、取消关闭连接且不冒 Failed。
 *
 * 说明：真实 TLS/网关行为由 data-reviewer 的 mock server 测试覆盖；这里精确覆盖 SSE 分支逻辑。
 */
class StreamingProviderTest {

    private val validConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
        timeoutSeconds = 5,
    )

    @Test
    fun `sse deltas and done map to delta events and single completed`() = runBlocking {
        val body = sse(
            deltaLine("你好"),
            deltaLine("，世界"),
            "data: [DONE]",
        )
        val fake = FakeHttpConnection(200, body)
        val provider = providerWith(fake)

        val events = provider.stream(validConfig, "sys", "user", maxTokens = 768).toList()

        assertEquals(3, events.size)
        assertEquals("你好", (events[0] as LlmStreamEvent.Delta).text)
        assertEquals("，世界", (events[1] as LlmStreamEvent.Delta).text)
        val completed = events[2] as LlmStreamEvent.Completed
        assertEquals("test-model", completed.model)
        assertEquals(0L, completed.latencyMs) // 固定时钟
    }

    @Test
    fun `stream request uses stream true and sse accept header`() = runBlocking {
        val fake = FakeHttpConnection(200, sse(deltaLine("x"), "data: [DONE]"))
        providerWith(fake).stream(validConfig, "系统提示", "用户正文", maxTokens = 768).toList()

        val body = fake.requestBody.toString(Charsets.UTF_8.name())
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"content\":\"系统提示\""))
        assertTrue(body.contains("\"content\":\"用户正文\""))
        assertTrue(body.contains("\"max_tokens\":768"))
        assertFalse("请求体不得包含 API Key", body.contains(validConfig.apiKey))
        assertEquals("Bearer " + validConfig.apiKey, fake.requestHeaders["Authorization"])
        assertTrue(
            "Accept 必须是 SSE：${fake.requestHeaders["Accept"]}",
            fake.requestHeaders["Accept"].orEmpty().contains("text/event-stream"),
        )
        assertEquals("POST", fake.capturedMethod)
        assertEquals(1, fake.disconnectCount.get())
    }

    @Test
    fun `eof without done but with content is treated as completed`() = runBlocking {
        val fake = FakeHttpConnection(200, sse(deltaLine("内容")))
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        val completed = events.last() as LlmStreamEvent.Completed
        assertEquals("内容", (events.first() as LlmStreamEvent.Delta).text)
        assertEquals("test-model", completed.model)
    }

    @Test
    fun `empty stream fails parse`() = runBlocking {
        val fake = FakeHttpConnection(200, "")
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        assertEquals(LlmErrorCode.PARSE, (events.single() as LlmStreamEvent.Failed).code)
    }

    @Test
    fun `bad json in data line fails parse without crash`() = runBlocking {
        val fake = FakeHttpConnection(200, "data: not-json\n")
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        val failed = events.single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.PARSE, failed.code)
        assertTrue(failed.message.contains("JSON"))
    }

    @Test
    fun `server error object mid stream fails http with readable detail`() = runBlocking {
        val fake = FakeHttpConnection(
            200,
            "data: {\"error\":{\"message\":\"quota exceeded\"}}\n",
        )
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        val failed = events.single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.HTTP, failed.code)
        assertTrue(failed.message.contains("quota exceeded"))
        assertFalse("错误信息不得包含 API Key", failed.message.contains(validConfig.apiKey))
    }

    @Test
    fun `non string delta content fails parse`() = runBlocking {
        val fake = FakeHttpConnection(
            200,
            "data: {\"choices\":[{\"delta\":{\"content\":123}}]}\n",
        )
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        assertEquals(LlmErrorCode.PARSE, (events.single() as LlmStreamEvent.Failed).code)
    }

    @Test
    fun `401 maps auth error`() = runBlocking {
        val fake = FakeHttpConnection(401, """{"error":{"message":"invalid api key"}}""")
        val events = providerWith(fake).stream(validConfig, "s", "u").toList()

        val failed = events.single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.AUTH, failed.code)
        assertTrue(failed.message.contains("401"))
        assertFalse(failed.message.contains(validConfig.apiKey))
    }

    @Test
    fun `429 maps rate limited`() = runBlocking {
        val fake = FakeHttpConnection(429, """{"error":{"message":"too many requests"}}""")
        val failed = providerWith(fake).stream(validConfig, "s", "u").toList()
            .single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.RATE_LIMITED, failed.code)
    }

    @Test
    fun `500 maps http error`() = runBlocking {
        val fake = FakeHttpConnection(500, """{"error":{"message":"boom"}}""")
        val failed = providerWith(fake).stream(validConfig, "s", "u").toList()
            .single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.HTTP, failed.code)
        assertTrue(failed.message.contains("500"))
    }

    @Test
    fun `timeout maps timeout`() = runBlocking {
        val fake = FakeHttpConnection(200, "", responseCodeFailure = SocketTimeoutException("timed out"))
        val failed = providerWith(fake).stream(validConfig, "s", "u").toList()
            .single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.TIMEOUT, failed.code)
        assertTrue(failed.message.contains("超时"))
    }

    @Test
    fun `io failure maps network error`() = runBlocking {
        val fake = FakeHttpConnection(200, "", responseCodeFailure = IOException("connection reset"))
        val failed = providerWith(fake).stream(validConfig, "s", "u").toList()
            .single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.NETWORK, failed.code)
    }

    @Test
    fun `blocked network policy opens zero connections`() = runBlocking {
        var openCalls = 0
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysBlocked(),
            opener = LlmHttpConnectionOpener {
                openCalls++
                FakeHttpConnection(200, sse(deltaLine("x"), "data: [DONE]"))
            },
        )
        val events = provider.stream(validConfig, "s", "u").toList()

        val failed = events.single() as LlmStreamEvent.Failed
        assertEquals(LlmErrorCode.OFFLINE, failed.code)
        assertEquals("禁网不得打开任何连接", 0, openCalls)
    }

    @Test
    fun `insecure http base url rejected before opening connection`() = runBlocking {
        var openCalls = 0
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            opener = LlmHttpConnectionOpener {
                openCalls++
                FakeHttpConnection(200, sse(deltaLine("x"), "data: [DONE]"))
            },
        )
        val events = provider.stream(
            validConfig.copy(baseUrl = "http://api.example.com/v1"),
            "s",
            "u",
        ).toList()

        assertEquals(LlmErrorCode.INSECURE_URL, (events.single() as LlmStreamEvent.Failed).code)
        assertEquals(0, openCalls)
    }

    @Test
    fun `invalid max tokens fails before opening connection`() = runBlocking {
        var openCalls = 0
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            opener = LlmHttpConnectionOpener {
                openCalls++
                FakeHttpConnection(200, sse(deltaLine("x"), "data: [DONE]"))
            },
        )
        val events = provider.stream(validConfig, "s", "u", maxTokens = 0).toList()

        assertEquals(LlmErrorCode.INVALID_CONFIG, (events.single() as LlmStreamEvent.Failed).code)
        assertEquals(0, openCalls)
    }

    @Test
    fun `opencode endpoint sends session header`() = runBlocking {
        val fake = FakeHttpConnection(200, sse(deltaLine("x"), "data: [DONE]"))
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            opener = LlmHttpConnectionOpener { fake },
            openCodeSessionIdProvider = { "zhibox-session-123" },
        )
        provider.stream(
            validConfig.copy(baseUrl = "https://opencode.ai/zen/go/v1"),
            "s",
            "u",
        ).toList()

        assertEquals("zhibox-session-123", fake.requestHeaders["x-opencode-session"])
    }

    @Test
    fun `cancellation closes connection and does not emit failed`() = runBlocking {
        val stream = BlockingSseStream()
        val fake = FakeHttpConnection(200, "", streamFactory = { stream }, onDisconnect = { stream.release() })
        val provider = providerWith(fake)

        val collected = mutableListOf<LlmStreamEvent>()
        withTimeout(5_000) {
            provider.stream(validConfig, "s", "u").take(1).collect { collected += it }
        }

        assertEquals(1, collected.size)
        assertTrue("首个事件应为 Delta（无 Failed）：$collected", collected.singleOrNull() is LlmStreamEvent.Delta)
        assertTrue("取消时必须关闭连接", fake.disconnectCount.get() >= 1)
        assertTrue("阻塞读应被 disconnect 释放", stream.released)
    }

    @Test
    fun `max stream chars constant is bounded`() {
        assertTrue(MAX_STREAM_CHARS >= 64 * 1024)
    }

    @Test
    fun `parse sse line handles ignore comment done error and delta`() {
        assertTrue(parseSseLine("", "m") is SseLine.Ignore)
        assertTrue(parseSseLine(": keep-alive", "m") is SseLine.Ignore)
        assertTrue(parseSseLine("event: delta", "m") is SseLine.Ignore)
        assertEquals("m", (parseSseLine("data: [DONE]", "m") as SseLine.Done).model)
        assertEquals(
            "hello",
            (parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}", "m") as SseLine.Delta)
                .text,
        )
        val error = parseSseLine("data: {\"error\":{\"message\":\"bad\"}}", "m") as SseLine.Invalid
        assertEquals(LlmErrorCode.HTTP, error.code)
        assertTrue(error.message.contains("bad"))
        val fallback = parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}", "cfg-model") as SseLine.Delta
        assertEquals("cfg-model", fallback.model)
    }

    // ---- helpers ----

    private fun providerWith(fake: FakeHttpConnection): OpenAiCompatibleLlmProvider =
        OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            clockMillis = { FIXED_CLOCK_MS },
            opener = LlmHttpConnectionOpener { fake },
        )

    private fun sse(vararg lines: String): String = lines.joinToString("\n") + "\n"

    private fun deltaLine(content: String): String =
        """data: {"model":"test-model","choices":[{"index":0,"delta":{"content":${jsonString(content)}}}]}"""

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 阻塞读流：先吐出第一条 SSE，然后阻塞直到 disconnect 释放（用于取消测试）。 */
    private class BlockingSseStream : InputStream() {
        private val first = (
            "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n"
            ).toByteArray(Charsets.UTF_8)
        private var position = 0
        private val releasedSignal = CountDownLatch(1)

        @Volatile
        var released: Boolean = false
            private set

        fun release() {
            released = true
            releasedSignal.countDown()
        }

        override fun read(): Int {
            if (position < first.size) return first[position++].toInt() and 0xFF
            // 有界等待：生产代码若未在取消时 disconnect，最多 2s 后抛 IOException
            // （让用例快速失败而不是永久挂住 test worker）。
            if (!releasedSignal.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("timeout waiting for disconnect")
            }
            throw IOException("stream closed")
        }

        private companion object {
            const val MAX_WAIT_MS = 2_000L
        }
    }

    private class FakeHttpConnection(
        private val status: Int,
        private val body: String,
        private val responseCodeFailure: Throwable? = null,
        private val streamFactory: (() -> InputStream)? = null,
        private val onDisconnect: (() -> Unit)? = null,
    ) : HttpURLConnection(URL("https://llm.invalid/v1/chat/completions")) {
        val requestBody = ByteArrayOutputStream()
        val requestHeaders = LinkedHashMap<String, String>()
        val connectCount = AtomicInteger(0)
        val disconnectCount = AtomicInteger(0)
        var capturedMethod: String = ""

        override fun connect() {
            connectCount.incrementAndGet()
        }

        override fun disconnect() {
            disconnectCount.incrementAndGet()
            onDisconnect?.invoke()
        }

        override fun usingProxy(): Boolean = false

        override fun setRequestMethod(method: String?) {
            capturedMethod = method.orEmpty()
        }

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null) requestHeaders[key] = value.orEmpty()
        }

        override fun getOutputStream(): OutputStream = requestBody

        override fun getResponseCode(): Int {
            responseCodeFailure?.let { throw it }
            return status
        }

        override fun getErrorStream(): InputStream? =
            if (status !in HTTP_SUCCESS_CODES && body.isNotEmpty()) body.byteInputStream() else null

        override fun getInputStream(): InputStream {
            if (status !in HTTP_SUCCESS_CODES) throw IOException("unexpected non-2xx status $status")
            return streamFactory?.invoke() ?: body.byteInputStream()
        }
    }

    private companion object {
        const val FIXED_CLOCK_MS = 1_000L
        val HTTP_SUCCESS_CODES = 200..299
    }
}
