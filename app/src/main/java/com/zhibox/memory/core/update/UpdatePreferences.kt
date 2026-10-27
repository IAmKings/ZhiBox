package com.zhibox.memory.core.update

import android.content.Context

/**
 * 更新检查的持久化状态：节流时间戳、缓存的最新版本、已取消（dismissed）版本。
 *
 * 独立 SharedPreferences（`app_update`），不参与知识库备份（allowBackup=false 已全局关闭）。
 * 接口化便于 manager 单测注入内存实现。
 */
interface UpdateStore {
    var lastSuccessAtMillis: Long
    var lastAttemptAtMillis: Long
    var dismissedVersion: String?

    fun cachedInfo(): UpdateInfo?
    fun saveCached(info: UpdateInfo)
    fun clearCache()
}

class SharedPrefsUpdateStore(context: Context) : UpdateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastSuccessAtMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()
        }

    override var lastAttemptAtMillis: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_ATTEMPT, value).apply()
        }

    override var dismissedVersion: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) {
            prefs.edit().putString(KEY_DISMISSED, value).apply()
        }

    override fun cachedInfo(): UpdateInfo? {
        val version = prefs.getString(KEY_C_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        return UpdateInfo(
            versionName = version,
            tagName = prefs.getString(KEY_C_TAG, null).orEmpty(),
            title = prefs.getString(KEY_C_TITLE, null).orEmpty(),
            notes = prefs.getString(KEY_C_NOTES, null).orEmpty(),
            publishedAt = prefs.getString(KEY_C_PUBLISHED, null),
            htmlUrl = prefs.getString(KEY_C_HTML, null).orEmpty(),
            apkUrl = prefs.getString(KEY_C_APK_URL, null).orEmpty(),
            apkSizeBytes = prefs.getLong(KEY_C_APK_SIZE, 0L),
            sha256 = prefs.getString(KEY_C_SHA, null),
            sha256Url = prefs.getString(KEY_C_SHA_URL, null),
        )
    }

    override fun saveCached(info: UpdateInfo) {
        prefs.edit()
            .putString(KEY_C_VERSION, info.versionName)
            .putString(KEY_C_TAG, info.tagName)
            .putString(KEY_C_TITLE, info.title)
            .putString(KEY_C_NOTES, info.notes)
            .putString(KEY_C_PUBLISHED, info.publishedAt)
            .putString(KEY_C_HTML, info.htmlUrl)
            .putString(KEY_C_APK_URL, info.apkUrl)
            .putLong(KEY_C_APK_SIZE, info.apkSizeBytes)
            .putString(KEY_C_SHA, info.sha256)
            .putString(KEY_C_SHA_URL, info.sha256Url)
            .apply()
    }

    override fun clearCache() {
        prefs.edit()
            .remove(KEY_C_VERSION)
            .remove(KEY_C_TAG)
            .remove(KEY_C_TITLE)
            .remove(KEY_C_NOTES)
            .remove(KEY_C_PUBLISHED)
            .remove(KEY_C_HTML)
            .remove(KEY_C_APK_URL)
            .remove(KEY_C_APK_SIZE)
            .remove(KEY_C_SHA)
            .remove(KEY_C_SHA_URL)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_C_VERSION = "cached_version"
        const val KEY_C_TAG = "cached_tag"
        const val KEY_C_TITLE = "cached_title"
        const val KEY_C_NOTES = "cached_notes"
        const val KEY_C_PUBLISHED = "cached_published"
        const val KEY_C_HTML = "cached_html"
        const val KEY_C_APK_URL = "cached_apk_url"
        const val KEY_C_APK_SIZE = "cached_apk_size"
        const val KEY_C_SHA = "cached_sha"
        const val KEY_C_SHA_URL = "cached_sha_url"
    }
}
