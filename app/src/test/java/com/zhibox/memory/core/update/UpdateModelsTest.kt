package com.zhibox.memory.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内更新纯逻辑测试：版本比较 / Release JSON 解析 / markdown 清洗 / 启动节流策略。
 * 全部 JVM（无 Robolectric、无网络）。
 */
class UpdateModelsTest {

    // ── 版本 ─────────────────────────────────────────────────────

    @Test
    fun parseVersion_toleratesPrefixSuffixAndMissingSegments() {
        assertEquals(listOf(0, 1, 6), parseAppVersion("v0.1.6")!!.parts)
        assertEquals(listOf(0, 1, 6), parseAppVersion("0.1.6-rc1")!!.parts)
        assertEquals(listOf("rc1"), parseAppVersion("0.1.6-rc1")!!.prerelease)
        assertEquals(listOf(0, 1), parseAppVersion("v0.1")!!.parts)
        assertEquals(listOf(1, 0, 0), parseAppVersion("1.0.0+build7")!!.parts)
        assertNull(parseAppVersion(""))
        assertNull(parseAppVersion("v"))
        assertNull(parseAppVersion("release"))
    }

    @Test
    fun semverPrereleaseOrdering() {
        assertTrue(isNewerVersion("0.1.7", "0.1.7-rc.1"))
        assertTrue(isNewerVersion("0.1.7-rc.1", "0.1.7-beta.2"))
        assertTrue(isNewerVersion("0.1.7-beta.2", "0.1.7-beta.1"))
        assertTrue(isNewerVersion("0.1.8-beta.1", "0.1.7"))
        assertFalse(isNewerVersion("0.1.7-beta.2", "0.1.7"))
    }

    @Test
    fun compareVersions_normalizesSegments() {
        assertTrue(isNewerVersion("0.1.6", "0.1.5"))
        assertTrue(isNewerVersion("v0.2", "0.1.9"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
        assertFalse(isNewerVersion("0.1.5", "0.1.5"))
        assertFalse(isNewerVersion("0.1.5", "0.1.6"))
        assertFalse(isNewerVersion("garbage", "0.1.5"))
    }

    // ── Release JSON ─────────────────────────────────────────────

    private val releaseJson = """
        {
          "tag_name": "v0.1.6",
          "name": "ZhiBox v0.1.6",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-27T02:00:00Z",
          "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.6",
          "body": "## 0. v0.1.6\n\n- **设置页** 简化\n- 下载地址：[Release](https://example.com)\n",
          "assets": [
            {"name": "zhibox-0.1.6-abc1234-mapping.txt", "size": 100,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-mapping.txt",
             "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"name": "zhibox-0.1.6-abc1234-release-signed.apk", "size": 70345697,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-release-signed.apk",
             "digest": "sha256:08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06"},
            {"name": "zhibox-0.1.6-abc1234-release-signed.apk.sha256", "size": 106,
             "browser_download_url": "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.6/zhibox-0.1.6-abc1234-release-signed.apk.sha256",
             "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
          ]
        }
    """.trimIndent()

    @Test
    fun parseLatestRelease_picksSignedApkAndApiDigest() {
        val info = UpdateReleaseParser.parseLatestRelease(releaseJson)
        assertEquals("0.1.6", info.versionName)
        assertEquals("v0.1.6", info.tagName)
        assertEquals(70_345_697L, info.apkSizeBytes)
        assertEquals(
            "08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06",
            info.sha256,
        )
        assertTrue(info.apkUrl.endsWith("-release-signed.apk"))
        assertTrue(info.sha256Url!!.endsWith(".sha256"))
        // markdown 清洗：去掉 ##、**、链接语法
        assertFalse(info.notes.contains("##"))
        assertFalse(info.notes.contains("**"))
        assertFalse(info.notes.contains("]("))
        assertTrue(info.notes.contains("设置页"))
    }

    @Test
    fun parseReleaseList_picksHighestSemverIncludingPrerelease() {
        val beta = releaseJson
            .replace("\"tag_name\": \"v0.1.6\"", "\"tag_name\": \"v0.1.7-beta.1\"")
            .replace("\"prerelease\": false", "\"prerelease\": true")
        val body = "[$releaseJson, $beta]"
        val info = UpdateReleaseParser.parseReleaseList(body)
        assertEquals("0.1.7-beta.1", info.versionName)
        assertEquals(UpdateChannel.BETA, info.channel)
    }

    @Test
    fun parseReleaseList_stableListReturnsStableChannel() {
        val info = UpdateReleaseParser.parseReleaseList("[$releaseJson]")
        assertEquals("0.1.6", info.versionName)
        assertEquals(UpdateChannel.STABLE, info.channel)
    }

    @Test
    fun parseLatestRelease_fallsBackToShaAssetWhenDigestMissing() {
        val json = releaseJson
            .replace(
                "\"digest\": \"sha256:08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06\"",
                "\"digest\": null",
            )
        val info = UpdateReleaseParser.parseLatestRelease(json)
        assertNull(info.sha256)
        assertTrue(info.sha256Url!!.endsWith(".sha256"))
    }

    @Test(expected = UpdateException::class)
    fun parseLatestRelease_rejectsPrerelease() {
        UpdateReleaseParser.parseLatestRelease(releaseJson.replace("\"prerelease\": false", "\"prerelease\": true"))
    }

    @Test(expected = UpdateException::class)
    fun parseLatestRelease_rejectsMissingApkAsset() {
        UpdateReleaseParser.parseLatestRelease(
            releaseJson.replace(
                "zhibox-0.1.6-abc1234-release-signed.apk\"",
                "zhibox-0.1.6-abc1234-release-signed.apk.tmp\"",
            ),
        )
    }

    @Test
    fun parseSha256File_acceptsHashAndFilenameFormat() {
        val hex = "c".repeat(64)
        assertEquals(hex, UpdateReleaseParser.parseSha256File("$hex  zhibox.apk\n"))
        assertEquals(hex, UpdateReleaseParser.parseSha256File(hex))
        assertNull(UpdateReleaseParser.parseSha256File("not-a-hash"))
    }

    // ── 节流策略 ─────────────────────────────────────────────────

    private class FakeStore : UpdateStore {
        override var lastSuccessAtMillis: Long = 0L
        override var lastAttemptAtMillis: Long = 0L
        override var dismissedVersion: String? = null
        override var autoCheckEnabled: Boolean = true
        override var channel: UpdateChannel = UpdateChannel.STABLE
        override var installId: String = "test-install-id"
        var cached: UpdateInfo? = null
        override fun cachedInfo(): UpdateInfo? = cached
        override fun saveCached(info: UpdateInfo) {
            cached = info
        }

        override fun clearCache() {
            cached = null
        }

        private var routes: Map<String, RouteHealth> = emptyMap()
        override fun routeHealth(): Map<String, RouteHealth> = routes
        override fun updateRouteHealth(health: Map<String, RouteHealth>) {
            routes = health
        }

        override var downloadedVersion: String? = null
        override var downloadedApkPath: String? = null
    }

    private fun info(version: String) = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = "t",
        notes = "n",
        publishedAt = null,
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$version/x-release-signed.apk",
        apkSizeBytes = 1,
        sha256 = "d".repeat(64),
        sha256Url = null,
    )

    @Test
    fun launchPolicy_skipsWhenAutoCheckDisabled() {
        val store = FakeStore().apply {
            autoCheckEnabled = false
            lastSuccessAtMillis = 0L
        }
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(1_700_000_000_000L, store, "0.1.6"),
        )
    }

