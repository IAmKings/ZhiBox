package com.zhibox.memory.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * B8 LLM Provider 端到端单测（JVM，无 Robolectric/无外网）：
 * 用本地 **TLS ServerSocket** mock OpenAI-compatible `/chat/completions`，覆盖
 * 200 成功 / 401 鉴权 / 429 限流 / 500 服务端错误 / 超时 / 坏 JSON，并断言：
 * - 请求是 POST 到 `<baseUrl>/chat/completions`、body 为最小 "ping" 且不含 API Key；
 * - 失败码为稳定 `LLM_*` 且可读、错误信息不含 key 明文；
 * - 本地 mock 记录到的请求数与预期一致（超时等场景均已真实发出请求）。
 *
 * 为什么用 TLS mock：生产 Provider 强制 HTTPS（防降级），plain HTTP mock 会被
 * `LLM_INSECURE_URL` 提前拒绝；这里内嵌一个**测试专用自签 PKCS12**（密码仅用于测试），
 * 在用例生命周期内替换 `HttpsURLConnection` 默认 SSLSocketFactory/HostnameVerifier，
 * 结束后恢复，避免影响其它 JVM 用例。
 */
class LlmProviderMockHttpsTest {

    private var originalSocketFactory: SSLSocketFactory? = null
    private var originalHostnameVerifier: HostnameVerifier? = null

    private val validConfig = LlmConfig(
        baseUrl = "https://127.0.0.1:1/v1", // 由各用例替换为 mock server 地址
        model = "gpt-4o-mini",
        apiKey = "sk-test-abcdefghijklmnop",
        timeoutSeconds = 5,
    )

    @Before
    fun saveHttpsDefaults() {
        originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    }

    @After
    fun restoreHttpsDefaults() {
        originalSocketFactory?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
        originalHostnameVerifier?.let { HttpsURLConnection.setDefaultHostnameVerifier(it) }
    }

