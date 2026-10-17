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
 * P2-B1-8：schema v4 → v5 迁移测试（数据正确性收敛）。
 *
 * v5 变更（见 AppDatabase.MIGRATION_4_5）：
 * 1) 8 条外键（父→子，ON DELETE CASCADE）覆盖 document/chunk/chunk_embedding/event/event_entity/chunk_entity；
 * 2) document.sizeBytes / embedding_meta.accelerator / embedding_meta.quantization 的 DB 默认值与实体对齐；
 * 3) 迁移前清理历史孤儿行，迁移后 `PRAGMA foreign_key_check` 必须为空；
 * 4) schema_migrations 写入 1..5。
 *
 * 另含两条**孤儿清理回归**用例（Lead 已加固 MIGRATION_4_5 的 doomedChunks 清理段）：
 * - 链式孤儿（document.sourceId 缺失 → 其 chunk 子树变新孤儿）必须在重建前整体清理，
 *   否则 chunk_new 的 FK 自检非空会导致迁移回滚；
 * - 被删除的 doomed chunk（无父 document / 父 document 无 source）的 chunk_fts 与
 *   chunk_embedding 必须同步清理，否则迁移后 FTS 残留（ftsHealthCheck 误报损坏）。
 */
class MigrationV4ToV5Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    // ---- 1) schema 结构 + 默认值 + 索引 + 迁移记录 ----

    @Test
    fun migrate4To5_schema_defaults_foreignKeys_indices_andMigrationLog() {
        val name = "migration-v4v5-schema.db"
        helper.createDatabase(name, 4).use { seedV4Full(it) }

        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)

        // 外键：8 条约束 / 6 张子表（FK 由父→子声明，删除父行级联删子行）
        val expectedForeignKeys = setOf(
            "document.sourceId->source.id:CASCADE",
            "chunk.documentId->document.id:CASCADE",
            "chunk_embedding.chunkId->chunk.id:CASCADE",
            "event.chunkId->chunk.id:CASCADE",
            "event_entity.eventId->event.id:CASCADE",
            "event_entity.entityId->entity.id:CASCADE",
            "chunk_entity.chunkId->chunk.id:CASCADE",
            "chunk_entity.entityId->entity.id:CASCADE",
        )
        val actualForeignKeys = listOf(
            "document", "chunk", "chunk_embedding", "event", "event_entity", "chunk_entity",
        ).flatMap { foreignKeys(db, it) }.toSet()
        assertEquals("v5 外键集合不符（应为父→子 8 条）", expectedForeignKeys, actualForeignKeys)
        assertEquals(8, actualForeignKeys.size)
        assertTrue("source/entity/ingest_job 不应有外键", foreignKeys(db, "source").isEmpty())
        assertTrue(foreignKeys(db, "ingest_job").isEmpty())

        // 默认值：迁移库与新建库 PRAGMA 必须一致（实体 @ColumnInfo(defaultValue)）
        val documentColumns = tableInfo(db, "document")
        assertEquals("0", documentColumns["sizeBytes"]?.trim())
        val metaColumns = tableInfo(db, "embedding_meta")
        assertEquals("'cpu'", metaColumns["accelerator"]?.trim())
        assertEquals("'f32'", metaColumns["quantization"]?.trim())
        // 新增 NOT NULL 列必须仍是 NOT NULL（alter 回填字段不允许被放宽）
        assertEquals("1", documentColumns["sizeBytes_notnull"])
        assertEquals("1", metaColumns["accelerator_notnull"])

        // 索引：v5 期望 14 个业务索引（含 v4 新加的空间+角色索引与 FK 子列索引）
        val expectedIndices = setOf(
            "index_document_sourceId",
            "index_chunk_documentId",
            "index_chunk_documentId_ordinal",
            "index_chunk_embedding_chunkId",
            "index_chunk_embedding_chunkId_embeddingSpaceId_role",
            "index_chunk_embedding_embeddingSpaceId_role",
            "index_event_chunkId",
            "index_entity_normalizedName",
            "index_event_entity_eventId",
            "index_event_entity_entityId",
            "index_chunk_entity_chunkId",
            "index_chunk_entity_entityId",
            "index_ingest_job_sourceId",
            "index_ingest_job_documentId",
        )
        assertEquals(expectedIndices, indexNames(db))
        // 唯一性约束不能被迁移重建丢掉
        assertTrue("chunk(documentId,ordinal) 唯一索引丢失", isUniqueIndex(db, "index_chunk_documentId_ordinal"))
        assertTrue(
            "chunk_embedding(chunkId,space,role) 唯一索引丢失",
            isUniqueIndex(db, "index_chunk_embedding_chunkId_embeddingSpaceId_role"),
        )

        // schema_migrations 记录 1..5（v4 及以前为空表，迁移补齐历史）
        assertEquals(listOf(1, 2, 3, 4, 5), schemaMigrationVersions(db))

        // 迁移后 FK 完整性自检为空
        assertForeignKeyCheckClean(db)

        // 迁移后的库可被 v5 实体直接打开并读写（Room 校验 schema/默认值/FK）
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roomDb = openRoom(context, name)
        try {
            runBlocking {
                assertEquals(12345L, roomDb.documentDao().get("doc_1")?.sizeBytes)
                val meta = roomDb.embeddingDao().meta("space_a")!!
                assertEquals("gpu", meta.accelerator)
                assertEquals("f32", meta.quantization)
                assertEquals("abcdef123456", meta.modelRevision)
                assertEquals(2, roomDb.ingestJobDao().get("job_1")?.attempt)
            }
        } finally {
            roomDb.close()
        }
    }

    // ---- 2) 全套代表性数据无损 ----

    @Test
    fun migrate4To5_allRepresentativeRowsPreserved() {
        val name = "migration-v4v5-data.db"
        helper.createDatabase(name, 4).use { seedV4Full(it) }

        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)

        // source / document（含 sizeBytes 与状态）
        db.query("SELECT title, uri, status FROM source WHERE id = 'src_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("迁移父表", c.getString(0))
            assertEquals("file:///v4.txt", c.getString(1))
            assertEquals("active", c.getString(2))
        }
        db.query("SELECT title, checksum, parserId, status, sizeBytes FROM document WHERE id = 'doc_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("迁移文档", c.getString(0))
            assertEquals("abc123", c.getString(1))
            assertEquals("txt-utf8-v1", c.getString(2))
            assertEquals("ready", c.getString(3))
            assertEquals(12345L, c.getLong(4))
        }
        // chunk：id/ordinal/content 逐字段保留
        db.query("SELECT id, documentId, ordinal, content, charStart, charEnd, tokenEstimate FROM chunk").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("doc_1", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals("向量检索迁移内容", c.getString(3))
            assertEquals(0, c.getInt(4))
            assertEquals(8, c.getInt(5))
            assertEquals(8, c.getInt(6))
            assertTrue(!c.moveToNext())
        }
        // chunk_fts：迁移不重建 FTS（chunk 数据一致时索引仍可查）
        db.query("SELECT chunkId FROM chunk_fts WHERE chunk_fts MATCH '\"向量\"'").use { c ->
            assertTrue("迁移后 FTS 应仍可命中 chunk 1", c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        // chunk_embedding：角色/空间/BLOB/范数/时间戳全保留
        db.query(
            "SELECT chunkId, embeddingSpaceId, role, vectorBlob, vectorNorm, createdAt " +
                "FROM chunk_embedding WHERE id = 1",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("space_a", c.getString(1))
            assertEquals("content", c.getString(2))
            assertEquals(8, c.getBlob(3).size) // 2 维 × 4B
            assertEquals(1.0, c.getDouble(4), 0.0)
            assertEquals(400L, c.getLong(5))
        }
        // embedding_meta：11 个字段（含 v2/v3 新增列）无损
        db.query(
            "SELECT providerId, modelId, modelRevision, dimensions, normalization, taskTemplate, " +
                "createdAt, active, accelerator, quantization FROM embedding_meta WHERE id = 'space_a'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("mediapipe-universal", c.getString(0))
            assertEquals("embeddinggemma-2-text-vision-440m", c.getString(1))
            assertEquals("abcdef123456", c.getString(2))
            assertEquals(2, c.getInt(3))
            assertEquals("l2", c.getString(4))
            assertEquals("app-prefix-v1", c.getString(5))
            assertEquals(300L, c.getLong(6))
            assertEquals(1, c.getInt(7))
            assertEquals("gpu", c.getString(8))
            assertEquals("f32", c.getString(9))
        }
        // 关系层（Phase 2 预留表）：event / entity / event_entity / chunk_entity
        db.query("SELECT chunkId, canonicalText, confidence FROM event WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("迁移事件", c.getString(1))
            assertEquals(0.9, c.getDouble(2), 0.0)
        }
        db.query("SELECT normalizedName, entityType FROM entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("智盒", c.getString(0))
            assertEquals("PRODUCT", c.getString(1))
        }
        db.query("SELECT eventId, entityId, role FROM event_entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals("subject", c.getString(2))
        }
        db.query("SELECT chunkId, entityId FROM chunk_entity WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
        }
        // ingest_job（v4 attempt）与 app_kv（v3）不参与重建但必须原样保留
        db.query("SELECT stage, progress, attempt FROM ingest_job WHERE id = 'job_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("done", c.getString(0))
            assertEquals(100, c.getInt(1))
            assertEquals(2, c.getInt(2))
        }
        db.query("SELECT value, updatedAt FROM app_kv WHERE `key` = 'reembed_last_done'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("123", c.getString(0))
            assertEquals(700L, c.getLong(1))
        }
        // 行数整体一致
        assertEquals(1, count(db, "source"))
        assertEquals(1, count(db, "document"))
        assertEquals(1, count(db, "chunk"))
        assertEquals(1, count(db, "chunk_fts"))
        assertEquals(1, count(db, "chunk_embedding"))
        assertEquals(1, count(db, "event"))
        assertEquals(1, count(db, "entity"))
        assertEquals(1, count(db, "event_entity"))
        assertEquals(1, count(db, "chunk_entity"))
        assertForeignKeyCheckClean(db)
    }

    // ---- 3) 孤儿清理（要求覆盖：chunk / chunk_embedding / event_entity）----

    @Test
    fun migrate4To5_orphanRowsCleaned_foreignKeyCheckClean() {
        val name = "migration-v4v5-orphan.db"
        helper.createDatabase(name, 4).use { db ->
            // 有效链（必须存活）
            db.execSQL(
                "INSERT INTO source (id,title,uri,mimeType,createdAt,status) " +
                    "VALUES ('src_ok','有效来源','file:///ok.txt','text/plain',1,'active')",
            )
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                    "VALUES ('doc_ok','src_ok','有效文档','sum','txt-utf8-v1',2,'ready',10)",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (1,'doc_ok',0,'有效块内容',0,4,4)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (1,'有效 效块')")
            db.execSQL(
                "INSERT INTO embedding_meta (id,providerId,modelId,modelRevision,dimensions,normalization," +
                    "taskTemplate,createdAt,active,accelerator,quantization) " +
                    "VALUES ('space_a','mediapipe-universal','m',NULL,2,'l2','raw-symmetric',3,1,'cpu','f32')",
            )
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (1,1,'space_a','content',X'0000003F0000803E',1.0,4)",
            )
            db.execSQL(
                "INSERT INTO event (id,chunkId,canonicalText,confidence) VALUES (1,1,'有效事件',0.8)",
            )
            db.execSQL("INSERT INTO entity (id,normalizedName,entityType) VALUES (1,'有效实体',NULL)")
            db.execSQL("INSERT INTO event_entity (id,eventId,entityId,role) VALUES (1,1,1,'subject')")
            db.execSQL("INSERT INTO chunk_entity (id,chunkId,entityId) VALUES (1,1,1)")

            // 孤儿：chunk 无 document、chunk_embedding 无 chunk、event/event_entity/chunk_entity 无父行
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (900,'doc_missing',0,'孤儿块',0,3,3)",
            )
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (901,999,'space_a','content',X'0000003F0000803E',1.0,1)",
            )
            db.execSQL("INSERT INTO event (id,chunkId,canonicalText,confidence) VALUES (902,999,'孤儿事件',0.1)")
            db.execSQL("INSERT INTO event_entity (id,eventId,entityId,role) VALUES (903,999,999,NULL)")
            db.execSQL("INSERT INTO chunk_entity (id,chunkId,entityId) VALUES (904,999,999)")
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (999,'孤儿 儿块')")
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)

        assertEquals("有效 source 必须保留", 1, count(db, "source"))
        assertEquals("有效 document 必须保留", 1, count(db, "document"))
        assertEquals("有效 chunk 必须保留，孤儿 chunk 必须删除", 1, count(db, "chunk"))
        assertEquals("有效 chunk_embedding 保留，孤儿删除", 1, count(db, "chunk_embedding"))
        assertEquals("有效 event 保留，孤儿删除", 1, count(db, "event"))
        assertEquals("有效 entity 保留", 1, count(db, "entity"))
        assertEquals("有效 event_entity 保留，孤儿删除", 1, count(db, "event_entity"))
        assertEquals("有效 chunk_entity 保留，孤儿删除", 1, count(db, "chunk_entity"))
        assertEquals("孤儿 chunk 的 FTS 行必须删除（step1 清理）", 1, count(db, "chunk_fts"))
        db.query("SELECT chunkId FROM chunk_fts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
        // 有效 chunk 的 id/内容未被重建改动
        db.query("SELECT id, content FROM chunk WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("有效块内容", c.getString(1))
        }
        assertForeignKeyCheckClean(db)
        // 未被清理干净时会在这里报出违规表名（提示迁移顺序问题）
        assertEquals(listOf(1, 2, 3, 4, 5), schemaMigrationVersions(db))
    }

    // ---- 4) 链式孤儿清理（缺口 A 回归：doomedChunks 先清子树再重建）----

    /**
     * 缺口 A 回归（MIGRATION_4_5 已加固）：`document.sourceId` 指向已删除 source 时，
     * 其 chunk 子树（chunk / chunk_fts / chunk_embedding / event）必须在重建前整体清理。
     *
     * 旧实现先在 document 存在时删 chunk（该 chunk 被保留），随后才删 orphan document →
     * chunk 变成新孤儿被 INSERT 进带 FK 的 chunk_new → `PRAGMA foreign_key_check` 非空 →
     * 迁移回滚、该类老库无法升级。当前实现以
     * `doomedChunksSql = chunk WHERE documentId NOT IN document OR documentId IN (source 缺失的 document)`
     * 先清子行、再删 chunk、最后删 document，本用例锁定该行为。
     */
    @Test
    fun migrate4To5_orphanDocumentChain_isCleanedBeforeRebuild() {
        val name = "migration-v4v5-orphan-chain.db"
        helper.createDatabase(name, 4).use { db ->
            // 有效链（迁移后必须保留）
            db.execSQL(
                "INSERT INTO source (id,title,uri,mimeType,createdAt,status) " +
                    "VALUES ('src_ok','有效来源','file:///ok.txt','text/plain',1,'active')",
            )
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                    "VALUES ('doc_ok','src_ok','有效文档','sum','txt-utf8-v1',2,'ready',8)",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (1,'doc_ok',0,'有效链块',0,4,4)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (1,'有效 效链 链块')")
            db.execSQL(
                "INSERT INTO embedding_meta (id,providerId,modelId,modelRevision,dimensions,normalization," +
                    "taskTemplate,createdAt,active,accelerator,quantization) " +
                    "VALUES ('space_a','mediapipe-universal','m',NULL,2,'l2','raw-symmetric',3,1,'cpu','f32')",
            )
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (1,1,'space_a','content',X'0000003F0000803E',1.0,4)",
            )
            db.execSQL("INSERT INTO event (id,chunkId,canonicalText,confidence) VALUES (1,1,'有效事件',0.8)")

            // 链式孤儿：document.sourceId 缺失，其 chunk 子树仍存在
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                    "VALUES ('doc_orphan','src_missing','孤儿文档','sum','txt-utf8-v1',2,'ready',0)",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (2,'doc_orphan',0,'链式孤儿块',0,5,5)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (2,'链式 式孤 孤儿')")
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (2,2,'space_a','content',X'0000003F0000803E',1.0,4)",
            )
            db.execSQL("INSERT INTO event (id,chunkId,canonicalText,confidence) VALUES (2,2,'链式事件',0.5)")
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)

        // 迁移成功 + FK 自检为空（旧实现会在自检处抛错回滚）
        assertForeignKeyCheckClean(db)
        // 有效数据完整保留
        assertEquals("有效 source 必须保留", 1, count(db, "source"))
        assertEquals("有效 document 必须保留", 1, count(db, "document"))
        assertEquals("有效 chunk 必须保留", 1, count(db, "chunk"))
        assertEquals("有效 chunk_fts 必须保留", 1, count(db, "chunk_fts"))
        assertEquals("有效 chunk_embedding 必须保留", 1, count(db, "chunk_embedding"))
        assertEquals("有效 event 必须保留", 1, count(db, "event"))
        db.query("SELECT id FROM document").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("doc_ok", c.getString(0))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT id, documentId, content FROM chunk").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("doc_ok", c.getString(1))
            assertEquals("有效链块", c.getString(2))
            assertTrue(!c.moveToNext())
        }
        // 链式孤儿整条子树已清（document + chunk + fts + embedding + event）：仅剩有效事件
        db.query("SELECT id, canonicalText FROM event").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("有效事件", c.getString(1))
            assertTrue(!c.moveToNext())
        }
        assertEquals("有效 embedding_meta 不受影响", 1, count(db, "embedding_meta"))
        // 迁移记录仍写入
        assertEquals(listOf(1, 2, 3, 4, 5), schemaMigrationVersions(db))
    }

    // ---- 5) doomed chunk 的 FTS/向量清理（缺口 B 回归）----

    /**
     * 缺口 B 回归：被删除的 orphan chunk 的 FTS 行必须同步删除。两条 doomed 分支都覆盖：
     * a) chunk.documentId 指向不存在的 document；
     * b) chunk 所属 document 的 source 缺失（链式孤儿）。
     *
     * 旧实现 step1 的 `chunk_fts WHERE chunkId NOT IN chunk` 发生在 chunk 删除之前，
     * 上述两类的 FTS 行会存活 → 迁移后 `chunk_fts` 与 `chunk` 行数不一致
     * （ftsHealthCheck 误报损坏、关键词候选指向已删除 chunk）。当前实现用 doomedChunksSql
     * 在 chunk 删除前先清 chunk_fts/chunk_embedding。
     */
    @Test
    fun migrate4To5_orphanFtsRows_areRemoved() {
        val name = "migration-v4v5-orphan-fts.db"
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO source (id,title,uri,mimeType,createdAt,status) " +
                    "VALUES ('src_ok','有效来源','file:///ok.txt','text/plain',1,'active')",
            )
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                    "VALUES ('doc_ok','src_ok','有效文档','sum','txt-utf8-v1',2,'ready',0)",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (1,'doc_ok',0,'有效块',0,3,3)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (1,'有效 效块')")
            db.execSQL(
                "INSERT INTO embedding_meta (id,providerId,modelId,modelRevision,dimensions,normalization," +
                    "taskTemplate,createdAt,active,accelerator,quantization) " +
                    "VALUES ('space_a','mediapipe-universal','m',NULL,2,'l2','raw-symmetric',3,1,'cpu','f32')",
            )
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (1,1,'space_a','content',X'0000003F0000803E',1.0,4)",
            )

            // a) 孤儿 chunk：document 缺失 + FTS/向量子行
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (900,'doc_missing',0,'孤儿块',0,3,3)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (900,'孤儿 儿块')")
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (900,900,'space_a','content',X'0000003F0000803E',1.0,4)",
            )
            // b) 链式孤儿 chunk：document 的 source 缺失 + FTS/向量子行
            db.execSQL(
                "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                    "VALUES ('doc_orphan','src_missing','孤儿文档','sum','txt-utf8-v1',2,'ready',0)",
            )
            db.execSQL(
                "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                    "VALUES (901,'doc_orphan',0,'链式孤儿块',0,5,5)",
            )
            db.execSQL("INSERT INTO chunk_fts (chunkId,bigramContent) VALUES (901,'链式 式孤 孤儿')")
            db.execSQL(
                "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                    "VALUES (901,901,'space_a','content',X'0000003F0000803E',1.0,4)",
            )
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)

        assertForeignKeyCheckClean(db)
        assertEquals("仅有效 chunk 保留", 1, count(db, "chunk"))
        assertEquals("仅有效 document 保留", 1, count(db, "document"))
        assertEquals("doomed chunk 的 FTS 行必须一并删除（ftsHealthCheck 口径）", 1, count(db, "chunk_fts"))
        assertEquals("doomed chunk 的向量行必须一并删除", 1, count(db, "chunk_embedding"))
        // 仅有效 chunk 的 FTS 行可被查到（无指向已删除 chunk 的候选）
        db.query("SELECT chunkId FROM chunk_fts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertTrue(!c.moveToNext())
        }
    }

    // ---- 工具 ----

    private fun openRoom(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()

    private fun seedV4Full(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO source (id,title,uri,mimeType,createdAt,status) " +
                "VALUES ('src_1','迁移父表','file:///v4.txt','text/plain',100,'active')",
        )
        db.execSQL(
            "INSERT INTO document (id,sourceId,title,checksum,parserId,createdAt,status,sizeBytes) " +
                "VALUES ('doc_1','src_1','迁移文档','abc123','txt-utf8-v1',200,'ready',12345)",
        )
        db.execSQL(
            "INSERT INTO chunk (id,documentId,ordinal,content,charStart,charEnd,tokenEstimate) " +
                "VALUES (1,'doc_1',0,'向量检索迁移内容',0,8,8)",
        )
        db.execSQL(
            "INSERT INTO chunk_fts (chunkId,bigramContent) " +
                "VALUES (1,'向量 量检 检索 索迁 迁移 移内 内容')",
        )
        db.execSQL(
            "INSERT INTO embedding_meta (id,providerId,modelId,modelRevision,dimensions,normalization," +
                "taskTemplate,createdAt,active,accelerator,quantization) VALUES " +
                "('space_a','mediapipe-universal','embeddinggemma-2-text-vision-440m','abcdef123456'," +
                "2,'l2','app-prefix-v1',300,1,'gpu','f32')",
        )
        db.execSQL(
            "INSERT INTO chunk_embedding (id,chunkId,embeddingSpaceId,role,vectorBlob,vectorNorm,createdAt) " +
                "VALUES (1,1,'space_a','content',X'0000003F0000803E',1.0,400)",
        )
        db.execSQL(
            "INSERT INTO event (id,chunkId,canonicalText,confidence) " +
                "VALUES (1,1,'迁移事件',0.9)",
        )
        db.execSQL("INSERT INTO entity (id,normalizedName,entityType) VALUES (1,'智盒','PRODUCT')")
        db.execSQL("INSERT INTO event_entity (id,eventId,entityId,role) VALUES (1,1,1,'subject')")
        db.execSQL("INSERT INTO chunk_entity (id,chunkId,entityId) VALUES (1,1,1)")
        db.execSQL(
            "INSERT INTO ingest_job (id,sourceId,documentId,stage,progress,error,createdAt,updatedAt,attempt) " +
                "VALUES ('job_1','src_1','doc_1','done',100,NULL,500,600,2)",
        )
        db.execSQL(
            "INSERT INTO app_kv (`key`,`value`,`updatedAt`) VALUES ('reembed_last_done','123',700)",
        )
    }

    /** [col] → dflt_value（默认值，含 SQL 引号、未声明为 null）；附加 [col]_notnull 标记。 */
    private fun tableInfo(db: SupportSQLiteDatabase, table: String): Map<String, String?> {
        val out = HashMap<String, String?>()
        db.query("PRAGMA table_info('$table')").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            val dfltCol = c.getColumnIndexOrThrow("dflt_value")
            val notNullCol = c.getColumnIndexOrThrow("notnull")
            while (c.moveToNext()) {
                val column = c.getString(nameCol)
                out[column] = if (c.isNull(dfltCol)) null else c.getString(dfltCol)
                out["${column}_notnull"] = c.getInt(notNullCol).toString()
            }
        }
        return out
    }

    /** 形如 "document.sourceId->source.id:CASCADE" 的 FK 描述集合。 */
    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.query("PRAGMA foreign_key_list('$table')").use { c ->
            val tableCol = c.getColumnIndexOrThrow("table")
            val fromCol = c.getColumnIndexOrThrow("from")
            val toCol = c.getColumnIndexOrThrow("to")
            val onDeleteCol = c.getColumnIndexOrThrow("on_delete")
            while (c.moveToNext()) {
                out += "$table.${c.getString(fromCol)}->${c.getString(tableCol)}." +
                    "${c.getString(toCol)}:${c.getString(onDeleteCol)}"
            }
        }
        return out
    }

    private fun assertForeignKeyCheckClean(db: SupportSQLiteDatabase) {
        val violations = ArrayList<String>()
        db.query("PRAGMA foreign_key_check").use { c ->
            val tableCol = c.getColumnIndexOrThrow("table")
            while (c.moveToNext()) violations += c.getString(tableCol)
        }
        assertTrue("迁移后 foreign_key_check 非空（违规表：$violations）", violations.isEmpty())
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> {
        val out = HashSet<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'").use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    private fun isUniqueIndex(db: SupportSQLiteDatabase, indexName: String): Boolean {
        var unique = false
        db.query("PRAGMA index_list('chunk')").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            val uniqueCol = c.getColumnIndexOrThrow("unique")
            while (c.moveToNext()) if (c.getString(nameCol) == indexName) unique = c.getInt(uniqueCol) == 1
        }
        if (indexName.startsWith("index_chunk_embedding_")) {
            db.query("PRAGMA index_list('chunk_embedding')").use { c ->
                val nameCol = c.getColumnIndexOrThrow("name")
                val uniqueCol = c.getColumnIndexOrThrow("unique")
                while (c.moveToNext()) {
                    if (c.getString(nameCol) == indexName) unique = c.getInt(uniqueCol) == 1
                }
            }
        }
        return unique
    }

    private fun schemaMigrationVersions(db: SupportSQLiteDatabase): List<Int> {
        val versions = ArrayList<Int>()
        db.query("SELECT version FROM schema_migrations ORDER BY version").use { c ->
            while (c.moveToNext()) versions += c.getInt(0)
        }
        return versions
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
}
