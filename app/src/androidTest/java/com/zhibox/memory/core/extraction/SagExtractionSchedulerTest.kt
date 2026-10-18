package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.llm.InMemoryLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B9 抽取调度（instrumentation，真实 Room；fake config store）：
 * - `onDocumentReady`：每 chunk 一条 job（幂等）；SAG 不可用 → not_indexed + NOT_CONFIGURED；
 *   补齐配置后再次调用 → not_indexed 重新入队（attempt 清零）；
 * - DONE/FAILED 不会被 onDocumentReady 改变（FAILED 只能走 retryFailed）；
 * - `cancelDocument` 整篇 cancelled；
 * - `backfill` 在 SAG 不可用时返回 0（UI 禁用按钮路径），不改任务状态。
 *
 * 覆盖缺口（需 `androidx.work:work-testing` 依赖 + WorkManagerTestInitHelper 才能确定性断言
 * `backfill`/`retryFailed` 的 enqueue 路径；否则会向真实 WorkManager 入队并在后台执行，产生竞态）：
 * backfill 创建缺失任务/重排 not_indexed/cancelled、retryFailed 重排失败任务——待依赖加入后补。
 */
class SagExtractionSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var originalSagEnabled = false
    private var originalNetworkAllowed = false

    private val llmConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
        originalSagEnabled = LlmPreferences.sagEnabled(context)
        originalNetworkAllowed = LlmPreferences.networkAllowed(context)
        LlmPreferences.setSagEnabled(context, true)
        LlmPreferences.setNetworkAllowed(context, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(llmConfig)
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, originalSagEnabled)
        LlmPreferences.setNetworkAllowed(context, originalNetworkAllowed)
    }

    @Test
    fun onDocumentReadyCreatesOneJobPerChunkAndIsIdempotent() = runBlocking {
        seedDocument(chunkCount = 2)

        assertEquals("2 个 chunk 应建 2 条 job", 2, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))
        val jobs = db.extractionJobDao().byDocument("doc1")
        assertEquals(2, jobs.size)
        assertTrue(jobs.all { it.status == ExtractionJobStatus.QUEUED.value })
        assertTrue(jobs.all { it.attempt == 0 })
        assertEquals("不同 chunk 各一条", 2, jobs.map { it.chunkId }.toSet().size)

        assertEquals("重复调用幂等：不新建/不改状态", 0, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))
        assertEquals(2, db.extractionJobDao().byDocument("doc1").size)
    }

    @Test
    fun onDocumentReadyWhenUnavailableMarksNotIndexed() = runBlocking {
        seedDocument(chunkCount = 2)
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, false)

        assertEquals(2, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))
        val jobs = db.extractionJobDao().byDocument("doc1")
        assertTrue(jobs.all { it.status == ExtractionJobStatus.NOT_INDEXED.value })
        assertTrue(jobs.all { it.errorCode == ExtractionErrorCodes.NOT_CONFIGURED })
        assertEquals("文档仍 ready，离线检索不受影响", DocStatus.READY.value, db.documentDao().get("doc1")?.status)
    }

    @Test
    fun onDocumentReadyRequeuesNotIndexedAfterConfigurationFilled() = runBlocking {
        seedDocument(chunkCount = 2)
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, false)
        assertEquals(2, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))

        // 用户补齐配置后：not_indexed → queued（attempt 清零），job.id 不变（extractionVersion 稳定）
        LlmPreferences.setSagEnabled(context, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(llmConfig)
        val beforeIds = db.extractionJobDao().byDocument("doc1").map { it.id }.toSet()
        assertEquals(2, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))

        val after = db.extractionJobDao().byDocument("doc1")
        assertEquals(beforeIds, after.map { it.id }.toSet())
        assertTrue(after.all { it.status == ExtractionJobStatus.QUEUED.value })
        assertTrue(after.all { it.attempt == 0 })
        assertTrue(after.all { it.errorCode == null })
    }

    @Test
    fun onDocumentReadyLeavesDoneAndFailedJobsUntouched() = runBlocking {
        seedDocument(chunkCount = 2)
        val chunks = db.chunkDao().byDocument("doc1").sortedBy { it.ordinal }
        db.extractionJobDao().upsert(job(chunks[0].id, "job-done", ExtractionJobStatus.DONE))
        db.extractionJobDao().upsert(job(chunks[1].id, "job-failed", ExtractionJobStatus.FAILED))

        assertEquals("已有终态任务不应被 onDocumentReady 改变", 0, SagExtractionScheduler.onDocumentReady(context, db, "doc1"))
        assertEquals(ExtractionJobStatus.DONE.value, db.extractionJobDao().get("job-done")!!.status)
        assertEquals(ExtractionJobStatus.FAILED.value, db.extractionJobDao().get("job-failed")!!.status)
    }

    @Test
    fun cancelDocumentMarksAllJobsCancelled() = runBlocking {
        seedDocument(chunkCount = 2)
        val chunks = db.chunkDao().byDocument("doc1").sortedBy { it.ordinal }
        db.extractionJobDao().upsert(job(chunks[0].id, "job-a", ExtractionJobStatus.QUEUED))
        db.extractionJobDao().upsert(job(chunks[1].id, "job-b", ExtractionJobStatus.RUNNING))

        assertEquals(2, SagExtractionScheduler.cancelDocument(context, db, "doc1"))
        assertTrue(
            db.extractionJobDao().byDocument("doc1").all { it.status == ExtractionJobStatus.CANCELLED.value },
        )
        assertEquals(ExtractionJobStatus.CANCELLED.value, db.extractionJobDao().get("job-b")!!.status)
    }

    @Test
    fun backfillReturnsZeroWhenSagUnavailableAndLeavesJobsUntouched() = runBlocking {
        seedDocument(chunkCount = 1)
        val chunkId = db.chunkDao().byDocument("doc1").single().id
        db.extractionJobDao().upsert(job(chunkId, "job-not-indexed", ExtractionJobStatus.NOT_INDEXED))

        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, false)
        assertEquals("不可用时 backfill 应为 0（UI 禁用路径）", 0, SagExtractionScheduler.backfill(context, db))
        assertEquals(
            ExtractionJobStatus.NOT_INDEXED.value,
            db.extractionJobDao().get("job-not-indexed")!!.status,
        )
    }

    // ---- helpers ----

    private suspend fun seedDocument(chunkCount: Int) {
        db.sourceDao().upsert(SourceEntity("src1", "B9 来源", "", null, 100, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc1", sourceId = "src1", title = "B9 文档", checksum = "sum-b9",
                parserId = "txt-utf8-v1", createdAt = 200, status = DocStatus.READY.value,
            ),
        )
        db.chunkDao().insertAll(
            (0 until chunkCount).map { index ->
                ChunkEntity(
                    documentId = "doc1",
                    ordinal = index,
                    content = "第 $index 段内容",
                    charStart = index * 10,
                    charEnd = index * 10 + 6,
                    tokenEstimate = 6,
                )
            },
        )
    }

    private fun job(chunkId: Long, id: String, status: ExtractionJobStatus) = ExtractionJobEntity(
        id = id,
        documentId = "doc1",
        chunkId = chunkId,
        status = status.value,
        attempt = 0,
        errorCode = null,
        errorDetail = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )
}
