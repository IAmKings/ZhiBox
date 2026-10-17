package com.zhibox.memory.core.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份包结构校验与 `vectors.bin` 段编解码（P0-1 / P1-7）。
 *
 * 设计约束：**纯 JVM**（只用 java.util.zip / java.nio，不碰 Android / Room / org.json），
 * 这样 `testDebugUnitTest` 能直接覆盖"坏包/截断/超限/端序/v1 兼容"等安全关键路径——
 * 备份是 P0 数据安全项，校验器本身不能再依赖真机才能验证。
 *
 * 职责边界：
 * - 本对象只做"字节层面的结构校验"（entry 尺寸上限、format_version 门、向量头/行/引用/重复校验）；
 * - 语义映射（JSON → Entity）在 KnowledgeBackup 内完成，本对象不认识业务字段。
 *
 * 格式版本：
 * - v1（历史包）：`meta.json` 空间表只有 5 个字段，向量行 = `spaceIdx:i16 | chunkId:i64 | len:i32 | blob`；
 * - v2（当前导出）：空间表补全 embedding_meta 全字段、sources/documents/chunks 补字段、新增 app_kv，
 *   向量行 = `spaceIdx:i16 | chunkId:i64 | len:i32 | roleLen:i16 | role | vectorNorm:f64 | createdAt:i64 | blob`。
 *   读取端按包内 format_version 选择行布局，以便仍能恢复 v1 包（缺失字段用默认值）。
 */
internal object BackupValidator {

    /** 当前导出版本；与 [KnowledgeBackup.FORMAT_VERSION] 保持一致 */
    const val FORMAT_VERSION = 2

    /** 可读取的最低版本（v1 兼容） */
    const val MIN_SUPPORTED_FORMAT = 1

    // ---- zip bomb 防护上限（P1-7） ----
    /** 单 entry 解压尺寸上限：向量段 = chunk 数 × dim × 4，万级 chunk 约 30MB，512MB 留足余量 */
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    /** 整包解压总尺寸上限 */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    /** entry 数量上限（正常包固定 6–7 个 entry） */
    const val MAX_ENTRIES = 64
    /** 声明向量行数上限（防损坏头导致 ArrayList 预分配 OOM） */
    const val MAX_VECTORS = 2_000_000
    /** 空间数上限 */
    const val MAX_SPACES = 256
    /** 单空间维度上限（现有模型 768/1024，留足演进余量） */
    const val MAX_DIMENSIONS = 16_384
    private const val MAX_ROLE_BYTES = 64
    private const val READ_BUFFER = 64 * 1024

    // 已知 entry 名单的单一事实源（KnowledgeBackup.ENTRY_* 引用此处，避免两处重复维护）
    const val ENTRY_META = "meta.json"
    const val ENTRY_REQUIREMENTS = "model_requirements.json"
    const val ENTRY_SOURCES = "sources.jsonl"
    const val ENTRY_DOCUMENTS = "documents.jsonl"
    const val ENTRY_CHUNKS = "chunks.jsonl"
    const val ENTRY_APP_KV = "app_kv.jsonl"
    const val ENTRY_VECTORS = "vectors.bin"

    /** 已知 entry 名（不在名单内的 entry 一律跳过——但不跳过尺寸统计，防未知 entry 绕过 zip bomb 防护） */
    val KNOWN_ENTRIES = setOf(
        ENTRY_META, ENTRY_REQUIREMENTS, ENTRY_SOURCES, ENTRY_DOCUMENTS,
        ENTRY_CHUNKS, ENTRY_APP_KV, ENTRY_VECTORS,
    )

    /** 可注入的限额（单测用小限额覆盖"超限"分支，避免真的分配 512MB） */
    data class Limits(
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        val maxEntries: Int = MAX_ENTRIES,
    ) {
        companion object {
            val DEFAULT = Limits()
        }
    }

    /** 向量段头/行解码所需的空件元数据（来自 meta.json 的 spaces） */
    data class SpaceSpec(val spaceId: String, val dimensions: Int)

