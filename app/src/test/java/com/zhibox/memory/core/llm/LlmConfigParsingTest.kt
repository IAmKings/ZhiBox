package com.zhibox.memory.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 LLM 纯逻辑单测（JVM，无 Android/Robolectric/网络）：
 * 配置校验、HTTPS URL 拼接、最小请求 JSON、OpenAI-compatible 响应解析、错误映射、key 脱敏。
 *
 * 说明：Keystore/偏好开关等 Android 相关行为由 data-reviewer 的 Robolectric/instrumentation
 * 测试覆盖（task-40）；本文件只锁定可在 JVM 直接验证的核心契约。
 */
class LlmConfigParsingTest {

    private val validConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
    )

    // ---- 配置校验（不发请求） ----

    @Test
    fun `valid config passes validation`() {
        assertNull(validateConfig(validConfig))
    }

    @Test
    fun `blank baseUrl and model are invalid config`() {
        val blankUrl = validateConfig(validConfig.copy(baseUrl = "  "))
        assertEquals(LlmErrorCode.INVALID_CONFIG, blankUrl?.code)

        val blankModel = validateConfig(validConfig.copy(model = ""))
        assertEquals(LlmErrorCode.INVALID_CONFIG, blankModel?.code)
    }

    @Test
    fun `blank api key is not configured`() {
        val failure = validateConfig(validConfig.copy(apiKey = ""))
        assertEquals(LlmErrorCode.NOT_CONFIGURED, failure?.code)
    }

    @Test
    fun `timeout and maxTokens out of range are invalid`() {
        assertEquals(
            LlmErrorCode.INVALID_CONFIG,
            validateConfig(validConfig.copy(timeoutSeconds = 0))?.code,
        )
        assertEquals(
            LlmErrorCode.INVALID_CONFIG,
            validateConfig(validConfig.copy(timeoutSeconds = 121))?.code,
        )
        assertEquals(
            LlmErrorCode.INVALID_CONFIG,
            validateConfig(validConfig.copy(maxTokens = 0))?.code,
        )
    }

    // ---- HTTPS-only URL 拼接（不发起请求） ----

    @Test
    fun `endpoint appends chat completions path and trims trailing slash`() {
        val url = endpointOf("https://api.example.com/v1/")
        assertNotNull(url)
        assertEquals("https://api.example.com/v1/chat/completions", url.toString())
    }

    @Test
    fun `endpoint rejects plain http and blank`() {
        assertNull(endpointOf("http://api.example.com/v1"))
        assertNull(endpointOf(""))
        assertNull(endpointOf("api.example.com/v1"))
    }

    @Test
    fun `endpoint rejects credentials embedded in url`() {
        assertNull(endpointOf("https://user:pass@api.example.com/v1"))
    }

    // ---- 最小请求 JSON ----

    @Test
    fun `request json is minimal and does not include api key`() {
        val json = buildTestRequestJson(validConfig).toString()
        assertTrue(json.contains("\"model\":\"test-model\""))
        assertTrue(json.contains("\"content\":\"ping\""))
        assertTrue(json.contains("\"max_tokens\":1"))
        assertTrue(json.contains("\"stream\":false"))
        assertFalse(json.contains(validConfig.apiKey))
    }

    @Test
    fun `request json escapes special characters in model name`() {
        val json = buildTestRequestJson(validConfig.copy(model = "m\"1\n2")).toString()
        assertFalse(json.contains("\n"))
        assertTrue(json.contains("m\\\"1\\n2"))
    }

    // ---- OpenAI-compatible 响应解析 ----

    @Test
    fun `parses response model and tolerates unknown fields`() {
        val body = """
            {"id":"x","model":"gpt-4o-mini","choices":[
              {"index":0,"message":{"role":"assistant","content":"pong"},"mystery":true}
            ],"usage":{"total_tokens":2}}
        """.trimIndent()
        assertEquals("gpt-4o-mini", parseTestResponseModel(body))
    }

    @Test
    fun `response without choices or message fails parse`() {
        assertNull(parseTestResponseModel("""{"model":"m"}"""))
        assertNull(parseTestResponseModel("""{"choices":[]}"""))
        assertNull(parseTestResponseModel("""{"choices":[{"index":0}]}"""))
        assertNull(parseTestResponseModel("not-json"))
    }

    @Test
    fun `response with empty model still parses`() {
        val body = """{"choices":[{"message":{"content":""}}]}"""
        assertEquals("", parseTestResponseModel(body))
    }

    // ---- 错误映射 ----

    @Test
    fun `http status maps to stable error codes and readable message`() {
        assertEquals(LlmErrorCode.AUTH, httpFailure(401, null).code)
        assertEquals(LlmErrorCode.AUTH, httpFailure(403, null).code)
        assertEquals(LlmErrorCode.RATE_LIMITED, httpFailure(429, null).code)
        assertEquals(LlmErrorCode.HTTP, httpFailure(500, null).code)
        assertEquals(LlmErrorCode.HTTP, httpFailure(404, null).code)
        assertTrue(httpFailure(401, null).readableMessage().contains("LLM_AUTH_ERROR"))
    }

    @Test
    fun `error detail extracts message and collapses newlines`() {
        val body = """{"error":{"message":"bad  key\nplease retry","type":"invalid_request"}}"""
        val detail = parseErrorDetail(body)
        assertEquals("bad key please retry", detail)
        assertNull(parseErrorDetail("not-json"))
        assertNull(parseErrorDetail("""{"other":"x"}"""))
    }

    @Test
    fun `error detail is truncated to 200 chars`() {
        val long = "x".repeat(500)
        val body = """{"error":{"message":"$long"}}"""
        val detail = parseErrorDetail(body)
        assertNotNull(detail)
        assertEquals(MAX_ERROR_DETAIL_CHARS, detail!!.length)
    }

    // ---- key 脱敏 / toString 安全 ----

    @Test
    fun `maskSecret keeps prefix and suffix only for long keys`() {
        assertEquals("sk-***abcd", LlmConfig.maskSecret("sk-test-1234567890abcd"))
        assertEquals("****", LlmConfig.maskSecret("short"))
        assertEquals("", LlmConfig.maskSecret(""))
    }

    @Test
    fun `toString never exposes raw api key`() {
        val text = validConfig.toString()
        assertFalse(text.contains(validConfig.apiKey))
        assertTrue(text.contains("***"))
    }
}
