package com.zhibox.memory.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B1-5：状态枚举单源契约（纯 JVM）。
 *
 * 锁三件事：
 * 1) value 字符串与存量数据库兼容（不可随意改名）；
 * 2) 未知/历史值读取返回 null（不崩溃，由调用方原样展示）；
 * 3) 跨列语义集合（活动态/中断态/终态）与枚举成员一致，避免多处手写漂移。
 */
class StatusEnumsTest {

    @Test
    fun sourceStatus_valuesStable_andFromValueCompatible() {
        assertEquals(setOf("active", "deleted"), SourceStatus.values().map { it.value }.toSet())
        assertEquals(SourceStatus.ACTIVE, SourceStatus.fromValue("active"))
        assertEquals(SourceStatus.DELETED, SourceStatus.fromValue("deleted"))
        assertNull(SourceStatus.fromValue("unknown"))
        assertNull(SourceStatus.fromValue(null))
    }

    @Test
    fun docStatus_valuesStable_andFromValueCompatible() {
        assertEquals(
            setOf("pending", "processing", "ready", "failed", "cancelled"),
            DocStatus.values().map { it.value }.toSet(),
        )
        DocStatus.values().forEach { assertEquals(it, DocStatus.fromValue(it.value)) }
        assertNull(DocStatus.fromValue("publishing"))
        assertNull(DocStatus.fromValue(null))
    }

    @Test
    fun jobStage_valuesStable_andFromValueCompatible() {
        assertEquals(
            setOf("queued", "parsing", "chunking", "embedding", "storing", "done", "failed", "cancelled"),
            JobStage.values().map { it.value }.toSet(),
        )
        JobStage.values().forEach { assertEquals(it, JobStage.fromValue(it.value)) }
        assertNull(JobStage.fromValue("mystery"))
        assertNull(JobStage.fromValue(null))
    }

    @Test
    fun crossColumnSemanticSets_matchEnumMembers() {
        assertEquals(setOf("pending", "processing"), DocStatus.ACTIVE_VALUES.toSet())
        assertEquals(
            setOf("queued", "parsing", "chunking", "embedding", "storing"),
            JobStage.ACTIVE_VALUES.toSet(),
        )
        assertEquals(
            setOf("parsing", "chunking", "embedding", "storing"),
            JobStage.INTERRUPTED_VALUES.toSet(),
        )
        assertEquals(setOf(JobStage.DONE, JobStage.FAILED, JobStage.CANCELLED), JobStage.TERMINAL)
        // 不变量：中断态必须是活动态的子集、且与终态不相交
        assertTrue(JobStage.ACTIVE_VALUES.containsAll(JobStage.INTERRUPTED_VALUES))
        assertTrue(JobStage.TERMINAL.map { it.value }.none { it in JobStage.INTERRUPTED_VALUES })
    }

    @Test
    fun everyEnumValueIsNonBlankAndUnique() {
        listOf(
            SourceStatus.values().map { it.value },
            DocStatus.values().map { it.value },
            JobStage.values().map { it.value },
        ).forEach { values ->
            assertTrue(values.all { it.isNotBlank() })
            assertEquals(values.size, values.toSet().size)
        }
    }
}
