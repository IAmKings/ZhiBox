package com.zhibox.memory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载器供应链安全策略单测（P1-10）。
 *
 * 只测纯函数（URL 白名单 / Content-Range 解析），不发网络请求、不依赖 Android 框架——
 * 网络桩会引入 Robolectric 或本地 HTTP server，超出本轮单测范围（真实下载链由真机验收覆盖）。
 */
class ModelDownloaderPolicyTest {

    @Test
    fun `白名单内的 https 地址通过`() {
        ModelDownloader.validateUrl(
            "https://hf-mirror.com/litert-community/embeddinggemma-2-text-270m-" +
                "litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"
        )
    }

    @Test
    fun `http 明文地址被拒绝`() {
        val e = expectThrows<ModelDownloader.DownloadException> {
            ModelDownloader.validateUrl("http://hf-mirror.com/a.litertlm")
        }
        assertTrue(e.message!!, e.message!!.contains("https"))
    }

    @Test
    fun `非白名单域名被拒绝（含后缀欺骗）`() {
        listOf(
            "https://huggingface.co/a.litertlm",
            "https://evil-hf-mirror.com/a.litertlm",
            "https://hf-mirror.com.evil.cn/a.litertlm",
            "https://127.0.0.1/a.litertlm",
        ).forEach { url ->
            val e = expectThrows<ModelDownloader.DownloadException> {
                ModelDownloader.validateUrl(url)
            }
            assertTrue("$url -> ${e.message}", e.message!!.contains("白名单"))
        }
    }

    @Test
    fun `非法 URL 被拒绝`() {
        val e = expectThrows<ModelDownloader.DownloadException> {
            ModelDownloader.validateUrl("not a url")
        }
        assertTrue(e.message!!, e.message!!.contains("非法"))
    }

    @Test
    fun `解析 Content-Range 起点`() {
        assertEquals(100L, ModelDownloader.contentRangeStart("bytes 100-199/200"))
        assertEquals(0L, ModelDownloader.contentRangeStart("bytes 0-99/*"))
        assertEquals(123L, ModelDownloader.contentRangeStart("  BYTES 123-456/789  "))
    }

    @Test
    fun `Content-Range 缺失或格式错误视为不可续传`() {
        assertNull(ModelDownloader.contentRangeStart(null))
        assertNull(ModelDownloader.contentRangeStart(""))
        assertNull(ModelDownloader.contentRangeStart("bytes */200")) // 416 响应
        assertNull(ModelDownloader.contentRangeStart("100-199/200"))
        assertNull(ModelDownloader.contentRangeStart("bytes abc-199/200"))
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("期望 ${T::class.simpleName}，实际 ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("期望抛出 ${T::class.simpleName}，但没有异常")
    }
}
