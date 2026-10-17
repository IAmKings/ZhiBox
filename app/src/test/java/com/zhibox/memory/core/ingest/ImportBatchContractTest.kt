package com.zhibox.memory.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-13 批量导入结果契约（纯 JVM，无需 Room）：ui-fixer 依赖的 `importStaged(): ImportBatchResult`
 * 字段语义锁定，避免 UI 接线时字段漂移。
 */
class ImportBatchContractTest {

    @Test
    fun `空批次结果为 0 登记 0 重复`() {
        val result = ImportBatchResult(documentIds = emptyList(), duplicateCount = 0)
        assertTrue(result.documentIds.isEmpty())
        assertEquals(0, result.duplicateCount)
        assertEquals(0, result.enqueuedCount)
    }

    @Test
    fun `新登记与重复计数分别独立`() {
        val result = ImportBatchResult(
            documentIds = listOf("doc_a", "doc_b"),
            duplicateCount = 3,
        )
        assertEquals(listOf("doc_a", "doc_b"), result.documentIds)
        assertEquals(3, result.duplicateCount)
        // 便捷属性只反映新登记，不把 duplicate 混入
        assertEquals(2, result.enqueuedCount)
        assertNotEquals(result.documentIds.size, result.duplicateCount)
    }

    @Test
    fun `data class 值语义与 copy（UI State 比较依赖）`() {
        val a = ImportBatchResult(listOf("doc_x"), 1)
        val b = ImportBatchResult(listOf("doc_x"), 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val copied = a.copy(duplicateCount = 2)
        assertEquals(listOf("doc_x"), copied.documentIds)
        assertEquals(2, copied.duplicateCount)
        assertEquals(1, a.duplicateCount) // copy 不修改原对象
    }

    @Test
    fun `documentIds 与 duplicateCount 的类型契约（List String Int）`() {
        // 编译期锁定契约：documentIds 是 List<String>，duplicateCount 是 Int
        val result: ImportBatchResult = ImportBatchResult(
            documentIds = listOf("doc_1"),
            duplicateCount = 1,
        )
        val ids: List<String> = result.documentIds
        val duplicates: Int = result.duplicateCount
        assertEquals(1, ids.size)
        assertEquals(1, duplicates)
    }
}
