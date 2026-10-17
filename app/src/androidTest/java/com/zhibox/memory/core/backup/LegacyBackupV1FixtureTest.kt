package com.zhibox.memory.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P2-B1-8：v1 备份包 fixture 导入兼容测试（instrumentation）。
 *
 * 历史 v1 包（FORMAT_VERSION=1）与当前 v2 的差异：
 * - meta.json 的 spaces 只有 5 字段（无 accelerator/quantization/modelRevision/normalization/active）；
 * - vectors.bin 行无 role/vectorNorm/createdAt（3 段式行布局）；
 * - sources 无 uri、documents 无 sizeBytes、无 app_kv.jsonl、model_requirements 无 size_bytes。
 *
 * 本测试手工构造一个 v1 zip（不依赖旧 APK），验证 `BackupValidator` 的版本门 + 行布局选择、
 * `KnowledgeBackup` 的 JSON 缺省值映射与写库链路，断言恢复后数据可用、缺省值符合预期。
 * 走 `importFromFileForTest` 跳过模型在位校验（测试机不需要真实模型资产）。
 */
class LegacyBackupV1FixtureTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val tmpFiles = mutableListOf<File>()

    private val spaceId = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu"
    private val blob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(0.5f)
        .putFloat(0.25f)
        .array()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
        tmpFiles.forEach { it.delete() }
    }

    @Test
    fun v1Package_previewAndImport_supportedWithDefaults() = runBlocking {
        val pkg = tmp("legacy-v1-backup.zip")
        writeV1Package(pkg)

        // 1) preview：版本门放行 v1，条目计数来自包内声明；不写库
        val preview = KnowledgeBackup.previewFromFile(context, pkg).getOrThrow()
        assertEquals("v1 包版本必须原样识别", 1, preview.formatVersion)
        assertEquals(1, preview.spaces.size)
        assertEquals("v1 包 source 数", 1, preview.sourceCount)
        assertEquals("v1 包 document 数", 1, preview.documentCount)
        assertEquals("v1 包 chunk 数", 1, preview.chunkCount)
        assertEquals("v1 包向量数（LE 头）", 1, preview.vectorCount)
        assertEquals(0, db.chunkDao().count()) // preview 不得写库

        // 2) 导入（跳过模型在位校验的测试入口）
        val result = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("v1 包导入失败：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, db.chunkDao().count())
        assertEquals("FTS 必须从 chunk 重建（v1 包不带 FTS）", 1, db.chunkDao().ftsCount())
        assertEquals(1, db.embeddingDao().count())
        assertEquals("v1 包 source/document 各 1 行", 1, db.sourceDao().all().size)

        // 3) v1 缺省值映射：source.uri 缺失 → ""；document.sizeBytes 缺失 → 0
        val source = db.sourceDao().get("src_v1")!!
        assertEquals("", source.uri)
        assertEquals("v1 原文", source.title)
        assertEquals("active", source.status)
        val document = db.documentDao().get("doc_v1")!!
        assertEquals(0L, document.sizeBytes)
        assertEquals("ready", document.status)
        assertEquals("txt-utf8-v1", document.parserId)

        // 4) v1 meta 缺省值：accelerator=cpu / quantization=f32 / revision=null / normalization=l2 / active=true
        val meta = db.embeddingDao().meta(spaceId)!!
        assertEquals("cpu", meta.accelerator)
        assertEquals("f32", meta.quantization)
        assertNull(meta.modelRevision)
        assertEquals("l2", meta.normalization)
        assertEquals("raw-symmetric", meta.taskTemplate)
        assertTrue(meta.active)
        assertEquals(2, meta.dimensions)

        // 5) v1 向量行缺省值：role=content / vectorNorm=1.0 / createdAt=0，BLOB 原样
        val vector = db.embeddingDao().page(limit = 100, offset = 0).single()
        assertEquals(spaceId, vector.embeddingSpaceId)
        assertEquals("content", vector.role)
        assertEquals(1.0, vector.vectorNorm, 0.0)
        assertEquals(0L, vector.createdAt)
        assertTrue("v1 向量 BLOB 必须原样恢复", blob.contentEquals(vector.vectorBlob))

        // 6) v1 不含 app_kv → 恢复后为空表；FTS 可检索重建后的 chunk
        assertNull(db.appKvDao().get("any_key"))
        val hits = db.chunkDao().searchFtsOrdered(BigramTokenizer.toOrMatchQuery("向量检索"), 5)
        assertEquals(listOf(1L), hits)
    }

    @Test
    fun v1Package_truncatedVectors_rejectedBeforeWritingDb() = runBlocking {
        val pkg = tmp("legacy-v1-truncated.zip")
        writeV1Package(pkg, truncateVectors = true)

        val result = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("截断的 vectors.bin 必须在写库前失败", result.isFailure)
        assertEquals("失败导入不得写入任何数据", 0, db.chunkDao().count())
        assertEquals(0, db.embeddingDao().count())
        assertEquals(0, db.sourceDao().all().size)
    }

    // ---- v1 包构造 ----

    private fun writeV1Package(dest: File, truncateVectors: Boolean = false) {
        val sources = """{"id":"src_v1","title":"v1 原文","mimeType":"text/plain","createdAt":1,"status":"active"}"""
        val documents =
            """{"id":"doc_v1","sourceId":"src_v1","title":"v1 文档","checksum":"abc123",""" +
                """"parserId":"txt-utf8-v1","createdAt":2,"status":"ready"}"""
        val content = "旧版备份内容向量检索"
        val chunks =
            """{"id":1,"documentId":"doc_v1","ordinal":0,"content":"$content",""" +
                """"charStart":0,"charEnd":${content.length},"tokenEstimate":${content.length}}"""
        val meta = """{"format_version":1,"exported_at":1,"app":"zhibox-memory","spaces":[""" +
            """{"id":"$spaceId","providerId":"mediapipe-universal",""" +
            """"modelId":"embeddinggemma-2-text-vision-440m","dimensions":2,"taskTemplate":"raw-symmetric"}]}"""
        val requirements = """{"requirements":[{"spaceId":"$spaceId",""" +
            """"modelId":"embeddinggemma-2-text-vision-440m"}]}"""

        // v1 行布局：spaceIdx:i16 | chunkId:i64 | len:i32 | blob（无 role/norm/createdAt）
        val vectors = BackupValidator.encodeVectorHeader(1) +
            BackupValidator.encodeVectorRow(0, 1L, blob, formatVersion = 1)
        val vectorsPayload = if (truncateVectors) vectors.copyOf(vectors.size - 3) else vectors

        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            // 单一定义，避免局部函数互相前向引用导致的递归类型推断问题
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(BackupValidator.ENTRY_META, meta.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_REQUIREMENTS, requirements.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_SOURCES, sources.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_DOCUMENTS, documents.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_CHUNKS, chunks.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_VECTORS, vectorsPayload)
            // 注意：v1 包没有 app_kv.jsonl
        }
    }

    private fun tmp(name: String): File =
        File(context.cacheDir, name).also { it.delete(); tmpFiles += it }
}
