package com.zhibox.memory.core.ingest

import android.app.Application
import androidx.room.withTransaction
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zhibox.memory.core.text.PdfPasswordVault
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * P1.1：PDF 密码重试（Robolectric + 真实 Room）：
 * 1) failed PDF job/doc → retryPdfWithPassword 事务迁移为 QUEUED/PENDING、清 error；
 * 2) 密码只进 [PdfPasswordVault]（一次性消费，不持久化），取走后再取为 null；
 * 3) 非 failed 状态返回 false；非 PDF / 空密码给可读 IllegalArgumentException。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfPasswordRetryRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository
    private lateinit var staging: File

    @Before
    fun setUp() {
        PdfPasswordVault.clearAll()
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        staging = File(app.filesDir, "import-staging").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        repository = IngestRepository(app)
        initWorkManagerIfNeeded()
    }

    @After
    fun tearDown() {
        PdfPasswordVault.clearAll()
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        staging.listFiles()?.forEach { it.delete() }
        File(app.filesDir, "documents").listFiles()?.forEach { it.delete() }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    @Test
    fun `retry failed pdf with password requeues job and stores password in vault`() = runBlocking {
        val documentId = importDocument("locked-${UUID.randomUUID()}.pdf")
        val job = jobOf(documentId)
        markFailed(documentId, job.id)

        val retried = repository.retryPdfWithPassword(job.id, documentId, "secret-pass".toCharArray())
        assertTrue("failed PDF 应可密码重试", retried)

        val requeued = jobOf(documentId)
        assertEquals("job 应 FAILED→QUEUED", JobStage.QUEUED.value, requeued.stage)
        assertEquals("progress 应清零", 0, requeued.progress)
        assertNull("error 应清空", requeued.error)
        assertEquals("document 应 FAILED→PENDING", DocStatus.PENDING.value, db.documentDao().get(documentId)?.status)

        val stored = PdfPasswordVault.take(documentId)
        assertArrayEquals("密码应一次性暂存在 vault", "secret-pass".toCharArray(), stored)
        stored?.fill('\u0000')
        assertNull("take 后 vault 不应残留", PdfPasswordVault.take(documentId))
    }

    @Test
    fun `retry password survives caller clearing its char array`() {
        runBlocking {
        // P1.1 E2E 回归：ViewModel 在 repository 返回后 finally 清零自己的 CharArray；
        // vault 必须持有副本，否则 worker 会收到空密码并误报“密码不正确”。
        val documentId = importDocument("copy-pw-${UUID.randomUUID()}.pdf")
        val job = jobOf(documentId)
        markFailed(documentId, job.id)

        val callerPassword = "secret-pass".toCharArray()
        assertTrue(repository.retryPdfWithPassword(job.id, documentId, callerPassword))
        callerPassword.fill('\u0000')

        val stored = PdfPasswordVault.take(documentId)
        assertArrayEquals("调用方清零后 vault 仍应保留副本", "secret-pass".toCharArray(), stored)
        stored?.fill('\u0000')
        }
    }

    @Test
    fun `retry returns false when job is not failed`() = runBlocking {
        val documentId = importDocument("queued-${UUID.randomUUID()}.pdf")
        val job = jobOf(documentId)

        assertFalse("queued job 不应密码重试", repository.retryPdfWithPassword(job.id, documentId, "x".toCharArray()))
        assertEquals("job 保持 queued", JobStage.QUEUED.value, jobOf(documentId).stage)
        assertNull("失败不应暂存密码", PdfPasswordVault.take(documentId))
    }

    @Test
    fun `retry non pdf throws readable error`() = runBlocking {
        val documentId = importDocument("notes-${UUID.randomUUID()}.md", "# markdown")
        val job = jobOf(documentId)
        markFailed(documentId, job.id)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.retryPdfWithPassword(job.id, documentId, "pw".toCharArray()) }
        }
        assertTrue("应提示仅 PDF：${error.message}", error.message!!.contains("仅 PDF"))
        assertNull("非 PDF 不应暂存密码", PdfPasswordVault.take(documentId))
    }

    @Test
    fun `retry with empty password throws readable error`() = runBlocking {
        val documentId = importDocument("empty-pw-${UUID.randomUUID()}.pdf")
        val job = jobOf(documentId)
        markFailed(documentId, job.id)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.retryPdfWithPassword(job.id, documentId, CharArray(0)) }
        }
        assertTrue("应提示密码不能为空：${error.message}", error.message!!.contains("不能为空"))
        assertNull(PdfPasswordVault.take(documentId))
    }

    // ---- 工具 ----

    /** Robolectric 无 WorkManager 自动初始化：retry 成功路径会 enqueue worker，需先初始化。 */
    private fun initWorkManagerIfNeeded() {
        try {
            WorkManager.getInstance(app)
        } catch (e: IllegalStateException) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
    }

    private suspend fun importDocument(name: String, content: String = "fake-pdf-bytes"): String {
        val staged = File(staging, name).apply { writeText(content) }
        return (repository.importFile(staged) as ImportResult.Enqueued).documentId
    }

    private suspend fun markFailed(documentId: String, jobId: String) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.ingestJobDao().markFailed(
                    jobId,
                    JobStage.FAILED.value,
                    PipelineError.encode(PipelineError.Unknown("PDF_ENCRYPTED: test")),
                    System.currentTimeMillis(),
                )
                db.documentDao().updateStatusIfNotCancelled(
                    documentId, DocStatus.FAILED.value, DocStatus.CANCELLED.value,
                )
            }
        }
    }

    private suspend fun jobOf(documentId: String): IngestJobEntity =
        db.ingestJobDao().all().first { it.documentId == documentId }

    /** Robolectric 测试隔离：关闭并清空 AppDatabase 进程单例（同 IngestCancelRobolectricTest）。 */
    private fun resetDatabaseSingleton() {
        val outerField = runCatching { AppDatabase::class.java.getDeclaredField("instance") }.getOrNull()
        val companionField = runCatching {
            Class.forName("com.zhibox.memory.data.db.AppDatabase\$Companion")
                .getDeclaredField("instance")
        }.getOrNull()
        val field = outerField ?: companionField
        checkNotNull(field) { "无法定位 AppDatabase.instance 单例字段" }
        val holder: Any? = if (field.declaringClass == AppDatabase::class.java) {
            null
        } else {
            field.declaringClass.getField("INSTANCE").get(null)
        }
        field.isAccessible = true
        runCatching { (field.get(holder) as? AppDatabase)?.close() }
        field.set(holder, null)
        check(field.get(holder) == null) { "AppDatabase 单例清空失败" }
    }

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
