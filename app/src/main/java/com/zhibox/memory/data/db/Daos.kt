package com.zhibox.memory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    /** B9：某文档的全部 chunk（按 ordinal，抽取/展示按原文顺序） */
    @Query("SELECT * FROM chunk WHERE documentId = :documentId ORDER BY ordinal")
    suspend fun byDocument(documentId: String): List<ChunkEntity>

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

// ═══════════════════ B8（schema v6）：SAG 抽取/检索/问答 DAO ═══════════════════
//
// 约定同文件头：状态过滤一律参数化（调用方传 StatusEnums.kt 的枚举值），SQL 不出现状态字面量。
// 这些 DAO 只提供数据访问原语；事务边界（如"旧 event 置 superseded + 新 event 写入"）由
// core 层用 db.withTransaction 组合，避免 DAO 之间互相调用导致 Room 生成代码膨胀。

/** B8：event 读写 + event_fts（FTS4，bigram 化文本；可从 event 行全量重建） */
@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    /** 同 chunk 全部 event（含被替代的历史行；排序新→旧） */
    @Query("SELECT * FROM event WHERE chunkId = :chunkId ORDER BY id DESC")
    suspend fun byChunk(chunkId: Long): List<EventEntity>

    @Query("SELECT * FROM event WHERE chunkId IN (:chunkIds) ORDER BY chunkId, id")
    suspend fun byChunkIds(chunkIds: List<Long>): List<EventEntity>

    /** 当前有效 event（每 chunk 至多一条的不变式由 active/supersede 维护） */
    @Query("SELECT * FROM event WHERE chunkId = :chunkId AND active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun activeByChunk(chunkId: Long): EventEntity?

    @Query(
        "SELECT * FROM event WHERE active = 1 AND chunkId IN (:chunkIds) " +
            "ORDER BY chunkId, id"
    )
    suspend fun activeByChunkIds(chunkIds: List<Long>): List<EventEntity>

    /** 幂等写入键：同 chunk + 同 extractionVersion 只允许一行（唯一索引保护） */
    @Query(
        "SELECT * FROM event WHERE chunkId = :chunkId AND extractionVersion = :extractionVersion LIMIT 1"
    )
    suspend fun byChunkAndExtractionVersion(chunkId: Long, extractionVersion: String): EventEntity?

    /**
     * 替代同 chunk 的旧 active event：notId 之外全部置 active=0、status=superseded、
     * supersededBy=newEventId。调用方应在同一事务内先写新行再调用（B9）。
     */
    @Query(
        "UPDATE event SET active = 0, status = :supersededStatus, supersededBy = :newEventId " +
            "WHERE chunkId = :chunkId AND id != :newEventId AND active = 1"
    )
    suspend fun supersedeOthers(
        chunkId: Long,
        newEventId: Long,
        supersededStatus: String,
    ): Int

    /** 丢弃/清除某 chunk 的当前 event（B9 重抽取失败回滚用） */
    @Query("UPDATE event SET active = 0, status = :discardedStatus WHERE chunkId = :chunkId AND active = 1")
    suspend fun discardActive(chunkId: Long, discardedStatus: String): Int

    @Query("DELETE FROM event WHERE chunkId = :chunkId")
    suspend fun deleteByChunk(chunkId: Long)

    @Query("DELETE FROM event WHERE chunkId IN (:chunkIds)")
    suspend fun deleteByChunkIds(chunkIds: List<Long>)

    @Query("SELECT * FROM event ORDER BY id")
    suspend fun all(): List<EventEntity>

    /** 备份 v3 分页导出（避免一次性加载全量 event） */
    @Query("SELECT * FROM event ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM event")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM event WHERE active = 1")
    suspend fun countActive(): Int

    // ---- event_fts：写入/重建/检索（bigram 文本由 core/search 生成） ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: EventFtsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFtsAll(rows: List<EventFtsEntity>): List<Long>

    @Query("DELETE FROM event_fts WHERE eventId = :eventId")
    suspend fun deleteFtsByEvent(eventId: Long)

    @Query("DELETE FROM event_fts WHERE eventId IN (:eventIds)")
    suspend fun deleteFtsByEvents(eventIds: List<Long>)

    @Query("DELETE FROM event_fts")
    suspend fun clearFts()

    @Query("SELECT COUNT(*) FROM event_fts")
    suspend fun countFts(): Int

    /** B9 删除清理：移除没有 event 的 FTS 孤儿行（FTS4 无 FK） */
    @Query("DELETE FROM event_fts WHERE eventId NOT IN (SELECT id FROM event)")
    suspend fun deleteFtsOrphans(): Int

    /** 关键词召回：match 为 FTS4 MATCH 表达式（bigram OR）；返回 eventId 按 rowid 顺序 */
    @Query("SELECT eventId FROM event_fts WHERE event_fts MATCH :match ORDER BY rowid LIMIT :limit")
    suspend fun searchFts(match: String, limit: Int): List<Long>
}

