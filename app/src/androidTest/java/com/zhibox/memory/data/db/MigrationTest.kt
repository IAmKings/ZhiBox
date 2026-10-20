package com.zhibox.memory.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * W4-5 首次 schema 迁移测试（§9.1-10）：v1 建库 → 插入代表性数据 → MIGRATION_1_2 →
 * 验证 schema 结构、数据无损、accelerator 从 spaceId 后缀回填。
 * 以 connectedDebugAndroidTest 在真机执行（MigrationTestHelper 需 instrumentation）。
 */
class MigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_dataPreserved_andAcceleratorBackfilled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 1. 以 v1 schema 建库（helper 依据 schemas/1.json）
        helper.createDatabase(testDb, 1).use { db ->
            // 空间：cpu（无后缀）、gpu、npu 各一 + 一篇文档
            db.execSQL(
                """INSERT INTO embedding_meta
                   (id, providerId, modelId, modelRevision, dimensions, normalization,
                    taskTemplate, createdAt, active)
                   VALUES
                   ('mp-universal/egemma2-440m/app-prefix-v1', 'mediapipe-universal',
                    'embeddinggemma-2-text-vision-440m', NULL, 768, 'l2',
                    'app-prefix-v1', 100, 1)"""
            )
            db.execSQL(
                """INSERT INTO embedding_meta
                   (id, providerId, modelId, modelRevision, dimensions, normalization,
                    taskTemplate, createdAt, active)
                   VALUES
                   ('mediapipe-universal/egemma2-440m/raw-symmetric-v1-gpu', 'mediapipe-universal',
                    'embeddinggemma-2-text-vision-440m', NULL, 768, 'l2',
                    'raw-symmetric', 101, 1)"""
            )
            db.execSQL(
                """INSERT INTO embedding_meta
                   (id, providerId, modelId, modelRevision, dimensions, normalization,
                    taskTemplate, createdAt, active)
                   VALUES
                   ('litertlm-engine/embeddinggemma-2-740m/raw-symmetric-v1-npu', 'litertlm-engine',
                    'embeddinggemma-2-740m', NULL, 768, 'l2',
                    'raw-symmetric', 102, 1)"""
            )
            db.execSQL(
                """INSERT INTO document
                   (id, sourceId, title, checksum, parserId, createdAt, status)
                   VALUES
                   ('doc_1', 'src_1', '迁移测试文档', 'abc123', 'txt-utf8-v1', 200, 'ready')"""
            )
        }

        // 2. 迁移并校验 v2 schema
        val db = helper.runMigrationsAndValidate(testDb, 2, true, AppDatabase.MIGRATION_1_2)

        // 3. 数据无损 + 回填正确
        db.query("SELECT id, accelerator FROM embedding_meta ORDER BY createdAt").use { c ->
            assertTrue(c.moveToFirst())
            c.let {
                assertEquals("mp-universal/egemma2-440m/app-prefix-v1", it.getString(0))
                assertEquals("cpu", it.getString(1)) // 无后缀 → cpu
            }
            assertTrue(c.moveToNext())
            assertEquals("mediapipe-universal/egemma2-440m/raw-symmetric-v1-gpu", c.getString(0))
            assertEquals("gpu", c.getString(1)) // -gpu 后缀 → gpu
            assertTrue(c.moveToNext())
            assertEquals("litertlm-engine/embeddinggemma-2-740m/raw-symmetric-v1-npu", c.getString(0))
            assertEquals("npu", c.getString(1)) // -npu 后缀 → npu
            assertTrue(!c.moveToNext()) // 恰好 3 行
        }
        db.query("SELECT id, title, status, sizeBytes FROM document WHERE id = 'doc_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("迁移测试文档", c.getString(1))
            assertEquals("ready", c.getString(2))
            assertEquals(0L, c.getLong(3)) // 存量 sizeBytes 置 0
        }

        // 4. 迁移后的库可被当前 Room 实体正常打开（entity=v6，需注册全链迁移）
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .build()
        try {
            runBlocking {
                val meta = roomDb.embeddingDao().allMeta()
                assertEquals(3, meta.size)
                assertTrue(meta.all { it.accelerator in setOf("cpu", "gpu", "npu") })
            }
        } finally {
            roomDb.close()
        }
    }
}

class MigrationV2ToV3Test {

