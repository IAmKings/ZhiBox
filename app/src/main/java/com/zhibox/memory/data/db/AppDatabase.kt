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
    ],
    version = 5,
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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhibox.sqlite3",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
