package com.zhibox.memory.core.graph

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B12 Graph DAO（instrumentation，in-memory Room，真实 DAO）：
 * `EventDao.activeGraphPage` / `countActiveGraph`：
 * - 只返回 active event，跨来源按 event.id DESC；
 * - 分页（limit/offset）正确、不重不漏；
 * - 来源过滤（document.sourceId）只返回该来源；未知来源为空；
 * - 计数与分页同过滤条件；空库为 0/空页；
 * - seed 后 `PRAGMA foreign_key_check` 为空（chunk/document/source 引用完整）。
 */
class GraphDaoTest {

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
    fun activeGraphPageExcludesInactiveOrdersByIdDescAndPaginates() = runBlocking {
        val seeded = seedGraph()
        val eventDao = db.eventDao()

        val all = eventDao.activeGraphPage(sourceId = null, limit = 10, offset = 0)
        assertEquals("3 条 active（2 条 inactive 被排除）", 3, all.size)
        assertEquals(
            "跨来源按 event.id DESC",
            listOf(seeded.bEventId, seeded.aEvent2Id, seeded.aEvent1Id),
            all.map { it.id },
        )
        assertTrue("不得包含 inactive event", all.all { it.active })
        assertTrue(all.none { it.id == seeded.aInactiveId || it.id == seeded.bInactiveId })

        // 分页：limit=2, page0=[b,e2], page1=[e1]；limit=1, offset=1 → [e2]
        assertEquals(
            listOf(seeded.bEventId, seeded.aEvent2Id),
            eventDao.activeGraphPage(null, limit = 2, offset = 0).map { it.id },
        )
        assertEquals(
            listOf(seeded.aEvent1Id),
            eventDao.activeGraphPage(null, limit = 2, offset = 2).map { it.id },
        )
        assertEquals(
            listOf(seeded.aEvent2Id),
            eventDao.activeGraphPage(null, limit = 1, offset = 1).map { it.id },
        )
        assertTrue("offset 超界返回空", eventDao.activeGraphPage(null, limit = 2, offset = 99).isEmpty())
    }

    @Test
    fun sourceFilterAndCountsAreExact() = runBlocking {
        val seeded = seedGraph()
        val eventDao = db.eventDao()

        assertEquals("无过滤 count", 3, eventDao.countActiveGraph(null))
        assertEquals("来源 A count", 2, eventDao.countActiveGraph(seeded.sourceA))
        assertEquals("来源 B count", 1, eventDao.countActiveGraph(seeded.sourceB))
        assertEquals("未知来源 count=0", 0, eventDao.countActiveGraph("src-missing"))

        val sourceAEvents = eventDao.activeGraphPage(seeded.sourceA, limit = 10, offset = 0)
        assertEquals(listOf(seeded.aEvent2Id, seeded.aEvent1Id), sourceAEvents.map { it.id })
        assertTrue("只返回来源 A", sourceAEvents.all { it.chunkId in seeded.aChunkIds })

        val sourceBEvents = eventDao.activeGraphPage(seeded.sourceB, limit = 10, offset = 0)
        assertEquals(listOf(seeded.bEventId), sourceBEvents.map { it.id })
        assertTrue(eventDao.activeGraphPage("src-missing", 10, 0).isEmpty())
        assertEquals("来源分页与计数一致", 2, eventDao.activeGraphPage(seeded.sourceA, limit = 2, offset = 0).size)
    }

    @Test
    fun emptyGraphReturnsEmptyPageAndZeroCount() = runBlocking {
        val eventDao = db.eventDao()
        assertTrue(eventDao.activeGraphPage(null, 10, 0).isEmpty())
        assertEquals(0, eventDao.countActiveGraph(null))
        assertEquals(0, eventDao.countActiveGraph("src-A"))
    }

    @Test
    fun seededGraphHasNoForeignKeyViolations() = runBlocking {
        seedGraph()
        assertEquals("有 5 条 event（含 inactive 历史）", 5, db.eventDao().count())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            val violations = cursor.count
            val detail = buildString {
                while (cursor.moveToNext()) {
                    append(cursor.getString(0)).append(' ')
                }
            }
            assertEquals("foreign_key_check 应为空：$detail", 0, violations)
        }
    }

    // ---- seed ----

    private data class Seeded(
        val sourceA: String,
        val sourceB: String,
        val aChunkIds: List<Long>,
        val aEvent1Id: Long,
        val aEvent2Id: Long,
        val aInactiveId: Long,
        val bChunkIds: List<Long>,
        val bEventId: Long,
        val bInactiveId: Long,
    )

    private suspend fun seedGraph(): Seeded {
        val sourceA = "src-A"
        val sourceB = "src-B"
        db.sourceDao().upsert(SourceEntity(sourceA, "来源A", "", null, 1L, "active"))
        db.sourceDao().upsert(SourceEntity(sourceB, "来源B", "", null, 2L, "active"))

        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-A1", sourceId = sourceA, title = "文档A1", checksum = "sum-a1",
                parserId = "txt-utf8-v1", createdAt = 10L, status = DocStatus.READY.value,
            ),
        )
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-A2", sourceId = sourceA, title = "文档A2", checksum = "sum-a2",
                parserId = "txt-utf8-v1", createdAt = 11L, status = DocStatus.READY.value,
            ),
        )
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-B1", sourceId = sourceB, title = "文档B1", checksum = "sum-b1",
                parserId = "txt-utf8-v1", createdAt = 12L, status = DocStatus.READY.value,
            ),
        )

        val chunkA1 = db.chunkDao().insertAll(
            listOf(ChunkEntity(documentId = "doc-A1", ordinal = 0, content = "A1 内容", charStart = 0, charEnd = 4, tokenEstimate = 4)),
        ).single()
        val chunkA2 = db.chunkDao().insertAll(
            listOf(ChunkEntity(documentId = "doc-A2", ordinal = 0, content = "A2 内容", charStart = 0, charEnd = 4, tokenEstimate = 4)),
        ).single()
        val chunkB1 = db.chunkDao().insertAll(
            listOf(ChunkEntity(documentId = "doc-B1", ordinal = 0, content = "B1 内容", charStart = 0, charEnd = 4, tokenEstimate = 4)),
        ).single()

        fun event(chunkId: Long, version: String, active: Boolean): EventEntity = EventEntity(
            chunkId = chunkId,
            canonicalText = "事件$version",
            confidence = 1.0,
            title = "事件$version",
            summary = "摘要$version",
            content = "内容$version",
            language = "zh",
            status = if (active) EventStatus.ACTIVE.value else EventStatus.SUPERSEDED.value,
            extractorVersion = "egemma-sag-extract-v1",
            extractionVersion = version,
            promptVersion = "v1",
            active = active,
            createdAt = 100L,
        )

        val aEvent1 = db.eventDao().upsert(event(chunkA1, "ev-a1", active = true))
        val aInactive = db.eventDao().upsert(event(chunkA1, "ev-a-old", active = false))
        val aEvent2 = db.eventDao().upsert(event(chunkA2, "ev-a2", active = true))
        val bEvent = db.eventDao().upsert(event(chunkB1, "ev-b1", active = true))
        val bInactive = db.eventDao().upsert(event(chunkB1, "ev-b-old", active = false))

        return Seeded(
            sourceA = sourceA,
            sourceB = sourceB,
            aChunkIds = listOf(chunkA1, chunkA2),
            aEvent1Id = aEvent1,
            aEvent2Id = aEvent2,
            aInactiveId = aInactive,
            bChunkIds = listOf(chunkB1),
            bEventId = bEvent,
            bInactiveId = bInactive,
        )
    }
}
