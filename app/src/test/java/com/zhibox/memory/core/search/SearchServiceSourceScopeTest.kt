package com.zhibox.memory.core.search

import android.app.Application
import com.zhibox.memory.core.embedding.EmbeddingDescriptor
import com.zhibox.memory.core.embedding.EmbeddingProvider
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R2 端到端回归：来源范围过滤必须先于向量候选截断/RRF，不能因全库高分候选而饿死范围内命中。
 * 使用 fake EmbeddingProvider + Robolectric DB，不加载真实模型/native。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchServiceSourceScopeTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var model: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        model = File(app.filesDir, "models/embeddinggemma-2-text-vision-440m.litertlm").apply {
            parentFile?.mkdirs()
            writeText("fake model for source-scope test")
        }
    }

    @After
    fun tearDown() {
        EmbeddingRuntime.providerFactory = null
        runBlocking(Dispatchers.IO) {
            EmbeddingRuntime.shutdown()
            db.clearAllTables()
            resetDatabaseSingleton()
        }
        model.delete()
        app.deleteDatabase(DB_NAME)
    }

    @Test
    fun scopedSearchReturnsInScopeHitEvenWhenGlobalTopKIsOtherSource() = runBlocking {
        val provider = object : EmbeddingProvider {
            override val descriptor = EmbeddingDescriptor(
                providerId = "mediapipe-universal",
                modelId = "embeddinggemma-2-text-vision-440m",
                dimensions = 2,
            )

            override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
                texts.map { floatArrayOf(1f, 0f) }

            override suspend fun embedQuery(text: String): FloatArray = floatArrayOf(1f, 0f)

            override fun close() = Unit
        }
        EmbeddingRuntime.providerFactory = { _, _, _, _ -> provider }
        val space = EmbeddingRuntime.spaceIdFor(provider)

        db.sourceDao().upsert(SourceEntity("outside", "outside", "", null, 0, "active"))
        db.sourceDao().upsert(SourceEntity("inside", "inside", "", null, 0, "active"))
        db.documentDao().upsertAll(
            listOf(
                DocumentEntity("outsideDoc", "outside", "外部", "外部", "txt", 0, "ready"),
                DocumentEntity("insideDoc", "inside", "内部", "内部", "txt", 0, "ready"),
            ),
        )
        val chunks = db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    documentId = "outsideDoc", ordinal = 0, content = "范围 外部",
                    charStart = 0, charEnd = 5, tokenEstimate = 5,
                ),
                ChunkEntity(
                    documentId = "insideDoc", ordinal = 0, content = "范围 内部",
                    charStart = 0, charEnd = 5, tokenEstimate = 5,
                ),
            ),
        )
        db.chunkDao().insertFts(
            chunks.map {
                ChunkFtsEntity(
                    chunkId = it,
                    bigramContent = BigramTokenizer.toFtsText(
                        if (it == chunks[0]) "范围 外部" else "范围 内部",
                    ),
                )
            },
        )
        db.embeddingDao().insertAll(
            listOf(
                ChunkEmbeddingEntity(
                    chunkId = chunks[0], embeddingSpaceId = space, role = "content",
                    vectorBlob = vector(1f, 0f), vectorNorm = 1.0, createdAt = 0,
                ),
                ChunkEmbeddingEntity(
                    chunkId = chunks[1], embeddingSpaceId = space, role = "content",
                    vectorBlob = vector(0.9f, 0.1f), vectorNorm = 0.9055, createdAt = 0,
                ),
            ),
        )

        val result = SearchService(app, db).search("范围", topK = 1, sourceIds = listOf("inside"))

        assertEquals("范围内结果不能被全库高分候选饿死", listOf(chunks[1]), result.hits.map { it.chunkId })
    }

    private fun vector(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).array()

    private fun resetDatabaseSingleton() {
        val field = AppDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        (field.get(null) as? AppDatabase)?.close()
        field.set(null, null)
    }

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
