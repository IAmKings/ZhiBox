package com.zhibox.memory.core.ingest

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.Sha256
import com.zhibox.memory.core.search.VectorMatrixCache
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.core.text.Chunker
import com.zhibox.memory.core.text.DocumentParser
import com.zhibox.memory.core.text.TextNormalizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** B1-6 单文件登记结果：新增入队 / 内容去重命中（返回既有文档 id）。 */
sealed interface ImportResult {
    /** 已登记新 source/document/job（由调用方负责 Worker 入队）。 */
    data class Enqueued(val documentId: String) : ImportResult

    /** 同 checksum 且状态为 pending/processing/ready 的文档已存在：staging 副本已删除，不再登记。 */
    data class Duplicate(val existingDocumentId: String) : ImportResult
}

/**
 * B3-13 批量导入结果（UI 契约，ui-fixer 依赖）：
 * - [documentIds]：本次**新登记并入队**的 document id（去重命中/失败的文件不计入）；
 * - [duplicateCount]：本次因同 checksum 已存在（pending/processing/ready）而跳过的文件数。
 *
 * 与 [IngestRepository.importFile] 的 [ImportResult] 一一对应：
 * Enqueued → documentIds+；Duplicate → duplicateCount++。
 */
data class ImportBatchResult(
    val documentIds: List<String>,
    val duplicateCount: Int,
) {
    /** 本次新登记数（UI 展示用便捷属性；不参与持久化）。 */
    val enqueuedCount: Int get() = documentIds.size
}

/**
 * B7 旧向量空间信息（核心 API，ui-fixer 依赖）：库中属于**已下线模型路线**的空间。
 *
 * 判定规则见 [IngestRepository.isSupportedSpace]：providerId ∈ {mediapipe-universal,
 * litertlm-engine} 且 modelId 含 `embeddinggemma-2`（忽略大小写）= 受支持（EGemma2 三强度 +
 * SM8750 NPU 变体）；其余（granite / Path C `embedding_gemma.task` / LFM / 未知孤儿空间）为 legacy。
 * UI 用 [modelId]/[vectorCount] 提示"旧数据需重新导入"（旧向量无法被新模型复用）。
 */
data class LegacySpaceInfo(
    val id: String,
    val providerId: String,
    val modelId: String,
    val vectorCount: Int,
)

/**
 * 导入编排（P1-W1-4）：解析 → 规范化 → 分块 → 分批 embedding → 入库（chunk + FTS bigram + 向量）。
 *
 * **线程边界（P0-2）**：[importStaged]/[importFile]/[processDocument] 整体 `withContext(Dispatchers.IO)`。
 * 文件遍历、复制、流式 SHA-256、解析读文件都在 IO；CPU 重活（分块/嵌入）在
 * `EmbeddingRuntime.withProvider` 的 `Dispatchers.Default` 上。Main 调用方只挂起等待。
 *
 * **发布语义（P1-1）**：document.status: pending → processing →（全部派生数据写完）→ ready。
 * 检索 DAO 只 JOIN `status='ready'`，因此导入中的 chunk/FTS/向量对检索不可见；维度校验
 * 提前到首批向量入库前；失败/取消路径统一清理该文档的 chunk/FTS/向量。
 *
 * **双状态机（B1-10）**：`ingest_job.stage` 是唯一事实源，`document.status` 由它派生
 * （映射见 [IngestStateMachine]）。本类所有状态写入都走下列统一入口，且 job 与 doc 在
 * **同一 Room 事务**内更新，杜绝 `doc=cancelled + job=done` 之类非法组合：
 * `claimJob` / `transition` / `updateProgress` / `publishReady` / `failOrRequeue` /
 * `requeueAfterInterruption` / `finishCancelled` / `recoverInterrupted` / `retryFailedJobs`。
 *
 * **去重（B1-6）**：importFile 在移动/写库前按流式 SHA-256 + 文档状态查重；同内容
 * pending/processing/ready 视为已存在（返回既有 documentId 并清理 staging 副本），
 * cancelled/failed 允许重导。
 *
 * **取消语义（P1-2）**：[cancelImport] 只取消 queued job，并把 pending/processing 文档标 cancelled
 * 作为"取消请求"信号；在途文档在批次边界检测到后自行清理并置 cancelled，不会再把 cancelled
 * 覆写为 ready。普通异常按 [MAX_ATTEMPTS] 自动重排队。
 *
 * **幂等性**：processDocument 开头清理该文档既有 chunk/FTS/向量——WorkManager 重跑或进程被杀后
 * 重新调度都安全（重做而非续做）。
 * 状态机：queued → parsing → chunking → embedding(progress) → storing → done / failed / cancelled。
 */
