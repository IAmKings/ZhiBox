package com.zhibox.memory.core.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-B2-10 向量矩阵缓存上限单测（纯 JVM，不依赖 Android/Robolectric）。
 *
 * B2-10 契约（[VectorMatrixCache]）：
 * - 默认上限 [VectorMatrixCache.DEFAULT_MAX_BYTES] = 64MB；
 * - 单个矩阵本身超过上限 → [VectorMatrixCache.put] 返回 false，**不缓存**（调用方本次仍可用）；
 * - 总量超上限/条目超上限 → 按 LRU 淘汰最久未使用空间；
 * - get 命中会刷新 LRU；同空间旧签名被新矩阵替换；
 * - invalidate(spaceId) 只清该空间，invalidate() 清全部。
 *
 * 覆盖：81MB 超上限不缓存、64MB 恰好可缓存、64MB+8B 不缓存、LRU 淘汰顺序、缩小上限即时淘汰、
 * 同空间替换、失效语义。81MB/64MB 用例使用真实大小分配（单次约 81MB/64MB/64MB），
 * 与生产默认上限同口径；其余用例注入小上限避免重复大分配。
 */
class VectorMatrixCacheLimitTest {

    @Before
    fun setUp() {
        VectorMatrixCache.resetForTest()
    }

    @After
    fun tearDown() {
        VectorMatrixCache.resetForTest()
    }

    @Test
    fun `默认上限是 64MB`() {
        assertEquals(64L * 1024 * 1024, VectorMatrixCache.DEFAULT_MAX_BYTES)
    }

    @Test
    fun `真实 81MB 矩阵不缓存 64MB 恰好可缓存 64MB加8B 不缓存`() {
        val limit = VectorMatrixCache.DEFAULT_MAX_BYTES
        assertEquals(64L * 1024 * 1024, limit)

        // 81MB > 64MB：不缓存（避免大库把进程内存拖死）
        assertFalse("81MB 单矩阵不应缓存", VectorMatrixCache.put(matrix("space-81m", 81L * MB)))
        assertEquals(0, VectorMatrixCache.cachedCountForTest())
        assertEquals(0L, VectorMatrixCache.cachedBytesForTest())
        assertNull(VectorMatrixCache.get("space-81m", 1, 1))

        // 64MB 恰好等于上限：可缓存（判定条件是 bytes > maxBytes）
        assertTrue("64MB 单矩阵应缓存", VectorMatrixCache.put(matrix("space-64m", limit)))
        assertEquals(1, VectorMatrixCache.cachedCountForTest())
        assertEquals(limit, VectorMatrixCache.cachedBytesForTest())
        assertNotNull(VectorMatrixCache.get("space-64m", 1, 1))

        // 清空后测 64MB+8B：超上限不缓存
        VectorMatrixCache.invalidate()
        assertFalse("64MB+8B 单矩阵不应缓存", VectorMatrixCache.put(matrix("space-over", limit + 8)))
        assertEquals(0, VectorMatrixCache.cachedCountForTest())
    }

    @Test
    fun `总量超上限时按 LRU 淘汰最久未使用空间`() {
        VectorMatrixCache.setMaxBytesForTest(3_000)
        assertTrue(VectorMatrixCache.put(matrix("A", 1_000)))
        assertTrue(VectorMatrixCache.put(matrix("B", 1_000)))
        assertTrue(VectorMatrixCache.put(matrix("C", 1_000)))
        assertEquals(3, VectorMatrixCache.cachedCountForTest())

        // 命中 A → A 移到最近使用；最久未用变成 B
        assertNotNull(VectorMatrixCache.get("A", 1, 1))
        assertTrue(VectorMatrixCache.put(matrix("D", 1_000)))

        assertEquals("总字节应保持在注入上限内", 3_000L, VectorMatrixCache.cachedBytesForTest())
        assertNull("最久未使用的 B 应被淘汰", VectorMatrixCache.get("B", 1, 1))
        assertNotNull(VectorMatrixCache.get("A", 1, 1))
        assertNotNull(VectorMatrixCache.get("C", 1, 1))
        assertNotNull(VectorMatrixCache.get("D", 1, 1))
    }