    /** 单个 entry 的读取结果：bytes 仅 keep=true 时非空（未知 entry 只消费字节数，防 zip bomb 绕过） */
    data class EntryBytes(val bytes: ByteArray, val size: Long)

    /** 解码后的一行向量（已通过全部结构校验） */
    data class VectorRow(
        val spaceId: String,
        val chunkId: Long,
        val role: String,
        val vectorNorm: Double,
        val createdAt: Long,
        val blob: ByteArray,
    )

    /** 向量段解码结果：declaredCount = 包内声明行数（UI/预览口径），rows = 实际写入行（可丢弃 GPU 空间） */
    data class VectorPayload(
        val declaredCount: Int,
        val rows: List<VectorRow>,
        val droppedCount: Int,
    )

    // ---- 格式版本门 ----

    /**
     * 版本门（P1-7）：v1 兼容读取、v2 当前，其它一律拒绝。
     * 之前 `format_version` 只展示不校验，未来格式升级会被旧代码静默误读。
     */
    fun checkFormatVersion(formatVersion: Int) {
        check(formatVersion in MIN_SUPPORTED_FORMAT..FORMAT_VERSION) {
            "备份包格式版本 $formatVersion 不受支持（当前支持 $MIN_SUPPORTED_FORMAT–$FORMAT_VERSION）；" +
                "请使用匹配版本的应用导出/导入"
        }
    }

    // ---- zip 读取（尺寸/数量上限） ----

    /**
     * 流式读完整包，返回"已知 entry → 字节"。
     *
     * 所有 entry（含未知 entry）都计入数量/总尺寸预算：恶意包可以把炸弹藏在未知 entry 里，
     * 只对已知 entry 限流等于没限。未知 entry 读完即丢（只计数，不保留字节）。
     */
    fun readArchive(zip: ZipInputStream, limits: Limits = Limits.DEFAULT): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0
        var entry = zip.nextEntry
        while (entry != null) {
            entryCount++
            check(entryCount <= limits.maxEntries) {
                "备份包 entry 数量超过上限（${limits.maxEntries}）——不是有效的知识库导出包"
            }
            if (!entry.isDirectory) {
                val keep = entry.name in KNOWN_ENTRIES
                check(!entries.containsKey(entry.name)) { "备份包存在重复 entry：${entry.name}" }
                val read = readEntryBounded(zip, entry, limits, totalBytes, keep = keep)
                totalBytes += read.size
                if (keep) entries[entry.name] = read.bytes
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return entries
    }

    /**
     * 读取单个 entry；超过单 entry / 整包上限立即抛可读错误（zip bomb 防护）。
     * [totalBytesSoFar] 用于整包预算；[keep] 为 false 时只消费字节不保留（未知 entry），
     * 但仍返回实际读取字节数供调用方累计总预算。
     */
    fun readEntryBounded(
        zip: ZipInputStream,
        entry: ZipEntry,
        limits: Limits = Limits.DEFAULT,
        totalBytesSoFar: Long = 0L,
        keep: Boolean = true,
    ): EntryBytes {
        // ZipInputStream 的 entry.size 在流式模式常为 -1（本地头无尺寸），不能作为唯一依据；
        // 真正防炸弹靠读取时逐块计数。
        if (entry.size > limits.maxEntryBytes) {
            throw illegalEntryTooLarge(entry.name, entry.size, limits.maxEntryBytes)
        }
        val out = if (keep) ByteArrayOutputStream(minOf(maxOf(entry.size, 8L), 1L shl 20).toInt()) else null
        val buffer = ByteArray(READ_BUFFER)
        var read = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            read += n
            if (read > limits.maxEntryBytes) {
                throw illegalEntryTooLarge(entry.name, read, limits.maxEntryBytes)
            }
            check(totalBytesSoFar + read <= limits.maxTotalBytes) {
                "备份包解压总尺寸超过上限（${limits.maxTotalBytes / 1024 / 1024}MB）——不是有效的知识库导出包"
            }
            out?.write(buffer, 0, n)
        }
        return EntryBytes(out?.toByteArray() ?: ByteArray(0), read)
    }

