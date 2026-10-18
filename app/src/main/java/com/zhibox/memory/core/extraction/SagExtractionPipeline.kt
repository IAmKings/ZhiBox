package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.room.withTransaction
import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.extract.EventEntityExtractor
import com.zhibox.memory.core.extract.ExtractedEntity
import com.zhibox.memory.core.extract.ExtractedEvent
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.extract.ExtractionInput
import com.zhibox.memory.core.extract.ExtractionPrompts
import com.zhibox.memory.core.extract.ExtractionResult
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.sag.SagVectorRole
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EntityFtsEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventFtsEntity
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import kotlinx.coroutines.CancellationException

/**
 * B9：SAG 逐 chunk 抽取流水线（Worker 与测试共用的核心实现）。
 *
 * 单个 job 的完整生命周期：
 * ```
 * QUEUED --claim--> RUNNING --extract--> 校验 --embed--> 单事务写入 --> DONE
 *                          \--可重试失败 (attempt<3)--> QUEUED（下次重试）
 *                          \--终态失败/输出无效--> FAILED
 *                          \--SAG 不可用--> NOT_INDEXED（文档仍 ready，等待 backfill）
 * ```
 *
 * 一致性约束：
 * - **先向量后事务**：event/entity 向量在事务外计算（远程 LLM/端侧编码耗时不占 DB 锁），
 *   随后在**一个事务**内写 event/entity/event_entity/FTS/sag_vector 并把 job 置 DONE；
 * - **supersede 语义**：同一 chunk 新 event 写入后旧 active event 置 superseded/supersededBy，
 *   并清理旧 event 的 FTS 与 sag_vector（旧 event_entity 作为历史保留）；
 * - **取消/进程被杀**：Worker 启动 [recoverInterrupted]（RUNNING→QUEUED）；事务内再校验 job 仍
 *   RUNNING，避免已取消任务写回数据；CancellationException 一律向上抛；
 * - **单 chunk 隔离**：任何失败只影响当前 job，不抛出到 Worker 循环之外（取消除外）；
 * - **隐私**：errorCode/errorDetail 不含 API Key / chunk 正文（[SagExtractionSupport.safeErrorDetail]）。
 */
