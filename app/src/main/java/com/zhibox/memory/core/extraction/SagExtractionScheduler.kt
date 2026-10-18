package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import java.util.UUID

/**
 * B9：SAG 抽取任务调度（只做"建任务/改状态/唤醒 Worker"，不执行抽取本身）。
 *
 * 状态语义：
 * - 文档 ready 后调用 [onDocumentReady]：为每个 chunk 建一条 job（document 不变、可离线检索）；
 * - SAG 不可用（未开启/未配置/禁网）→ job=`not_indexed`，用户补齐配置后 [backfill] 置 `queued`；
 * - [backfill]：补建缺失任务 + 把 not_indexed/cancelled 重新入队（整库补抽取）；
 * - [retryFailed]：终态 failed 的任务清零 attempt 后重排队（UI"重试失败"）；
 * - [cancelDocument]：文档取消导入/删除时整篇置 cancelled；
 * - [enqueue]：唤醒 Worker（unique work，约束=联网+电量非低；APPEND_OR_REPLACE 防止"刚查完队列
 *   准备退出"的丢唤醒窗口）。
 *
 * 幂等：同 chunk 至多一条 job（v7 唯一索引）；重复调用只更新状态，不会重复抽取：
 * DONE/QUEUED/RUNNING/FAILED 均保持原状态，仅 not_indexed/cancelled 在可用时重新入队。
 */
object SagExtractionScheduler {

    /**
     * 单次批量扫描上限（backfill/retryFailed 使用）。个人知识库规模远小于此；
     * 上限只防异常膨胀导致一次事务过长，超出部分由后续再次调用处理。
     */
    private const val MAX_SCAN: Int = 10_000

    /**
     * 文档 ready 后登记 SAG 抽取任务（每 chunk 一条，幂等）。
     *
     * @return 新建或重新入队的任务数（0=无需变更）
     */
    suspend fun onDocumentReady(context: Context, db: AppDatabase, documentId: String): Int {
        val chunks = db.chunkDao().byDocument(documentId)
        if (chunks.isEmpty()) return 0

        val available = SagAvailability.isAvailable(context)
        val existingByChunk = db.extractionJobDao().byDocument(documentId).associateBy { it.chunkId }
        val now = System.currentTimeMillis()
        var changed = 0

        db.withTransaction {
            val insertRows = ArrayList<ExtractionJobEntity>(chunks.size)
            val requeueIds = ArrayList<String>()
            for (chunk in chunks) {
                val existing = existingByChunk[chunk.id]
                if (existing == null) {
                    insertRows += ExtractionJobEntity(
                        // job.id 同时是本次抽取的 extractionVersion（重抽取换 UUID）
                        id = UUID.randomUUID().toString(),
                        documentId = documentId,
                        chunkId = chunk.id,
                        status = if (available) {
                            ExtractionJobStatus.QUEUED.value
                        } else {
                            ExtractionJobStatus.NOT_INDEXED.value
                        },
                        attempt = 0,
                        errorCode = if (available) null else ExtractionErrorCodes.NOT_CONFIGURED,
                        errorDetail = if (available) null else SagExtractionSupport.NOT_INDEXED_DETAIL,
                        createdAt = now,
                        updatedAt = now,
                    )
                } else {
                    when (ExtractionJobStatus.fromValue(existing.status)) {
                        // 不可用期间跳过的任务：配置补齐后重新入队（job.id 不变，extractionVersion 稳定）
                        ExtractionJobStatus.NOT_INDEXED,
                        ExtractionJobStatus.CANCELLED -> if (available) requeueIds += existing.id
                        // QUEUED/RUNNING：在途；DONE：已完成；FAILED：留给 retryFailed 手动重试
                        else -> Unit
                    }
                }
            }
            if (insertRows.isNotEmpty()) {
                // IGNORE 兜底并发：另一个调度者已插入 chunk 时返回 -1，不能 REPLACE 覆盖其 RUNNING 状态
                val inserted = db.extractionJobDao().insertIgnoreAll(insertRows)
                changed += inserted.count { it != -1L && it > 0L }
            }
            for (jobId in requeueIds) {
                db.extractionJobDao().resetAttempt(jobId, now)
                db.extractionJobDao().requeue(jobId, ExtractionJobStatus.QUEUED.value, now)
            }
            changed += requeueIds.size
        }
        return changed
    }

