package com.zhibox.memory.core.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P1-1 回归（instrumentation，需 arm64 真机执行）：检索候选必须只包含 `document.status='ready'`
 * 的 chunk——失败/取消/导入中的半成品即使已写入 chunk+FTS+向量也不可被检索。
 *
 * 说明：本用例直接验证 DAO 层的 JOIN 过滤（不依赖嵌入模型），因此可在无模型资产的真机上运行。
 */
class SearchVisibilityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun onlyReadyDocumentsAreSearchable() = runBlocking {
        val space = "mp-universal/egemma2-440m/app-prefix-v1"
        val text = "蓝牙断开恢复"
        db.sourceDao().upsert(SourceEntity("src", "src", "", null, 0, "active"))
        db.documentDao().upsertAll(
            listOf(
                DocumentEntity("doc_ready", "src", "ready", "sum1", "txt", 0, "ready"),
                DocumentEntity("doc_failed", "src", "failed", "sum2", "txt", 0, "failed"),
                DocumentEntity("doc_processing", "src", "processing", "sum3", "txt", 0, "processing"),
            )
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(
                ChunkEntity(documentId = "doc_ready", ordinal = 0, content = text, charStart = 0, charEnd = text.length, tokenEstimate = 6),
                ChunkEntity(documentId = "doc_failed", ordinal = 0, content = text, charStart = 0, charEnd = text.length, tokenEstimate = 6),
                ChunkEntity(documentId = "doc_processing", ordinal = 0, content = text, charStart = 0, charEnd = text.length, tokenEstimate = 6),
            )
        )
        assertEquals(3, chunkIds.size)
        db.chunkDao().insertFts(
            chunkIds.map { ChunkFtsEntity(chunkId = it, bigramContent = BigramTokenizer.toFtsText(text)) }
        )
        db.embeddingDao().insertAll(
            chunkIds.map {
                ChunkEmbeddingEntity(
                    chunkId = it,
                    embeddingSpaceId = space,
                    role = "content",
                    vectorBlob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(1f).putFloat(0f).array(),
                    vectorNorm = 1.0,
                    createdAt = 0,
                )
            }
        )

        // 向量通道：只应返回 ready 文档的 chunk
        val vectors = db.embeddingDao().vectorsForSpace(space)
        assertEquals(listOf(chunkIds[0]), vectors.map { it.chunkId })
        // 关键词通道：同样只应返回 ready 文档的 chunk
        val fts = db.chunkDao().searchFtsOrdered(BigramTokenizer.toOrMatchQuery("蓝牙断开"), 10)
        assertEquals(listOf(chunkIds[0]), fts)
        // 缓存签名只统计 ready 向量（矩阵缓存不得把半成品算进去）
        assertEquals(1, db.embeddingDao().readyVectorSignature(space)?.rowCount)
    }
}
