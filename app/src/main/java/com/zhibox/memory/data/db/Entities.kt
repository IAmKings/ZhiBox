package com.zhibox.memory.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema（Android PRD §4.2 十表 + W1 修订；v5 引入外键与列默认值）。
 *
 * W1 修订（依据 Phase 0 结论）：
 * - chunk_embedding 增加 role 列（对齐六向量角色协议；MVP 仅写 "content"，schema 预留）
 * - embedding_meta 增加 task_template 策略字段（G1 锁定 app-prefix）
 * - chunk_fts 为 FTS4 表，内容为 bigram 预分词文本（G3 结论），入库与查询两侧统一 bigram 化
 */

@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uri: String,
    val mimeType: String?,
    val createdAt: Long,
    val status: String, // active / deleted
)

@Entity(
    tableName = "document",
    indices = [Index("sourceId")],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val checksum: String, // SHA-256 hex
    val parserId: String,
    val createdAt: Long,
    val status: String, // pending / processing / ready / failed / cancelled（常量见 StatusEnums.kt）
    // schema v2：源文件字节数（来源页展示；存量数据置 0，导入时写入）
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
)

@Entity(
    tableName = "chunk",
    indices = [Index("documentId"), Index(value = ["documentId", "ordinal"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val ordinal: Int,
    val content: String,
    val charStart: Int,
    val charEnd: Int,
    val tokenEstimate: Int,
)

/**
 * FTS4 中文关键词索引（bigram 预分词，G3 选定方案）。
 *
 * 注意（B1-1）：`rowid` 由 SQLite 自增维护，**不与** `ChunkEntity.id` 对齐（v5 起明确修正
 * 旧注释）；业务关联一律通过 `chunkId` 列。FTS 是 chunk.content 的纯派生数据，可随时全量
 * 重建（`rebuildFtsIndex`），写入侧按 chunkId 去重避免重复召回。
 */
@Fts4
@Entity(tableName = "chunk_fts")
data class ChunkFtsEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val chunkId: Long,
    val bigramContent: String,
)

/** 向量空间注册表：任何 (provider, model, revision, dim, 模板策略) 变更 = 新 space，禁止混用 */
@Entity(tableName = "embedding_meta")
data class EmbeddingMetaEntity(
    @PrimaryKey val id: String, // embedding space id，如 mp-universal/egemma2-440m/app-prefix-v1
    val providerId: String,
    val modelId: String,
    val modelRevision: String?,
    val dimensions: Int,
    val normalization: String, // l2
    val taskTemplate: String,  // app-prefix-v1（G1 锁定）
    val createdAt: Long,
    val active: Boolean,
    // schema v2：GPU/NPU 向量跨设备/跨后端不逐位一致（实测 Adreno/Mali 不同簇），
    // 结构化记录生成后端（迁移从 spaceId 后缀回填：-gpu/-npu，缺省 cpu）
    @ColumnInfo(defaultValue = "cpu") val accelerator: String = "cpu",
    // schema v3：向量存储量化格式（f32/int8——存储减半的量化向量演进预留；
    // 不同量化格式的向量数值不同，检索过滤需按此列）
    @ColumnInfo(defaultValue = "f32") val quantization: String = "f32",
)

@Entity(
    tableName = "chunk_embedding",
    indices = [
        Index("chunkId"),
        Index(value = ["chunkId", "embeddingSpaceId", "role"], unique = true),
        // schema v4：检索热路径按空间+角色过滤向量（P1-5），前导列必须命中查询条件
        Index(value = ["embeddingSpaceId", "role"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val embeddingSpaceId: String,
    val role: String,          // 六向量角色协议：content（MVP）/ heading / event_title ...（Phase 2+）
    val vectorBlob: ByteArray, // float32 LE
    val vectorNorm: Double,
    val createdAt: Long,
)

// ── Phase 2 关系层占位（Android PRD §4.2；MVP 建表不写入） ────────────────

@Entity(
    tableName = "event",
    indices = [Index("chunkId")],
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val canonicalText: String,
    val confidence: Double,
)

@Entity(tableName = "entity", indices = [Index(value = ["normalizedName"], unique = true)])
data class EntityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedName: String,
    val entityType: String?,
)

@Entity(
    tableName = "event_entity",
    indices = [Index("eventId"), Index("entityId")],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EntityEntity::class,
            parentColumns = ["id"],
            childColumns = ["entityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventEntityRel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val entityId: Long,
    val role: String?,
)

@Entity(
    tableName = "chunk_entity",
    indices = [Index("chunkId"), Index("entityId")],
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EntityEntity::class,
            parentColumns = ["id"],
            childColumns = ["entityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntityRel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val entityId: Long,
)

// ── 导入任务状态机 ─────────────────────────────────────────────

@Entity(tableName = "ingest_job", indices = [Index("sourceId"), Index("documentId")])
data class IngestJobEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val documentId: String?,
    val stage: String,       // queued / parsing / chunking / embedding / storing / done / failed / cancelled
    val progress: Int,       // 0-100
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    // schema v4：失败重试计数（P1-2 可恢复失败：attempt < MAX 时自动重排队，超过后保持 failed 供 UI 手动重试）
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
)

@Entity(tableName = "schema_migrations")
data class SchemaMigrationEntity(
    @PrimaryKey val version: Int,
    val appliedAt: Long,
)

/** schema v3：通用键值表（跨 WorkManager 生命周期的持久状态；随知识库备份走） */
@Entity(tableName = "app_kv")
data class AppKvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
