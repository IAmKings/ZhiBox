package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 批量导入 Worker（优化②）：单 worker 循环领任务直至无排队。
 *
 * 取代此前的"每文档一个 worker + APPEND 链"——摊薄 worker 起停开销（实测占导入 6-13%），
 * 且任务清单从 DB 读取（nextQueued + claim 原子认领），不受 WorkManager Data 10KB 上限约束。
 *
 * 崩溃/进程被杀：WorkManager 重新调度本 worker，剩余 queued 任务继续（processDocument 幂等）。
 * 新导入在运行中入队：本 worker 的循环会自然拾取（activeCount 守卫已阻止并发批次，此为兜底）。
 */
class IngestBatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = IngestRepository(applicationContext)
        val jobDao = repository.db.ingestJobDao()
        while (true) {
            val job = jobDao.nextQueued() ?: break
            val docId = job.documentId ?: continue
            if (jobDao.claim(job.id, System.currentTimeMillis()) == 0) continue // 已被领走
            // 单文档失败已由 processDocument 落库（job=failed），循环继续处理后续文档
            runCatching { repository.processDocument(docId, job.id) }
        }
        return Result.success()
    }
}
