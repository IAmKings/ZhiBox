package com.zhibox.memory.core.graph

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1.3b Explore 大库 instrumentation（in-memory Room，K60U 可执行）：
 *
 * seed 1000 active events + 1000 entities + 1000 event_entity links（+ alias），重建 event_fts/entity_fts 后：
 * - 空白 query 分页：page1=100 / offset 500 第二页 / 最后一页 / offset 越界空图；
 * - FTS query 分页（event_fts 命中 1000）：page size=100、totalEvents=1000、hasMore；
 * - entity_fts alias 命中（"独特别名"）→ 关联事件；
 * - 测量分页 load 耗时（宽松 <2s，避免 flaky）并打印原始行；
 * - 无正文加载：event.content 含 SECRET marker，但图节点 label/subtitle 只来自 title/summary。
 */
@RunWith(AndroidJUnit4::class)
class ExploreGraphBigDbInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bigGraphFtsPaginationStaysFastAndContentFree() = runBlocking {
        val seedMs = timed { seedBigDatabase() }
        val ftsMs = timed { SagFtsRebuilder.rebuildAll(db) }
        assertEquals(1_000, db.eventDao().count())
        assertEquals(1_000, db.entityDao().count())
        assertEquals(1_000, db.eventEntityDao().count())
        assertTrue("event_fts 应重建", db.eventDao().countFts() >= 1_000)

        val blankPage1 = timed { ExploreGraphRepository.load(db, maxEvents = 100, offset = 0) }
        assertEquals(100, blankPage1.value.eventNodes.size)
        assertEquals(1_000, blankPage1.value.totalEvents)
        assertEquals(0, blankPage1.value.pageOffset)
        assertTrue(blankPage1.value.hasMore)
        assertFalse("正常分页不应误报 truncated", blankPage1.value.truncated)

        val blankPage2 = timed { ExploreGraphRepository.load(db, maxEvents = 100, offset = 500) }
        assertEquals(100, blankPage2.value.eventNodes.size)
        assertEquals(500, blankPage2.value.pageOffset)
        assertTrue(blankPage2.value.hasMore)
        assertTrue(
            "分页按 event.id 升序不重叠",
            blankPage2.value.eventNodes.minOf { it.eventId!! } > blankPage1.value.eventNodes.maxOf { it.eventId!! },
        )

        val blankLast = timed { ExploreGraphRepository.load(db, maxEvents = 100, offset = 950) }
        assertEquals(50, blankLast.value.eventNodes.size)
        assertFalse(blankLast.value.hasMore)

        val beyond = timed { ExploreGraphRepository.load(db, maxEvents = 100, offset = 5_000) }
        assertTrue(beyond.value.nodes.isEmpty())
        assertEquals(1_000, beyond.value.totalEvents)
        assertEquals(5_000, beyond.value.pageOffset)
        assertFalse(beyond.value.hasMore)

        val ftsPage = timed { ExploreGraphRepository.load(db, query = "大库事件", maxEvents = 100) }
        assertEquals(100, ftsPage.value.eventNodes.size)
        assertEquals("FTS 命中全量 1000", 1_000, ftsPage.value.totalEvents)
        assertTrue(ftsPage.value.hasMore)
        // 无正文加载：图节点只含 title/summary，不含 content 中的 SECRET marker
        ftsPage.value.nodes.forEach { node ->
            assertFalse("节点 label 不得包含正文：${node.label}", node.label.contains(SECRET))
            assertFalse("节点 subtitle 不得包含正文：${node.subtitle}", node.subtitle.contains(SECRET))
        }

        val aliasPage = timed { ExploreGraphRepository.load(db, query = "独特别名", maxEvents = 100) }
        assertEquals("entity_fts(alias) 命中关联事件", 1, aliasPage.value.totalEvents)
        assertEquals(1, aliasPage.value.eventNodes.size)
        assertFalse(aliasPage.value.hasMore)

        // 宽松阈值（K60U 实测远低于 2s；避免 CI/设备抖动 flaky）
        listOf(
            "blank_page1" to blankPage1.ms,
            "blank_page2" to blankPage2.ms,
            "blank_last" to blankLast.ms,
            "fts_page" to ftsPage.ms,
            "alias_page" to aliasPage.ms,
        ).forEach { (name, ms) ->
            assertTrue("$name 分页 load 应 <2000ms（实际 ${ms}ms）", ms < 2_000)
        }
        println(
            "B15_EXPLORE_BIGDB events=1000 entities=1000 links=1000 alias=1 " +
                "seed_ms=${seedMs.ms} fts_rebuild_ms=${ftsMs.ms} " +
                "blank_page1_ms=${blankPage1.ms} blank_page2_ms=${blankPage2.ms} " +
                "blank_last_ms=${blankLast.ms} beyond_ms=${beyond.ms} " +
                "fts_page_ms=${ftsPage.ms} alias_page_ms=${aliasPage.ms} " +
                "fts_total=${ftsPage.value.totalEvents} fts_page=${ftsPage.value.eventNodes.size}",
        )
    }

    // ---- seed ----

    private suspend fun seedBigDatabase() {
        db.sourceDao().upsert(SourceEntity("src-big", "大库来源", "", null, 1, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-big", sourceId = "src-big", title = "大库文档", checksum = "sum-big",
                parserId = "txt-utf8-v1", createdAt = 2, status = DocStatus.READY.value,
            ),
        )
        val chunkCount = 10
        val chunkIds = db.chunkDao().insertAll(
            (0 until chunkCount).map { index ->
                ChunkEntity(
                    documentId = "doc-big",
                    ordinal = index,
                    content = "大库正文分片$index",
                    charStart = index * 10,
                    charEnd = index * 10 + 4,
                    tokenEstimate = 4,
                )
            },
        )

        val events = ArrayList<EventEntity>(1_000)
        repeat(1_000) { index ->
            events += EventEntity(
                chunkId = chunkIds[index % chunkCount],
                canonicalText = "大库事件${index + 1}",
                confidence = 0.8,
                title = "大库事件${index + 1}",
                summary = "大库摘要${index + 1}",
                content = "大库正文$SECRET${index + 1}",
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractionVersion = "ev-big-${index + 1}",
                active = true,
                createdAt = 1_000L + index,
            )
        }
        db.eventDao().upsertAll(events)
        val storedEventIds = db.eventDao().all().sortedBy { it.id }.map { it.id }

        val entityEntities = (1..1_000).map { index ->
            EntityEntity(
                normalizedName = "大库实体$index",
                entityType = "person",
                name = "大库实体$index",
                description = "大库实体描述$index",
                createdAt = 2_000L + index,
            )
        }
        val entityIds = ArrayList<Long>(1_000)
        entityEntities.forEach { entityIds += db.entityDao().upsert(it) }
        db.eventEntityDao().upsertAll(
            storedEventIds.mapIndexed { index, eventId ->
                EventEntityRel(
                    eventId = eventId,
                    entityId = entityIds[index],
                    role = "person",
                    weight = 1.0,
                )
            },
        )
        db.entityAliasDao().upsertAll(
            listOf(
                EntityAliasEntity(
                    entityId = entityIds.first(),
                    alias = "独特别名",
                    normalizedAlias = "独特别名",
                    createdAt = 3_000,
                ),
            ),
        )
    }

    private inline fun <T> timed(block: () -> T): Timed<T> {
        val start = System.currentTimeMillis()
        val value = block()
        return Timed(value, System.currentTimeMillis() - start)
    }

    private data class Timed<T>(val value: T, val ms: Long)

    private companion object {
        const val SECRET = "SECRET_CONTENT_MARKER"
    }
}