    @Test
    fun successResponseReturnsModelAndLatency() {
        val body = """{"id":"x","model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"pong"}}]}"""
        MockHttpsServer { MockResponse(200, body) }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue("应成功：$result", result is LlmTestResult.Success)
            val success = result as LlmTestResult.Success
            assertEquals("gpt-4o-mini", success.model)
            assertTrue("latency 应为非负数", success.latencyMs >= 0)

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer " + validConfig.apiKey, request.headers["authorization"])
            assertTrue(request.body.contains("\"content\":\"ping\""))
            assertTrue(request.body.contains("\"max_tokens\":1"))
            assertFalse("请求 body 不得包含 API Key", request.body.contains(validConfig.apiKey))
        }
    }

    @Test
    fun auth401MapsAuthErrorAndDoesNotLeakKey() {
        val errorBody = """{"error":{"message":"invalid api key","type":"invalid_request_error"}}"""
        MockHttpsServer { MockResponse(401, errorBody) }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue(result is LlmTestResult.Failure)
            val failure = result as LlmTestResult.Failure
            assertEquals(LlmErrorCode.AUTH, failure.code)
            assertTrue(failure.readableMessage().contains(LlmErrorCode.AUTH))
            assertTrue("错误信息应含 HTTP 状态", failure.message.contains("401"))
            assertTrue("错误信息应含服务端 detail", failure.message.contains("invalid api key"))
            assertFalse("错误信息不得包含 API Key 明文", failure.message.contains(validConfig.apiKey))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun rateLimit429MapsRateLimited() {
        MockHttpsServer { MockResponse(429, """{"error":{"message":"too many requests"}}""") }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue(result is LlmTestResult.Failure)
            val failure = result as LlmTestResult.Failure
            assertEquals(LlmErrorCode.RATE_LIMITED, failure.code)
            assertTrue(failure.readableMessage().contains("LLM_RATE_LIMITED"))
        }
    }

    @Test
    fun serverError500MapsHttpErrorWithDetail() {
        MockHttpsServer { MockResponse(500, """{"error":{"message":"upstream exploded"}}""") }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue(result is LlmTestResult.Failure)
            val failure = result as LlmTestResult.Failure
            assertEquals(LlmErrorCode.HTTP, failure.code)
            assertTrue(failure.message.contains("500"))
            assertTrue(failure.message.contains("upstream exploded"))
        }
    }

    @Test
    fun redirectIsNotFollowedAndMapsHttpError() {
        // 安全属性：仅 HTTPS 且不跟随重定向（防 https→http 降级）。
        // 这里把 Location 指向一个明文 ServerSocket 观察点：若跟随则它会收到连接。
        ServerSocket(0).use { downgradeTarget ->
            downgradeTarget.soTimeout = 300
            val location = "http://127.0.0.1:${downgradeTarget.localPort}/downgrade"
            MockHttpsServer {
                MockResponse(302, "", headers = mapOf("Location" to location))
            }.use { server ->
                val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
                val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

                assertTrue("3xx 应映射为 Failure：$result", result is LlmTestResult.Failure)
                val failure = result as LlmTestResult.Failure
                assertEquals(LlmErrorCode.HTTP, failure.code)
                assertTrue("错误信息应含 HTTP 302：${failure.message}", failure.message.contains("302"))
                assertFalse("错误信息不得包含 API Key 明文", failure.message.contains(validConfig.apiKey))
                assertEquals("不得跟随重定向（只发出 1 次请求）", 1, server.requests.size)

                val followed = try {
                    downgradeTarget.accept().use { true }
                } catch (e: SocketTimeoutException) {
                    false
                } catch (e: IOException) {
                    false
                }
                assertFalse("不得跟随 https→http 降级重定向（零连接）", followed)
            }
        }
    }

    @Test
    fun badJsonMapsParseError() {
        MockHttpsServer { MockResponse(200, "this-is-not-json") }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue(result is LlmTestResult.Failure)
            assertEquals(LlmErrorCode.PARSE, (result as LlmTestResult.Failure).code)
        }
    }

    @Test
    fun responseWithoutChoicesMapsParseError() {
        MockHttpsServer { MockResponse(200, """{"model":"gpt-4o-mini"}""") }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking { provider.testConnection(validConfig.copy(baseUrl = server.baseUrl())) }

            assertTrue(result is LlmTestResult.Failure)
            assertEquals(LlmErrorCode.PARSE, (result as LlmTestResult.Failure).code)
        }
    }

    @Test
    fun timeoutMapsTimeoutAndIsReadable() {
        MockHttpsServer {
            Thread.sleep(2_500) // 服务端延迟响应，超过客户端 1s 超时
            MockResponse(200, """{"model":"late","choices":[{"message":{"content":"x"}}]}""")
        }.use { server ->
            val provider = OpenAiCompatibleLlmProvider(NetworkPolicy.alwaysAllowed())
            val result = runBlocking {
                provider.testConnection(validConfig.copy(baseUrl = server.baseUrl(), timeoutSeconds = 1))
            }

            assertTrue("应超时：$result", result is LlmTestResult.Failure)
            val failure = result as LlmTestResult.Failure
            assertEquals(LlmErrorCode.TIMEOUT, failure.code)
            assertTrue(failure.message.contains("超时"))
        }
    }

    // ---- 本地 TLS mock 服务端（测试专用） ----

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class MockResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private class MockHttpsServer(
        private val responder: (RecordedRequest) -> MockResponse,
    ) : Closeable {
        /** 已收到请求（测试断言用，故意暴露给外部测试方法） */
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val serverContext: SSLContext = LlmProviderMockHttpsTest.buildTestSslContext()
        private val serverSocket: SSLServerSocket =
            (serverContext.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                as SSLServerSocket)
        @Volatile
        private var closed = false
        private val acceptThread: Thread = Thread({ acceptLoop() }, "llm-mock-https").apply {
            isDaemon = true
            start()
        }

        init {
            // 仅测试进程内：信任本用例自签证书（@After 恢复原值）
            HttpsURLConnection.setDefaultSSLSocketFactory(serverContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        }

        fun baseUrl(): String = "https://127.0.0.1:${serverSocket.localPort}/v1"

        private fun acceptLoop() {
            while (!closed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                socket.use { s ->
                    s.soTimeout = 5_000
                    try {
                        val request = readRequest(s.getInputStream())
                        requests += request
                        writeResponse(s.getOutputStream(), responder(request))
                    } catch (e: IOException) {
                        // 客户端已超时/断开时写响应会失败：忽略，用例只关心请求侧与错误码
                    }
                }
            }
        }

        private fun readRequest(input: InputStream): RecordedRequest {
            val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: throw IOException("empty request")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(bodyChars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val parts = requestLine.split(" ")
            return RecordedRequest(
                method = parts.getOrNull(0).orEmpty(),
                path = parts.getOrNull(1).orEmpty(),
                headers = headers,
                body = String(bodyChars, 0, read),
            )
        }

        private fun writeResponse(out: OutputStream, response: MockResponse) {
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val extraHeaders = response.headers.entries.joinToString("") { (name, value) ->
                "$name: $value\r\n"
            }
            val head = "HTTP/1.1 ${response.status} ${LlmProviderMockHttpsTest.statusText(response.status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                extraHeaders + "\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            closed = true
            runCatching { serverSocket.close() }
            acceptThread.join(3_000)
        }
    }

    private companion object {
        const val TEST_KEYSTORE_PASSWORD = "zhibox-mock"

        /** 测试专用自签 PKCS12（CN=127.0.0.1，仅本文件用例使用；运行时无外网依赖）。 */
        val TEST_KEYSTORE_B64: String = """
            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
        """.trimIndent()

        fun statusText(status: Int): String = when (status) {
            200 -> "OK"
            302 -> "Found"
            401 -> "Unauthorized"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Response"
        }

        fun buildTestSslContext(): SSLContext {
            val keyBytes = Base64.getDecoder().decode(TEST_KEYSTORE_B64.filterNot { it.isWhitespace() })
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(keyBytes.inputStream(), TEST_KEYSTORE_PASSWORD.toCharArray())
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, TEST_KEYSTORE_PASSWORD.toCharArray())
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustAll), null)
            }
        }
    }
}
