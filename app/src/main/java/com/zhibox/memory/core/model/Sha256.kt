package com.zhibox.memory.core.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 唯一实现（B4-9：统一此前分散在 `ModelIntegrity` / `ModelFingerprint` /
 * `IngestRepository` 的三份流式哈希）。
 *
 * 约定：
 * - 输出**小写 hex**（与 catalog / 备份格式 / 去重 checksum 的既有口径一致）；
 * - 文件与大流一律 64KB 分块流式计算，不把整文件读入内存（模型 300–500MB）；
 * - [hex] 接受外部 [InputStream] 时**不关闭**它（生命周期由调用方管理，便于 SAF
 *   `DocumentFile` 流与 `use {}` 复用）；
 * - 增量场景用 [newDigest] 自行 `update`，最后用 `hex(digest)` 收尾（`digest()` 会 reset，
 *   一次性消费；需要再算请重新 [newDigest]）。
 *
 * 线程：本对象为纯阻塞 IO，调用方负责放在 IO/Default 线程（例如 `ModelIntegrity.verify`
 * 已在 `Dispatchers.IO` 内、`IngestRepository.importFile` 在 IO 上下文调用）。
 */
object Sha256 {

    /** 流式分块大小：64KB（与历史三份实现的最小公共口径一致） */
    const val BUFFER_BYTES: Int = 64 * 1024

    const val ALGORITHM: String = "SHA-256"

    /** 新建 digest（增量入口；调用方自行 update，最终交给 [hex]） */
    fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    /** 一次性字节数组哈希（小数据/测试向量用；大文件请用 [hex(File)]） */
    fun hex(bytes: ByteArray): String {
        val digest = newDigest()
        digest.update(bytes)
        return hex(digest)
    }

    /** 文件流式哈希（64KB 分块，不整文件入内存）。文件不存在/不可读时抛 IOException。 */
    fun hex(file: File): String =
        file.inputStream().buffered(BUFFER_BYTES).use { input -> hex(input) }

    /**
     * InputStream 流式哈希；**不关闭** [input]（调用方负责关闭）。
     * 适合 SAF/网络流等无法交给 [hex(File)] 的场景。
     */
    fun hex(input: InputStream): String {
        val digest = newDigest()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return hex(digest)
    }

    /**
     * 收尾增量 digest → 小写 hex（**一次性**：`MessageDigest.digest()` 会 reset）。
     * 手写 hex 避免 `String.format` 的 Locale/性能开销（B4-3 同类治理）。
     */
    fun hex(digest: MessageDigest): String {
        val bytes = digest.digest()
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }
}
