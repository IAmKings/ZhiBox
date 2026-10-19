package com.zhibox.memory.core.text

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1 网页抓取（纯 JVM，fake HttpURLConnection，无网络）：
 * 禁网零请求、HTTPS-only、标题+正文、明文响应、2MB 上限、HTTP/超时错误、文件名清洗。
 */
class WebPageFetcherTest {

    @Test
    fun `blocked network returns offline without opening connection`() = runBlocking {
        var openCalls = 0
        val result = WebPageFetcher.fetchWithOpener(
            url = "https://example.com/a",
            networkAllowed = false,
            timeoutMillis = 1_000,
            opener = WebPageFetcher.WebPageConnectionOpener {
                openCalls++
                FakeConnection(200, "<html></html>")
            },
        )

        assertTrue(result is WebPageResult.Failure)
        assertEquals(WebPageErrorCodes.OFFLINE, (result as WebPageResult.Failure).code)
        assertEquals("禁网不得创建连接", 0, openCalls)
    }

    @Test
    fun `http url rejected without opening connection`() = runBlocking {
        var openCalls = 0
        val result = WebPageFetcher.fetchWithOpener(
            url = "http://example.com/a",
            networkAllowed = true,
            timeoutMillis = 1_000,
            opener = WebPageFetcher.WebPageConnectionOpener {
                openCalls++
                FakeConnection(200, "<html></html>")
            },
        )

        assertEquals(WebPageErrorCodes.INSECURE_URL, (result as WebPageResult.Failure).code)
        assertEquals(0, openCalls)
    }