    private val testDb = "migration-v2v3-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3_quantizationDefault_andKvTableUsable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper.createDatabase(testDb, 2).use { db ->
            db.execSQL(
                """INSERT INTO embedding_meta
                   (id, providerId, modelId, modelRevision, dimensions, normalization,
                    taskTemplate, createdAt, active, accelerator)
                   VALUES
                   ('mp-universal/egemma2-440m/app-prefix-v1', 'mediapipe-universal',
                    'embeddinggemma-2-text-vision-440m', NULL, 768, 'l2',
                    'app-prefix-v1', 100, 1, 'gpu')"""
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 3, true, AppDatabase.MIGRATION_2_3)

        // quantization 列缺省 f32、既有数据无损
        db.query("SELECT id, accelerator, quantization FROM embedding_meta").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("gpu", c.getString(1))
            assertEquals("f32", c.getString(2))
            assertTrue(!c.moveToNext())
        }
        // app_kv 表在迁移后即可用（建表类型覆盖）
        db.execSQL(
            """INSERT INTO app_kv (`key`, `value`, `updatedAt`)
               VALUES ('reembed_last_done', '300', 12345)"""
        )
        db.query("SELECT value FROM app_kv WHERE `key` = 'reembed_last_done'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("300", c.getString(0))
        }

        // v3 DAO 读写
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .build()
        try {
            runBlocking {
                assertEquals("300", roomDb.appKvDao().get("reembed_last_done"))
                roomDb.appKvDao().put(
                    AppKvEntity("reembed_last_done", "600", 999)
                )
                assertEquals("600", roomDb.appKvDao().get("reembed_last_done"))
                assertEquals(999L, roomDb.appKvDao().getUpdatedAt("reembed_last_done"))
            }
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun migrate1To3_chained() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper.createDatabase(testDb + "chained", 1).use { db ->
            db.execSQL(
                """INSERT INTO embedding_meta
                   (id, providerId, modelId, modelRevision, dimensions, normalization,
                    taskTemplate, createdAt, active)
                   VALUES
                   ('litertlm-engine/embeddinggemma-2-text-270m/raw-symmetric-v1', 'litertlm-engine',
                    'embeddinggemma-2-text-270m', NULL, 768, 'l2',
                    'raw-symmetric', 100, 1)"""
            )
        }

        // 链式迁移 1→2→3
        val db = helper.runMigrationsAndValidate(
            testDb + "chained", 3, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
        )
        db.query("SELECT id, accelerator, quantization FROM embedding_meta").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("cpu", c.getString(1)) // v1 无后缀 → cpu
            assertEquals("f32", c.getString(2)) // v3 缺省
        }
    }
}

/**
 * schema v3 → v4（P1-2/P1-5）：ingest_job.attempt 列与 chunk_embedding 空间索引。
 */
class MigrationV3ToV4Test {

    private val testDb = "migration-v3v4-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate3To4_attemptColumnDefault_andVectorSpaceIndex() {
        helper.createDatabase(testDb, 3).use { db ->
            db.execSQL(
                """INSERT INTO ingest_job
                   (id, sourceId, documentId, stage, progress, error, createdAt, updatedAt)
                   VALUES ('job_1', 'src_1', 'doc_1', 'queued', 0, NULL, 1, 1)"""
            )
            db.execSQL(
                """INSERT INTO chunk_embedding
                   (id, chunkId, embeddingSpaceId, role, vectorBlob, vectorNorm, createdAt)
                   VALUES (1, 10, 'space-a', 'content', X'00000000', 1.0, 1)"""
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 4, true, AppDatabase.MIGRATION_3_4)

        // attempt 列缺省 0，存量 job 无损
        db.query("SELECT attempt, stage FROM ingest_job WHERE id = 'job_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals("queued", c.getString(1))
        }
        // 空间索引存在（P1-5 检索热路径）
        var indexFound = false
        db.query("PRAGMA index_list('chunk_embedding')").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == "index_chunk_embedding_embeddingSpaceId_role") {
                    indexFound = true
                }
            }
        }
        assertTrue("chunk_embedding 空间索引缺失", indexFound)

        // 迁移后的库可被 v5→v6 实体打开（Room 全链迁移）
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .build()
        try {
            runBlocking {
                assertEquals(1, roomDb.ingestJobDao().get("job_1")?.attempt?.plus(1))
            }
        } finally {
            roomDb.close()
        }
    }
}
