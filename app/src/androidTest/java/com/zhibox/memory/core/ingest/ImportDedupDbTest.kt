package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P2-B1-6 回归（instrumentation，需 arm64 真机执行）：内容级重复导入去重。
 *
 * 直接调用 [IngestRepository.importFile]（不经过 Worker）：
 * 1) 第一次返回 Enqueued，写 source/document/job 各 1 行；
 * 2) 同内容第二次返回 Duplicate(existingDocumentId)，不新增任何行，且删除本次 staging 副本。
 */
class ImportDedupDbTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var staging: File

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            db = AppDatabase.get(context)
            db.clearAllTables()
            staging = File(context.filesDir, "import-staging").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            db.clearAllTables()
            staging.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun duplicateContent_isRegisteredOnce_andStagingCopyRemoved() = runBlocking {
        val repository = IngestRepository(context)
        val content = "# 重复导入\n\n同一内容只允许登记一次（B1-6）。\n"
        val first = stage("dedup-1.md", content)
        val second = stage("dedup-2.md", content)

        val r1 = repository.importFile(first)
        assertTrue("首次导入应入队：$r1", r1 is ImportResult.Enqueued)
        val documentId = (r1 as ImportResult.Enqueued).documentId
        assertEquals(1, db.sourceDao().all().size)
        assertEquals(1, db.documentDao().all().size)
        assertEquals(1, db.ingestJobDao().all().size)

        val r2 = repository.importFile(second)
        assertTrue("同内容第二次应判定重复：$r2", r2 is ImportResult.Duplicate)
        assertEquals(documentId, (r2 as ImportResult.Duplicate).existingDocumentId)
        assertEquals("重复导入不得新增 source", 1, db.sourceDao().all().size)
        assertEquals("重复导入不得新增 document", 1, db.documentDao().all().size)
        assertEquals("重复导入不得新增 job", 1, db.ingestJobDao().all().size)
        assertTrue("重复的 staging 副本应被删除", !second.exists())
        assertEquals("staging 目录应为空", 0, staging.listFiles()?.size ?: 0)
    }

    @Test
    fun cancelledDocument_allowsReimport() = runBlocking {
        val repository = IngestRepository(context)
        val content = "# 允许重导\n\nfailed/cancelled 文档允许重新导入。\n"
        val first = stage("dedup-cancelled.md", content)
        val r1 = repository.importFile(first)
        assertTrue(r1 is ImportResult.Enqueued)
        val documentId = (r1 as ImportResult.Enqueued).documentId
        // 模拟上次导入被取消（终态：允许用户重导）
        db.withTransaction {
            db.documentDao().updateStatusIfNotCancelled(documentId, "cancelled", "cancelled")
            db.ingestJobDao().upsert(
                db.ingestJobDao().all().first().copy(stage = "cancelled")
            )
        }

        val r2 = repository.importFile(stage("dedup-cancelled-2.md", content))
        assertTrue("cancelled 文档应允许重导：$r2", r2 is ImportResult.Enqueued)
        assertEquals("重导后应有 2 个 document", 2, db.documentDao().all().size)
    }

    private fun stage(name: String, content: String): File =
        File(staging, name).apply { writeText(content) }
}
