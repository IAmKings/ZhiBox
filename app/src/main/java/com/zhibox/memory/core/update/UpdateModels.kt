package com.zhibox.memory.core.update

import com.zhibox.memory.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 应用内更新（App Update）的纯数据模型与解析逻辑。
 *
 * 本文件不依赖 Android 运行时之外的东西（只有 BuildConfig 常量），全部可被纯 JVM 单测覆盖：
 * - 版本号解析/比较（`v0.1.6` vs `0.1.5`，容忍 `-rc1` 之类后缀）；
 * - GitHub `releases/latest` JSON 解析（契约见 `docs/product/APP_UPDATE_SPEC.md`）；
 * - 更新说明 markdown → 纯文本；
 * - 状态机/错误模型（UI 与 manager 共用）。
 */

/** GitHub 更新源配置（owner/repo 由 build.gradle.kts 的 BuildConfig 派生）。 */
object UpdateConfig {
    val repoOwner: String = BuildConfig.UPDATE_REPO_OWNER
    val repoName: String = BuildConfig.UPDATE_REPO_NAME

    /** 匿名可访问（仓库必须为 public；见 APP_UPDATE_SPEC.md）。 */
    val latestReleaseUrl: String = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    val userAgent: String = "ZhiBox/${BuildConfig.VERSION_NAME} (+https://github.com/$repoOwner/$repoName)"

    /**
     * GitHub Release 资产直连在部分网络不可达时的下载镜像前缀（按顺序回退）。
     * 镜像只看到公开 APK；完整性由 SHA-256（API digest / `.sha256` 资产）与安装前
     * 包名/versionCode/签名证书校验共同保证。
     */
    val downloadMirrors: List<String> = BuildConfig.UPDATE_DOWNLOAD_MIRRORS
        .split(',')
        .map { it.trim() }
        .filter { it.startsWith("https://") && it.endsWith("/") }

    /** Release 资产命名契约（release.yml：`zhibox-<version>-<sha7>-release-signed.apk`）。 */
    const val APK_ASSET_SUFFIX = "-release-signed.apk"
    const val SHA256_ASSET_SUFFIX = ".sha256"

    /** 更新说明在本地存储/展示用的上限（完整内容可跳浏览器）。 */
    const val NOTES_LIMIT = 4_000

    /** 自动检查节流：成功检查后 24h 内不重复请求。 */
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L

    /** 自动检查失败后退避：1h 内不再自动重试（手动始终可重试）。 */
    const val AUTO_RETRY_INTERVAL_MS = 60 * 60 * 1_000L

    /** 下载整体超时：避免网络卡死时不可取消的进度框永久停留。 */
    const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1_000L
}

/** 可比较的应用版本：`v` 前缀、段数不足、`-rc`/`+build` 后缀均容错。 */
data class AppVersion(val raw: String, val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val left = parts.getOrElse(i) { 0 }
            val right = other.parts.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}

fun parseAppVersion(raw: String): AppVersion? {
    val text = raw.trim().removePrefix("v").removePrefix("V")
    if (text.isEmpty()) return null
    val core = text.substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    val parts = core.split('.').map { segment ->
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    }
    if (parts.isEmpty()) return null
    return AppVersion(raw, parts)
}

/** 远端版本是否比当前版本新；任一无法解析时返回 false（保守不提示）。 */
fun isNewerVersion(remote: String, current: String): Boolean {
    val remoteVersion = parseAppVersion(remote) ?: return false
    val currentVersion = parseAppVersion(current) ?: return false
    return remoteVersion > currentVersion
}

/** 一条可下载的更新信息（GitHub release + 资产 + 校验值）。 */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String?,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** 优先取 GitHub API 资产 digest；为空时用 [sha256Url] 回退下载 `.sha256` 文件。 */
    val sha256: String?,
    val sha256Url: String?,
)

/** 失败原因（core 不引资源 ID，由 UI 层映射为可读文案）。 */
sealed interface UpdateFailure {
    data object Network : UpdateFailure
    data object Timeout : UpdateFailure
    data class Http(val code: Int, val rateLimited: Boolean = false) : UpdateFailure
    data object Parse : UpdateFailure
    data object NoRelease : UpdateFailure
    data object IncompleteRelease : UpdateFailure
    data object NoChecksum : UpdateFailure
    data object ChecksumMismatch : UpdateFailure
    data object ApkInvalid : UpdateFailure
    data object InsufficientSpace : UpdateFailure
    data object InstallPermission : UpdateFailure
    data object InstallFailed : UpdateFailure
    data object FileUnavailable : UpdateFailure
}

