package com.zhibox.memory.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 灰度分桶与元数据解析测试。 */
class UpdateRolloutTest {

    private fun info(
        version: String = "0.1.7",
        rollout: Int = 100,
        minSupported: String? = null,
    ) = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = version,
        notes = "",
        publishedAt = null,
        htmlUrl = "",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$version/x-release-signed.apk",
        apkSizeBytes = 1,
        sha256 = "a".repeat(64),
        sha256Url = null,
        rolloutPercent = rollout,
        minSupportedVersion = minSupported,
    )

    @Test
    fun cohortIsDeterministicAndBounded() {
        val a = UpdateRollout.cohort("install-1", "0.1.7")
        val b = UpdateRollout.cohort("install-1", "0.1.7")
        assertEquals(a, b)
        assertTrue(a in 0..99)
        assertTrue(UpdateRollout.cohort("install-2", "0.1.7") in 0..99)
    }

    @Test
    fun eligibilityHonorsRolloutPercent() {
        assertTrue(UpdateRollout.isEligible("id", info(rollout = 100)))
        assertFalse(UpdateRollout.isEligible("id", info(rollout = 0)))
    }

    @Test
    fun forcedWhenCurrentBelowMinSupported() {
        assertTrue(UpdateRollout.isForced(info(minSupported = "0.1.7"), "0.1.6"))
        assertFalse(UpdateRollout.isForced(info(minSupported = "0.1.6"), "0.1.6"))
        assertFalse(UpdateRollout.isForced(info(minSupported = null), "0.1.6"))
    }

    @Test
    fun parseUpdateMetaValidatesSchemaAndRange() {
        val meta = UpdateMetaParser.parse(
            """{"schema":1,"rolloutPercent":25,"minSupportedVersion":"0.1.5"}""",
        )
        assertEquals(25, meta.rolloutPercent)
        assertEquals("0.1.5", meta.minSupportedVersion)

        val applied = UpdateMetaParser.apply(info(), meta)
        assertEquals(25, applied.rolloutPercent)
        assertEquals("0.1.5", applied.minSupportedVersion)
    }

    @Test(expected = UpdateException::class)
    fun parseUpdateMetaRejectsInvalidRollout() {
        UpdateMetaParser.parse("""{"schema":1,"rolloutPercent":101}""")
    }

    @Test
    fun releaseParserReadsUpdateMetaAssetUrl() {
        val json = """
            {
              "tag_name": "v0.1.7", "name": "v0.1.7", "draft": false, "prerelease": false,
              "html_url": "https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7",
              "body": "notes",
              "assets": [
                {"name":"zhibox-0.1.7-abc-release-signed.apk","size":1,
                 "browser_download_url":"https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/zhibox-0.1.7-abc-release-signed.apk",
                 "digest":"sha256:${"a".repeat(64)}"},
                {"name":"zhibox-0.1.7-abc-update.json","size":100,
                 "browser_download_url":"https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/zhibox-0.1.7-abc-update.json"}
              ]
            }
        """.trimIndent()
        val info = UpdateReleaseParser.parseLatestRelease(json)
        assertEquals(
            "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/zhibox-0.1.7-abc-update.json",
            info.metaUrl,
        )
    }
}
