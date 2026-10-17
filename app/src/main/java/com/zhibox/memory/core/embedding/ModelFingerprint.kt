package com.zhibox.memory.core.embedding

import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.Sha256
import java.io.File

/**
 * 模型文件指纹（P1-6）：为 `EmbeddingDescriptor.modelRevision` 提供可复现的权重版本号。
 *
 * 语义（与 [EmbeddingDescriptor.modelRevision] 注释一致）：
 * - **已收录模型优先取目录 SHA-256 前 12 位**——零 IO、确定性、且与下载校验同源；
 * - 未收录模型（用户自投放/改名）流式计算文件 SHA-256 前 12 位；
 * - 同名换文件检测：已收录但实际文件尺寸与目录不符（容差 1%，见 [ModelCatalog.sizeMatches]）
 *   时不再信任目录 SHA（视为"同名新版本"），改算实际文件哈希——否则同名覆盖的模型
 *   会与旧向量静默混用同一空间（P1-6）。
 *
 * 成本：未收录文件需完整读一遍（388MB 约 1-2s），结果按 `path|size|mtime` 缓存在
 * 进程内 ConcurrentHashMap（模型文件替换后 size/mtime 变化即重算）。Provider 构造在
 * [EmbeddingRuntime.withProvider] 的 `Dispatchers.Default` 上执行，不阻塞 Main。
 */
object ModelFingerprint {

    /** 指纹长度（与 descriptor 注释"文件 SHA-256 前 12 位"一致） */
    const val REVISION_LENGTH = 12

    /** 负结果哨兵：ConcurrentHashMap 不接受 null 值；空串 = 无法计算（文件缺失/IO 失败） */
    private const val UNAVAILABLE = ""

    /** key = "$absolutePath|$size|$mtime"；value = 12 位 hex 或 [UNAVAILABLE] */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 返回模型文件的 revision（SHA-256 前 12 位小写 hex）。
     * - 已收录且尺寸吻合（或文件暂不可读）→ 信任 catalog 声明的供应链哈希（无 IO 开销）；
     * - 未收录 / 同名换文件（尺寸不符）→ 对实际内容流式计算；文件不可读时返回 null。
     */
    fun revision(file: File): String? {
        val catalog = ModelCatalog.byFileName(file.name)
        if (catalog != null && (!file.isFile || ModelCatalog.sizeMatches(catalog, file))) {
            // 目录登记文件：信任供应链哈希（下载/启用/备份导入均按此值校验）
            return catalog.sha256.take(REVISION_LENGTH)
        }
        // 未收录或同名换文件：以实际内容为准
        val cacheKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val cached = cache[cacheKey]
        if (cached != null) return cached.takeIf { it != UNAVAILABLE }
        val computed = try {
            // B4-9：流式 SHA-256 统一走 Sha256（64KB 分块，不关闭调用方流）
            if (file.isFile) Sha256.hex(file).take(REVISION_LENGTH) else null
        } catch (_: Exception) {
            null
        }
        cache[cacheKey] = computed ?: UNAVAILABLE
        return computed
    }

    /** 仅供单测：清空进程内缓存（生产代码不需要） */
    internal fun clearCacheForTest() = cache.clear()
}
