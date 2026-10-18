package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.llm.RemoteLlmEventEntityExtractor
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * B9：SAG 抽取后台 Worker（unique work `zhibox-sag-extraction`，约束=联网+电量非低）。
 *
 * 职责边界：
 * - 不承载业务逻辑：构造 [SagExtractionPipeline]（远程 LLM 抽取器 + 端侧嵌入网关）后串行
 *   处理排队任务，直到队列为空；
 * - 启动先 [SagExtractionPipeline.recoverInterrupted]：进程被杀/Worker 被停止遗留的 RUNNING
 *   任务回到 QUEUED（幂等重做，不会丢抽取）；
 * - 取消：CancellationException 在 NonCancellable 中回收 RUNNING 后重新抛出，让 WorkManager
 *   正确感知中断（不当作业务失败，不写 failed）；
 * - 不写日志（项目约定：错误已结构化落在 extraction_job.errorCode/errorDetail）。
 *
 * 依赖：远程 LLM 抽取器见 core/llm/RemoteLlmEventEntityExtractor（build-fixer/task-42）；
 * 本 Worker 只做组装，pipeline 的可用性/失败分类/重试语义在 core/extraction 内自洽。
 */
class SagExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val context = applicationContext
        val db = AppDatabase.get(context)
        val pipeline = SagExtractionPipeline(
            context = context,
            db = db,
            extractor = RemoteLlmEventEntityExtractor(
                provider = OpenAiCompatibleLlmProvider.from(context),
                store = KeystoreLlmConfigStore(context),
            ),
            embeddings = EmbeddingSagGateway(context),
        )

        pipeline.recoverInterrupted()
        try {
            while (pipeline.processNext()) {
                // 协作取消点：WorkManager 停止 Worker 时尽快退出（processJob 内部还有嵌入/DB 取消点）
                currentCoroutineContext().ensureActive()
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { pipeline.recoverInterrupted() }
            throw c
        }
        Result.success()
    }

    companion object {
        /** WorkManager unique work 名（SagExtractionScheduler 入队/设置页观察共用） */
        const val WORK_NAME: String = "zhibox-sag-extraction"
    }
}
