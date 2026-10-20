package com.zhibox.memory.core.ingest

import android.app.Application
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.JobStage
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * B14.2 导入侧旁车集成（JVM Robolectric，真实 Room + 真实 IngestRepository）：
 * - `importStagedForTest`（测试缝，不入队 Worker）读取 `<file>.origin` → importFile(originUrl)
 *   → SourceEntity.originUrl 持久化；
 * - 成功入库 / 命中去重 → 旁车删除；单文件失败 → 文件+旁车保留（供重试）；
 * - 无旁车 → originUrl=null；非法 URL 旁车 → 按无 URL 处理且删除旁车；
 * - 批次结束清理孤儿旁车（无源文件/不可导入）。
 *
 * 用 `AppDatabase.get(context)` 进程单例（Robolectric 每方法独立 Application/临时目录），
 * 通过反射清空单例保证隔离（与 IngestCancelRobolectricTest 一致）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StagingOriginSidecarRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository
    private lateinit var staging: File
    private lateinit var documents: File

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
        documents = File(app.filesDir, "documents").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        repository = IngestRepository(app)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        staging.listFiles()?.forEach { it.delete() }
        documents.listFiles()?.forEach { it.delete() }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    // ---- 成功路径 ----

    @Test
    fun importStagedPersistsOriginUrlAndDeletesSidecar() = runBlocking {
        val file = stage("note.md", "# 标题\n正文", originUrl = "https://example.com/note?id=1")
        val result = repository.importStagedForTest(listOf(file))

        assertEquals(1, result.enqueuedCount)
        val source = sourceOf(result.documentIds.single())
        assertEquals("https://example.com/note?id=1", source.originUrl)
        assertFalse("旁车应在成功后删除", SourceOriginUrls.sidecarFor(file).exists())
        assertFalse("staging 源文件应已被 importFile 消费", file.exists())
    }

    @Test
    fun importStagedWithoutSidecarLeavesOriginUrlNull() = runBlocking {
        val file = stage("plain.md", "# 无来源")
        val result = repository.importStagedForTest(listOf(file))

        assertEquals(1, result.enqueuedCount)
        assertNull(sourceOf(result.documentIds.single()).originUrl)
    }

    @Test
    fun invalidSidecarsAreIgnoredAndDeleted() = runBlocking {
        val rawSidecars = listOf(
            "http://example.com/http",
            "https://user:pass@example.com/userinfo",
            "not-a-url",
            "https://example.com/" + "a".repeat(4096),
        )
        val files = rawSidecars.mapIndexed { index, raw ->
            stage("invalid-$index.txt", "内容 $index", rawSidecar = raw)
        }
        val result = repository.importStagedForTest(files)

        assertEquals("非法 URL 不阻断导入", files.size, result.enqueuedCount)
        result.documentIds.forEach { docId ->
            assertNull("非法旁车应按无 URL 处理", sourceOf(docId).originUrl)
        }
        files.forEachIndexed { index, file ->
            assertFalse("无效旁车 $index 应删除", SourceOriginUrls.sidecarFor(file).exists())
        }
    }

    @Test
    fun duplicateImportConsumesSidecarAndDoesNotCreateSecondSource() = runBlocking {
        val first = stage("dup.md", "# 重复内容", originUrl = "https://example.com/dup")
        val firstResult = repository.importStagedForTest(listOf(first))
        assertEquals(1, firstResult.enqueuedCount)
        val sourceCountAfterFirst = db.sourceDao().all().size
        // 模拟 Worker 已完成首个 job：否则 importStaged 的 activeCount>0 早退分支会直接返回空结果
        //（生产语义：在途批次存在时不重复扫描登记）。doc 仍为 pending → 第二次导入应命中去重。
        val firstJob = db.ingestJobDao().all().single()
        db.ingestJobDao().upsert(firstJob.copy(stage = JobStage.DONE.value, progress = 100))

        // 重新放回同内容文件 + 新旁车（模拟用户再次点导入）
        val second = stage("dup.md", "# 重复内容", originUrl = "https://example.com/dup2")
        val secondResult = repository.importStagedForTest(listOf(second))

        assertEquals("应命中去重", 1, secondResult.duplicateCount)
        assertEquals(0, secondResult.enqueuedCount)
        assertEquals("不得新增 source", sourceCountAfterFirst, db.sourceDao().all().size)
        assertFalse("去重路径也必须消费旁车", SourceOriginUrls.sidecarFor(second).exists())
        assertEquals(
            "首个来源 URL 不被第二次导入改写",
            "https://example.com/dup",
            db.sourceDao().all().single().originUrl,
        )
    }

    @Test
    fun directImportFileWithOriginUrlPersistsAndDefaultIsNull() = runBlocking {
        val withUrl = stage("direct.md", "# 直接导入", originUrl = "https://example.com/direct")
        val docWithUrl = (repository.importFile(withUrl, "https://example.com/direct") as ImportResult.Enqueued).documentId
        assertEquals("https://example.com/direct", sourceOf(docWithUrl).originUrl)

        val withoutUrl = stage("default.md", "# 默认无 URL")
        val docWithoutUrl = (repository.importFile(withoutUrl) as ImportResult.Enqueued).documentId
        assertNull(sourceOf(docWithoutUrl).originUrl)

        // 直接 importFile 传入非法 URL → 仍按无 URL 处理（不写脏数据）
        val invalid = stage("invalid-direct.md", "# 非法直接 URL")
        val docInvalid = (repository.importFile(invalid, "http://example.com") as ImportResult.Enqueued).documentId
        assertNull(sourceOf(docInvalid).originUrl)
    }

    // ---- 失败路径：保留旁车供重试 ----

    @Test
    fun singleFileFailureKeepsStagingFileAndSidecarForRetry() = runBlocking {
        val file = stage("retry.md", "# 将失败", originUrl = "https://example.com/retry")
        // 让 documents 目录不可用：用同名普通文件占位，importFile 的 copyTo 会抛 IOException
        documents.deleteRecursively()
        documents.writeText("blocked")

        try {
            val result = repository.importStagedForTest(listOf(file))
            assertEquals("失败不计入登记", 0, result.enqueuedCount)
            assertEquals(0, result.duplicateCount)
            assertTrue("失败时 staging 源文件保留供重试", file.exists())
            assertTrue("失败时旁车保留（重试可恢复 URL）", SourceOriginUrls.sidecarFor(file).exists())
        } finally {
            documents.delete()
            documents.mkdirs()
        }
    }

    // ---- 孤儿旁车清理 ----

    @Test
    fun batchCleanupRemovesOrphanAndUnsupportedSidecars() = runBlocking {
        val supported = stage("ok.md", "# 正常文件", originUrl = "https://example.com/ok")
        val orphanSidecar = File(staging, "missing.txt.origin").apply { writeText("https://example.com/missing") }
        File(staging, "note.zip").writeText("zip 内容")
        val unsupportedSidecar = File(staging, "note.zip.origin").apply { writeText("https://example.com/zip") }

        val result = repository.importStagedForTest(listOf(supported))

        assertEquals(1, result.enqueuedCount)
        assertFalse("正常文件旁车已被消费", SourceOriginUrls.sidecarFor(supported).exists())
        assertFalse("无源文件孤儿旁车应清理", orphanSidecar.exists())
        assertFalse("源文件不可导入时旁车应清理", unsupportedSidecar.exists())
        assertTrue("不可导入的 zip 原文件不动", File(staging, "note.zip").exists())
    }

    // ---- helpers ----

    private fun stage(
        name: String,
        content: String,
        originUrl: String? = null,
        rawSidecar: String? = null,
    ): File {
        val file = File(staging, name).apply { writeText(content) }
        val sidecarRaw = rawSidecar ?: originUrl
        if (sidecarRaw != null) {
            SourceOriginUrls.sidecarFor(file).writeText(sidecarRaw)
        }
        return file
    }

    private suspend fun sourceOf(documentId: String): SourceEntity {
        val document: DocumentEntity = db.documentDao().get(documentId)
            ?: error("document not found: $documentId")
        return db.sourceDao().get(document.sourceId) ?: error("source not found: ${document.sourceId}")
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

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
