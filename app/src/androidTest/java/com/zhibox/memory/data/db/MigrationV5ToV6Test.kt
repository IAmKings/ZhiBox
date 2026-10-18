package com.zhibox.memory.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.database.SQLException

/**
 * B8 schema v5 → v6 迁移（instrumentation，MigrationTestHelper 依据 app/schemas）：
 * - additive：event/entity/event_entity 旧行保留并回填默认值；
 * - 新增表/索引/FTS 均可创建且与 Room 预期 schema 一致（runMigrationsAndValidate）；
 * - `foreign_key_check` 空（迁移末尾自检不抛）；
 * - event 重复旧行不删除：非最新行改写为 `legacy-v5-<id>` 后建唯一索引 `(chunkId, extractionVersion)`；
 * - schema_migrations 记录 version=6；
 * - 迁移后的新表可正常 INSERT（sag_vector/extraction_job/chat/query_trace）。
 */
class MigrationV5ToV6Test {

    private val testDb = "migration-v5-v6-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate5To6_preservesDataAndBackfillsDefaults() {
        helper.createDatabase(testDb, 5).use { db ->
            // v5 最小数据：source → document → chunk ×2 → event ×2（同 chunk，v5 允许重复） + entity/event_entity
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) " +
                    "VALUES ('src1', '迁移来源', '', NULL, 100, 'active')",
            )
            db.execSQL(
                "INSERT INTO document (id, sourceId, title, checksum, parserId, createdAt, status, sizeBytes) " +
                    "VALUES ('doc1', 'src1', '迁移文档', 'sum', 'txt-utf8-v1', 200, 'ready', 10)",
            )
            db.execSQL(
                "INSERT INTO chunk (id, documentId, ordinal, content, charStart, charEnd, tokenEstimate) " +
                    "VALUES (1, 'doc1', 0, '第一段', 0, 3, 3), (2, 'doc1', 1, '第二段', 3, 6, 3)",
            )
            // 同 chunk 两条旧 event（v5 无唯一约束）；迁移保留旧行，非最新行改写 legacy 版本戳后建唯一索引
            db.execSQL(
                "INSERT INTO event (id, chunkId, canonicalText, confidence) " +
                    "VALUES (1, 1, '旧事件A', 0.8), (2, 1, '旧事件B', 0.9)",
            )
            db.execSQL(
                "INSERT INTO entity (id, normalizedName, entityType) " +
                    "VALUES (1, '张三', 'person')",
            )
            // 引用保留下来的 event id=2（id=1 将被迁移去重删除，若引用 id=1 会级联删除）
            db.execSQL(
                "INSERT INTO event_entity (id, eventId, entityId, role) " +
                    "VALUES (1, 2, 1, 'subject')",
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 6, true, AppDatabase.MIGRATION_5_6)

        // 1) schema_migrations 记录 v6
        db.query("SELECT COUNT(*) FROM schema_migrations WHERE version = 6").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // 2) event：additive 不删旧行；同 (chunkId, 空 extractionVersion) 的重复行中，
        //    非最新行由迁移改写为确定性的 legacy-v5-<id>（唯一索引前处理），最新行保留空串。
        db.query(
            "SELECT id, chunkId, canonicalText, confidence, title, summary, content, language, " +
                "status, extractorVersion, extractionVersion, modelId, promptVersion, active, " +
                "supersededBy, createdAt FROM event ORDER BY id",
        ).use { c ->
            assertEquals("旧 event 行应全部保留（不因唯一索引删除数据）", 2, c.count)

            assertTrue(c.moveToFirst()) // id=1（非最新，被改写版本戳）
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals("旧事件A", c.getString(2))
            assertEquals(0.8, c.getDouble(3), 0.0001)
            assertEquals("", c.getString(4)) // title
            assertEquals("", c.getString(5)) // summary
            assertEquals("", c.getString(6)) // content
            assertEquals("", c.getString(7)) // language
            assertEquals("active", c.getString(8))
            assertEquals("", c.getString(9)) // extractorVersion
            assertEquals("legacy-v5-1", c.getString(10))
            assertEquals("", c.getString(11))
            assertEquals("", c.getString(12))
            assertEquals(1, c.getInt(13)) // active
            assertTrue(c.isNull(14)) // supersededBy
            assertEquals(0L, c.getLong(15))

            assertTrue(c.moveToNext()) // id=2（最新，保留空串）
            assertEquals(2L, c.getLong(0))
            assertEquals("旧事件B", c.getString(2))
            assertEquals(0.9, c.getDouble(3), 0.0001)
            assertEquals("", c.getString(10)) // extractionVersion
            assertEquals(1, c.getInt(13))
            assertFalse(c.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM event WHERE extractionVersion LIKE 'legacy-v5-%'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("只有非最新重复行被打 legacy 版本戳", 1, c.getInt(0))
        }

        // 3) entity / event_entity 扩展列默认值
        db.query("SELECT name, description, createdAt FROM entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
            assertEquals("", c.getString(1))
            assertEquals(0L, c.getLong(2))
        }
        db.query("SELECT eventId, entityId, role, weight, description FROM event_entity").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(2L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals("subject", c.getString(2))
            assertEquals(1.0, c.getDouble(3), 0.0001) // 新增列默认 1.0
            assertTrue(c.isNull(4))
        }

        // 4) 新表 + FTS 均存在
        val tables = listOf(
            "sag_vector", "event_fts", "entity_fts", "extraction_job",
            "chat_conversation", "chat_message", "message_citation", "query_trace",
        )
        tables.forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { c ->
                assertTrue("缺少新表：$table", c.moveToFirst())
            }
        }

        // 5) 索引齐全（v5 既有 + v6 新增）
        val indices = listOf(
            "index_event_chunkId",
            "index_event_chunkId_extractionVersion",
            "index_sag_vector_ownerType_ownerId",
            "index_sag_vector_spaceId_role_ownerType",
            "index_extraction_job_documentId",
            "index_extraction_job_chunkId",
            "index_extraction_job_status",
            "index_chat_message_conversationId",
            "index_message_citation_messageId",
            "index_message_citation_chunkId",
            "index_query_trace_queryHash",
            "index_query_trace_createdAt",
        )
        indices.forEach { index ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$index'").use { c ->
                assertTrue("缺少索引：$index", c.moveToFirst())
            }
        }

        // 6) 外键：extraction_job → document/chunk，chat_message → conversation，message_citation → message
        assertEquals(2, fkCount(db, "extraction_job"))
        assertEquals(1, fkCount(db, "chat_message"))
        assertEquals(1, fkCount(db, "message_citation"))
        assertEquals(1, fkCount(db, "event"))

        // 7) foreign_key_check 空
        db.query("PRAGMA foreign_key_check").use { assertEquals("foreign_key_check 应为空", 0, it.count) }

        // 8) 新表可写：sag_vector / extraction_job / chat / query_trace
        db.execSQL(
            "INSERT INTO sag_vector (ownerType, ownerId, role, spaceId, dimensions, vectorBlob, vectorNorm, createdAt) " +
                "VALUES ('EVENT', 2, 'event_content', 'space-v6', 2, x'0000803F', 1.0, 1)",
        )
        db.execSQL(
            "INSERT INTO extraction_job (id, documentId, chunkId, status, attempt, errorCode, errorDetail, createdAt, updatedAt) " +
                "VALUES ('job-1', 'doc1', 1, 'queued', 0, NULL, NULL, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO chat_conversation (id, title, sourceScope, createdAt, updatedAt) " +
                "VALUES ('conv1', '迁移后会话', NULL, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO chat_message (id, conversationId, role, content, createdAt, model) " +
                "VALUES ('m1', 'conv1', 'user', '问题', 1, NULL)",
        )
        db.execSQL(
            "INSERT INTO message_citation (messageId, chunkId, rank) VALUES ('m1', 1, 0)",
        )
        db.execSQL(
            "INSERT INTO query_trace (queryHash, mode, timingJson, degradedReason, createdAt) " +
                "VALUES ('hash', 'hybrid', '{}', NULL, 1)",
        )
        db.query("PRAGMA foreign_key_check").use { assertTrue(it.count == 0) }
    }

    @Test
    fun migrate5To6_uniqueEventIndexRejectsDuplicateVersion() {
        helper.createDatabase(testDb + "-unique", 5).use { db ->
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) VALUES ('s', 't', '', NULL, 1, 'active')",
            )
            db.execSQL(
                "INSERT INTO document (id, sourceId, title, checksum, parserId, createdAt, status, sizeBytes) " +
                    "VALUES ('d', 's', 't', 'c', 'p', 1, 'ready', 0)",
            )
            db.execSQL(
                "INSERT INTO chunk (id, documentId, ordinal, content, charStart, charEnd, tokenEstimate) " +
                    "VALUES (1, 'd', 0, 'x', 0, 1, 1)",
            )
        }
        val db = helper.runMigrationsAndValidate(testDb + "-unique", 6, true, AppDatabase.MIGRATION_5_6)

        // 唯一索引 (chunkId, extractionVersion)：同 chunk 同版本第二条必须被拒绝
        db.execSQL(
            "INSERT INTO event (chunkId, canonicalText, confidence, extractionVersion) " +
                "VALUES (1, 'a', 1.0, 'uuid-1')",
        )
        val violation = runCatching {
            db.execSQL(
                "INSERT INTO event (chunkId, canonicalText, confidence, extractionVersion) " +
                    "VALUES (1, 'b', 1.0, 'uuid-1')",
            )
        }.exceptionOrNull()
        assertTrue("重复 (chunkId, extractionVersion) 应被唯一索引拒绝：$violation", violation is SQLException)
    }

    private fun fkCount(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int = try {
        db.query("PRAGMA foreign_key_list($table)").use { it.count }
    } catch (e: Exception) {
        0
    }
}