    @Test
    fun `条目数超过 4 时淘汰最久未使用`() {
        VectorMatrixCache.setMaxBytesForTest(1_000_000)
        (1..5).forEach { i ->
            assertTrue(VectorMatrixCache.put(matrix("space-$i", 100)))
        }
        assertEquals("MAX_ENTRIES=4", 4, VectorMatrixCache.cachedCountForTest())
        assertNull("最早写入的空间应被淘汰", VectorMatrixCache.get("space-1", 1, 1))
        (2..5).forEach { i ->
            assertNotNull("空间 space-$i 应保留", VectorMatrixCache.get("space-$i", 1, 1))
        }
    }

    @Test
    fun `缩小上限后立即淘汰到新上限内`() {
        VectorMatrixCache.setMaxBytesForTest(2_000)
        assertTrue(VectorMatrixCache.put(matrix("A", 1_000)))
        assertTrue(VectorMatrixCache.put(matrix("B", 1_000)))
        assertEquals(2_000L, VectorMatrixCache.cachedBytesForTest())

        VectorMatrixCache.setMaxBytesForTest(1_500) // 立即淘汰到 ≤1.5KB
        assertEquals("缩小上限应立即淘汰", 1_000L, VectorMatrixCache.cachedBytesForTest())
        assertEquals(1, VectorMatrixCache.cachedCountForTest())
        assertNull("最久未使用的 A 被淘汰", VectorMatrixCache.get("A", 1, 1))
        assertNotNull(VectorMatrixCache.get("B", 1, 1))
    }

    @Test
    fun `同空间旧签名被新矩阵替换`() {
        VectorMatrixCache.setMaxBytesForTest(10_000)
        assertTrue(VectorMatrixCache.put(matrix("space", 1_000, rowCount = 10, maxVectorId = 100)))
        assertTrue(VectorMatrixCache.put(matrix("space", 2_000, rowCount = 20, maxVectorId = 200)))
        assertEquals("同空间只保留最新矩阵", 1, VectorMatrixCache.cachedCountForTest())
        assertEquals(2_000L, VectorMatrixCache.cachedBytesForTest())
        assertNull("旧签名不再命中", VectorMatrixCache.get("space", 10, 100))
        assertNotNull("新签名命中", VectorMatrixCache.get("space", 20, 200))
    }

    @Test
    fun `失效语义：按空间失效与全量清空`() {
        VectorMatrixCache.setMaxBytesForTest(1_000_000)
        VectorMatrixCache.put(matrix("A", 100))
        VectorMatrixCache.put(matrix("B", 100))
        assertEquals(2, VectorMatrixCache.cachedCountForTest())

        VectorMatrixCache.invalidate("A")
        assertNull(VectorMatrixCache.get("A", 1, 1))
        assertNotNull(VectorMatrixCache.get("B", 1, 1))
        assertEquals("按空间失效需同步扣减总字节", 100L, VectorMatrixCache.cachedBytesForTest())

        VectorMatrixCache.invalidate()
        assertEquals(0, VectorMatrixCache.cachedCountForTest())
        assertEquals(0L, VectorMatrixCache.cachedBytesForTest())
    }

    // ---- 工具 ----

    /** 构造指定 byteSize 的矩阵：byteSize = matrix.size*4 + chunkIds.size*8（本工具只用 matrix 承载）。 */
    private fun matrix(
        spaceId: String,
        bytes: Long,
        rowCount: Int = 1,
        maxVectorId: Long = 1,
    ): SpaceMatrix {
        require(bytes % 4 == 0L) { "matrix 测试载荷必须 4B 对齐：$bytes" }
        val floats = (bytes / 4).toInt()
        return SpaceMatrix(
            spaceId = spaceId,
            rowCount = rowCount,
            maxVectorId = maxVectorId,
            dim = floats,
            chunkIds = LongArray(0),
            matrix = FloatArray(floats),
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
