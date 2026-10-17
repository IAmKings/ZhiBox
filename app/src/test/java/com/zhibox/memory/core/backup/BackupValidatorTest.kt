package com.zhibox.memory.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包校验器单测（P0-1 / P1-7）：纯 JVM，不依赖 Android/Robolectric。
 *
 * 覆盖 DoD 要求的"坏包 / 截断 / 超限 / 端序 / v1 兼容"：
 * - 端序：vectors.bin 头必须按 LE 读（旧 preview 用默认 BIG_ENDIAN，count=1 显示 16777216）；
 * - 截断/尾随/长度不符/引用缺失/空间越界/重复行：任一不通过都必须在写库前失败；
 * - 尺寸/数量上限：zip bomb 防护；
 * - v1 兼容：旧包向量行没有 role/norm/createdAt，按默认值解析。
 * - B1-9 引用闭包：导出只带 ready 文档及其 sources/chunks/vectors，子表无孤儿；导入端
 *   `BackupClosure.validateImportClosure` 对缺父行报可读错误（向量段两条引用在 decodeVectors 校验）。
 *
 * 注：JSON 字段的 v1 默认值（source.uri=""、document.sizeBytes=0、app_kv 缺失）在
 * KnowledgeBackup 的 JSON 映射层，依赖 org.json（Android 桩在 JVM 单测不可用），
 * 因此这里覆盖的是同样决定 v1 兼容性的**向量段行布局版本**与格式版本门。
 */
class BackupValidatorTest {

    // ---- 端序 ----

    @Test
    fun `向量头按小端编码与读取`() {
        val header = BackupValidator.encodeVectorHeader(3)
        // LE：3 → 03 00 00 00（若按 BE 读会得到 50331648，正是旧 preview 的 bug）
        assertEquals(listOf<Byte>(3, 0, 0, 0), header.toList())
        assertEquals(3, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(3, BackupValidator.readVectorCount(header))
        // 旧实现按默认 BIG_ENDIAN 读同一字节 → 反向验证端序差异确实存在
        assertEquals(50331648, ByteBuffer.wrap(header).int)
    }

    @Test
    fun `preview 只读头不解析行`() {
        val payload = BackupValidator.encodeVectorHeader(9) + ByteArray(13) { 0x7f }
        assertEquals(9, BackupValidator.readVectorCount(payload))
    }

    @Test
    fun `向量头截断与非法 count 报错`() {
        assertTrue(expectThrows<IllegalStateException> { BackupValidator.readVectorCount(byteArrayOf(1, 0)) }
            .message!!.contains("截断"))
        assertTrue(
            expectThrows<IllegalStateException> {
                BackupValidator.readVectorCount(BackupValidator.encodeVectorHeader(-1))
            }.message!!.contains("非法")
        )
        assertTrue(
            expectThrows<IllegalStateException> {
                BackupValidator.readVectorCount(BackupValidator.encodeVectorHeader(BackupValidator.MAX_VECTORS + 1))
            }.message!!.contains("非法")
        )
    }

    // ---- v2 行编解码往返 ----

    @Test
    fun `v2 向量行往返保留 role norm 与 createdAt`() {
        val blob = ByteArray(8) { it.toByte() } // 2 维模型：2*4=8B
        val row = BackupValidator.encodeVectorRow(
            spaceIdx = 0, chunkId = 42L, blob = blob,
            formatVersion = 2, role = "heading", vectorNorm = 0.5, createdAt = 1234L,
        )
        val payload = BackupValidator.encodeVectorHeader(1) + row
        val result = BackupValidator.decodeVectors(
            payload = payload, formatVersion = 2,
            spaces = listOf(BackupValidator.SpaceSpec("space-a", 2)),
            chunkIds = setOf(42L, 43L),
        )
        assertEquals(1, result.declaredCount)
        assertEquals(1, result.rows.size)
        val r = result.rows.first()
        assertEquals("space-a", r.spaceId)
        assertEquals(42L, r.chunkId)
        assertEquals("heading", r.role)
        assertEquals(0.5, r.vectorNorm, 0.0)
        assertEquals(1234L, r.createdAt)
        assertTrue(blob.contentEquals(r.blob))
    }

    // ---- v1 兼容 ----

    @Test
    fun `v1 包行布局兼容（缺失 role norm createdAt 用默认值）`() {
        val payload = ByteBuffer.allocate(4 + 2 + 8 + 4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1) // 头 count
            .putShort(0) // spaceIdx
            .putLong(7L) // chunkId
            .putInt(8) // len
            .put(ByteArray(8) { 0 }) // blob
            .array()
        val result = BackupValidator.decodeVectors(
            payload = payload, formatVersion = 1,
            spaces = listOf(BackupValidator.SpaceSpec("legacy-space", 2)),
            chunkIds = setOf(7L),
        )
        assertEquals(1, result.rows.size)
        val r = result.rows.first()
        assertEquals("content", r.role)
        assertEquals(1.0, r.vectorNorm, 0.0)
        assertEquals(0L, r.createdAt)
    }

    @Test
    fun `格式版本门 v1 v2 通过 更高版本与 0 拒绝`() {
        BackupValidator.checkFormatVersion(1)
        BackupValidator.checkFormatVersion(2)
        assertTrue(expectThrows<IllegalStateException> { BackupValidator.checkFormatVersion(3) }
            .message!!.contains("不受支持"))
        assertTrue(expectThrows<IllegalStateException> { BackupValidator.checkFormatVersion(0) }
            .message!!.contains("不受支持"))
    }

    // ---- 损坏包 / 截断 / 引用校验 ----

    @Test
    fun `声明 2 行但只有 1 行数据报截断`() {
        val oneRow = BackupValidator.encodeVectorRow(0, 1L, ByteArray(4), 2)
        val payload = BackupValidator.encodeVectorHeader(2) + oneRow
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(payload, 2, listOf(BackupValidator.SpaceSpec("s", 1)), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("截断"))
    }

    @Test
    fun `尾部多余字节报错`() {
        val oneRow = BackupValidator.encodeVectorRow(0, 1L, ByteArray(4), 2)
        val payload = BackupValidator.encodeVectorHeader(1) + oneRow + ByteArray(3)
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(payload, 2, listOf(BackupValidator.SpaceSpec("s", 1)), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("多余"))
    }

    @Test
    fun `blob 长度必须等于维度乘 4`() {
        // 声明 len=4，但空间维度 1 → 期望 1*4=4；这里故意写 8 触发不一致
        val payload = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 1L, ByteArray(8), 2)
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(payload, 2, listOf(BackupValidator.SpaceSpec("s", 1)), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("不符"))
    }