class UpdateException(
    val failure: UpdateFailure,
    message: String? = null,
) : Exception(message ?: failure.toString())

/** 触发来源：自动（冷启动）或手动（设置里「检查更新」）。 */
enum class UpdateTrigger { AUTO, MANUAL }

/**
 * 更新服务状态机（UI 只读；所有流转只在 AppUpdateManager 内发生）。
 */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    data class Checking(val trigger: UpdateTrigger) : UpdatePhase

    data class UpToDate(val trigger: UpdateTrigger, val versionName: String) : UpdatePhase

    data class Available(val trigger: UpdateTrigger, val info: UpdateInfo) : UpdatePhase

    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdatePhase

    data class Verifying(val info: UpdateInfo) : UpdatePhase

    data class NeedInstallPermission(val info: UpdateInfo) : UpdatePhase

    data class CheckFailed(val trigger: UpdateTrigger, val failure: UpdateFailure) : UpdatePhase

    data class DownloadFailed(val info: UpdateInfo, val failure: UpdateFailure) : UpdatePhase
}

/** 供 Compose 消费的完整状态（Dialog 与设置行共用）。 */
data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val lastCheckAtMillis: Long = 0L,
)

/**
 * markdown → 纯文本（更新说明只在弹窗里展示概要，完整内容跳浏览器）。
 * 不做完整 markdown 渲染：去标题符号/加粗/行内代码/链接/引用，压缩空行并截断。
 */
fun plainTextFromMarkdown(markdown: String, limit: Int = UpdateConfig.NOTES_LIMIT): String {
    var text = markdown
        .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^#{1,6}\s*"""), "")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""__(.+?)__"""), "$1")
        .replace(Regex("""`([^`]*)`"""), "$1")
        .replace(Regex("""(?m)^>\s?"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    if (text.length > limit) {
        text = text.take(limit).trimEnd() + "…"
    }
    return text
}

/** GitHub `releases/latest` 响应解析（使用已有 kotlinx.serialization JSON DOM，无编译器插件）。 */
object UpdateReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("^[a-f0-9]{64}$")

    fun parseLatestRelease(body: String): UpdateInfo {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.Parse, "release json 解析失败")
        }

        fun obj(key: String) = root[key]?.jsonPrimitive?.contentOrNull
        fun bool(key: String) = obj(key)?.toBooleanStrictOrNull() ?: false
        if (bool("draft") || bool("prerelease")) {
            throw UpdateException(UpdateFailure.NoRelease, "latest release 是 draft/prerelease")
        }

        val tagName = obj("tag_name")?.trim().orEmpty()
        val version = parseAppVersion(tagName)
            ?: throw UpdateException(UpdateFailure.Parse, "tag_name 不可解析：$tagName")
        val versionName = tagName.removePrefix("v").removePrefix("V")

        val assets = try {
            root["assets"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.Parse, "assets 解析失败")
        }

        fun assetName(asset: kotlinx.serialization.json.JsonObject): String =
            asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val apkAsset = assets.firstOrNull {
            assetName(it).endsWith(UpdateConfig.APK_ASSET_SUFFIX)
        } ?: throw UpdateException(UpdateFailure.IncompleteRelease, "release 缺少 signed apk 资产")

        val apkName = assetName(apkAsset)
        val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!apkUrl.startsWith("https://github.com/")) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "apk 下载地址非法：$apkUrl")
        }
        val apkSize = apkAsset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

        val digest = apkAsset["digest"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { sha256Regex.matches(it) }

        val shaAsset = assets.firstOrNull { assetName(it) == apkName + UpdateConfig.SHA256_ASSET_SUFFIX }
        val shaUrl = shaAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://github.com/") }
        if (digest == null && shaUrl == null) {
            throw UpdateException(UpdateFailure.NoChecksum, "release 缺少 SHA-256（digest 与 .sha256 均无）")
        }

        val htmlUrl = obj("html_url").orEmpty()
        val notes = plainTextFromMarkdown(obj("body").orEmpty())

        return UpdateInfo(
            versionName = versionName,
            tagName = tagName,
            title = obj("name")?.trim().orEmpty().ifBlank { tagName },
            notes = notes.ifBlank { "（该版本未提供更新说明）" },
            publishedAt = obj("published_at"),
            htmlUrl = htmlUrl,
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            sha256 = digest,
            sha256Url = shaUrl,
        )
    }

    /** 解析 `.sha256` 资产内容（`<hex>  <filename>` 或纯 hex）。 */
    fun parseSha256File(text: String): String? {
        val hex = text.trim().substringBefore(' ').substringBefore('\t').lowercase()
        return hex.takeIf { sha256Regex.matches(it) }
    }
}
