package com.zhibox.memory.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema（Android PRD §4.2 十表 + W1 修订；v5 引入外键与列默认值；v6 引入 SAG 层）。
 *
 * W1 修订（依据 Phase 0 结论）：
 * - chunk_embedding 增加 role 列（对齐六向量角色协议；MVP 仅写 "content"，schema 预留）
 * - embedding_meta 增加 task_template 策略字段（G1 锁定 app-prefix）
 * - chunk_fts 为 FTS4 表，内容为 bigram 预分词文本（G3 结论），入库与查询两侧统一 bigram 化
 *
 * **B8（schema v6，additive）**：SAG（Structured Augmented Generation）数据层铺路，不改 v5
 * 既有表语义与既有列（只新增列/表）：
 * - `event` 扩展为完整抽取结果（title/summary/content/language/status/版本戳/active/supersededBy）；
 *   `entity` 扩展 name/description；`event_entity` 扩展 weight/description；
 * - 新增 `sag_vector`（event/entity 语义向量，与 chunk_embedding 分离）、`event_fts`/`entity_fts`
 *   （FTS4，与 chunk_fts 同风格可重建）、`extraction_job`（每 chunk 一条抽取任务）、
 *   `chat_conversation`/`chat_message`/`message_citation`（P1 问答）、`query_trace`（本地检索诊断）。
 * 迁移实现见 [AppDatabase.MIGRATION_5_6]；旧库升级不触发网络/重嵌入（新表初始为空）。
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

/**
 * B8（v6）：SAG 抽取的 event 行——**1 chunk 1 event**（唯一索引 `(chunkId, extractionVersion)`）。
 *
 * - `canonicalText`/`confidence` 为 v5 预留列（保留兼容；B9 起 canonicalText 写 title+summary
 *   归一化文本，confidence 写抽取器/模型的置信度）；
 * - `extractorVersion`：抽取器实现版本（如 `egemma-extract-v1`，与 prompt 规则绑定）；
 * - `extractionVersion`：**一次抽取的版本 UUID**（重抽取产生新 UUID，旧行由 `active=false` +
 *   `supersededBy` 指向新行；相同 chunk + 同一 UUID 只允许一条，防重复抽取写重）；
 * - `status` 取值见 [ExtractionJobStatus]/`EventStatus`（active/superseded/discarded）；
 * - `modelId`/`promptVersion`：生成该结果的 LLM 模型与提示词版本，用于质量追溯/重抽取决策。
 */
@Entity(
    tableName = "event",
    indices = [
        Index("chunkId"),
        Index(value = ["chunkId", "extractionVersion"], unique = true),
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
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Long,
    val canonicalText: String,
    val confidence: Double,
    // ---- B8/schema v6 新增列（均可缺省：旧库 ALTER 回填默认值，语义=尚未抽取/旧版） ----
    @ColumnInfo(defaultValue = "") val title: String = "",
    @ColumnInfo(defaultValue = "") val summary: String = "",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "") val language: String = "",
    @ColumnInfo(defaultValue = "active") val status: String = "active",
    @ColumnInfo(defaultValue = "") val extractorVersion: String = "",
    @ColumnInfo(defaultValue = "") val extractionVersion: String = "",
    @ColumnInfo(defaultValue = "") val modelId: String = "",
    @ColumnInfo(defaultValue = "") val promptVersion: String = "",
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    val supersededBy: Long? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

/**
 * B8（v6）：SAG 抽取的 entity 行——按 `normalizedName`（小写/归一化）唯一，跨文档复用。
 * `entityType` 为 [com.zhibox.memory.core.extract.EntityType] 的 wire 名（可空兼容 v5 预留行）。
 */
@Entity(tableName = "entity", indices = [Index(value = ["normalizedName"], unique = true)])
data class EntityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedName: String,
    val entityType: String?,
    @ColumnInfo(defaultValue = "") val name: String = "",
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
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
    // B8（v6）：事件-实体关联强度（1.0=核心/必然，0.5=提及）与关系描述（LLM 可选输出）
    @ColumnInfo(defaultValue = "1.0") val weight: Double = 1.0,
    val description: String? = null,
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

// ───────────────────────── B8（schema v6）：SAG 层新增表 ─────────────────────────

/**
 * B8（v6）：event/entity 的语义向量（SAG 检索用），与 chunk_embedding 分离。
 *
 * ownerType ∈ [SagOwnerType]（EVENT/ENTITY）；owner 为多态引用（event.id / entity.id），
 * 父行删除时由业务侧按 owner 显式清理（SQLite 不支持多态 FK）。`(spaceId, role, ownerType)`
 * 服务"按空间+角色批量取向量"；`(ownerType, ownerId)` 服务"按 owner 取向量/删向量"。
 */
@Entity(
    tableName = "sag_vector",
    indices = [
        Index(value = ["ownerType", "ownerId"]),
        Index(value = ["spaceId", "role", "ownerType"]),
    ],
)
data class SagVectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: String,   // EVENT / ENTITY（SagOwnerType.value）
    val ownerId: Long,
    val role: String,        // 语义角色：event_title / event_summary / entity_description ...
    val spaceId: String,
    val dimensions: Int,
    val vectorBlob: ByteArray, // float32 LE（与 chunk_embedding 同编码）
    val vectorNorm: Double,
    val createdAt: Long,
)

