package com.zhibox.memory.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P2-B1-8：v1 → v5 链式迁移（1→2→3→4→5）数据无损测试。
 *
 * 覆盖真实存量设备的升级路径：v1 库（初版 schema）→ v2（accelerator/sizeBytes，含 gpu 后缀回填）
 * → v3（quantization/app_kv）→ v4（ingest_job.attempt + 空间索引）→ v5（外键 + 默认值 + FK 自检）。
 * 全程只允许 schema 演进，不允许丢行/改内容/改变 FTS 可检索性。
 */
class MigrationV1ToV5Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To5_chained_dataPreserved_allDefaultsApplied() {
        val name = "migration-v1v5-chained.db"
        val gpuSpace = "mediapipe-universal/egemma2-440m/raw-symmetric-v1-gpu"
        helper.createDatabase(name, 1).use { db ->
            // v1 全表代表性数据（v1 无 sizeBytes/accelerator/quantization/attempt/app_kv）
            db.execSQL(
                "INSERT INTO source (id,title,uri,mimeType,createdAt,status) " +
                    "VALUES ('src_1','v1 来源','file:///v1.txt','text/plain',100,'active')",
            )
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status) " +
                    "VALUES ('doc_1','src_1','v1 文档','abc123','txt-utf8-v1',200,'ready')",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (1,'doc_1',0,'旧版向量检索',0,6,6)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (1,'旧版 版向 向量 量检 检索')")
            db.execSQL(
                "INSERT INTO embedding_meta (id,providerId,modelId,modelRevision,dimensions,normalization," +
                    "taskTemplate,createdAt,active) VALUES " +
                    "('$gpuSpace','mediapipe-universal','embeddinggemma-2-text-vision-440m',NULL," +
                    "2,'l2','raw-symmetric',300,1)",
            )
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (1,1,'$gpuSpace','content',X'0000003F0000803E',1.0,400)",
            )
            db.execSQL("INSERT INTO event (id,chunkId,canonicalText,confidence) VALUES (1,1,'v1 事件',0.7)")
            db.execSQL("INSERT INTO entity (id,normalizedName,entityType) VALUES (1,'智盒',NULL)")
            db.execSQL("INSERT INTO event_entity (id,eventId,entityId,role) VALUES (1,1,1,'subject')")
            db.execSQL("INSERT INTO chunk_entity (id,chunkId,entityId) VALUES (1,1,1)")
            db.execSQL(
                "INSERT INTO ingest_job (id,sourceId,documentId,stage,progress,error,createdAt,updatedAt) " +
                    "VALUES ('job_1','src_1','doc_1','done',100,NULL,500,600)",
            )
        }

        // 链式迁移 1→2→3→4→5，并让 Room 校验 v5 schema（含 FK/默认值/索引）
        val db = helper.runMigrationsAndValidate(
            name, 5, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
        )

        // --- v1→v2：accelerator 从 spaceId 后缀回填；sizeBytes 存量置 0 ---
        db.query("SELECT accelerator, quantization FROM embedding_meta WHERE id = ?", arrayOf(gpuSpace)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("gpu", c.getString(0))
            assertEquals("f32", c.getString(1)) // v2→v3 缺省
        }
        db.query("SELECT sizeBytes, title, status FROM document WHERE id = 'doc_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
            assertEquals("v1 文档", c.getString(1))
            assertEquals("ready", c.getString(2))
        }
        // --- v3→v4：attempt 缺省 0 ---
        db.query("SELECT attempt, stage FROM ingest_job WHERE id = 'job_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals("done", c.getString(1))
        }
        // --- 数据无损：chunk/chunk_embedding/关系表 ---
        db.query("SELECT id, documentId, ordinal, content FROM chunk").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("doc_1", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals("旧版向量检索", c.getString(3))
            assertTrue(!c.moveToNext())
        }
        db.query(
            "SELECT chunkId, embeddingSpaceId, role, vectorBlob, vectorNorm, createdAt " +
                "FROM chunk_embedding WHERE id = 1",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(gpuSpace, c.getString(1))
            assertEquals("content", c.getString(2))
            assertEquals(8, c.getBlob(3).size)
            assertEquals(1.0, c.getDouble(4), 0.0)
            assertEquals(400L, c.getLong(5))
        }
        db.query("SELECT chunkId, canonicalText FROM event WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("v1 事件", c.getString(1))
        }
        db.query("SELECT eventId, entityId FROM event_entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
        }
        db.query("SELECT chunkId, entityId FROM chunk_entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
        }
        // --- FTS 全程未重建，迁移后仍可检索 ---
        db.query("SELECT chunkId FROM chunk_fts WHERE chunk_fts MATCH '\"向量\"'").use { c ->
            assertTrue("链式迁移后 FTS 应仍可命中", c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        // --- v3 新表 app_kv 已建立（空表可用） ---
        db.execSQL("INSERT INTO app_kv (`key`,`value`,`updatedAt`) VALUES ('k','v',1)")
        // --- v4→v5：外键 8 条 + FK 自检为空 + schema_migrations 1..5 ---
        assertForeignKeyCheckClean(db)
        var fkCount = 0
        for (table in listOf("document", "chunk", "chunk_embedding", "event", "event_entity", "chunk_entity")) {
            db.query("PRAGMA foreign_key_list('$table')").use { c -> fkCount += c.count }
        }
        assertEquals("v5 外键约束数应为 8", 8, fkCount)
        val versions = ArrayList<Int>()
        db.query("SELECT version FROM schema_migrations ORDER BY version").use { c ->
            while (c.moveToNext()) versions += c.getInt(0)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), versions)
        // 默认值对齐（v5 重建列）
        db.query("PRAGMA table_info('document')").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            val dfltCol = c.getColumnIndexOrThrow("dflt_value")
            var checked = false
            while (c.moveToNext()) {
                if (c.getString(nameCol) == "sizeBytes") {
                    assertEquals("0", c.getString(dfltCol).trim())
                    checked = true
                }
            }
            assertTrue("document.sizeBytes 默认值缺失", checked)
        }

        // --- 链式迁移后的库可被 v5 实体打开并正常读写 ---
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            runBlocking {
                assertEquals("旧版向量检索", roomDb.chunkDao().chunksByIds(listOf(1L)).first().content)
                assertEquals(1, roomDb.chunkDao().ftsCount())
                assertEquals(gpuSpace, roomDb.embeddingDao().meta(gpuSpace)?.id)
                assertEquals("gpu", roomDb.embeddingDao().meta(gpuSpace)?.accelerator)
                assertEquals(1, roomDb.embeddingDao().count())
                assertEquals("v", roomDb.appKvDao().get("k"))
            }
        } finally {
            roomDb.close()
        }
    }

    private fun assertForeignKeyCheckClean(db: SupportSQLiteDatabase) {
        val violations = ArrayList<String>()
        db.query("PRAGMA foreign_key_check").use { c ->
            val tableCol = c.getColumnIndexOrThrow("table")
            while (c.moveToNext()) violations += c.getString(tableCol)
        }
        assertTrue("链式迁移后 foreign_key_check 非空（违规表：$violations）", violations.isEmpty())
    }
}