    /**
     * 整库补抽取：把 not_indexed/cancelled 任务重新入队，并为缺失任务的 ready 文档补建任务。
     * SAG 不可用时返回 0（UI 应在此之前禁用按钮并提示先配置/联网）。
     *
     * @return 重新入队/新建的任务数；>0 时内部已唤醒 Worker
     */
    suspend fun backfill(context: Context, db: AppDatabase): Int {
        if (!SagAvailability.isAvailable(context)) return 0
        val now = System.currentTimeMillis()
        var changed = 0

        db.withTransaction {
            for (status in listOf(ExtractionJobStatus.NOT_INDEXED, ExtractionJobStatus.CANCELLED)) {
                for (job in db.extractionJobDao().byStatus(status.value, MAX_SCAN)) {
                    val document = db.documentDao().get(job.documentId) ?: continue
                    if (DocStatus.fromValue(document.status) != DocStatus.READY) continue
                    db.extractionJobDao().resetAttempt(job.id, now)
                    db.extractionJobDao().requeue(job.id, ExtractionJobStatus.QUEUED.value, now)
                    changed++
                }
            }
        }

        // 补建缺失任务（历史上未走过 B9 调度 / 人工清库后只剩文档的场景）
        val readyDocuments = db.documentDao().all()
            .filter { DocStatus.fromValue(it.status) == DocStatus.READY }
        for (document in readyDocuments) {
            changed += onDocumentReady(context, db, document.id)
        }

        if (changed > 0) enqueue(context)
        return changed
    }

    /**
     * 重试终态失败任务（attempt 清零后重排队）。SAG 不可用时返回 0。
     *
     * @return 重新入队的任务数；>0 时内部已唤醒 Worker
     */
    suspend fun retryFailed(context: Context, db: AppDatabase): Int {
        if (!SagAvailability.isAvailable(context)) return 0
        val now = System.currentTimeMillis()
        var requeued = 0
        db.withTransaction {
            for (job in db.extractionJobDao().byStatus(ExtractionJobStatus.FAILED.value, MAX_SCAN)) {
                val document = db.documentDao().get(job.documentId) ?: continue
                if (DocStatus.fromValue(document.status) != DocStatus.READY) continue
                db.extractionJobDao().resetAttempt(job.id, now)
                db.extractionJobDao().requeue(job.id, ExtractionJobStatus.QUEUED.value, now)
                requeued++
            }
        }
        if (requeued > 0) enqueue(context)
        return requeued
    }

    /** 文档取消导入/删除时整篇置 cancelled（幂等）。@return 受影响任务数 */
    @Suppress("UNUSED_PARAMETER") // 契约保留 context（与其它调度入口一致，便于未来加约束/通知）
    suspend fun cancelDocument(context: Context, db: AppDatabase, documentId: String): Int =
        db.extractionJobDao().cancelByDocument(
            documentId = documentId,
            cancelledStatus = ExtractionJobStatus.CANCELLED.value,
            now = System.currentTimeMillis(),
        )

    /**
     * 唤醒抽取 Worker：unique work + 联网/电量非低约束。
     * 使用 APPEND_OR_REPLACE：已有 worker 在跑时追加新请求，覆盖"worker 刚查完队列、任务随后入队"
     * 的丢唤醒窗口；多余的链尾 worker 会立刻退出，成本可忽略。
     */
    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SagExtractionWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                SagExtractionWorker.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
    }
}
