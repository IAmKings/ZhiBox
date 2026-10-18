package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.extract.EventEntityExtractor
import com.zhibox.memory.core.extract.ExtractedEntity
import com.zhibox.memory.core.extract.ExtractedEvent
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.extract.ExtractionInput
import com.zhibox.memory.core.extract.ExtractionResult
import com.zhibox.memory.core.llm.InMemoryLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.sag.SagVectorRole
import com.zhibox.memory.core.sag.SagVectorSpace
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityFtsEntity
import com.zhibox.memory.data.db.EventFtsEntity
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B9 抽取流水线（instrumentation，真实 Room + fake extractor/embedding gateway）：
 * 成功写入 event/entity/event_entity/FTS/sag_vector；supersede 旧版本并清理旧派生数据；
 * 无效输出终态；可重试失败 attempt 上限；非可重试终态；嵌入失败重试；not_indexed 与恢复；
 * 取消/文档非 ready 守卫；进程回收；单 chunk 失败隔离；100 chunk 成功率 100%；FTS 全量重建。
 *
 * 用 `SagAvailability.storeOverrideForTests`（@VisibleForTesting）注入 InMemoryLlmConfigStore，
 * 避免依赖 AndroidKeyStore；开关用真实 LlmPreferences 并在 @After 复原。
 */
class SagExtractionPipelineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var clock = 1_000L
    private var originalSagEnabled = false
    private var originalNetworkAllowed = false

    private val llmConfig = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        apiKey = "sk-test-1234567890abcd",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
        originalSagEnabled = LlmPreferences.sagEnabled(context)
        originalNetworkAllowed = LlmPreferences.networkAllowed(context)
        LlmPreferences.setSagEnabled(context, true)
        LlmPreferences.setNetworkAllowed(context, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(llmConfig)
        clock = 1_000L
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, originalSagEnabled)
        LlmPreferences.setNetworkAllowed(context, originalNetworkAllowed)
    }

    // ---- 成功路径 ----

    @Test
    fun successWritesEventEntityLinksFtsAndSagVectors() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-1")
        val event = event(
            title = "上海学习数学",
            summary = "张三在上海学习数学",
            content = "张三在上海学习数学。",
            entities = listOf(
                ExtractedEntity(EntityType.PERSON, "张三", "学生"),
                ExtractedEntity(EntityType.LOCATION, "上海", "学习地点"),
            ),
        )
        val extractor = ScriptedExtractor { ExtractionResult.Success(event) }
        val gateway = ScriptedGateway { role, texts -> vectorsFor(role, texts) }
        val pipeline = pipeline(extractor, gateway)

        assertTrue(pipeline.processJob(job))

        val storedJob = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.DONE.value, storedJob.status)
        assertEquals(1, storedJob.attempt)
        assertNull(storedJob.errorCode)

        val active = db.eventDao().activeByChunk(seed.chunkIds[0])!!
        assertEquals("上海学习数学", active.title)
        assertEquals("zh", active.language)
        assertEquals(EventStatus.ACTIVE.value, active.status)
        assertEquals("job-1", active.extractionVersion)
        assertEquals("egemma-sag-extract-v1", active.extractorVersion)
        assertEquals("v1", active.promptVersion)
        assertTrue(active.active)
        assertTrue(active.canonicalText.contains("上海学习数学"))

        assertEquals("event FTS 应写入 1 行", 1, db.eventDao().countFts())
        assertEquals("应创建 2 个实体", 2, db.entityDao().count())
        assertEquals("event-entity 关联 2 条", 2, db.eventEntityDao().count())
        assertEquals(1.0, db.eventEntityDao().byEvent(active.id).first().weight, 0.0001)
        assertEquals("entity FTS 2 行", 2, db.entityDao().countFts())

        val eventVectors = db.sagVectorDao().byOwner(SagOwnerType.EVENT.value, active.id)
        assertEquals(1, eventVectors.size)
        assertEquals(SagVectorRole.EVENT_CONTENT.wire, eventVectors.single().role)
        assertEquals(2, eventVectors.single().dimensions)
        assertEquals(1.0, eventVectors.single().vectorNorm, 0.0001)
        assertEquals("EVENT 向量 1 + ENTITY 向量 2 = 3", 3, db.sagVectorDao().count())

        assertEquals("单 chunk 只抽取一次", 1, extractor.inputs.size)
        assertNull("第 0 chunk 无 previousContext", extractor.inputs.single().previousContext)
        assertEquals("userPrompt 来源标题", "B9 文档", extractor.inputs.single().sourceTitle)
        assertEquals(listOf(SagVectorRole.EVENT_CONTENT, SagVectorRole.ENTITY_DESCRIPTION), gateway.roles)
        assertEquals(listOf(1, 2), gateway.textCounts)
        assertEquals(DocStatus.READY.value, db.documentDao().get("doc1")?.status)
    }

    @Test
    fun supersedeOldVersionKeepsHistoryAndCleansOldDerivedData() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val chunkId = seed.chunkIds[0]

        val job1 = seedJob(chunkId, "job-v1")
        val extractor1 = ScriptedExtractor {
            ExtractionResult.Success(event(title = "旧事件", entities = listOf(entity("张三"))))
        }
        val pipeline1 = pipeline(extractor1, ScriptedGateway { role, texts -> vectorsFor(role, texts) })
        assertTrue(pipeline1.processJob(job1))
        val oldActive = db.eventDao().activeByChunk(chunkId)!!
        assertEquals(1, db.eventDao().countFts())
        assertEquals(1, db.entityDao().count())

        // 同 chunk 重抽取：先删旧 job（v7 每 chunk 唯一）再插新 job
        db.extractionJobDao().deleteByChunk(chunkId)
        val job2 = seedJob(chunkId, "job-v2", createdAt = 2_000L)
        val extractor2 = ScriptedExtractor {
            ExtractionResult.Success(event(title = "新事件", entities = listOf(entity("李四"))))
        }
        val pipeline2 = pipeline(extractor2, ScriptedGateway { role, texts -> vectorsFor(role, texts) })
        assertTrue(pipeline2.processJob(job2))

        val events = db.eventDao().byChunk(chunkId)
        assertEquals("新旧两行都保留（历史可追溯）", 2, events.size)
        val newActive = events.single { it.active }
        assertEquals("新事件", newActive.title)
        assertEquals(job2, newActive.extractionVersion)
        val old = events.single { !it.active }
        assertEquals(oldActive.id, old.id)
        assertEquals(EventStatus.SUPERSEDED.value, old.status)
        assertEquals(newActive.id, old.supersededBy)

        assertEquals("event FTS 只保留新 active event", 1, db.eventDao().countFts())
        assertTrue(
            "旧 event 向量应被清理",
            db.sagVectorDao().byOwner(SagOwnerType.EVENT.value, old.id).isEmpty(),
        )
        assertEquals("旧 event_entity 作为历史保留 + 新关联 = 2 条", 2, db.eventEntityDao().count())
        assertEquals("实体跨文档复用：张三 + 李四", 2, db.entityDao().count())
    }

    @Test
    fun secondChunkReceivesPreviousContextForPronounResolution() = runBlocking {
        val seed = seedDocument(chunkCount = 2)
        val job = seedJob(seed.chunkIds[1], "job-ctx")
        val extractor = ScriptedExtractor { ExtractionResult.Success(event()) }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        assertTrue(pipeline.processJob(job))

        assertEquals(1, extractor.inputs.size)
        val input = extractor.inputs.single()
        assertEquals("应抽取第 1 号 chunk", seed.chunkIds[1], input.chunkId)
        assertEquals(
            "previousContext 应为上一 chunk 尾部（本 fixture 第 0 段内容）",
            "第 0 段内容",
            input.previousContext,
        )
        assertEquals(1, db.eventDao().countActive())
    }

    @Test
    fun invalidOutputIsTerminalFailureWithoutWrites() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-invalid")
        val extractor = ScriptedExtractor { ExtractionResult.Success(event(isValid = false)) }
        val gateway = ScriptedGateway { role, texts -> vectorsFor(role, texts) }
        val pipeline = pipeline(extractor, gateway)

        assertTrue(pipeline.processJob(job))

        val storedJob = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.FAILED.value, storedJob.status)
        assertEquals(1, storedJob.attempt)
        assertEquals(SagExtractionErrorCodes.INVALID_OUTPUT, storedJob.errorCode)
        assertEquals("无效输出不写任何派生数据", 0, db.eventDao().count())
        assertEquals(0, db.sagVectorDao().count())
        assertEquals("无有效 event 不应调用 embedding", 0, gateway.roles.size)
    }

    @Test
    fun retryableFailureRequeuesThenFailsAtAttemptLimit() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-retry")
        val extractor = ScriptedExtractor {
            ExtractionResult.Failure(LlmErrorCode.RATE_LIMITED, "请求被限流（HTTP 429）：请稍后重试")
        }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        assertTrue(pipeline.processJob(job)) // attempt 1 → 可重试
        var stored = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.QUEUED.value, stored.status)
        assertEquals(1, stored.attempt)

        assertTrue(pipeline.processJob(job)) // attempt 2 → 可重试
        stored = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.QUEUED.value, stored.status)
        assertEquals(2, stored.attempt)

        assertTrue(pipeline.processJob(job)) // attempt 3 → 达到上限终态失败
        stored = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.FAILED.value, stored.status)
        assertEquals(3, stored.attempt)
        assertEquals(LlmErrorCode.RATE_LIMITED, stored.errorCode)
        assertTrue(stored.errorDetail.orEmpty().contains("429"))
        assertEquals("失败重试不产生数据", 0, db.eventDao().count())
    }

    @Test
    fun nonRetryableFailureFailsImmediately() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-auth")
        val extractor = ScriptedExtractor {
            ExtractionResult.Failure(LlmErrorCode.AUTH, "鉴权失败（HTTP 401）：请检查 API Key")
        }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        assertTrue(pipeline.processJob(job))

        val stored = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.FAILED.value, stored.status)
        assertEquals(1, stored.attempt)
        assertEquals(LlmErrorCode.AUTH, stored.errorCode)
    }

    @Test
    fun embeddingFailureRetriesThenSucceeds() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-embed-retry")
        var calls = 0
        val gateway = ScriptedGateway { role, texts ->
            calls++
            if (calls == 1) {
                throw SagEmbeddingException(
                    code = SagExtractionErrorCodes.EMBEDDING_FAILED,
                    message = "端侧嵌入瞬时失败",
                )
            }
            vectorsFor(role, texts)
        }
        val pipeline = pipeline(
            ScriptedExtractor { ExtractionResult.Success(event()) },
            gateway,
        )

        assertTrue(pipeline.processJob(job)) // attempt 1 → 可重试
        assertEquals(ExtractionJobStatus.QUEUED.value, db.extractionJobDao().get(job)!!.status)
        assertTrue(pipeline.processJob(job)) // attempt 2 → 成功
        val stored = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.DONE.value, stored.status)
        assertEquals(2, stored.attempt)
        assertEquals(1, db.eventDao().countActive())
    }

    @Test
    fun unavailableSagMarksNotIndexedAndRecoversAfterEnabling() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-offline")
        val extractor = ScriptedExtractor { ExtractionResult.Success(event()) }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, false)
        assertTrue(pipeline.processJob(job))
        val notIndexed = db.extractionJobDao().get(job)!!
        assertEquals(ExtractionJobStatus.NOT_INDEXED.value, notIndexed.status)
        assertEquals(ExtractionErrorCodes.NOT_CONFIGURED, notIndexed.errorCode)
        assertEquals("未索引不写数据", 0, db.eventDao().count())
        assertEquals("未索引不调用抽取器", 0, extractor.inputs.size)
        assertEquals(DocStatus.READY.value, db.documentDao().get("doc1")?.status)

        // 补齐配置后（scheduler.backfill 负责重排队；这里直接重排队验证 pipeline 恢复）
        LlmPreferences.setSagEnabled(context, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(llmConfig)
        db.extractionJobDao().upsert(
            notIndexed.copy(
                status = ExtractionJobStatus.QUEUED.value,
                attempt = 0,
                errorCode = null,
                errorDetail = null,
            ),
        )
        assertTrue(pipeline.processJob(job))
        assertEquals(ExtractionJobStatus.DONE.value, db.extractionJobDao().get(job)!!.status)
        assertEquals(1, db.eventDao().countActive())
    }

    @Test
    fun cancelDuringExtractionPreventsWrites() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val job = seedJob(seed.chunkIds[0], "job-cancel")
        val extractor = ScriptedExtractor {
            db.extractionJobDao().cancelByDocument(
                documentId = "doc1",
                cancelledStatus = ExtractionJobStatus.CANCELLED.value,
                now = 9_999L,
            )
            ExtractionResult.Success(event())
        }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        assertFalse("取消后不得提交数据", pipeline.processJob(job))
        assertEquals(ExtractionJobStatus.CANCELLED.value, db.extractionJobDao().get(job)!!.status)
        assertEquals("取消后不写 event", 0, db.eventDao().count())
        assertEquals(0, db.sagVectorDao().count())
    }

    @Test
    fun documentNotReadyCancelsAllJobsForDocument() = runBlocking {
        val seed = seedDocument(chunkCount = 2)
        val job1 = seedJob(seed.chunkIds[0], "job-a")
        val job2 = seedJob(seed.chunkIds[1], "job-b")
        db.documentDao().updateStatusIfNotCancelled("doc1", DocStatus.FAILED.value, DocStatus.CANCELLED.value)
        val extractor = ScriptedExtractor { ExtractionResult.Success(event()) }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        assertFalse(pipeline.processJob(job1))
        assertTrue(
            "整篇任务应统一取消",
            db.extractionJobDao().byDocument("doc1").all { it.status == ExtractionJobStatus.CANCELLED.value },
        )
        assertEquals(ExtractionJobStatus.CANCELLED.value, db.extractionJobDao().get(job2)!!.status)
        assertEquals("非 ready 文档不写数据", 0, db.eventDao().count())
    }

    @Test
    fun recoverInterruptedRequeuesRunningJobs() = runBlocking {
        val seed = seedDocument(chunkCount = 2)
        val running = seedJob(seed.chunkIds[0], "job-running")
        val queued = seedJob(seed.chunkIds[1], "job-queued")
        db.extractionJobDao().claim(running, ExtractionJobStatus.QUEUED.value, ExtractionJobStatus.RUNNING.value, 5_000L)
        assertEquals(ExtractionJobStatus.RUNNING.value, db.extractionJobDao().get(running)!!.status)

        val pipeline = pipeline(
            ScriptedExtractor { ExtractionResult.Success(event()) },
            ScriptedGateway { role, texts -> vectorsFor(role, texts) },
        )
        assertEquals("只回收 RUNNING", 1, pipeline.recoverInterrupted())
        assertEquals(ExtractionJobStatus.QUEUED.value, db.extractionJobDao().get(running)!!.status)
        assertEquals("queued 不受影响", ExtractionJobStatus.QUEUED.value, db.extractionJobDao().get(queued)!!.status)
        assertEquals("回收后 attempt 保留", 1, db.extractionJobDao().get(running)!!.attempt)
    }

    @Test
    fun singleChunkFailureDoesNotAffectOtherChunks() = runBlocking {
        val seed = seedDocument(chunkCount = 2)
        val badChunk = seed.chunkIds[1]
        seedJob(seed.chunkIds[0], "job-good")
        seedJob(badChunk, "job-bad")
        val extractor = ScriptedExtractor { input ->
            if (input.chunkId == badChunk) {
                ExtractionResult.Failure(LlmErrorCode.AUTH, "鉴权失败（HTTP 401）")
            } else {
                ExtractionResult.Success(event())
            }
        }
        val pipeline = pipeline(extractor, ScriptedGateway { role, texts -> vectorsFor(role, texts) })

        var processed = 0
        while (pipeline.processNext()) processed++
        assertEquals(2, processed)
        assertEquals(1, db.extractionJobDao().countByStatus(ExtractionJobStatus.DONE.value))
        assertEquals(1, db.extractionJobDao().countByStatus(ExtractionJobStatus.FAILED.value))
        assertEquals("仅成功 chunk 写数据", 1, db.eventDao().countActive())
    }

    @Test
    fun hundredChunksAllSucceed() = runBlocking {
        val seed = seedDocument(chunkCount = 100)
        seed.chunkIds.forEachIndexed { index, chunkId ->
            seedJob(chunkId, "job-$index", createdAt = 10_000L + index)
        }
        val extractor = ScriptedExtractor { ExtractionResult.Success(event()) }
        val gateway = ScriptedGateway { role, texts -> vectorsFor(role, texts) }
        val pipeline = pipeline(extractor, gateway)

        var processed = 0
        while (pipeline.processNext()) processed++

        assertEquals("应处理 100 个 chunk", 100, processed)
        assertEquals(100, db.extractionJobDao().countByStatus(ExtractionJobStatus.DONE.value))
        assertEquals(0, db.extractionJobDao().countByStatus(ExtractionJobStatus.FAILED.value))
        assertEquals(100, db.eventDao().countActive())
        assertEquals(100, db.eventDao().countFts())
        assertEquals("同事实体跨 chunk 复用为 1 个 entity", 1, db.entityDao().count())
        assertEquals("100 条 event_entity 关联", 100, db.eventEntityDao().count())
        assertEquals(1, db.entityDao().countFts())
        assertEquals("100 event 向量 + 1 entity 向量", 101, db.sagVectorDao().count())
        assertEquals("100 chunk 成功率 100%", 100, extractor.inputs.size)
    }

    @Test
    fun ftsRebuildRestoresActiveEventsAndAllEntities() = runBlocking {
        val seed = seedDocument(chunkCount = 2)
        val job1 = seedJob(seed.chunkIds[0], "job-f1")
        val pipeline = pipeline(
            ScriptedExtractor { ExtractionResult.Success(event(entities = listOf(entity("张三")))) },
            ScriptedGateway { role, texts -> vectorsFor(role, texts) },
        )
        assertTrue(pipeline.processJob(job1))
        db.extractionJobDao().deleteByChunk(seed.chunkIds[0])
        val job2 = seedJob(seed.chunkIds[0], "job-f2", createdAt = 2_000L)
        assertTrue(pipeline.processJob(job2))
        val job3 = seedJob(seed.chunkIds[1], "job-f3", createdAt = 3_000L)
        assertTrue(pipeline.processJob(job3))

        // 制造脏 FTS：重建应清空后按主表重写
        db.eventDao().insertFts(EventFtsEntity(eventId = 999_999L, text = "stale"))
        db.entityDao().insertFts(EntityFtsEntity(entityId = 999_999L, text = "stale"))

        val activeEvents = db.eventDao().all().filter { it.active }
        val entities = db.entityDao().all()
        val rebuilt = SagFtsRebuilder.rebuildAll(db)
        assertEquals("重建行数 = active event + all entity", activeEvents.size + entities.size, rebuilt)
        assertEquals(activeEvents.size, db.eventDao().countFts())
        assertEquals(entities.size, db.entityDao().countFts())
        assertEquals("应清掉 stale FTS", activeEvents.size, db.eventDao().countFts())
        assertEquals(3, db.eventDao().all().size)
        assertEquals(1, db.entityDao().count())
    }

    // ---- helpers ----

    private data class Seed(val chunkIds: List<Long>)

    private suspend fun seedDocument(chunkCount: Int): Seed {
        db.sourceDao().upsert(SourceEntity("src1", "B9 来源", "", null, 100, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc1", sourceId = "src1", title = "B9 文档", checksum = "sum-b9",
                parserId = "txt-utf8-v1", createdAt = 200, status = DocStatus.READY.value,
            ),
        )
        val chunks = db.chunkDao().insertAll(
            (0 until chunkCount).map { index ->
                ChunkEntity(
                    documentId = "doc1",
                    ordinal = index,
                    content = "第 $index 段内容",
                    charStart = index * 10,
                    charEnd = index * 10 + 6,
                    tokenEstimate = 6,
                )
            },
        )
        return Seed(chunkIds = chunks)
    }

    private suspend fun seedJob(chunkId: Long, id: String, createdAt: Long = 1_000L): String {
        db.extractionJobDao().upsert(
            ExtractionJobEntity(
                id = id,
                documentId = "doc1",
                chunkId = chunkId,
                status = ExtractionJobStatus.QUEUED.value,
                attempt = 0,
                errorCode = null,
                errorDetail = null,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        return id
    }

    private fun pipeline(
        extractor: EventEntityExtractor,
        gateway: SagEmbeddingGateway,
    ) = SagExtractionPipeline(
        context = context,
        db = db,
        extractor = extractor,
        embeddings = gateway,
        now = { clock += 10; clock },
    )

    private fun event(
        title: String = "测试事件",
        summary: String = "用于测试的抽取事件",
        content: String = "张三在上海学习数学。",
        entities: List<ExtractedEntity> = listOf(entity("张三")),
        isValid: Boolean = true,
    ) = ExtractedEvent(
        title = title,
        summary = summary,
        content = content,
        language = "zh",
        entities = entities,
        isValid = isValid,
    )

    private fun entity(name: String) = ExtractedEntity(EntityType.PERSON, name, "测试实体")

    private fun vectorsFor(role: SagVectorRole, texts: List<String>): List<SagEmbeddedVector> {
        val spaceId = SagVectorSpace.spaceIdFor(BASE_SPACE, role)
        return texts.map {
            SagEmbeddedVector(
                vectorBlob = ByteBuffer.allocate(DIMS * 4).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(0.5f).putFloat(0.25f).array(),
                vectorNorm = 1.0,
                spaceId = spaceId,
                dimensions = DIMS,
            )
        }
    }

    private class ScriptedExtractor(
        private val handler: suspend (ExtractionInput) -> ExtractionResult,
    ) : EventEntityExtractor {
        val inputs = mutableListOf<ExtractionInput>()
        override suspend fun extract(input: ExtractionInput): ExtractionResult {
            inputs += input
            return handler(input)
        }
    }

    private class ScriptedGateway(
        private val handler: suspend (SagVectorRole, List<String>) -> List<SagEmbeddedVector>,
    ) : SagEmbeddingGateway {
        val roles = mutableListOf<SagVectorRole>()
        val textCounts = mutableListOf<Int>()
        override suspend fun embed(role: SagVectorRole, texts: List<String>): List<SagEmbeddedVector> {
            roles += role
            textCounts += texts.size
            return handler(role, texts)
        }
    }

    private companion object {
        const val DIMS = 2
        const val BASE_SPACE = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1"
    }
}
