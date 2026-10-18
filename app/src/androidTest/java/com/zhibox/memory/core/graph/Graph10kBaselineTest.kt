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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B12 Graph 10k 分页性能基线（instrumentation，in-memory Room，真实 DAO；测试 only）。
 *
 * seed：100 来源 × 1 文档 × 1 chunk × 100 active event = 10,000 active event；
 * 另 seed 500 inactive event（不进入 graph 分页），来源 src-050 有 100 active event 作为过滤子集。
 *
 * 测量（真实 device 上跑，含 JIT/首查冷启动）：
 * - `countActiveGraph(null)`：cold + 10 轮 warm p50/p95/max；
 * - `activeGraphPage(null, 50, offsets 0/5000/9950)`：每页 cold + 10 轮 warm p50/p95/max；
 * - 来源过滤：`countActiveGraph("src-050")` / `activeGraphPage("src-050", 50, 0)` p95。
 *
 * 输出：logcat `System.out` 中 `B12_GRAPH_10K_*` 原始数据，供 B12 验收报告；
 * 不设硬延迟断言（B12 目标 p95≤1.5s，仅打印 target_ok 供对照）；只断言结果/分页/过滤正确性。
 */
class Graph10kBaselineTest {

    private lateinit var db: AppDatabase

    private val activePerSource = 100
    private val sources = 100
    private val inactiveEvents = 500

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
    fun graphPaginationOn10kEventsReportsRawLatencyBaseline() = runBlocking {
        val seedStart = System.currentTimeMillis()
        val seeded = seedGraph()
        val seedMs = System.currentTimeMillis() - seedStart
        val eventDao = db.eventDao()

        // 正确性：active 计数（排除 500 inactive）
        val total = eventDao.countActiveGraph(null)
        assertEquals("active 总数=10,000（inactive 500 被排除）", 10_000, total)
        assertEquals(10_000, eventDao.countActiveGraph(null))
        assertEquals("来源 src-050 的 active 数", activePerSource, eventDao.countActiveGraph(seeded.filterSource))
        assertEquals(0, eventDao.countActiveGraph("src-missing"))

        // 分页正确性：limit=50，offset 0/5000/9950；active 且 id DESC；来源过滤仅本来源
        val page0 = eventDao.activeGraphPage(null, limit = 50, offset = 0)
        val pageMid = eventDao.activeGraphPage(null, limit = 50, offset = 5_000)
        val pageLast = eventDao.activeGraphPage(null, limit = 50, offset = 9_950)
        assertEquals(50, page0.size)
        assertEquals(50, pageMid.size)
        assertEquals(50, pageLast.size)
        assertTrue("三页均 active", (page0 + pageMid + pageLast).all { it.active })
        assertTrue("id 严格 DESC", page0.zipWithNext().all { (a, b) -> a.id > b.id })
        assertTrue("中页 id DESC", pageMid.zipWithNext().all { (a, b) -> a.id > b.id })
        assertFalse("分页不重叠", page0.map { it.id }.toSet().intersect(pageMid.map { it.id }.toSet()).isNotEmpty())
        assertEquals("offset 超界空", 0, eventDao.activeGraphPage(null, 50, 100_000).size)

        val filteredPage = eventDao.activeGraphPage(seeded.filterSource, limit = 50, offset = 0)
        assertEquals("来源过滤分页受 limit=50 约束", 50, filteredPage.size)
        assertEquals("来源过滤总数=100", activePerSource, eventDao.countActiveGraph(seeded.filterSource))
        assertTrue("过滤页均属 src-050 的 chunk", filteredPage.all { it.chunkId in seeded.filterChunkIds })
        assertTrue("过滤页 active", filteredPage.all { it.active })

        // ---- 计时 ----
        val rounds = 10

        // 预热一次（不计入 cold），随后 cold 以第一轮计时
        eventDao.countActiveGraph(null)
        eventDao.activeGraphPage(null, 50, 0)

        val countCold = timed { eventDao.countActiveGraph(null) }
        val countWarm = (1..rounds).map { timed { eventDao.countActiveGraph(null) } }
        val countStats = stats("count_all", countCold, countWarm)

        val pageStats = listOf(0, 5_000, 9_950).associateWith { offset ->
            val cold = timed { eventDao.activeGraphPage(null, 50, offset) }
            val warm = (1..rounds).map { timed { eventDao.activeGraphPage(null, 50, offset) } }
            stats("page50_offset$offset", cold, warm)
        }

        val countFilterCold = timed { eventDao.countActiveGraph(seeded.filterSource) }
        val countFilterWarm = (1..rounds).map { timed { eventDao.countActiveGraph(seeded.filterSource) } }
        val countFilterStats = stats("count_src050", countFilterCold, countFilterWarm)

        val pageFilterCold = timed { eventDao.activeGraphPage(seeded.filterSource, 50, 0) }
        val pageFilterWarm = (1..rounds).map { timed { eventDao.activeGraphPage(seeded.filterSource, 50, 0) } }
        val pageFilterStats = stats("page50_src050", pageFilterCold, pageFilterWarm)

        println(
            "B12_GRAPH_10K_SEED active=10000 inactive=$inactiveEvents sources=$sources " +
                "chunks=${seeded.chunkCount} seed_ms=$seedMs",
        )
        listOf(countStats, countFilterStats, pageFilterStats).plus(pageStats.values).forEach { s ->
            println(
                "B12_GRAPH_10K ${s.name} rows=${s.rows} cold_ms=${s.coldMs} " +
                    "warm_p50_ms=${s.p50} warm_p95_ms=${s.p95} warm_max_ms=${s.max} " +
                    "rounds=${s.rounds} target_1500ms_ok=${s.p95 <= 1_500}",
            )
        }
        println(
            "B12_GRAPH_10K_PAGE_FILTER rows=${filteredPage.size} " +
                "count=${eventDao.countActiveGraph(seeded.filterSource)} source=${seeded.filterSource}",
        )
    }

    // ---- 计时/统计 ----

    private data class Timed(val value: Any, val ms: Long)

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
        return Timed(value as Any, (System.currentTimeMillis() - start).coerceAtLeast(0))
    }

    private fun stats(name: String, cold: Timed, warm: List<Timed>): Stats {
        val sorted = warm.map { it.ms }.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        val rows = when (val v = cold.value) {
            is Int -> v
            is List<*> -> v.size
            else -> -1
        }
        return Stats(name, rows, cold.ms, p50, p95, sorted.last(), warm.size)
    }

    // ---- seed ----

    private data class Seeded(
        val filterSource: String,
        val filterChunkIds: Set<Long>,
        val chunkCount: Int,
    )

    private suspend fun seedGraph(): Seeded {
        // 来源/文档：100 条；chunk 批量一次插入；event 汇总后一次 upsertAll（单事务，seed 更快）。
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

        // 500 条 inactive（散播到前 5 个 chunk；不得计入 graph）
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
        val inactive = (0 until inactiveEvents).map { index ->
            event(
                chunkId = inactiveChunks[index % inactiveChunks.size],
                version = "ev-inactive-$index",
                active = false,
                globalIndex = 100_000 + index,
            )
        }
        db.eventDao().upsertAll(inactive)

        val filterSource = sourceLabel(50)
        val filterChunkIds = db.chunkDao().byDocument("doc-50").map { it.id }.toSet()
        return Seeded(filterSource = filterSource, filterChunkIds = filterChunkIds, chunkCount = totalChunks)
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
}
