package com.zhibox.memory.core.update

import com.zhibox.memory.core.llm.MockHttpsServer
import com.zhibox.memory.core.llm.MockResponse
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateApi / UpdateDownloader 的本地 Mock HTTPS 端到端测试：
 * 覆盖 200 解析、404、403 限流、SHA 校验、`.sha256` 回退、域名白名单。
 * 全部走 127.0.0.1 自签 TLS（复用 TestTlsSupport），不访问外网。
 */
class UpdateApiAndDownloaderTest {

    private val releaseJson = """
        {
          "tag_name": "v0.1.7",
          "name": "ZhiBox v0.1.7",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-27T00:00:00Z",
          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7",
          "body": "## 0. v0.1.7\n- 更新",
          "assets": [
            {"name": "zhibox-0.1.7-abc1234-release-signed.apk", "size": 9,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/zhibox-0.1.7-abc1234-release-signed.apk",
             "digest": "sha256:${"a".repeat(64)}"}
          ]
        }
    """.trimIndent()

    private val payload = "test-apk!".toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun testInfo(
        apkUrl: String,
        size: Long = payload.size.toLong(),
        sha: String? = sha256(payload),
        shaUrl: String? = null,
    ) = UpdateInfo(
        versionName = "0.1.7",
        tagName = "v0.1.7",
        title = "ZhiBox v0.1.7",
        notes = "更新内容",
        publishedAt = "2026-10-27T00:00:00Z",
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
        apkUrl = apkUrl,
        apkSizeBytes = size,
        sha256 = sha,
        sha256Url = shaUrl,
    )

    // ── API ─────────────────────────────────────────────────────

