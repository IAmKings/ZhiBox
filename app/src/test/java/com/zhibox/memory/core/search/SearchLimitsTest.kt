package com.zhibox.memory.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P1-5/P2-2 检索扩展性单测（纯 JVM）：
 * 查询截断、bigram OR 上限、MATCH 串生成、向量矩阵缓存签名失配。
 */
class SearchLimitsTest {

    @Test
    fun `空查询与超长查询防护`() {
        assertEquals("", SearchService.boundedQuery("   "))
        assertEquals("正常查询", SearchService.boundedQuery("  正常查询  "))
        val long = "中".repeat(2_000)
        assertEquals(SearchService.MAX_QUERY_CHARS, SearchService.boundedQuery(long).length)
        assertEquals(512, SearchService.MAX_QUERY_CHARS)
    }

    @Test
    fun `bigram OR 查询截断到 64 个且去重`() {
        // 100 个互不相同的汉字 → 99 个互不相同的相邻 bigram，必须截断到上限
        val query = (0 until 100).map { (0x4E00 + it).toChar() }.joinToString("")
        val grams = SearchService.limitedGrams(query)
        assertEquals(SearchService.MAX_OR_GRAMS, grams.size)
        assertEquals(64, SearchService.MAX_OR_GRAMS)
        assertEquals(grams.size, grams.distinct().size)
        // 短查询不截断
        assertEquals(4, SearchService.limitedGrams("abcde").size) // ab/bc/cd/de
    }

    @Test
    fun `MATCH 串：空列表返回 null、每项加引号并移除内嵌引号`() {
        assertNull(SearchService.orMatchQuery(emptyList()))
        assertEquals("\"ab\" OR \"bc\"", SearchService.orMatchQuery(listOf("ab", "bc")))
        // 内嵌引号移除（FTS4 MATCH 安全）
        assertEquals("\"ab\"", SearchService.orMatchQuery(listOf("a\"b")))
    }

    @Test
    fun `向量矩阵缓存签名：rowCount 或 maxVectorId 变化即失效`() {
        VectorMatrixCache.invalidate()
        val matrix = SpaceMatrix(
            spaceId = "space-a",
            rowCount = 10,
            maxVectorId = 100,
            dim = 4,
            chunkIds = longArrayOf(1, 2, 3),
            matrix = floatArrayOf(1f, 2f, 3f, 4f),
        )
        VectorMatrixCache.put(matrix)
        // 同签名命中（同一对象引用，避免重复解码）
        assertSame(matrix, VectorMatrixCache.get("space-a", 10, 100))
        // 行数变化（新增/删除/ready 迁移）→ 失效
        assertNull(VectorMatrixCache.get("space-a", 11, 100))
        // 最大 id 变化（重嵌入/替换写入）→ 失效
        assertNull(VectorMatrixCache.get("space-a", 10, 101))
        // 空间变化 → 不命中
        assertNull(VectorMatrixCache.get("space-b", 10, 100))
    }

    @Test
    fun `向量矩阵缓存写入失效与替换`() {
        VectorMatrixCache.invalidate()
        val first = SpaceMatrix("space-c", 1, 1, 2, longArrayOf(1), floatArrayOf(1f, 2f))
        VectorMatrixCache.put(first)
        assertSame(first, VectorMatrixCache.get("space-c", 1, 1))
        // 同空间新矩阵替换旧矩阵
        val second = SpaceMatrix("space-c", 2, 2, 2, longArrayOf(1, 2), floatArrayOf(1f, 2f, 3f, 4f))
        VectorMatrixCache.put(second)
        assertSame(second, VectorMatrixCache.get("space-c", 2, 2))
        assertNull(VectorMatrixCache.get("space-c", 1, 1))
        // 主动失效（写路径调用）
        VectorMatrixCache.invalidate("space-c")
        assertNull(VectorMatrixCache.get("space-c", 2, 2))
        VectorMatrixCache.invalidate()
    }

    @Test
    fun `IN 分批上限不超过 SQLite 变量上限`() {
        assertEquals(400, SearchService.SQL_VARIABLE_BATCH)
        val ids = (1L..1_001L).toList()
        val batches = ids.chunked(SearchService.SQL_VARIABLE_BATCH)
        assertEquals(3, batches.size)
        assertNotSame(batches[0], batches[1])
        batches.forEach { assert(it.size <= SearchService.SQL_VARIABLE_BATCH) }
    }
}
