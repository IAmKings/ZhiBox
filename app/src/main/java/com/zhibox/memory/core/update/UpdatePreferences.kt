package com.zhibox.memory.core.update

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

    /** 自动检查开关（默认 true；关闭后仅手动检查）。 */
    var autoCheckEnabled: Boolean

    /** 更新通道（默认 STABLE；BETA 接收 GitHub prerelease）。 */
    var channel: UpdateChannel

    /** 本地安装标识（随机 UUID；仅用于灰度分桶，不上报）。 */
    var installId: String

    fun cachedInfo(): UpdateInfo?
    fun saveCached(info: UpdateInfo)
    fun clearCache()

    /** 缓存是否包含灰度/强制元数据（v0.1.15+；旧缓存为 false，不能用于灰度/强制判断）。 */
    val cachedMetaKnown: Boolean

    /** 缓存写入时间（0=旧缓存/未知）；用于强制缓存的过期校验。 */
    val cachedAtMillis: Long

    /** 下载线路健康统计（本地，不上报）。 */
    fun routeHealth(): Map<String, RouteHealth>
    fun updateRouteHealth(health: Map<String, RouteHealth>)

    /** 后台下载完成、等待安装的 APK（跨进程恢复用）。 */
    var downloadedVersion: String?
    var downloadedApkPath: String?

    /** 已经拉起过系统安装器的版本（防止用户取消安装后重启又自动拉起）。 */
    var installerLaunchedVersion: String?

    /** 已经消费过的 WorkManager 任务 id（防止重启后重复消费 SUCCEEDED）。 */
    var consumedWorkId: String?
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

    override var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
        }

    override var channel: UpdateChannel
        get() = when (prefs.getString(KEY_CHANNEL, null)) {
            UpdateChannel.BETA.name -> UpdateChannel.BETA
            else -> UpdateChannel.STABLE
        }
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    override var installId: String
        get() {
            val existing = prefs.getString(KEY_INSTALL_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
            return generated
        }
        set(value) {
            prefs.edit().putString(KEY_INSTALL_ID, value).apply()
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
            rolloutPercent = prefs.getInt(KEY_C_ROLLOUT, 100).coerceIn(0, 100),
            minSupportedVersion = prefs.getString(KEY_C_MIN_SUPPORTED, null),
            channel = when (prefs.getString(KEY_C_CHANNEL, null)) {
                UpdateChannel.BETA.name -> UpdateChannel.BETA
                else -> UpdateChannel.STABLE
            },
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
            .putInt(KEY_C_ROLLOUT, info.rolloutPercent.coerceIn(0, 100))
            .putString(KEY_C_MIN_SUPPORTED, info.minSupportedVersion)
            .putString(KEY_C_CHANNEL, info.channel.name)
            .putInt(KEY_C_META_SCHEMA, CACHE_META_SCHEMA)
            .putLong(KEY_C_AT, System.currentTimeMillis())
            .apply()
    }

    override val cachedMetaKnown: Boolean
        get() = prefs.getInt(KEY_C_META_SCHEMA, 0) >= CACHE_META_SCHEMA

    override val cachedAtMillis: Long
        get() = prefs.getLong(KEY_C_AT, 0L)

    override var downloadedVersion: String?
        get() = prefs.getString(KEY_DOWNLOADED_VERSION, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_VERSION, value).apply()
        }

    override var downloadedApkPath: String?
        get() = prefs.getString(KEY_DOWNLOADED_APK_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_APK_PATH, value).apply()
        }

    override var installerLaunchedVersion: String?
        get() = prefs.getString(KEY_INSTALLER_LAUNCHED_VERSION, null)
        set(value) {
            prefs.edit().putString(KEY_INSTALLER_LAUNCHED_VERSION, value).apply()
        }

    override var consumedWorkId: String?
        get() = prefs.getString(KEY_CONSUMED_WORK_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CONSUMED_WORK_ID, value).apply()
        }

    override fun routeHealth(): Map<String, RouteHealth> {
        val raw = prefs.getString(KEY_ROUTE_HEALTH, null) ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (id, element) ->
                val obj = element.jsonObject
                RouteHealth(
                    id = id,
                    lastSuccessAt = obj["lastSuccessAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    lastFailureAt = obj["lastFailureAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    consecutiveFailures = obj["consecutiveFailures"]?.jsonPrimitive?.intOrNull ?: 0,
                    lastLatencyMs = obj["lastLatencyMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cooldownUntil = obj["cooldownUntil"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        }.getOrDefault(emptyMap())
    }

    override fun updateRouteHealth(health: Map<String, RouteHealth>) {
        val obj = buildJsonObject {
            health.forEach { (id, route) ->
                put(
                    id,
                    buildJsonObject {
                        put("lastSuccessAt", route.lastSuccessAt)
                        put("lastFailureAt", route.lastFailureAt)
                        put("consecutiveFailures", route.consecutiveFailures)
                        put("lastLatencyMs", route.lastLatencyMs)
                        put("cooldownUntil", route.cooldownUntil)
                    },
                )
            }
        }
        prefs.edit().putString(KEY_ROUTE_HEALTH, obj.toString()).apply()
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
            .remove(KEY_C_ROLLOUT)
            .remove(KEY_C_MIN_SUPPORTED)
            .remove(KEY_C_CHANNEL)
            .remove(KEY_C_META_SCHEMA)
            .remove(KEY_C_AT)
            .apply()
    }

    private companion object {
        val json = Json
        const val PREFS_NAME = "app_update"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_AUTO_CHECK = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_ROUTE_HEALTH = "route_health"
        const val KEY_DOWNLOADED_VERSION = "downloaded_version"
        const val KEY_DOWNLOADED_APK_PATH = "downloaded_apk_path"
        const val KEY_INSTALLER_LAUNCHED_VERSION = "installer_launched_version"
        const val KEY_CONSUMED_WORK_ID = "consumed_work_id"
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
        const val KEY_C_ROLLOUT = "cached_rollout_percent"
        const val KEY_C_MIN_SUPPORTED = "cached_min_supported"
        const val KEY_C_CHANNEL = "cached_channel"
        const val KEY_C_META_SCHEMA = "cached_meta_schema"
        const val CACHE_META_SCHEMA = 2
        const val KEY_C_AT = "cached_at"
    }
}
