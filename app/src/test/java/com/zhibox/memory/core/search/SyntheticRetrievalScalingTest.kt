package com.zhibox.memory.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * P2-B5-3：检索规模回归基线（纯 JVM 合成库）。
 *
 * 目标：在**不依赖真机/模型/Room**的前提下，锁定：
 * 1) 10k chunk × 768d 的向量通道扫描 p50/p95 与堆内存增量；
 * 2) 10k 候选的关键词覆盖分重排成本；
 * 3) 100k 规模走流式扫描门限（不实际分配 300MB 矩阵）。
 *
 * 输出前缀 `B5_10K_*` / `B5_100K_*` 由 CI/人工归档到
 * `docs/review/b5/10k-baseline.json`；阈值只做病态兜底（不同机器绝对 ms 不可直接比较）。
 */
class SyntheticRetrievalScalingTest {

    private companion object {
        const val ROWS = 10_000
        const val DIM = 768
        const val WARMUP = 3
        const val ROUNDS = 10
        const val TOP_K = 10
        // 阈值只做病态兜底（实测向量 p95=4ms、关键词 p95=436ms，留 ~100x/4x 余量防慢机误报）
        const val VECTOR_P95_LIMIT_MS = 500L
        const val KEYWORD_P95_LIMIT_MS = 2_000L
        // 数据量：10_000×768 float = 29MB。used heap（非增量/峰值）受 GC 时机影响，
        // debug/release-unit 实测 46–235MB 波动 → 仅作病态兜底，基线以打印值为准
        // （见 docs/review/b5/10k-baseline.json）。
        const val HEAP_LIMIT_MB = 1024L
    }

    @Test
    fun synthetic10kVectorScan_recordsP50P95AndHeapDelta() {
        val n = ROWS
        val matrix = FloatArray(n * DIM)
        // 确定性伪随机填充（线性同余），避免 Random 依赖与分布噪声
        var seed = 0x2545F4914F6CDD1DL
        for (i in matrix.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            matrix[i] = (((seed ushr 11) and 0xFF).toFloat() - 128f) / 128f
        }
        val query = FloatArray(DIM) { (it % 7).toFloat() - 3f }
        val qNorm = sqrt(query.fold(0.0) { acc, v -> acc + v.toDouble() * v })
        val ids = LongArray(n) { it.toLong() }

        fun scanOnce(): List<Long> {
            val bestScore = DoubleArray(TOP_K) { Double.NEGATIVE_INFINITY }
            val bestId = LongArray(TOP_K)
            for (r in 0 until n) {
                val base = r * DIM
                var dot = 0.0
                for (d in 0 until DIM) dot += matrix[base + d] * query[d]
                val score = if (qNorm == 0.0) 0.0 else dot / qNorm
                if (score > bestScore[TOP_K - 1]) {
                    var pos = TOP_K - 1
                    while (pos > 0 && bestScore[pos - 1] < score) {
                        bestScore[pos] = bestScore[pos - 1]
                        bestId[pos] = bestId[pos - 1]
                        pos--
                    }
                    bestScore[pos] = score
                    bestId[pos] = ids[r]
                }
            }
            return bestId.toList()
        }

        repeat(WARMUP) { scanOnce() }
        val times = LongArray(ROUNDS)
        var result: List<Long> = emptyList()
        for (i in 0 until ROUNDS) {
            val t0 = System.nanoTime()
            result = scanOnce()
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95 / 100).coerceAtMost(times.lastIndex)]
        // 说明：这是 JVM 当前 used heap，不是"申请前/后差值"或峰值；受 GC 时机影响，
        // 只用于基线记录与病态兜底（见 JSON 的 heap_used_mb_range）。
        val heapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        println(
"B5_10K_VECTOR_SCAN rows=$n dim=$DIM topK=$TOP_K warmup=$WARMUP rounds=$ROUNDS " +
                "p50_ms=$p50 p95_ms=$p95 heap_used_mb=$heapUsedMb matrix_mb=${matrix.size * 4L / (1024 * 1024)} " +
                "result=${result.size}",
        )
        assertEquals(TOP_K, result.size)
        assertTrue("向量扫描 p95 异常（${p95}ms）", p95 < VECTOR_P95_LIMIT_MS)
        assertTrue("10k 矩阵 used heap 异常（${heapUsedMb}MB）", heapUsedMb < HEAP_LIMIT_MB)
    }

    @Test
    fun synthetic10kKeywordRerank_recordsP50P95() {
        // 10k 候选，每条 content = 1200 字符（300×"合成语料"；模拟 1 chunk/文档的极端候选规模）
        val content = buildString { repeat(300) { append("合成语料") } } // 1200 chars
        val contentById = HashMap<Long, String>(ROWS)
        for (i in 0 until ROWS) contentById[i.toLong()] = content
        val queryGrams = setOf("合成", "成语", "语料")
        val hitIds = (0 until ROWS).map { it.toLong() }
        val cap = 120

        repeat(WARMUP) { SearchService.rankKeywordHits(hitIds, contentById, queryGrams, cap) }
        val times = LongArray(ROUNDS)
        var result: List<Long> = emptyList()
        for (i in 0 until ROUNDS) {
            val t0 = System.nanoTime()
            result = SearchService.rankKeywordHits(hitIds, contentById, queryGrams, cap)
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95 / 100).coerceAtMost(times.lastIndex)]
        println(
            "B5_10K_KEYWORD_RERANK rows=$ROWS content_chars=1200 topK_cap=$cap warmup=$WARMUP " +
                "rounds=$ROUNDS p50_ms=$p50 p95_ms=$p95 result=${result.size}",
        )
        assertEquals("候选截断在排序后", cap, result.size)
        assertTrue("关键词重排 p95 异常（${p95}ms）", p95 < KEYWORD_P95_LIMIT_MS)
    }

    @Test
    fun synthetic100k_mustUseStreamingScan_notFullMatrix() {
        val estimated = SearchService.estimatedMatrixBytes(100_000, DIM)
        val streaming = SearchService.shouldStreamMatrix(100_000, DIM)
        println(
            "B5_100K_ESTIMATE rows=100000 dim=$DIM estimated_matrix_mb=${estimated / (1024 * 1024)} " +
                "streaming=$streaming stream_page_size=${SearchService.STREAM_PAGE_SIZE}",
        )
        assertTrue("100k×768 矩阵预估应约 293MB（实际 ${estimated}B）", estimated in 290L * 1024 * 1024..310L * 1024 * 1024)
        assertTrue("100k 必须走流式扫描（避免 300MB 矩阵 OOM）", streaming)
        val pages = (100_000 + SearchService.STREAM_PAGE_SIZE - 1) / SearchService.STREAM_PAGE_SIZE
        println("B5_100K_STREAM pages=$pages page_size=${SearchService.STREAM_PAGE_SIZE}")
        assertTrue("流式分页数应 > 90", pages > 90)
    }
}
