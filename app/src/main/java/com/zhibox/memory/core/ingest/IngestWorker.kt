package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 导入 Worker（P1-W1-4）：进程被杀后由 WorkManager 重新调度；
 * repository 的幂等清理保证重跑安全（§9.1-4）。
 */
class IngestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT) ?: return Result.failure()
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        return try {
            IngestRepository(applicationContext).processDocument(documentId, jobId)
            Result.success()
        } catch (t: Throwable) {
            // 单文档失败已落库（job=failed 可见原因）。返回 success 让 APPEND 链继续处理
            // 后续文档——Result.failure 会取消整条链（实测 1 个坏文档导致 198 个任务全取消）
            Result.success()
        }
    }

    companion object {
        const val KEY_DOCUMENT = "document_id"
        const val KEY_JOB = "job_id"
    }
}
