package com.zhibox.memory.core.ingest

import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.JobStage

/**
 * 导入双状态机一致性模型（P2 B1-10）。
 *
 * **唯一事实源 = `ingest_job.stage`**；`document.status` 是它的派生视图：
 *
 * | job.stage | document.status |
 * |---|---|
 * | QUEUED | PENDING |
 * | PARSING / CHUNKING / EMBEDDING / STORING | PROCESSING |
 * | DONE | READY |
 * | FAILED | FAILED |
 * | CANCELLED | CANCELLED |
 *
 * 两列必须在**同一 Room 事务**内更新（见 `IngestRepository` 的 transition/failOrRequeue/
 * finishCancelled/claimJob/recoverInterrupted/retryFailedJobs）；本对象只承载纯函数判定，
 * 供实现与单测共用，避免"矩阵写死在注释里"。
 *
 * **取消请求过渡态**：`cancelImport()` 只能把 queued job 直接置 cancelled；对已在途
 * （已 claim）的任务，取消请求以 `document.status=cancelled` 表达（P1-2 设计），由
 * processDocument 在批次边界收尾为 cancelled。因此 `doc=cancelled + job∈中间态`是合法的
 * 过渡组合；而 `doc=cancelled` 与 `job=DONE/FAILED/QUEUED` 的组合**非法**——这两个方向
 * 分别由发布守卫（发布前先写 doc，被取消则改为 cancelled）与统一迁移入口保证不会出现。
 */
object IngestStateMachine {

    /**
     * 中间阶段（已 claim、尚未发布/终结）：doc 派生状态为 PROCESSING。
     * 审查修复 S5：直接复用 [JobStage.INTERRUPTED_VALUES] 单一事实源，避免本对象重复手写
     * 同一组阶段（新增阶段时只需改 StatusEnums）。
     */
    val INTERMEDIATE: Set<JobStage> =
        JobStage.INTERRUPTED_VALUES.mapNotNull(JobStage::fromValue).toSet()

    /** 阶段 → 派生文档状态（唯一事实源映射，纯函数） */
    fun documentStatusFor(stage: JobStage): DocStatus = when (stage) {
        JobStage.QUEUED -> DocStatus.PENDING
        JobStage.DONE -> DocStatus.READY
        JobStage.FAILED -> DocStatus.FAILED
        JobStage.CANCELLED -> DocStatus.CANCELLED
        JobStage.PARSING, JobStage.CHUNKING, JobStage.EMBEDDING, JobStage.STORING -> DocStatus.PROCESSING
    }

    /** 取消请求过渡态：canonical 组合之外唯一允许的额外组合（见类注释） */
    fun isCancelRequestTransition(document: DocStatus, stage: JobStage): Boolean =
        document == DocStatus.CANCELLED && stage in INTERMEDIATE

    /**
     * 组合是否一致（用于单测矩阵与诊断；[document] 为 null 表示行缺失，视为不一致）。
     */
    fun isConsistent(document: DocStatus?, stage: JobStage): Boolean {
        if (document == null) return false
        return document == documentStatusFor(stage) || isCancelRequestTransition(document, stage)
    }

    /** doc 是否已冻结在取消态（不允许再被发布/失败覆写） */
    fun isDocumentCancelled(document: DocStatus?): Boolean = document == DocStatus.CANCELLED

    /** 阶段是否终态（DONE/FAILED/CANCELLED），终态不再自动流转 */
    fun isTerminal(stage: JobStage): Boolean = stage in JobStage.TERMINAL
}