    @Test
    fun api_parsesLatestReleaseResponse() = runBlocking {
        MockHttpsServer { MockResponse(200, releaseJson) }.use { server ->
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
            )
            val result = api.fetchLatest()
            assertTrue("应成功：$result", result.isSuccess)
            val info = result.getOrThrow()
            assertEquals("0.1.7", info.versionName)
            assertEquals(9L, info.apkSizeBytes)
            assertTrue("markdown 清洗后应保留正文：${info.notes}", info.notes.contains("更新"))
            assertEquals(1, server.requests.size)
            assertEquals("GET", server.requests.first().method)
        }
    }

    @Test
    fun api_appliesUpdateMetaFromReleaseAsset() = runBlocking {
        var serverRef: MockHttpsServer? = null
        val server = MockHttpsServer { request ->
            when {
                request.path.startsWith("/repos/") -> {
                    val metaUrl = serverRef!!.baseUrl("/meta/update.json")
                    val json = """
                        {
                          "tag_name": "v0.1.9",
                          "name": "ZhiBox v0.1.9",
                          "draft": false,
                          "prerelease": false,
                          "published_at": "2026-10-28T00:00:00Z",
                          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.9",
                          "body": "- 更新",
                          "assets": [
                            {"name": "zhibox-0.1.9-abc1234-release-signed.apk", "size": 9,
                             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.9/zhibox-0.1.9-abc1234-release-signed.apk",
                             "digest": "sha256:${"a".repeat(64)}"},
                            {"name": "zhibox-0.1.9-abc1234-update.json", "size": 100,
                             "browser_download_url": "$metaUrl"}
                          ]
                        }
                    """.trimIndent()
                    MockResponse(200, json)
                }

                request.path.startsWith("/meta/") -> MockResponse(
                    200,
                    """{"schema":1,"rolloutPercent":25,"minSupportedVersion":"0.1.5"}""",
                )

                else -> MockResponse(404, "{}")
            }
        }.also { serverRef = it }
        server.use {
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
                testMetaHosts = setOf("127.0.0.1"),
            )
            val info = api.fetchLatest().getOrThrow()
            assertEquals(25, info.rolloutPercent)
            assertEquals("0.1.5", info.minSupportedVersion)
        }
    }

    @Test
    fun api_fallsBackToMirrorWhenMetaDirectUnreachable() = runBlocking {
        var serverRef: MockHttpsServer? = null
        val server = MockHttpsServer { request ->
            when {
                request.path.startsWith("/repos/") -> {
                    val metaUrl = serverRef!!.baseUrl("/meta/direct/update.json")
                    val json = """
                        {
                          "tag_name": "v0.1.12", "name": "v0.1.12", "draft": false,
                          "prerelease": true, "body": "- 更新",
                          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.12",
                          "assets": [
                            {"name": "zhibox-0.1.12-abc-release-signed.apk", "size": 9,
                             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.12/zhibox-0.1.12-abc-release-signed.apk",
                             "digest": "sha256:${"a".repeat(64)}"},
                            {"name": "zhibox-0.1.12-abc-update.json", "size": 100,
                             "browser_download_url": "$metaUrl"}
                          ]
                        }
                    """.trimIndent()
                    MockResponse(200, "[$json]")
                }

                request.path.startsWith("/meta/direct/") -> MockResponse(502, "{}")
                request.path.startsWith("/mirror/") -> MockResponse(
                    200,
                    """{"schema":1,"rolloutPercent":100,"minSupportedVersion":"0.1.11"}""",
                )

                else -> MockResponse(404, "{}")
            }
        }.also { serverRef = it }
        server.use {
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
                testMetaHosts = setOf("127.0.0.1"),
                metaMirrors = listOf(server.baseUrl("/mirror/")),
            )
            val info = api.fetchLatest(UpdateChannel.BETA).getOrThrow()
            assertEquals("0.1.12", info.versionName)
            assertEquals("0.1.11", info.minSupportedVersion)
            assertTrue(server.requests.any { it.path.startsWith("/mirror/") })
        }
    }

    @Test
    fun api_metaRedirectIsFollowedWithHostValidation() = runBlocking {
        var serverRef: MockHttpsServer? = null
        val server = MockHttpsServer { request ->
            when {
                request.path.startsWith("/repos/") -> {
                    val metaUrl = serverRef!!.baseUrl("/meta/start")
                    val json = """
                        {
                          "tag_name": "v0.1.17", "name": "v0.1.17", "draft": false,
                          "prerelease": false, "body": "- 更新",
                          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.17",
                          "assets": [
                            {"name": "zhibox-0.1.17-abc-release-signed.apk", "size": 9,
                             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.17/zhibox-0.1.17-abc-release-signed.apk",
                             "digest": "sha256:${"a".repeat(64)}"},
                            {"name": "zhibox-0.1.17-abc-update.json", "size": 100,
                             "browser_download_url": "$metaUrl"}
                          ]
                        }
                    """.trimIndent()
                    MockResponse(200, json)
                }

                request.path.startsWith("/meta/start") -> MockResponse(
                    302,
                    "",
                    headers = mapOf("Location" to "/meta/final"),
                )

                request.path.startsWith("/meta/final") -> MockResponse(
                    200,
                    """{"schema":1,"rolloutPercent":10,"minSupportedVersion":"0.1.5"}""",
                )

                else -> MockResponse(404, "{}")
            }
        }.also { serverRef = it }
        server.use {
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
                testMetaHosts = setOf("127.0.0.1"),
            )
            val info = api.fetchLatest().getOrThrow()
            assertEquals(10, info.rolloutPercent)
            assertEquals("0.1.5", info.minSupportedVersion)
            assertTrue(server.requests.any { it.path == "/meta/final" })
        }
    }

    @Test
    fun api_404MapsToNoRelease() = runBlocking {
        MockHttpsServer { MockResponse(404, "{}") }.use { server ->
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
            )
            val error = api.fetchLatest().exceptionOrNull() as UpdateException
            assertEquals(UpdateFailure.NoRelease, error.failure)
        }
    }

    @Test
    fun api_403WithRateLimitHeaderMapsToRateLimited() = runBlocking {
        MockHttpsServer {
            MockResponse(
                403,
                "{}",
                headers = mapOf("X-RateLimit-Remaining" to "0"),
            )
        }.use { server ->
            val api = HttpUpdateApi(
                releasesApiBase = server.baseUrl("/repos/IAmKings/ZhiBox/releases"),
            )
            val error = api.fetchLatest().exceptionOrNull() as UpdateException
            val http = error.failure as UpdateFailure.Http
            assertTrue(http.rateLimited)
        }
    }

    // ── 下载 ─────────────────────────────────────────────────────

    private fun newDownloader() = HttpUpdateDownloader(
        testAllowedHosts = setOf("127.0.0.1"),
        freeBytes = { Long.MAX_VALUE },
    )

    @Test
    fun downloader_triesRemainingRouteWhenFirstIs404() = runBlocking {
        val dir = createTempDir("update-dl-route-ok")
        val payloadText = payload.toString(Charsets.UTF_8)
        try {
            MockHttpsServer { request ->
                if (request.path.startsWith("/https://github.com/")) {
                    MockResponse(200, payloadText)
                } else {
                    MockResponse(404, "not found")
                }
            }.use { server ->
                val downloader = HttpUpdateDownloader(
                    mirrorPrefixes = listOf("https://gh-proxy.test/"),
                    openConnection = { url ->
                        URL(server.baseUrl(url.path)).openConnection() as java.net.HttpURLConnection
                    },
                    freeBytes = { Long.MAX_VALUE },
                )
                val file = downloader.download(
                    testInfo(
                        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/x-release-signed.apk",
                    ),
                    dir,
                )
                assertArrayEquals(payload, file.readBytes())
                assertTrue(server.requests.any { it.path.startsWith("/https://github.com/") })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_allRoutes404MapsToReleaseUnavailable() = runBlocking {
        val dir = createTempDir("update-dl-all-gone")
        try {
            MockHttpsServer { MockResponse(404, "not found") }.use { server ->
                val downloader = HttpUpdateDownloader(
                    mirrorPrefixes = listOf("https://gh-proxy.test/"),
                    openConnection = { url ->
                        URL(server.baseUrl(url.path)).openConnection() as java.net.HttpURLConnection
                    },
                    freeBytes = { Long.MAX_VALUE },
                )
                val error = try {
                    downloader.download(
                        testInfo(
                            apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/x-release-signed.apk",
                        ),
                        dir,
                    )
                    null
                } catch (e: UpdateException) {
                    e
                }
                assertEquals(UpdateFailure.ReleaseUnavailable, error?.failure)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_404MapsToReleaseUnavailable() = runBlocking {
        val dir = createTempDir("update-dl-gone")
        try {
            MockHttpsServer { MockResponse(404, "not found") }.use { server ->
                val error = try {
                    newDownloader().download(
                        testInfo(apkUrl = server.baseUrl("/gone.apk")),
                        dir,
                    )
                    null
                } catch (e: UpdateException) {
                    e
                }
                assertEquals(UpdateFailure.ReleaseUnavailable, error?.failure)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_downloadsVerifiesAndRenames() = runBlocking {
        val dir = createTempDir("update-dl-ok")
        try {
            MockHttpsServer {
                MockResponse(200, String(payload, Charsets.ISO_8859_1), "application/octet-stream")
            }.use { server ->
                val info = testInfo(server.baseUrl("/repos/x/releases/download/v0.1.7/x.apk"))
                var progress = 0
                val file = newDownloader().download(info, dir) { _, _ -> progress++ }
                assertTrue(file.isFile)
                assertArrayEquals(payload, file.readBytes())
                assertTrue("应至少回调一次进度", progress >= 1)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_rejectsShaMismatchAndDeletesPart() = runBlocking {
        val dir = createTempDir("update-dl-bad-sha")
        try {
            MockHttpsServer {
                MockResponse(200, String(payload, Charsets.ISO_8859_1), "application/octet-stream")
            }.use { server ->
                val info = testInfo(
                    apkUrl = server.baseUrl("/repos/x/releases/download/v0.1.7/x.apk"),
                    sha = "b".repeat(64),
                )
                val error = runCatching { newDownloader().download(info, dir) }
                    .exceptionOrNull() as UpdateException
                assertEquals(UpdateFailure.ChecksumMismatch, error.failure)
                assertFalse(File(dir, "zhibox-0.1.7.apk.part").exists())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_refusesWithoutAnyChecksum() = runBlocking {
        val dir = createTempDir("update-dl-no-sha")
        try {
            val error = runCatching {
                newDownloader().download(
                    testInfo(
                        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/x.apk",
                        sha = null,
                        shaUrl = null,
                    ),
                    dir,
                )
            }.exceptionOrNull() as UpdateException
            assertEquals(UpdateFailure.NoChecksum, error.failure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloader_parsesContentRangeStart() {
        assertEquals(123L, HttpUpdateDownloader.contentRangeStart("bytes 123-456/789"))
        assertEquals(0L, HttpUpdateDownloader.contentRangeStart("bytes 0-10/*"))
        assertTrue(HttpUpdateDownloader.contentRangeStart("garbage") == null)
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