    @Test
    fun launchPolicy_skipsWithinSuccessInterval() {
        val store = FakeStore().apply { lastSuccessAtMillis = 100_000L }
        val action = UpdatePolicy.decideOnLaunch(
            nowMillis = 100_000L + UpdateConfig.AUTO_CHECK_INTERVAL_MS - 1,
            store = store,
            currentVersionName = "0.1.6",
        )
        assertEquals(UpdatePolicy.LaunchAction.Skip, action)
    }

    @Test
    fun launchPolicy_checksAfterInterval() {
        val store = FakeStore().apply { lastSuccessAtMillis = 100_000L }
        val action = UpdatePolicy.decideOnLaunch(
            nowMillis = 100_000L + UpdateConfig.AUTO_CHECK_INTERVAL_MS + 1,
            store = store,
            currentVersionName = "0.1.6",
        )
        assertEquals(UpdatePolicy.LaunchAction.Check, action)
    }

    @Test
    fun launchPolicy_skipsDuringFailureBackoff() {
        val store = FakeStore().apply { lastAttemptAtMillis = 100_000L }
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(
                100_000L + UpdateConfig.AUTO_RETRY_INTERVAL_MS - 1,
                store,
                "0.1.6",
            ),
        )
    }

    @Test
    fun launchPolicy_showsNewerCachedVersion() {
        val store = FakeStore().apply {
            lastSuccessAtMillis = 500_000L
            cached = info("0.1.7")
        }
        val action = UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6")
        assertTrue(action is UpdatePolicy.LaunchAction.ShowCached)
    }

    @Test
    fun launchPolicy_doesNotShowDismissedCachedVersion() {
        val store = FakeStore().apply {
            lastSuccessAtMillis = 500_000L
            cached = info("0.1.7")
            dismissedVersion = "0.1.7"
        }
        assertEquals(
            UpdatePolicy.LaunchAction.Skip,
            UpdatePolicy.decideOnLaunch(500_000L, store, "0.1.6"),
        )
    }
}
