package com.zhibox.memory.core.ingest

import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.JobStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-10 双状态机纪律单测（纯函数/枚举层，无需 Room）：
 * - `ingest_job.stage` → `document.status` 派生映射；
 * - 合法组合矩阵 = canonical 组合 + 取消请求过渡态（doc=cancelled + 在途阶段）；
 * - 终态判定。
 */
class IngestStateMachineTest {

    @Test
    fun `阶段到派生文档状态映射`() {
        assertEquals(DocStatus.PENDING, IngestStateMachine.documentStatusFor(JobStage.QUEUED))
        assertEquals(DocStatus.PROCESSING, IngestStateMachine.documentStatusFor(JobStage.PARSING))
        assertEquals(DocStatus.PROCESSING, IngestStateMachine.documentStatusFor(JobStage.CHUNKING))
        assertEquals(DocStatus.PROCESSING, IngestStateMachine.documentStatusFor(JobStage.EMBEDDING))
        assertEquals(DocStatus.PROCESSING, IngestStateMachine.documentStatusFor(JobStage.STORING))
        assertEquals(DocStatus.READY, IngestStateMachine.documentStatusFor(JobStage.DONE))
        assertEquals(DocStatus.FAILED, IngestStateMachine.documentStatusFor(JobStage.FAILED))
        assertEquals(DocStatus.CANCELLED, IngestStateMachine.documentStatusFor(JobStage.CANCELLED))
    }

    @Test
    fun `canonical 组合全部一致`() {
        JobStage.entries.forEach { stage ->
            val document = IngestStateMachine.documentStatusFor(stage)
            assertTrue(
                "canonical 组合应一致：doc=$document / job=$stage",
                IngestStateMachine.isConsistent(document, stage),
            )
        }
    }

    @Test
    fun `取消请求过渡态：doc cancelled 与在途阶段合法`() {
        IngestStateMachine.INTERMEDIATE.forEach { stage ->
            assertTrue(
                "取消请求过渡态应合法：doc=CANCELLED / job=$stage",
                IngestStateMachine.isConsistent(DocStatus.CANCELLED, stage),
            )
        }
        // 取消已收尾：job 也是 cancelled
        assertTrue(IngestStateMachine.isConsistent(DocStatus.CANCELLED, JobStage.CANCELLED))
        // 但 doc=cancelled 不能与排队态/终态（DONE/FAILED）共存——B1-10 明确禁止的组合
        assertFalse(IngestStateMachine.isConsistent(DocStatus.CANCELLED, JobStage.QUEUED))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.CANCELLED, JobStage.DONE))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.CANCELLED, JobStage.FAILED))
    }

    @Test
    fun `典型非法组合示例`() {
        // 发布未同步 / 失败未同步 / 反向错位
        assertFalse(IngestStateMachine.isConsistent(DocStatus.READY, JobStage.PARSING))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.PENDING, JobStage.EMBEDDING))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.FAILED, JobStage.DONE))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.PROCESSING, JobStage.QUEUED))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.READY, JobStage.CANCELLED))
        assertFalse(IngestStateMachine.isConsistent(DocStatus.PENDING, JobStage.DONE))
        // 文档行缺失视为不一致（调用方可据此诊断孤儿 job）
        assertFalse(IngestStateMachine.isConsistent(null, JobStage.DONE))
    }

    @Test
    fun `全枚举矩阵只有 canonical 与取消过渡被接受`() {
        var accepted = 0
        for (stage in JobStage.entries) {
            for (document in DocStatus.entries) {
                val canonical = document == IngestStateMachine.documentStatusFor(stage)
                val cancelRequest = document == DocStatus.CANCELLED &&
                    stage in IngestStateMachine.INTERMEDIATE
                assertEquals(
                    "组合判定错误：doc=$document / job=$stage",
                    canonical || cancelRequest,
                    IngestStateMachine.isConsistent(document, stage),
                )
                if (canonical || cancelRequest) accepted++
            }
        }
        // 8 个 canonical + 4 个取消过渡（INTERMEDIATE 4 阶段）= 12
        assertEquals(12, accepted)
    }

    @Test
    fun `终态判定`() {
        assertTrue(IngestStateMachine.isTerminal(JobStage.DONE))
        assertTrue(IngestStateMachine.isTerminal(JobStage.FAILED))
        assertTrue(IngestStateMachine.isTerminal(JobStage.CANCELLED))
        assertFalse(IngestStateMachine.isTerminal(JobStage.QUEUED))
        assertFalse(IngestStateMachine.isTerminal(JobStage.PARSING))
        assertFalse(IngestStateMachine.isTerminal(JobStage.CHUNKING))
        assertFalse(IngestStateMachine.isTerminal(JobStage.EMBEDDING))
        assertFalse(IngestStateMachine.isTerminal(JobStage.STORING))
    }

    @Test
    fun `取消冻结判定`() {
        assertTrue(IngestStateMachine.isDocumentCancelled(DocStatus.CANCELLED))
        assertFalse(IngestStateMachine.isDocumentCancelled(DocStatus.PROCESSING))
        assertFalse(IngestStateMachine.isDocumentCancelled(DocStatus.READY))
        assertFalse(IngestStateMachine.isDocumentCancelled(null))
    }
}
