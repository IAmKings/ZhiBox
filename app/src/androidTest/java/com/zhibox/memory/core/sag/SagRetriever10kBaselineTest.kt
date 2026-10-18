package com.zhibox.memory.core.sag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B10 10k event/chunk 检索 SQL/耗时基线（instrumentation，in-memory Room，不依赖模型/网络）。
 *
 * 规模：100 文档 × 100 chunk = 10,000 chunk；10,000 active event（每 chunk 一条）；
 * 1 个 query entity 关联全部 10,000 event（event_entity weight=1.0）；100 条事件向量 seed。
 *
 * 测量（真实 SagRetriever + fake 注入缝，不含端侧 embedding 时间）：
 * - cold/warm p50/p95 全流程 retrieve(multi_es_fast)；
 * - DAO 基线：`activeEventIdsByEntityIds`（1 跳扩展 SQL）、`EventDao.byIds`（候选回填）。
 *
 * 输出原始数（logcat/println）：`B10_10K_RETRIEVE ...` / `B10_10K_SQL ...`，供 Lead 与 B10 报告引用；
 * 硬断言只设 30s 病态上限，不做性能门禁（阈值待真机基线确定）。
 */
class SagRetriever10kBaselineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val baseSpace = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1"
    private val eventSpace = SagVectorSpace.spaceIdFor(baseSpace, SagVectorRole.EVENT_CONTENT)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun retrieveOn10kEventsReportsRawLatencyBaseline() = runBlocking {
        val entityId = seed10k()

        // DAO 基线：1 跳扩展（SQL LIMIT frontier+1=51）与候选回填 byIds(50)
        db.eventEntityDao().activeEventIdsByEntityIds(listOf(entityId), 51) // warm-up
        val hopIds = timed { db.eventEntityDao().activeEventIdsByEntityIds(listOf(entityId), 51) }
        val candidateIds = hopIds.value.take(50)
        val byIds = timed { db.eventDao().byIds(candidateIds) }
        assertEquals("扩展候选应有 51 条（含截断探测）", 51, hopIds.value.size)
        assertEquals(50, byIds.value.size)
        println(
            "B10_10K_SQL hop_limit51_ms=${hopIds.ms} byIds50_ms=${byIds.ms} " +
                "events=10000 links=10000",
        )

        val retriever = SagRetriever(context = context, db = db)
        retriever.queryEmbeddingGateway = object : QueryEmbeddingGateway {
            override suspend fun embedQuery(text: String): FloatArray? = floatArrayOf(1f, 0f)
        }
        retriever.eventVectorSpaceForTests = eventSpace
        val supplementCalls = java.util.concurrent.atomic.AtomicInteger(0)
        retriever.vectorSupplementGateway = object : VectorSupplementGateway {
            override suspend fun supplement(
                query: String,
                topK: Int,
                excludeChunkIds: Set<Long>,
                sourceIds: List<String>,
            ): List<SearchService.SearchHit> {
                supplementCalls.incrementAndGet()
                return emptyList()
            }
        }

        val request = SagQuery(
            query = "上海",
            mode = RetrievalMode.MULTI_ES_FAST,
            topK = 10,
            budget = SagBudget(frontier = 50, kSeed = 50, kCand = 100, kEvent = 5, kOut = 10),
        )

        // cold（含首查 DAO/排序路径）
        val cold = timed { retriever.retrieve(request) }
        assertFalse("基线检索不应返回 refused：${cold.value.refused}", cold.value.refused != null)
        assertTrue("应有事件层命中：${cold.value.hits.size}", cold.value.hits.isNotEmpty())
        assertTrue("输出 ≤ min(topK,kOut)", cold.value.hits.size <= 10)
        assertEquals("选中数 = hits.size", cold.value.hits.size, cold.value.trace.selected)
        assertTrue("候选事件 ≤ kCand", cold.value.trace.candidates <= 100)
        assertTrue("expandedEvents 记录截断前扩展数", cold.value.trace.expandedEvents >= 51)
        assertTrue("10k 规模 cold 不应病态（<30s）：${cold.ms}ms", cold.ms < 30_000)
        assertEquals("chunk 去重", cold.value.hits.size, cold.value.hits.map { it.chunkId }.toSet().size)
        assertTrue("事件层可用（degraded 仅可能来自预算截断）", cold.value.trace.degradedReason!!.startsWith("预算截断："))

        // warm：再跑 10 轮取 p50/p95
        val rounds = 10
        val warm = ArrayList<Long>(rounds)
        repeat(rounds) {
            val t = timed { retriever.retrieve(request) }
            warm += t.ms
        }
        val sorted = warm.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        val traceRows = db.queryTraceDao().count()
        assertTrue("每次 retrieve 都应写 trace", traceRows >= rounds + 1)
        println(
            "B10_10K_RETRIEVE hits=${cold.value.hits.size} seedEvents=${cold.value.trace.seedEvents} " +
                "seedEntities=${cold.value.trace.seedEntities} expanded=${cold.value.trace.expandedEvents} " +
                "candidates=${cold.value.trace.candidates} selected=${cold.value.trace.selected} " +
                "cold_ms=${cold.ms} warm_p50_ms=$p50 warm_p95_ms=$p95 rounds=$rounds " +
                "fillCalls=${supplementCalls.get()} traceRows=$traceRows " +
                "degraded=${cold.value.trace.degradedReason}",
        )
    }

    // ---- seed ----

    private suspend fun seed10k(): Long {
        db.sourceDao().upsert(SourceEntity("src-1", "基线来源", "", null, 1L, "active"))
        var docIndex = 0
        while (docIndex < DOC_COUNT) {
            val docId = "doc-$docIndex"
            db.documentDao().upsert(
                DocumentEntity(
                    id = docId,
                    sourceId = "src-1",
                    title = "基线文档 $docIndex",
                    checksum = "sum-$docIndex",
                    parserId = "txt-utf8-v1",
                    createdAt = 1L,
                    status = DocStatus.READY.value,
                ),
            )
            db.chunkDao().insertAll(
                (0 until CHUNKS_PER_DOC).map { ordinal ->
                    ChunkEntity(
                        documentId = docId,
                        ordinal = ordinal,
                        content = "文档 $docIndex 第 $ordinal 段内容",
                        charStart = ordinal * 10,
                        charEnd = ordinal * 10 + 6,
                        tokenEstimate = 6,
                    )
                },
            )
            docIndex++
        }
        assertEquals(CHUNK_COUNT, db.chunkDao().count())

        // 10k event（每 chunk 一条，extractionVersion 唯一）+ 1 个实体 + 全量 event_entity
        val chunks = db.chunkDao().page(CHUNK_COUNT, 0)
        assertEquals(CHUNK_COUNT, chunks.size)
        val events = chunks.mapIndexed { index, chunk ->
            EventEntity(
                chunkId = chunk.id,
                canonicalText = "基线事件 $index",
                confidence = 1.0,
                title = "基线事件 $index",
                summary = "基线事件摘要 $index",
                content = "基线事件内容 $index",
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractorVersion = "egemma-sag-extract-v1",
                extractionVersion = "ev-$index",
                promptVersion = "v1",
                active = true,
                createdAt = 1_000L + index,
            )
        }
        db.eventDao().upsertAll(events)
        assertEquals(EVENT_COUNT, db.eventDao().count())
        val storedEvents = db.eventDao().all()
        assertEquals(EVENT_COUNT, storedEvents.size)

        val entityId = db.entityDao().upsert(
            EntityEntity(
                normalizedName = "上海",
                entityType = "location",
                name = "上海",
                description = "地点",
                createdAt = 1_000L,
            ),
        )
        db.eventEntityDao().upsertAll(
            storedEvents.map { event ->
                EventEntityRel(eventId = event.id, entityId = entityId, role = "location", weight = 1.0)
            },
        )
        assertEquals(EVENT_COUNT, db.eventEntityDao().count())

        // 100 条事件向量 seed（覆盖 kSeed=50，避免 bySpace 全表 5000 上限无关）
        val vectors = storedEvents.take(SAG_VECTOR_SEED).map { event ->
            SagVectorEntity(
                ownerType = SagOwnerType.EVENT.value,
                ownerId = event.id,
                role = SagVectorRole.EVENT_CONTENT.wire,
                spaceId = eventSpace,
                dimensions = 2,
                vectorBlob = floatBlob(1f, 0f),
                vectorNorm = 1.0,
                createdAt = 1_000L,
            )
        }
        db.sagVectorDao().insertAll(vectors)
        assertEquals(SAG_VECTOR_SEED, db.sagVectorDao().count())
        return entityId
    }

    private fun floatBlob(vararg values: Float): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private data class Timed<T>(val value: T, val ms: Long)

    private inline fun <T> timed(block: () -> T): Timed<T> {
        val start = System.currentTimeMillis()
        val value = block()
        return Timed(value, (System.currentTimeMillis() - start).coerceAtLeast(0))
    }

    private companion object {
        const val DOC_COUNT = 100
        const val CHUNKS_PER_DOC = 100
        const val CHUNK_COUNT = DOC_COUNT * CHUNKS_PER_DOC
        const val EVENT_COUNT = CHUNK_COUNT
        const val SAG_VECTOR_SEED = 100
    }
}
