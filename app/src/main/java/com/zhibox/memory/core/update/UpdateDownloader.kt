package com.zhibox.memory.core.update

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** APK 下载抽象（manager 单测可注入 fake）。 */
interface UpdateDownloader {
    /**
     * 下载并校验 SHA-256，成功返回最终 APK 文件；失败抛 [UpdateException]。
     * 直连 GitHub 资产失败（网络/超时）时会按 [UpdateConfig.downloadMirrors] 依次回退镜像。
     */
    suspend fun download(
        info: UpdateInfo,
        targetDir: File,
        routeHealth: Map<String, RouteHealth> = emptyMap(),
        onRouteResult: (RouteHealth) -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File
}

/**
 * GitHub Release APK 下载器。
 *
 * 安全/稳定性约束（docs/product/APP_UPDATE_SPEC.md）：
 * - 只允许 HTTPS；直连地址必须是 github.com 的本仓库 release 资产路径；
 * - 手动跟随重定向，**每一跳**都做 host 白名单校验（github.com /
 *   objects.githubusercontent.com / release-assets.githubusercontent.com）；
 * - 直连 CDN 在部分网络（国内常见）不可用时，按配置的**下载镜像**前缀回退；
 *   镜像只看到公开 APK，完整性由 SHA-256 + 安装前签名/包名/versionCode 校验共同保证；
 * - Range 续传要求 206 + Content-Range 起点一致，否则丢弃半包重下；
 * - 下载完整文件后流式 SHA-256 校验；失败删除 `.part`；
 * - 磁盘空间预检 + 原子 rename。
 */
class HttpUpdateDownloader(
    private val userAgent: String = UpdateConfig.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val freeBytes: (File) -> Long = { dir ->
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    },
    private val mirrorPrefixes: List<String> = UpdateConfig.downloadMirrors,
    /** 仅测试用：非空时把 host 白名单限制为该集合（MockHttpsServer 为 127.0.0.1）。 */
    private val testAllowedHosts: Set<String> = emptySet(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : UpdateDownloader {

    private val mirrorHosts: Set<String> = mirrorPrefixes.mapNotNull { prefix ->
        runCatching { URL(prefix).host?.lowercase(Locale.ROOT) }.getOrNull()
    }.toSet()

    override suspend fun download(
        info: UpdateInfo,
        targetDir: File,
        routeHealth: Map<String, RouteHealth>,
        onRouteResult: (RouteHealth) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val target = File(targetDir, "zhibox-${info.versionName}.apk")
        val part = File(targetDir, target.name + ".part")
        val expectedSha = resolveExpectedSha(info)

        // 旧版本残留清理（不同版本的同名文件/半包不能复用）
        targetDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("zhibox-") && file.absolutePath != part.absolutePath &&
                file.absolutePath != target.absolutePath
            ) {
                file.delete()
            }
        }

        val requiredBytes = (info.apkSizeBytes.takeIf { it > 0 } ?: DEFAULT_APK_BYTES) * 2 +
            MIN_SPARE_BYTES
        if (freeBytes(targetDir) < requiredBytes) {
            throw UpdateException(UpdateFailure.InsufficientSpace)
        }

        // 线路健康驱动的尝试顺序：直连未冷却时优先直连，否则优先健康镜像。
        val effectiveMirrors = if (testAllowedHosts.isNotEmpty()) emptyList() else mirrorPrefixes
        val mirrorAttempts = effectiveMirrors.mapNotNull { prefix ->
            val host = runCatching { URL(prefix).host?.lowercase(Locale.ROOT) }.getOrNull()
            if (host == null) null else Attempt(id = host, url = prefix + info.apkUrl, mirror = true)
        }.distinctBy { it.id }
        val routeById = buildMap {
            put(
                UpdateRouteSelector.DIRECT_ID,
                Attempt(id = UpdateRouteSelector.DIRECT_ID, url = info.apkUrl, mirror = false),
            )
            mirrorAttempts.forEach { put(it.id, it) }
        }
        val orderedRoutes = UpdateRouteSelector.plan(
            nowMillis = nowMillis(),
            mirrorIds = mirrorAttempts.map { it.id },
            health = routeHealth,
        )
        var lastFailure: UpdateException? = null
        var nonNotFoundFailure: UpdateException? = null
        var sawNotFound = false
        var sawNonNotFoundFailure = false
        orderedRoutes.forEachIndexed { index, routeId ->
            val attempt = routeById[routeId] ?: return@forEachIndexed
            val startedAt = nowMillis()
            try {
                val file = attemptDownload(
                    attempt = attempt,
                    info = info,
                    part = part,
                    target = target,
                    expectedSha = expectedSha,
                    allowResume = true,
                    onProgress = onProgress,
                )
                onRouteResult(
                    UpdateRouteSelector.record(
                        nowMillis = nowMillis(),
                        previous = routeHealth[routeId],
                        success = true,
                        latencyMs = (nowMillis() - startedAt).coerceAtLeast(0L),
                        id = routeId,
                    ),
                )
                return@withContext file
            } catch (e: UpdateException) {
                lastFailure = e
                when (val failure = e.failure) {
                    UpdateFailure.Network, UpdateFailure.Timeout -> {
                        sawNonNotFoundFailure = true
                        onRouteResult(
                            UpdateRouteSelector.record(
                                nowMillis = nowMillis(),
                                previous = routeHealth[routeId],
                                success = false,
                                latencyMs = 0L,
                                id = routeId,
                            ),
                        )
                        // 网络/读超时保留 .part：重试时继续 Range 续传，弱网可从断点恢复。
                    }

                    is UpdateFailure.Http -> {
                        if (failure.code == 404 || failure.code == 410) {
                            sawNotFound = true
                        } else {
                            nonNotFoundFailure = e
                            sawNonNotFoundFailure = true
                        }
                        part.delete()
                    }

                    UpdateFailure.ChecksumMismatch -> {
                        // 某条线路给的内容不对：换路重试，最终失败保留校验错误。
                        nonNotFoundFailure = e
                        sawNonNotFoundFailure = true
                        part.delete()
                    }

                    else -> throw e
                }
            }
        }
        // 只有所有线路都明确 404/410 才判定版本已下架；混合网络/服务器错误时
        // 保留缓存，让用户可稍后重试，避免误清强制/灰度状态。
        if (sawNotFound && !sawNonNotFoundFailure) {
            throw UpdateException(UpdateFailure.ReleaseUnavailable)
        }
        throw nonNotFoundFailure ?: lastFailure ?: UpdateException(UpdateFailure.Network)
    }

    private data class Attempt(val id: String, val url: String, val mirror: Boolean)

    private suspend fun attemptDownload(
        attempt: Attempt,
        info: UpdateInfo,
        part: File,
        target: File,
        expectedSha: String,
        allowResume: Boolean,
        onProgress: (Long, Long) -> Unit,
    ): File {
        var resumeFrom = if (allowResume && part.isFile) part.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            val connectTimeout = if (attempt.mirror) MIRROR_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            val readTimeout = if (attempt.mirror) MIRROR_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            connection = openFollowingRedirects(
                URL(attempt.url),
                attempt.mirror,
                resumeFrom,
                connectTimeout,
                readTimeout,
            )
            var code = connection.responseCode
            if (resumeFrom > 0) {
                val resumable = code == HttpURLConnection.HTTP_PARTIAL &&
                    contentRangeStart(connection.getHeaderField("Content-Range")) == resumeFrom
                if (!resumable) {
                    connection.disconnect()
                    part.delete()
                    resumeFrom = 0L
                    connection = openFollowingRedirects(
                        URL(attempt.url),
                        attempt.mirror,
                        0L,
                        connectTimeout,
                        readTimeout,
                    )
                    code = connection.responseCode
                }
            }
            if (code !in 200..299) {
                throw UpdateException(UpdateFailure.Http(code))
            }
            val total = connection.contentLengthLong.let { if (it >= 0) it + resumeFrom else -1L }

            connection.inputStream.use { input ->
                FileOutputStream(part, resumeFrom > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = resumeFrom
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw UpdateException(UpdateFailure.Timeout)
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.Network)
        } finally {
            runCatching { connection?.disconnect() }
        }

        if (!part.isFile || part.length() == 0L) {
            part.delete()
            throw UpdateException(UpdateFailure.Network, "下载内容为空")
        }
        if (info.apkSizeBytes > 0 && part.length() != info.apkSizeBytes) {
            part.delete()
            throw UpdateException(UpdateFailure.ChecksumMismatch, "APK 大小不符")
        }
        val actualSha = sha256Hex(part)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            part.delete()
            throw UpdateException(UpdateFailure.ChecksumMismatch)
        }
        if (target.isFile) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateException(UpdateFailure.FileUnavailable, "APK 落盘失败")
        }
        return target
    }

    /** 期望的 SHA-256：优先 API digest；否则下载 `.sha256` 资产解析。 */
    private suspend fun resolveExpectedSha(info: UpdateInfo): String {
        info.sha256?.let { return it }
        val shaUrl = info.sha256Url ?: throw UpdateException(UpdateFailure.NoChecksum)
        return withContext(Dispatchers.IO) {
            val effectiveMirrors = if (testAllowedHosts.isNotEmpty()) emptyList() else mirrorPrefixes
            val candidates = buildList {
                add(shaUrl to false)
                effectiveMirrors.forEach { add(it + shaUrl to true) }
            }
            var lastFailure: UpdateException? = null
            var sawNotFound = false
            var sawNonNotFound = false
            for ((candidate, mirrored) in candidates) {
                var connection: HttpURLConnection? = null
                try {
                    connection = openFollowingRedirects(
                        url = URL(candidate),
                        mirror = mirrored,
                        rangeFrom = 0L,
                    )
                    val shaCode = connection.responseCode
                    if (shaCode !in 200..299) {
                        lastFailure = UpdateException(UpdateFailure.Http(shaCode))
                        if (shaCode == 404 || shaCode == 410) sawNotFound = true else sawNonNotFound = true
                        continue
                    }
                    val text = connection.inputStream.use { readLimited(it, MAX_SHA_FILE_BYTES) }
                    val sha = UpdateReleaseParser.parseSha256File(text)
                    if (sha == null) {
                        sawNonNotFound = true
                        lastFailure = UpdateException(UpdateFailure.NoChecksum)
                        continue
                    }
                    return@withContext sha
                } catch (e: UpdateException) {
                    lastFailure = e
                    sawNonNotFound = true
                } catch (e: SocketTimeoutException) {
                    lastFailure = UpdateException(UpdateFailure.Timeout)
                    sawNonNotFound = true
                } catch (e: IOException) {
                    lastFailure = UpdateException(UpdateFailure.Network)
                    sawNonNotFound = true
                } finally {
                    runCatching { connection?.disconnect() }
                }
            }
            if (sawNotFound && !sawNonNotFound) {
                throw UpdateException(UpdateFailure.ReleaseUnavailable)
            }
            throw lastFailure ?: UpdateException(UpdateFailure.NoChecksum)
        }
    }

    private fun openFollowingRedirects(
        url: URL,
        mirror: Boolean,
        rangeFrom: Long,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            validateAllowedUrl(current, initial = hop == 0, mirror = mirror)
            val connection = openConnection(current).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/octet-stream")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw UpdateException(UpdateFailure.IncompleteRelease, "重定向缺少 Location")
                }
                current = URL(current, location)
            } else {
                return connection
            }
        }
        throw UpdateException(UpdateFailure.IncompleteRelease, "重定向次数过多")
    }

    /**
     * 直连：初始地址限定本仓库 release 资产路径，重定向只做 host 白名单；
     * 镜像：host 必须在配置的镜像域内，且 URL 必须包含本仓库 release 资产路径。
     */
    private fun validateAllowedUrl(url: URL, initial: Boolean, mirror: Boolean) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "拒绝非 https 下载地址")
        }
        val host = url.host?.lowercase(Locale.ROOT)
        val releasePath = "/${UpdateConfig.repoOwner}/${UpdateConfig.repoName}/releases/download/"
        if (testAllowedHosts.isNotEmpty()) {
            if (host == null || host !in testAllowedHosts) {
                throw UpdateException(UpdateFailure.IncompleteRelease, "测试域名白名单拒绝：${url.host}")
            }
            return
        }
        if (mirror) {
            if (initial) {
                if (host == null || host !in mirrorHosts) {
                    throw UpdateException(UpdateFailure.IncompleteRelease, "镜像域名不在白名单：${url.host}")
                }
                if (!url.toString().contains(releasePath)) {
                    throw UpdateException(UpdateFailure.IncompleteRelease, "镜像路径非法：${url.path}")
                }
            } else {
                // 镜像可能重定向到 GitHub CDN；重定向目标允许镜像域 + GitHub CDN 白名单。
                if (host == null || (host !in mirrorHosts && host !in ALLOWED_REDIRECT_HOSTS)) {
                    throw UpdateException(UpdateFailure.IncompleteRelease, "镜像重定向域名不在白名单：${url.host}")
                }
            }
            return
        }
        if (host == null || !ALLOWED_REDIRECT_HOSTS.contains(host)) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "下载域名不在白名单：${url.host}")
        }
        if (initial && !url.path.orEmpty().startsWith(releasePath)) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "下载路径非法：${url.path}")
        }
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val MIRROR_CONNECT_TIMEOUT_MS = 10_000
        const val MIRROR_READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_SHA_FILE_BYTES = 4 * 1024
        const val MIN_SPARE_BYTES = 50L * 1024 * 1024
        const val DEFAULT_APK_BYTES = 80L * 1024 * 1024

        val ALLOWED_REDIRECT_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        /** 解析 `Content-Range: bytes START-END/TOTAL` 的 START。 */
        internal fun contentRangeStart(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
                .find(header) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
