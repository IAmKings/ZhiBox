package com.zhibox.memory.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P0-1 回归（instrumentation，需 arm64 真机执行）：
 * 1) 导出 → 清库 → 导入 → 再导出的向量数与空间数不降（P0-1b 恢复 embedding_meta 的核心保证）；
 * 2) 损坏包导入失败且**原库保持不变**（P0-1a 事务/先校验后写库）。
 *
 * 导入走 `importFromFileForTest`（跳过模型在位校验，避免测试机需要真实模型资产）；
 * 生产入口的模型供应链校验由 `BackupValidatorTest`/`ModelIntegrityTest` 覆盖。
 */
class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val tmpFiles = mutableListOf<File>()

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
    fun exportImportExport_preservesSpacesAndVectors() = runBlocking {
        seed()

        val p1 = tmp("backup-roundtrip-1.zip")
        assertEquals(1, KnowledgeBackup.exportToFile(context, p1).getOrThrow())
        val prev1 = KnowledgeBackup.previewFromFile(context, p1).getOrThrow()
        assertEquals(BackupValidator.FORMAT_VERSION, prev1.formatVersion)
        assertEquals(1, prev1.spaces.size)
        assertEquals(1, prev1.vectorCount)

        db.clearAllTables()
        assertEquals(0, db.embeddingDao().count())
        assertTrue(KnowledgeBackup.importFromFileForTest(context, p1).isSuccess)

        // 数据完整恢复（含 P0-1b：embedding_meta / app_kv / source.uri / document.sizeBytes）
        assertEquals(1, db.chunkDao().count())
        assertEquals(1, db.chunkDao().ftsCount())
        assertEquals(1, db.embeddingDao().count())
        val meta = db.embeddingDao().meta(SPACE)!!
        assertEquals("gpu", meta.accelerator)
        assertEquals("f32", meta.quantization)
        assertEquals("abcdef123456", meta.modelRevision)
        assertEquals("app-prefix-v1", meta.taskTemplate)
        assertEquals("v", db.appKvDao().get("roundtrip_key"))
        assertEquals("file:///orig.txt", db.sourceDao().get("src")!!.uri)
        assertEquals(9L, db.documentDao().get("doc")!!.sizeBytes)

        // 再导出：向量数与空间数不降（P0-1b 修复前这里会静默丢光向量）
        val p2 = tmp("backup-roundtrip-2.zip")
        assertTrue(KnowledgeBackup.exportToFile(context, p2).isSuccess)
        val prev2 = KnowledgeBackup.previewFromFile(context, p2).getOrThrow()
        assertEquals(prev1.vectorCount, prev2.vectorCount)
        assertEquals(prev1.spaces.size, prev2.spaces.size)
    }

    @Test
    fun corruptedPackage_failsWithoutWipingExistingDb() = runBlocking {
        seed()
        val before = counts()
        val bad = tmp("backup-corrupted.zip").apply { writeText("this is not a zip") }

        val result = KnowledgeBackup.importFromFile(context, bad, dropIncompatibleGpuVectors = false)
        assertTrue("损坏包必须失败", result.isFailure)
        assertEquals("失败导入不得清空/修改现有知识库", before, counts())
        assertTrue(before.chunks == 1 && before.vectors == 1)
    }

    private suspend fun seed() {
        db.embeddingDao().upsertMeta(
            EmbeddingMetaEntity(
                id = SPACE,
                providerId = "mediapipe-universal",
                modelId = "embeddinggemma-2-text-vision-440m",
                modelRevision = "abcdef123456",
                dimensions = 2,
                normalization = "l2",
                taskTemplate = "app-prefix-v1",
                createdAt = 1,
                active = true,
                accelerator = "gpu",
                quantization = "f32",
            )
        )
        db.sourceDao().upsert(SourceEntity("src", "原文", "file:///orig.txt", "text/plain", 1, "active"))
        db.documentDao().upsert(
            DocumentEntity("doc", "src", "文档", "checksum", "txt-utf8-v1", 2, "ready", sizeBytes = 9)
        )
        val text = "导出回环测试内容"
        val ids = db.chunkDao().insertAll(
            listOf(
                ChunkEntity(documentId = "doc", ordinal = 0, content = text, charStart = 0, charEnd = text.length, tokenEstimate = 8)
            )
        )
        db.chunkDao().insertFts(ids.map { ChunkFtsEntity(chunkId = it, bigramContent = BigramTokenizer.toFtsText(text)) })
        db.embeddingDao().insertAll(
            ids.map {
                ChunkEmbeddingEntity(
                    chunkId = it,
                    embeddingSpaceId = SPACE,
                    role = "content",
                    vectorBlob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(0f).array(),
                    vectorNorm = 1.0,
                    createdAt = 3,
                )
            }
        )
        db.appKvDao().put(AppKvEntity("roundtrip_key", "v", 4))
    }

    private data class Counts(
        val sources: Int, val documents: Int, val chunks: Int, val fts: Int,
        val vectors: Int, val metas: Int, val kv: Int,
    )

    private suspend fun counts() = Counts(
        sources = db.sourceDao().all().size,
        documents = db.documentDao().all().size,
        chunks = db.chunkDao().count(),
        fts = db.chunkDao().ftsCount(),
        vectors = db.embeddingDao().count(),
        metas = db.embeddingDao().allMeta().size,
        kv = db.appKvDao().all().size,
    )

    private fun tmp(name: String): File =
        File(context.cacheDir, name).also { tmpFiles += it; if (it.exists()) it.delete() }

    private companion object {
        const val SPACE = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu"
    }
}
