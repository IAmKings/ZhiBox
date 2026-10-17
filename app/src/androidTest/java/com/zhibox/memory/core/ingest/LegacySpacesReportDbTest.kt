package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B7 旧空间报表验收（instrumentation；不依赖嵌入模型）。
 *
 * 被测：`IngestRepository.legacySpacesReport(): List<LegacySpaceInfo>`
 * - 只返回**已下线模型路线**的空间（granite / Path C embedding_gemma / LFM / 无法识别的孤儿空间）；
 * - 受支持的 EGemma2 空间（modelId 含 embeddinggemma-2 且 provider ∈ {mediapipe-universal,
 *   litertlm-engine}）不出现；
 * - 覆盖 meta 缺失的孤儿空间（遍历 meta ∪ chunk_embedding 空间 id），vectorCount 为实际行数；
 * - 按 id 排序，设置页据此提示"旧数据需重新导入"。
 */
class LegacySpacesReportDbTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = IngestRepository(context, db = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun legacySpacesReport_onlyReturnsUnsupportedSpaces_withCorrectVectorCounts() = runBlocking {
        seedMixedSpaces()

        val report = repository.legacySpacesReport()

        assertEquals(
            "报表只应包含 3 个已下线路线空间 + 1 个孤儿空间（按 id 排序）",
            listOf(
                "litertlm-engine/LFM2.5-Embedding-350M_wi8fc/raw-symmetric-v1",
                "litertlm-engine/granite-311m/raw-symmetric-v1",
                "mediapipe-text/embedding_gemma/raw-symmetric-v1",
                "orphan/unregistered-space/raw-symmetric-v1",
            ),
            report.map { it.id },
        )

        val byId = report.associateBy { it.id }
        // granite（旧 Path B 模型）
        byId["litertlm-engine/granite-311m/raw-symmetric-v1"]!!.let {
            assertEquals("litertlm-engine", it.providerId)
            assertEquals("granite-embedding-311m-r2_wi8fc", it.modelId)
            assertEquals("granite 空间向量数", 1, it.vectorCount)
        }
        // Path C（.task / TextEmbedder）
        byId["mediapipe-text/embedding_gemma/raw-symmetric-v1"]!!.let {
            assertEquals("mediapipe-text", it.providerId)
            assertEquals("embedding_gemma", it.modelId)
            assertEquals("Path C 空间向量数", 2, it.vectorCount)
        }
        // LFM（旧 Path B 模型，1024d）
        byId["litertlm-engine/LFM2.5-Embedding-350M_wi8fc/raw-symmetric-v1"]!!.let {
            assertEquals("litertlm-engine", it.providerId)
            assertEquals("LFM2.5-Embedding-350M_wi8fc", it.modelId)
            assertEquals("LFM 空间向量数", 1, it.vectorCount)
        }
        // 孤儿空间（meta 缺失）：providerId/modelId 为空串，计数仍准确
        byId["orphan/unregistered-space/raw-symmetric-v1"]!!.let {
            assertEquals("", it.providerId)
            assertEquals("", it.modelId)
            assertEquals("孤儿空间向量数", 1, it.vectorCount)
        }

        // 受支持 EGemma2 空间绝不出现在报表（含 GPU/rev 形态）
        assertTrue(
            "受支持空间不得被判为 legacy",
            report.none { it.id.contains("embeddinggemma-2") },
        )
        assertEquals("混合库中 legacy 空间总数", 4, report.size)
        println(
            "B7_LEGACY_SPACES " + report.joinToString("; ") { "${it.id}#${it.vectorCount}" },
        )
    }

    @Test
    fun legacySpacesReport_emptyWhenOnlySupportedSpaces() = runBlocking {
        seedValidChain()
        db.embeddingDao().upsertMeta(supportedMeta(SUPPORTED_SPACE, "embeddinggemma-2-text-vision-440m"))
        db.embeddingDao().insertAll(
            listOf(embedding(chunkIds[0], SUPPORTED_SPACE), embedding(chunkIds[1], SUPPORTED_SPACE)),
        )

        assertTrue("仅支持空间时报表为空", repository.legacySpacesReport().isEmpty())
    }

    // ---- seed ----

    private lateinit var chunkIds: List<Long>

    private suspend fun seedValidChain() {
        db.sourceDao().upsert(SourceEntity("src", "旧空间测试来源", "", null, 0, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc", sourceId = "src", title = "旧空间测试文档", checksum = "sum",
                parserId = "txt-utf8-v1", createdAt = 0, status = DocStatus.READY.value,
            ),
        )
        chunkIds = db.chunkDao().insertAll(
            (0 until 2).map { i ->
                ChunkEntity(
                    documentId = "doc", ordinal = i, content = "旧空间向量内容$i",
                    charStart = 0, charEnd = 10, tokenEstimate = 10,
                )
            },
        )
        assertEquals(2, chunkIds.size)
    }

    private suspend fun seedMixedSpaces() {
        seedValidChain()
        // 受支持 EGemma2 空间（含 GPU/rev 形态）
        db.embeddingDao().upsertMeta(supportedMeta(SUPPORTED_SPACE, "embeddinggemma-2-text-vision-440m"))
        // 已下线路线空间 meta
        db.embeddingDao().upsertMeta(meta(GRANITE_SPACE, "litertlm-engine", "granite-embedding-311m-r2_wi8fc"))
        db.embeddingDao().upsertMeta(meta(PATH_C_SPACE, "mediapipe-text", "embedding_gemma"))
        db.embeddingDao().upsertMeta(meta(LFM_SPACE, "litertlm-engine", "LFM2.5-Embedding-350M_wi8fc"))

        db.embeddingDao().insertAll(
            listOf(
                // 受支持空间：2 行（不计入 legacy 报表）
                embedding(chunkIds[0], SUPPORTED_SPACE),
                embedding(chunkIds[1], SUPPORTED_SPACE),
                // granite：1 行
                embedding(chunkIds[0], GRANITE_SPACE),
                // Path C：2 行
                embedding(chunkIds[0], PATH_C_SPACE),
                embedding(chunkIds[1], PATH_C_SPACE),
                // LFM：1 行
                embedding(chunkIds[0], LFM_SPACE),
                // 孤儿空间（无 meta）：1 行
                embedding(chunkIds[1], ORPHAN_SPACE),
            ),
        )
    }

    private fun meta(id: String, providerId: String, modelId: String) = EmbeddingMetaEntity(
        id = id,
        providerId = providerId,
        modelId = modelId,
        modelRevision = null,
        dimensions = 2,
        normalization = "l2",
        taskTemplate = "raw-symmetric",
        createdAt = 1,
        active = false,
        accelerator = "cpu",
        quantization = "f32",
    )

    private fun supportedMeta(id: String, modelId: String) = EmbeddingMetaEntity(
        id = id,
        providerId = "mediapipe-universal",
        modelId = modelId,
        modelRevision = "abcdef123456",
        dimensions = 2,
        normalization = "l2",
        taskTemplate = "app-prefix-v1",
        createdAt = 1,
        active = true,
        accelerator = "gpu",
        quantization = "f32",
    )

    private fun embedding(chunkId: Long, spaceId: String) = ChunkEmbeddingEntity(
        chunkId = chunkId,
        embeddingSpaceId = spaceId,
        role = "content",
        vectorBlob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(0f).array(),
        vectorNorm = 1.0,
        createdAt = 1,
    )

    private companion object {
        const val SUPPORTED_SPACE =
            "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu@abcdef123456"
        const val GRANITE_SPACE = "litertlm-engine/granite-311m/raw-symmetric-v1"
        const val PATH_C_SPACE = "mediapipe-text/embedding_gemma/raw-symmetric-v1"
        const val LFM_SPACE = "litertlm-engine/LFM2.5-Embedding-350M_wi8fc/raw-symmetric-v1"
        const val ORPHAN_SPACE = "orphan/unregistered-space/raw-symmetric-v1"
    }
}
