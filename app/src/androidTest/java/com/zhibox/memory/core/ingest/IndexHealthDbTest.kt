package com.zhibox.memory.core.ingest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P2-B2-8 索引健康/孤儿清理验收（instrumentation；不依赖嵌入模型）。
 *
 * 被测 API（core-fixer B2-8 落地版，以当前 main 为准）：
 * - [IngestRepository.indexHealthCheck]：chunk/chunk_fts/chunk_embedding 三表计数 + 孤儿检测；
 * - [IngestRepository.repairIndex]：单事务精确删除孤儿 FTS/向量行；若清理后 FTS 计数仍不符
 *   （缺行/重复）→ 全量重建 FTS 兜底；返回 before/after 报告。
 *
 * 覆盖：
 * 1) 健康库 → `healthy=true`、孤儿为空、兼容接口 `ftsHealthCheck()` 返回 null；
 * 2) 人工孤儿（chunk_fts/chunk_embedding 指向不存在 chunk）+ 重复 FTS 行 → 报告准确
 *    （orphanFtsChunkIds / orphanEmbeddingChunkIds / 计数不一致 reason），repair 后清零、
 *    FTS 重建、正常 chunk/向量不被误删；
 * 3) FTS 缺行（chunk 存在但无对应 FTS 行）→ 计数不一致被报告，repair 重建后可再次检索。
 *
 * 孤儿向量构造说明：v5 `chunk_embedding.chunkId` 外键（ON DELETE CASCADE）在 Room
 * 连接上默认开启，正常 DAO 写入无法留下引用不存在 chunk 的向量行。本测试用**文件库**：
 * 先经 Room seed 正常数据并关闭，再用独立的 `SQLiteDatabase` 连接（SQLite 默认
 * foreign_keys=OFF）插入历史残留行，最后重新打开 Room 交给 indexHealthCheck 检测。
 * 仅测试构造数据，不改生产逻辑。
 */
class IndexHealthDbTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: IngestRepository

    private val dbName = "index-health-test.db"

    private data class Seed(val documentId: String, val chunkIds: List<Long>)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        reopenRoom()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun healthyIndex_hasNoReasons() = runBlocking {
        seedValid(chunkCount = 3, embeddingCount = 2)

        val health = repository.indexHealthCheck()
        assertEquals(3, health.chunkCount)
        assertEquals(3, health.ftsCount)
        assertEquals(2, health.embeddingCount)
        assertTrue("健康库不应有 FTS 孤儿：${health.orphanFtsChunkIds}", health.orphanFtsChunkIds.isEmpty())
        assertTrue(
            "健康库不应有向量孤儿：${health.orphanEmbeddingChunkIds}",
            health.orphanEmbeddingChunkIds.isEmpty(),
        )
        assertTrue("健康库不应有 reasons：${health.reasons}", health.healthy)
        assertNull("兼容接口：健康时返回 null", repository.ftsHealthCheck())
        println(
            "B2_INDEX_HEALTH healthy chunk=${health.chunkCount} fts=${health.ftsCount} " +
                "embedding=${health.embeddingCount}",
        )
    }

    @Test
    fun orphansAndDuplicateFts_areReportedAndRepaired_withoutTouchingValidData() = runBlocking {
        val seed = seedValid(chunkCount = 2, embeddingCount = 2)
        // FTS 无外键：孤儿 FTS 行 + 同 chunk 重复行可直接写入（模拟 B1-1 前的历史数据）
        db.chunkDao().insertFts(listOf(ChunkFtsEntity(chunkId = ORPHAN_FTS_CHUNK_ID, bigramContent = "孤儿 儿块")))
        db.chunkDao().insertFts(
            listOf(ChunkFtsEntity(chunkId = seed.chunkIds[0], bigramContent = "重复 复行")),
        )
        // 孤儿向量：关闭 Room 后用独立连接写入历史残留，再重开
        insertOrphanEmbeddingViaRawSql(chunkId = ORPHAN_EMBEDDING_CHUNK_ID)
        reopenRoom()

        val before = repository.indexHealthCheck()
        assertEquals(2, before.chunkCount)
        assertEquals("2 有效 + 1 重复 + 1 孤儿", 4, before.ftsCount)
        assertEquals(3, before.embeddingCount)
        assertEquals("FTS 孤儿集合", listOf(ORPHAN_FTS_CHUNK_ID), before.orphanFtsChunkIds)
        assertEquals("向量孤儿集合", listOf(ORPHAN_EMBEDDING_CHUNK_ID), before.orphanEmbeddingChunkIds)
        assertFalse("存在孤儿/重复时不得判定健康", before.healthy)
        assertTrue(
            "应报告 FTS 行数不一致：${before.reasons}",
            before.reasons.any { it.contains("FTS 行数") },
        )
        assertTrue(
            "应报告向量孤儿：${before.reasons}",
            before.reasons.any { it.contains("向量孤儿") },
        )
        assertNotNull("兼容接口应报告计数不一致", repository.ftsHealthCheck())
        println(
            "B2_INDEX_HEALTH before chunk=${before.chunkCount} fts=${before.ftsCount} " +
                "embedding=${before.embeddingCount} orphan_fts=${before.orphanFtsChunkIds} " +
                "orphan_emb=${before.orphanEmbeddingChunkIds} reasons=${before.reasons.size}",
        )

        val repair = repository.repairIndex()
        assertTrue("重复/缺行场景应由全量重建 FTS 兜底", repair.rebuiltFts)
        assertEquals("重复行 + 孤儿行应被移除", 2, repair.removedFtsRows)
        assertEquals("孤儿向量行应被移除", 1, repair.removedEmbeddingRows)
        assertTrue(
            "修复后 FTS 孤儿应清零：${repair.after.orphanFtsChunkIds}",
            repair.after.orphanFtsChunkIds.isEmpty(),
        )
        assertTrue(
            "修复后向量孤儿应清零：${repair.after.orphanEmbeddingChunkIds}",
            repair.after.orphanEmbeddingChunkIds.isEmpty(),
        )
        assertEquals("重建后 FTS 行数应与 chunk 一致", repair.after.chunkCount, repair.after.ftsCount)
        assertTrue("修复后应健康：${repair.after.reasons}", repair.after.healthy)
        println(
            "B2_INDEX_HEALTH repaired rebuilt_fts=${repair.rebuiltFts} " +
                "removed_fts=${repair.removedFtsRows} removed_emb=${repair.removedEmbeddingRows} " +
                "after_chunk=${repair.after.chunkCount} after_fts=${repair.after.ftsCount} " +
                "after_embedding=${repair.after.embeddingCount}",
        )

        // 正常数据不受影响
        assertEquals("repair 不得删除 chunk", 2, db.chunkDao().count())
        assertEquals("repair 不得删除有效向量", 2, db.embeddingDao().count())
        // 重建后原内容仍可检索，且无重复召回（重复行已被 rebuild 清掉）
        val hits = db.chunkDao().searchFtsOrdered(
            BigramTokenizer.toOrMatchQuery("蓝牙连接"), 100, DocStatus.READY.value,
        )
        assertEquals("重建后应恰好命中 chunk 1 次（无重复召回）", listOf(seed.chunkIds[0]), hits)
    }

    @Test
    fun missingFtsRows_areDetectedAndRebuiltFromChunkContent() = runBlocking {
        val seed = seedValid(chunkCount = 2, embeddingCount = 0)
        // 删除 chunk1 的 FTS 行，模拟缺行
        db.chunkDao().deleteFtsByChunkIds(listOf(seed.chunkIds[1]))

        val before = repository.indexHealthCheck()
        assertEquals(2, before.chunkCount)
        assertEquals("缺 1 行 FTS", 1, before.ftsCount)
        assertFalse(before.healthy)
        assertTrue(
            "应报告 FTS 行数不一致：${before.reasons}",
            before.reasons.any { it.contains("FTS 行数") },
        )
        assertTrue("缺行不属于孤儿集合（只按 chunkId 判定）", before.orphanFtsChunkIds.isEmpty())

        val repair = repository.repairIndex()
        assertTrue(repair.rebuiltFts)
        assertEquals(2, repair.after.ftsCount)
        assertTrue("修复后应健康：${repair.after.reasons}", repair.after.healthy)
        // 缺行 chunk 的内容在重建后可检索
        val hits = db.chunkDao().searchFtsOrdered(
            BigramTokenizer.toOrMatchQuery("量子纠缠"), 100, DocStatus.READY.value,
        )
        assertEquals(listOf(seed.chunkIds[1]), hits)
    }

    // ---- seed / 工具 ----

    private fun reopenRoom() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        repository = IngestRepository(context, db = db)
    }

    /** 2~N 个 ready chunk（每 chunk 独立 content）+ FTS + 前 [embeddingCount] 个向量。 */
    private suspend fun seedValid(chunkCount: Int, embeddingCount: Int): Seed {
        db.sourceDao().upsert(SourceEntity("src", "src", "", null, 0, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc", sourceId = "src", title = "索引健康测试文档", checksum = "sum",
                parserId = "txt-utf8-v1", createdAt = 0, status = DocStatus.READY.value,
            ),
        )
        val contents = (0 until chunkCount).map { i ->
            if (i == 0) "蓝牙连接测试内容$i" else "量子纠缠测试内容$i"
        }
        val chunkIds = db.chunkDao().insertAll(
            contents.mapIndexed { i, text ->
                ChunkEntity(
                    documentId = "doc", ordinal = i, content = text,
                    charStart = 0, charEnd = text.length, tokenEstimate = text.length,
                )
            },
        )
        assertEquals("seed chunk 数", chunkCount, chunkIds.size)
        db.chunkDao().insertFts(
            chunkIds.mapIndexed { i, id ->
                ChunkFtsEntity(chunkId = id, bigramContent = BigramTokenizer.toFtsText(contents[i]))
            },
        )
        db.embeddingDao().insertAll(
            chunkIds.take(embeddingCount).map { id ->
                ChunkEmbeddingEntity(
                    chunkId = id, embeddingSpaceId = SPACE, role = "content",
                    vectorBlob = blob2d(), vectorNorm = 1.0, createdAt = 1,
                )
            },
        )
        return Seed("doc", chunkIds)
    }

    /**
     * 用独立 SQLite 连接写入"引用不存在 chunk"的历史残留向量行。
     * 前置条件：Room 已关闭（否则 Room 连接上的 foreign_keys=ON 会拒绝写入）。
     */
    private fun insertOrphanEmbeddingViaRawSql(chunkId: Long) {
        db.close()
        val path = context.getDatabasePath(dbName).absolutePath
        val raw = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL(
                "INSERT INTO chunk_embedding " +
                    "(chunkId, embeddingSpaceId, role, vectorBlob, vectorNorm, createdAt) " +
                    "VALUES ($chunkId, '$SPACE', 'content', X'0000003F0000803E', 1.0, 1)",
            )
        } finally {
            raw.close()
        }
    }

    private fun blob2d(): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(0f).array()

    private companion object {
        const val SPACE = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu"
        const val ORPHAN_FTS_CHUNK_ID = 9999L
        const val ORPHAN_EMBEDDING_CHUNK_ID = 8888L
    }
}