    @Test
    fun `success extracts title and clean body text`() = runBlocking {
        val html = """
            <html><head><title>示例 页面</title><script>alert(1)</script></head>
            <body><h1>第一节</h1><p>正文 &amp; 内容</p></body></html>
        """.trimIndent()
        val result = WebPageFetcher.fetchWithOpener(
            url = "https://example.com/doc",
            networkAllowed = true,
            timeoutMillis = 1_000,
            opener = WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, html, contentType = "text/html; charset=utf-8")
            },
        )

        val page = (result as WebPageResult.Success).page
        assertEquals("示例 页面", page.title)
        assertTrue("应含正文：${page.text}", page.text.contains("第一节"))
        assertTrue("实体应解码：${page.text}", page.text.contains("正文 & 内容"))
        assertFalse("不得包含脚本", page.text.contains("alert(1)"))
        assertEquals("https://example.com/doc", page.finalUrl)
        assertTrue("文件名应为 .txt：${page.suggestedFileName}", page.suggestedFileName.endsWith(".txt"))
        assertFalse("文件名不得含路径分隔符", page.suggestedFileName.contains("/"))
    }

    @Test
    fun `plain text response kept as text with host fallback title`() = runBlocking {
        val result = WebPageFetcher.fetchWithOpener(
            url = "https://example.com/notes.txt",
            networkAllowed = true,
            timeoutMillis = 1_000,
            opener = WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, "第一行\n第二行", contentType = "text/plain; charset=utf-8")
            },
        )
        val page = (result as WebPageResult.Success).page
        assertEquals("example.com", page.title)
        assertTrue(page.text.contains("第一行"))
    }

    @Test
    fun `response larger than two megabytes fails too large`() = runBlocking {
        val huge = "x".repeat(WebPageFetcher.MAX_PAGE_BYTES + 1)
        val result = WebPageFetcher.fetchWithOpener(
            url = "https://example.com/huge",
            networkAllowed = true,
            timeoutMillis = 1_000,
            opener = WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, huge, contentType = "text/html", contentLength = huge.length.toLong())
            },
        )
        assertEquals(WebPageErrorCodes.TOO_LARGE, (result as WebPageResult.Failure).code)
    }

    @Test
    fun `http 500 and redirect are readable failures`() = runBlocking {
        val serverError = WebPageFetcher.fetchWithOpener(
            "https://example.com/boom", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener { FakeConnection(500, "boom") },
        ) as WebPageResult.Failure
        assertEquals(WebPageErrorCodes.HTTP, serverError.code)
        assertTrue(serverError.message.contains("500"))

        val redirect = WebPageFetcher.fetchWithOpener(
            "https://example.com/redirect", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener { FakeConnection(302, "") },
        ) as WebPageResult.Failure
        assertEquals(WebPageErrorCodes.HTTP, redirect.code)
        assertTrue(redirect.message.contains("重定向"))
    }

    @Test
    fun `timeout and io errors map to stable codes`() = runBlocking {
        val timeout = WebPageFetcher.fetchWithOpener(
            "https://example.com/slow", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, "", responseCodeFailure = SocketTimeoutException("timeout"))
            },
        ) as WebPageResult.Failure
        assertEquals(WebPageErrorCodes.TIMEOUT, timeout.code)

        val io = WebPageFetcher.fetchWithOpener(
            "https://example.com/broken", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, "", responseCodeFailure = IOException("reset"))
            },
        ) as WebPageResult.Failure
        assertEquals(WebPageErrorCodes.NETWORK, io.code)
    }

    @Test
    fun `private and local hosts are rejected without opening connection`() = runBlocking {
        val urls = listOf("https://127.0.0.1/x", "https://localhost/x", "https://192.168.1.1/x", "https://[::1]/x")
        urls.forEach { url ->
            var openCalls = 0
            val result = WebPageFetcher.fetchWithOpener(
                url, true, 1_000,
                WebPageFetcher.WebPageConnectionOpener {
                    openCalls++
                    FakeConnection(200, "<html></html>")
                },
            )
            assertEquals("$url 应拒绝", WebPageErrorCodes.INVALID_URL, (result as WebPageResult.Failure).code)
            assertEquals("$url 不得连接", 0, openCalls)
        }
    }

    @Test
    fun `binary content type is rejected before body import`() = runBlocking {
        val result = WebPageFetcher.fetchWithOpener(
            "https://example.com/image.png", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener {
                FakeConnection(200, "not html", contentType = "image/png")
            },
        )
        assertEquals(
            WebPageErrorCodes.UNSUPPORTED_CONTENT_TYPE,
            (result as WebPageResult.Failure).code,
        )
    }

    @Test
    fun `empty body fails readable`() = runBlocking {
        val result = WebPageFetcher.fetchWithOpener(
            "https://example.com/empty", true, 1_000,
            WebPageFetcher.WebPageConnectionOpener { FakeConnection(200, "", contentType = "text/html") },
        )
        assertEquals(WebPageErrorCodes.EMPTY, (result as WebPageResult.Failure).code)
    }

    @Test
    fun `suggested file name sanitizes separators and control chars`() {
        val name = WebPageFetcher.suggestedFileName("a/b:c*?\"<>|", "example.com")
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
        assertFalse(name.contains("*"))
        assertTrue(name.endsWith(".txt"))
        assertEquals("webpage.txt", WebPageFetcher.suggestedFileName("   ", ""))
    }

    private class FakeConnection(
        private val status: Int,
        private val body: String,
        private val contentType: String? = "text/html; charset=utf-8",
        private val contentLength: Long = -1L,
        private val responseCodeFailure: Throwable? = null,
    ) : HttpURLConnection(URL("https://example.com/")) {
        private val disconnectCount = AtomicInteger(0)
        val headers = LinkedHashMap<String, String>()

        override fun connect() = Unit
        override fun disconnect() {
            disconnectCount.incrementAndGet()
        }

        override fun usingProxy(): Boolean = false
        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null) headers[key] = value.orEmpty()
        }

        override fun getResponseCode(): Int {
            responseCodeFailure?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream {
            if (status !in 200..299) throw IOException("non-2xx")
            return body.toByteArray(Charsets.UTF_8).inputStream()
        }

        override fun getErrorStream(): InputStream? = null
        override fun getContentType(): String? = contentType
        override fun getContentLengthLong(): Long = contentLength
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    }
}