    @Test
    fun `chunkId 引用不存在报错`() {
        val payload = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 99L, ByteArray(4), 2)
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(payload, 2, listOf(BackupValidator.SpaceSpec("s", 1)), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("不存在"))
    }

    @Test
    fun `空间索引越界报错`() {
        val payload = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 1L, ByteArray(4), 2)
        val e = expectThrows<IllegalStateException> {
            // 空间表为空 → 索引 0 越界
            BackupValidator.decodeVectors(payload, 2, emptyList(), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("越界"))
    }

    @Test
    fun `重复向量行报错`() {
        val row = BackupValidator.encodeVectorRow(0, 1L, ByteArray(4), 2)
        val payload = BackupValidator.encodeVectorHeader(2) + row + row
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(payload, 2, listOf(BackupValidator.SpaceSpec("s", 1)), setOf(1L))
        }
        assertTrue(e.message!!, e.message!!.contains("重复"))
    }

    @Test
    fun `空间维度非法在解码前拒绝`() {
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(
                BackupValidator.encodeVectorHeader(0), 2,
                listOf(BackupValidator.SpaceSpec("s", 0)), emptySet(),
            )
        }
        assertTrue(e.message!!, e.message!!.contains("维度非法"))
    }

    // ---- 丢弃不兼容 GPU 空间 ----

    @Test
    fun `dropped 空间只计数不产出写入行`() {
        val payload = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 1L, ByteArray(4), 2)
        val result = BackupValidator.decodeVectors(
            payload, 2, listOf(BackupValidator.SpaceSpec("space-gpu", 1)),
            setOf(1L), droppedSpaceIds = setOf("space-gpu"),
        )
        assertEquals(1, result.declaredCount)
        assertEquals(0, result.rows.size)
        assertEquals(1, result.droppedCount)
    }

    // ---- zip bomb 防护（entry 尺寸 / 数量 / 总量上限） ----

    @Test
    fun `单 entry 解压尺寸超限报错`() {
        val zip = zipOf("vectors.bin" to ByteArray(200))
        val e = expectThrows<IllegalStateException> {
            zip.use {
                BackupValidator.readArchive(
                    it,
                    BackupValidator.Limits(maxEntryBytes = 64, maxTotalBytes = 1_000_000, maxEntries = 8),
                )
            }
        }
        assertTrue(e.message!!, e.message!!.contains("zip bomb"))
    }

    @Test
    fun `entry 数量超限报错`() {
        val zip = zipOf("a" to ByteArray(1), "b" to ByteArray(1))
        val e = expectThrows<IllegalStateException> {
            zip.use { BackupValidator.readArchive(it, BackupValidator.Limits(maxEntries = 1)) }
        }
        assertTrue(e.message!!, e.message!!.contains("entry 数量"))
    }

    @Test
    fun `整包解压总尺寸超限报错`() {
        val zip = zipOf("a" to ByteArray(8), "b" to ByteArray(8))
        val e = expectThrows<IllegalStateException> {
            zip.use {
                BackupValidator.readArchive(
                    it,
                    BackupValidator.Limits(maxEntryBytes = 100, maxTotalBytes = 10, maxEntries = 8),
                )
            }
        }
        assertTrue(e.message!!, e.message!!.contains("总尺寸"))
    }

    @Test
    fun `未知 entry 也计入总量预算（炸弹不能藏未知 entry）`() {
        val zip = zipOf("payload.bin" to ByteArray(16))
        val e = expectThrows<IllegalStateException> {
            zip.use {
                BackupValidator.readArchive(
                    it,
                    BackupValidator.Limits(maxEntryBytes = 100, maxTotalBytes = 8, maxEntries = 8),
                )
            }
        }
        assertTrue(e.message!!, e.message!!.contains("总尺寸"))
    }

    @Test
    fun `已知 entry 正常读出且未知 entry 不保留`() {
        val zip = zipOf(
            "meta.json" to """{"format_version":2}""".toByteArray(),
            "vectors.bin" to BackupValidator.encodeVectorHeader(0),
            "unknown.bin" to ByteArray(3),
        )
        val entries = zip.use { BackupValidator.readArchive(it) }
        assertEquals(setOf("meta.json", "vectors.bin"), entries.keys)
        assertEquals(0, BackupValidator.readVectorCount(entries.getValue("vectors.bin")))
    }

    // ---- B1-9 引用闭包：导出只带 ready 文档、子表无孤儿、导入校验 ----

    @Test
    fun `导出闭包排除非 ready 文档及其子表行`() {
        val closure = BackupExportClosure()
        assertTrue(closure.acceptDocument("doc-ready", "src-1", "ready"))
        listOf("failed", "cancelled", "pending", "processing").forEach { status ->
            assertFalse("状态 $status 的文档不应进备份", closure.acceptDocument("doc-$status", "src-2", status))
        }
        assertEquals(setOf("doc-ready"), closure.documentIds())
        // 非 ready 文档引用的 source 也不进包（来源同样按引用闭包裁剪）
        assertEquals(listOf("src-1"), closure.sourceIds())

        assertTrue(closure.acceptChunk(1L, "doc-ready"))
        assertFalse("父文档被排除时 chunk 不得进包", closure.acceptChunk(2L, "doc-failed"))
        assertEquals(setOf(1L), closure.chunkIds())
        assertFalse(closure.includesChunk(2L))
        assertFalse(closure.includesDocument("doc-failed"))
    }

    @Test
    fun `导出闭包产出无子表孤儿`() {
        val closure = BackupExportClosure()
        listOf(
            Triple("d1", "s1", "ready"),
            Triple("d2", "s1", "failed"),
            Triple("d3", "s2", "ready"),
        ).forEach { (id, sourceId, status) -> closure.acceptDocument(id, sourceId, status) }

        val allChunks = listOf(10L to "d1", 11L to "d2", 12L to "d3", 13L to "d-missing")
        val acceptedChunks = allChunks.filter { (id, docId) -> closure.acceptChunk(id, docId) }
        // 向量端与导出实现同一谓词：chunk 在包内才接受
        val allVectors = listOf(10L to "space-a", 11L to "space-a", 12L to "space-a", 99L to "space-a")
        val acceptedVectors = allVectors.filter { closure.includesChunk(it.first) }

        assertEquals(listOf(10L, 12L), acceptedChunks.map { it.first })
        assertEquals(listOf(10L, 12L), acceptedVectors.map { it.first })
        // 闭包性质断言：包内每个子行都有父行（导入端 validateImportClosure 的前提）
        assertTrue(acceptedChunks.all { closure.includesDocument(it.second) })
        assertTrue(acceptedVectors.all { closure.includesChunk(it.first) })
        // 只有 ready 文档用到的来源进包
        assertEquals(listOf("s1", "s2"), closure.sourceIds())
    }

    @Test
    fun `快照模式保留非 ready 文档且父子仍成对`() {
        // 导入前快照 = 回滚安全网，要保留库内全部文档行（含 failed），父子仍成对 → 闭包一致
        val closure = BackupExportClosure(includeNonReadyDocuments = true)
        assertTrue(closure.acceptDocument("d-ready", "s1", "ready"))
        assertTrue(closure.acceptDocument("d-failed", "s2", "failed"))
        assertTrue(closure.acceptChunk(1L, "d-ready"))
        assertTrue(closure.acceptChunk(2L, "d-failed"))
        assertEquals(setOf("d-ready", "d-failed"), closure.documentIds())
        assertEquals(listOf("s1", "s2"), closure.sourceIds())
    }

    @Test
    fun `导入引用闭包校验：document 缺 source 报可读错误`() {
        val e = expectThrows<IllegalStateException> {
            BackupClosure.validateImportClosure(
                sourceIds = setOf("src-1"),
                documents = listOf(BackupClosure.DocumentRef("doc-1", "src-missing")),
                chunks = emptyList(),
            )
        }
        assertTrue(e.message!!, e.message!!.contains("引用闭包"))
        assertTrue(e.message!!, e.message!!.contains("src-missing"))
    }

    @Test
    fun `导入引用闭包校验：chunk 缺 document 报可读错误`() {
        val e = expectThrows<IllegalStateException> {
            BackupClosure.validateImportClosure(
                sourceIds = setOf("src-1"),
                documents = listOf(BackupClosure.DocumentRef("doc-1", "src-1")),
                chunks = listOf(BackupClosure.ChunkRef(7L, "doc-missing")),
            )
        }
        assertTrue(e.message!!, e.message!!.contains("引用闭包"))
        assertTrue(e.message!!, e.message!!.contains("doc-missing"))
    }

    @Test
    fun `合规导出闭包可通过导入校验（导出导入同一口径）`() {
        val closure = BackupExportClosure()
        closure.acceptDocument("d1", "s1", "ready")
        closure.acceptChunk(1L, "d1")
        val documents = closure.documentIds().map { BackupClosure.DocumentRef(it, "s1") }
        val chunks = closure.chunkIds().map { BackupClosure.ChunkRef(it, "d1") }
        BackupClosure.validateImportClosure(closure.sourceIds(), documents, chunks) // 不抛即通过
    }

    @Test
    fun `向量引用缺失由 decodeVectors 在解析阶段报错（B1-9 第三项）`() {
        // 文档/ chunk 闭包合法
        BackupClosure.validateImportClosure(
            sourceIds = setOf("s1"),
            documents = listOf(BackupClosure.DocumentRef("d1", "s1")),
            chunks = listOf(BackupClosure.ChunkRef(1L, "d1")),
        )
        // 向量指向不存在的 chunk → decodeVectors 报错（写库前失败）
        val payload = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 999L, ByteArray(4), BackupValidator.FORMAT_VERSION)
        val e = expectThrows<IllegalStateException> {
            BackupValidator.decodeVectors(
                payload, BackupValidator.FORMAT_VERSION,
                listOf(BackupValidator.SpaceSpec("space-a", 1)), setOf(1L),
            )
        }
        assertTrue(e.message!!, e.message!!.contains("不存在"))
    }

    // ---- helpers ----

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ZipInputStream {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return ZipInputStream(baos.toByteArray().inputStream())
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("期望 ${T::class.simpleName}，实际 ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("期望抛出 ${T::class.simpleName}，但没有异常")
    }
}