    private fun illegalEntryTooLarge(name: String, actual: Long, limit: Long): IllegalStateException =
        IllegalStateException(
            "备份包 entry $name 解压尺寸 ${actual / 1024 / 1024}MB 超过上限 ${limit / 1024 / 1024}MB（zip bomb 防护）"
        )

    // ---- vectors.bin 编解码 ----

    fun encodeVectorHeader(count: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array()

    /** 只读头（preview 用）：LE 读取，count 合法性校验 */
    fun readVectorCount(payload: ByteArray): Int {
        check(payload.size >= 4) { "vectors.bin 头部截断（实际 ${payload.size}B，至少需要 4B）" }
        val count = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        check(count in 0..MAX_VECTORS) {
            "vectors.bin 声明向量数非法：$count（上限 $MAX_VECTORS）——备份包已损坏"
        }
        return count
    }

    /** 编码一行向量（导出端）；v1 布局仅保留给测试/兼容，正式导出只写 v2 */
    fun encodeVectorRow(
        spaceIdx: Int,
        chunkId: Long,
        blob: ByteArray,
        formatVersion: Int = FORMAT_VERSION,
        role: String = "content",
        vectorNorm: Double = 1.0,
        createdAt: Long = 0L,
    ): ByteArray {
        require(spaceIdx in 0..Short.MAX_VALUE) { "空间索引越界：$spaceIdx" }
        val roleBytes = role.toByteArray(Charsets.UTF_8)
        require(roleBytes.isNotEmpty() && roleBytes.size <= MAX_ROLE_BYTES) { "角色名长度非法：${roleBytes.size}" }
        val header = 2 + 8 + 4
        val extra = if (formatVersion >= 2) 2 + roleBytes.size + 8 + 8 else 0
        val buf = ByteBuffer.allocate(header + extra + blob.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(spaceIdx.toShort())
        buf.putLong(chunkId)
        buf.putInt(blob.size)
        if (formatVersion >= 2) {
            buf.putShort(roleBytes.size.toShort())
            buf.put(roleBytes)
            buf.putDouble(vectorNorm)
            buf.putLong(createdAt)
        }
        buf.put(blob)
        return buf.array()
    }

    /**
     * 解码 + 完整语义校验（P0-1 核心）：**先全部校验通过，调用方才允许写库**。
     *
     * 校验项：
     * 1. 头 count ∈ [0, MAX_VECTORS]；
     * 2. 逐行：空间索引合法、blob 长度 == dimensions×4、chunkId 在 chunks.jsonl 中存在、
     *    (chunkId, space, role) 不重复、norm 有限；
     * 3. 行数与声明 count 一致、尾部无多余字节（截断/多写都能发现）。
     *
     * @param collectRows false 时只校验不保留行（preview 省内存）；import 必须 true。
     */
    fun decodeVectors(
        payload: ByteArray,
        formatVersion: Int,
        spaces: List<SpaceSpec>,
        chunkIds: Set<Long>,
        droppedSpaceIds: Set<String> = emptySet(),
        collectRows: Boolean = true,
    ): VectorPayload {
        check(spaces.size <= MAX_SPACES) { "备份包空间数超过上限（${MAX_SPACES}）" }
        spaces.forEach {
            check(it.dimensions in 1..MAX_DIMENSIONS) {
                "备份包空间 ${it.spaceId} 维度非法：${it.dimensions}（合法 1..$MAX_DIMENSIONS）"
            }
        }
        val declared = readVectorCount(payload)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // 跳过已校验的头
        val rows = if (collectRows) ArrayList<VectorRow>(minOf(declared, 4096)) else null
        val seen = HashSet<String>(minOf(declared, 4096).coerceAtLeast(16))
        var dropped = 0
        for (i in 0 until declared) {
            val rowNo = i + 1
            ensureRemaining(buf, 2 + 8 + 4, rowNo, declared)
            val spaceIdx = buf.short.toInt()
            check(spaceIdx in spaces.indices) {
                "vectors.bin 第 $rowNo 行空间索引 $spaceIdx 越界（空间数 ${spaces.size}）——备份包损坏"
            }
            val spec = spaces[spaceIdx]
            val chunkId = buf.long
            val len = buf.int
            val expectedLen = spec.dimensions * 4
            check(len == expectedLen) {
                "vectors.bin 第 $rowNo 行向量长度 ${len}B 与空间 ${spec.spaceId} 维度 " +
                    "${spec.dimensions}×4=${expectedLen}B 不符——备份包损坏或空间元数据不一致"
            }
            var role = "content"
            var vectorNorm = 1.0
            var createdAt = 0L
            if (formatVersion >= 2) {
                ensureRemaining(buf, 2, rowNo, declared)
                val roleLen = buf.short.toInt()
                check(roleLen in 1..MAX_ROLE_BYTES) {
                    "vectors.bin 第 $rowNo 行角色长度非法：$roleLen"
                }
                ensureRemaining(buf, roleLen + 8 + 8, rowNo, declared)
                val roleBytes = ByteArray(roleLen)
                buf.get(roleBytes)
                role = String(roleBytes, Charsets.UTF_8)
                vectorNorm = buf.double
                createdAt = buf.long
                check(vectorNorm.isFinite()) {
                    "vectors.bin 第 $rowNo 行向量范数非法：$vectorNorm"
                }
            }
            check(chunkId in chunkIds) {
                "vectors.bin 第 $rowNo 行 chunkId=$chunkId 在 chunks.jsonl 中不存在（引用校验失败）"
            }
            check(seen.add("$spaceIdx|$chunkId|$role")) {
                "vectors.bin 第 $rowNo 行重复：chunkId=$chunkId / space=${spec.spaceId} / role=$role"
            }
            ensureRemaining(buf, len, rowNo, declared)
            if (spec.spaceId in droppedSpaceIds) {
                dropped++
                buf.position(buf.position() + len)
                continue
            }
            if (rows != null) {
                val blob = ByteArray(len)
                buf.get(blob)
                rows.add(VectorRow(spec.spaceId, chunkId, role, vectorNorm, createdAt, blob))
            } else {
                buf.position(buf.position() + len)
            }
        }
        check(!buf.hasRemaining()) {
            "vectors.bin 尾部还有 ${buf.remaining()}B 多余数据（声明 $declared 行）——备份包损坏或版本不匹配"
        }
        return VectorPayload(declared, rows ?: emptyList(), dropped)
    }

    private fun ensureRemaining(buf: ByteBuffer, need: Int, rowNo: Int, declared: Int) {
        check(buf.remaining() >= need) {
            "vectors.bin 截断：读第 $rowNo/$declared 行还需 ${need}B，实际剩余 ${buf.remaining()}B"
        }
    }
}

/**
 * 模型在位判定（P1-7 ③）：把"包内声明的模型在本机哪个候选路径可用"的纯逻辑抽出来，
 * preview / import 共用，并可 JVM 单测。
 *
 * 为什么不能固定查 `filesDir/models/$file`：共享模型仓"启用"后文件落在 `cacheDir/models-link`，
 * 旧判定会对明明可用的模型判 missing → 合法备份被 blocked、永远无法导入（false-negative）；
 * 未收录进 catalog 的老模型（catalog_file 为 null）更是完全无路可走。
 */
internal object BackupModelPresence {

    /**
     * @param catalogFile 包内 `catalog_file`（v1 包可能缺失/为 null）
     * @param modelId 包内 `modelId`（catalog_file 缺失时按文件名去后缀回退匹配）
     * @param candidates `PathPreferences.candidates(context)` 的结果（filesDir/models + cacheDir/models-link）
     */
    fun find(catalogFile: String?, modelId: String, candidates: List<File>): File? {
        catalogFile?.takeIf { it.isNotBlank() }?.let { fileName ->
            return candidates.firstOrNull { it.name == fileName }
        }
        if (modelId.isBlank()) return null
        return candidates.firstOrNull {
            val stem = it.name.removeSuffix(".litertlm").removeSuffix(".task")
            stem == modelId || stem.startsWith(modelId) || modelId.startsWith(stem)
        }
    }
}
