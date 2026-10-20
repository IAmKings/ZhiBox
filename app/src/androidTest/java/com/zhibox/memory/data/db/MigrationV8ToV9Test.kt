package com.zhibox.memory.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * B15.1 schema v8 → v9（instrumentation，MigrationTestHelper 依据 app/schemas/9.json）：
 * - 新增 `entity_alias`（id/entityId/alias/normalizedAlias/createdAt + normalizedAlias 唯一索引 +
 *   entityId 索引 + FK entityId→entity.id CASCADE）；
 * - 老库迁移后别名表为空、既有 entity/数据不丢失；迁移记录 schema_migrations=9、外键完整；
 * - 别名 FK 级联：删除 entity 自动清理其 aliases。
 */
class MigrationV8ToV9Test {

    private val testDb = "migration-v8-v9-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate8To9CreatesEntityAliasAndKeepsEntities() {
        helper.createDatabase(testDb, 8).use { db ->
            db.execSQL(
                "INSERT INTO entity (id, normalizedName, entityType, name, description, createdAt) " +
                    "VALUES (1, '上海', 'location', '上海', '城市', 100)",
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 9, true, AppDatabase.MIGRATION_8_9)

        // 既有 entity 保留
        db.query("SELECT id, normalizedName, name FROM entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("上海", c.getString(1))
            assertEquals("上海", c.getString(2))
        }

        // 新表已建且为空（旧库无别名）
        db.query("SELECT COUNT(*) FROM entity_alias").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // 可写入别名 + 唯一约束 + FK 级联
        db.execSQL(
            "INSERT INTO entity_alias (entityId, alias, normalizedAlias, createdAt) " +
                "VALUES (1, '沪上', '沪上', 200)",
        )
        db.query("SELECT entityId, alias, normalizedAlias FROM entity_alias").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("沪上", c.getString(1))
            assertEquals("沪上", c.getString(2))
        }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM entity WHERE id = 1")
        db.query("SELECT COUNT(*) FROM entity_alias").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("删除 entity 应级联清理别名", 0, c.getInt(0))
        }

        // 迁移记录 + 外键完整
        db.query("SELECT COUNT(*) FROM schema_migrations WHERE version = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun migrateV1ToV9FullChainPreservesDataAndAddsEntityAlias() {
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
            dbName, 9, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9,
        )
        db.query("SELECT id, title FROM source WHERE id = 'src1'").use { c ->
            assertTrue("v1→v9 链式迁移后 source 应保留", c.moveToFirst())
            assertEquals("全链来源", c.getString(1))
        }
        db.query("SELECT content FROM chunk WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("v1 全链内容", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM entity_alias").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("v1→v9 新表应为空", 0, c.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
}
