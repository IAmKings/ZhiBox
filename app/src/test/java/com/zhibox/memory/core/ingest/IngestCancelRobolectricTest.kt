package com.zhibox.memory.core.ingest

import android.app.Application
import androidx.room.withTransaction
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * B5-1 Robolectric（JVM，真实 Room + Robolectric 临时目录）：导入状态机取消路径。
 *
 * 覆盖（不触发 WorkManager 实际执行：只用 `importFile`（仅登记、不入队 Worker）+ DAO/Repository）：
 * 1) 导入登记（job=queued / doc=pending）→ `cancelImport()` 后：
 *    - 排队中 job → cancelled、pending 文档 → cancelled；
 *    - 已完成文档（job=done、doc=ready）不受影响（ready 不被越权取消）；
 * 2) 取消后 `recoverInterrupted()` / `requeueInterrupted(...)` **不复活** cancelled：
 *    - 已 cancelled 的 job 不在 INTERRUPTED_VALUES 集合，requeue 返回 0 且状态不变；
 *    - 在途 job=parsing + doc=processing 的场景：cancelImport 先把 doc 标 cancelled；
 *      recoverInterrupted 先 requeue parsing，随后因 doc=cancelled 在同一事务内把 job 收尾为
 *      cancelled —— 最终无 queued/active 残留（doc=cancelled + job=queued 非法组合不会出现）。
 *
 * 说明：`AppDatabase.get(context)` 是进程单例；Robolectric 每个测试方法有独立 Application/临时
 * 目录，因此通过反射清空/关闭单例保证测试隔离（仅测试代码，不改 main）。
 * 运行前提：`app/build.gradle.kts` 已加入 Robolectric 测试依赖（build-fixer 负责）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IngestCancelRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository
    private lateinit var staging: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        staging = File(app.filesDir, "import-staging").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        repository = IngestRepository(app)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        staging.listFiles()?.forEach { it.delete() }
        File(app.filesDir, "documents").listFiles()?.forEach { it.delete() }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    @Test
    fun cancelImport_cancelsQueuedJobsAndPendingDocs_butKeepsReadyDocumentIntact() = runBlocking {
        val readyDocId = (repository.importFile(stage("ready.md", "# 已完成文档")) as ImportResult.Enqueued).documentId
        val cancelledDocId = (repository.importFile(stage("cancel.md", "# 待取消文档")) as ImportResult.Enqueued).documentId
        assertEquals("登记后应只有 queued/ pending 状态", 2, activeJobCount())

        // 模拟 readyDoc 已完成：job=done、doc=ready（同一事务，模拟发布语义）
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.ingestJobDao().upsert(
                    jobOf(readyDocId).copy(stage = JobStage.DONE.value, progress = 100),
                )
                db.documentDao().updateStatusIfNotCancelled(
                    readyDocId, DocStatus.READY.value, DocStatus.CANCELLED.value,
                )
            }
        }
        assertEquals(JobStage.DONE.value, jobOf(readyDocId).stage)
        assertEquals(DocStatus.READY.value, db.documentDao().get(readyDocId)?.status)

        // 取消：只应影响 queued job + pending 文档
        val cancelled = repository.cancelImport()
        assertEquals("应取消 1 个排队 job + 1 个 pending 文档", 2, cancelled)

        // ready 文档不被取消
        assertEquals("ready 文档必须保持 ready", DocStatus.READY.value, db.documentDao().get(readyDocId)?.status)
        assertEquals("已完成 job 必须保持 done", JobStage.DONE.value, jobOf(readyDocId).stage)
        // 排队中 job / pending 文档被取消
        assertEquals("排队 job 应 cancelled", JobStage.CANCELLED.value, jobOf(cancelledDocId).stage)
        assertEquals(
            "pending 文档应 cancelled",
            DocStatus.CANCELLED.value,
            db.documentDao().get(cancelledDocId)?.status,
        )
        assertEquals("取消后不应有 active job", 0, activeJobCount())
        assertEquals(
            "取消后不应有 queued job",
            0,
            db.ingestJobDao().all().count { it.stage == JobStage.QUEUED.value },
        )

        // 取消后 recoverInterrupted/requeueInterrupted 不复活 cancelled
        assertEquals("cancelled 不属于 INTERRUPTED，recover 应为 0", 0, repository.recoverInterrupted())
        val directRequeue = withContext(Dispatchers.IO) {
            db.ingestJobDao().requeueInterrupted(
                JobStage.INTERRUPTED_VALUES, JobStage.QUEUED.value, System.currentTimeMillis(),
            )
        }
        assertEquals("cancelled 不被 requeueInterrupted 回收", 0, directRequeue)
        assertEquals("取消状态不得被复活", JobStage.CANCELLED.value, jobOf(cancelledDocId).stage)
        assertEquals(
            "取消文档不得被复活",
            DocStatus.CANCELLED.value,
            db.documentDao().get(cancelledDocId)?.status,
        )
        assertEquals("不复活后仍无 active job", 0, activeJobCount())
        println(
            "B5_CANCEL_ROBOLECTRIC cancelled=$cancelled readyDoc=${db.documentDao().get(readyDocId)?.status} " +
                "cancelledDoc=${db.documentDao().get(cancelledDocId)?.status} active=${activeJobCount()}",
        )
    }

    @Test
    fun cancelledInFlightDocument_isFinishedAsCancelledByRecover_neverRevivedAsQueued() = runBlocking {
        val docId = (repository.importFile(stage("inflight.md", "# 在途文档")) as ImportResult.Enqueued).documentId
        // 模拟 worker 已认领：job=parsing、doc=processing
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.ingestJobDao().upsert(jobOf(docId).copy(stage = JobStage.PARSING.value))
                db.documentDao().updateStatusIfNotCancelled(
                    docId, DocStatus.PROCESSING.value, DocStatus.CANCELLED.value,
                )
            }
        }

        // 取消请求：只取消 queued job；processing 文档立即标 cancelled（在途任务自行收尾信号）
        val cancelled = repository.cancelImport()
        assertEquals("应取消 1 个 processing 文档（parsing job 不在 queued 取消范围）", 1, cancelled)
        assertEquals("processing 文档应 cancelled", DocStatus.CANCELLED.value, db.documentDao().get(docId)?.status)
        assertEquals("parsing job 由在途 worker 收尾", JobStage.PARSING.value, jobOf(docId).stage)

        // 进程被杀 → recoverInterrupted：先 requeue parsing，再因 doc=cancelled 在同一事务收尾为 cancelled
        val recovered = repository.recoverInterrupted()
        assertEquals("recoverInterrupted 应回收 1 个中间态 job", 1, recovered)
        assertEquals(
            "已取消文档的 job 不得留在 queued/parsing（不复活）",
            JobStage.CANCELLED.value,
            jobOf(docId).stage,
        )
        assertEquals("取消文档不得被复位为 pending", DocStatus.CANCELLED.value, db.documentDao().get(docId)?.status)
        assertEquals("最终不应有 active job", 0, activeJobCount())

        // 再次直接 requeue 也无效（job 已终态）
        val directRequeue = withContext(Dispatchers.IO) {
            db.ingestJobDao().requeueInterrupted(
                JobStage.INTERRUPTED_VALUES, JobStage.QUEUED.value, System.currentTimeMillis(),
            )
        }
        assertEquals(0, directRequeue)
        assertEquals(JobStage.CANCELLED.value, jobOf(docId).stage)
        println("B5_RECOVER_CANCELLED recovered=$recovered finalJob=${jobOf(docId).stage} active=${activeJobCount()}")
    }

    // ---- 工具 ----

    private fun stage(name: String, content: String): File =
        File(staging, name).apply { writeText(content) }

    private suspend fun jobOf(documentId: String): IngestJobEntity =
        db.ingestJobDao().all().first { it.documentId == documentId }

    private suspend fun activeJobCount(): Int = db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES)

    /**
     * AppDatabase.get(context) 为进程单例；Robolectric 每个测试方法有独立 Application/临时目录，
     * 这里关闭并清空单例（Kotlin companion 属性后备字段是 outer class 的 static 字段）。
     */
    private fun resetDatabaseSingleton() {
        // Kotlin companion 属性的后备字段位于 outer class（AppDatabase.instance）；
        // 兜底再找 Companion 内部的同名 static 字段（不同 Kotlin 版本布局差异）。
        val outerField = runCatching {
            AppDatabase::class.java.getDeclaredField("instance")
        }.getOrNull()
        val companionField = runCatching {
            Class.forName("com.zhibox.memory.data.db.AppDatabase\$Companion")
                .getDeclaredField("instance")
        }.getOrNull()
        val field = outerField ?: companionField
        checkNotNull(field) {
            "无法定位 AppDatabase.instance 单例字段：Robolectric 测试隔离需要清空该单例"
        }
        val holder: Any? = if (field.declaringClass == AppDatabase::class.java) {
            null
        } else {
            // Companion.INSTANCE 静态实例
            field.declaringClass.getField("INSTANCE").get(null)
        }
        field.isAccessible = true
        // 关闭失败可容忍（测试进程即将结束），但清空单例失败必须显式失败，
        // 否则字段重命名会静默泄漏单例、后续用例串库（审查 S-判断性）。
        runCatching { (field.get(holder) as? AppDatabase)?.close() }
        field.set(holder, null)
        check(field.get(holder) == null) {
            "AppDatabase 单例清空失败（${field.declaringClass.simpleName}.${field.name}）——测试隔离失效"
        }
    }

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
