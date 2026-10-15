package com.zhibox.memory.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema v1（Android PRD §4.2 十表 + W1 修订）。
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
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val checksum: String, // SHA-256 hex
    val parserId: String,
    val createdAt: Long,
    val status: String, // processing / ready / failed
)

@Entity(
    tableName = "chunk",
    indices = [Index("documentId"), Index(value = ["documentId", "ordinal"], unique = true)],
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

/** FTS4 中文关键词索引（bigram 预分词，G3 选定方案；rowid 与 ChunkEntity.id 对齐由入库逻辑维护） */
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
)

@Entity(
    tableName = "chunk_embedding",
    indices = [
        Index("chunkId"),
        Index(value = ["chunkId", "embeddingSpaceId", "role"], unique = true),
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

@Entity(tableName = "event", indices = [Index("chunkId")])
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

@Entity(tableName = "event_entity", indices = [Index("eventId"), Index("entityId")])
data class EventEntityRel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val entityId: Long,
    val role: String?,
)

@Entity(tableName = "chunk_entity", indices = [Index("chunkId"), Index("entityId")])
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
    val stage: String,       // queued / parsing / chunking / embedding / storing / done / failed
    val progress: Int,       // 0-100
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "schema_migrations")
data class SchemaMigrationEntity(
    @PrimaryKey val version: Int,
    val appliedAt: Long,
)