class SagExtractionPipeline(
    context: Context,
    private val db: AppDatabase,
    private val extractor: EventEntityExtractor,
    private val embeddings: SagEmbeddingGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val appContext: Context = context.applicationContext

    /**
     * 写入 event.modelId 的 LLM 模型名（质量追溯）。Keystore 配置读取失败不阻塞抽取：置空串。
     * 只在真正写入 event 时求值一次（懒加载）。
     */
    private val modelId: String by lazy {
        runCatching { KeystoreLlmConfigStore(appContext).load()?.model }.getOrNull().orEmpty()
    }

    /**
     * 进程被杀/Worker 被停止后回收中断任务：RUNNING → QUEUED（幂等）。
     * @return 被回收的任务数
     */
    suspend fun recoverInterrupted(): Int =
        db.extractionJobDao().requeueInterrupted(
            fromStatuses = listOf(ExtractionJobStatus.RUNNING.value),
            toStatus = ExtractionJobStatus.QUEUED.value,
            now = now(),
        )

    /**
     * 领取并处理一个排队任务。
     * @return true=处理了一个任务（含失败/重排队/未索引）；false=队列为空或任务不可领取
     */
    suspend fun processNext(): Boolean {
        val next = db.extractionJobDao()
            .nextQueued(ExtractionJobStatus.QUEUED.value, 1)
            .firstOrNull() ?: return false
        return processJob(next.id)
    }

    /**
     * 处理指定任务（先原子 claim QUEUED→RUNNING，再执行）。
     *
     * @return true=领取成功并已处理（DONE/FAILED/重排队/NOT_INDEXED）；false=不可领取（不存在、
     *         非 QUEUED、被取消或 claim 竞争失败）
     */
    suspend fun processJob(jobId: String): Boolean {
        val job = db.extractionJobDao().get(jobId) ?: return false
        if (ExtractionJobStatus.fromValue(job.status) != ExtractionJobStatus.QUEUED) return false

        // 可用性检查在 claim 之前：不可用时直接置 not_indexed（文档保持 ready，后续 backfill 恢复）
        if (!SagAvailability.isAvailable(appContext)) {
            db.extractionJobDao().markNotIndexed(
                id = job.id,
                notIndexedStatus = ExtractionJobStatus.NOT_INDEXED.value,
                errorCode = ExtractionErrorCodes.NOT_CONFIGURED,
                errorDetail = SagExtractionSupport.NOT_INDEXED_DETAIL,
                now = now(),
            )
            return true
        }

        if (db.extractionJobDao().claim(
                id = job.id,
                fromStatus = ExtractionJobStatus.QUEUED.value,
                toStatus = ExtractionJobStatus.RUNNING.value,
                now = now(),
            ) == 0
        ) {
            return false // 已被其它执行者领取/状态已变
        }
        // claim 原子地把 attempt+1；重试上限按"本次执行"计数
        val attemptAfterClaim = job.attempt + 1

        val chunk = db.chunkDao().chunksByIds(listOf(job.chunkId)).firstOrNull()
            ?: return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = SagExtractionErrorCodes.CHUNK_MISSING,
                detail = "chunk 不存在（可能已被重导入清理）",
            )
        val document = db.documentDao().get(chunk.documentId)
        if (document == null) {
            return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = SagExtractionErrorCodes.DOCUMENT_NOT_READY,
                detail = "文档不存在或已被删除",
            )
        }
        if (DocStatus.fromValue(document.status) != DocStatus.READY) {
            // 文档已不再 ready（取消导入/重处理）：整篇抽取任务统一取消，不写任何 SAG 数据
            db.extractionJobDao().cancelByDocument(
                documentId = document.id,
                cancelledStatus = ExtractionJobStatus.CANCELLED.value,
                now = now(),
            )
            return false
        }

        val input = ExtractionInput(
            chunkId = chunk.id,
            content = chunk.content,
            sourceTitle = document.title,
            previousContext = previousContext(document.id, chunk),
            entityTypes = EntityType.ALL,
        )
        val extraction = try {
            extractor.extract(input)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // 抽取器契约要求失败以值返回；这里兜底未预期异常（不向 Worker 循环抛业务异常）
            ExtractionResult.Failure(
                SagExtractionErrorCodes.INTERNAL,
                "抽取器未预期异常（${e.javaClass.simpleName}）",
            )
        }

        return when (extraction) {
            is ExtractionResult.Failure -> handleExtractionFailure(job.id, attemptAfterClaim, extraction)
            is ExtractionResult.Success -> handleExtractionSuccess(job, chunk, document, extraction.event, attemptAfterClaim)
        }
    }

    // ─────────────────────────── 成功路径 ───────────────────────────

    private suspend fun handleExtractionSuccess(
        job: ExtractionJobEntity,
        chunk: ChunkEntity,
        document: DocumentEntity,
        event: ExtractedEvent,
        attemptAfterClaim: Int,
    ): Boolean {
        // 1) 业务校验（ExtractionJson 已做 11 类/字段/单 event 严格解析，这里补业务语义校验）
        val invalidReason = SagExtractionSupport.invalidReason(event)
        if (invalidReason != null) {
            return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = SagExtractionErrorCodes.INVALID_OUTPUT,
                detail = invalidReason,
            )
        }
        val entities = SagExtractionSupport.prepareEntities(event.entities)
        if (entities.isEmpty()) {
            return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = SagExtractionErrorCodes.INVALID_OUTPUT,
                detail = "抽取结果没有有效实体（PRD：每 event 至少 1 个实体）",
            )
        }
        val eventText = SagExtractionSupport.eventEmbeddingText(event)
        if (eventText.isBlank()) {
            return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = SagExtractionErrorCodes.INVALID_OUTPUT,
                detail = "抽取结果缺少可嵌入文本（content/summary/title 均为空）",
            )
        }

        // 2) 先算向量（事务外，避免长耗占 DB 事务）
        val bundle = when (val embedding = embedVectors(eventText, entities)) {
            is EmbeddingBundleResult.Failure -> return failOrRetry(
                jobId = job.id,
                attemptAfterClaim = attemptAfterClaim,
                code = embedding.code,
                detail = embedding.detail,
            )
            is EmbeddingBundleResult.Success -> embedding.bundle
        }

        // 3) 单事务写入 + job done（与派生数据原子）
        return db.withTransaction {
            writeExtraction(
                job = job,
                chunk = chunk,
                event = event,
                entities = entities,
                eventVector = bundle.eventVector,
                entityVectors = bundle.entityVectors,
                sourceDocumentId = document.id,
            )
        }
    }

    /** 向量组合（event 1 条 + entity N 条），事务外计算后一次性带入写入。 */
    private data class EmbeddingBundle(
        val eventVector: SagEmbeddedVector,
        val entityVectors: List<SagEmbeddedVector>,
    )

    private sealed interface EmbeddingBundleResult {
        data class Success(val bundle: EmbeddingBundle) : EmbeddingBundleResult

        data class Failure(val code: String, val detail: String) : EmbeddingBundleResult
    }

    /**
     * 事务外计算 event/entity 向量；失败以 [EmbeddingBundleResult.Failure] 返回（含可重试分类码），
     * CancellationException 原样向上抛（协程取消语义）。
     */
    private suspend fun embedVectors(
        eventText: String,
        entities: List<ExtractedEntity>,
    ): EmbeddingBundleResult = try {
        val eventVector = embeddings.embed(SagVectorRole.EVENT_CONTENT, listOf(eventText)).firstOrNull()
            ?: return EmbeddingBundleResult.Failure(
                SagExtractionErrorCodes.EMBEDDING_FAILED,
                "SAG event 向量数量不匹配（期望 1）",
            )
        val entityVectors = if (entities.isEmpty()) {
            emptyList()
        } else {
            embeddings.embed(
                SagVectorRole.ENTITY_DESCRIPTION,
                entities.map { SagExtractionSupport.entityEmbeddingText(it) },
            )
        }
        if (entityVectors.size != entities.size) {
            EmbeddingBundleResult.Failure(
                SagExtractionErrorCodes.EMBEDDING_FAILED,
                "SAG entity 向量数量不匹配（期望 ${entities.size}，实际 ${entityVectors.size}）",
            )
        } else {
            EmbeddingBundleResult.Success(EmbeddingBundle(eventVector, entityVectors))
        }
    } catch (c: CancellationException) {
        throw c
    } catch (e: SagEmbeddingException) {
        EmbeddingBundleResult.Failure(e.code, e.message ?: "SAG 向量生成失败")
    } catch (e: Exception) {
        EmbeddingBundleResult.Failure(
            SagExtractionErrorCodes.EMBEDDING_FAILED,
            "SAG 向量生成失败（${e.javaClass.simpleName}）",
        )
    }

    /**
     * 事务内写入（event → supersede 旧 active → event FTS/向量 → entity/FTS/向量 → job done）。
     * 入参向量已在事务外算好。
     *
     * @return true=已提交；false=job 已非 RUNNING（被取消/抢占），事务内未写任何数据
     */
    private suspend fun writeExtraction(
        job: ExtractionJobEntity,
        chunk: ChunkEntity,
        event: ExtractedEvent,
        entities: List<ExtractedEntity>,
        eventVector: SagEmbeddedVector,
        entityVectors: List<SagEmbeddedVector>,
        sourceDocumentId: String,
    ): Boolean {
        val jobDao = db.extractionJobDao()
        // 取消守卫：抽取/编码期间可能被用户取消（cancelByDocument → CANCELLED）或 Worker 停止
        val current = jobDao.get(job.id) ?: return false
        if (ExtractionJobStatus.fromValue(current.status) != ExtractionJobStatus.RUNNING) return false
        // 文档守卫：重导入/取消后不再写入（chunk 通常已被级联删除，此处防御）
        if (db.documentDao().get(sourceDocumentId)?.let { DocStatus.fromValue(it.status) } != DocStatus.READY) {
            return false
        }

        val ts = now()
        val eventDao = db.eventDao()

        // 幂等防御：同 chunk + 同 extractionVersion 已存在（异常重放）→ 直接置 done，不重复写
        if (eventDao.byChunkAndExtractionVersion(chunk.id, job.id) != null) {
            jobDao.markDone(job.id, ExtractionJobStatus.DONE.value, ts)
            return true
        }

        // 旧 active event（supersede 目标）：先取再写新行
        val oldActiveEvents = eventDao.byChunk(chunk.id).filter { it.active }

        val eventId = eventDao.upsert(
            EventEntity(
                chunkId = chunk.id,
                canonicalText = SagExtractionSupport.canonicalEventText(event),
                confidence = if (event.isValid) 1.0 else 0.0,
                title = event.title.trim(),
                summary = event.summary.trim(),
                content = event.content.trim(),
                language = event.language.trim().ifBlank { "und" },
                status = EventStatus.ACTIVE.value,
                extractorVersion = ExtractionPrompts.EXTRACTOR_VERSION,
                extractionVersion = job.id,
                modelId = modelId,
                promptVersion = ExtractionPrompts.VERSION,
                active = true,
                supersededBy = null,
                createdAt = ts,
            ),
        )

        // 旧 active → superseded，并清理其派生数据（FTS/向量；event_entity 作为历史保留）
        eventDao.supersedeOthers(chunk.id, eventId, EventStatus.SUPERSEDED.value)
        for (old in oldActiveEvents) {
            eventDao.deleteFtsByEvent(old.id)
            db.sagVectorDao().deleteByOwner(SagOwnerType.EVENT.value, old.id)
        }

        // 新 event FTS（title+summary+content，bigram 口径与检索一致）
        eventDao.deleteFtsByEvent(eventId)
        eventDao.insertFts(
            EventFtsEntity(
                eventId = eventId,
                text = SagExtractionSupport.toFtsText(SagExtractionSupport.eventFtsText(event)),
            ),
        )
        // 新 event 向量
        db.sagVectorDao().upsert(
            sagVector(SagOwnerType.EVENT, eventId, SagVectorRole.EVENT_CONTENT, eventVector, ts),
        )

        // 实体：normalizedName 去重复用 → event_entity 关联 → entity FTS → entity 向量
        entities.forEachIndexed { index, extracted ->
            val vector = entityVectors[index]
            val normalizedName = SagExtractionSupport.normalizeEntityName(extracted.name)
            var row = db.entityDao().byNormalizedName(normalizedName)
            if (row == null) {
                val insertedId = db.entityDao().insertIgnore(
                    EntityEntity(
                        normalizedName = normalizedName,
                        entityType = extracted.type.wire,
                        name = extracted.name.trim(),
                        description = extracted.description.trim(),
                        createdAt = ts,
                    ),
                )
                row = if (insertedId > 0) {
                    db.entityDao().byId(insertedId)
                } else {
                    db.entityDao().byNormalizedName(normalizedName)
                }
                if (row == null) return@forEachIndexed // 防御：并发插入后回查失败，跳过该实体（不阻塞事件）
            } else {
                // 跨文档复用：只补空字段（保留首见名称/描述，避免无界增长）
                val merged = row.copy(
                    entityType = row.entityType ?: extracted.type.wire,
                    name = row.name.ifBlank { extracted.name.trim() },
                    description = row.description.ifBlank { extracted.description.trim() },
                )
                if (merged != row) {
                    db.entityDao().update(merged)
                    row = merged
                }
            }
            val entityRow = row ?: return@forEachIndexed

            db.eventEntityDao().upsert(
                EventEntityRel(
                    eventId = eventId,
                    entityId = entityRow.id,
                    role = extracted.type.wire,
                    weight = ENTITY_WEIGHT,
                    description = extracted.description.trim().ifBlank { null },
                ),
            )
            // entity FTS：文本口径固定为 name+description，重写保证与主表一致
            db.entityDao().deleteFtsByEntity(entityRow.id)
            db.entityDao().insertFts(
                EntityFtsEntity(
                    entityId = entityRow.id,
                    text = SagExtractionSupport.toFtsText(
                        SagExtractionSupport.entityFtsText(entityRow.name, entityRow.description),
                    ),
                ),
            )
            db.sagVectorDao().upsert(
                sagVector(SagOwnerType.ENTITY, entityRow.id, SagVectorRole.ENTITY_DESCRIPTION, vector, ts),
            )
        }

        jobDao.markDone(job.id, ExtractionJobStatus.DONE.value, ts)
        return true
    }

    /** 取消守卫：worker 失败收尾前重新读取状态，避免覆盖用户取消。 */
    private suspend fun isJobCancelled(jobId: String): Boolean =
        ExtractionJobStatus.fromValue(db.extractionJobDao().get(jobId)?.status) ==
            ExtractionJobStatus.CANCELLED

    // ─────────────────────────── 失败路径 ───────────────────────────

    private suspend fun handleExtractionFailure(
        jobId: String,
        attemptAfterClaim: Int,
        failure: ExtractionResult.Failure,
    ): Boolean {
        // 取消竞态守卫：用户已取消时不要被失败收尾改回 queued/failed
        if (isJobCancelled(jobId)) return true
        // 未配置/禁网/配置存储损坏：属于"暂不可用"而非数据错误 → not_indexed，等待 backfill
        if (SagErrorClassifier.isNotIndexed(failure.code)) {
            db.extractionJobDao().markNotIndexed(
                id = jobId,
                notIndexedStatus = ExtractionJobStatus.NOT_INDEXED.value,
                errorCode = failure.code,
                errorDetail = "远程 LLM 未配置或不可用（禁网/配置损坏），等待配置后补抽取（文档仍可离线检索）",
                now = now(),
            )
            return true
        }
        val detail = SagExtractionSupport.safeErrorDetail(
            code = failure.code,
            message = failure.message,
            fallback = "抽取失败",
        )
        return failOrRetry(jobId, attemptAfterClaim, failure.code, detail)
    }

    /**
     * 统一失败收尾：可重试且未达上限 → QUEUED（保留 attempt 供下次判定）；否则终态 FAILED。
     * @return 恒为 true（任务已处理，Worker 可继续下一篇）
     */
    private suspend fun failOrRetry(
        jobId: String,
        attemptAfterClaim: Int,
        code: String,
        detail: String,
    ): Boolean {
        val dao = db.extractionJobDao()
        if (isJobCancelled(jobId)) return true
        return if (SagExtractionSupport.shouldRetry(attemptAfterClaim, code)) {
            dao.requeue(jobId, ExtractionJobStatus.QUEUED.value, now())
            true
        } else {
            dao.markFailed(
                id = jobId,
                failedStatus = ExtractionJobStatus.FAILED.value,
                errorCode = code,
                errorDetail = detail.take(SagExtractionSupport.MAX_ERROR_DETAIL_LENGTH),
                now = now(),
            )
            true
        }
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 上一 chunk 尾部 ≤200 字作为断句/代词消解上下文（不用于抽取事件内容本身） */
    private suspend fun previousContext(documentId: String, chunk: ChunkEntity): String? {
        if (chunk.ordinal <= 0) return null
        val previous = db.chunkDao().byDocument(documentId)
            .firstOrNull { it.ordinal == chunk.ordinal - 1 }
            ?: return null
        return previous.content.takeLast(PREVIOUS_CONTEXT_CHARS).takeIf { it.isNotBlank() }
    }

    private fun sagVector(
        ownerType: SagOwnerType,
        ownerId: Long,
        role: SagVectorRole,
        vector: SagEmbeddedVector,
        createdAt: Long,
    ): SagVectorEntity = SagVectorEntity(
        ownerType = ownerType.value,
        ownerId = ownerId,
        role = role.wire,
        spaceId = vector.spaceId,
        dimensions = vector.dimensions,
        vectorBlob = vector.vectorBlob,
        vectorNorm = vector.vectorNorm,
        createdAt = createdAt,
    )

    private companion object {
        /** previousContext 最大字符数（B8 契约：≤200 字） */
        const val PREVIOUS_CONTEXT_CHARS: Int = 200

        /** event_entity 关联强度：LLM 每 chunk 只输出一个核心事件，关联实体按核心权重记 1.0 */
        const val ENTITY_WEIGHT: Double = 1.0
    }
}
