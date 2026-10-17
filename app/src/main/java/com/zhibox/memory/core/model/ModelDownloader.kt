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
import java.util.Locale

/**
 * 模型资产下载器（P1-W1-5）：显式用户触发的下载（应用内唯一联网路径）。
 * 断点续传（Range）、流式 SHA-256 校验、磁盘空间预检、原子改名。
 * 失败残留 .part 文件，下次续传；校验失败删除 .part 并抛错。
 *
 * P1-10 安全边界（本轮加固）：
 * - **只允许 https 且 host ∈ 白名单**（hf-mirror.com）：catalog 是常量来源，但仍做防御性校验，
 *   避免未来"配置/输入驱动下载地址"时把明文或任意域引进来；
 * - **Range 续传必须 206 + Content-Range 起点一致**，否则丢弃 .part 从头下载：服务器忽略 Range
 *   返回 200 时，旧实现会继续以 append 方式写，把残缺前缀拼进新文件——静默损坏，只有最后
 *   SHA-256 失败才发现（白下几百 MB，且用户看到的是"下载失败"而非"续传失败"）。
 */
object ModelDownloader {

    class DownloadException(message: String) : Exception(message)

    /** 下载域名白名单：模型资产仅从 hf-mirror 镜像获取（新增源必须显式加白并审视 TLS 面） */
    private val ALLOWED_HOSTS = setOf("hf-mirror.com")

    private const val MIN_FREE_BYTES = 600L * 1024 * 1024 // 440M 模型 + 余量
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000

    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // 下载地址安全门：https + host 白名单（P1-10）
        validateUrl(url)

        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        val free = StatFs(target.parentFile!!.absolutePath).availableBytes
        if (free < MIN_FREE_BYTES) {
            throw DownloadException("insufficient disk space: need ≥${MIN_FREE_BYTES / 1024 / 1024}MB, free ${free / 1024 / 1024}MB")
        }

        var resumeFrom = if (part.isFile) part.length() else 0L
        var conn = open(url, resumeFrom)
        if (resumeFrom > 0) {
            val resumable = conn.responseCode == 206 &&
                contentRangeStart(conn.getHeaderField("Content-Range")) == resumeFrom
            if (!resumable) {
                // 416（文件已完成/范围失效）或 200（服务器忽略 Range）或 Content-Range 起点不符：
                // 一律丢弃半包从头下载。append 到不可信前缀 = 生成损坏文件。
                conn.disconnect()
                part.delete()
                resumeFrom = 0
                conn = open(url, 0)
            }
        }
        checkHttpOk(conn.responseCode)
        var total = conn.contentLengthLong.let { if (it >= 0) it + resumeFrom else -1L }

        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, resumeFrom > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = resumeFrom
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 空响应体（如代理返回 200 空内容）：直接失败，避免把 0B 文件当模型改名落盘
        if (!part.isFile || part.length() == 0L) {
            part.delete()
            throw DownloadException("下载内容为空：${target.name}")
        }
        // 供应链校验统一走 ModelIntegrity.verify（P1-7 审查修复：不再在本类内联 SHA-256 实现；
        // 下载器只负责"完整落盘到 .part"，校验/失败语义与 SAF 启用、备份导入完全一致）。
        if (expectedSha256 != null) {
            ModelIntegrity.verify(part, expectedSha256, expectedSize = null).getOrElse {
                part.delete()
                throw DownloadException(it.message ?: "模型校验失败：${target.name}")
            }
        }
        if (target.isFile) target.delete()
        check(part.renameTo(target)) { "rename to ${target.name} failed" }
        target
    }

    /**
     * 下载地址安全校验（纯函数，JVM 单测覆盖 P1-10）：
     * scheme 必须是 https、host 必须命中白名单（精确匹配，防 `evil-hf-mirror.com` 之类后缀欺骗）。
     */
    internal fun validateUrl(url: String) {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw DownloadException("下载地址非法：$url")
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw DownloadException("拒绝非 https 下载地址（P1-10 只允许加密传输）：$url")
        }
        val host = parsed.host?.lowercase(Locale.ROOT)
        if (host == null || host !in ALLOWED_HOSTS) {
            throw DownloadException(
                "下载域名不在白名单（${ALLOWED_HOSTS.joinToString()}）：${host ?: url}"
            )
        }
    }

    /** 解析 `Content-Range: bytes START-END/TOTAL` 的 START；格式不符返回 null（视为不可续传） */
    internal fun contentRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
            .find(header) ?: return null
        return match.groupValues[1].toLongOrNull()
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
