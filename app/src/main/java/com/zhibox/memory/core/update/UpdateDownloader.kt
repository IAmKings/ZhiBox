package com.zhibox.memory.core.update

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
     * 支持 `.part` 断点续传（同版本）。
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
 * - 只允许 HTTPS；初始地址必须是 github.com 的本仓库 release 资产路径；
 * - 手动跟随重定向，**每一跳**都做 host 白名单校验（github.com /
 *   objects.githubusercontent.com / release-assets.githubusercontent.com）；
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
    /** 仅测试用：非空时把 host 白名单限制为该集合（MockHttpsServer 为 127.0.0.1）。 */
    private val testAllowedHosts: Set<String> = emptySet(),
) : UpdateDownloader {

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

        var resumeFrom = if (part.isFile) part.length() else 0L
        var connection = openFollowingRedirects(URL(info.apkUrl), resumeFrom)
        try {
            var code = connection.responseCode
            if (resumeFrom > 0) {
                val resumable = code == HttpURLConnection.HTTP_PARTIAL &&
                    contentRangeStart(connection.getHeaderField("Content-Range")) == resumeFrom
                if (!resumable) {
                    // 200（服务器忽略 Range）/416/路径变化：丢弃半包重下，避免把残缺前缀拼进新文件。
                    connection.disconnect()
                    part.delete()
                    resumeFrom = 0L
                    connection = openFollowingRedirects(URL(info.apkUrl), 0L)
                    code = connection.responseCode
                }
            }
            if (code !in 200..299) {
                throw UpdateException(UpdateFailure.Http(code))
            }
            var total = connection.contentLengthLong.let { if (it >= 0) it + resumeFrom else -1L }

            try {
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
            } finally {
                connection.disconnect()
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw UpdateException(UpdateFailure.Timeout)
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.Network)
        } finally {
            runCatching { connection.disconnect() }
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
        target
    }

    /** 期望的 SHA-256：优先 API digest；否则下载 `.sha256` 资产解析。 */
    private suspend fun resolveExpectedSha(info: UpdateInfo): String {
        info.sha256?.let { return it }
        val shaUrl = info.sha256Url ?: throw UpdateException(UpdateFailure.NoChecksum)
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = openFollowingRedirects(URL(shaUrl), 0L)
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

    private fun openFollowingRedirects(url: URL, rangeFrom: Long): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            validateAllowedUrl(current, initial = hop == 0)
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

    /** 初始地址限定本仓库 release 资产路径；重定向只做 host 白名单。 */
    private fun validateAllowedUrl(url: URL, initial: Boolean) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "拒绝非 https 下载地址")
        }
        val testHost = url.host?.lowercase(Locale.ROOT)
        if (testAllowedHosts.isNotEmpty()) {
            if (testHost == null || testHost !in testAllowedHosts) {
                throw UpdateException(UpdateFailure.IncompleteRelease, "测试域名白名单拒绝：${url.host}")
            }
            return
        }
        if (!url.host.equals("github.com", ignoreCase = true) &&
            !url.host.equals("api.github.com", ignoreCase = true) &&
            !ALLOWED_REDIRECT_HOSTS.contains(url.host?.lowercase(Locale.ROOT))
        ) {
            throw UpdateException(UpdateFailure.IncompleteRelease, "下载域名不在白名单：${url.host}")
        }
        if (initial && !url.path.orEmpty().startsWith(
                "/${UpdateConfig.repoOwner}/${UpdateConfig.repoName}/releases/download/",
            )
        ) {
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
