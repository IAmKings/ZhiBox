package com.zhibox.memory.core.sag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EntityFtsEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * B10 SagRetriever（instrumentation，真实 Room + fake embedding/supplement/LLM/selector）：
 * 三模式矩阵（vector / multi_es_fast / multi）、source scope、降级、预算截断、multi refused（零 LLM 调用）、
 * mock selector 选择去重补位、trace 每次同步写（refused/degraded 也写、keepRecent 50、无正文）、
 * event→chunk 映射一致性、非 ready/删除路径。
 *
 * 注入缝（core/sag 的 @VisibleForTesting internal）：queryEmbeddingGateway + eventVectorSpaceForTests
 * （事件层 seed，无需真实模型资产）与 vectorSupplementGateway（vector 模式/语义补位可控）。
 */
class SagRetrieverTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private var eventSeq = 0

    private val baseSpace = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1"
    private val eventSpace = SagVectorSpace.spaceIdFor(baseSpace, SagVectorRole.EVENT_CONTENT)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
        eventSeq = 0
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
    }

    // ---- vector 模式 ----

    @Test
    fun vectorModeEmptyQueryRefusedButTraceWritten() = runBlocking {
        val retriever = newRetriever()

        val result = retriever.retrieve(SagQuery(query = "   ", mode = RetrievalMode.VECTOR))

        assertEquals("请输入检索内容", result.refused)
        assertTrue(result.hits.isEmpty())
        assertEquals(RetrievalMode.VECTOR, result.effectiveMode)
        assertEquals(listOf("normalize", "trace"), result.trace.stages.map { it.name })
        assertEquals("空查询 normalize count=0", 0, result.trace.stages.first().count)
        assertEquals(1, db.queryTraceDao().count())
        val row = db.queryTraceDao().recent(1).single()
        assertEquals("vector", row.mode)
        assertTrue(row.degradedReason == null)
        assertFalse("timingJson 不含 query 正文", row.timingJson.contains("张三"))
        assertEquals(sha256(""), row.queryHash)
    }

    @Test
    fun vectorModeMapsSearchHitToReadableOriginalAndWritesTrace() = runBlocking {
        val source = seedSource("src-1", "来源一")
        val doc = seedDocument("doc-1", source, status = DocStatus.READY, title = "文档一")
        val chunk = seedChunk(doc, ordinal = 2, content = "张三在上海学习数学。", charStart = 10, charEnd = 21)
        val supplement = FakeSupplement { _, _, _, _ ->
            listOf(searchHit(chunk, doc, source, ordinal = 2, content = "张三在上海学习数学。", charStart = 10, charEnd = 21))
        }
        val retriever = newRetriever(supplement = supplement)

        val result = retriever.retrieve(SagQuery(query = "张三 上海", mode = RetrievalMode.VECTOR))

        assertNull(result.refused)
        assertEquals(RetrievalMode.VECTOR, result.effectiveMode)
        assertFalse(result.degraded)
        val hit = result.hits.single()
        assertEquals(chunk, hit.chunkId)
        assertEquals(doc, hit.documentId)
        assertEquals(source, hit.sourceId)
        assertEquals(2, hit.ordinal)
        assertEquals("张三在上海学习数学。", hit.content)
        assertEquals(10, hit.charStart)
        assertEquals(21, hit.charEnd)
        assertEquals("文档一", hit.docTitle)
        assertEquals("来源一", hit.sourceTitle)
        assertTrue("channels 应含 vector/keyword：${hit.channels}", hit.channels.contains(SagChannels.VECTOR))
        assertEquals(0.75, hit.score, 1e-9)
        assertEquals(0.75, hit.fusedScore!!, 1e-9)
        assertNull("vector 模式无事件层信息", hit.eventId)

        // trace：normalize → supplement → trace
        assertEquals(listOf("normalize", "supplement", "trace"), result.trace.stages.map { it.name })
        assertEquals(1, result.trace.stages[1].count)
        assertEquals(1, result.trace.candidates)
        assertEquals(1, result.trace.selected)
        val row = db.queryTraceDao().recent(1).single()
        assertEquals("vector", row.mode)
        assertEquals(result.trace.queryHash, row.queryHash)
        assertTrue(row.timingJson.contains("\"name\":\"supplement\""))
        assertFalse("持久化 trace 不含 query 正文", row.timingJson.contains("张三"))
        // gateway 入参：query 规范化、topK=kPool、exclude 空、sourceIds 空
        assertEquals("张三 上海", supplement.calls.single().query)
        assertEquals(50, supplement.calls.single().topK)
        assertEquals(emptySet<Long>(), supplement.calls.single().excludeChunkIds)
        assertEquals(emptyList<String>(), supplement.calls.single().sourceIds)
    }

    @Test
    fun vectorModeTopKAndSourceIdsArePassedThrough() = runBlocking {
        val sourceA = seedSource("src-A", "来源A")
        val sourceB = seedSource("src-B", "来源B")
        val docA = seedDocument("doc-A", sourceA)
        val docB = seedDocument("doc-B", sourceB)
        val chunkA = seedChunk(docA, ordinal = 0, content = "A 内容", charStart = 0, charEnd = 4)
        val chunkB = seedChunk(docB, ordinal = 0, content = "B 内容", charStart = 0, charEnd = 4)
        val supplement = FakeSupplement { query, topK, exclude, sourceIds ->
            assertEquals("sourceIds 应透传给补位网关", listOf("src-A"), sourceIds)
            listOf(
                searchHit(chunkA, docA, sourceA, content = "A 内容"),
                searchHit(chunkB, docB, sourceB, content = "B 内容"),
            ).filter { it.sourceId in sourceIds || sourceIds.isEmpty() }.take(topK)
        }
        val retriever = newRetriever(supplement = supplement)

        val result = retriever.retrieve(
            SagQuery(query = "内容", mode = RetrievalMode.VECTOR, topK = 1, sourceIds = listOf("src-A")),
        )

        assertEquals("输出 = min(topK, kOut)", 1, result.hits.size)
        assertEquals(chunkA, result.hits.single().chunkId)
        assertEquals("src-A", result.hits.single().sourceId)
        assertEquals(listOf("src-A"), supplement.calls.single().sourceIds)
    }

    // ---- fast 模式（事件层） ----

    @Test
    fun fastModeUsesEventSeedAndEntityHop() = runBlocking {
        val seeded = seedEventLayer(chunks = 2)
        val supplement = FakeSupplement { _, _, _, _ -> listOf(searchHit(seeded.chunkIds[1], seeded.docId, seeded.sourceId, content = "第二段")) }
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = supplement,
        )

        val result = retriever.retrieve(
            SagQuery(query = "上海", mode = RetrievalMode.MULTI_ES_FAST, topK = 5),
        )

        assertNull(result.refused)
        assertEquals(RetrievalMode.MULTI_ES_FAST, result.effectiveMode)
        assertFalse("事件层可用时不应降级", result.degraded)
        val eventHit = result.hits.firstOrNull { it.chunkId == seeded.chunkIds[0] }
        assertNotNull("事件 seed 应产生 chunk 命中", eventHit)
        assertNotNull("事件命中应带 eventId", eventHit!!.eventId)
        assertEquals(seeded.eventId, eventHit.eventId)
        assertTrue("事件向量通道", eventHit.channels.contains(SagChannels.EVENT_VECTOR))
        assertTrue("实体扩展通道", eventHit.channels.contains(SagChannels.EVENT_ENTITY))
        assertTrue("应带关联 entityIds", eventHit.entityIds.isNotEmpty())
        assertEquals("上海学习数学", eventHit.eventTitle)
        assertEquals("上海学习数学", eventHit.eventSummary)
        assertEquals("文档一", eventHit.docTitle)
        assertEquals("来源一", eventHit.sourceTitle)

        // trace 字段/阶段顺序：normalize→entitySeeds→vectorSeeds→expand→candidates→select→supplement→trace
        assertEquals(
            listOf("normalize", "entitySeeds", "vectorSeeds", "expand", "candidates", "select", "supplement", "trace"),
            result.trace.stages.map { it.name },
        )
        assertTrue("词法实体 seed", result.trace.seedEntities >= 1)
        assertTrue("事件向量 seed", result.trace.seedEvents >= 1)
        assertTrue("实体 1 跳扩展", result.trace.expandedEvents >= 1)
        assertTrue(result.trace.candidates >= 1)
        assertEquals(result.hits.size, result.trace.selected)
        // 语义补位去重：exclude 含已选 chunkId
        assertTrue(supplement.calls.isNotEmpty())
        assertTrue("补位 exclude 应含已选 chunk", supplement.calls.last().excludeChunkIds.contains(seeded.chunkIds[0]))
        // 补位命中带 semantic 通道
        val fillHit = result.hits.firstOrNull { it.chunkId == seeded.chunkIds[1] }
        assertNotNull("应补齐到 kOut", fillHit)
        assertTrue(fillHit!!.channels.contains(SagChannels.SEMANTIC_FILL))
    }

    @Test
    fun fastModeWithoutEventLayerFallsBackToVectorAndDegraded() = runBlocking {
        val source = seedSource("src-1", "来源一")
        val doc = seedDocument("doc-1", source)
        val chunk = seedChunk(doc, ordinal = 0, content = "张三在上海学习数学。", charStart = 0, charEnd = 11)
        val supplement = FakeSupplement { _, _, _, _ -> listOf(searchHit(chunk, doc, source, content = "张三在上海学习数学。")) }
        // 注入 embedding 但不注入 event 空间 → 事件层不可用
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            eventSpace = null,
            supplement = supplement,
        )

        val result = retriever.retrieve(SagQuery(query = "张三", mode = RetrievalMode.MULTI_ES_FAST))

        assertNull(result.refused)
        assertTrue("事件层不可用应降级", result.degraded)
        assertEquals(RetrievalMode.VECTOR, result.effectiveMode)
        assertTrue(
            "降级原因应可读：${result.trace.degradedReason}",
            result.trace.degradedReason!!.startsWith("事件层不可用："),
        )
        assertTrue(result.trace.degradedReason!!.contains("已降级为向量检索"))
        assertEquals(chunk, result.hits.single().chunkId)
        // trace 同步写入 degradedReason
        val row = db.queryTraceDao().recent(1).single()
        assertEquals("multi_es_fast", row.mode)
        assertTrue(row.degradedReason!!.startsWith("事件层不可用："))
        assertTrue(result.trace.stages.any { it.name == "supplement" })
    }

    @Test
    fun fastModeBudgetTruncationIsAnnotated() = runBlocking {
        val seeded = seedEventLayer(chunks = 2, entityLinkEvents = 3)
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = FakeSupplement { _, _, _, _ -> emptyList() },
        )

        val result = retriever.retrieve(
            SagQuery(
                query = "上海",
                mode = RetrievalMode.MULTI_ES_FAST,
                budget = SagBudget(frontier = 1, kSeed = 10, kEvent = 5, kOut = 10),
            ),
        )

        assertTrue("超 frontier 应标注降级：${result.trace.degradedReason}", result.degraded)
        assertTrue(
            "降级原因应含预算截断：${result.trace.degradedReason}",
            result.trace.degradedReason!!.contains("预算截断："),
        )
        assertEquals("expandedEvents 应记录截断前扩展数", 2, result.trace.expandedEvents)
        assertTrue(result.trace.stages.any { it.name == "expand" })
    }

    @Test
    fun sourceIdsFilterEventLayerHits() = runBlocking {
        val seedA = seedEventLayer(chunks = 1, sourceId = "src-A", docId = "doc-A", sourceTitle = "来源A", entityName = "上海")
        val seedB = seedEventLayer(chunks = 1, sourceId = "src-B", docId = "doc-B", sourceTitle = "来源B", entityName = "上海")
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = FakeSupplement { _, _, _, _ -> emptyList() },
        )

        val result = retriever.retrieve(
            SagQuery(query = "上海", mode = RetrievalMode.MULTI_ES_FAST, sourceIds = listOf("src-A")),
        )

        assertTrue(result.hits.isNotEmpty())
        assertTrue("事件层命中必须属于 src-A：${result.hits.map { it.sourceId }}", result.hits.all { it.sourceId == "src-A" })
        assertTrue(result.hits.any { it.chunkId == seedA.chunkIds[0] })
        assertFalse(result.hits.any { it.chunkId == seedB.chunkIds[0] })
    }

    @Test
    fun nonReadyDocumentEventCandidatesAreExcludedAndDegraded() = runBlocking {
        val seeded = seedEventLayer(chunks = 1)
        db.documentDao().updateStatusIfNotCancelled(seeded.docId, DocStatus.FAILED.value, DocStatus.CANCELLED.value)
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = FakeSupplement { _, _, _, _ -> emptyList() },
        )

        val result = retriever.retrieve(SagQuery(query = "上海", mode = RetrievalMode.MULTI_ES_FAST))

        assertTrue(result.degraded)
        assertEquals(RetrievalMode.VECTOR, result.effectiveMode)
        assertTrue(
            "非 ready 文档应被过滤并降级：${result.trace.degradedReason}",
            result.trace.degradedReason!!.startsWith("事件层无数据："),
        )
        assertTrue(result.hits.isEmpty())
    }

    // ---- multi 模式 ----

    @Test
    fun multiWithoutLlmIsRefusedWithoutCallingExtractorOrSelector() = runBlocking {
        seedEventLayer(chunks = 1)
        val extractor = CountingExtractor { emptyList() }
        val selector = CountingSelector()
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = FakeSupplement { _, _, _, _ -> emptyList() },
            llmAvailable = { false },
            llmExtractor = extractor,
            llmSelector = selector,
        )

        val result = retriever.retrieve(SagQuery(query = "上海", mode = RetrievalMode.MULTI))

        assertNotNull(result.refused)
        assertTrue(
            "no-LLM refused 文案：${result.refused}",
            result.refused!!.startsWith("multi 需要配置并允许联网的 LLM"),
        )
        assertTrue(result.hits.isEmpty())
        assertEquals(RetrievalMode.VECTOR, result.effectiveMode)
        assertEquals("refused 不得调用 LLM 抽取", 0, extractor.calls)
        assertEquals("refused 不得调用 LLM selector", 0, selector.calls)
        assertEquals("refused 也写 trace", 1, db.queryTraceDao().count())
        assertEquals("multi", db.queryTraceDao().recent(1).single().mode)
    }

    @Test
    fun multiWithFakeLlmAndSelectorSelectsAndFills() = runBlocking {
        val seeded = seedEventLayer(chunks = 2)
        val selector = CountingSelector()
        val extractor = CountingExtractor {
            listOf(
                QueryEntity(
                    id = 0L,
                    type = "location",
                    name = "上海",
                    normalizedName = "上海",
                    score = 0.9,
                    source = "llm",
                ),
            )
        }
        val supplement = FakeSupplement { _, _, _, _ ->
            listOf(searchHit(seeded.chunkIds[1], seeded.docId, seeded.sourceId, content = "第二段"))
        }
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = supplement,
            llmAvailable = { true },
            llmExtractor = extractor,
            llmSelector = selector,
        )

        val result = retriever.retrieve(
            SagQuery(query = "上海在哪里学习", mode = RetrievalMode.MULTI, topK = 5),
        )

        assertNull(result.refused)
        assertEquals(RetrievalMode.MULTI, result.effectiveMode)
        assertFalse("无回退时不应 degraded", result.degraded)
        assertEquals("LLM 抽取调用一次", 1, extractor.calls)
        assertEquals("selector 调用一次", 1, selector.calls)
        assertTrue("selector 应收到候选事件", selector.lastCandidates.isNotEmpty())
        assertTrue("候选应携带 eventTitle 供 LLM 选择", selector.lastCandidates.first().eventTitle.isNotBlank())
        assertTrue("选中事件 ≤ kEvent", result.hits.count { it.eventId != null } <= 1)
        val selectedEventHit = result.hits.first { it.eventId != null }
        assertTrue("multi LLM 选中应带 llm 通道：${selectedEventHit.channels}", selectedEventHit.channels.contains(SagChannels.LLM_SELECTED))
        assertTrue("输出上限 min(topK,kOut)", result.hits.size <= 5)
        assertTrue("补位命中带 semantic", result.hits.any { SagChannels.SEMANTIC_FILL in it.channels })
        assertTrue("应含事件层 chunk", result.hits.any { it.chunkId == seeded.chunkIds[0] })
        assertTrue("应含语义补位 chunk", result.hits.any { it.chunkId == seeded.chunkIds[1] })
        assertEquals("选后去重", result.hits.map { it.chunkId }.size, result.hits.map { it.chunkId }.toSet().size)
        assertTrue(result.trace.stages.any { it.name == "select" })
    }

    @Test
    fun multiFallsBackToLexicalWhenLlmExtractorReturnsEmpty() = runBlocking {
        seedEventLayer(chunks = 1)
        val extractor = CountingExtractor { emptyList() } // 等价坏 JSON/空输出 → Retriever 回退词法
        val retriever = newRetriever(
            embedding = { _ -> floatArrayOf(1f, 0f) },
            supplement = FakeSupplement { _, _, _, _ -> emptyList() },
            llmAvailable = { true },
            llmExtractor = extractor,
            llmSelector = CountingSelector(),
        )

        val result = retriever.retrieve(SagQuery(query = "上海", mode = RetrievalMode.MULTI))

        assertNull(result.refused)
        assertTrue("LLM 抽取为空应回退并标注", result.degraded)
        assertTrue(
            "降级原因含 LLM 回退：${result.trace.degradedReason}",
            result.trace.degradedReason!!.contains("LLM 回退："),
        )
        assertTrue(result.trace.stages.any { it.name == "llmFallback" })
        assertEquals("回退后仍能出事件层结果", 1, result.hits.count { it.eventId != null })
    }

    // ---- trace 持久化/裁剪/脱敏 ----

    @Test
    fun traceKeepsRecentFiftyAndStoresNoQueryBody() = runBlocking {
        val retriever = newRetriever(supplement = FakeSupplement { _, _, _, _ -> emptyList() })
        repeat(55) { i ->
            db.queryTraceDao().insert(
                com.zhibox.memory.data.db.QueryTraceEntity(
                    queryHash = "seed-$i",
                    mode = "vector",
                    timingJson = "{\"stages\":[]}",
                    degradedReason = null,
                    createdAt = 1_000L + i,
                ),
            )
        }
        val query = "张三 上海"
        val result = retriever.retrieve(SagQuery(query = query, mode = RetrievalMode.VECTOR))

        assertEquals("keepRecent(50)", 50, db.queryTraceDao().count())
        val row = db.queryTraceDao().recent(1).single()
        assertEquals(sha256("张三 上海"), row.queryHash)
        assertEquals(64, row.queryHash.length)
        assertFalse(row.timingJson.contains("张三"))
        assertFalse(row.timingJson.contains("上海"))
        assertFalse(row.timingJson.contains("query"))
        val stages = row.timingJson
        // 持久化 timingJson 在写入前构建：含 normalize/supplement 等实际阶段，但**不含 trace 阶段本身**
        // （trace 阶段 count=1/ms=写入耗时，只存在于返回的 SagResult.trace.stages）
        listOf("\"name\":\"normalize\"", "\"name\":\"supplement\"").forEach {
            assertTrue("持久化 timingJson 应含阶段 $it：$stages", stages.contains(it))
        }
        assertFalse("持久化 timingJson 不应含 trace 阶段（自引用，仅返回的 trace 里有）", stages.contains("\"name\":\"trace\""))
        assertEquals("返回的 trace 末尾应有 trace 阶段", "trace", result.trace.stages.last().name)
    }

    @Test
    fun eachRetrieveWritesTraceIncludingDegradedAndRefused() = runBlocking {
        val retriever = newRetriever(supplement = FakeSupplement { _, _, _, _ -> emptyList() })
        val before = db.queryTraceDao().count()

        retriever.retrieve(SagQuery(query = "a", mode = RetrievalMode.VECTOR))
        retriever.retrieve(SagQuery(query = "b", mode = RetrievalMode.MULTI_ES_FAST))
        retriever.retrieve(SagQuery(query = "c", mode = RetrievalMode.MULTI))

        assertEquals("每次 retrieve 都应写一条 trace", before + 3, db.queryTraceDao().count())
        val modes = db.queryTraceDao().recent(10).map { it.mode }.toSet()
        assertEquals(setOf("vector", "multi_es_fast", "multi"), modes)
    }

    // ---- 辅助构造 ----

    private data class SeededLayer(
        val sourceId: String,
        val docId: String,
        val chunkIds: List<Long>,
        val eventId: Long,
        val entityId: Long,
    )

    private fun newRetriever(
        embedding: (suspend (String) -> FloatArray?)? = null,
        eventSpace: String? = this.eventSpace,
        supplement: FakeSupplement? = null,
        llmAvailable: () -> Boolean = { false },
        llmExtractor: QueryEntityExtractor? = null,
        llmSelector: SagContextSelector? = null,
    ): SagRetriever {
        val retriever = SagRetriever(
            context = context,
            db = db,
            llmAvailable = llmAvailable,
            llmExtractor = llmExtractor,
            llmSelector = llmSelector,
        )
        if (embedding != null) {
            val embed = embedding
            retriever.queryEmbeddingGateway = object : QueryEmbeddingGateway {
                override suspend fun embedQuery(text: String): FloatArray? = embed(text)
            }
            retriever.eventVectorSpaceForTests = eventSpace
        }
        if (supplement != null) {
            retriever.vectorSupplementGateway = supplement
        }
        return retriever
    }

    private suspend fun seedEventLayer(
        chunks: Int,
        entityLinkEvents: Int = 1,
        sourceId: String = "src-1",
        docId: String = "doc-1",
        sourceTitle: String = "来源一",
        entityName: String = "上海",
    ): SeededLayer {
        seedSource(sourceId, sourceTitle)
        seedDocument(docId, sourceId)
        val chunkIds = (0 until chunks).map { index ->
            seedChunk(docId, ordinal = index, content = "第 $index 段内容", charStart = index, charEnd = index + 5)
        }
        // 事件：第 0 个 chunk；实体 FTS/精确 seed；event_entity 关联
        val eventIds = ArrayList<Long>()
        repeat(maxOf(1, entityLinkEvents)) { i ->
            eventIds += seedEvent(chunkIds[0], title = "上海学习数学", summary = "上海学习数学")
        }
        val eventId = eventIds.first()
        val entityId = seedEntity(entityName)
        eventIds.forEach { id -> seedEventEntity(id, entityId, weight = 1.0) }
        db.sagVectorDao().insert(
            SagVectorEntity(
                ownerType = SagOwnerType.EVENT.value,
                ownerId = eventId,
                role = SagVectorRole.EVENT_CONTENT.wire,
                spaceId = eventSpace,
                dimensions = 2,
                vectorBlob = floatBlob(1f, 0f),
                vectorNorm = 1.0,
                createdAt = 1_000L,
            ),
        )
        return SeededLayer(sourceId, docId, chunkIds, eventId, entityId)
    }

    private suspend fun seedSource(id: String, title: String): String {
        db.sourceDao().upsert(SourceEntity(id, title, "", null, 1_000L, "active"))
        return id
    }

    private suspend fun seedDocument(
        id: String,
        sourceId: String,
        status: DocStatus = DocStatus.READY,
        title: String = "文档一",
    ): String {
        db.documentDao().upsert(
            DocumentEntity(
                id = id,
                sourceId = sourceId,
                title = title,
                checksum = "sum-$id",
                parserId = "txt-utf8-v1",
                createdAt = 1_000L,
                status = status.value,
            ),
        )
        return id
    }

    private suspend fun seedChunk(
        documentId: String,
        ordinal: Int,
        content: String,
        charStart: Int,
        charEnd: Int,
    ): Long = db.chunkDao().insertAll(
        listOf(
            ChunkEntity(
                documentId = documentId,
                ordinal = ordinal,
                content = content,
                charStart = charStart,
                charEnd = charEnd,
                tokenEstimate = content.length,
            ),
        ),
    ).single()

    private suspend fun seedEvent(chunkId: Long, title: String, summary: String): Long =
        db.eventDao().upsert(
            EventEntity(
                chunkId = chunkId,
                canonicalText = "$title $summary",
                confidence = 1.0,
                title = title,
                summary = summary,
                content = summary,
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractorVersion = "egemma-sag-extract-v1",
                extractionVersion = "ev-$chunkId-${eventSeq++}",
                promptVersion = "v1",
                active = true,
                createdAt = 1_000L,
            ),
        )

    private suspend fun seedEventEntity(eventId: Long, entityId: Long, weight: Double) {
        db.eventEntityDao().upsert(
            EventEntityRel(eventId = eventId, entityId = entityId, role = "location", weight = weight),
        )
    }

    private suspend fun seedEntity(normalizedName: String): Long {
        val id = db.entityDao().upsert(
            EntityEntity(
                normalizedName = normalizedName,
                entityType = "location",
                name = normalizedName,
                description = "地点",
                createdAt = 1_000L,
            ),
        )
        db.entityDao().insertFts(
            EntityFtsEntity(entityId = id, text = BigramTokenizer.toFtsText(normalizedName)),
        )
        return id
    }

    private fun floatBlob(vararg values: Float): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun searchHit(
        chunkId: Long,
        documentId: String,
        sourceId: String,
        ordinal: Int = 0,
        content: String = "内容",
        charStart: Int = 0,
        charEnd: Int = 2,
        fusedScore: Double = 0.75,
    ): SearchService.SearchHit = SearchService.SearchHit(
        chunkId = chunkId,
        documentId = documentId,
        ordinal = ordinal,
        content = content,
        charStart = charStart,
        charEnd = charEnd,
        docTitle = "文档一",
        sourceTitle = "来源一",
        sourceUri = "",
        vectorRank = 1,
        keywordRank = 1,
        fusedScore = fusedScore,
        channels = "向量+关键词",
        sourceId = sourceId,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- 可计数 fake ----

    private class CountingExtractor(
        private val result: suspend (String) -> List<QueryEntity>,
    ) : QueryEntityExtractor {
        var calls: Int = 0
        override suspend fun extract(query: String): List<QueryEntity> {
            calls++
            return result(query)
        }
    }

    private class CountingSelector : SagContextSelector {
        var calls: Int = 0
        var lastCandidates: List<SagHit> = emptyList()
        override suspend fun select(query: String, candidates: List<SagHit>): List<SagHit> {
            calls++
            lastCandidates = candidates
            return candidates.take(1)
        }
    }

    private class FakeSupplement(
        private val handler: suspend (String, Int, Set<Long>, List<String>) -> List<SearchService.SearchHit>,
    ) : VectorSupplementGateway {
        data class Call(
            val query: String,
            val topK: Int,
            val excludeChunkIds: Set<Long>,
            val sourceIds: List<String>,
        )

        val calls = mutableListOf<Call>()
        override suspend fun supplement(
            query: String,
            topK: Int,
            excludeChunkIds: Set<Long>,
            sourceIds: List<String>,
        ): List<SearchService.SearchHit> {
            calls += Call(query, topK, excludeChunkIds, sourceIds)
            return handler(query, topK, excludeChunkIds, sourceIds)
        }
    }
}
