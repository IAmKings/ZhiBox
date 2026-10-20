package com.zhibox.memory.core.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P1.3c ExplorePerfMonitor（纯 JVM）：
 * - 环形最近 64 次；stats count/p50/p95/max/last；空快照全 0/last=null；reset；
 * - 样本字段（offset/pageSize/result/total/durationMs/usedFts/usedLike/truncated）完整保存；
 * - 基本并发安全（多线程 record 不丢/不崩）。
 */
class ExplorePerfMonitorTest {

    private fun sample(
        durationMs: Long,
        offset: Int = 0,
        pageSize: Int = 10,
        result: ExploreLoadResult = ExploreLoadResult.OK,
        total: Int = 20,
        usedFts: Boolean = true,
        usedLike: Boolean = true,
        truncated: Boolean = false,
    ) = ExploreLoadSample(
        offset = offset,
        pageSize = pageSize,
        result = result,
        total = total,
        durationMs = durationMs,
        usedFts = usedFts,
        usedLike = usedLike,
        truncated = truncated,
    )

    @Test
    fun emptyStatsAreZeroed() {
        ExplorePerfMonitor.reset()
        val stats = ExplorePerfMonitor.stats()
        assertEquals(0, stats.count)
        assertEquals(0L, stats.p50Ms)
        assertEquals(0L, stats.p95Ms)
        assertEquals(0L, stats.maxMs)
        assertNull(stats.last)
        assertTrue(ExplorePerfMonitor.snapshot().isEmpty())
    }

    @Test
    fun percentileAndMaxUseRecordedSamples() {
        ExplorePerfMonitor.reset()
        // 10 样本：1..10 → sorted[10*50/100]=sorted[5]=6；p95=sorted[9]=10
        (1L..10L).forEach { duration -> ExplorePerfMonitor.record(sample(duration, offset = (duration - 1).toInt())) }
        val stats = ExplorePerfMonitor.stats()
        assertEquals(10, stats.count)
        assertEquals(6L, stats.p50Ms)
        assertEquals(10L, stats.p95Ms)
        assertEquals(10L, stats.maxMs)
        assertNotNull(stats.last)
        assertEquals(10L, stats.last!!.durationMs)
        assertEquals(9, stats.last!!.offset)
        assertEquals(ExplorePerfMonitor.CAPACITY, ExplorePerfMonitor.CAPACITY) // 常量自检
        assertTrue(ExplorePerfMonitor.snapshot().zipWithNext().all { (a, b) -> a.durationMs <= b.durationMs })
    }

    @Test
    fun sampleFieldsRoundTripAndResultClassification() {
        ExplorePerfMonitor.reset()
        ExplorePerfMonitor.record(
            sample(
                durationMs = 12,
                offset = 100,
                pageSize = 0,
                result = ExploreLoadResult.EMPTY,
                total = 3_000,
                usedFts = false,
                usedLike = true,
                truncated = true,
            ),
        )
        val last = ExplorePerfMonitor.stats().last!!
        assertEquals(100, last.offset)
        assertEquals(0, last.pageSize)
        assertEquals(ExploreLoadResult.EMPTY, last.result)
        assertEquals(3_000, last.total)
        assertEquals(12L, last.durationMs)
        assertEquals(false, last.usedFts)
        assertTrue(last.usedLike)
        assertTrue(last.truncated)
    }

    @Test
    fun ringKeepsOnlyMostRecent64Samples() {
        ExplorePerfMonitor.reset()
        (1L..70L).forEach { duration ->
            ExplorePerfMonitor.record(sample(duration, offset = duration.toInt(), pageSize = 5, total = 7))
        }
        val stats = ExplorePerfMonitor.stats()
        val snapshot = ExplorePerfMonitor.snapshot()
        assertEquals(ExplorePerfMonitor.CAPACITY, stats.count)
        assertEquals(ExplorePerfMonitor.CAPACITY, snapshot.size)
        assertEquals("保留最近 64 条（offset 7..70）", 7, snapshot.first().offset)
        assertEquals(70, snapshot.last().offset)
        assertEquals(70L, stats.maxMs)
        // 7..70 共64个：sorted[64*50/100]=sorted[32]=39；p95=sorted[60]=67
        assertEquals(39L, stats.p50Ms)
        assertEquals(67L, stats.p95Ms)
    }

    @Test
    fun negativeValuesAreClamped() {
        ExplorePerfMonitor.reset()
        ExplorePerfMonitor.record(
            sample(
                durationMs = -5,
                offset = -2,
                pageSize = -1,
                total = -3,
                result = ExploreLoadResult.EMPTY,
                truncated = true,
            ),
        )
        val last = ExplorePerfMonitor.stats().last!!
        assertEquals(0L, last.durationMs)
        assertEquals(0, last.offset)
        assertEquals(0, last.pageSize)
        assertEquals(0, last.total)
        assertTrue(last.truncated)
    }

    @Test
    fun resetClearsSamples() {
        ExplorePerfMonitor.reset()
        ExplorePerfMonitor.record(sample(12L))
        assertEquals(1, ExplorePerfMonitor.stats().count)
        ExplorePerfMonitor.reset()
        assertEquals(0, ExplorePerfMonitor.stats().count)
    }

    @Test
    fun concurrentRecordsAreThreadSafe() {
        ExplorePerfMonitor.reset()
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        repeat(threads) { threadIndex ->
            pool.execute {
                try {
                    repeat(perThread) { i ->
                        ExplorePerfMonitor.record(sample(durationMs = (threadIndex * perThread + i).toLong()))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("并发记录应在 10s 内完成", latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        val stats = ExplorePerfMonitor.stats()
        assertEquals("环形最多 64 条", ExplorePerfMonitor.CAPACITY, stats.count)
        assertEquals(ExplorePerfMonitor.CAPACITY, ExplorePerfMonitor.snapshot().size)
        assertTrue(stats.p50Ms >= 0 && stats.p95Ms >= 0 && stats.maxMs >= 0)
    }
}
