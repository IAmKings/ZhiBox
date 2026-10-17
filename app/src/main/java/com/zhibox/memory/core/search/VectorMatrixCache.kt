package com.zhibox.memory.core.search

import androidx.annotation.VisibleForTesting

/**
 * 进程内向量矩阵缓存（P1-5 + B2-10 上限/LRU）：避免每次检索都从 Room 全量读取 BLOB 并解码。
 *
 * - 键 = (spaceId, rowCount, maxVectorId)：三者任一变化即视为新矩阵。rowCount 覆盖
 *   新增/删除/文档 ready↔failed 迁移，maxVectorId 覆盖重嵌入/替换写入（v4 索引
 *   `(embeddingSpaceId, role)` 让签名查询只扫索引不读 BLOB）；
 * - 写路径（导入完成、失败清理、重嵌入每批）调用 [invalidate] 主动失效，签名只是兜底；
 * - **B2-10 容量上限**：默认 [DEFAULT_MAX_BYTES]（64MB，约等于 2×10k×768 矩阵），
 *   超出时按 LRU 淘汰最久未使用空间；**单个矩阵本身超过上限则不缓存**（put 返回 false）——
 *   调用方本次仍可用刚构建的矩阵完成检索，但下一次查询会重新载入（等价于 P1-5 之前的
 *   “每次扫描”，100k 级库有 OOM 风险但不会因缓存常驻把进程拖死；分片/流式扫描见 B5-3）；
 * - 矩阵/chunkIds 为进程级共享只读数组，[SpaceMatrix] 发布后不得原地修改。
 */
internal data class SpaceMatrix(
    val spaceId: String,
    val rowCount: Int,
    val maxVectorId: Long,
    val dim: Int,
    val chunkIds: LongArray,
    val matrix: FloatArray,
) {
    /** 缓存占用估算（只算数组载荷）：matrix FloatArray + chunkIds LongArray */
    val byteSize: Long
        get() = matrix.size.toLong() * 4 + chunkIds.size.toLong() * 8
}

internal object VectorMatrixCache {

    /** B2-10 默认缓存上限：64MB */
    const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

    /** 空间条目数上限（字节上限之外的硬边界，避免极小矩阵堆积） */
    private const val MAX_ENTRIES = 4

    private var maxBytes: Long = DEFAULT_MAX_BYTES
    private var totalBytes: Long = 0

    /** 头部 = 最近使用（LRU；[get] 命中会移到头部） */
    private val entries = ArrayDeque<SpaceMatrix>()

    @Synchronized
    fun get(spaceId: String, rowCount: Int, maxVectorId: Long): SpaceMatrix? {
        val index = entries.indexOfFirst {
            it.spaceId == spaceId && it.rowCount == rowCount && it.maxVectorId == maxVectorId
        }
        if (index < 0) return null
        if (index == 0) return entries[0]
        // LRU：命中后移到头部
        return entries.removeAt(index).also { entries.addFirst(it) }
    }

    /**
     * 写入缓存（同空间旧签名被替换，LRU 淘汰直到满足字节/条目上限）。
     * @return true=已缓存；false=单矩阵超过 [DEFAULT_MAX_BYTES] 上限，**未缓存**（调用方本次仍可用）
     */
    @Synchronized
    fun put(matrix: SpaceMatrix): Boolean {
        removeSpace(matrix.spaceId)
        val bytes = matrix.byteSize
        if (bytes > maxBytes) return false // 单矩阵超上限：不常驻，避免大库把进程内存拖死
        entries.addFirst(matrix)
        totalBytes += bytes
        while (entries.isNotEmpty() && (entries.size > MAX_ENTRIES || totalBytes > maxBytes)) {
            val evicted = entries.removeLast()
            totalBytes -= evicted.byteSize
        }
        return true
    }

    /** 写路径主动失效；[spaceId] 为 null 时清空（全库替换/备份导入） */
    @Synchronized
    fun invalidate(spaceId: String? = null) {
        if (spaceId == null) {
            entries.clear()
            totalBytes = 0
        } else {
            removeSpace(spaceId)
        }
    }

    private fun removeSpace(spaceId: String) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.spaceId == spaceId) {
                iterator.remove()
                totalBytes -= entry.byteSize
            }
        }
    }

    // ---- 仅供单测：注入更小的上限/重置（生产代码不调用） ----

    @Synchronized
    @VisibleForTesting
    internal fun setMaxBytesForTest(bytes: Long) {
        require(bytes > 0) { "max cache bytes must be positive" }
        maxBytes = bytes
        evictToLimit()
    }

    @Synchronized
    @VisibleForTesting
    internal fun resetForTest() {
        maxBytes = DEFAULT_MAX_BYTES
        entries.clear()
        totalBytes = 0
    }

    @Synchronized
    @VisibleForTesting
    internal fun cachedBytesForTest(): Long = totalBytes

    @Synchronized
    @VisibleForTesting
    internal fun cachedCountForTest(): Int = entries.size

    private fun evictToLimit() {
        while (entries.isNotEmpty() && (entries.size > MAX_ENTRIES || totalBytes > maxBytes)) {
            val evicted = entries.removeLast()
            totalBytes -= evicted.byteSize
        }
    }
}