class IngestRepository(
    private val context: Context,
    val db: AppDatabase = AppDatabase.get(context),
    private val chunker: Chunker = Chunker(),
) {
    private val filesDir get() = context.filesDir

    // ───────────────────────── Worker 编排入口（B1-10 统一状态写入） ─────────────────────────

    /** 领取下一排队任务（读路径；状态写入统一走 [claimJob]）。 */
    suspend fun nextQueuedJob(): IngestJobEntity? =
        db.ingestJobDao().nextQueued(JobStage.QUEUED.value)

    /**
     * B1-10：原子认领 + 派生文档状态（同一事务）：job QUEUED→PARSING，document→PROCESSING。
     * 返回 false = 已被其他执行者领走。
     */
    suspend fun claimJob(job: IngestJobEntity): Boolean = withContext(Dispatchers.IO) {
        val documentId = job.documentId ?: return@withContext false
        db.withTransaction {
            val claimed = db.ingestJobDao().claim(
                job.id, JobStage.QUEUED.value, JobStage.PARSING.value, now(),
            )
            if (claimed > 0) {
                db.documentDao().updateStatusIfNotCancelled(
                    documentId, DocStatus.PROCESSING.value, DocStatus.CANCELLED.value,
                )
            }
            claimed > 0
        }
    }

    /** 无 documentId 的坏 job 直接置终态（B1-10：无 doc 可派生，仅 job 迁移）。 */
    suspend fun failJobWithoutDocument(jobId: String) {
        db.ingestJobDao().markFailed(
            jobId,
            JobStage.FAILED.value,
            PipelineError.encode(PipelineError.Unknown("missing documentId")),
            now(),
        )
    }

    /**
     * Worker 启动时回收进程被杀遗留的中间态任务（P1-2），并**同一事务**把对应文档
     * 复位为 pending（B1-10：job→QUEUED 的派生状态必须是 PENDING，不能留下 processing 悬挂）。
     * 返回重排队的 job 数。
     */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            val jobDao = db.ingestJobDao()
            // 先快照待回收 job（requeueInterrupted 之后无法再按原 stage 区分）
            val interrupted = jobDao.all().filter { job ->
                JobStage.fromValue(job.stage)?.let { it in IngestStateMachine.INTERMEDIATE } == true
            }
            val requeued = jobDao.requeueInterrupted(
                JobStage.INTERRUPTED_VALUES, JobStage.QUEUED.value, now(),
            )
            for (job in interrupted) {
                val documentId = job.documentId ?: continue
                // 取消请求在进程被杀前来不及收尾（doc=cancelled + job=中间态）：job 一并收尾为
                // cancelled，不能把已取消内容重新排入队列（否则 doc=cancelled + job=queued 非法）。
                val cancelled = IngestStateMachine.isDocumentCancelled(
                    DocStatus.fromValue(db.documentDao().status(documentId)),
                )
                if (cancelled) {
                    jobDao.updateStageIfNotCancelled(
                        job.id, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
                    )
                } else {
                    db.documentDao().updateStatusIfNotCancelled(
                        documentId, DocStatus.PENDING.value, DocStatus.CANCELLED.value,
                    )
                }
            }
            requeued
        }
    }

    /**
     * 把暂存目录（filesDir/import-staging）中的 txt/md 全部登记并排队（B3-13 结果契约）。
     *
     * P1-4 修复：
     * - 逐文件 try/catch：单文件异常不再中断整批；
     * - `finally` 只要登记过 job 就确保 Worker 入队（APPEND_OR_REPLACE），杜绝"有 queued job 无 worker"死局；
     * - `activeCount>0` 时不再静默返回：补一次入队（修复上一轮异常留下的悬挂队列）。
     * B1-6：内容重复（同 checksum 且状态 pending/processing/ready）的文件跳过登记、清理 staging 副本，
     * 计入 [ImportBatchResult.duplicateCount]，不触发多余的 Worker 入队。
     *
     * @return [ImportBatchResult]（新登记 documentIds + 去重跳过数）；UI 可据此提示"已登记 N，跳过重复 M"。
     */
    suspend fun importStaged(): ImportBatchResult = importStagedInternal(files = null)

    /**
     * 显式文件清单重载（与任务契约 `importStaged(files)` 对齐；UI/测试可传已选文件）。
     * 不传文件时请用无参 [importStaged]——扫描 staging 目录发生在 IO 线程内，**不要**把
     * 目录扫描做成默认参数（默认参数在调用方线程求值，Main 调用会触发磁盘读，违反 P0-2）。
     */
    suspend fun importStaged(files: List<File>): ImportBatchResult = importStagedInternal(files)

    private suspend fun importStagedInternal(files: List<File>?): ImportBatchResult =
        withContext(Dispatchers.IO) {
            // 双入队防护（实测两轮均出现 2×100 任务）：在途批次存在时不再扫描登记。
            // 但仍要确保 worker 存活（见下）——只防重复登记，不放弃唤醒。
            if (!stagingBusy.compareAndSet(false, true)) return@withContext ImportBatchResult(emptyList(), 0)
            var registered = 0
            try {
                if (db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES) > 0) {
                    ensureIngestWorker() // P1-4：有 job（可能因上轮异常而无人消费）→ 补入队
                    return@withContext ImportBatchResult(emptyList(), 0)
                }
                // 目录扫描/过滤在 IO 线程内完成（无参入口不把扫描放进默认参数，见方法注释）
                val batchFiles = files ?: stagingFiles()
                val documentIds = ArrayList<String>(batchFiles.size)
                var duplicateCount = 0
                for (file in batchFiles) {
                    try {
                        when (val result = importFile(file)) {
                            is ImportResult.Enqueued -> {
                                documentIds += result.documentId
                                registered++
                            }
                            // B1-6：内容重复——staging 副本已由 importFile 删除，不登记、不产生 job；
                            // B3-13：计入 duplicateCount 供 UI 提示（不再静默吞掉）
                            is ImportResult.Duplicate -> duplicateCount++
                        }
                    } catch (c: CancellationException) {
                        throw c // 取消必须向上传播（P1-2）：不能当单文件失败继续循环
                    } catch (e: Exception) {
                        // 单文件登记失败：跳过继续（文件回滚失败则保留在 staging 供下次重试）。
                        // 仓库约定不写运行日志（错误已结构化落库），仅跳过该文件。
                    } catch (e: Error) {
                        throw e // OOM 等不可恢复错误：不当作单文件失败吞掉
                    }
                }
                ImportBatchResult(documentIds, duplicateCount)
            } finally {
                stagingBusy.set(false)
                // 只要本次登记过任何 job（含部分失败前已登记的），就必须保证 Worker 会执行
                if (registered > 0) ensureIngestWorker()
            }
        }

    /** staging 目录扫描（点开头过滤 + only md/txt + 按文件名排序）；必须在 IO 线程调用。 */
    private fun stagingFiles(): List<File> {
        val staging = File(filesDir, "import-staging")
        return staging.listFiles { f ->
            // 点开头文件过滤（W1-K1 根因修复）：macOS AppleDouble(._*)/.DS_Store 等
            // 传输垃圾会伪装成 .txt/.md 进入知识库（实测 100 语料变 200 文档）
            f.isFile && !f.name.startsWith(".") &&
                (f.extension.equals("md", true) || f.extension.equals("txt", true))
        }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 登记单个文件：按 checksum 去重（B1-6）→ 复制到 documents/ → 单事务写 source/document/job
     * → 删除 staging 文件。P1-4：DB 写失败时回滚已复制的文件；文件保留在 staging（先复制后删），
     * 避免"文件没了、DB 没记录"。
     *
     * @return [ImportResult.Enqueued] 新登记；[ImportResult.Duplicate] 同内容文档已存在（不重复登记）。
     */
    suspend fun importFile(staged: File): ImportResult = withContext(Dispatchers.IO) {
        // ── B1-6：内容级去重（移动/写库前）──
        // 同 checksum 文档处于 pending/processing/ready ⇒ 内容已在库（或正在入库），跳过；
        // cancelled/failed 属"上次失败/被取消"，允许重导（用户重试意图）。
        val checksum = sha256HexFile(staged)
        val duplicate = db.documentDao().byChecksum(checksum).firstOrNull { doc ->
            // 显式复查 checksum（byChecksum 的 SQL 条件之外再确认一次）：只有内容确实相同
            // 才允许删除 staging 副本；防止未来查询/索引条件漂移后误删用户未入库的文件。
            doc.checksum == checksum && isDuplicateDocumentStatus(DocStatus.fromValue(doc.status))
        }
        if (duplicate != null) {
            // 同内容已登记（pending/processing/ready）：删除本次 staging 副本
            //（否则每次"全部导入"都会重复扫描同一文件），返回既有 documentId 供调用方展示。
            staged.delete()
            return@withContext ImportResult.Duplicate(duplicate.id)
        }
        val sizeBytes = staged.length()

        val documentId = "doc_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val sourceId = "src_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val jobId = "job_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val now = System.currentTimeMillis()

        val docsDir = File(filesDir, "documents").apply { mkdirs() }
        val stored = File(docsDir, "$documentId.${staged.extension}")
        staged.copyTo(stored, overwrite = true)
        try {
            db.withTransaction {
                db.sourceDao().upsert(
                    SourceEntity(sourceId, staged.nameWithoutExtension, stored.absolutePath, null, now, SourceStatus.ACTIVE.value)
                )
                db.documentDao().upsert(
                    // 命名参数（曾因位置错位把 "pending" 传给 parserId、status 恒为 processing，
                    // 导致取消导入的 cancelAllPending 永远匹配不到——K40 实测发现）
                    DocumentEntity(
                        id = documentId,
                        sourceId = sourceId,
                        title = staged.nameWithoutExtension,
                        checksum = checksum,
                        parserId = "pending", // 解析器在 processDocument 时回写（P1-6）
                        createdAt = now,
                        status = DocStatus.PENDING.value, // 与 job QUEUED 的派生状态一致（B1-10）
                        sizeBytes = sizeBytes, // P1-6：此前恒 0
                    )
                )
                db.ingestJobDao().upsert(
                    IngestJobEntity(jobId, sourceId, documentId, JobStage.QUEUED.value, 0, null, now, now)
                )
            }
        } catch (t: Throwable) {
            // 回滚已复制的文件（staging 原件仍在，用户文件名不会丢）
            runCatching { stored.delete() }
            throw t
        }
        // 入队由 importStaged/ensureIngestWorker 统一完成（批量单 worker）；此处仅登记
        staged.delete()
        ImportResult.Enqueued(documentId)
    }

    /**
     * worker 主流程（P1-1/P1-2 重写；B1-10 状态写入统一走事务 helper）：
     * - 先 processing（不可检索），全部派生数据写完最后一步才置 ready（发布语义）；
     * - 首批向量入库前校验维度；
     * - CancellationException：清理 + job 重排队 + 文档回 pending + 重抛（WorkManager 停止不是业务失败）；
     * - Exception：清理 + attempt++；attempt<[MAX_ATTEMPTS] 自动重排队，否则 failed（终态供 UI 手动重试）；
     * - Error：直接抛出，不当作业务失败（OOM 等）。
     */
    suspend fun processDocument(documentId: String, jobId: String): Unit = withContext(Dispatchers.IO) {
        val docDao = db.documentDao()
        try {
            val doc = docDao.get(documentId) ?: error("document not found: $documentId")
            val source = db.sourceDao().get(doc.sourceId) ?: error("source not found: ${doc.sourceId}")
            val raw = File(source.uri)
            check(raw.isFile) { "document file missing: ${source.uri}" }

            // B1-10：统一迁移入口（job→PARSING + doc→PROCESSING，同事务；claim 已做过则幂等）。
            // 返回 false = 被取消守卫拦截（用户取消）→ 清理并按 cancelled 收尾。
            if (!transition(documentId, jobId, JobStage.PARSING, 5)) {
                return@withContext finishCancelled(documentId, jobId)
            }
            if (isCancelRequested(documentId)) return@withContext finishCancelled(documentId, jobId)

            // 1. 解析 + 规范化 + 回写真实 parserId/sizeBytes（P1-6；非状态列，不走状态机）
            val parsed = DocumentParser.parse(raw.inputStream(), null, raw.name)
            val normalized = TextNormalizer.normalize(parsed.text)
            docDao.updateParserAndSize(documentId, parsed.parserId, raw.length())

            // 2. 分块（幂等：chunk+FTS+旧向量单事务清理后重写）。
            // B2-5/B7：块长统一由 Chunker.Options.maxChars=600 决定（构造函数注入的 chunker 即默认 600）——
            // 路径 A（UE）受 litertlm 1024 token 签名上限约束（600 字×1.35+前缀≈830 token 留余量）；
            // 路径 B 引擎当前仅 EGemma2-270M 使用，实测动态上下文 ≥800 CJK 字可用（G1B 2026-10-16），
            // 为保持三强度一致仍取 600。旧 `chunkerFor(pathB)` 死分支（600/600 等价）已删除。
            updateProgress(jobId, JobStage.CHUNKING, 15) // 派生 doc 仍 processing，不变
            val model = findModel() ?: error("未找到可用模型：filesDir/models 为空（请先下载/投放模型）")
            val chunks = chunker.chunk(normalized)
            check(chunks.isNotEmpty()) { "document produced no chunks (empty content?)" }
            val chunkIds = replaceChunks(documentId, chunks)
            if (isCancelRequested(documentId)) return@withContext finishCancelled(documentId, jobId)

            // 3. embedding（EmbeddingRuntime 进程单例 + 互斥串行：模型 1.4GB，仅允许一份实例；
            //    导入与检索共用同一运行时与路由，唯一事实源见 EmbeddingRuntime）
            updateProgress(jobId, JobStage.EMBEDDING, 20)
            var cancelledMidway = false
            EmbeddingRuntime.withProvider(context, model) { provider ->
                if (isCancelRequested(documentId)) {
                    cancelledMidway = true
                    return@withProvider
                }
                // B4-3：维度单一来源——catalog 已收录模型（含 LFM 1024d）走 catalog dims；
                // 未收录模型回退 ModelCatalog.DEFAULT_DIMS（不再有 IngestRepository.EXPECTED_DIM 副本）
                val expectedDim = ModelCatalog.dimsFor(model.name)
                var dimChecked = false
                var spaceId: String? = null
                var offset = 0
                while (offset < chunkIds.size) {
                    currentCoroutineContext().ensureActive() // WorkManager 停止 → CancellationException
                    if (isCancelRequested(documentId)) {
                        cancelledMidway = true
                        return@withProvider
                    }
                    val end = minOf(offset + EMBED_BATCH, chunkIds.size)
                    val batchChunks = chunks.subList(offset, end)
                    val vectors = provider.embedDocuments(batchChunks.map { it.content })
                    check(vectors.size == end - offset) {
                        "provider returned ${vectors.size} vectors for ${end - offset} texts"
                    }
                    if (!dimChecked) {
                        // P1-1：维度校验提前到**首批向量入库前**。此前在全部向量写完后才 check，
                        // 失败时整空间已污染（且旧代码把 enabled=false 的引擎留在缓存里，见 P1-3）。
                        check(provider.descriptor.dimensions == expectedDim) {
                            "unexpected dimensions ${provider.descriptor.dimensions} (expect $expectedDim)"
                        }
                        // 空间注册（embedding_meta 唯一写入口）：此时 descriptor.dimensions 已回填；
                        // B1-4 同时维护"仅一个 active 空间"不变式（见 EmbeddingRuntime.registerSpace）
                        spaceId = EmbeddingRuntime.registerSpace(context, provider, model)
                        dimChecked = true
                    }
                    val nowTs = now()
                    db.embeddingDao().insertAll(
                        batchChunks.mapIndexed { i, c ->
                            ChunkEmbeddingEntity(
                                chunkId = chunkIds[offset + i],
                                embeddingSpaceId = spaceId!!,
                                role = ROLE_CONTENT,
                                vectorBlob = toBlob(vectors[i]),
                                vectorNorm = 1.0, // L2 归一化由 provider 保证
                                createdAt = nowTs,
                            )
                        }
                    )
                    // P1-5：写路径主动失效矩阵缓存（签名兜底，但显式失效让下次检索立即看到新数据）
                    VectorMatrixCache.invalidate(spaceId)
                    offset = end
                    updateProgress(jobId, JobStage.EMBEDDING, 20 + (70 * offset / chunkIds.size))
                }
            }
            if (cancelledMidway) return@withContext finishCancelled(documentId, jobId)

            // 4. 发布（B1-10）：同一事务 doc→ready + job→done；取消竞态下返回 false → 清理并保持 cancelled
            updateProgress(jobId, JobStage.STORING, 95)
            if (!publishReady(documentId, jobId)) {
                return@withContext finishCancelled(documentId, jobId)
            }
        } catch (c: CancellationException) {
            // P1-2：WorkManager 停止/协程取消 = 中断而非业务失败。清理必须用 NonCancellable，
            // 否则已取消的协程里任何 suspend DB 调用都会立刻再抛 CancellationException。
            withContext(NonCancellable) { requeueAfterInterruption(documentId, jobId) }
            throw c
        } catch (e: Error) {
            // OOM 等不可恢复错误：不落业务失败（P1-2：catch Throwable 会把 Error 当失败吞掉）
            throw e
        } catch (e: Exception) {
            // P1-1：失败路径清理派生数据 + B1-10 同一事务派生 doc 状态
            withContext(NonCancellable) {
                // B2-7：结构化分类（CODE:detail），retryable 参与自动重试决策（审查 H1），
                // 旧纯文本 error 仍可通过 PipelineError.decode 兼容展示。
                failOrRequeue(documentId, jobId, PipelineError.classify(e))
            }
        }
    }

    // ───────────────────────── B1-10 统一状态迁移入口（全部 @Transaction 语义） ─────────────────────────

    /**
     * 迁移到「处理中/终态」阶段：同一事务内 job.stage（取消守卫）→ document.status 派生。
     *
     * @return true=迁移成功；false=被取消守卫拦截（doc 已冻结或 job 已冻结）。
     *         拦截时会把 job 收尾为 cancelled，避免 `doc=cancelled + job=中间态` 悬挂。
     */
    private suspend fun transition(
        documentId: String,
        jobId: String,
        stage: JobStage,
        progress: Int,
    ): Boolean = db.withTransaction {
        val jobDao = db.ingestJobDao()
        val docDao = db.documentDao()
        val jobUpdated = jobDao.updateStageIfNotCancelled(
            jobId, stage.value, progress, now(), JobStage.CANCELLED.value,
        )
        if (jobUpdated == 0) return@withTransaction false // job 已冻结：不得再改 doc 派生状态
        val docUpdated = docDao.updateStatusIfNotCancelled(
            documentId, IngestStateMachine.documentStatusFor(stage).value, DocStatus.CANCELLED.value,
        )
        if (docUpdated == 0) {
            // doc 已被用户取消冻结：把 job 一并收尾为 cancelled（组合一致性），由调用方清理
            jobDao.updateStageIfNotCancelled(
                jobId, JobStage.CANCELLED.value, progress, now(), JobStage.CANCELLED.value,
            )
            return@withTransaction false
        }
        true
    }

    /**
     * 中间阶段进度推进（B1-10）：只写 job（唯一事实源）；派生 document.status 仍为
     * PROCESSING 不变，因此无需（也不应）重复写 doc，避免每批一次多余写入。
     */
    private suspend fun updateProgress(jobId: String, stage: JobStage, progress: Int) {
        db.ingestJobDao().updateStageIfNotCancelled(
            jobId, stage.value, progress, now(), JobStage.CANCELLED.value,
        )
    }

    /**
     * 发布（B1-10）：同一事务内 doc→READY、job→DONE。
     * 返回 false = 取消竞态（doc 冻结）：调用方走 [finishCancelled] 清理。
     */
    private suspend fun publishReady(documentId: String, jobId: String): Boolean = db.withTransaction {
        val docDao = db.documentDao()
        val jobDao = db.ingestJobDao()
        val docUpdated = docDao.updateStatusIfNotCancelled(
            documentId, DocStatus.READY.value, DocStatus.CANCELLED.value,
        )
        if (docUpdated == 0) {
            jobDao.updateStageIfNotCancelled(
                jobId, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
            )
            return@withTransaction false
        }
        val jobUpdated = jobDao.updateStageIfNotCancelled(
            jobId, JobStage.DONE.value, 100, now(), JobStage.CANCELLED.value,
        )
        if (jobUpdated == 0) {
            // 防御性补偿：in-flight job 理论上不会被 cancelImport 取消；若真发生，
            // 把已置 ready 的 doc 回退 cancelled，保持两列组合一致（READY+CANCELLED 非法）。
            docDao.updateStatusIfNotCancelled(
                documentId, DocStatus.CANCELLED.value, DocStatus.CANCELLED.value,
            )
            return@withTransaction false
        }
        true
    }

    /**
     * 失败收尾（B1-10）：清理派生数据 + attempt++ + 视上限走 requeue（job→QUEUED/doc→PENDING）
     * 或终态 failed（job→FAILED/doc→FAILED），全部在同一事务内完成。
     */
    private suspend fun failOrRequeue(documentId: String, jobId: String, error: PipelineError) {
        val encodedError = PipelineError.encode(error)
        db.withTransaction {
            deleteDerivedRows(documentId)
            val jobDao = db.ingestJobDao()
            val docDao = db.documentDao()
            if (IngestStateMachine.isDocumentCancelled(DocStatus.fromValue(docDao.status(documentId)))) {
                // 并发取消：以用户取消为准，不做重试（job 与 doc 对齐为 cancelled）
                jobDao.updateStageIfNotCancelled(
                    jobId, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
                )
                return@withTransaction
            }
            jobDao.recordFailure(jobId, encodedError, now())
            val attempt = jobDao.attempt(jobId) ?: 0
            // 审查 H1：不可自动重试的错误（MODEL_CORRUPT/SPACE_MISMATCH/OOM）直接终态，
            // 不再白白重试 3 次；IO/UNKNOWN/MODEL_MISSING 仍按 attempt 上限自动重排队。
            if (error.retryable && shouldAutoRetry(attempt)) {
                // 瞬态失败：自动重排队，worker 循环会再试
                jobDao.requeue(jobId, JobStage.QUEUED.value, now())
                docDao.updateStatusIfNotCancelled(
                    documentId, DocStatus.PENDING.value, DocStatus.CANCELLED.value,
                )
            } else {
                // 超过上限：终态 failed，供 UI "重试失败导入"手动重试
                jobDao.markFailed(jobId, JobStage.FAILED.value, encodedError, now())
                docDao.updateStatusIfNotCancelled(
                    documentId, DocStatus.FAILED.value, DocStatus.CANCELLED.value,
                )
            }
        }
        VectorMatrixCache.invalidate()
    }

    /**
     * 协程中断收尾（B1-10）：清理派生数据 + 区分"用户取消"与"系统中断"：
     * - 用户取消优先 → job/doc 均收尾 cancelled；
     * - 系统中断（WorkManager 停止/进程优雅取消）→ job→QUEUED、doc→PENDING，等待重做。
     */
    private suspend fun requeueAfterInterruption(documentId: String, jobId: String) {
        db.withTransaction {
            deleteDerivedRows(documentId)
            val jobDao = db.ingestJobDao()
            val docDao = db.documentDao()
            val jobStage = JobStage.fromValue(jobDao.get(jobId)?.stage)
            val docStatus = DocStatus.fromValue(docDao.status(documentId))
            if (jobStage == JobStage.CANCELLED || docStatus == DocStatus.CANCELLED) {
                docDao.updateStatusIfNotCancelled(
                    documentId, DocStatus.CANCELLED.value, DocStatus.CANCELLED.value,
                )
                jobDao.updateStageIfNotCancelled(
                    jobId, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
                )
            } else {
                jobDao.requeue(jobId, JobStage.QUEUED.value, now())
                docDao.updateStatusIfNotCancelled(
                    documentId, DocStatus.PENDING.value, DocStatus.CANCELLED.value,
                )
            }
        }
        VectorMatrixCache.invalidate()
    }

    /**
     * 在途取消收尾（P1-2/B1-10）：清理派生数据，并在同一事务内把 job/doc 置为 cancelled。
     * 已 ready 的文档不降级（防御：正常发布失败路径不应到达这里）。
     */
    private suspend fun finishCancelled(documentId: String, jobId: String) {
        db.withTransaction {
            deleteDerivedRows(documentId)
            val docStatus = DocStatus.fromValue(db.documentDao().status(documentId))
            if (docStatus != DocStatus.READY) {
                db.documentDao().updateStatusIfNotCancelled(
                    documentId, DocStatus.CANCELLED.value, DocStatus.CANCELLED.value,
                )
            }
            db.ingestJobDao().updateStageIfNotCancelled(
                jobId, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
            )
        }
        VectorMatrixCache.invalidate()
    }

    // ───────────────────────── 派生数据清理 / FTS（B1-1） ─────────────────────────

    /** 删除文档派生数据行（chunk/FTS/向量）；必须在调用方的事务内执行以与状态迁移原子。 */
    private suspend fun deleteDerivedRows(documentId: String) {
        db.chunkDao().deleteFtsByDocument(documentId)
        db.embeddingDao().deleteByDocument(documentId)
        db.chunkDao().deleteByDocument(documentId)
    }

    /** 取消请求信号：cancelImport 把 pending/processing 文档统一标 cancelled */
    private suspend fun isCancelRequested(documentId: String): Boolean =
        IngestStateMachine.isDocumentCancelled(DocStatus.fromValue(db.documentDao().status(documentId)))

    /**
     * 单事务重写该文档 chunk+FTS（P1-9：此前 4 组独立事务存在 chunk/FTS 不一致窗口）。
     *
     * B1-1：FTS 行写入前按 chunkId 去重（[dedupeFtsByChunkId]）。FTS 是 chunk.content 的
     * 纯派生数据，重复行会导致同一 chunk 重复召回、`ftsHealthCheck` 计数漂移。
     *
     * FTS5 external content 评估结论（B1-1）：Room 仅提供 @Fts3/@Fts4 注解，FTS5 需绕过 Room
     * 手工建虚拟表并维护自定义迁移；收益（省一份 bigram 存储）不足以承担本轮风险 → 保持 FTS4。
     */
    private suspend fun replaceChunks(documentId: String, chunks: List<Chunker.Chunk>): List<Long> {
        var chunkIds: List<Long> = emptyList()
        db.withTransaction {
            deleteDerivedRows(documentId)
            chunkIds = db.chunkDao().insertAll(
                chunks.map {
                    ChunkEntity(
                        documentId = documentId, ordinal = it.ordinal, content = it.content,
                        charStart = it.charStart, charEnd = it.charEnd, tokenEstimate = it.tokenEstimate,
                    )
                }
            )
            val ftsRows = dedupeFtsByChunkId(
                chunkIds.map { ChunkFtsEntity(chunkId = it, bigramContent = "") }
                    .zip(chunks) { fts, c -> fts.copy(bigramContent = BigramTokenizer.toFtsText(c.content)) }
            )
            db.chunkDao().insertFts(ftsRows)
        }
        return chunkIds
    }

    /**
     * B7 收敛后的模型路由（与 EmbeddingRuntime.createProvider 对应）：
     * - 440M/740M + SM8750 NPU 变体 → 路径 A（MediaPipe UniversalEmbedder）；
     * - 270M → 路径 B（litertlm EmbeddingEngine）。
     * 显式名单匹配（[isPathABModel]/[isPathBModel]），不给字母序默认值留位置。
     */
    /**
     * 删除级联（§9.1-5）：五表单事务删除 + documents/ 原文文件清理；幂等。
     * 导入进行中拒绝（在途 worker 会在级联后写回数据造成半删复活）。
     */
    suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
        val source = db.sourceDao().get(sourceId) ?: return@withContext
        check(db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES) == 0) { "导入进行中，请稍后再删除" }
        db.sourceDao().deleteCascade(sourceId)
        val stored = File(source.uri)
        val docsDir = File(filesDir, "documents")
        // 仅清理本应用 documents 目录内的文件，uri 指向外部时不动
        if (stored.isFile && stored.parentFile == docsDir) stored.delete()
        VectorMatrixCache.invalidate()
    }

    /**
     * W4-1 取消导入（§9.1-4 + P1-2 修订；B1-10 事务化）：
     * - **只取消 queued 任务**（job stage=cancelled，不再被 worker 领取）；
     * - pending/processing 文档立即标 cancelled——这是给在途任务的"取消请求"信号；
     *   两列在同一事务内更新，事务外不会观察到 job=cancelled + doc=pending 的中间态；
     * - 在途文档**不中断**：processDocument 在批次边界检测到 cancelled 后自行清理派生数据并退出，
     *   `updateStageIfNotCancelled`/`updateStatusIfNotCancelled` 保证 cancelled 不会被覆写回 ready；
     * - 已导入的 ready 文档不受影响；staging 未登记的文件保留待下次导入。
     * 返回被取消的 job/文档计数（供 UI 展示）。
     */
    suspend fun cancelImport(): Int = withContext(Dispatchers.IO) {
        val cancelled = db.withTransaction {
            val jobs = db.ingestJobDao().cancelQueued(
                JobStage.CANCELLED.value, JobStage.QUEUED.value, now(),
            )
            val docs = db.documentDao().cancelAllPending(
                DocStatus.CANCELLED.value, DocStatus.ACTIVE_VALUES,
            )
            jobs + docs
        }
        VectorMatrixCache.invalidate()
        cancelled
    }

    /**
     * P1-2 手动重试入口（UI「重试失败导入」按钮；B1-10 事务化）：
     * failed job → queued，且文档在同一事务内从 failed 复位 pending；cancelled 文档对应的
     * 历史 failed job 收尾为 cancelled（不重试已取消内容）。返回重排队数量。
     */
    suspend fun retryFailedJobs(): Int = withContext(Dispatchers.IO) {
        val requeued = db.withTransaction {
            val jobDao = db.ingestJobDao()
            val docDao = db.documentDao()
            val failedJobs = jobDao.all().filter { JobStage.fromValue(it.stage) == JobStage.FAILED }
            for (job in failedJobs) {
                val documentId = job.documentId ?: continue
                if (IngestStateMachine.isDocumentCancelled(DocStatus.fromValue(docDao.status(documentId)))) {
                    // 历史异常组合（cancelled 文档 + failed job）：收尾为 cancelled，不复活
                    jobDao.updateStageIfNotCancelled(
                        job.id, JobStage.CANCELLED.value, 0, now(), JobStage.CANCELLED.value,
                    )
                } else {
                    // job→QUEUED 的派生文档状态是 PENDING：同一事务先复位，避免 retry 窗口非法组合
                    docDao.updateStatusIfNotCancelled(
                        documentId, DocStatus.PENDING.value, DocStatus.CANCELLED.value,
                    )
                }
            }
            jobDao.retryFailed(JobStage.FAILED.value, JobStage.QUEUED.value, now())
        }
        if (requeued > 0) ensureIngestWorker()
        requeued
    }

    // ───────────────────────── B7 旧空间检测（模型路线收敛） ─────────────────────────

    /**
     * B7：列出库中**已下线模型路线**的向量空间（granite / Path C `embedding_gemma.task` / LFM /
     * 无法识别的孤儿空间），供设置页提示"旧数据需重新导入"（旧向量与新模型不兼容）。
     *
     * - 支持判定为纯函数 [isSupportedSpace]（EGemma2 三强度 + SM8750 NPU 变体）；
     * - 遍历 meta ∪ chunk_embedding 的空间 id（覆盖 meta 缺失的孤儿空间），按 id 排序；
     * - 每个空间用 `countBySpace` 走索引计数，不加载向量 BLOB（100k 级库也安全，无 OOM）。
     */
    suspend fun legacySpacesReport(): List<LegacySpaceInfo> = withContext(Dispatchers.IO) {
        val metas = db.embeddingDao().allMeta().associateBy { it.id }
        val spaceIds = (metas.keys + db.embeddingDao().allSpaceIds()).toSortedSet()
        spaceIds.mapNotNull { spaceId ->
            val meta = metas[spaceId]
            if (meta != null && isSupportedSpace(meta.providerId, meta.modelId)) {
                return@mapNotNull null // 受支持的 EGemma2 空间：不算 legacy
            }
            LegacySpaceInfo(
                id = spaceId,
                providerId = meta?.providerId ?: "",
                modelId = meta?.modelId ?: "",
                vectorCount = db.embeddingDao().countBySpace(spaceId),
            )
        }
    }

    /**
     * W4-3 FTS 索引损坏恢复（§9.1-10）：bigram 文本可从 chunk.content 完全重生成——
     * 清空 chunk_fts 后按 chunk 全量重建。关键词检索立即恢复；向量不受影响。
     *
     * B1-1：重建同样按 chunkId 去重（跨批次用 seen 集合 + [dedupeFtsByChunkId] 双保险），
     * 保证重复执行/重复 chunk 行都不会产生重复 FTS 行。返回实际写入的索引条数。
     */
    suspend fun rebuildFtsIndex(): Int = withContext(Dispatchers.IO) {
        // 审查 J3：删除+重建必须在同一事务内，避免中途失败留下"半残 FTS"（关键词检索全空）。
        // 说明：allChunks 为全量内存（100k chunk 需数 10MB 级），仅诊断/修复路径调用，可接受。
        db.withTransaction {
            db.chunkDao().deleteAllFts()
            val chunks = db.chunkDao().allChunks()
            val seen = HashSet<Long>(chunks.size)
            var n = 0
            for (batch in chunks.chunked(200)) {
                val rows = dedupeFtsByChunkId(
                    batch.mapNotNull { c ->
                        if (seen.add(c.id)) {
                            ChunkFtsEntity(chunkId = c.id, bigramContent = BigramTokenizer.toFtsText(c.content))
                        } else null
                    }
                )
                if (rows.isNotEmpty()) db.chunkDao().insertFts(rows)
                n += rows.size
            }
            n
        }
    }

    /**
     * 兼容旧 UI/诊断（SettingsScreen）的签名：内部委托 [indexHealthCheck]，
     * 因此孤儿 FTS/向量等"计数相等"的不一致也能被判定（审查：此前只比计数会漏检）。
     * 返回 null=健康；否则 (chunkCount, ftsCount) 供旧 UI 展示，完整原因请用 [indexHealthCheck]。
     */
    suspend fun ftsHealthCheck(): Pair<Int, Int>? {
        val health = indexHealthCheck()
        return if (health.healthy) null else health.chunkCount to health.ftsCount
    }

    // ───────────────────────── B2-8 索引健康 / 孤儿清理 ─────────────────────────

    /** B2-8 三表健康报告：计数 + 孤儿 chunkId 集合（chunk 已不存在的 FTS/向量行） */
    data class IndexHealth(
        val chunkCount: Int,
        val ftsCount: Int,
        val embeddingCount: Int,
        val orphanFtsChunkIds: List<Long>,
        val orphanEmbeddingChunkIds: List<Long>,
    ) {
        val reasons: List<String>
            get() = IngestRepository.buildIndexHealthReasons(
                chunkCount, ftsCount, embeddingCount, orphanFtsChunkIds, orphanEmbeddingChunkIds,
            )

        val healthy: Boolean get() = reasons.isEmpty()
    }

    /** B2-8 修复结果（before/after 便于设置页/诊断展示） */
    data class IndexRepair(
        val rebuiltFts: Boolean,
        val removedFtsRows: Int,
        val removedEmbeddingRows: Int,
        val before: IndexHealth,
        val after: IndexHealth,
    )

    /**
     * B2-8 完整索引健康检查：chunk / chunk_fts / chunk_embedding 三表计数 + 孤儿检测。
     * - chunk/向量行分页读取（500/页）避免全量 Entity 物化；但 **chunkId 集合与 FTS chunkId
     *   列表仍是全量内存**（100k 约数 MB，可接受）——审查 J2：注释不再声称"峰值只有一页"；
     * - FTS 侧只取 chunkId（allFtsChunkIds），孤儿 = FTS 行 chunkId 在 chunk 表不存在；
     *   向量孤儿同理（B1-2 加 FK 前的历史数据 / 重嵌入与删除并发残留）；
     * - FTS 重复行（同一 chunkId 多行）无法仅凭计数与孤儿集合区分，会以"行数不一致"
     *   原因暴露，由 [repairIndex] 重建兜底。
     */
    suspend fun indexHealthCheck(): IndexHealth = withContext(Dispatchers.IO) {
        // 单事务快照读取：避免导入/删除并发时三表计数来自不同时间点而误报
        db.withTransaction {
            val chunkIds = HashSet<Long>()
            var offset = 0
            while (true) {
                val page = db.chunkDao().page(HEALTH_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                for (chunk in page) chunkIds.add(chunk.id)
                offset += page.size
            }
            val orphanEmbeddings = ArrayList<Long>()
            var embeddingCount = 0
            offset = 0
            while (true) {
                val page = db.embeddingDao().page(HEALTH_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                for (row in page) {
                    embeddingCount++
                    if (row.chunkId !in chunkIds) orphanEmbeddings.add(row.chunkId)
                }
                offset += page.size
            }
            val ftsChunkIds = db.chunkDao().allFtsChunkIds()
            IndexHealth(
                chunkCount = chunkIds.size,
                ftsCount = ftsChunkIds.size,
                embeddingCount = embeddingCount,
                orphanFtsChunkIds = ftsChunkIds.asSequence()
                    .filter { it !in chunkIds }.distinct().sorted().toList(),
                orphanEmbeddingChunkIds = orphanEmbeddings.distinct().sorted(),
            )
        }
    }

    /**
     * B2-8 修复：
     * 1) 同一事务内精确删除孤儿 FTS/向量行（按 chunkId ≤400/批，避免 SQLite 变量上限）；
     * 2) 清理后 FTS 行数仍与 chunk 数不一致（缺行/重复）→ [rebuildFtsIndex] 全量重建兜底
     *    （FTS 是 chunk.content 的纯派生数据，重建无损）；
     * 3) 失效检索矩阵缓存，返回 before/after 报告。
     */
    suspend fun repairIndex(): IndexRepair = withContext(Dispatchers.IO) {
        val before = indexHealthCheck()
        db.withTransaction {
            for (batch in before.orphanFtsChunkIds.chunked(SQL_VARIABLE_BATCH)) {
                db.chunkDao().deleteFtsByChunkIds(batch)
            }
            for (batch in before.orphanEmbeddingChunkIds.chunked(SQL_VARIABLE_BATCH)) {
                db.embeddingDao().deleteEmbeddingsByChunkIds(batch)
            }
        }
        val afterPurge = indexHealthCheck()
        val needFtsRebuild = afterPurge.ftsCount != afterPurge.chunkCount ||
            afterPurge.orphanFtsChunkIds.isNotEmpty()
        if (needFtsRebuild) rebuildFtsIndex()
        VectorMatrixCache.invalidate()
        val after = if (needFtsRebuild) indexHealthCheck() else afterPurge
        IndexRepair(
            rebuiltFts = needFtsRebuild,
            removedFtsRows = (before.ftsCount - after.ftsCount).coerceAtLeast(0),
            removedEmbeddingRows = (before.embeddingCount - after.embeddingCount).coerceAtLeast(0),
            before = before,
            after = after,
        )
    }

    private fun findModel(): File? = findModelIn(context)

    private fun toBlob(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in v) putFloat(f)
        }.array()

    /**
     * 流式 SHA-256（64KB 分块）：P0-2 不再整文件 readBytes 进内存。
     * B4-9：唯一实现收敛到 [Sha256]（保留本私有方法作为导入去重的调用点）。
     */
    private fun sha256HexFile(file: File): String = Sha256.hex(file)

    /**
     * 确保唯一导入 Worker 会执行（P1-4）：
     * 使用 `APPEND_OR_REPLACE` —— 若现有 worker 正在跑（含"刚查完 queued 准备退出"的窗口），
     * 新请求追加到链尾保证被消费；否则替换为新的 OneTimeWork。旧实现 KEEP 在该窗口会
     * 丢弃入队，导致 job 永久 queued。多余的链尾 worker 立即退出，成本可忽略。
     */
    private suspend fun ensureIngestWorker() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                INGEST_BATCH_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<IngestBatchWorker>().build(),
            )
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /**
         * EGemma2-440M 通用 CPU 的存量空间 id（B7 后仍保留：既有用户库/向量继续可检索；
         * 其余空间按 `provider/model/raw-symmetric-v1[-accel][@rev]` 规则生成）。
         * granite 的 SPACE_ID_B 已随路线下线删除（B7），旧空间由 [legacySpacesReport] 报告。
         */
        const val SPACE_ID = "mp-universal/egemma2-440m/app-prefix-v1"

        /**
         * B7：当前受支持的 embedding provider。EGemma2 三强度 + SM8750 NPU 变体：
         * - "mediapipe-universal"：路径 A（UniversalEmbedder：440M/740M/特化版）；
         * - "litertlm-engine"：路径 B（litertlm EmbeddingEngine：270M）。
         * 字符串与各 Provider 的 PROVIDER_ID 常量保持一致（本类不反向 import embedding 层，
         * 避免 ingest→embedding→ingest 循环依赖；BackupValidator/备份/UI 共用 [isSupportedSpace]）。
         */
        val SUPPORTED_EMBEDDING_PROVIDERS: Set<String> = setOf("mediapipe-universal", "litertlm-engine")

        /** B7：受支持模型 modelId 必须包含的家族标记（忽略大小写） */
        const val SUPPORTED_MODEL_MARKER = "embeddinggemma-2"

        /**
         * B7 纯函数：空间是否属于当前支持的 EGemma2 路线（备份导出过滤 / 导入拒绝 / 旧空间报表共用）。
         * 不支持的旧路线（granite / Path C `embedding_gemma.task` / LFM 等）返回 false。
         */
        fun isSupportedSpace(providerId: String, modelId: String): Boolean =
            providerId in SUPPORTED_EMBEDDING_PROVIDERS &&
                modelId.contains(SUPPORTED_MODEL_MARKER, ignoreCase = true)

        const val ROLE_CONTENT = "content"
        const val EMBED_BATCH = 16
        const val INGEST_BATCH_WORK = "ingest-batch"

        /** P1-2 自动重试上限：第 N 次失败后 attempt=N，N<MAX 自动重排队，达到上限转终态 failed */
        const val MAX_ATTEMPTS = 3

        /** B2-8 健康检查分页大小（峰值内存 ≈ 一页 chunk/vector 行） */
        private const val HEALTH_PAGE_SIZE = 500

        /** B2-8 批量删除 chunkId 列表上限（SQLite 变量上限 999，留余量） */
        private const val SQL_VARIABLE_BATCH = 400

        /** B2-8 纯函数：三表计数/孤儿 → 可读原因列表（空 = 健康；单测覆盖） */
        internal fun buildIndexHealthReasons(
            chunkCount: Int,
            ftsCount: Int,
            embeddingCount: Int,
            orphanFtsChunkIds: List<Long>,
            orphanEmbeddingChunkIds: List<Long>,
        ): List<String> {
            val reasons = ArrayList<String>(3)
            if (ftsCount != chunkCount) {
                reasons += "FTS 行数 $ftsCount ≠ chunk 数 $chunkCount（缺行/重复/孤儿，需重建或清理）"
            }
            if (orphanFtsChunkIds.isNotEmpty()) {
                reasons += "FTS 孤儿 ${orphanFtsChunkIds.size} 行（chunk 已删除）"
            }
            if (orphanEmbeddingChunkIds.isNotEmpty()) {
                reasons += "向量孤儿 ${orphanEmbeddingChunkIds.size} 行（chunk 已删除）"
            }
            return reasons
        }

        private val stagingBusy = AtomicBoolean(false)

        /**
         * B1-6 重复导入判定集合：pending/processing/ready = 内容已在库或正在入库；
         * cancelled/failed 允许重导（用户重试意图）。
         */
        val DEDUP_DOC_STATUSES: Set<DocStatus> = setOf(
            DocStatus.PENDING,
            DocStatus.PROCESSING,
            DocStatus.READY,
        )

        /** B1-6 纯函数：给定文档状态是否视为"重复导入"（单测覆盖） */
        fun isDuplicateDocumentStatus(status: DocStatus?): Boolean =
            status != null && status in DEDUP_DOC_STATUSES

        /**
         * B1-1：FTS 写入前按 chunkId 去重，保留首次出现顺序。纯函数（单测覆盖）。
         *
         * 定位：真正的防重是写入前 deleteDerivedRows/deleteAllFts；本函数是**防御性兜底**——
         * 正常输入来自自增主键天然不重复，但重建/并发/异常路径可能出现重复行（审查 S4）。
         */
        fun dedupeFtsByChunkId(rows: List<ChunkFtsEntity>): List<ChunkFtsEntity> =
            if (rows.size <= 1) rows else rows.distinctBy { it.chunkId }

        /**
         * 路径 A 判定（历史白名单口径）：仅 440M（含 SM8750 特化版；特化版在 createProvider 先按
         * SOC 分流）。740M 虽然同样走 UE 通道，但不在 AUTO GPU 白名单内（G1E 只在显式基准/
         * 偏好路径验证过），因此这里保持 false，避免 B7 顺手改变现有 GPU auto 策略。
         */
        fun isPathAModel(name: String): Boolean = name.contains("440m")

        /**
         * 路径 B 引擎（litertlm EmbeddingEngine）：B7 后**仅 EGemma2-270M**。
         * Granite/LFM2.5 路线已随技术栈收敛下线（见 ModelCatalog B7 注释）；740M 走 UE 通道
         * （G1E 实测 GPU 全绿），不属于路径 B。
         */
        fun isPathBModel(name: String): Boolean = name.contains("270m")

        /**
         * P1-2 失败决策（纯函数，单测覆盖）：attempt 为已累加后的尝试次数。
         * true=可自动重排队；false=终态 failed（由 UI 手动重试）。
         */
        fun shouldAutoRetry(attemptAfterIncrement: Int): Boolean = attemptAfterIncrement < MAX_ATTEMPTS

        /**
         * 模型选择（EmbeddingRuntime.findModel 与导入共用）：尊重用户路径偏好
         * （PathPreferences，W3-3），偏好缺失回退 auto 顺序；显式名单匹配——
         * 不给字母序默认值留位置（270M 结构不支持曾四次踩中同类坑）。
         */
        fun findModelIn(context: Context): File? =
            com.zhibox.memory.core.model.PathPreferences.resolve(context)
    }
}
