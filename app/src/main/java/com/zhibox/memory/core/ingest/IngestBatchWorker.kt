package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * 批量导入 Worker（优化②）：单 worker 循环领任务直至无排队。
 *
 * 取代此前的"每文档一个 worker + APPEND 链"——摊薄 worker 起停开销（实测占导入 6-13%），
 * 且任务清单从 DB 读取（nextQueued + claim 原子认领），不受 WorkManager Data 10KB 上限约束。
 *
 * 崩溃/进程被杀：WorkManager 重新调度本 worker，剩余 queued 任务继续（processDocument 幂等）。
 * 新导入在运行中入队：本 worker 的循环会自然拾取（activeCount 守卫已阻止并发批次，此为兜底）。
 *
 * P1-2 修复：
 * - 取消异常重新抛出（此前 `runCatching` 会吞掉 CancellationException 继续循环）；
 * - 普通异常不影响后续文档（processDocument 已把 failed/requeued 落库）；
 * - `Error`（OOM 等）直接抛出，不当作业务失败继续跑；
 * - documentId 为空的坏 job 直接标 failed（否则 nextQueued 反复命中 → 死循环）。
 *
 * B1-10：Worker 不再直接写状态列。认领/回收/无文档失败全部经 [IngestRepository] 的
 * 事务化入口（claimJob / recoverInterrupted / failJobWithoutDocument），保证
 * `ingest_job.stage`（唯一事实源）与派生 `document.status` 同步更新。
 */
class IngestBatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = IngestRepository(applicationContext)
        // B1-10：同一事务回收上一代 worker 被杀遗留的中间态任务（job→queued）
        // 并把对应文档派生状态复位 pending；claim 只认 queued，不回收则永久悬挂。
        repository.recoverInterrupted()
        while (true) {
            val job = repository.nextQueuedJob() ?: break
            val documentId = job.documentId
            if (documentId.isNullOrBlank()) {
                // 无文档可处理：置终态防止 nextQueued 无限命中（P1-2）
                repository.failJobWithoutDocument(job.id)
                continue
            }
            // B1-10：原子认领 + 派生 doc=PROCESSING（同一事务）
            if (!repository.claimJob(job)) continue // 已被领走
            try {
                repository.processDocument(documentId, job.id)
            } catch (c: CancellationException) {
                // 系统停止 Worker：processDocument 已在 NonCancellable 中清理并重排队；
                // 必须继续向上抛出让 WorkManager 感知中断（P1-2）
                throw c
            } catch (e: Error) {
                throw e
            } catch (e: Exception) {
                // 兜底：单文档失败已由 processDocument 落库（failed 或 requeued），继续下一篇。
                // 不写 Log（仓库约定 0 日志：错误已结构化落在 ingest_job.error，避免路径/内容泄漏）。
            }
        }
        return Result.success()
    }
}
