package com.zhibox.memory.core.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SharedPrefsUpdateStore 的真实序列化回归：
 * v0.1.15 之前缓存只保存旧字段，导致重启后 forced/rollout 丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateStorePersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    @Before
    fun resetPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun saveCachedRoundTripsRolloutForcedAndChannel() {
        val store = SharedPrefsUpdateStore(context)
        val info = UpdateInfo(
            versionName = "0.1.15-force.1",
            tagName = "v0.1.15-force.1",
            title = "t",
            notes = "n",
            publishedAt = null,
            htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
            apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.15-force.1/x-release-signed.apk",
            apkSizeBytes = 1,
            sha256 = "a".repeat(64),
            sha256Url = null,
            channel = UpdateChannel.BETA,
            rolloutPercent = 25,
            minSupportedVersion = "0.1.15",
        )
        store.saveCached(info)

        assertTrue(store.cachedMetaKnown)
        assertTrue("缓存时间应被记录", store.cachedAtMillis > 0L)
        val cached = store.cachedInfo()!!
        assertEquals("0.1.15-force.1", cached.versionName)
        assertEquals(25, cached.rolloutPercent)
        assertEquals("0.1.15", cached.minSupportedVersion)
        assertEquals(UpdateChannel.BETA, cached.channel)
        assertTrue(UpdateRollout.isForced(cached, "0.1.14"))
    }

    @Test
    fun legacyCacheWithoutMetaSchemaIsMarkedUnknown() {
        // 模拟旧版本写入的缓存（只有旧字段）。
        prefs.edit()
            .putString("cached_version", "0.1.7")
            .putString("cached_tag", "v0.1.7")
            .putString("cached_title", "t")
            .putString("cached_notes", "n")
            .putString("cached_html", "https://github.com/IAmKings/ZhiBox/releases")
            .putString("cached_apk_url", "https://github.com/IAmKings/ZhiBox/releases/download/v0.1.7/x-release-signed.apk")
            .putLong("cached_apk_size", 1L)
            .putString("cached_sha", "b".repeat(64))
            .commit()

        val store = SharedPrefsUpdateStore(context)
        assertFalse(store.cachedMetaKnown)
        val cached = store.cachedInfo()!!
        assertEquals(100, cached.rolloutPercent)
        assertEquals(null, cached.minSupportedVersion)
        assertFalse(UpdateRollout.isForced(cached, "0.1.6"))
    }
}
