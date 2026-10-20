package com.zhibox.memory.core.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P1 真实网络 smoke：WebPageFetcher 在当前设备网络下可抓取公网 HTTPS 页面。 */
@RunWith(AndroidJUnit4::class)
class WebPageFetcherInstrumentedTest {
    @Test
    fun fetchExampleDotCom_succeedsWithTitleAndText() = runBlocking {
        val result = WebPageFetcher.fetch(
            url = "https://example.com",
            networkAllowed = true,
            timeoutMillis = 15_000,
        )
        assertTrue("真实网页抓取应成功：$result", result is WebPageResult.Success)
        val page = (result as WebPageResult.Success).page
        assertTrue("标题/正文非空", page.title.isNotBlank() && page.text.isNotBlank())
    }
}
