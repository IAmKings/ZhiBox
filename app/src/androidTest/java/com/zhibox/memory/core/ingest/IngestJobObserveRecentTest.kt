package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B3-9 验收（instrumentation）：`IngestJobDao.observeRecent(activeStages, limit)` 限量/排序契约。
 *
 * 契约（core-fixer 落地版）：
 * ```kotlin
 * @Query("SELECT * FROM ingest_job ORDER BY createdAt DESC LIMIT :limit")
 * fun observeRecent(activeStages: List<String>, limit: Int): Flow<List<IngestJobEntity>>
 * ```
 * 背景：任务列表此前 `observeAll()` 全量渲染（101+ 卡片全量重组），UI 改为 `observeRecent(emptyList(), 50)`。
 *
 * 覆盖：插入 60 个 createdAt 各不相同的 job → observeRecent(emptyList(), 50).first() 恰好 50 条、
 * createdAt 严格降序、且保留最新 50 条（createdAt 最小的 10 条被排除）。
 */
class IngestJobObserveRecentTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeRecent_returnsOnlyNewestLimitRows_inDescendingCreatedAt() = runBlocking {
        seedJobs(60)

        val recent = db.ingestJobDao().observeRecent(emptyList(), 50).first()

        assertEquals("observeRecent(emptyList(), 50) 必须恰好返回 50 条", 50, recent.size)
        assertEquals("最新一条应为 createdAt 最大者", 1_000L + 59, recent.first().createdAt)
        assertEquals("第 50 条应为 createdAt 次新边界", 1_000L + 10, recent.last().createdAt)
        // 严格降序（createdAt 两两不同）
        for (i in 1 until recent.size) {
            assertTrue(
                "createdAt 必须降序：index=${i}，${recent[i - 1].createdAt} -> ${recent[i].createdAt}",
                recent[i - 1].createdAt > recent[i].createdAt,
            )
        }
        // 最新 50 条保留、最旧 10 条被排除
        val returnedIds = recent.map { it.id }.toSet()
        assertEquals("最新 50 条应全部保留", 50, returnedIds.size)
        for (i in 0 until 10) {
            assertTrue("最旧 job job_$i 不应出现在 recent 中", "job_$i" !in returnedIds)
        }
        for (i in 10 until 60) {
            assertTrue("较新 job job_$i 应出现在 recent 中", "job_$i" in returnedIds)
        }
        // 全量查询仍保留 60 条（observeAll 不受限量影响，诊断/备份依赖）
        assertEquals("observeAll 仍应返回全量", 60, db.ingestJobDao().observeAll().first().size)
        // limit 边界：0 → 空；>=总数 → 全部
        assertEquals("observeRecent(emptyList(), 0) 应为空", 0, db.ingestJobDao().observeRecent(emptyList(), 0).first().size)
        assertEquals("observeRecent(emptyList(), 100) 应返回全部 60 条", 60, db.ingestJobDao().observeRecent(emptyList(), 100).first().size)
        println(
            "B3_OBSERVE_RECENT limit=50 returned=${recent.size} " +
                "newest=${recent.first().createdAt} oldest=${recent.last().createdAt}",
        )
    }

    private suspend fun seedJobs(count: Int) {
        val jobs = (0 until count).map { i ->
            IngestJobEntity(
                id = "job_$i",
                sourceId = "src_$i",
                documentId = "doc_$i",
                stage = JobStage.DONE.value,
                progress = 100,
                error = null,
                createdAt = 1_000L + i, // 两两不同，便于断言降序与"最新 50 条"
                updatedAt = 2_000L + i,
                attempt = 0,
            )
        }
        // DAO 无 upsertAll（仅单条 upsert）；60 条顺序写入，耗时对 instrumentation 可接受
        jobs.forEach { db.ingestJobDao().upsert(it) }
    }
}
