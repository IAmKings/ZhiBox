package com.zhibox.memory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkFtsEntity::class,
        EmbeddingMetaEntity::class,
        ChunkEmbeddingEntity::class,
        EventEntity::class,
        EntityEntity::class,
        EventEntityRel::class,
        ChunkEntityRel::class,
        IngestJobEntity::class,
        SchemaMigrationEntity::class,
        AppKvEntity::class,
        // ---- B8（schema v6）：SAG 层 ----
        SagVectorEntity::class,
        EventFtsEntity::class,
        EntityFtsEntity::class,
        ExtractionJobEntity::class,
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        MessageCitationEntity::class,
        QueryTraceEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ingestJobDao(): IngestJobDao
    abstract fun schemaMigrationDao(): SchemaMigrationDao
    abstract fun appKvDao(): AppKvDao

    // ---- B8（schema v6）：SAG 抽取/检索/问答 ----
    abstract fun eventDao(): EventDao
    abstract fun entityDao(): EntityDao
    abstract fun eventEntityDao(): EventEntityDao
    abstract fun sagVectorDao(): SagVectorDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatDao(): ChatDao
    abstract fun queryTraceDao(): QueryTraceDao

    companion object {
        /**
         * schema v1 → v2（首次真实迁移，W4-5）：
         * - embedding_meta 增 accelerator 列：GPU/NPU 向量跨设备/跨后端不逐位一致（实测
         *   Adreno/Mali 不同簇），从 spaceId 后缀结构化回填（-gpu/-npu，缺省 cpu）；
         * - document 增 sizeBytes 列：源文件字节数（存量置 0，导入时写入）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE embedding_meta ADD COLUMN accelerator TEXT NOT NULL DEFAULT 'cpu'"
                )
                db.execSQL(
                    "UPDATE embedding_meta SET accelerator = 'gpu' WHERE id LIKE '%-gpu'"
                )
                db.execSQL(
                    "UPDATE embedding_meta SET accelerator = 'npu' WHERE id LIKE '%-npu'"
                )
                db.execSQL(
                    "ALTER TABLE document ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * schema v2 → v3（第二次真实迁移）：
         * - embedding_meta 增 quantization 列（f32 缺省）；
         * - 新建 app_kv 表（通用键值，重嵌入时间戳等跨 Worker 生命周期状态）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE embedding_meta ADD COLUMN quantization TEXT NOT NULL DEFAULT 'f32'"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS app_kv (" +
                        "`key` TEXT NOT NULL PRIMARY KEY, " +
                        "`value` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * schema v3 → v4：
         * - ingest_job 增 attempt 列（P1-2：可恢复失败自动重排队，超过上限后保持 failed 待手动重试）；
         * - chunk_embedding 增 (embeddingSpaceId, role) 索引（P1-5：检索热路径按空间+角色过滤向量，
         *   原索引前导列是 chunkId，无法服务 WHERE embeddingSpaceId = ?）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ingest_job ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chunk_embedding_embeddingSpaceId_role " +
                        "ON chunk_embedding (embeddingSpaceId, role)"
                )
            }
        }

        /**
         * schema v4 → v5（数据正确性收敛，P2-B1）：
         *
         * 1) **外键（父→子）**：document→source、chunk→document、chunk_embedding→chunk、
         *    event→chunk、event_entity→event/entity、chunk_entity→chunk/entity，均 ON DELETE CASCADE；
         * 2) **列默认值对齐**：document.sizeBytes DEFAULT 0、embedding_meta.accelerator DEFAULT 'cpu'、
         *    quantization DEFAULT 'f32'（新建库与迁移库 PRAGMA 一致）；
         * 3) **孤儿清理前置**：加 FK 前删除无父行（历史异常/崩溃残留），否则建表失败；
         * 4) **迁移顺序**：清理 → 父表重建（document/embedding_meta）→ chunk → chunk_embedding
         *    → event → event_entity/chunk_entity；SQLite 在事务内完成，任一步失败整体回滚；
         * 5) 迁移后 `PRAGMA foreign_key_check` 必须为空，并写入 schema_migrations 记录。
         *
         * 注意：Room 迁移运行在事务内，`PRAGMA foreign_keys` 不能临时关闭；上述顺序保证
         * drop 父表时子表尚未带 FK，因此不会触发级联误删。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 1) 孤儿清理（先子后父；覆盖两类父缺失：document 缺失、document 的 source 缺失）----
                // 待删除 document 集合 = 无父 chunk 的 document 来源 + source 缺失的 document
                val orphanDocsSql =
                    "SELECT id FROM document WHERE sourceId NOT IN (SELECT id FROM source)"
                val doomedChunksSql =
                    "SELECT id FROM chunk WHERE documentId NOT IN (SELECT id FROM document) " +
                        "OR documentId IN ($orphanDocsSql)"
                db.execSQL("DELETE FROM chunk_fts WHERE chunkId NOT IN (SELECT id FROM chunk)")
                db.execSQL("DELETE FROM chunk_embedding WHERE chunkId NOT IN (SELECT id FROM chunk)")
                db.execSQL("DELETE FROM chunk_fts WHERE chunkId IN ($doomedChunksSql)")
                db.execSQL("DELETE FROM chunk_embedding WHERE chunkId IN ($doomedChunksSql)")
                db.execSQL("DELETE FROM chunk WHERE documentId NOT IN (SELECT id FROM document)")
                db.execSQL("DELETE FROM chunk WHERE documentId IN ($orphanDocsSql)")
                db.execSQL("DELETE FROM document WHERE sourceId NOT IN (SELECT id FROM source)")
                db.execSQL("DELETE FROM event WHERE chunkId NOT IN (SELECT id FROM chunk)")
                db.execSQL(
                    "DELETE FROM event_entity WHERE eventId NOT IN (SELECT id FROM event) " +
                        "OR entityId NOT IN (SELECT id FROM entity)"
                )
                db.execSQL(
                    "DELETE FROM chunk_entity WHERE chunkId NOT IN (SELECT id FROM chunk) " +
                        "OR entityId NOT IN (SELECT id FROM entity)"
                )

                // ---- 2) 无子表依赖的父表：document（FK source + sizeBytes 默认值）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_new` (" +
                        "`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`checksum` TEXT NOT NULL, `parserId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `source`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `document_new` (`id`,`sourceId`,`title`,`checksum`,`parserId`," +
                        "`createdAt`,`status`,`sizeBytes`) " +
                        "SELECT `id`,`sourceId`,`title`,`checksum`,`parserId`,`createdAt`,`status`," +
                        "COALESCE(`sizeBytes`,0) FROM `document`"
                )
                db.execSQL("DROP TABLE `document`")
                db.execSQL("ALTER TABLE `document_new` RENAME TO `document`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_sourceId` ON `document` (`sourceId`)"
                )

                // ---- 3) embedding_meta（列默认值）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `embedding_meta_new` (" +
                        "`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, " +
                        "`modelRevision` TEXT, `dimensions` INTEGER NOT NULL, `normalization` TEXT NOT NULL, " +
                        "`taskTemplate` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "`accelerator` TEXT NOT NULL DEFAULT 'cpu', " +
                        "`quantization` TEXT NOT NULL DEFAULT 'f32', PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `embedding_meta_new` (`id`,`providerId`,`modelId`,`modelRevision`," +
                        "`dimensions`,`normalization`,`taskTemplate`,`createdAt`,`active`," +
                        "`accelerator`,`quantization`) " +
                        "SELECT `id`,`providerId`,`modelId`,`modelRevision`,`dimensions`,`normalization`," +
                        "`taskTemplate`,`createdAt`,`active`,`accelerator`,`quantization` FROM `embedding_meta`"
                )
                db.execSQL("DROP TABLE `embedding_meta`")
                db.execSQL("ALTER TABLE `embedding_meta_new` RENAME TO `embedding_meta`")

                // ---- 4) chunk（FK → document）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chunk_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, `content` TEXT NOT NULL, `charStart` INTEGER NOT NULL, " +
                        "`charEnd` INTEGER NOT NULL, `tokenEstimate` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`documentId`) REFERENCES `document`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `chunk_new` (`id`,`documentId`,`ordinal`,`content`,`charStart`," +
                        "`charEnd`,`tokenEstimate`) SELECT `id`,`documentId`,`ordinal`,`content`," +
                        "`charStart`,`charEnd`,`tokenEstimate` FROM `chunk`"
                )
                db.execSQL("DROP TABLE `chunk`")
                db.execSQL("ALTER TABLE `chunk_new` RENAME TO `chunk`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_documentId` ON `chunk` (`documentId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_chunk_documentId_ordinal` " +
                        "ON `chunk` (`documentId`, `ordinal`)"
                )

                // ---- 5) chunk_embedding（FK → chunk）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chunk_embedding_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chunkId` INTEGER NOT NULL, " +
                        "`embeddingSpaceId` TEXT NOT NULL, `role` TEXT NOT NULL, `vectorBlob` BLOB NOT NULL, " +
                        "`vectorNorm` REAL NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`chunkId`) REFERENCES `chunk`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `chunk_embedding_new` (`id`,`chunkId`,`embeddingSpaceId`,`role`," +
                        "`vectorBlob`,`vectorNorm`,`createdAt`) SELECT `id`,`chunkId`,`embeddingSpaceId`," +
                        "`role`,`vectorBlob`,`vectorNorm`,`createdAt` FROM `chunk_embedding`"
                )
                db.execSQL("DROP TABLE `chunk_embedding`")
                db.execSQL("ALTER TABLE `chunk_embedding_new` RENAME TO `chunk_embedding`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_embedding_chunkId` " +
                        "ON `chunk_embedding` (`chunkId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_chunk_embedding_chunkId_embeddingSpaceId_role` " +
                        "ON `chunk_embedding` (`chunkId`, `embeddingSpaceId`, `role`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_embedding_embeddingSpaceId_role` " +
                        "ON `chunk_embedding` (`embeddingSpaceId`, `role`)"
                )

                // ---- 6) event（FK → chunk）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chunkId` INTEGER NOT NULL, " +
                        "`canonicalText` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                        "FOREIGN KEY(`chunkId`) REFERENCES `chunk`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `event_new` (`id`,`chunkId`,`canonicalText`,`confidence`) " +
                        "SELECT `id`,`chunkId`,`canonicalText`,`confidence` FROM `event`"
                )
                db.execSQL("DROP TABLE `event`")
                db.execSQL("ALTER TABLE `event_new` RENAME TO `event`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_chunkId` ON `event` (`chunkId`)"
                )

                // ---- 7) event_entity（FK → event/entity）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_entity_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, " +
                        "`entityId` INTEGER NOT NULL, `role` TEXT, " +
                        "FOREIGN KEY(`eventId`) REFERENCES `event`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`entityId`) REFERENCES `entity`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `event_entity_new` (`id`,`eventId`,`entityId`,`role`) " +
                        "SELECT `id`,`eventId`,`entityId`,`role` FROM `event_entity`"
                )
                db.execSQL("DROP TABLE `event_entity`")
                db.execSQL("ALTER TABLE `event_entity_new` RENAME TO `event_entity`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_entity_eventId` " +
                        "ON `event_entity` (`eventId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_entity_entityId` " +
                        "ON `event_entity` (`entityId`)"
                )

                // ---- 8) chunk_entity（FK → chunk/entity）----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chunk_entity_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chunkId` INTEGER NOT NULL, " +
                        "`entityId` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`chunkId`) REFERENCES `chunk`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`entityId`) REFERENCES `entity`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `chunk_entity_new` (`id`,`chunkId`,`entityId`) " +
                        "SELECT `id`,`chunkId`,`entityId` FROM `chunk_entity`"
                )
                db.execSQL("DROP TABLE `chunk_entity`")
                db.execSQL("ALTER TABLE `chunk_entity_new` RENAME TO `chunk_entity`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_entity_chunkId` " +
                        "ON `chunk_entity` (`chunkId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_entity_entityId` " +
                        "ON `chunk_entity` (`entityId`)"
                )

                // ---- 9) 迁移记录（schema_migrations 首次真正写入，P2-B1-3）----
                val now = System.currentTimeMillis()
                for (version in 1..5) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO schema_migrations (`version`, `appliedAt`) " +
                            "VALUES ($version, $now)"
                    )
                }

                // ---- 10) 迁移后外键完整性自检：任何违规都让事务回滚 ----
                val violations = db.query("PRAGMA foreign_key_check")
                try {
                    check(!violations.moveToFirst()) {
                        "schema v4→v5 迁移后存在外键违规，已回滚（请检查孤儿数据/迁移逻辑）"
                    }
                } finally {
                    violations.close()
                }
            }
        }

        /**
         * schema v5 → v6（B8：SAG 数据层铺路，**纯 additive**）：
         *
         * 1) `event` 扩展为完整抽取结果（title/summary/content/language/status/版本戳/active/
         *    supersededBy/createdAt），并加唯一索引 `(chunkId, extractionVersion)`——相同 chunk
         *    的同一抽取版本只允许一行，重抽取靠新 UUID 产生新行再由 supersede 事务收口；
         * 2) `entity` 扩展 name/description/createdAt；`event_entity` 扩展 weight/description；
         * 3) 新表：sag_vector（event/entity 语义向量）、event_fts/entity_fts（FTS4，可从行重建）、
         *    extraction_job（每 chunk 一条抽取任务）、chat_conversation/chat_message/
         *    message_citation（P1 问答）、query_trace（本地检索诊断）；
         * 4) 迁移**不做网络/重嵌入**：新表初始为空，既有检索/导入行为不变；旧库 event/entity
         *    预留行保留（新列取默认值，`active=1`/`status='active'` 视为尚未抽取的旧行）；
         * 5) 唯一索引前先按 `(chunkId, extractionVersion)` 去重（保留 id 最大=最新行）：v5 从未写入
         *    event 表，此步只对人为/历史异常数据生效，保证迁移不会因唯一约束失败；
         * 6) 迁移后 `PRAGMA foreign_key_check` 必须为空，并写入 schema_migrations.version=6。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 1) event：扩展列（全部 NOT NULL 列带默认值，旧行安全回填） ----
                db.execSQL("ALTER TABLE event ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN content TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN language TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
                db.execSQL("ALTER TABLE event ADD COLUMN extractorVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN extractionVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN modelId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN promptVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE event ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE event ADD COLUMN supersededBy INTEGER")
                db.execSQL("ALTER TABLE event ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                // 唯一索引前处理重复行（见方法注释第 5 点；v5 无 event 写入，正常库为空操作）：
                // 不删除旧行（避免 FK 级联误删 event_entity / 破坏"additive 不丢数据"约定），
                // 而是给非最新重复行分配确定性的 legacy 版本戳，使唯一索引成立且旧行仍可追溯。
                db.execSQL(
                    "UPDATE event SET extractionVersion = 'legacy-v5-' || id " +
                        "WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM event GROUP BY chunkId, extractionVersion)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_chunkId_extractionVersion` " +
                        "ON `event` (`chunkId`, `extractionVersion`)"
                )

                // ---- 2) entity / event_entity 扩展列 ----
                db.execSQL("ALTER TABLE entity ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entity ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entity ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE event_entity ADD COLUMN weight REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE event_entity ADD COLUMN description TEXT")

                // ---- 3) sag_vector（owner 多态，无 FK；按 owner/空间+角色建索引） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sag_vector` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ownerType` TEXT NOT NULL, `ownerId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, `spaceId` TEXT NOT NULL, " +
                        "`dimensions` INTEGER NOT NULL, `vectorBlob` BLOB NOT NULL, " +
                        "`vectorNorm` REAL NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sag_vector_ownerType_ownerId` " +
                        "ON `sag_vector` (`ownerType`, `ownerId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sag_vector_spaceId_role_ownerType` " +
                        "ON `sag_vector` (`spaceId`, `role`, `ownerType`)"
                )

                // ---- 4) event_fts / entity_fts（FTS4；与 chunk_fts 同风格，可从行全量重建） ----
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `event_fts` USING FTS4(" +
                        "`eventId` INTEGER NOT NULL, `text` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `entity_fts` USING FTS4(" +
                        "`entityId` INTEGER NOT NULL, `text` TEXT NOT NULL)"
                )

                // ---- 5) extraction_job（每 chunk 一条；document/chunk 级联清理） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `extraction_job` (" +
                        "`id` TEXT NOT NULL, `documentId` TEXT NOT NULL, `chunkId` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `attempt` INTEGER NOT NULL DEFAULT 0, " +
                        "`errorCode` TEXT, `errorDetail` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`documentId`) REFERENCES `document`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`chunkId`) REFERENCES `chunk`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_extraction_job_documentId` " +
                        "ON `extraction_job` (`documentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_extraction_job_chunkId` " +
                        "ON `extraction_job` (`chunkId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_extraction_job_status` " +
                        "ON `extraction_job` (`status`)"
                )

                // ---- 6) chat_conversation / chat_message / message_citation（P1 问答；不随备份导出） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_conversation` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `sourceScope` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_message` (" +
                        "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `model` TEXT, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `chat_conversation`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_message_conversationId` " +
                        "ON `chat_message` (`conversationId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_citation` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, `chunkId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`messageId`) REFERENCES `chat_message`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_citation_messageId` " +
                        "ON `message_citation` (`messageId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_citation_chunkId` " +
                        "ON `message_citation` (`chunkId`)"
                )

                // ---- 7) query_trace（本地诊断；仅保留最近 N 条，不含正文） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `query_trace` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`queryHash` TEXT NOT NULL, `mode` TEXT NOT NULL, `timingJson` TEXT NOT NULL, " +
                        "`degradedReason` TEXT, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_query_trace_queryHash` " +
                        "ON `query_trace` (`queryHash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_query_trace_createdAt` " +
                        "ON `query_trace` (`createdAt`)"
                )

                // ---- 8) 迁移记录 ----
                db.execSQL(
                    "INSERT OR IGNORE INTO schema_migrations (`version`, `appliedAt`) " +
                        "VALUES (6, ${System.currentTimeMillis()})"
                )

                // ---- 9) 迁移后外键完整性自检：任何违规都让事务回滚 ----
                val violations = db.query("PRAGMA foreign_key_check")
                try {
                    check(!violations.moveToFirst()) {
                        "schema v5→v6 迁移后存在外键违规，已回滚（请检查孤儿数据/迁移逻辑）"
                    }
                } finally {
                    violations.close()
                }
            }
        }

        /**
         * schema v6 → v7（B9：抽取流水线约束收口，**纯 ALTER/CREATE + 去重**）：
         *
         * 1) `event` 增加 `(chunkId, active)` 索引：检索/展示"当前有效事件"按 chunk 过滤；
         * 2) `event_entity` 增加唯一 `(eventId, entityId)`：同一事件对同一实体只保留一条关联；
         * 3) `extraction_job.chunkId` 由普通索引改为**唯一**（每 chunk 一条抽取任务，重抽取复用行）；
         * 4) `sag_vector` 增加唯一 `(ownerType, ownerId, role, spaceId)`：重抽取 upsert 幂等；
         * 5) **先清理历史重复再建唯一索引**（v6 设计尚无唯一约束，手工/异常数据可能重复）：
         *    event_entity 保留 MIN(id)、extraction_job 每 chunk 保留 MIN(rowid)、
         *    sag_vector 按四元组保留 MIN(id)；
         * 6) 不做网络/重嵌入，不修改既有行内容（除删除重复行）；迁移后 foreign_key_check 自检。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 1) 去重（保留最小 id/rowid；先清子表重复，再建唯一索引） ----
                db.execSQL(
                    "DELETE FROM event_entity WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM event_entity GROUP BY eventId, entityId)"
                )
                db.execSQL(
                    "DELETE FROM extraction_job WHERE rowid NOT IN " +
                        "(SELECT MIN(rowid) FROM extraction_job GROUP BY chunkId)"
                )
                db.execSQL(
                    "DELETE FROM sag_vector WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM sag_vector " +
                        "GROUP BY ownerType, ownerId, role, spaceId)"
                )

                // ---- 2) event：当前有效事件热路径索引 ----
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_chunkId_active` " +
                        "ON `event` (`chunkId`, `active`)"
                )

                // ---- 3) event_entity：关系唯一约束 ----
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_entity_eventId_entityId` " +
                        "ON `event_entity` (`eventId`, `entityId`)"
                )

                // ---- 4) extraction_job：chunkId 索引升级为唯一（先删旧普通索引同名再建） ----
                db.execSQL("DROP INDEX IF EXISTS `index_extraction_job_chunkId`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_extraction_job_chunkId` " +
                        "ON `extraction_job` (`chunkId`)"
                )

                // ---- 5) sag_vector：owner 语义角色在空间内唯一 ----
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_sag_vector_ownerType_ownerId_role_spaceId` " +
                        "ON `sag_vector` (`ownerType`, `ownerId`, `role`, `spaceId`)"
                )

                // ---- 6) 迁移记录 + 外键完整性自检 ----
                db.execSQL(
                    "INSERT OR IGNORE INTO schema_migrations (`version`, `appliedAt`) " +
                        "VALUES (7, ${System.currentTimeMillis()})"
                )
                val violations = db.query("PRAGMA foreign_key_check")
                try {
                    check(!violations.moveToFirst()) {
                        "schema v6→v7 迁移后存在外键违规，已回滚（请检查重复数据清理/FK 级联）"
                    }
                } finally {
                    violations.close()
                }
            }
        }

        /**
         * schema v7 → v8（B14.2）：`source` 增加 `originUrl`（来源 URL provenance，可空 TEXT）。
         *
         * 只加列、不回填、不联网：旧库所有行的 originUrl=NULL（表示"无来源 URL 信息"），
         * 本地文件导入行为不变；新导入/备份恢复可写入 HTTPS URL。加列不涉及索引/外键，
         * 不重写既有行，迁移后仍做外键自检 + 记录 schema_migrations=8。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source ADD COLUMN originUrl TEXT")
                db.execSQL(
                    "INSERT OR IGNORE INTO schema_migrations (`version`, `appliedAt`) " +
                        "VALUES (8, ${System.currentTimeMillis()})",
                )
                db.query("PRAGMA foreign_key_check").use { violations ->
                    check(!violations.moveToFirst()) { "schema v7→v8 迁移后存在外键违规" }
                }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhibox.sqlite3",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8,
                    )
                    .build().also { instance = it }
            }
    }
}
