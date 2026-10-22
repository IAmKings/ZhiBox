package com.zhibox.memory.core.graph

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.zhibox.memory.core.extraction.SagFtsRebuilder
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1.3c Explore 5000 大库多轮分页基准（in-memory Room，K60U 可执行）。
 *
 * seed：5000 active events + 5000 entities + 5000 event_entity links（+1 alias），批量显式 id 写入；
 * 重建 event_fts/entity_fts 后，重置 [ExplorePerfMonitor]，执行 3 轮 ×（空白分页 5 次 + FTS 分页 3 次 +
 * alias 1 次）=27 次 load：
 * - 断言每页 size=100、totalEvents=5000、hasMore 语义、offset 超界空图；
 * - 断言最近 64 环形指标（p50/p95/max <2000ms 宽松）与无正文（节点 label/subtitle 不含 SECRET）；
 * - 打印 `B15_EXPLORE_PERF ...` 原始行供性能卡汇报。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ExploreGraphPerfBenchmarkInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        ExplorePerfMonitor.reset()
        db.close()
    }

    @Test
    fun multiRoundPaginationOn5kGraphKeepsLatencyBounded() = runBlocking {
        val seedStart = System.currentTimeMillis()
        seedBigDatabase(events = 5_000, entities = 5_000)
        val seedMs = System.currentTimeMillis() - seedStart
        assertEquals(5_000, db.eventDao().count())
        assertEquals(5_000, db.entityDao().count())
        assertEquals(5_000, db.eventEntityDao().count())

        val ftsStart = System.currentTimeMillis()
        SagFtsRebuilder.rebuildAll(db)
        val ftsRebuildMs = System.currentTimeMillis() - ftsStart
        assertTrue("event_fts 应为 5000 行", db.eventDao().countFts() >= 5_000)
        assertTrue("entity_fts 应为 5000 行", db.entityDao().countFts() >= 5_000)

        ExplorePerfMonitor.reset()
        val rounds = 3
        var loads = 0
        repeat(rounds) { round ->
            // 空白 query 分页（5 页：0/1000/2000/3000/4000）
            listOf(0, 1_000, 2_000, 3_000, 4_000).forEach { offset ->
                val page = ExploreGraphRepository.load(db, maxEvents = 100, offset = offset)
                assertEquals("blank round=$round offset=$offset page size", 100, page.eventNodes.size)
                assertEquals(5_000, page.totalEvents)
                assertEquals(offset, page.pageOffset)
                assertTrue("blank hasMore offset=$offset", page.hasMore)
                assertTrue(page.nodes.all { !it.label.contains(SECRET) && !it.subtitle.contains(SECRET) })
                loads++
            }
            // FTS query 分页（3 页）
            listOf(0, 2_000, 4_000).forEach { offset ->
                val page = ExploreGraphRepository.load(db, query = "大库事件", maxEvents = 100, offset = offset)
                assertEquals("fts round=$round offset=$offset page size", 100, page.eventNodes.size)
                assertEquals(5_000, page.totalEvents)
                assertEquals(offset, page.pageOffset)
                assertTrue("fts hasMore offset=$offset", page.hasMore)
                loads++
            }
            // entity_fts alias → 关联事件
            val aliasPage = ExploreGraphRepository.load(db, query = "独特别名", maxEvents = 100)
            assertEquals(1, aliasPage.totalEvents)
            assertEquals(1, aliasPage.eventNodes.size)
            assertFalse(aliasPage.hasMore)
            loads++
        }
        assertEquals(rounds * 9, loads)

        // offset 超界（空白/FTS 各一次，确认空图 + hasMore=false）
        val blankBeyond = ExploreGraphRepository.load(db, maxEvents = 100, offset = 5_000)
        assertTrue(blankBeyond.nodes.isEmpty())
        assertEquals(5_000, blankBeyond.totalEvents)
        assertEquals(5_000, blankBeyond.pageOffset)
        assertFalse(blankBeyond.hasMore)
        val ftsBeyond = ExploreGraphRepository.load(db, query = "大库事件", maxEvents = 100, offset = 5_000)
        assertTrue(ftsBeyond.nodes.isEmpty())
        assertFalse(ftsBeyond.hasMore)
        loads += 2

        val stats = ExplorePerfMonitor.stats()
        assertEquals("环形缓冲应记录本轮全部 load", loads, stats.count)
        assertTrue("p50 <3000ms 宽松阈值：${stats.p50Ms}", stats.p50Ms < 3_000)
        assertTrue("p95 <3000ms 宽松阈值：${stats.p95Ms}", stats.p95Ms < 3_000)
        assertTrue("max <3000ms 宽松阈值：${stats.maxMs}", stats.maxMs < 3_000)
        assertNotNull("应有 last 样本", stats.last)
        assertEquals("last.offset=越界页 5000", 5_000, stats.last!!.offset)
        assertEquals(ExploreLoadResult.EMPTY, stats.last!!.result)
        assertTrue("monitor 环形保留最近样本", ExplorePerfMonitor.snapshot().size <= ExplorePerfMonitor.CAPACITY)
        println(
            "B15_EXPLORE_SCALE events=5000 entities=5000 links=5000 loads=$loads rounds=$rounds " +
                "seed_ms=$seedMs fts_rebuild_ms=$ftsRebuildMs " +
                "p50_ms=${stats.p50Ms} p95_ms=${stats.p95Ms} max_ms=${stats.maxMs} " +
                "samples=${stats.count}",
        )
    }

    // ---- seed（批量显式 id，避免 5000 次单行 upsert） ----

    private suspend fun seedBigDatabase(events: Int, entities: Int) {
        db.sourceDao().upsert(SourceEntity("src-perf", "性能来源", "", null, 1, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-perf", sourceId = "src-perf", title = "性能文档", checksum = "sum-perf",
                parserId = "txt-utf8-v1", createdAt = 2, status = DocStatus.READY.value,
            ),
        )
        val chunkCount = 10
        val chunkIds = db.chunkDao().insertAll(
            (0 until chunkCount).map { index ->
                ChunkEntity(
                    documentId = "doc-perf",
                    ordinal = index,
                    content = "性能正文分片$index",
                    charStart = index * 10,
                    charEnd = index * 10 + 4,
                    tokenEstimate = 4,
                )
            },
        )
        db.eventDao().upsertAll(
            (1..events).map { index ->
                EventEntity(
                    id = index.toLong(),
                    chunkId = chunkIds[(index - 1) % chunkCount],
                    canonicalText = "大库事件$index",
                    confidence = 0.8,
                    title = "大库事件$index",
                    summary = "大库摘要$index",
                    content = "大库正文$SECRET$index",
                    language = "zh",
                    status = EventStatus.ACTIVE.value,
                    extractionVersion = "ev-perf-$index",
                    active = true,
                    createdAt = 1_000L + index,
                )
            },
        )
        db.entityDao().upsertAll(
            (1..entities).map { index ->
                EntityEntity(
                    id = index.toLong(),
                    normalizedName = "大库实体$index",
                    entityType = "person",
                    name = "大库实体$index",
                    description = "大库实体描述$index",
                    createdAt = 2_000L + index,
                )
            },
        )
        db.eventEntityDao().upsertAll(
            (1..minOf(events, entities)).map { index ->
                EventEntityRel(
                    eventId = index.toLong(),
                    entityId = index.toLong(),
                    role = "person",
                    weight = 1.0,
                )
            },
        )
        db.entityAliasDao().upsertAll(
            listOf(
                EntityAliasEntity(
                    entityId = 1L,
                    alias = "独特别名",
                    normalizedAlias = "独特别名",
                    createdAt = 3_000,
                ),
            ),
        )
    }

    private companion object {
        const val SECRET = "SECRET_CONTENT_MARKER"
    }
}
