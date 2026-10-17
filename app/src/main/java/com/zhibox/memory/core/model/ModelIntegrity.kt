package com.zhibox.memory.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 模型文件完整性统一校验（P1-7 / P1-10）。
 *
 * 为什么需要统一入口：此前完整性校验只存在于 `ModelDownloader.download(expectedSha256=...)` 这一路；
 * SAF 共享目录启用（`SharedModelStore.enable`）只比长度、共享目录"同名等长"直接跳过下载、
 * 备份导入只做 1% 容差尺寸判定——**同长文件不可信**（SAF 目录可被其他应用/用户替换），
 * 而模型一旦被替换，MediaPipe/LiteRT native 解析行为不可预测（已知会挂死/崩溃），
 * 向量空间语义也会在无感知的情况下错位。本对象把"流式 SHA-256 + 精确尺寸"收敛为唯一实现。
 *
 * 调用时机（三个链路统一）：
 * 1. `SharedModelStore.enable` 复用 models-link 缓存前 / 复制完成后；
 * 2. `SharedModelStore.downloadEntry` 判定"共享目录已存在同名文件可跳过"前；
 * 3. `KnowledgeBackup.import` 写库前的模型在位校验（`catalog_sha256` 真正参与比对）。
 */
object ModelIntegrity {

    /**
     * 校验 [file] 是否与期望的 SHA-256 / 尺寸一致。
     *
     * - [expectedSha256] 为 null/空白：跳过哈希（用于未收录进 catalog 的历史模型，此时只能校验尺寸）；
     * - [expectedSize] 为 null 或 <=0：跳过尺寸（未知期望值时不误伤）。
     * 失败返回 `Result.failure` 且 message 为可直接展示给用户的中文原因。
     */
    suspend fun verify(file: File, expectedSha256: String?, expectedSize: Long?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(file.isFile) { "模型文件不存在：${file.name}" }
                if (expectedSize != null && expectedSize > 0) {
                    val actual = file.length()
                    check(actual == expectedSize) {
                        "模型尺寸不符：${file.name} 期望 ${expectedSize}B，实际 ${actual}B（文件损坏或被替换，请重新下载）"
                    }
                }
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = sha256Hex(file)
                    check(actual.equals(expectedSha256, ignoreCase = true)) {
                        "模型 SHA-256 不符：${file.name} 期望 ${expectedSha256.lowercase(Locale.ROOT)}，实际 $actual" +
                            "（文件损坏或被替换，请重新下载）"
                    }
                }
            }
        }

    /**
     * 流式 SHA-256（SAF 共享目录拿不到可随机读的 File，只能开 InputStream）。
     * 阻塞式实现：调用方必须已在 IO 线程上（本类不做线程切换以免在 `verify` 内重复切线程）。
     *
     * B4-9：唯一实现收敛到 [Sha256]（64KB 分块、小写 hex）；本方法保留原 API 委托调用。
     */
    fun sha256Hex(file: File): String = Sha256.hex(file)

    /** 流式 SHA-256；不关闭 [input]（由调用方 use 管理生命周期）。B4-9 委托 [Sha256]。 */
    fun sha256Hex(input: InputStream): String = Sha256.hex(input)

    /**
     * 对已打开的流做完整性校验（SAF 场景）：尺寸由调用方用 DocumentFile.length() 另行核对，
     * 这里补上之前完全缺失的 SHA-256 比对。
     */
    suspend fun verifyStream(input: InputStream, expectedSha256: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = sha256Hex(input)
                    check(actual.equals(expectedSha256, ignoreCase = true)) {
                        "模型 SHA-256 不符：期望 ${expectedSha256.lowercase(Locale.ROOT)}，实际 $actual（文件损坏或被替换，请重新下载）"
                    }
                }
            }
        }
}
