package com.zhibox.memory.core.search

import androidx.room.Room
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 可移植的 Room 检索边界测试；默认 JVM CI 执行，不依赖模型或 native 库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchVisibilityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
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
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(
                chunk("doc_ready", text),
                chunk("doc_failed", text),
                chunk("doc_processing", text),
            ),
        )
        db.chunkDao().insertFts(
            chunkIds.map { ChunkFtsEntity(chunkId = it, bigramContent = BigramTokenizer.toFtsText(text)) },
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
            },
        )

        assertEquals(listOf(chunkIds[0]), db.embeddingDao().vectorsForSpace(space).map { it.chunkId })
        assertEquals(
            listOf(chunkIds[0]),
            db.chunkDao().searchFtsOrdered(BigramTokenizer.toOrMatchQuery("蓝牙断开"), 10),
        )
        assertEquals(1, db.embeddingDao().readyVectorSignature(space).rowCount)
    }

    @Test
    fun sourceScopeIsAppliedBeforeFtsLimit() = runBlocking {
        val text = "范围检索"
        db.sourceDao().upsert(SourceEntity("outside", "outside", "", null, 0, "active"))
        db.sourceDao().upsert(SourceEntity("inside", "inside", "", null, 0, "active"))
        db.documentDao().upsertAll(
            listOf(
                DocumentEntity("outside_1", "outside", "outside 1", "sum1", "txt", 0, "ready"),
                DocumentEntity("outside_2", "outside", "outside 2", "sum2", "txt", 0, "ready"),
                DocumentEntity("inside_1", "inside", "inside", "sum3", "txt", 0, "ready"),
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(chunk("outside_1", text), chunk("outside_2", text), chunk("inside_1", text)),
        )
        db.chunkDao().insertFts(
            chunkIds.map { ChunkFtsEntity(chunkId = it, bigramContent = BigramTokenizer.toFtsText(text)) },
        )

        assertEquals(
            listOf(chunkIds[2]),
            db.chunkDao().searchFtsOrderedBySources(
                BigramTokenizer.toOrMatchQuery(text),
                1,
                DocStatus.READY.value,
                listOf("inside"),
            ),
        )
        assertEquals(
            listOf(chunkIds[2]),
            db.chunkDao().readyIdsBySources(listOf("inside"), DocStatus.READY.value),
        )
    }

    @Test
    fun sourceScopedVectorPageFiltersBeforePaging() = runBlocking {
        val space = "mp-universal/egemma2-440m/app-prefix-v1"
        db.sourceDao().upsert(SourceEntity("outside", "outside", "", null, 0, "active"))
        db.sourceDao().upsert(SourceEntity("inside", "inside", "", null, 0, "active"))
        db.documentDao().upsertAll(
            listOf(
                DocumentEntity("outside_doc", "outside", "outside", "s1", "txt", 0, "ready"),
                DocumentEntity("inside_doc", "inside", "inside", "s2", "txt", 0, "ready"),
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(chunk("outside_doc", "outside"), chunk("inside_doc", "inside")),
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
            },
        )

        assertEquals(
            listOf(chunkIds[1]),
            db.embeddingDao().vectorsForSpaceSourcesPage(
                space, DocStatus.READY.value, listOf("inside"), 10, 0,
            ).map { it.chunkId },
        )
    }

    private fun chunk(documentId: String, content: String) = ChunkEntity(
        documentId = documentId,
        ordinal = 0,
        content = content,
        charStart = 0,
        charEnd = content.length,
        tokenEstimate = content.length,
    )
}
