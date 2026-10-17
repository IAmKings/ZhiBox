package com.zhibox.memory.core.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-B2 10k 合成 FTS 性能基线（instrumentation；不依赖嵌入模型）。
 *
 * 目的：为 B2-1「关键词通道取回全部命中（安全上限 5000），不再按 topK×3×4 截断」
 * 提供 10k chunk 规模下的**可复跑基线数据**：DB 用 Room in-memory 建 AppDatabase，
 * seed 100 文档 × 100 chunk = 10,000 chunk + 10,000 条 chunk_fts（bigram 文本），
 * 用现有 DAO（`ChunkDao.searchFtsOrdered(matchQuery, limit, readyStatus)`，检索关键词通道
 * 同一条 SQL）跑三类查询：
 * - 稀有词（1 命中）：验证召回正确性；
 * - 中等词（5000 命中）：验证 DAO 层“取回全部命中”的能力（B2-1 改前 SearchService 只请求
 *   topK×3×4=120 条，改后请求到安全上限 5000；本用例直接以 limit=5000 固化 DAO 基线）；
 * - 全命中（10000 命中）：验证安全上限 5000 生效（按 rowid 截断）。
 *
 * 输出（logcat/`println`）可直接喂给 Lead 做改前/改后对照：
 * `B2_10K_FTS query=<q> hits=<n> cold_ms=<n> warm_p50_ms=<n> warm_p95_ms=<n> rounds=30`
 *
 * 复跑口径：`./gradlew :app:connectedDebugAndroidTest --tests "*Fts10kPerfTest*"`（由 Lead 统一执行）。
 * 不把耗时设为硬门禁（仅 5s 上限防病态回归），基线以输出为准。
 */
class Fts10kPerfTest {

    private lateinit var db: AppDatabase

    private data class PerfResult(
        val query: String,
        val hits: List<Long>,
        val coldMs: Long,
        val warmP50Ms: Long,
        val warmP95Ms: Long,
        val rounds: Int,
    )

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
    fun fts10k_keywordQuery_baseline_coldAndWarm() = runBlocking {
        val rareChunkId = seed10k()

        assertEquals("seed 后 chunk 数", CHUNK_COUNT, db.chunkDao().count())
        assertEquals("seed 后 chunk_fts 数（B1-1 去重口径）", CHUNK_COUNT, db.chunkDao().ftsCount())
        println(
            "B2_10K_FTS_SEED docs=$DOC_COUNT chunks=${db.chunkDao().count()} " +
                "fts_rows=${db.chunkDao().ftsCount()} limit=$MAX_KEYWORD_CANDIDATES",
        )
        // B2-1 安全上限：把"测试口径 5000"与生产常量绑定，任何改动都会被本用例暴露
        assertEquals(
            "B2-1 关键词全量上限应为 5000（SearchService.KEYWORD_FULL_HIT_LIMIT）",
            MAX_KEYWORD_CANDIDATES,
            SearchService.KEYWORD_FULL_HIT_LIMIT,
        )

        // 1) 稀有词：只有 chunk #0 含 "独一无二罕见标记" → 关键词候选应恰好命中该 chunk
        val rare = measure("罕见", limit = MAX_KEYWORD_CANDIDATES)
        printResult(rare)
        assertEquals("稀有词应恰好 1 个候选：${rare.query}", listOf(rareChunkId), rare.hits)

        // 2) 中等词：5000 个 chunk 含 "迁移测试"（B2-1 前被 topK*3*4=120 截断 → 本用例固化"全量"）
        val mid = measure("迁移测试", limit = MAX_KEYWORD_CANDIDATES)
        printResult(mid)
        assertEquals(
            "5000 命中在安全上限内应全部返回（B2-1：不再按 topK×3×4 截断）",
            MID_HITS,
            mid.hits.size,
        )
        assertStableChunkIdOrder(mid.query, mid.hits)

        // 3) 全命中 10000 → 安全上限 5000 生效（按 rowid 截断），且前 5000 条有序
        val all = measure("合成语料", limit = MAX_KEYWORD_CANDIDATES)
        printResult(all)
        assertEquals("超过安全上限时应截断到 $MAX_KEYWORD_CANDIDATES", MAX_KEYWORD_CANDIDATES, all.hits.size)
        assertStableChunkIdOrder(all.query, all.hits)

        // 4) limit 显式生效（小 limit 不越界）
        val small = db.chunkDao().searchFtsOrdered(
            BigramTokenizer.toOrMatchQuery("迁移测试"), 120, DocStatus.READY.value,
        )
        assertEquals("limit=120 应返回 120 条", 120, small.size)

        // 耗时仅做病态回归兜底（in-memory 10k chunk 预期 p95 << 5s）
        for (r in listOf(rare, mid, all)) {
            assertTrue(
                "关键词查询 p95 异常（${r.query}：${r.warmP95Ms}ms）——疑似退化为全表/Kotlin 侧全量物化",
                r.warmP95Ms < 5_000,
            )
        }
        println(
            "B2_10K_FTS_SUMMARY chunks=${CHUNK_COUNT} fts_rows=${CHUNK_COUNT} " +
                "rare_hits=1 mid_hits=$MID_HITS capped_hits=$MAX_KEYWORD_CANDIDATES",
        )
    }

