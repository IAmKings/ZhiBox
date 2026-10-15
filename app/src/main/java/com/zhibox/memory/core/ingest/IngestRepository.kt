package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.core.text.Chunker
import com.zhibox.memory.core.text.DocumentParser
import com.zhibox.memory.core.text.TextNormalizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导入编排（P1-W1-4）：解析 → 规范化 → 分块 → 分批 embedding → 入库（chunk + FTS bigram + 向量）。
 *
 * 幂等性：processDocument 开头清理该文档既有 chunk/FTS/向量——WorkManager 重跑或进程被杀后
 * 重新调度都安全（重做而非续做；续做优化留待基准证明必要后再做）。
 * 状态机：queued → parsing → chunking → embedding(progress) → storing → done / failed。
 */
class IngestRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    private val chunker: Chunker = Chunker(),
) {
    private val filesDir get() = context.filesDir

    /** 把暂存目录（filesDir/import-staging）中的 txt/md 全部登记并排队 */
    suspend fun importStaged(): List<String> {
        // 双入队防护（实测两轮均出现 2×100 任务）：在途批次存在时直接跳过整轮扫描。
        // 覆盖并发与顺序重入两种模式；AtomicBoolean 只防并发不防顺序，故改用 DB 在途检查。
        if (db.ingestJobDao().activeCount() > 0) return emptyList()
        if (!stagingBusy.compareAndSet(false, true)) return emptyList()
        try {
            val staging = File(filesDir, "import-staging")
            val files = staging.listFiles { f ->
                f.isFile && (f.extension.equals("md", true) || f.extension.equals("txt", true))
            }?.sortedBy { it.name } ?: return emptyList()
            return files.map { importFile(it) }
        } finally {
            stagingBusy.set(false)
        }
    }

    /** 登记单个文件：移动到 documents/、建 source/document/job 行、排队 WorkManager */
    suspend fun importFile(staged: File): String {
        val documentId = "doc_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val sourceId = "src_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val jobId = "job_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val now = System.currentTimeMillis()

        val docsDir = File(filesDir, "documents").apply { mkdirs() }
        val stored = File(docsDir, "$documentId.${staged.extension}")
        if (!staged.renameTo(stored)) {
            staged.copyTo(stored, overwrite = true)
            staged.delete()
        }

        val bytes = stored.readBytes()
        val checksum = sha256Hex(bytes)

        db.sourceDao().upsert(
            SourceEntity(sourceId, staged.nameWithoutExtension, stored.absolutePath, null, now, "active")
        )
        db.documentDao().upsert(
            DocumentEntity(documentId, sourceId, staged.nameWithoutExtension, checksum, "pending", now, "processing")
        )
        db.ingestJobDao().upsert(
            IngestJobEntity(jobId, sourceId, documentId, "queued", 0, null, now, now)
        )

        // 唯一工作链串行执行：多 worker 并行会各自加载嵌入模型（1.4GB/实例），
        // 低内存设备直接内存耗尽（P1-W1 验收实测：并行 5 实例 RSS 7GB 触发 LMK）
        WorkManager.getInstance(context)
            .beginUniqueWork(INGEST_CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<IngestWorker>()
                    .setInputData(
                        workDataOf(
                            IngestWorker.KEY_DOCUMENT to documentId,
                            IngestWorker.KEY_JOB to jobId,
                        )
                    )
                    .build())
            .enqueue()
        return documentId
    }

    /** worker 主流程；失败时 job/document 已标记，向上抛出由 worker 决定 Result */
    suspend fun processDocument(documentId: String, jobId: String) {
        val jobDao = db.ingestJobDao()
        val docDao = db.documentDao()
        try {
            val doc = docDao.get(documentId) ?: error("document not found: $documentId")
            val source = db.sourceDao().get(doc.sourceId) ?: error("source not found: ${doc.sourceId}")
            val raw = File(source.uri)
            check(raw.isFile) { "document file missing: ${source.uri}" }
            docDao.updateStatus(documentId, "processing")

            // 1. 解析 + 规范化
            jobDao.updateStage(jobId, "parsing", 5, now())
            val parsed = DocumentParser.parse(raw.inputStream(), null, raw.name)
            val normalized = TextNormalizer.normalize(parsed.text)

            // 2. 分块（幂等：清旧再插）
            jobDao.updateStage(jobId, "chunking", 15, now())
            db.chunkDao().deleteFtsByDocument(documentId)
            db.embeddingDao().deleteByDocument(documentId)
            db.chunkDao().deleteByDocument(documentId)
            val chunks = chunker.chunk(normalized)
            check(chunks.isNotEmpty()) { "document produced no chunks (empty content?)" }
            val chunkIds = db.chunkDao().insertAll(
                chunks.map {
                    ChunkEntity(
                        documentId = documentId, ordinal = it.ordinal, content = it.content,
                        charStart = it.charStart, charEnd = it.charEnd, tokenEstimate = it.tokenEstimate,
                    )
                }
            )
            db.chunkDao().insertFts(
                chunkIds.map { ChunkFtsEntity(chunkId = it, bigramContent = "") }
                    .zip(chunks) { fts, c -> fts.copy(bigramContent = BigramTokenizer.toFtsText(c.content)) }
            )

            // 3. embedding（进程级单例 Provider + 互斥串行：模型 1.4GB，仅允许一份实例）
            jobDao.updateStage(jobId, "embedding", 20, now())
            val model = findModel() ?: error("no loadable .litertlm model in filesDir/models")
            providerMutex.withLock {
                val provider = sharedProvider
                    ?: UniversalEmbedderProvider(context, model)
                        .also { sharedProvider = it } // 进程生命周期持有，避免反复冷加载
                ensureSpace(provider)
                val batch = EMBED_BATCH
                val embeddings = ArrayList<ChunkEmbeddingEntity>(chunks.size)
                chunks.forEachIndexed { i, c ->
                    val vec = provider.embedDocuments(listOf(c.content)).first()
                    embeddings += ChunkEmbeddingEntity(
                        chunkId = chunkIds[i],
                        embeddingSpaceId = SPACE_ID,
                        role = ROLE_CONTENT,
                        vectorBlob = toBlob(vec),
                        vectorNorm = 1.0, // L2 归一化由 provider 保证
                        createdAt = now(),
                    )
                    if (embeddings.size % batch == 0 || i == chunks.lastIndex) {
                        db.embeddingDao().insertAll(embeddings.toList())
                        embeddings.clear()
                    }
                    if (i % batch == 0) {
                        jobDao.updateStage(
                            jobId, "embedding",
                            20 + (70 * (i + 1) / chunks.size), now(),
                        )
                    }
                }
                // 维度校验须在首次编码之后（descriptor.dimensions 首次推理才回填，
                // 新进程首次文档为 -1；放在循环前曾导致首文档必失败并连锁取消整条链）
                check(provider.descriptor.dimensions == EXPECTED_DIM) {
                    "unexpected dimensions ${provider.descriptor.dimensions}"
                }
            }

            // 4. 完成
            jobDao.updateStage(jobId, "storing", 95, now())
            docDao.updateStatus(documentId, "ready")
            jobDao.updateStage(jobId, "done", 100, now())
        } catch (t: Throwable) {
            docDao.updateStatus(documentId, "failed")
            jobDao.markFailed(jobId, "${t.javaClass.simpleName}: ${t.message}", now())
            throw t
        }
    }

    private suspend fun ensureSpace(provider: UniversalEmbedderProvider) {
        val existing = db.embeddingDao().meta(SPACE_ID)
        if (existing == null) {
            db.embeddingDao().upsertMeta(
                com.zhibox.memory.data.db.EmbeddingMetaEntity(
                    id = SPACE_ID,
                    providerId = UniversalEmbedderProvider.PROVIDER_ID,
                    modelId = provider.descriptor.modelId,
                    modelRevision = provider.descriptor.modelRevision,
                    dimensions = EXPECTED_DIM,
                    normalization = "l2",
                    taskTemplate = "app-prefix-v1",
                    createdAt = now(),
                    active = true,
                )
            )
        }
    }

    private fun findModel(): File? =
        File(filesDir, "models").listFiles { f -> f.extension == "litertlm" }
            ?.sortedByDescending { it.name.contains("440m") }
            ?.firstOrNull { it.name.contains("440m") } // 仅接受已验证可加载的 440M（P0-2 教训）

    private fun toBlob(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in v) putFloat(f)
        }.array()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val SPACE_ID = "mp-universal/egemma2-440m/app-prefix-v1"
        const val EXPECTED_DIM = 768
        const val ROLE_CONTENT = "content"
        const val EMBED_BATCH = 16
        const val INGEST_CHAIN = "ingest-chain"

        /** 进程级共享嵌入模型（唯一实例）；embedding 全程持锁串行 */
        @Volatile
        private var sharedProvider: UniversalEmbedderProvider? = null
        private val providerMutex = Mutex()
        private val stagingBusy = AtomicBoolean(false)
    }
}
