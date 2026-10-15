package com.zhibox.memory.core.model

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型资产下载器（P1-W1-5）：显式用户触发的下载（应用内唯一联网路径）。
 * 断点续传（Range）、流式 SHA-256 校验、磁盘空间预检、原子改名。
 * 失败残留 .part 文件，下次续传；校验失败删除 .part 并抛错。
 */
object ModelDownloader {

    class DownloadException(message: String) : Exception(message)

    private const val MIN_FREE_BYTES = 600L * 1024 * 1024 // 440M 模型 + 余量
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000

    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        val free = StatFs(target.parentFile!!.absolutePath).availableBytes
        if (free < MIN_FREE_BYTES) {
            throw DownloadException("insufficient disk space: need ≥${MIN_FREE_BYTES / 1024 / 1024}MB, free ${free / 1024 / 1024}MB")
        }

        var resumeFrom = if (part.isFile) part.length() else 0L
        var conn = open(url, resumeFrom)
        var total = conn.contentLengthLong.let { if (it >= 0) it + resumeFrom else -1L }
        if (conn.responseCode == 416) { // 服务器不支持或文件已完成
            conn.disconnect()
            part.delete()
            resumeFrom = 0
            conn = open(url, 0)
            total = conn.contentLengthLong.let { if (it >= 0) it else -1L }
        }
        checkHttpOk(conn.responseCode)

        val digest = MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, resumeFrom > 0).use { out ->
                    if (resumeFrom > 0) {
                        // 续传时重新计算整文件哈希：先读已有部分
                        part.inputStream().use { prev ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = prev.read(buf)
                                if (n < 0) break
                                digest.update(buf, 0, n)
                            }
                        }
                    }
                    val buf = ByteArray(64 * 1024)
                    var downloaded = resumeFrom
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actualHex.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw DownloadException("sha256 mismatch: expected $expectedSha256, got $actualHex")
        }
        if (target.isFile) target.delete()
        check(part.renameTo(target)) { "rename to ${target.name} failed" }
        target
    }

    private fun open(url: String, resumeFrom: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        return conn
    }

    private fun checkHttpOk(code: Int) {
        if (code !in 200..299 && code != 206) {
            throw DownloadException("http $code")
        }
    }
}
