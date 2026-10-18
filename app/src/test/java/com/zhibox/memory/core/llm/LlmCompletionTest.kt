package com.zhibox.memory.core.llm

import kotlinx.coroutines.runBlocking
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

/**
 * B9 LLM completion 纯 JVM 单测（fake HttpURLConnection，无网络/无 Robolectric）：
 * 200 成功 / system+user 请求体 / max_tokens / 401 / 403 / 429 / 5xx / 超时 / IO /
 * 坏 JSON / 空 choices / content 非字符串 / 空内容 / 禁网零请求 / HTTPS-only / key 不入错误。
 *
 * 说明：真实 `HttpsURLConnection` 行为（TLS/401 error body 等）由 data-reviewer 的
 * `LlmProviderMockHttpsTest` TLS mock 覆盖；这里通过 internal opener 注入 fake connection
 * 精确覆盖 completion 分支逻辑，不依赖网络。
 */
class LlmCompletionTest {

    private val validConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
        timeoutSeconds = 5,
    )

    // ---- 成功路径 ----

    @Test
    fun `completion 200 returns text model and latency`() {
        val body = """
            {"id":"x","model":"gpt-4o-mini","choices":[
              {"index":0,"message":{"role":"assistant","content":"{\"title\":\"t\",\"isValid\":true}"}}
            ],"usage":{"total_tokens":8}}
        """.trimIndent()
        val fake = FakeHttpConnection(status = 200, body = body)
        val provider = providerWith(fake)

        val result = runBlocking { provider.complete(validConfig, "sys prompt", "user prompt", maxTokens = 512) }

        assertTrue("应成功：$result", result is LlmCompletionResult.Success)
        result as LlmCompletionResult.Success
        assertEquals("{\"title\":\"t\",\"isValid\":true}", result.text)
        assertEquals("gpt-4o-mini", result.model)
        assertEquals(0L, result.latencyMs) // 固定时钟
    }

    @Test
    fun `completion request has system and user messages and max tokens`() {
        val fake = FakeHttpConnection(status = 200, body = successBody("ok"))
        val provider = providerWith(fake)

        runBlocking { provider.complete(validConfig, "系统提示", "用户正文", maxTokens = 768) }

        val body = fake.requestBody.toString(Charsets.UTF_8.name())
        assertEquals("POST", fake.capturedMethod)
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"系统提示\""))
        assertTrue(body.contains("\"content\":\"用户正文\""))
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"max_tokens\":768"))
        assertTrue(body.contains("\"stream\":false"))
        assertFalse("请求体不得包含 API Key", body.contains(validConfig.apiKey))
        assertEquals("Bearer " + validConfig.apiKey, fake.requestHeaders["Authorization"])
        assertEquals(1, fake.disconnectCount.get())
    }

    @Test
    fun `completion default max tokens is 1024`() {
        val fake = FakeHttpConnection(status = 200, body = successBody("x"))
        runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertTrue(fake.requestBody.toString(Charsets.UTF_8.name()).contains("\"max_tokens\":1024"))
    }

    @Test
    fun `completion model falls back to config when response omits model`() {
        val fake = FakeHttpConnection(status = 200, body = """{"choices":[{"message":{"content":"ok"}}]}""")
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertTrue(result is LlmCompletionResult.Success)
        assertEquals("test-model", (result as LlmCompletionResult.Success).model)
    }

    // ---- 非 2xx ----

    @Test
    fun `completion 401 maps auth error and does not leak key`() {
        val fake = FakeHttpConnection(401, """{"error":{"message":"invalid api key"}}""")
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertTrue(result is LlmCompletionResult.Failure)
        result as LlmCompletionResult.Failure
        assertEquals(LlmErrorCode.AUTH, result.code)
        assertTrue(result.message.contains("401"))
        assertTrue(result.message.contains("invalid api key"))
        assertTrue(result.readableMessage().contains(LlmErrorCode.AUTH))
        assertFalse("错误信息不得包含 API Key", result.message.contains(validConfig.apiKey))
    }

    @Test
    fun `completion 403 maps auth error`() {
        val result = runBlocking {
            providerWith(FakeHttpConnection(403, "")).complete(validConfig, "s", "u")
        }
        assertEquals(LlmErrorCode.AUTH, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion 429 maps rate limited`() {
        val result = runBlocking {
            providerWith(FakeHttpConnection(429, """{"error":{"message":"too many requests"}}"""))
                .complete(validConfig, "s", "u")
        }
        assertEquals(LlmErrorCode.RATE_LIMITED, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion 500 maps http error`() {
        val result = runBlocking {
            providerWith(FakeHttpConnection(500, """{"error":{"message":"upstream exploded"}}"""))
                .complete(validConfig, "s", "u")
        }
        val failure = result as LlmCompletionResult.Failure
        assertEquals(LlmErrorCode.HTTP, failure.code)
        assertTrue(failure.message.contains("500"))
        assertTrue(failure.message.contains("upstream exploded"))
    }

    // ---- 传输层异常 ----

    @Test
    fun `completion timeout maps timeout with readable message`() {
        val fake = FakeHttpConnection(200, "", responseCodeFailure = SocketTimeoutException("read timed out"))
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        val failure = result as LlmCompletionResult.Failure
        assertEquals(LlmErrorCode.TIMEOUT, failure.code)
        assertTrue(failure.message.contains("超时"))
    }

    @Test
    fun `completion io failure maps network error`() {
        val fake = FakeHttpConnection(200, "", responseCodeFailure = IOException("connection reset"))
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertEquals(LlmErrorCode.NETWORK, (result as LlmCompletionResult.Failure).code)
    }

    // ---- 响应内容异常 → PARSE ----

    @Test
    fun `completion bad json maps parse error`() {
        val result = runBlocking {
            providerWith(FakeHttpConnection(200, "not-json")).complete(validConfig, "s", "u")
        }
        assertEquals(LlmErrorCode.PARSE, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion empty choices maps parse error`() {
        val result = runBlocking {
            providerWith(FakeHttpConnection(200, """{"choices":[]}""")).complete(validConfig, "s", "u")
        }
        assertEquals(LlmErrorCode.PARSE, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion missing content maps parse error`() {
        val fake = FakeHttpConnection(200, """{"choices":[{"message":{"role":"assistant"}}]}""")
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertEquals(LlmErrorCode.PARSE, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion non string content maps parse error`() {
        val fake = FakeHttpConnection(200, """{"choices":[{"message":{"content":123}}]}""")
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        assertEquals(LlmErrorCode.PARSE, (result as LlmCompletionResult.Failure).code)
    }

    @Test
    fun `completion blank content maps parse error`() {
        val fake = FakeHttpConnection(200, """{"choices":[{"message":{"content":"   "}}]}""")
        val result = runBlocking { providerWith(fake).complete(validConfig, "s", "u") }
        val failure = result as LlmCompletionResult.Failure
        assertEquals(LlmErrorCode.PARSE, failure.code)
        assertTrue(failure.message.contains("为空"))
    }

    // ---- 预检：禁网零请求 / HTTPS-only / 配置 ----

    @Test
    fun `completion blocked policy fails offline without opening connection`() {
        var openCalls = 0
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysBlocked(),
            opener = LlmHttpConnectionOpener {
                openCalls++
                FakeHttpConnection(200, successBody("x"))
            },
        )
        val result = runBlocking { provider.complete(validConfig, "s", "u") }
        assertEquals(LlmErrorCode.OFFLINE, (result as LlmCompletionResult.Failure).code)
        assertEquals("禁网时不得打开任何连接", 0, openCalls)
    }

    @Test
    fun `completion insecure http url rejected before opening connection`() {
        var openCalls = 0
        val provider = OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            opener = LlmHttpConnectionOpener {
                openCalls++
                FakeHttpConnection(200, successBody("x"))
            },
        )
        val result = runBlocking {
            provider.complete(validConfig.copy(baseUrl = "http://api.example.com/v1"), "s", "u")
        }
        assertEquals(LlmErrorCode.INSECURE_URL, (result as LlmCompletionResult.Failure).code)
        assertEquals(0, openCalls)
    }

    @Test
    fun `completion invalid config fails before any request`() {
        val fake = FakeHttpConnection(200, successBody("x"))
        val provider = providerWith(fake)

        assertEquals(
            LlmErrorCode.NOT_CONFIGURED,
            (runBlocking { provider.complete(validConfig.copy(apiKey = ""), "s", "u") }
                as LlmCompletionResult.Failure).code,
        )
        assertEquals(
            LlmErrorCode.INVALID_CONFIG,
            (runBlocking { provider.complete(validConfig, "", "u") }
                as LlmCompletionResult.Failure).code,
        )
        assertEquals(
            LlmErrorCode.INVALID_CONFIG,
            (runBlocking { provider.complete(validConfig, "s", "u", maxTokens = 0) }
                as LlmCompletionResult.Failure).code,
        )
        assertEquals(0, fake.connectCount.get())
    }

    // ---- 与 B8 testConnection 共存 ----

    @Test
    fun `provider implements both connectivity and completion contracts`() {
        val provider = providerWith(FakeHttpConnection(200, """{"model":"m","choices":[{"message":{}}]}"""))
        assertTrue(provider is LlmProvider)
        assertTrue(provider is LlmCompletionProvider)
        val result = runBlocking { provider.testConnection(validConfig) }
        assertTrue(result is LlmTestResult.Success)
        assertEquals("m", (result as LlmTestResult.Success).model)
    }

    // ---- helpers ----

    private fun providerWith(fake: FakeHttpConnection): OpenAiCompatibleLlmProvider =
        OpenAiCompatibleLlmProvider.createForTesting(
            networkPolicy = NetworkPolicy.alwaysAllowed(),
            clockMillis = { FIXED_CLOCK_MS },
            opener = LlmHttpConnectionOpener { fake },
        )

    private fun successBody(content: String): String =
        """{"model":"test-model","choices":[{"message":{"role":"assistant","content":${jsonString(content)}}}]}"""

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * 最小 fake `HttpURLConnection`：只实现 Provider 用到的读写方法；
     * 不连接网络、不依赖 TLS（真实 TLS 行为由 data-reviewer 的 mock server 测试覆盖）。
     */
    private class FakeHttpConnection(
        private val status: Int,
        private val body: String,
        private val responseCodeFailure: Throwable? = null,
    ) : HttpURLConnection(URL("https://llm.invalid/v1/chat/completions")) {
        val requestBody = ByteArrayOutputStream()
        val requestHeaders = LinkedHashMap<String, String>()
        val connectCount = java.util.concurrent.atomic.AtomicInteger(0)
        val disconnectCount = java.util.concurrent.atomic.AtomicInteger(0)
        var capturedMethod: String = ""

        override fun connect() {
            connectCount.incrementAndGet()
        }

        override fun disconnect() {
            disconnectCount.incrementAndGet()
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
            return body.byteInputStream()
        }
    }

    private companion object {
        const val FIXED_CLOCK_MS = 1_000L
        val HTTP_SUCCESS_CODES = 200..299
    }
}
