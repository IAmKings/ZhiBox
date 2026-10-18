package com.zhibox.memory.ui

import com.zhibox.memory.data.db.ExtractionJobDao
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus

/**
 * B9：SAG 两阶段抽取的 UI 视图模型（只依赖 data 层实体/状态枚举与 DAO 聚合结果，
 * 不把 DAO/Entity 直接传进 Compose，便于预览与单元测试）。
 *
 * 设计：抽取是独立于导入的第二阶段——抽取失败**不代表**导入失败，UI 必须分开呈现。
 */

/** 全局抽取任务计数（设置页摘要；queued/running/done/failed/not_indexed 五态 + cancelled 诊断）。 */
data class ExtractionCountsUi(
    val queued: Int = 0,
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val notIndexed: Int = 0,
    val cancelled: Int = 0,
) {
    val total: Int get() = queued + running + done + failed + notIndexed + cancelled
    val active: Int get() = queued + running
    val hasJobs: Boolean get() = total > 0
}

/** 单文档抽取聚合（来源页每个 document 一行；由 ExtractionJobDao.byDocument 聚合）。 */
data class ExtractionDocUi(
    val done: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val notIndexed: Int = 0,
    val cancelled: Int = 0,
) {
    val total: Int get() = done + queued + running + failed + notIndexed + cancelled
    val active: Int get() = queued + running
    val hasJobs: Boolean get() = total > 0

    companion object {
        /** 由某文档的抽取任务列表聚合计数（未知历史状态忽略）。 */
        fun from(jobs: List<ExtractionJobEntity>): ExtractionDocUi {
            var done = 0
            var queued = 0
            var running = 0
            var failed = 0
            var notIndexed = 0
            var cancelled = 0
            jobs.forEach { job ->
                when (ExtractionJobStatus.fromValue(job.status)) {
                    ExtractionJobStatus.DONE -> done++
                    ExtractionJobStatus.QUEUED -> queued++
                    ExtractionJobStatus.RUNNING -> running++
                    ExtractionJobStatus.FAILED -> failed++
                    ExtractionJobStatus.NOT_INDEXED -> notIndexed++
                    ExtractionJobStatus.CANCELLED -> cancelled++
                    null -> Unit // 未知/历史值忽略，不阻塞原有文档状态展示
                }
            }
            return ExtractionDocUi(
                done = done,
                queued = queued,
                running = running,
                failed = failed,
                notIndexed = notIndexed,
                cancelled = cancelled,
            )
        }
    }
}

/**
 * B9：读取五态 + cancelled 计数（调用方需在 IO 上下文）。
 * 设置页与导入页共用，避免两处统计口径漂移。
 */
suspend fun ExtractionJobDao.readCounts(): ExtractionCountsUi = ExtractionCountsUi(
    queued = countByStatus(ExtractionJobStatus.QUEUED.value),
    running = countByStatus(ExtractionJobStatus.RUNNING.value),
    done = countByStatus(ExtractionJobStatus.DONE.value),
    failed = countByStatus(ExtractionJobStatus.FAILED.value),
    notIndexed = countByStatus(ExtractionJobStatus.NOT_INDEXED.value),
    cancelled = countByStatus(ExtractionJobStatus.CANCELLED.value),
)