/** B8（v6）：event 文本的 FTS4 索引（bigram 化后在 B9 写入；可从 event 行全量重建） */
@Fts4
@Entity(tableName = "event_fts")
data class EventFtsEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val eventId: Long,
    val text: String,
)

/** B8（v6）：entity 文本的 FTS4 索引（name+description bigram 化；可从 entity 行全量重建） */
@Fts4
@Entity(tableName = "entity_fts")
data class EntityFtsEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val entityId: Long,
    val text: String,
)

/**
 * B8（v6）：每 chunk 一条的抽取任务（B9 由导入联动创建；不阻塞文档 ready）。
 * 状态见 [ExtractionJobStatus]；`errorCode/errorDetail` 可读失败信息（不含 API key/正文）。
 */
@Entity(
    tableName = "extraction_job",
    indices = [Index("documentId"), Index("chunkId"), Index("status")],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExtractionJobEntity(
    @PrimaryKey val id: String, // UUID（= 该 chunk 的 extractionVersion）
    val documentId: String,
    val chunkId: Long,
    val status: String, // queued / running / done / failed / cancelled / not_indexed
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    val errorCode: String?,
    val errorDetail: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** B8（v6）：问答会话（P1；备份 P0 默认不导出，删除会话不影响知识库） */
@Entity(tableName = "chat_conversation")
data class ChatConversationEntity(
    @PrimaryKey val id: String, // UUID
    val title: String,
    val sourceScope: String?, // null=全部来源；否则来源 id / 过滤表达式（B12 语义）
    val createdAt: Long,
    val updatedAt: Long,
)

/** B8（v6）：问答消息；role ∈ [ChatRole]（user/assistant/system） */
@Entity(
    tableName = "chat_message",
    indices = [Index("conversationId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String, // UUID
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val model: String?, // 生成该消息的模型；user/system 消息为 null
)

/** B8（v6）：消息引用（可点击回原文）；rank 为模型引用顺序（0 起） */
@Entity(
    tableName = "message_citation",
    indices = [Index("messageId"), Index("chunkId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageCitationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val chunkId: Long,
    val rank: Int,
)

/**
 * B8（v6）：本地检索/问答诊断轨迹（P0 必需；不含正文，仅 queryHash/耗时/降级原因）。
 * 仅保留最近 N 条（DAO 负责裁剪），可清空/脱敏导出；不进入备份。
 */
@Entity(
    tableName = "query_trace",
    indices = [Index("queryHash"), Index("createdAt")],
)
data class QueryTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryHash: String, // 查询文本的 SHA-256（不落正文）
    val mode: String,      // keyword / vector / hybrid（B11 语义）
    val timingJson: String, // 各阶段耗时 JSON（{kwMs:.., vecMs:.., rrfMs:..}）
    val degradedReason: String?,
    val createdAt: Long,
)
