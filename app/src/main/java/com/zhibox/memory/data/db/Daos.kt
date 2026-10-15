package com.zhibox.memory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM source WHERE status = 'active' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun get(id: String): SourceEntity?
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM document ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("UPDATE document SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT COUNT(*) FROM document WHERE status = 'ready'")
    suspend fun readyCount(): Int
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

    @Query("SELECT COUNT(*) FROM chunk")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM chunk_fts")
    suspend fun ftsCount(): Int

    @Query("SELECT COUNT(*) FROM chunk WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: String): Int

    /** 关键词候选：bigram 化后的查询串 MATCH（W2 检索服务接入，W1 冒烟用） */
    @Query(
        "SELECT chunkId FROM chunk_fts WHERE chunk_fts MATCH :matchQuery LIMIT :limit"
    )
    suspend fun searchFts(matchQuery: String, limit: Int): List<Long>

    @Query("SELECT * FROM chunk WHERE id IN (:ids)")
    suspend fun chunksByIds(ids: List<Long>): List<ChunkEntity>
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: EmbeddingMetaEntity)

    @Query("SELECT * FROM embedding_meta WHERE active = 1 LIMIT 1")
    suspend fun activeSpace(): EmbeddingMetaEntity?

    @Query("SELECT * FROM embedding_meta WHERE id = :id")
    suspend fun meta(id: String): EmbeddingMetaEntity?

    @Insert
    suspend fun insertAll(embeddings: List<ChunkEmbeddingEntity>)

    @Query("DELETE FROM chunk_embedding WHERE chunkId IN (SELECT id FROM chunk WHERE documentId = :documentId)")
    suspend fun deleteByDocument(documentId: String)

    @Query("SELECT COUNT(*) FROM chunk_embedding")
    suspend fun count(): Int
}

@Dao
interface IngestJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: IngestJobEntity)

    @Query("SELECT * FROM ingest_job ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IngestJobEntity>>

    @Query("SELECT * FROM ingest_job WHERE id = :id")
    suspend fun get(id: String): IngestJobEntity?

    @Query("UPDATE ingest_job SET stage = :stage, progress = :progress, updatedAt = :now WHERE id = :id")
    suspend fun updateStage(id: String, stage: String, progress: Int, now: Long)

    @Query("UPDATE ingest_job SET stage = 'failed', error = :error, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: String, error: String, now: Long)

    @Query("SELECT COUNT(*) FROM ingest_job WHERE stage IN ('queued','parsing','chunking','embedding','storing')")
    suspend fun activeCount(): Int
}

@Dao
interface SchemaMigrationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(migration: SchemaMigrationEntity)
}
