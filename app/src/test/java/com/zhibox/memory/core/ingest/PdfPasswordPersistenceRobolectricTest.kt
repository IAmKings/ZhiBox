package com.zhibox.memory.core.ingest

import android.app.Application
import androidx.room.withTransaction
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.zhibox.memory.core.text.DocumentParser
import com.zhibox.memory.core.text.FakePdfPasswordCipher
import com.zhibox.memory.core.text.PdfPasswordStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * P1.1b 密码持久化端到端（Robolectric，真实 Room + PDFBox user-password fixture + fake cipher store）：
 * - retryPdfWithPassword 写入加密 store（UI 清空 CharArray 不影响）；
 * - 模拟"进程死亡"（清空进程内 Vault）后，worker/processDocument 路径仍能从 store 解密取回密码，
 *   并用 PDFBox user password 成功解析加密 PDF；
 * - 解析成功 → store 清空；错误密码 → store 清空且错误文案不变；IO/未知失败 → 保留供 worker 重试；
 * - 删除来源 → 清理该来源文档的持久化密码。
 *
 * 生产默认 [PdfPasswordStore] 使用 Android Keystore；本测试注入 fake cipher（Robolectric 无真实
 * Keystore）。真实 Keystore 由 androidTest `PdfPasswordStoreKeystoreTest` 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfPasswordPersistenceRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository
    private lateinit var staging: File
    private lateinit var store: PdfPasswordStore
    private lateinit var cipher: FakePdfPasswordCipher
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        PdfPasswordVault.clearAll()
        app = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(app)
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        staging = File(app.filesDir, "import-staging").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        File(app.filesDir, "documents").mkdirs()
        app.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).edit().clear().commit()
        cipher = FakePdfPasswordCipher()
        now = 1_000_000L
        store = PdfPasswordStore(app, cipher, clock = { now }, ttlMs = PdfPasswordStore.DEFAULT_TTL_MS)
        repository = IngestRepository(app, pdfPasswordStore = store)
        initWorkManagerIfNeeded()
    }

    @After
    fun tearDown() {
        PdfPasswordVault.clearAll()
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        staging.listFiles()?.forEach { it.delete() }
        File(app.filesDir, "documents").listFiles()?.forEach { it.delete() }
        app.getSharedPreferences(PdfPasswordStore.PREFS_NAME, 0).edit().clear().commit()
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    // ---- 进程重启恢复 ----

    @Test
    fun retryPersistsEncryptedPasswordAndNewProcessRecoversAndParsesPdf() = runBlocking {
        val (documentId, jobId) = importEncryptedPdf()
        val callerPassword = "user-secret".toCharArray()

        assertTrue(repository.retryPdfWithPassword(jobId, documentId, callerPassword))
        callerPassword.fill('\u0000')
        assertTrue("retry 应写入加密持久化 store", store.has(documentId))

        // 模拟进程死亡：仅存加密 store，进程内 Vault 清空
        PdfPasswordVault.clearAll()
        assertNull("Vault 已清空", PdfPasswordVault.take(documentId))
        val recovered = repository.takePdfPassword(documentId)!!
        assertArrayEquals("新进程应从 store 解密取回密码", "user-secret".toCharArray(), recovered)

        // 用取回的密码走 PDFBox user-password 解析（真实加密 PDF fixture）
        val raw = sourceFile(documentId)
        val parsed = DocumentParser.parse(app, raw, fileName = raw.name, password = recovered)
        assertTrue("加密 PDF 应成功抽取文本：${parsed.text}", parsed.text.contains("Secret PDF"))

        // 解析成功后清理（processDocument 成功路径调用同一 helper）
        repository.clearStoredPdfPassword(documentId)
        assertFalse("解析成功后 store 应清空", store.has(documentId))
        assertNull(repository.takePdfPassword(documentId))
        recovered.fill('\u0000')
    }

    @Test
    fun processDocumentAfterRetryClearsStoreWhenParseSucceeds() = runBlocking {
        val (documentId, jobId) = importEncryptedPdf()
        assertTrue(repository.retryPdfWithPassword(jobId, documentId, "user-secret".toCharArray()))
        PdfPasswordVault.clearAll()
        assertTrue(store.has(documentId))

        // 无模型资产的 Robolectric 环境：解析成功后会在"模型缺失"阶段失败，但密码清理已发生。
        runCatching { repository.processDocument(documentId, jobId) }

        assertFalse("解析成功后 store 必须清空（即使后续 embedding 失败）", store.has(documentId))
        assertTrue("store 路径应实际被读取一次（进程内 Vault 已清空）", cipher.decryptCalls >= 1)
    }

    @Test
    fun wrongPasswordClearsStoreAndFailsWithReadableError() = runBlocking {
        val (documentId, jobId) = importEncryptedPdf()
        assertTrue(repository.retryPdfWithPassword(jobId, documentId, "wrong-password".toCharArray()))
        PdfPasswordVault.clearAll()
        assertTrue(store.has(documentId))

        runCatching { repository.processDocument(documentId, jobId) }

        assertFalse("密码错误后 store 应清空（UI 重新输入）", store.has(documentId))
        val job = jobOf(documentId)
        assertEquals("PDF 密码错误是确定性失败 → failed", JobStage.FAILED.value, job.stage)
        val error = job.error
        assertNotNull(error)
        assertTrue("错误应结构化保留 DOCUMENT_PARSE：$error", error!!.startsWith("DOCUMENT_PARSE:"))
        assertTrue("错误文案应提示密码不正确（文案不变）：$error", error.contains("密码不正确"))
        assertEquals(DocStatus.FAILED.value, db.documentDao().get(documentId)?.status)
    }

    @Test
    fun ioFailureKeepsStoreForWorkerRetry() = runBlocking {
        val (documentId, jobId) = importEncryptedPdf()
        assertTrue(repository.retryPdfWithPassword(jobId, documentId, "user-secret".toCharArray()))
        PdfPasswordVault.clearAll()
        assertTrue(store.has(documentId))

        // 删除源文件模拟 IO 失败（非密码错误）
        sourceFile(documentId).delete()
        runCatching { repository.processDocument(documentId, jobId) }

        assertTrue("IO/未知失败应保留密码供下次 worker 自动恢复", store.has(documentId))
        val retained = store.get(documentId)!!
        assertArrayEquals("user-secret".toCharArray(), retained)
        retained.fill('\u0000')
    }

    @Test
    fun documentDeleteClearsStoredPassword() = runBlocking {
        val (documentId, _) = importEncryptedPdf()
        val job = jobOf(documentId)
        assertTrue(repository.retryPdfWithPassword(job.id, documentId, "user-secret".toCharArray()))
        PdfPasswordVault.clearAll()
        assertTrue(store.has(documentId))

        // deleteSource 守卫要求无 active job：把 retry 后的 queued job 收尾为 done
        db.ingestJobDao().upsert(jobOf(documentId).copy(stage = JobStage.DONE.value, progress = 100))
        val document = db.documentDao().get(documentId)!!
        repository.deleteSource(document.sourceId)

        assertFalse("删除来源后不得残留密码密文", store.has(documentId))
        assertNull(db.documentDao().get(documentId))
    }

    // ---- helper ----

    private fun initWorkManagerIfNeeded() {
        try {
            WorkManager.getInstance(app)
        } catch (e: IllegalStateException) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
    }

    private fun resetDatabaseSingleton() {
        val outerField = runCatching {
            AppDatabase::class.java.getDeclaredField("instance")
        }.getOrNull()
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

    private suspend fun importEncryptedPdf(userPassword: String = "user-secret"): Pair<String, String> {
        val bytes = encryptedPdf(userPassword = userPassword, ownerPassword = "owner-secret")
        val staged = File(staging, "locked-${UUID.randomUUID()}.pdf").apply { writeBytes(bytes) }
        val documentId = (repository.importFile(staged) as ImportResult.Enqueued).documentId
        val job = jobOf(documentId)
        markFailed(documentId, job.id)
        return documentId to job.id
    }

    private suspend fun markFailed(documentId: String, jobId: String) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.ingestJobDao().markFailed(
                    jobId,
                    JobStage.FAILED.value,
                    PipelineError.encode(PipelineError.Unknown("PDF_ENCRYPTED: test fixture")),
                    System.currentTimeMillis(),
                )
                db.documentDao().updateStatusIfNotCancelled(
                    documentId,
                    DocStatus.FAILED.value,
                    DocStatus.CANCELLED.value,
                )
            }
        }
    }

    private suspend fun jobOf(documentId: String): IngestJobEntity =
        db.ingestJobDao().all().first { it.documentId == documentId }

    private suspend fun sourceFile(documentId: String): File {
        val document = db.documentDao().get(documentId)!!
        val source = db.sourceDao().get(document.sourceId)!!
        return File(source.uri)
    }

    // ---- PDFBox fixture（加密 PDF：user password 打开） ----

    private fun encryptedPdf(userPassword: String, ownerPassword: String): ByteArray {
        val permission = AccessPermission().apply { setCanExtractContent(true) }
        val policy = StandardProtectionPolicy(ownerPassword, userPassword, permission).apply {
            setEncryptionKeyLength(128)
        }
        PDDocument.load(buildPdf(listOf("Secret PDF")).inputStream()).use { document ->
            document.protect(policy)
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    private fun buildPdf(pageTexts: List<String>): ByteArray {
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += ""
        val fontObjectNumber = 3 + pageTexts.size * 2
        val pageRefs = ArrayList<Int>()
        pageTexts.forEachIndexed { index, pageText ->
            val pageObjectNumber = 3 + index * 2
            val contentObjectNumber = pageObjectNumber + 1
            pageRefs += pageObjectNumber
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents $contentObjectNumber 0 R " +
                "/Resources << /Font << /F1 $fontObjectNumber 0 R >> >> >>"
            val content = "BT /F1 12 Tf 72 720 Td (${escapePdfString(pageText)}) Tj ET"
            val contentLength = content.toByteArray(Charsets.ISO_8859_1).size
            objects += "<< /Length $contentLength >>\nstream\n$content\nendstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects[1] = "<< /Type /Pages /Kids [" +
            pageRefs.joinToString(" ") { "$it 0 R" } + "] /Count ${pageTexts.size} >>"

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>(objects.size)
        objects.forEachIndexed { index, body ->
            offsets += out.length
            out.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefStart = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { offset -> out.append(String.format("%010d 00000 n \n", offset)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        out.append("startxref\n$xrefStart\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun escapePdfString(raw: String): String =
        raw.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
