package com.zhibox.memory.core.sag

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.QueryTraceEntity
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * B10：SAG 检索器（三模式 + 双路径 + 降级 + 轨迹）。
 *
 * 模式：
 * - [RetrievalMode.VECTOR]：复用 [SearchService]（向量+关键词+RRF）作为基线；
 * - [RetrievalMode.MULTI_ES_FAST]（离线，零网络）：查询向量 → 事件向量 seed（τ=0.4）+ 查询实体
 *   词法 seed → **多跳桥接**（seed event 的关联实体并入扩展种子）→ `event_entity` 1 跳扩展 →
 *   确定性融合 → event→chunk 去重 → 语义补位到 kOut；
 * - [RetrievalMode.MULTI]：fast 候选之上叠加 LLM 查询实体抽取 / LLM 上下文选择；
 *   `llmAvailable()` 为 false 时直接拒绝（不发起任何 LLM/网络请求）。
 *
 * 降级（[SagResult.degraded] / [SagTrace.degradedReason]）：
 * - 事件层不可用（无嵌入模型/查询向量失败）或事件层无数据（事件向量空间为空）→ 降级为 vector；
 * - LLM 实体抽取/选择失败或为空 → 回退离线词法/确定性排序（前缀 `LLM 回退：`）；
 * - 实体扩展超 frontier → SQL/合并截断并在 trace 标注（前缀 `预算截断：`，不算能力降级）；
 * - 内部异常 → 空结果 + refused + `内部错误：`（不含正文）。
 *
 * 一致性：
 * - 只返回 `document.status=ready` 的 chunk（与 SearchService 一致）；
 * - `sourceIds` 非空时事件层与语义补位均按 `document.sourceId` 过滤；
 * - [SagHit.channels] 使用 [SagChannels] **固定值**（vector/keyword/event/entity/semantic/llm）；
 *   event→chunk 按 chunkId 去重；
 * - **每次 retrieve 同步写 query_trace**（成功/refused/degraded/内部异常均写；queryHash 不落正文，
 *   keepRecent(50)）；CancellationException 直接上抛（取消不是完成的检索）。
 *
 * trace 阶段名固定（Lead/测试断言口径）：[STAGE_NORMALIZE] / [STAGE_VECTOR_SEEDS] /
 * [STAGE_ENTITY_SEEDS] / [STAGE_EXPAND] / [STAGE_CANDIDATES] / [STAGE_SELECT] /
 * [STAGE_SUPPLEMENT] / [STAGE_TRACE]。
 *
 * 线程边界：公开入口整体 `withContext(Dispatchers.Default)`；嵌入/检索内部各自切线程。
 */
