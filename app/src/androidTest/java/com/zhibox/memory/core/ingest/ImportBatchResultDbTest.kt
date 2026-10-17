package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B3-13 验收（instrumentation，需真机）：`importStaged()` 批量结果契约。
 *
 * 契约（core-fixer 落地版）：
 * ```kotlin
 * data class ImportBatchResult(val documentIds: List<String>, val duplicateCount: Int)
 * suspend fun importStaged(): ImportBatchResult   // 扫描 filesDir/import-staging
 * suspend fun importStaged(files: List<File>): ImportBatchResult
 * ```
 * - `documentIds` = 本次**新登记**的 document id（Enqueued 的文件）；
 * - `duplicateCount` = 因同 checksum 已存在（pending/processing/ready）而跳过的**文件数**；
 * - 成功登记/命中重复的 staging 副本都会被删除（staging 最终为空）。
 *
 * 覆盖：
 * 1) 首批 3 个文件（2 同内容 + 1 不同）→ documentIds=2、duplicateCount=1、
 *    DB document/job 各 2 行、staging 清空；
 * 2) 同内容再次批量导入（模拟"再跑一次"）→ documentIds 空、duplicateCount=2、
 *    不新增 document/job 行、staging 清空。
 *
 * 确定性说明：`importStaged()` 会 enqueue 真实 WorkManager（测试机无模型 → worker 会快速失败，
 * 但只改 document/job 的**状态**、不增删行），因此断言只取行数/结果契约；第二个用例用
 * `importFile`（不触发 Worker）先造"已入库"基线，再把 job 置终态避免 `activeCount` 守卫，
 * 从而完全确定性地验证 duplicateCount（不依赖 worker 调度时序）。
 */
class ImportBatchResultDbTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var staging: File
    private lateinit var repository: IngestRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking {
            db.clearAllTables()
            // 取消上一轮可能残留的批量 worker，避免本用例期间并发写状态
            WorkManager.getInstance(context).cancelUniqueWork(IngestRepository.INGEST_BATCH_WORK)
        }
        staging = File(context.filesDir, "import-staging").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        repository = IngestRepository(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            WorkManager.getInstance(context).cancelUniqueWork(IngestRepository.INGEST_BATCH_WORK)
            db.clearAllTables()
        }
        staging.listFiles()?.forEach { it.delete() }
        // 清理 importFile 复制到 documents/ 的测试文件
        File(context.filesDir, "documents").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun firstBatch_returnsNewDocumentIdsAndDuplicateCount_andClearsStaging() = runBlocking {
        val duplicateContent = "# 同内容\n\nB3-13 批量导入去重测试。\n"
        val otherContent = "# 不同内容\n\nB3-13 第二个文档。\n"
        stage("b3-dup-1.md", duplicateContent)
        stage("b3-dup-2.md", duplicateContent)
        stage("b3-other.md", otherContent)
        assertEquals("前置：staging 应有 3 个文件", 3, stagingFiles().size)

        val result = repository.importStaged()

        assertEquals("新登记 documentIds 应为 2（同内容只登记 1 个 + 不同内容 1 个）", 2, result.documentIds.size)
        assertEquals("应跳过 1 个同内容文件", 1, result.duplicateCount)
        assertEquals("便捷属性 enqueuedCount", 2, result.enqueuedCount)
        assertEquals("DB document 行数", 2, db.documentDao().all().size)
        assertEquals("DB ingest_job 行数", 2, db.ingestJobDao().all().size)
        assertEquals("成功登记/重复命中的 staging 副本都应删除", 0, stagingFiles().size)
        val dbDocIds = db.documentDao().all().map { it.id }.toSet()
        assertTrue("result.documentIds 必须对应 DB 中真实 document", dbDocIds.containsAll(result.documentIds))

        // 契约边界：Enqueued/Duplicate 的 staging 副本都会被删除 → staging 已空时再跑一次
        // 只能返回 (emptyList, 0)（不会凭空产生 duplicateCount）。
        val emptyRun = repository.importStaged()
        assertEquals("staging 已空时 documentIds 应为空", 0, emptyRun.documentIds.size)
        assertEquals("staging 已空时 duplicateCount 应为 0", 0, emptyRun.duplicateCount)
        println(
            "B3_IMPORT_BATCH first documentIds=${result.documentIds.size} " +
                "duplicateCount=${result.duplicateCount} docs=${db.documentDao().all().size} " +
                "jobs=${db.ingestJobDao().all().size}",
        )
    }

    @Test
    fun secondBatch_sameContents_countsDuplicates_andAddsNoRows() = runBlocking {
        val contentA = "# 重复内容 A\n\nB3-13 第二批导入测试。\n"
        val contentB = "# 重复内容 B\n\nB3-13 第二批导入测试之二。\n"

        // 第一批：直接 importFile（不触发 Worker 入队），登记 2 个 pending document + 2 个 queued job
        val firstA = repository.importFile(stage("b3-a-1.md", contentA))
        val firstB = repository.importFile(stage("b3-b-1.md", contentB))
        assertTrue("首批 A 应登记成功：$firstA", firstA is ImportResult.Enqueued)
        assertTrue("首批 B 应登记成功：$firstB", firstB is ImportResult.Enqueued)
        // 模拟首批已完成：document→ready、job→DONE（同时避免 importStaged 的 activeCount 守卫提前返回）
        db.documentDao().all().forEach { doc ->
            db.documentDao().updateStatusIfNotCancelled(
                doc.id, DocStatus.READY.value, DocStatus.CANCELLED.value,
            )
        }
        db.ingestJobDao().all().forEach { job ->
            db.ingestJobDao().upsert(job.copy(stage = JobStage.DONE.value))
        }
        assertEquals(2, db.documentDao().all().size)
        assertEquals(2, db.ingestJobDao().all().size)

        // 第二批：再次放入同内容的 2 个文件 → 全部命中已有 document（checksum 相同且状态 pending）
        stage("b3-a-2.md", contentA)
        stage("b3-b-2.md", contentB)
        val second = repository.importStaged()

        assertEquals("重复批次的 documentIds 必须为空", 0, second.documentIds.size)
        assertEquals("2 个文件均因同内容被跳过", 2, second.duplicateCount)
        assertEquals("不得新增 document", 2, db.documentDao().all().size)
        assertEquals("不得新增 job", 2, db.ingestJobDao().all().size)
        assertEquals("重复文件的 staging 副本应被删除", 0, stagingFiles().size)
        println(
            "B3_IMPORT_BATCH second documentIds=${second.documentIds.size} " +
                "duplicateCount=${second.duplicateCount} docs=${db.documentDao().all().size} " +
                "jobs=${db.ingestJobDao().all().size}",
        )
    }

    private fun stage(name: String, content: String): File =
        File(staging, name).apply { writeText(content) }

    private fun stagingFiles(): List<File> =
        staging.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.toList() ?: emptyList()
}
