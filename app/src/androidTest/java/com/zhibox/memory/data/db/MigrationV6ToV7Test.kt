package com.zhibox.memory.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.database.SQLException

/**
 * B9 schema v6 → v7（instrumentation，MigrationTestHelper 依据 app/schemas）：
 * - 先清理历史重复再建唯一索引：
 *   event_entity 按 (eventId, entityId) 保留 MIN(id)；
 *   extraction_job 按 chunkId 保留 MIN(rowid)；
 *   sag_vector 按 (ownerType, ownerId, role, spaceId) 保留 MIN(id)；
 * - 新增索引：event `(chunkId, active)` 普通索引；event_entity / extraction_job.chunkId /
 *   sag_vector 四元组为唯一索引（extraction_job 旧普通索引被唯一索引替换）；
 * - 既有行内容不修改（仅删除重复行），`foreign_key_check` 空，schema_migrations 记 v7；
 * - 迁移后唯一约束生效（重复写入抛约束异常）；
 * - v1 → v7 全链迁移数据无损。
 */
class MigrationV6ToV7Test {

    private val testDb = "migration-v6-v7-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate6To7_dedupesAndCreatesUniqueIndices() {
        helper.createDatabase(testDb, 6).use { db ->
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) " +
                    "VALUES ('src1', 'B9 迁移来源', '', NULL, 100, 'active')",
            )
            db.execSQL(
                "INSERT INTO document (id, sourceId, title, checksum, parserId, createdAt, status, sizeBytes) " +
                    "VALUES ('doc1', 'src1', 'B9 迁移文档', 'sum', 'txt-utf8-v1', 200, 'ready', 10)",
            )
            db.execSQL(
                "INSERT INTO chunk (id, documentId, ordinal, content, charStart, charEnd, tokenEstimate) " +
                    "VALUES (1, 'doc1', 0, '第一段', 0, 3, 3), (2, 'doc1', 1, '第二段', 3, 6, 3)",
            )
            db.execSQL(
                "INSERT INTO event (id, chunkId, canonicalText, confidence, title, summary, content, language, " +
                    "status, extractorVersion, extractionVersion, modelId, promptVersion, active, supersededBy, createdAt) " +
                    "VALUES (1, 1, '事件A', 0.9, '事件A', '', '', 'zh', 'active', 'v1', 'uuid-1', 'm', 'v1', 1, NULL, 500)",
            )
            db.execSQL(
                "INSERT INTO entity (id, normalizedName, entityType, name, description, createdAt) " +
                    "VALUES (1, '上海', 'location', '上海', '', 600), (2, '数学', 'subject', '数学', '', 601)",
            )
            // 重复 event_entity：(1,1) 两条 + (1,2) 一条（保留 MIN(id)=1 与 (1,2)）
            db.execSQL(
                "INSERT INTO event_entity (id, eventId, entityId, role, weight, description) VALUES " +
                    "(1, 1, 1, 'location', 0.5, '弱')," +
                    "(2, 1, 1, 'subject', 1.0, '重复行')," +
                    "(3, 1, 2, 'subject', 1.0, '核心')",
            )
            // 重复 extraction_job：同 chunk 1 两条 + chunk 2 一条（保留 MIN(rowid)）
            db.execSQL(
                "INSERT INTO extraction_job (id, documentId, chunkId, status, attempt, errorCode, errorDetail, createdAt, updatedAt) " +
                    "VALUES ('job-a', 'doc1', 1, 'queued', 0, NULL, NULL, 700, 700)," +
                    "('job-b', 'doc1', 1, 'failed', 3, 'X', 'dup', 701, 701)," +
                    "('job-c', 'doc1', 2, 'done', 1, NULL, NULL, 702, 702)",
            )
            // 重复 sag_vector：同四元组两条 + 其他两条（保留 MIN(id)）
            db.execSQL(
                "INSERT INTO sag_vector (id, ownerType, ownerId, role, spaceId, dimensions, vectorBlob, vectorNorm, createdAt) " +
                    "VALUES (1, 'EVENT', 1, 'event_title', 'space-v7', 2, X'0000803F00000000', 1.0, 800)," +
                    "(2, 'EVENT', 1, 'event_title', 'space-v7', 2, X'0000803F00000000', 1.0, 801)," +
                    "(3, 'EVENT', 1, 'event_summary', 'space-v7', 2, X'0000803F00000000', 1.0, 802)," +
                    "(4, 'ENTITY', 1, 'entity_description', 'space-v7', 2, X'0000803F00000000', 1.0, 803)",
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 7, true, AppDatabase.MIGRATION_6_7)

        // 1) 重复清理：保留 MIN(id)/MIN(rowid)
        db.query("SELECT id, eventId, entityId FROM event_entity ORDER BY id").use { c ->
            assertEquals("(1,1) 重复只留 MIN(id)=1，另保留 (1,2)", 2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals(1L, c.getLong(2))
            assertTrue(c.moveToNext())
            assertEquals(3L, c.getLong(0))
            assertEquals(2L, c.getLong(2))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT id, chunkId FROM extraction_job ORDER BY chunkId").use { c ->
            assertEquals("每 chunk 只保留一条 job", 2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("job-a", c.getString(0))
            assertEquals(1L, c.getLong(1))
            assertTrue(c.moveToNext())
            assertEquals("job-c", c.getString(0))
            assertEquals(2L, c.getLong(1))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT id, ownerType, ownerId, role FROM sag_vector ORDER BY id").use { c ->
            assertEquals("同四元组只留 MIN(id)，其余角色保留", 3, c.count)
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) ids += c.getLong(0)
            assertEquals(listOf(1L, 3L, 4L), ids)
        }

        // 2) 既有行内容不被修改（重复行删除，其余保留）
        db.query("SELECT canonicalText, title, active FROM event WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("事件A", c.getString(0))
            assertEquals("事件A", c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM entity").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }

        // 3) 新索引存在 + 唯一性（event 普通索引；其余三个唯一）
        assertIndex(db, table = "event", name = "index_event_chunkId_active", unique = 0)
        assertIndex(db, table = "event_entity", name = "index_event_entity_eventId_entityId", unique = 1)
        assertIndex(db, table = "extraction_job", name = "index_extraction_job_chunkId", unique = 1)
        assertIndex(
            db,
            table = "sag_vector",
            name = "index_sag_vector_ownerType_ownerId_role_spaceId",
            unique = 1,
        )

        // 4) schema_migrations 记录 v7；foreign_key_check 空
        db.query("SELECT COUNT(*) FROM schema_migrations WHERE version = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }

        // 5) 唯一约束生效：重复写入被拒绝
        assertDuplicateRejected {
            db.execSQL(
                "INSERT INTO event_entity (eventId, entityId, role, weight, description) " +
                    "VALUES (1, 1, 'dup', 1.0, 'dup')",
            )
        }
        assertDuplicateRejected {
            db.execSQL(
                "INSERT INTO extraction_job (id, documentId, chunkId, status, attempt, errorCode, errorDetail, createdAt, updatedAt) " +
                    "VALUES ('job-x', 'doc1', 1, 'queued', 0, NULL, NULL, 900, 900)",
            )
        }
        assertDuplicateRejected {
            db.execSQL(
                "INSERT INTO sag_vector (ownerType, ownerId, role, spaceId, dimensions, vectorBlob, vectorNorm, createdAt) " +
                    "VALUES ('EVENT', 1, 'event_title', 'space-v7', 2, X'0000803F00000000', 1.0, 901)",
            )
        }
    }

    @Test
    fun migrateV1ToV7FullChainPreservesData() {
        val dbName = "$testDb-chain"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) " +
                    "VALUES ('src1', '全链来源', '', NULL, 100, 'active')",
            )
            db.execSQL(
                "INSERT INTO document (id, sourceId, title, checksum, parserId, createdAt, status) " +
                    "VALUES ('doc1', 'src1', '全链文档', 'sum', 'txt-utf8-v1', 200, 'ready')",
            )
            db.execSQL(
                "INSERT INTO chunk (id, documentId, ordinal, content, charStart, charEnd, tokenEstimate) " +
                    "VALUES (1, 'doc1', 0, 'v1 内容', 0, 6, 6)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 7, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
        )
        db.query("SELECT id, content FROM chunk ORDER BY id").use { c ->
            assertTrue("v1→v7 链式迁移后 chunk 数据应保留", c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("v1 内容", c.getString(1))
        }
        db.query("SELECT title, status FROM document WHERE id = 'doc1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("全链文档", c.getString(0))
            assertEquals("ready", c.getString(1))
        }
        // 中间版本新增表/列在 v7 可用
        listOf("event", "entity", "event_entity", "sag_vector", "extraction_job", "query_trace").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { c ->
                assertTrue("全链后缺少表：$table", c.moveToFirst())
            }
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    private fun assertIndex(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        name: String,
        unique: Int,
    ) {
        var found = false
        var actualUnique = -1
        db.query("PRAGMA index_list('$table')").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    found = true
                    actualUnique = c.getInt(2)
                    break
                }
            }
        }
        assertTrue("表 $table 缺少索引 $name", found)
        assertEquals("索引 $name 的 unique 标志不符", unique, actualUnique)
    }

    private fun assertDuplicateRejected(statement: () -> Unit) {
        val error = runCatching(statement).exceptionOrNull()
        assertTrue("重复写入应抛 SQL 约束异常：$error", error is SQLException)
    }
}