/** B8：entity 读写 + entity_fts（按 normalizedName 跨文档去重复用） */
@Dao
interface EntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntityEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EntityEntity>)

    /**
     * B9：按 normalizedName 唯一索引"插入或忽略"（返回 -1 表示已存在）。
     * 抽取流水线先 insertIgnore 再按 normalizedName 回查，避免并发/重复实体的
     * REPLACE（会级联删除旧 entity 的 event_entity/chunk_entity 关联）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: EntityEntity): Long

    /** B9：更新已存在 entity（description/entityType 合并时用；不改变 id/关联） */
    @Update
    suspend fun update(entity: EntityEntity)

    @Query("SELECT * FROM entity WHERE id = :id")
    suspend fun byId(id: Long): EntityEntity?

    @Query("SELECT * FROM entity WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun byNormalizedName(normalizedName: String): EntityEntity?

    @Query("SELECT * FROM entity WHERE normalizedName IN (:normalizedNames)")
    suspend fun byNormalizedNames(normalizedNames: List<String>): List<EntityEntity>

    @Query("SELECT * FROM entity WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<EntityEntity>

    @Query("SELECT * FROM entity WHERE entityType = :entityType ORDER BY id LIMIT :limit")
    suspend fun byType(entityType: String, limit: Int): List<EntityEntity>

    @Query("SELECT * FROM entity ORDER BY id")
    suspend fun all(): List<EntityEntity>

    /** 备份 v3 分页导出 */
    @Query("SELECT * FROM entity ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<EntityEntity>

    @Query("DELETE FROM entity WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 全库替换导入前清空（B8/备份 v3）：entity 没有父表可级联，必须显式清理；
     * 由导入事务在写入前调用（backup-fixer 契约）。
     */
    @Query("DELETE FROM entity")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM entity")
    suspend fun count(): Int

    // ---- entity_fts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: EntityFtsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFtsAll(rows: List<EntityFtsEntity>): List<Long>

    @Query("DELETE FROM entity_fts WHERE entityId = :entityId")
    suspend fun deleteFtsByEntity(entityId: Long)

    @Query("DELETE FROM entity_fts WHERE entityId IN (:entityIds)")
    suspend fun deleteFtsByEntities(entityIds: List<Long>)

    @Query("DELETE FROM entity_fts")
    suspend fun clearFts()

    @Query("SELECT COUNT(*) FROM entity_fts")
    suspend fun countFts(): Int

    /** B9 删除清理：移除没有 entity 的 FTS 孤儿行（FTS4 无 FK） */
    @Query("DELETE FROM entity_fts WHERE entityId NOT IN (SELECT id FROM entity)")
    suspend fun deleteFtsOrphans(): Int

    /** B9 删除清理：删除不再被任何 incidence 引用的实体（跨文档共享实体只在无引用时删） */
    @Query("DELETE FROM entity WHERE id NOT IN (SELECT entityId FROM event_entity)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT entityId FROM entity_fts WHERE entity_fts MATCH :match ORDER BY rowid LIMIT :limit")
    suspend fun searchFts(match: String, limit: Int): List<Long>
}

/** B8：event-entity 关联（weight=关联强度，description=关系描述） */
@Dao
interface EventEntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rel: EventEntityRel): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rels: List<EventEntityRel>)

    @Query("SELECT * FROM event_entity WHERE eventId = :eventId ORDER BY weight DESC, id")
    suspend fun byEvent(eventId: Long): List<EventEntityRel>

    @Query("SELECT * FROM event_entity WHERE eventId IN (:eventIds) ORDER BY eventId, weight DESC, id")
    suspend fun byEvents(eventIds: List<Long>): List<EventEntityRel>

    @Query("SELECT * FROM event_entity WHERE entityId = :entityId ORDER BY id")
    suspend fun byEntity(entityId: Long): List<EventEntityRel>

    @Query("SELECT * FROM event_entity ORDER BY id")
    suspend fun all(): List<EventEntityRel>

    /** 备份 v3 分页导出 */
    @Query("SELECT * FROM event_entity ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<EventEntityRel>

    @Query("DELETE FROM event_entity WHERE eventId = :eventId")
    suspend fun deleteByEvent(eventId: Long)

    @Query("DELETE FROM event_entity WHERE eventId IN (:eventIds)")
    suspend fun deleteByEvents(eventIds: List<Long>)

    @Query("SELECT COUNT(*) FROM event_entity")
    suspend fun count(): Int
}

/** B8：SAG 语义向量（event/entity 多态 owner；与 chunk_embedding 分离） */
@Dao
interface SagVectorDao {
    @Insert
    suspend fun insert(vector: SagVectorEntity): Long

    @Insert
    suspend fun insertAll(vectors: List<SagVectorEntity>)

    /**
     * B9：按唯一索引 (ownerType, ownerId, role, spaceId) 幂等写入。
     * 重抽取同一事件/实体时 REPLACE 旧向量（id 会变，调用方不要依赖 id 稳定）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: SagVectorEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(vectors: List<SagVectorEntity>)

    @Query("SELECT * FROM sag_vector WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY role")
    suspend fun byOwner(ownerType: String, ownerId: Long): List<SagVectorEntity>

    @Query(
        "SELECT * FROM sag_vector WHERE ownerType = :ownerType AND ownerId IN (:ownerIds) " +
            "ORDER BY ownerId, role"
    )
    suspend fun byOwners(ownerType: String, ownerIds: List<Long>): List<SagVectorEntity>

    @Query(
        "SELECT * FROM sag_vector WHERE spaceId = :spaceId AND role = :role " +
            "AND ownerType = :ownerType ORDER BY id LIMIT :limit"
    )
    suspend fun bySpace(spaceId: String, role: String, ownerType: String, limit: Int): List<SagVectorEntity>

    /** 备份导出分页（避免一次加载全部向量 BLOB） */
    @Query("SELECT * FROM sag_vector ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<SagVectorEntity>

    @Query("SELECT COUNT(*) FROM sag_vector")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sag_vector WHERE spaceId = :spaceId")
    suspend fun countBySpace(spaceId: String): Int

    @Query("DELETE FROM sag_vector WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteByOwner(ownerType: String, ownerId: Long)

    /** B9：清理某 owner 指定语义角色的向量（如 event 重建 title 向量但保留 summary） */
    @Query(
        "DELETE FROM sag_vector WHERE ownerType = :ownerType " +
            "AND ownerId = :ownerId AND role = :role"
    )
    suspend fun deleteByOwnerRole(ownerType: String, ownerId: Long, role: String)

    @Query("DELETE FROM sag_vector WHERE ownerType = :ownerType AND ownerId IN (:ownerIds)")
    suspend fun deleteByOwners(ownerType: String, ownerIds: List<Long>)

    @Query("DELETE FROM sag_vector WHERE spaceId = :spaceId")
    suspend fun deleteBySpace(spaceId: String)

    /**
     * 全库替换导入前清空（B8/备份 v3）：sag_vector 的 owner 是多态引用（无 FK），
     * 不显式清空会残留旧向量 → SAG 检索命中无 event 的实体/旧向量（backup-fixer 契约）。
     */
    @Query("DELETE FROM sag_vector")
    suspend fun deleteAll()

    /** B9 删除清理：owner 已不存在的多态向量（event/entity 删除后无 FK 级联） */
    @Query(
        "DELETE FROM sag_vector WHERE " +
            "(ownerType = 'EVENT' AND ownerId NOT IN (SELECT id FROM event)) OR " +
            "(ownerType = 'ENTITY' AND ownerId NOT IN (SELECT id FROM entity))"
    )
    suspend fun deleteOrphans(): Int
}

/** B8：抽取任务队列（每 chunk 一条；状态机见 [ExtractionJobStatus]） */
@Dao
interface ExtractionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ExtractionJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(jobs: List<ExtractionJobEntity>)

    /** B9 调度：并发创建时以唯一 chunkId 兜底，已存在则忽略（不覆盖 RUNNING 等状态） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(jobs: List<ExtractionJobEntity>): List<Long>

    @Query("SELECT * FROM extraction_job WHERE id = :id")
    suspend fun get(id: String): ExtractionJobEntity?

    @Query("SELECT * FROM extraction_job WHERE documentId = :documentId ORDER BY chunkId")
    suspend fun byDocument(documentId: String): List<ExtractionJobEntity>

    @Query("SELECT * FROM extraction_job WHERE chunkId = :chunkId ORDER BY id")
    suspend fun byChunk(chunkId: Long): List<ExtractionJobEntity>

    /** 领取下一批排队任务（按创建时间 FIFO）；limit 建议 = 批大小 */
    @Query("SELECT * FROM extraction_job WHERE status = :queuedStatus ORDER BY createdAt, id LIMIT :limit")
    suspend fun nextQueued(queuedStatus: String, limit: Int): List<ExtractionJobEntity>

    /** B9：按状态取任务（诊断/重试队列；按创建时间 FIFO，limit 建议 ≤批大小） */
    @Query("SELECT * FROM extraction_job WHERE status = :status ORDER BY createdAt, id LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int): List<ExtractionJobEntity>

    /** B9：某文档指定状态的任务（重抽取/取消清理用） */
    @Query(
        "SELECT * FROM extraction_job WHERE documentId = :documentId AND status = :status " +
            "ORDER BY chunkId"
    )
    suspend fun byDocumentAndStatus(documentId: String, status: String): List<ExtractionJobEntity>

    /** B9：排队数（worker/UI 判断是否有待抽取任务；queuedStatus=queued） */
    @Query("SELECT COUNT(*) FROM extraction_job WHERE status = :status")
    suspend fun queuedCount(status: String): Int

    /**
     * 原子认领：仅当仍为 fromStatus 时置 toStatus（QUEUED→RUNNING），返回 0 表示被其它执行者领走。
     */
    @Query(
        "UPDATE extraction_job SET status = :toStatus, attempt = attempt + 1, updatedAt = :now " +
            "WHERE id = :id AND status = :fromStatus"
    )
    suspend fun claim(id: String, fromStatus: String, toStatus: String, now: Long): Int

    /** 置成功（doneStatus=done），清空错误信息 */
    @Query(
        "UPDATE extraction_job SET status = :doneStatus, errorCode = NULL, errorDetail = NULL, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun markDone(id: String, doneStatus: String, now: Long): Int

    /** 置失败（failedStatus=failed）：保留 attempt 计数供重试上限判定 */
    @Query(
        "UPDATE extraction_job SET status = :failedStatus, errorCode = :errorCode, " +
            "errorDetail = :errorDetail, updatedAt = :now WHERE id = :id"
    )
    suspend fun markFailed(
        id: String,
        failedStatus: String,
        errorCode: String,
        errorDetail: String,
        now: Long,
    ): Int

    /** 失败重排队（toStatus=queued）：attempt 已由 [claim] 累加，超过上限由业务层拒绝调用 */
    @Query(
        "UPDATE extraction_job SET status = :toStatus, errorCode = NULL, errorDetail = NULL, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun requeue(id: String, toStatus: String, now: Long): Int

    @Query("UPDATE extraction_job SET status = :cancelledStatus, updatedAt = :now WHERE documentId = :documentId")
    suspend fun cancelByDocument(documentId: String, cancelledStatus: String, now: Long): Int

    /** 进程被杀回收：把中断态（running）重新置为 queued，避免任务永久卡住 */
    @Query(
        "UPDATE extraction_job SET status = :toStatus, updatedAt = :now " +
            "WHERE status IN (:fromStatuses)"
    )
    suspend fun requeueInterrupted(fromStatuses: List<String>, toStatus: String, now: Long): Int

    /**
     * B9：无 LLM/开关关闭/禁网时标记"未索引"（notIndexedStatus=not_indexed）——
     * 文档仍 ready，可离线检索原文；后续开启 LLM 后由 backfill 重置为 queued。
     */
    @Query(
        "UPDATE extraction_job SET status = :notIndexedStatus, errorCode = :errorCode, " +
            "errorDetail = :errorDetail, updatedAt = :now WHERE id = :id"
    )
    suspend fun markNotIndexed(
        id: String,
        notIndexedStatus: String,
        errorCode: String?,
        errorDetail: String?,
        now: Long,
    ): Int

    /** B9：手动重试/补抽取前清零 attempt（避免沿用历史失败计数直接超限） */
    @Query("UPDATE extraction_job SET attempt = 0, updatedAt = :now WHERE id = :id")
    suspend fun resetAttempt(id: String, now: Long): Int

    /** B9：整篇文档重抽取——清零 attempt 并置 queued（toStatus=queued） */
    @Query(
        "UPDATE extraction_job SET status = :toStatus, attempt = 0, errorCode = NULL, " +
            "errorDetail = NULL, updatedAt = :now WHERE documentId = :documentId"
    )
    suspend fun requeueByDocument(documentId: String, toStatus: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM extraction_job WHERE status IN (:statuses)")
    suspend fun activeCount(statuses: List<String>): Int

    @Query("SELECT COUNT(*) FROM extraction_job WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT * FROM extraction_job ORDER BY createdAt, id")
    suspend fun all(): List<ExtractionJobEntity>

    /** 备份 v3 分页导出 */
    @Query("SELECT * FROM extraction_job ORDER BY createdAt, id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ExtractionJobEntity>

    @Query("DELETE FROM extraction_job WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: String)

    @Query("DELETE FROM extraction_job WHERE chunkId = :chunkId")
    suspend fun deleteByChunk(chunkId: Long)

    @Query("DELETE FROM extraction_job")
    suspend fun deleteAll()
}

/** B8：问答会话/消息/引用（P1；备份 P0 默认不导出） */
@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ChatConversationEntity)

    @Query("SELECT * FROM chat_conversation WHERE id = :id")
    suspend fun conversation(id: String): ChatConversationEntity?

    @Query("SELECT * FROM chat_conversation ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentConversations(limit: Int): List<ChatConversationEntity>

    @Query("UPDATE chat_conversation SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, now: Long)

    @Query("DELETE FROM chat_conversation WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_message WHERE conversationId = :conversationId ORDER BY createdAt, id")
    suspend fun messages(conversationId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCitations(citations: List<MessageCitationEntity>)

    @Query("SELECT * FROM message_citation WHERE messageId = :messageId ORDER BY rank")
    suspend fun citations(messageId: String): List<MessageCitationEntity>

    @Query("DELETE FROM message_citation WHERE messageId = :messageId")
    suspend fun deleteCitations(messageId: String)
}

/** B8：本地检索/问答诊断轨迹（仅保留最近 N 条；不含正文） */
@Dao
interface QueryTraceDao {
    @Insert
    suspend fun insert(trace: QueryTraceEntity): Long

    @Query("SELECT * FROM query_trace ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<QueryTraceEntity>

    @Query(
        "DELETE FROM query_trace WHERE id NOT IN " +
            "(SELECT id FROM query_trace ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun keepRecent(keep: Int): Int

    @Query("DELETE FROM query_trace WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM query_trace")
    suspend fun count(): Int

    @Query("DELETE FROM query_trace")
    suspend fun deleteAll()
}