    /**
     * 查询一次（冷）→ 预热 5 轮 → 30 轮计时。返回最后一轮命中列表。
     * 口径：DAO 原始 SQL（与 SearchService 关键词通道同一查询），不含 Kotlin 侧重排。
     */
    private suspend fun measure(
        query: String,
        limit: Int,
        rounds: Int = 30,
        warmup: Int = 5,
    ): PerfResult {
        val orQuery = BigramTokenizer.toOrMatchQuery(query)
        val coldStart = System.nanoTime()
        var hits = db.chunkDao().searchFtsOrdered(orQuery, limit, DocStatus.READY.value)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000
        repeat(warmup) {
            hits = db.chunkDao().searchFtsOrdered(orQuery, limit, DocStatus.READY.value)
        }
        val times = LongArray(rounds)
        for (i in 0 until rounds) {
            val t0 = System.nanoTime()
            hits = db.chunkDao().searchFtsOrdered(orQuery, limit, DocStatus.READY.value)
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        return PerfResult(
            query = query,
            hits = hits,
            coldMs = coldMs,
            warmP50Ms = percentile(times, 0.50),
            warmP95Ms = percentile(times, 0.95),
            rounds = rounds,
        )
    }

    private fun percentile(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun printResult(r: PerfResult) {
        println(
            "B2_10K_FTS query=${r.query} hits=${r.hits.size} cold_ms=${r.coldMs} " +
                "warm_p50_ms=${r.warmP50Ms} warm_p95_ms=${r.warmP95Ms} rounds=${r.rounds}",
        )
    }

    private fun assertStableChunkIdOrder(query: String, hits: List<Long>) {
        // DAO 契约：ORDER BY chunk_fts.rowid；本 seed 恰为顺序插入，故 chunkId 与 rowid 同序。
        // 注意（审查）：FTS 重建（rebuildFtsIndex 按 documentId,ordinal 写）后 rowid 会变，
        // 该断言只验证“本次 seed 下的确定性输出”，不能外推为 rowid 语义。
        for (i in 1 until hits.size) {
            assertTrue("查询 $query 的候选未按 chunkId 升序", hits[i] > hits[i - 1])
        }
    }

    /** 100 文档 × 100 chunk = 10,000；返回稀有词 chunk 的 id。 */
    private suspend fun seed10k(): Long {
        db.sourceDao().upsert(SourceEntity("src_perf", "perf-corpus", "", null, 0, "active"))
        db.documentDao().upsertAll(
            (0 until DOC_COUNT).map { d ->
                DocumentEntity(
                    id = docId(d),
                    sourceId = "src_perf",
                    title = "合成文档$d",
                    checksum = "sum_$d",
                    parserId = "txt-utf8-v1",
                    createdAt = d.toLong(),
                    status = DocStatus.READY.value,
                )
            },
        )

        val chunks = ArrayList<ChunkEntity>(CHUNK_COUNT)
        for (d in 0 until DOC_COUNT) {
            for (o in 0 until CHUNKS_PER_DOC) {
                val globalIndex = d * CHUNKS_PER_DOC + o
                val content = when {
                    globalIndex == 0 -> "独一无二罕见标记；合成语料第${globalIndex}段"
                    globalIndex % 2 == 0 -> "迁移测试 合成语料第${globalIndex}段"
                    else -> "量子纠缠 合成语料第${globalIndex}段"
                }
                chunks += ChunkEntity(
                    documentId = docId(d),
                    ordinal = o,
                    content = content,
                    charStart = 0,
                    charEnd = content.length,
                    tokenEstimate = content.length,
                )
            }
        }
        val ids = ArrayList<Long>(CHUNK_COUNT)
        for (batch in chunks.chunked(INSERT_BATCH)) {
            ids += db.chunkDao().insertAll(batch)
        }
        assertEquals(CHUNK_COUNT, ids.size)
        val ftsRows = chunks.mapIndexed { i, c ->
            ChunkFtsEntity(chunkId = ids[i], bigramContent = BigramTokenizer.toFtsText(c.content))
        }
        for (batch in ftsRows.chunked(INSERT_BATCH)) {
            db.chunkDao().insertFts(batch)
        }
        // 稀有词所在的 chunk 即全局第一个 chunk（id 由 AUTOINCREMENT 从 1 开始）
        return ids[0]
    }

    private fun docId(d: Int): String = "doc_%03d".format(d)

    private companion object {
        const val DOC_COUNT = 100
        const val CHUNKS_PER_DOC = 100
        const val CHUNK_COUNT = DOC_COUNT * CHUNKS_PER_DOC
        // 偶数 chunk 放 "迁移测试"，但 globalIndex==0 被稀有词占用 → 5000 个偶数里少 1 个
        const val MID_HITS = CHUNK_COUNT / 2 - 1
        const val INSERT_BATCH = 500
        const val MAX_KEYWORD_CANDIDATES = 5000
    }
}
