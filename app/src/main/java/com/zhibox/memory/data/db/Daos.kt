package com.zhibox.memory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO 层（P2 B1-5 状态参数化）。
 *
 * 约定：
 * - 所有状态/阶段过滤与更新在 @Query 中只使用绑定参数（`:status` / `:stages`），SQL 里不再出现
 *   `'ready'`、`'queued'` 之类的字面量；状态取值由调用方从 StatusEnums.kt 的枚举传入；
 * - 需要“旧调用兼容”的少数入口（androidTest / 备份等暂未迁移的调用方）保留无参重载，
 *   内部转发到参数化 @Query 并显式使用枚举值（见各方法注释），新代码请优先调用参数化版本。
 */

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    /** 来源列表：activeStatus 传 [SourceStatus.ACTIVE.value]（状态字面量已参数化） */
    @Query("SELECT * FROM source WHERE status = :activeStatus ORDER BY createdAt DESC")
    fun observeAll(activeStatus: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    // ---- 删除级联（§9.1-5）：v5 起 document/chunk/chunk_embedding/Phase2 已有外键 CASCADE，
    // 这里保留手工子查询级联作为幂等防御（兼容旧库/迁移未完成的边界）；原文文件由调用方清理 ----

    @Query(
        "DELETE FROM chunk_embedding WHERE chunkId IN " +
            "(SELECT id FROM chunk WHERE documentId IN (SELECT id FROM document WHERE sourceId = :sourceId))"
    )
    suspend fun deleteEmbeddingsBySource(sourceId: String)

    @Query(
        "DELETE FROM chunk_fts WHERE chunkId IN " +
            "(SELECT id FROM chunk WHERE documentId IN (SELECT id FROM document WHERE sourceId = :sourceId))"
    )
    suspend fun deleteFtsBySource(sourceId: String)

    @Query("DELETE FROM chunk WHERE documentId IN (SELECT id FROM document WHERE sourceId = :sourceId)")
    suspend fun deleteChunksBySource(sourceId: String)

    @Query("DELETE FROM ingest_job WHERE sourceId = :sourceId")
    suspend fun deleteJobsBySource(sourceId: String)

    @Query("DELETE FROM document WHERE sourceId = :sourceId")
    suspend fun deleteDocumentsBySource(sourceId: String)

    @Query("DELETE FROM source WHERE id = :sourceId")
    suspend fun deleteById(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<SourceEntity>)

    /** 单事务五表级联；失败整体回滚（导入中途删除不会留半删状态） */
    @Transaction
    suspend fun deleteCascade(sourceId: String) {
        deleteEmbeddingsBySource(sourceId)
        deleteFtsBySource(sourceId)
        deleteChunksBySource(sourceId)
        deleteJobsBySource(sourceId)
        deleteDocumentsBySource(sourceId)
        deleteById(sourceId)
    }

    // ---- 备份/替换全库与批量回填（P0-1：导入需在单事务内先清后写） ----

    @Query("SELECT * FROM source ORDER BY createdAt DESC")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SourceEntity>

    /** 流式导出分页（P1-7：备份导出不再一次性全量读入内存） */
    @Query("SELECT * FROM source ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<SourceEntity>

    @Query("DELETE FROM source")
    suspend fun deleteAll()
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM document ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    /** W4-1 取消导入：pending 与被中断的 processing 文档一并标记。
     *  cancelledStatus 传 [DocStatus.CANCELLED.value]；fromStatuses 传 [DocStatus.ACTIVE_VALUES]。 */
    @Query("UPDATE document SET status = :cancelledStatus WHERE status IN (:fromStatuses)")
    suspend fun cancelAllPending(cancelledStatus: String, fromStatuses: List<String>): Int

    /** 已发布（ready）文档计数：status 传 [DocStatus.READY.value] */
    @Query("SELECT COUNT(*) FROM document WHERE status = :status")
    suspend fun readyCount(status: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(documents: List<DocumentEntity>)

    // ---- 备份/替换全库、批量回填与发布状态（P0-1/P1-1/P1-6） ----

    @Query("SELECT * FROM document ORDER BY createdAt DESC")
    suspend fun all(): List<DocumentEntity>

    @Query("SELECT * FROM document WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<DocumentEntity>

    /** 流式导出分页（P1-7） */
    @Query("SELECT * FROM document ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<DocumentEntity>

    @Query("DELETE FROM document")
    suspend fun deleteAll()

    /** 解析完成后回写真实解析器与源文件字节数（P1-6：此前 parserId 恒 pending、sizeBytes 恒 0） */
    @Query("UPDATE document SET parserId = :parserId, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateParserAndSize(id: String, parserId: String, sizeBytes: Long)

    /** 取消竞态防护：已冻结（默认 cancelled）文档不允许被在途 worker 覆写为 ready/failed（P1-1/P1-2）。
     *  status 传目标 [DocStatus] 的 value，guardStatus 传 [DocStatus.CANCELLED.value]。 */
    @Query("UPDATE document SET status = :status WHERE id = :id AND status != :guardStatus")
    suspend fun updateStatusIfNotCancelled(id: String, status: String, guardStatus: String): Int

    /** 在途取消信号读取（P1-1/P1-2：processDocument 在批次边界检查是否已被取消）。
     *  返回原始 TEXT（读取路径），调用方用 [DocStatus.fromValue] 映射并兼容未知值。 */
    @Query("SELECT status FROM document WHERE id = :id")
    suspend fun status(id: String): String?

    /**
     * B1-6 重复导入去重：按源文件 SHA-256 查已登记文档（按创建时间倒序）。
     * 返回 List：同内容可能被重复导入过多次，由调用方决定取最新 ready 条还是全部跳过。
     */
    @Query("SELECT * FROM document WHERE checksum = :checksum ORDER BY createdAt DESC")
    suspend fun byChecksum(checksum: String): List<DocumentEntity>
}

@Dao
interface ChunkDao {
    @Insert
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Insert
    suspend fun insertFts(rows: List<ChunkFtsEntity>)

    @Query("DELETE FROM chunk WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: String)

    @Query("DELETE FROM chunk_fts WHERE chunkId IN (SELECT id FROM chunk WHERE documentId = :documentId)")
    suspend fun deleteFtsByDocument(documentId: String)

    /** W4-3 索引损坏恢复：全量清空 FTS（内容表独立于 chunk，chunk 数据不受影响） */
    @Query("DELETE FROM chunk_fts")
    suspend fun deleteAllFts()

    /** B2-8 健康检查：FTS 全量业务键（孤儿检测用；调用方自行分批/限流） */
    @Query("SELECT chunkId FROM chunk_fts")
    suspend fun allFtsChunkIds(): List<Long>

    /** B2-8 修复：按 chunkId 批量清理 FTS 孤儿行（调用方分批 ≤400） */
    @Query("DELETE FROM chunk_fts WHERE chunkId IN (:chunkIds)")
    suspend fun deleteFtsByChunkIds(chunkIds: List<Long>)

    @Query("SELECT COUNT(*) FROM chunk")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM chunk_fts")
    suspend fun ftsCount(): Int

    @Query("SELECT COUNT(*) FROM chunk WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: String): Int

    /** 关键词候选：bigram 化后的查询串 MATCH（W1 冒烟/诊断用，无文档状态过滤；
     *  检索主路径请用 [searchFtsOrdered]） */
    @Query(
        "SELECT chunkId FROM chunk_fts WHERE chunk_fts MATCH :matchQuery LIMIT :limit"
    )
    suspend fun searchFts(matchQuery: String, limit: Int): List<Long>

    /**
     * FTS 候选按 rowid 升序（入库顺序）稳定排序，保证 fusion 输入确定性。
     * P1-1：JOIN chunk/document 并只返回已发布文档——失败/取消/导入中的半成品
     * chunk（chunk+FTS 先于向量写入）不得进入检索结果。
     * readyStatus 传 [DocStatus.READY.value]。
     */
    @Query(
        "SELECT chunk_fts.chunkId FROM chunk_fts " +
            "JOIN chunk c ON c.id = chunk_fts.chunkId " +
            "JOIN document d ON d.id = c.documentId " +
            "WHERE chunk_fts MATCH :matchQuery AND d.status = :readyStatus " +
            "ORDER BY chunk_fts.rowid LIMIT :limit"
    )
    suspend fun searchFtsOrdered(matchQuery: String, limit: Int, readyStatus: String): List<Long>

    /**
     * 兼容重载（暂未迁移的旧调用方/诊断工具）：等价于 readyStatus = [DocStatus.READY.value]。
     * 新代码请显式传枚举值，避免“只读 ready”这一契约在调用点隐形。
     */
    suspend fun searchFtsOrdered(matchQuery: String, limit: Int): List<Long> =
        searchFtsOrdered(matchQuery, limit, DocStatus.READY.value)

    @Query("SELECT * FROM chunk WHERE id IN (:ids)")
    suspend fun chunksByIds(ids: List<Long>): List<ChunkEntity>

    /** W4-3 FTS 重建输入（全量） */
    @Query("SELECT * FROM chunk ORDER BY documentId, ordinal")
    suspend fun allChunks(): List<ChunkEntity>

    /** 流式导出分页（P1-7：按 id 稳定排序，避免全量进内存） */
    @Query("SELECT * FROM chunk ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ChunkEntity>

    /** 备份替换全库（P0-1：事务内清表） */
    @Query("DELETE FROM chunk")
    suspend fun deleteAll()

    /** 向量矩阵缓存签名用：全表最大 chunk id（写入新 chunk 后必然变化，P1-5） */
    @Query("SELECT COALESCE(MAX(id), 0) FROM chunk")
    suspend fun maxId(): Long
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: EmbeddingMetaEntity)

    @Query("SELECT * FROM embedding_meta WHERE id = :id")
    suspend fun meta(id: String): EmbeddingMetaEntity?

    @Query("SELECT * FROM embedding_meta ORDER BY createdAt")
    suspend fun allMeta(): List<EmbeddingMetaEntity>

    @Insert
    suspend fun insertAll(embeddings: List<ChunkEmbeddingEntity>)

    @Query("DELETE FROM chunk_embedding WHERE chunkId IN (SELECT id FROM chunk WHERE documentId = :documentId)")
    suspend fun deleteByDocument(documentId: String)

    /** B2-8 修复：按 chunkId 批量清理孤儿向量（chunk 已不存在；调用方分批 ≤400） */
    @Query("DELETE FROM chunk_embedding WHERE chunkId IN (:chunkIds)")
    suspend fun deleteEmbeddingsByChunkIds(chunkIds: List<Long>)

    @Query("SELECT COUNT(*) FROM chunk_embedding")
    suspend fun count(): Int

    /**
     * 精确扫描输入（G4 选型：PRELOADED——一次载入展平 FloatArray 矩阵）。
     *
     * P1-1：JOIN chunk/document 且只取已发布文档（readyStatus 传 [DocStatus.READY.value]）——
     * 半成品文档（维度校验失败/导入中/已取消）的向量不参与检索；JOIN 同时过滤无主孤儿向量。
     * `ORDER BY e.chunkId` 保证矩阵行序稳定（缓存签名与 rank 确定性依赖它）。
     */
    @Query(
        "SELECT e.chunkId AS chunkId, e.vectorBlob AS vectorBlob FROM chunk_embedding e " +
            "JOIN chunk c ON c.id = e.chunkId " +
            "JOIN document d ON d.id = c.documentId " +
            "WHERE e.embeddingSpaceId = :spaceId AND e.role = 'content' AND d.status = :readyStatus " +
            "ORDER BY e.chunkId"
    )
    suspend fun vectorsForSpace(spaceId: String, readyStatus: String): List<VectorRow>

    /** 兼容重载（旧调用/测试）：等价于 readyStatus = [DocStatus.READY.value] */
    suspend fun vectorsForSpace(spaceId: String): List<VectorRow> =
        vectorsForSpace(spaceId, DocStatus.READY.value)

    /**
     * B2-10 流式扫描分页（大空间安全阀）：与 [vectorsForSpace] 同过滤/同排序，
     * 按 chunkId 稳定分页，避免一次性把 100k×768 向量读入内存（~300MB）。
     */
    @Query(
        "SELECT e.chunkId AS chunkId, e.vectorBlob AS vectorBlob FROM chunk_embedding e " +
            "JOIN chunk c ON c.id = e.chunkId " +
            "JOIN document d ON d.id = c.documentId " +
            "WHERE e.embeddingSpaceId = :spaceId AND e.role = 'content' AND d.status = :readyStatus " +
            "ORDER BY e.chunkId LIMIT :limit OFFSET :offset"
    )
    suspend fun vectorsForSpacePage(
        spaceId: String,
        readyStatus: String,
        limit: Int,
        offset: Int,
    ): List<VectorRow>

    /**
     * 检索缓存签名（P1-5）：该空间内**可检索**（readyStatus 传 [DocStatus.READY.value]）向量的
     * 行数与最大行 id。行数变化覆盖新增/删除/文档 ready↔failed 迁移；maxId 覆盖重嵌入/替换写入。
     * 只扫索引不读 BLOB，比全表载入便宜一到两个数量级。
     */
    @Query(
        "SELECT COUNT(*) AS rowCount, COALESCE(MAX(e.id), 0) AS maxVectorId FROM chunk_embedding e " +
            "JOIN chunk c ON c.id = e.chunkId " +
            "JOIN document d ON d.id = c.documentId " +
            "WHERE e.embeddingSpaceId = :spaceId AND e.role = 'content' AND d.status = :readyStatus"
    )
    suspend fun readyVectorSignature(spaceId: String, readyStatus: String): VectorSignature

    /** 兼容重载（旧调用/测试）：等价于 readyStatus = [DocStatus.READY.value] */
    suspend fun readyVectorSignature(spaceId: String): VectorSignature =
        readyVectorSignature(spaceId, DocStatus.READY.value)

    @Query("SELECT COUNT(*) FROM chunk_embedding WHERE embeddingSpaceId = :spaceId")
    suspend fun countBySpace(spaceId: String): Int

    /** W4+-1 自动重嵌入输入：旧空间有向量、且尚未写入目标空间的 chunk（content 供重编码） */
    @Query(
        "SELECT c.id AS id, c.documentId AS documentId, c.ordinal AS ordinal, c.content AS content, " +
            "c.charStart AS charStart, c.charEnd AS charEnd, c.tokenEstimate AS tokenEstimate FROM chunk c " +
            "WHERE EXISTS (SELECT 1 FROM chunk_embedding e WHERE e.chunkId = c.id AND e.embeddingSpaceId = :fromSpaceId) " +
            "AND NOT EXISTS (SELECT 1 FROM chunk_embedding e2 WHERE e2.chunkId = c.id AND e2.embeddingSpaceId = :toSpaceId) " +
            "ORDER BY c.id"
    )
    suspend fun chunksNeedingReembed(fromSpaceId: String, toSpaceId: String): List<ChunkEntity>

    /** 旧空间向量计数（设置页提示"有多少旧数据待同步"） */
    @Query(
        "SELECT COUNT(DISTINCT e.chunkId) FROM chunk_embedding e WHERE e.embeddingSpaceId = :fromSpaceId " +
            "AND NOT EXISTS (SELECT 1 FROM chunk_embedding e2 WHERE e2.chunkId = e.chunkId AND e2.embeddingSpaceId = :toSpaceId)"
    )
    suspend fun countNeedingReembed(fromSpaceId: String, toSpaceId: String): Int

    /** 全部有向量的空间 id */
    @Query("SELECT DISTINCT embeddingSpaceId FROM chunk_embedding")
    suspend fun allSpaceIds(): List<String>

    // ---- 备份/替换全库（P0-1：导入单事务内先清后写；P0-1b：恢复 embedding_meta） ----

    /** 流式导出分页（P1-7：向量 blob 逐页写 zip，内存峰值 = 页大小） */
    @Query("SELECT * FROM chunk_embedding ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ChunkEmbeddingEntity>

    @Query("DELETE FROM chunk_embedding")
    suspend fun deleteAll()

    @Query("DELETE FROM embedding_meta")
    suspend fun deleteAllMeta()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetaAll(metas: List<EmbeddingMetaEntity>)
}

/** 检索服务只读投影（非 Entity）：chunk 与其向量 BLOB */
data class VectorRow(val chunkId: Long, val vectorBlob: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is VectorRow && other.chunkId == chunkId && other.vectorBlob.contentEquals(vectorBlob)

    override fun hashCode(): Int = (chunkId * 31 + vectorBlob.contentHashCode()).toInt()
}

/** 检索缓存签名（P1-5）：可检索向量行数 + 空间内最大向量行 id */
data class VectorSignature(val rowCount: Int, val maxVectorId: Long)

@Dao
interface IngestJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: IngestJobEntity)

    @Query("SELECT * FROM ingest_job ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IngestJobEntity>>

    /**
     * B3-9 任务列表限量查询（UI 契约）：只观察最近 [limit] 条任务，避免 101+ 卡片全量重组；
     * **审查修复**：先按"活动态优先"排序再按 createdAt 倒序，避免早于 50 条的活动任务被截掉。
     *
     * @param activeStages 传 [JobStage.ACTIVE_VALUES]（状态字面量单源；空列表=纯时间序）
     * @param limit 建议 UI ≤50
     * 保留 [observeAll] 供备份/诊断等需要全量的调用方。
     */
    @Query(
        "SELECT * FROM ingest_job " +
            "ORDER BY CASE WHEN stage IN (:activeStages) THEN 0 ELSE 1 END, createdAt DESC " +
            "LIMIT :limit"
    )
    fun observeRecent(activeStages: List<String>, limit: Int): Flow<List<IngestJobEntity>>

    @Query("SELECT * FROM ingest_job WHERE id = :id")
    suspend fun get(id: String): IngestJobEntity?

    /** 无条件进度更新（诊断/回滚用；业务路径请用 [updateStageIfNotCancelled]） */
    @Query("UPDATE ingest_job SET stage = :stage, progress = :progress, updatedAt = :now WHERE id = :id")
    suspend fun updateStage(id: String, stage: String, progress: Int, now: Long)

    /** 置终态失败：failedStage 传 [JobStage.FAILED.value] */
    @Query("UPDATE ingest_job SET stage = :failedStage, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: String, failedStage: String, error: String, now: Long)

    /** 活动任务数：stages 传 [JobStage.ACTIVE_VALUES]（导入互斥/删除守卫/重嵌入让路） */
    @Query("SELECT COUNT(*) FROM ingest_job WHERE stage IN (:stages)")
    suspend fun activeCount(stages: List<String>): Int

    /** 领取下一排队任务：queuedStage 传 [JobStage.QUEUED.value] */
    @Query("SELECT * FROM ingest_job WHERE stage = :queuedStage ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(queuedStage: String): IngestJobEntity?

    /**
     * W4-1 取消导入（诊断/回滚路径）：把 activeStages 全部标记 cancelledStage。
     * 中间态若不一并取消，日后 requeueInterrupted 会将其复活。
     * 注意：P1-2 起 [cancelImport] 只用 [cancelQueued]（只取消排队任务，在途文档由
     * document.status='cancelled' 信号驱动清理），本方法保留供诊断/回滚场景。
     */
    @Query("UPDATE ingest_job SET stage = :cancelledStage, updatedAt = :now WHERE stage IN (:activeStages)")
    suspend fun cancelAllQueued(cancelledStage: String, activeStages: List<String>, now: Long): Int

    /** P1-2 取消语义：只取消尚未被 worker 领取的排队任务。
     *  cancelledStage=[JobStage.CANCELLED.value]，queuedStage=[JobStage.QUEUED.value]。 */
    @Query("UPDATE ingest_job SET stage = :cancelledStage, updatedAt = :now WHERE stage = :queuedStage")
    suspend fun cancelQueued(cancelledStage: String, queuedStage: String, now: Long): Int

    /**
     * 原子认领：仅当仍为 fromStage 时置为 toStage（业务传 QUEUED→PARSING）；
     * 返回 0 表示已被其他执行者领走。
     */
    @Query(
        "UPDATE ingest_job SET stage = :toStage, progress = 5, updatedAt = :now " +
            "WHERE id = :id AND stage = :fromStage"
    )
    suspend fun claim(id: String, fromStage: String, toStage: String, now: Long): Int

    /**
     * 重排队中断任务：进程被杀时处于 fromStages 的 job 永远无法再被 claim（只认 QUEUED）。
     * 唯一 worker 启动时调用——此刻任何非终态 job 必属已死 worker 的遗留，重排队后由本 worker
     * 幂等重做（processDocument 开头清旧数据）。fromStages 传 [JobStage.INTERRUPTED_VALUES]。
     */
    @Query("UPDATE ingest_job SET stage = :toStage, updatedAt = :now WHERE stage IN (:fromStages)")
    suspend fun requeueInterrupted(fromStages: List<String>, toStage: String, now: Long): Int

    // ---- 备份替换、重试与取消竞态（P0-1/P1-2） ----

    @Query("SELECT * FROM ingest_job ORDER BY createdAt DESC")
    suspend fun all(): List<IngestJobEntity>

    @Query("DELETE FROM ingest_job")
    suspend fun deleteAll()

    /** 取消/中断后重排队（P1-2：CancellationException 不算业务失败）。
     *  toStage 传 [JobStage.QUEUED.value]。 */
    @Query("UPDATE ingest_job SET stage = :toStage, progress = 0, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeue(id: String, toStage: String, now: Long)

    /** 记录一次失败并累加 attempt（P1-2：可恢复失败自动重试的上限依据） */
    @Query("UPDATE ingest_job SET attempt = attempt + 1, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun recordFailure(id: String, error: String, now: Long)

    @Query("SELECT attempt FROM ingest_job WHERE id = :id")
    suspend fun attempt(id: String): Int?

    /** 手动/批量重试终态失败的 job（P1-2：此前 failed 无任何恢复入口）。
     *  failedStage=[JobStage.FAILED.value]，toStage=[JobStage.QUEUED.value]；
     *  排除 documentId 为空的异常行——重排后 worker 只能再次标 failed（无文档可处理）。 */
    @Query(
        "UPDATE ingest_job SET stage = :toStage, progress = 0, error = NULL, updatedAt = :now " +
            "WHERE stage = :failedStage AND documentId IS NOT NULL"
    )
    suspend fun retryFailed(failedStage: String, toStage: String, now: Long): Int

    /** 带取消守卫的进度更新：已冻结（guardStage 默认 cancelled）的 job 不会被在途 worker 覆写。
     *  guardStage 传 [JobStage.CANCELLED.value]。 */
    @Query(
        "UPDATE ingest_job SET stage = :stage, progress = :progress, updatedAt = :now " +
            "WHERE id = :id AND stage != :guardStage"
    )
    suspend fun updateStageIfNotCancelled(
        id: String,
        stage: String,
        progress: Int,
        now: Long,
        guardStage: String,
    ): Int

    /** 终态失败任务数：failedStage 传 [JobStage.FAILED.value]（UI“重试失败导入”入口） */
    @Query("SELECT COUNT(*) FROM ingest_job WHERE stage = :failedStage")
    suspend fun failedCount(failedStage: String): Int
}

@Dao
interface AppKvDao {
    @Query("SELECT value FROM app_kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT updatedAt FROM app_kv WHERE `key` = :key")
    suspend fun getUpdatedAt(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppKvEntity)

    @Query("DELETE FROM app_kv WHERE `key` = :key")
    suspend fun remove(key: String)

    // ---- 备份全量读写（P1-7：app_kv 随知识库备份走，此前导出未包含） ----

    @Query("SELECT * FROM app_kv ORDER BY `key`")
    suspend fun all(): List<AppKvEntity>

    @Query("SELECT * FROM app_kv WHERE `key` = :key")
    suspend fun getEntry(key: String): AppKvEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<AppKvEntity>)

    @Query("DELETE FROM app_kv")
    suspend fun deleteAll()
}

@Dao
interface SchemaMigrationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(migration: SchemaMigrationEntity)
}
