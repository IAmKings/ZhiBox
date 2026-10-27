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
) : UpdateDownloader {

    private val mirrorHosts: Set<String> = mirrorPrefixes.mapNotNull { prefix ->
        runCatching { URL(prefix).host?.lowercase(Locale.ROOT) }.getOrNull()
    }.toSet()

    override suspend fun download(
        info: UpdateInfo,
        targetDir: File,
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

        // 直连优先；仅对传输层失败（Network/Timeout）回退镜像，HTTP/校验类错误不回退。
        val attempts = buildList {
            add(Attempt(url = info.apkUrl, mirror = false))
            mirrorPrefixes.forEach { prefix ->
                add(Attempt(url = prefix + info.apkUrl, mirror = true))
            }
        }
        var lastFailure: UpdateException? = null
        attempts.forEachIndexed { index, attempt ->
            try {
                return@withContext attemptDownload(
                    attempt = attempt,
                    info = info,
                    part = part,
                    target = target,
                    expectedSha = expectedSha,
                    allowResume = index == 0,
                    onProgress = onProgress,
                )
            } catch (e: UpdateException) {
                lastFailure = e
                val retryableMirror = e.failure is UpdateFailure.Network ||
                    e.failure is UpdateFailure.Timeout
                if (!retryableMirror) throw e
                // 下一次尝试从零开始（镜像不一定支持 Range；半包不可信）
                part.delete()
            }
        }
        throw lastFailure ?: UpdateException(UpdateFailure.Network)
    }

    private data class Attempt(val url: String, val mirror: Boolean)

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
            connection = openFollowingRedirects(URL(attempt.url), attempt.mirror, resumeFrom)
            var code = connection.responseCode
            if (resumeFrom > 0) {
                val resumable = code == HttpURLConnection.HTTP_PARTIAL &&
                    contentRangeStart(connection.getHeaderField("Content-Range")) == resumeFrom
                if (!resumable) {
                    connection.disconnect()
                    part.delete()
                    resumeFrom = 0L
                    connection = openFollowingRedirects(URL(attempt.url), attempt.mirror, 0L)
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
            var connection: HttpURLConnection? = null
            try {
                connection = openFollowingRedirects(URL(shaUrl), mirror = false, rangeFrom = 0L)
                if (connection.responseCode !in 200..299) {
                    throw UpdateException(UpdateFailure.Http(connection.responseCode))
                }
                val text = connection.inputStream.use { readLimited(it, MAX_SHA_FILE_BYTES) }
                UpdateReleaseParser.parseSha256File(text)
                    ?: throw UpdateException(UpdateFailure.NoChecksum)
            } catch (e: UpdateException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw UpdateException(UpdateFailure.Timeout)
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.Network)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    private fun openFollowingRedirects(
        url: URL,
        mirror: Boolean,
        rangeFrom: Long,
    ): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            validateAllowedUrl(current, initial = hop == 0, mirror = mirror)
            val connection = openConnection(current).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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
            if (host == null || host !in mirrorHosts) {
                throw UpdateException(UpdateFailure.IncompleteRelease, "镜像域名不在白名单：${url.host}")
            }
            if (!url.toString().contains(releasePath)) {
                throw UpdateException(UpdateFailure.IncompleteRelease, "镜像路径非法：${url.path}")
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
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
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
