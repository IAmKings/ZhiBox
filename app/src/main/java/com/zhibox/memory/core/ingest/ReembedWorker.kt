package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.search.VectorMatrixCache
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * W4+-1 自动重嵌入（§9.1-7 增强，用户拍板的后期优化落地）：
 * 把"旧向量空间的全部 chunk"后台同步到当前模型空间——切换模型后旧数据自动可搜，
 * 无需手动切回或重新导入。
 *
 * 设计：
 * - 幂等扫描：每次执行扫描全部空间，对"非当前空间且有向量"的空间，将其中尚未写入
 *   当前空间的 chunk 重编码写入（NOT EXISTS 去重；重复执行安全）；
 * - 限速：每批 EMBED_BATCH 条 + 批间 200ms，兼顾发热（温度报告实测满负载 +1.2°C）；
 * - 门槛：电量非低（Constraints）；协作取消（ensureActive + 取消即终止，重跑续做）；
 * - 旧空间向量保留（切回原模型即时可用；存储 ~3KB/chunk 可接受，清理留待后续）；
 * - 触发：设置页切换模型后自动调度 + 设置页手动"同步旧数据"按钮。
 *
 * **锁粒度（P1-4 修复）**：不再整个任务期间持有 EmbeddingRuntime 全局 mutex。每批只在
 * `withProvider { embedDocuments(batch) }` 内持锁，写库与批间 delay 在锁外——重嵌入数分钟
 * 期间检索/导入可正常插队，不再排队等待。每批 `setProgress(done/total)` 让设置页有真实进度。
 */
object ReembedScheduler {

    const val UNIQUE_WORK = "auto-reembed"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReembedWorker>()
            .setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK, ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

class ReembedWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        EmbeddingRuntime.findModel(context)
            ?: return Result.failure(workDataOf("error" to "no model"))
        if (AppDatabase.get(context).ingestJobDao().activeCount(JobStage.ACTIVE_VALUES) > 0) {
            return Result.retry() // 导入进行中：稍后重试（导入完成后 WorkManager 重新调度）
        }
        // P1-4：逐批上报进度（此前只在结束时 setProgress 一次，设置页运行中恒 -1/0）
        val (done, total) = reembedSyncAll(context) { d, t ->
            setProgress(workDataOf("done" to d, "total" to t))
        }
        if (total < 0) return Result.retry() // 执行期间开始导入：稍后重试
        return Result.success()
    }
}

/**
 * 同步主体（Worker 与验证 Runner 共用）：把全部旧空间的 chunk 重编码写入当前模型空间。
 * 返回 (done, total)；total=-1 表示因导入进行中放弃本次执行。
 *
 * @param onProgress 每批写库后回调（done, total），Worker 用它更新 WorkManager progress。
 */
suspend fun reembedSyncAll(
    context: Context,
    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): Pair<Int, Int> {
    val db = AppDatabase.get(context)
    val model = EmbeddingRuntime.findModel(context) ?: return 0 to 0
    if (db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES) > 0) return 0 to -1

    // 第一次短暂持锁：解析目标空间 + 注册 meta + 统计总量（不在此锁内做任何批量编码）
    val (toSpace, total) = EmbeddingRuntime.withProvider(context, model) { provider ->
        // 目标空间注册（meta 行，embedding_meta 唯一写入口）——缺失会导致导出 spaceIdx 回退污染（实测）
        val target = EmbeddingRuntime.registerSpace(context, provider, model)
        var sum = 0
        for (from in db.embeddingDao().allSpaceIds().filter { it != target }) {
            sum += db.embeddingDao().countNeedingReembed(from, target)
        }
        target to sum
    }
    if (total == 0) return 0 to 0
    onProgress(0, total)

    val allSpaces = db.embeddingDao().allSpaceIds().filter { it != toSpace }
    var done = 0
    for (from in allSpaces) {
        val pending = db.embeddingDao().chunksNeedingReembed(from, toSpace)
        for (batch in pending.chunked(IngestRepository.EMBED_BATCH)) {
            currentCoroutineContext().ensureActive()
            // P1-4：锁内只做嵌入；写库/进度/限速都在锁外
            val vectors = EmbeddingRuntime.withProvider(context, model) { provider ->
                provider.embedDocuments(batch.map { it.content })
            }
            if (vectors.size != batch.size) {
                return done to total // 防御：异常返回数量时不写半批（下次运行重做）
            }
            val now = System.currentTimeMillis()
            db.embeddingDao().insertAll(
                batch.zip(vectors) { c, v ->
                    ChunkEmbeddingEntity(
                        chunkId = c.id,
                        embeddingSpaceId = toSpace,
                        role = "content",
                        vectorBlob = ByteBuffer.allocate(v.size * 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .apply { for (f in v) putFloat(f) }.array(),
                        vectorNorm = 1.0,
                        createdAt = now,
                    )
                }
            )
            VectorMatrixCache.invalidate(toSpace) // P1-5：写路径失效
            done += batch.size
            onProgress(done, total)
            delay(200) // 限速：发热控制（锁外）
        }
    }
    // 完成时间戳持久化到 app_kv（schema v3）——跨 WorkManager/进程生命周期，设置页显示
    if (done > 0) {
        AppDatabase.get(context).appKvDao().put(
            AppKvEntity("reembed_last_done", System.currentTimeMillis().toString(),
                System.currentTimeMillis())
        )
    }
    return done to total
}
