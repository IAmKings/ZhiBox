package com.zhibox.memory.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * B14.2 schema v7 → v8（instrumentation，MigrationTestHelper 依据 app/schemas/8.json）：
 * - `source` 增加可空 `originUrl TEXT`：旧行 NULL（无 URL provenance），新行可写入 HTTPS URL；
 * - 加列不重写既有行、不改其他列/表；迁移记录 schema_migrations=8、foreign_key_check 空；
 * - v1 → v8 全链迁移保留 source/chunk 数据且 originUrl 为 NULL。
 */
class MigrationV7ToV8Test {

    private val testDb = "migration-v7-v8-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate7To8AddsNullableOriginUrlAndKeepsExistingRows() {
        helper.createDatabase(testDb, 7).use { db ->
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) " +
                    "VALUES ('src1', 'B14 迁移来源', '', NULL, 100, 'active')",
            )
            db.execSQL(
                "INSERT INTO source (id, title, uri, mimeType, createdAt, status) " +
                    "VALUES ('src2', 'B14 已有来源', 'content://x', 'text/plain', 200, 'deleted')",
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 8, true, AppDatabase.MIGRATION_7_8)

        // 旧行保留原值 + originUrl NULL
        db.query("SELECT id, title, uri, mimeType, status, originUrl FROM source ORDER BY id").use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("src1", c.getString(0))
            assertEquals("B14 迁移来源", c.getString(1))
            assertEquals("", c.getString(2))
            assertTrue("旧 mimeType 保持 NULL", c.isNull(3))
            assertEquals("active", c.getString(4))
            assertTrue("旧行 originUrl 必须为 NULL", c.isNull(5))
            assertTrue(c.moveToNext())
            assertEquals("src2", c.getString(0))
            assertEquals("content://x", c.getString(2))
            assertEquals("text/plain", c.getString(3))
            assertEquals("deleted", c.getString(4))
            assertTrue(c.isNull(5))
        }

        // 新列可写 HTTPS URL，且不影响已有行
        db.execSQL(
            "INSERT INTO source (id, title, uri, mimeType, createdAt, status, originUrl) " +
                "VALUES ('src3', 'URL 来源', '', NULL, 300, 'active', 'https://example.com/a?b=1')",
        )
        db.query("SELECT originUrl FROM source WHERE id = 'src3'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://example.com/a?b=1", c.getString(0))
        }
        db.query("SELECT originUrl FROM source WHERE id = 'src1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }

        // 迁移记录 + 外键完整
        db.query("SELECT COUNT(*) FROM schema_migrations WHERE version = 8").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun migrateV1ToV8FullChainPreservesSourceAndChunkWithNullOriginUrl() {
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
                    "VALUES (1, 'doc1', 0, 'v1 全链内容', 0, 6, 6)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 8, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        )
        db.query("SELECT id, title, status, originUrl FROM source WHERE id = 'src1'").use { c ->
            assertTrue("v1→v8 链式迁移后 source 应保留", c.moveToFirst())
            assertEquals("全链来源", c.getString(1))
            assertEquals("active", c.getString(2))
            assertTrue("全链迁移旧行 originUrl 应为 NULL", c.isNull(3))
        }
        db.query("SELECT id, content FROM chunk ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("v1 全链内容", c.getString(1))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
}