class SagRetriever(
    context: Context,
    private val db: AppDatabase,
    private val searchService: SearchService = SearchService(context),
    private val lexicalExtractor: QueryEntityExtractor = LexicalQueryEntityExtractor(db),
    private val selector: SagContextSelector = DeterministicSagSelector(),
    private val llmAvailable: () -> Boolean = { false },
    private val llmExtractor: QueryEntityExtractor? = null,
    private val llmSelector: SagContextSelector? = null,
) {

    private val appContext: Context = context.applicationContext

    // ───────── 测试注入缝（internal；默认 null=生产路径；公开构造/语义不变） ─────────

    /** 查询向量网关：null=走 [EmbeddingRuntime]；返回 null=事件层不可用（触发降级）。 */
    @VisibleForTesting
    internal var queryEmbeddingGateway: QueryEmbeddingGateway? = null

    /**
     * 事件向量派生空间 id（`SagVectorSpace.spaceIdFor(base, EVENT_CONTENT)`）。
     * 无模型资产的 instrumentation 测试注入 [queryEmbeddingGateway] 时需同时设置本值；
     * 未设置则事件层视为不可用（走 vector 降级）。
     */
    @VisibleForTesting
    internal var eventVectorSpaceForTests: String? = null

    /** 向量补位/vector 模式网关：null=使用构造传入的 [SearchService]。 */
    @VisibleForTesting
    internal var vectorSupplementGateway: VectorSupplementGateway? = null

    suspend fun retrieve(request: SagQuery): SagResult = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val stages = ArrayList<SagTraceStage>()

        val normalizeStart = System.currentTimeMillis()
        val query = SagSupport.normalizeQuery(request.query)
        stages += stage(STAGE_NORMALIZE, if (query.isEmpty()) 0 else 1, normalizeStart)

        val outcome: Outcome = try {
            doRetrieve(query, request, stages)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Outcome(
                refused = "检索失败：${e.javaClass.simpleName}",
                degraded = true,
                degradedReason = "内部错误：${e.javaClass.simpleName}（已返回空结果）",
                effectiveMode = RetrievalMode.VECTOR,
            )
        }

        val baseTrace = SagTrace(
            mode = request.mode.wire,
            queryHash = SagSupport.queryHash(query),
            stages = stages.toList(),
            seedEvents = outcome.seedEvents,
            seedEntities = outcome.seedEntities,
            expandedEvents = outcome.expandedEvents,
            candidates = outcome.candidates,
            selected = outcome.hits.size,
            degradedReason = outcome.degradedReason,
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
        )
        // 同步写 trace（refused/degraded/异常都写）。trace 阶段在写完后追加到内存 trace：
        // 持久化 JSON 在写入前构建（避免自引用），写入耗时体现在 elapsedMs；测试可从返回的
        // SagResult.trace 读到 trace 阶段（count=1/ms=写入耗时）。
        val traceWriteStart = System.currentTimeMillis()
        writeTrace(baseTrace)
        val traceWriteMs = (System.currentTimeMillis() - traceWriteStart).coerceAtLeast(0)
        stages += SagTraceStage(STAGE_TRACE, 1, traceWriteMs)
        val trace = baseTrace.copy(
            stages = stages.toList(),
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
        )

        val effectiveMode = outcome.effectiveMode
        SagResult(
            query = query,
            mode = request.mode,
            hits = outcome.hits,
            trace = trace,
            refused = outcome.refused,
            degraded = outcome.degraded || effectiveMode != request.mode,
            effectiveMode = effectiveMode,
        )
    }

    // ───────────────────────────── 模式分发 ─────────────────────────────

    private suspend fun doRetrieve(query: String, request: SagQuery, stages: MutableList<SagTraceStage>): Outcome {
        if (query.isEmpty()) {
            return Outcome(refused = EMPTY_QUERY_REASON, effectiveMode = RetrievalMode.VECTOR)
        }
        return when (request.mode) {
            RetrievalMode.VECTOR -> retrieveVector(query, request, stages)
            RetrievalMode.MULTI_ES_FAST -> retrieveFast(query, request, stages)
            RetrievalMode.MULTI -> retrieveMulti(query, request, stages)
        }
    }

    private suspend fun retrieveVector(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
    ): Outcome {
        val t = System.currentTimeMillis()
        val supplement = runSupplement(query, request.budget.kPool, emptySet(), request.sourceIds)
        stages += stage(STAGE_SUPPLEMENT, supplement.hits.size, t)
        return Outcome(
            hits = supplement.hits.take(outputLimit(request)).map { mapSearchHit(it) },
            refused = supplement.refused,
            candidates = supplement.hits.size,
            effectiveMode = RetrievalMode.VECTOR,
        )
    }

    private suspend fun retrieveFast(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
    ): Outcome {
        val seedStart = System.currentTimeMillis()
        val entitySeeds = safeSuspend { lexicalExtractor.extract(query) }.orEmpty()
            .take(request.budget.kSeed)
        stages += stage(STAGE_ENTITY_SEEDS, entitySeeds.size, seedStart)

        return when (val layer = buildEventLayer(query, request, stages, entitySeeds)) {
            is EventLayer.Unavailable -> fallbackToVector(query, request, stages, layer.reason)
            is EventLayer.Available -> finishEventMode(
                query = query,
                request = request,
                stages = stages,
                layer = layer,
                activeSelector = selector,
                allowLlmChannel = false,
                multi = false,
                extraReason = null,
                degradedByLlmFallback = false,
            )
        }
    }

    private suspend fun retrieveMulti(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
    ): Outcome {
        if (!llmAvailable()) {
            // 隐私/安全门禁：未开启 SAG+联网时绝不进入任何 LLM 路径
            return Outcome(refused = MULTI_REFUSED_REASON, effectiveMode = RetrievalMode.VECTOR)
        }

        // 1) 查询实体：优先 LLM；失败/为空回退离线词法
        val seedStart = System.currentTimeMillis()
        var llmExtractorFallback = false
        val seeds: List<QueryEntity>
        if (llmExtractor != null) {
            val llmSeeds = safeSuspend { llmExtractor.extract(query) }
            if (llmSeeds.isNullOrEmpty()) {
                llmExtractorFallback = true
                seeds = safeSuspend { lexicalExtractor.extract(query) }.orEmpty()
                stages += stage(STAGE_ENTITY_SEEDS, 0, seedStart)
                stages += stage("llmFallback", seeds.size, seedStart)
            } else {
                seeds = llmSeeds
                stages += stage(STAGE_ENTITY_SEEDS, llmSeeds.size, seedStart)
            }
        } else {
            seeds = safeSuspend { lexicalExtractor.extract(query) }.orEmpty()
            stages += stage(STAGE_ENTITY_SEEDS, seeds.size, seedStart)
        }
        val entitySeeds = seeds.take(request.budget.kSeed)

        // 2) 事件层候选 + LLM/确定性选择 + 语义补位
        return when (val layer = buildEventLayer(query, request, stages, entitySeeds)) {
            is EventLayer.Unavailable -> fallbackToVector(query, request, stages, layer.reason)
            is EventLayer.Available -> finishEventMode(
                query = query,
                request = request,
                stages = stages,
                layer = layer,
                activeSelector = llmSelector ?: selector,
                allowLlmChannel = llmSelector != null,
                multi = true,
                extraReason = if (llmExtractorFallback) {
                    "LLM 回退：查询实体抽取失败或为空，已使用离线词法"
                } else {
                    null
                },
                degradedByLlmFallback = llmExtractorFallback,
            )
        }
    }

    /** 事件层候选 → 选择器 → event→chunk 映射 → 语义补位（fast / multi 共用收尾）。 */
    private suspend fun finishEventMode(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
        layer: EventLayer.Available,
        activeSelector: SagContextSelector,
        allowLlmChannel: Boolean,
        multi: Boolean,
        extraReason: String?,
        degradedByLlmFallback: Boolean,
    ): Outcome {
        val selectStart = System.currentTimeMillis()
        val selection = applySelection(query, request, layer.candidates, activeSelector, allowLlmChannel)
        stages += stage(STAGE_SELECT, selection.hits.size, selectStart)
        if (selection.hits.isEmpty()) {
            return fallbackToVector(
                query, request, stages,
                "事件层无数据：选择结果为空（候选 ${layer.candidates.size}）",
            )
        }

        val outLimit = outputLimit(request)
        val seen = LinkedHashSet<Long>(outLimit)
        val result = ArrayList<SagHit>(outLimit)
        for (hit in selection.hits) {
            if (result.size >= outLimit) break
            if (!seen.add(hit.chunkId)) continue
            result += if (multi && selection.llmApplied) {
                hit.copy(channels = hit.channels + SagChannels.LLM_SELECTED)
            } else {
                hit
            }
        }

        var fillDegraded = false
        var fillReason: String? = null
        var added = 0
        val fillStart = System.currentTimeMillis()
        if (result.size < outLimit) {
            val supplement = runSupplement(query, request.budget.kPool, seen, request.sourceIds)
            if (supplement.refused != null) {
                fillDegraded = true
                fillReason = "语义补位不可用：${supplement.refused}"
            } else {
                for (hit in supplement.hits) {
                    if (result.size >= outLimit) break
                    if (!seen.add(hit.chunkId)) continue
                    result += mapSearchHit(hit, extraChannels = setOf(SagChannels.SEMANTIC_FILL))
                    added++
                }
            }
        }
        stages += stage(STAGE_SUPPLEMENT, added, fillStart)

        val reason = combineReasons(layer.note, extraReason, selection.degradedReason, fillReason)
        return Outcome(
            hits = result,
            // 预算截断也必须在 UI/trace 标记（PRD：SQL/超预算→截断并标注）；
            // 与能力降级共用 degraded 标记，degradedReason 前缀区分“预算截断：”。
            degraded = degradedByLlmFallback || selection.degradedReason != null || fillDegraded ||
                layer.note != null,
            degradedReason = reason,
            effectiveMode = if (multi) RetrievalMode.MULTI else RetrievalMode.MULTI_ES_FAST,
            seedEvents = layer.seedEvents,
            seedEntities = layer.seedEntities,
            expandedEvents = layer.expandedEvents,
            candidates = layer.candidates.size,
        )
    }

    // ───────────────────────── 事件层候选构建（fast/multi 共用） ─────────────────────────

    private suspend fun buildEventLayer(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
        entitySeeds: List<QueryEntity>,
    ): EventLayer {
        val budget = request.budget

        // 1) 查询向量 + 事件向量 seed（空间缺失/模型缺失→事件层不可用）
        val seedStart = System.currentTimeMillis()
        val queryVector = resolveQueryVectorSafely(query)
        if (queryVector == null) {
            stages += stage(STAGE_VECTOR_SEEDS, 0, seedStart)
            return EventLayer.Unavailable("事件层不可用：未找到可用嵌入模型或查询向量计算失败")
        }
        val rawVectors = safeSuspend {
            db.sagVectorDao().bySpace(
                spaceId = queryVector.eventSpaceId,
                role = SagVectorRole.EVENT_CONTENT.wire,
                ownerType = SagOwnerType.EVENT.value,
                limit = SagSupport.EVENT_VECTOR_SCAN_LIMIT,
            )
        }.orEmpty()
        val vectorSeeds = rankEventVectors(rawVectors, queryVector, budget.kSeed)
        stages += stage(STAGE_VECTOR_SEEDS, vectorSeeds.size, seedStart)
        if (vectorSeeds.isEmpty()) {
            return EventLayer.Unavailable(
                "事件层无数据：事件向量空间无数据（${queryVector.eventSpaceId}）",
            )
        }

        // 2) 查询实体 seed → DB id 绑定（id<=0 用 normalizedName 回查；绑不到则丢弃）
        val queryEntityIds = resolveEntityIds(entitySeeds)

        // 2b) **多跳桥接（真机多跳评测缺口修复）**：事件向量 seed → 其关联实体 → 与查询实体合并。
        //     只做 query entity → event 时，查询仅命中"人物"事件无法桥接到该人物事件关联的
        //     "组织/地点"事件，C2 桥接 chunk 永远进不了候选（120 条 2–4 跳评测 R@5 偏低根因）。
        //     预算：查询实体优先、其次 seed-event 实体；去重后限制 IN 规模（frontier/kSeed 较大者）。
        val seedEventEntityIds = if (vectorSeeds.isEmpty()) {
            emptyList()
        } else {
            loadEventEntityIds(vectorSeeds.map { it.first })
                .values.flatten()
                .distinct()
        }
        val hopEntityIds = (queryEntityIds + seedEventEntityIds)
            .distinct()
            .take(maxOf(budget.frontier, budget.kSeed))

        // 3) 实体扩展（查询实体 + seed-event 桥接实体）→ 活跃事件（单 SQL，LIMIT=frontier+1 探测截断）
        val expandStart = System.currentTimeMillis()
        val expandedAll = if (hopEntityIds.isEmpty()) {
            emptyList()
        } else {
            safeSuspend {
                db.eventEntityDao().activeEventIdsByEntityIds(hopEntityIds, budget.frontier + 1)
            }.orEmpty()
        }
        val truncated = expandedAll.size > budget.frontier
        val hopIds = expandedAll.take(budget.frontier)
        stages += stage(STAGE_EXPAND, expandedAll.size, expandStart)
        val note = if (truncated) "预算截断：实体扩展超过 frontier=${budget.frontier}，已截断" else null

        // 4) 合并候选（向量 0.7 + incidence 0.3，确定性排序，kCand 截断）
        val candidateStart = System.currentTimeMillis()
        // incidence 权重按"查询实体 + 桥接实体"的并集统计（桥接事件的关联强度不再退化到默认值）
        val hopWeights = loadHopWeights(hopIds, hopEntityIds.toHashSet())
        val accumulator = LinkedHashMap<Long, MutableCandidate>(vectorSeeds.size + hopIds.size)
        for ((eventId, score) in vectorSeeds) {
            accumulator.getOrPut(eventId) { MutableCandidate(eventId) }.vectorScore = score
        }
        for (eventId in hopIds) {
            val weight = hopWeights[eventId] ?: DEFAULT_INCIDENCE_WEIGHT
            accumulator.getOrPut(eventId) { MutableCandidate(eventId) }.entityScore = SagSupport.clamp01(weight)
        }
        val vectorSeedIds = vectorSeeds.mapTo(HashSet()) { it.first }
        val hopIdSet = hopIds.toHashSet()
        val scored = accumulator.values
            .map { candidate -> candidate to SagSupport.fuseEventScore(candidate.vectorScore, candidate.entityScore) }
            .sortedWith(
                compareByDescending<Pair<MutableCandidate, Double>> { it.second }
                    .thenBy { it.first.eventId },
            )
            .take(budget.kCand)
        if (scored.isEmpty()) {
            stages += stage(STAGE_CANDIDATES, 0, candidateStart)
            return EventLayer.Unavailable("事件层无数据：候选事件为空")
        }
        val scoreByEventId = scored.associate { (candidate, score) -> candidate.eventId to score }
        // 5) 候选事件 → chunk/doc/source 回填（ready + source scope 过滤）
        val candidateEvents = safeSuspend {
            db.eventDao().byIds(scored.map { it.first.eventId })
        }.orEmpty().filter { it.active }
        val chunks = loadChunks(candidateEvents.map { it.chunkId }.distinct())
        val documents = loadDocuments(chunks.values.map { it.documentId }.distinct())
        val sources = loadSources(documents.values.map { it.sourceId }.distinct())
        val eventEntityIds = loadEventEntityIds(candidateEvents.map { it.id })
        val entityNames = loadEntityNames(eventEntityIds.values.flatten().distinct())
        val scope = request.sourceIds.toHashSet()

        val candidates = ArrayList<SagHit>(candidateEvents.size)
        for (event in candidateEvents) {
            val chunk = chunks[event.chunkId] ?: continue
            val document = documents[chunk.documentId] ?: continue
            if (DocStatus.fromValue(document.status) != DocStatus.READY) continue
            if (scope.isNotEmpty() && document.sourceId !in scope) continue
            val score = scoreByEventId[event.id] ?: continue
            val source = sources[document.sourceId]
            val linkedEntityIds = eventEntityIds[event.id].orEmpty()
            val channels = LinkedHashSet<String>(2)
            if (event.id in vectorSeedIds) channels += SagChannels.EVENT_VECTOR
            if (event.id in hopIdSet) channels += SagChannels.EVENT_ENTITY
            candidates += SagHit(
                chunkId = chunk.id,
                documentId = chunk.documentId,
                sourceId = document.sourceId,
                ordinal = chunk.ordinal,
                content = chunk.content,
                charStart = chunk.charStart,
                charEnd = chunk.charEnd,
                docTitle = document.title,
                sourceTitle = source?.title ?: "",
                sourceUri = source?.uri ?: "",
                eventId = event.id,
                entityIds = linkedEntityIds,
                channels = channels,
                score = score,
                fusedScore = score,
                eventTitle = event.title,
                eventSummary = event.summary,
                entityNames = linkedEntityIds.mapNotNull { entityNames[it] },
            )
        }
        val ordered = candidates.sortedWith(
            compareByDescending<SagHit> { it.score }
                .thenBy { it.eventId ?: Long.MAX_VALUE }
                .thenBy { it.chunkId },
        )
        stages += stage(STAGE_CANDIDATES, ordered.size, candidateStart)
        if (ordered.isEmpty()) {
            return EventLayer.Unavailable(
                "事件层无数据：候选事件均不可检索（inactive/文档未 ready/来源范围外）",
            )
        }
        return EventLayer.Available(
            candidates = ordered,
            seedEvents = vectorSeeds.size,
            seedEntities = entitySeeds.size,
            expandedEvents = expandedAll.size,
            note = note,
        )
    }

    // ───────────────────────── 选择 / 降级 ─────────────────────────

    private suspend fun applySelection(
        query: String,
        request: SagQuery,
        candidates: List<SagHit>,
        activeSelector: SagContextSelector,
        allowLlmChannel: Boolean,
    ): SelectionResult {
        val raw = safeSuspend { activeSelector.select(query, candidates) }
        var reason: String? = null
        var llmApplied = false
        val selected: List<SagHit> = when {
            raw == null -> {
                reason = "LLM 回退：上下文选择失败，已使用确定性排序"
                deterministicOrder(candidates)
            }
            raw.isEmpty() -> {
                reason = "LLM 回退：上下文选择为空，已使用确定性排序"
                deterministicOrder(candidates)
            }
            else -> {
                val byChunk = candidates.associateBy { it.chunkId }
                val filtered = raw.mapNotNull { byChunk[it.chunkId] }.distinctBy { it.chunkId }
                if (filtered.isEmpty()) {
                    reason = "LLM 回退：选择结果不在候选集内，已使用确定性排序"
                    deterministicOrder(candidates)
                } else {
                    llmApplied = allowLlmChannel
                    filtered
                }
            }
        }
        return SelectionResult(
            hits = selected.take(request.budget.kEvent),
            llmApplied = llmApplied,
            degradedReason = reason,
        )
    }

    private suspend fun fallbackToVector(
        query: String,
        request: SagQuery,
        stages: MutableList<SagTraceStage>,
        reason: String,
    ): Outcome {
        val t = System.currentTimeMillis()
        val supplement = runSupplement(query, request.budget.kPool, emptySet(), request.sourceIds)
        stages += stage(STAGE_SUPPLEMENT, supplement.hits.size, t)
        return Outcome(
            hits = supplement.hits.take(outputLimit(request)).map { mapSearchHit(it) },
            refused = supplement.refused,
            degraded = true,
            degradedReason = "$reason，已降级为向量检索",
            effectiveMode = RetrievalMode.VECTOR,
            candidates = supplement.hits.size,
        )
    }

    // ───────────────────────── 查询向量 / 向量补位 ─────────────────────────

    private suspend fun resolveQueryVectorSafely(query: String): QueryVector? = try {
        resolveQueryVector(query)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveQueryVector(query: String): QueryVector? {
        // 测试注入：不触碰 EmbeddingRuntime（返回 null=事件层不可用）
        queryEmbeddingGateway?.let { gateway ->
            val vector = gateway.embedQuery(query) ?: return null
            val space = eventVectorSpaceForTests ?: return null
            return QueryVector(vector, space, vector.size)
        }
        val model = EmbeddingRuntime.findModel(appContext) ?: return null
        return EmbeddingRuntime.withProvider(appContext, model) { provider ->
            val vector = provider.embedQuery(query)
            val baseSpaceId = EmbeddingRuntime.resolveSpaceId(appContext, provider)
            val eventSpaceId = SagVectorSpace.spaceIdFor(baseSpaceId, SagVectorRole.EVENT_CONTENT)
            val dims = provider.descriptor.dimensions.takeIf { it > 0 } ?: vector.size
            QueryVector(vector, eventSpaceId, dims)
        }
    }

    /** 向量补位/vector 模式：测试网关优先；默认 [SearchService] + exclude 过滤。 */
    private suspend fun runSupplement(
        query: String,
        topK: Int,
        excludeChunkIds: Set<Long>,
        sourceIds: List<String>,
    ): SupplementResult {
        vectorSupplementGateway?.let { gateway ->
            val hits = safeSuspend {
                gateway.supplement(query, topK, excludeChunkIds, sourceIds)
            }.orEmpty()
            return SupplementResult(hits.filter { it.chunkId !in excludeChunkIds }, refused = null)
        }
        val result = searchService.search(query, topK, sourceIds)
        return SupplementResult(
            hits = result.hits.filter { it.chunkId !in excludeChunkIds },
            refused = result.refused,
        )
    }

    // ───────────────────────── 候选/回填辅助 ─────────────────────────

    /** 事件向量余弦：blob float32 LE；优先使用入库记录的 vectorNorm（与 B9 写入一致），缺失则现场算。 */
    private fun rankEventVectors(
        rows: List<SagVectorEntity>,
        queryVector: QueryVector,
        kSeed: Int,
    ): List<Pair<Long, Double>> {
        if (rows.isEmpty()) return emptyList()
        val dim = queryVector.dims
        if (dim <= 0) return emptyList()
        var qNorm = 0.0
        for (value in queryVector.vector) qNorm += value.toDouble() * value.toDouble()
        qNorm = sqrt(qNorm)
        if (qNorm == 0.0) return emptyList()

        val out = ArrayList<Pair<Long, Double>>(rows.size)
        for (row in rows) {
            if (row.vectorBlob.size != dim * 4) continue
            val buffer = ByteBuffer.wrap(row.vectorBlob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var dot = 0.0
            var squared = 0.0
            for (k in 0 until dim) {
                val value = buffer.get(k).toDouble()
                dot += value * queryVector.vector[k].toDouble()
                squared += value * value
            }
            val norm = row.vectorNorm.takeIf { it > 0.0 } ?: sqrt(squared)
            if (norm <= 0.0) continue
            val cosine = SagSupport.clamp01(dot / (qNorm * norm))
            if (cosine >= SagSupport.EVENT_VECTOR_TAU) {
                out += row.ownerId to cosine
            }
        }
        return out
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
            .take(kSeed)
    }

    /** 实体 seed → DB entity id（id>0 直接用；id<=0 用 normalizedName 回查；去重保序）。 */
    private suspend fun resolveEntityIds(seeds: List<QueryEntity>): List<Long> {
        val resolved = LinkedHashSet<Long>()
        for (seed in seeds) {
            if (seed.id > 0L) {
                resolved += seed.id
                continue
            }
            val normalized = SagSupport.normalizeEntityName(seed.normalizedName.ifBlank { seed.name })
            if (normalized.isBlank()) continue
            val row = safeSuspend { db.entityDao().byNormalizedName(normalized) } ?: continue
            resolved += row.id
        }
        return resolved.toList()
    }

    /** 实体扩展事件的 incidence 权重和（仅统计命中的 query 实体；byEvents 批量取关系行）。 */
    private suspend fun loadHopWeights(eventIds: List<Long>, entityIds: Set<Long>): Map<Long, Double> {
        if (eventIds.isEmpty() || entityIds.isEmpty()) return emptyMap()
        val rels = safeSuspend { db.eventEntityDao().byEvents(eventIds) }.orEmpty()
        val weights = HashMap<Long, Double>(rels.size)
        for (rel in rels) {
            if (rel.entityId !in entityIds) continue
            weights[rel.eventId] = (weights[rel.eventId] ?: 0.0) + rel.weight
        }
        return weights
    }

    private suspend fun loadChunks(ids: List<Long>): Map<Long, ChunkEntity> =
        loadLongBatches(ids) { batch -> db.chunkDao().chunksByIds(batch) }.associateBy { it.id }

    private suspend fun loadDocuments(ids: List<String>): Map<String, DocumentEntity> =
        loadStringBatches(ids) { batch -> db.documentDao().byIds(batch) }.associateBy { it.id }

    private suspend fun loadSources(ids: List<String>): Map<String, SourceEntity> =
        loadStringBatches(ids) { batch -> db.sourceDao().byIds(batch) }.associateBy { it.id }

    private suspend fun loadEntities(ids: List<Long>): Map<Long, EntityEntity> =
        loadLongBatches(ids) { batch -> db.entityDao().byIds(batch) }.associateBy { it.id }

    private suspend fun loadEntityNames(entityIds: List<Long>): Map<Long, String> =
        if (entityIds.isEmpty()) {
            emptyMap()
        } else {
            loadEntities(entityIds).mapValues { (_, entity) -> entity.name.ifBlank { entity.normalizedName } }
        }

    /** eventId → 全部关联 entityId（batch；用于 SagHit.entityIds/entityNames）。 */
    private suspend fun loadEventEntityIds(eventIds: List<Long>): Map<Long, List<Long>> {
        if (eventIds.isEmpty()) return emptyMap()
        val rels = loadLongBatches(eventIds) { batch -> db.eventEntityDao().byEvents(batch) }
        val map = LinkedHashMap<Long, MutableList<Long>>()
        for (rel in rels) {
            map.getOrPut(rel.eventId) { mutableListOf() }.add(rel.entityId)
        }
        return map
    }

    private suspend fun <T> loadLongBatches(ids: List<Long>, loader: suspend (List<Long>) -> List<T>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<T>(ids.size)
        for (batch in ids.distinct().chunked(SQL_VARIABLE_BATCH)) {
            out += safeSuspend { loader(batch) }.orEmpty()
        }
        return out
    }

    private suspend fun <T> loadStringBatches(ids: List<String>, loader: suspend (List<String>) -> List<T>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<T>(ids.size)
        for (batch in ids.distinct().chunked(SQL_VARIABLE_BATCH)) {
            out += safeSuspend { loader(batch) }.orEmpty()
        }
        return out
    }

    // ───────────────────────── 轨迹 / 通用 ─────────────────────────

    private suspend fun writeTrace(trace: SagTrace) {
        try {
            db.queryTraceDao().insert(
                QueryTraceEntity(
                    queryHash = trace.queryHash,
                    mode = trace.mode,
                    timingJson = SagSupport.encodeTimingJson(trace.stages),
                    degradedReason = trace.degradedReason,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.queryTraceDao().keepRecent(SagSupport.TRACE_KEEP)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // 轨迹是诊断旁路：写失败不影响检索结果（项目约定不写日志）
        }
    }

    private fun mapSearchHit(
        hit: SearchService.SearchHit,
        extraChannels: Set<String> = emptySet(),
    ): SagHit = SagHit(
        chunkId = hit.chunkId,
        documentId = hit.documentId,
        sourceId = hit.sourceId,
        ordinal = hit.ordinal,
        content = hit.content,
        charStart = hit.charStart,
        charEnd = hit.charEnd,
        docTitle = hit.docTitle,
        sourceTitle = hit.sourceTitle,
        sourceUri = hit.sourceUri,
        channels = SagSupport.channelsFromSearchHit(hit.channels) + extraChannels,
        score = hit.fusedScore,
        fusedScore = hit.fusedScore,
    )

    private fun deterministicOrder(candidates: List<SagHit>): List<SagHit> =
        candidates
            .distinctBy { it.chunkId }
            .sortedWith(
                compareByDescending<SagHit> { it.score }
                    .thenBy { it.eventId ?: Long.MAX_VALUE }
                    .thenBy { it.chunkId },
            )

    private fun outputLimit(request: SagQuery): Int =
        minOf(request.topK.coerceAtLeast(1), request.budget.kOut.coerceAtLeast(1))

    private fun stage(name: String, count: Int, startedAtMs: Long): SagTraceStage =
        SagTraceStage(name, count, (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0))

    private fun combineReasons(vararg reasons: String?): String? =
        reasons.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString("；").ifBlank { null }

    private suspend fun <T> safeSuspend(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    // ───────────────────────── 内部类型 ─────────────────────────

    private data class QueryVector(
        val vector: FloatArray,
        val eventSpaceId: String,
        val dims: Int,
    )

    private class MutableCandidate(val eventId: Long) {
        var vectorScore: Double? = null
        var entityScore: Double? = null
    }

    private sealed interface EventLayer {
        data class Available(
            val candidates: List<SagHit>,
            val seedEvents: Int,
            val seedEntities: Int,
            val expandedEvents: Int,
            val note: String?,
        ) : EventLayer

        data class Unavailable(val reason: String) : EventLayer
    }

    private data class SelectionResult(
        val hits: List<SagHit>,
        val llmApplied: Boolean,
        val degradedReason: String?,
    )

    private data class SupplementResult(
        val hits: List<SearchService.SearchHit>,
        val refused: String?,
    )

    private data class Outcome(
        val hits: List<SagHit> = emptyList(),
        val refused: String? = null,
        val degraded: Boolean = false,
        val degradedReason: String? = null,
        val effectiveMode: RetrievalMode,
        val seedEvents: Int = 0,
        val seedEntities: Int = 0,
        val expandedEvents: Int = 0,
        val candidates: Int = 0,
    )

    companion object {
        // ── trace 阶段名（固定；Lead/测试断言口径） ──
        internal const val STAGE_NORMALIZE: String = "normalize"
        internal const val STAGE_VECTOR_SEEDS: String = "vectorSeeds"
        internal const val STAGE_ENTITY_SEEDS: String = "entitySeeds"
        internal const val STAGE_EXPAND: String = "expand"
        internal const val STAGE_CANDIDATES: String = "candidates"
        internal const val STAGE_SELECT: String = "select"
        internal const val STAGE_SUPPLEMENT: String = "supplement"
        internal const val STAGE_TRACE: String = "trace"

        // ── 稳定文案/常量 ──
        internal const val MULTI_REFUSED_REASON: String =
            "multi 需要配置并允许联网的 LLM，可切换到 vector / multi_es_fast 离线模式"

        internal const val EMPTY_QUERY_REASON: String = "请输入检索内容"

        /** IN (:ids) 分批（SQLite 变量上限 999，留余量） */
        private const val SQL_VARIABLE_BATCH: Int = 400

        /** incidence 权重缺失时的默认值（正常由 event_entity.weight 提供） */
        private const val DEFAULT_INCIDENCE_WEIGHT: Double = 1.0
    }
}
