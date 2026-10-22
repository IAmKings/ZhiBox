package com.zhibox.memory.core.graph

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B12 Graph 10k **持久盘**分页性能基线（instrumentation，file-backed Room，真实 DAO）。
 *
 * 与 `Graph10kBaselineTest`（in-memory 下限参考）互补：本测试用真实 SQLite 文件 + WAL/fsync，
 * 反映真机磁盘/页缓存下的 count/page 冷热耗时，供 B12 验收报告。
 *
 * seed：100 来源 × 1 文档 × 1 chunk × 100 active event = 10,000 active；另 500 inactive；105 chunk。
 * 测量：`countActiveGraph(null)`、`activeGraphPage(null, 50, offsets 0/5000/9950)` 各 cold + 10 轮
 * warm p50/p95/max；来源过滤 `src-050` count/page。打印 `B12_GRAPH_10K_PERSISTENT ...` 原始行。
 * 结束后关闭并 `deleteDatabase` 清理测试 DB（不触碰生产 DB）。
 */
@LargeTest
class Graph10kPersistentBaselineTest {

    private lateinit var context: Context
    private var database: AppDatabase? = null

    private val activePerSource = 100
    private val sources = 100
    private val inactiveEvents = 500
    private val rounds = 10

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 上次异常中断留下的测试 DB 先清掉，保证冷启动/文件大小基线干净。
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun cleanupDatabase() {
        runCatching { database?.close() }
        database = null
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun persistentGraphPaginationOn10kEventsReportsRawLatencyBaseline() = runBlocking {
        context.deleteDatabase(DB_NAME)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
        database = db
        var cleanupDeleted = false

        try {
            val seedStart = System.currentTimeMillis()
            val seeded = db.withTransaction { seedGraph(db) }
            val seedMs = System.currentTimeMillis() - seedStart
            val eventDao = db.eventDao()

            // ---- 正确性 ----
            val total = eventDao.countActiveGraph(null)
            assertEquals("active 总数=10,000（inactive 500 被排除）", 10_000, total)
            assertEquals("src-050 active 数", activePerSource, eventDao.countActiveGraph(seeded.filterSource))
            assertEquals(0, eventDao.countActiveGraph("src-missing"))

            val page0 = eventDao.activeGraphPage(null, 50, 0)
            val pageMid = eventDao.activeGraphPage(null, 50, 5_000)
            val pageLast = eventDao.activeGraphPage(null, 50, 9_950)
            assertEquals(50, page0.size)
            assertEquals(50, pageMid.size)
            assertEquals(50, pageLast.size)
            assertTrue("三页均 active", (page0 + pageMid + pageLast).all { it.active })
            assertTrue("id DESC", page0.zipWithNext().all { (a, b) -> a.id > b.id })
            assertTrue("中页 id DESC", pageMid.zipWithNext().all { (a, b) -> a.id > b.id })
            assertFalse("分页不重叠", page0.map { it.id }.toSet().intersect(pageMid.map { it.id }.toSet()).isNotEmpty())
            assertEquals("offset 超界空", 0, eventDao.activeGraphPage(null, 50, 100_000).size)

            val filteredPage = eventDao.activeGraphPage(seeded.filterSource, 50, 0)
            assertEquals("过滤页受 limit=50", 50, filteredPage.size)
            assertTrue("过滤页均属 src-050 chunk", filteredPage.all { it.chunkId in seeded.filterChunkIds })
            assertTrue("过滤页 active", filteredPage.all { it.active })

            // ---- 计时（cold = 首次；warm = 10 轮；文件 DB 含磁盘页缓存/WAL 读取） ----
            eventDao.countActiveGraph(null) // 预热 Room statement cache，不计 cold
            eventDao.activeGraphPage(null, 50, 0)

            val countCold = timed { eventDao.countActiveGraph(null) }
            val countWarm = (1..rounds).map { timed { eventDao.countActiveGraph(null) } }
            val countStats = stats("count_all", countCold, countWarm)

            val pageStats = listOf(0, 5_000, 9_950).associateWith { offset ->
                val cold = timed { eventDao.activeGraphPage(null, 50, offset) }
                val warm = (1..rounds).map { timed { eventDao.activeGraphPage(null, 50, offset) } }
                stats("page50_offset$offset", cold, warm)
            }

            val filterCountStats = stats(
                "count_src050",
                timed { eventDao.countActiveGraph(seeded.filterSource) },
                (1..rounds).map { timed { eventDao.countActiveGraph(seeded.filterSource) } },
            )
            val filterPageStats = stats(
                "page50_src050",
                timed { eventDao.activeGraphPage(seeded.filterSource, 50, 0) },
                (1..rounds).map { timed { eventDao.activeGraphPage(seeded.filterSource, 50, 0) } },
            )

            println(
                "B12_GRAPH_10K_PERSISTENT_SEED db=$DB_NAME active=10000 inactive=$inactiveEvents " +
                    "sources=$sources chunks=${seeded.chunkCount} seed_ms=$seedMs",
            )
            listOf(countStats, filterCountStats, filterPageStats).plus(pageStats.values).forEach { s ->
                println(
                    "B12_GRAPH_10K_PERSISTENT ${s.name} rows=${s.rows} cold_ms=${s.coldMs} " +
                        "warm_p50_ms=${s.p50} warm_p95_ms=${s.p95} warm_max_ms=${s.max} " +
                        "rounds=${s.rounds} target_1500ms_ok=${s.p95 <= 1_500}",
                )
            }
            println(
                "B12_GRAPH_10K_PERSISTENT_PAGE_FILTER rows=${filteredPage.size} " +
                    "count=${eventDao.countActiveGraph(seeded.filterSource)} source=${seeded.filterSource}",
            )
        } finally {
            // 关闭并按任务要求删除测试 DB（含 -wal/-shm），不触碰生产 DB。
            db.close()
            database = null
            context.deleteDatabase(DB_NAME)
            cleanupDeleted = !context.getDatabasePath(DB_NAME).exists()
            println("B12_GRAPH_10K_PERSISTENT_CLEANUP deleted=$cleanupDeleted db=$DB_NAME")
        }
        assertTrue("测试 DB 应已删除", cleanupDeleted)
    }

    // ---- 计时/统计 ----

    private data class Timed(val rows: Int, val ms: Long)

    private data class Stats(
        val name: String,
        val rows: Int,
        val coldMs: Long,
        val p50: Long,
        val p95: Long,
        val max: Long,
        val rounds: Int,
    )

    private inline fun <T> timed(block: () -> T): Timed {
        val start = System.currentTimeMillis()
        val value = block()
        val rows = when (value) {
            is Int -> value
            is List<*> -> value.size
            else -> -1
        }
        return Timed(rows, (System.currentTimeMillis() - start).coerceAtLeast(0))
    }

    private fun stats(name: String, cold: Timed, warm: List<Timed>): Stats {
        val sorted = warm.map { it.ms }.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        return Stats(name, cold.rows, cold.ms, p50, p95, sorted.last(), warm.size)
    }

    // ---- seed（单事务批量写；持久盘下验证提交/fsync 影响的 seed 耗时） ----

    private data class Seeded(
        val filterSource: String,
        val filterChunkIds: Set<Long>,
        val chunkCount: Int,
    )

    private suspend fun seedGraph(db: AppDatabase): Seeded {
        for (sourceIndex in 0 until sources) {
            val sourceId = sourceLabel(sourceIndex)
            db.sourceDao().upsert(SourceEntity(sourceId, "来源$sourceIndex", "", null, 1_000L + sourceIndex, "active"))
            db.documentDao().upsert(
                DocumentEntity(
                    id = "doc-$sourceIndex",
                    sourceId = sourceId,
                    title = "文档$sourceIndex",
                    checksum = "sum-$sourceIndex",
                    parserId = "txt-utf8-v1",
                    createdAt = 1_000L + sourceIndex,
                    status = DocStatus.READY.value,
                ),
            )
        }
        val chunkIds = db.chunkDao().insertAll(
            (0 until sources).map { sourceIndex ->
                ChunkEntity(
                    documentId = "doc-$sourceIndex",
                    ordinal = 0,
                    content = "来源$sourceIndex 的 chunk 内容",
                    charStart = 0,
                    charEnd = 10,
                    tokenEstimate = 10,
                )
            },
        )
        var totalChunks = chunkIds.size

        val activeEvents = ArrayList<EventEntity>(sources * activePerSource)
        for (sourceIndex in 0 until sources) {
            repeat(activePerSource) { eventIndex ->
                val globalIndex = sourceIndex * activePerSource + eventIndex
                activeEvents += event(
                    chunkId = chunkIds[sourceIndex],
                    version = "ev-$globalIndex",
                    active = true,
                    globalIndex = globalIndex,
                )
            }
        }
        db.eventDao().upsertAll(activeEvents)

        val inactiveChunks = db.chunkDao().insertAll(
            (0 until 5).map { idx ->
                ChunkEntity(
                    documentId = "doc-$idx",
                    ordinal = 99,
                    content = "inactive 历史 chunk $idx",
                    charStart = 100,
                    charEnd = 110,
                    tokenEstimate = 3,
                )
            },
        )
        totalChunks += inactiveChunks.size
        db.eventDao().upsertAll(
            (0 until inactiveEvents).map { index ->
                event(
                    chunkId = inactiveChunks[index % inactiveChunks.size],
                    version = "ev-inactive-$index",
                    active = false,
                    globalIndex = 100_000 + index,
                )
            },
        )

        val filterSource = sourceLabel(50)
        val filterChunkIds = db.chunkDao().byDocument("doc-50").map { it.id }.toSet()
        return Seeded(filterSource, filterChunkIds, totalChunks)
    }

    private fun sourceLabel(index: Int): String = "src-%03d".format(index)

    private fun event(chunkId: Long, version: String, active: Boolean, globalIndex: Int): EventEntity =
        EventEntity(
            chunkId = chunkId,
            canonicalText = "事件$globalIndex",
            confidence = 1.0,
            title = "事件$globalIndex",
            summary = "摘要$globalIndex",
            content = "内容$globalIndex",
            language = "zh",
            status = if (active) EventStatus.ACTIVE.value else EventStatus.SUPERSEDED.value,
            extractorVersion = "egemma-sag-extract-v1",
            extractionVersion = version,
            promptVersion = "v1",
            active = active,
            createdAt = 1_000L + globalIndex,
        )

    private companion object {
        const val DB_NAME = "b12-graph10k-persistent.db"
    }
}
