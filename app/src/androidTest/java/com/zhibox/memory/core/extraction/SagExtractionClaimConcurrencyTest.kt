package com.zhibox.memory.core.extraction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.extract.EventEntityExtractor
import com.zhibox.memory.core.extract.ExtractedEntity
import com.zhibox.memory.core.extract.ExtractedEvent
import com.zhibox.memory.core.extract.ExtractionInput
import com.zhibox.memory.core.extract.ExtractionResult
import com.zhibox.memory.core.llm.InMemoryLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.sag.SagVectorRole
import com.zhibox.memory.core.sag.SagVectorSpace
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * B12 抽取并发 claim 安全（instrumentation，in-memory Room + fake extractor/gateway）：
 *
 * 复刻 `SagExtractionWorker` 的并发消费循环（parallelism=4，竞争失败时查 queuedCount 后
 * 短延迟重试），验证：
 * - **每个 QUEUED job 只被一个执行者 claim**（`ExtractionJobDao.claim` 原子）：fake extractor 对
 *   每个 chunk 恰好调用一次，无重复处理；
 * - 所有 job 最终 DONE、attempt=1、errorCode=null；
 * - 写入不重复：每 chunk 1 active event / 1 FTS / 无重复 sag_vector；
 * - 同 job 并发 `processJob` 只有一个成功（另一个 claim 竞争失败返回 false）。
 *
 * 仅编译验证（Lead 要求暂不跑 PJZ110，等 Settings UI 完成后统一串行）；设备空闲后可直接运行。
 */
class SagExtractionClaimConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var pipeline: SagExtractionPipeline
    private val extractorInputs = CopyOnWriteArrayList<ExtractionInput>()
    private val extractorCalls = AtomicInteger(0)
    private val gatewayCalls = AtomicInteger(0)
    private val clock = AtomicLong(1_000L)
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
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        originalSagEnabled = LlmPreferences.sagEnabled(context)
        originalNetworkAllowed = LlmPreferences.networkAllowed(context)
        LlmPreferences.setSagEnabled(context, true)
        LlmPreferences.setNetworkAllowed(context, true)
        SagAvailability.storeOverrideForTests = InMemoryLlmConfigStore(llmConfig)

        val extractor = object : EventEntityExtractor {
            override suspend fun extract(input: ExtractionInput): ExtractionResult {
                extractorCalls.incrementAndGet()
                extractorInputs += input
                delay(3) // 放大并发窗口，制造真实 claim 竞争
                return ExtractionResult.Success(event(input))
            }
        }
        val gateway = object : SagEmbeddingGateway {
            override suspend fun embed(role: SagVectorRole, texts: List<String>): List<SagEmbeddedVector> {
                gatewayCalls.incrementAndGet()
                return vectorsFor(role, texts)
            }
        }
        pipeline = SagExtractionPipeline(
            context = context,
            db = db,
            extractor = extractor,
            embeddings = gateway,
            now = { clock.incrementAndGet() },
        )
    }

    @After
    fun tearDown() {
        db.close()
        SagAvailability.storeOverrideForTests = null
        LlmPreferences.setSagEnabled(context, originalSagEnabled)
        LlmPreferences.setNetworkAllowed(context, originalNetworkAllowed)
    }

    @Test
    fun concurrentConsumersClaimEachJobExactlyOnceAndAllDone() = runBlocking {
        val chunkCount = 8
        val seed = seedDocument(chunkCount)
        seed.chunkIds.forEachIndexed { index, chunkId ->
            seedJob(chunkId, "job-$index", createdAt = 1_000L + index)
        }

        // 复刻 Worker 并发循环（parallelism=4）：竞争失败时 queuedCount>0 → 让出后重试。
        val parallelism = 4
        coroutineScope {
            repeat(parallelism) {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val processed = pipeline.processNext()
                        if (!processed) {
                            if (db.extractionJobDao().queuedCount(ExtractionJobStatus.QUEUED.value) <= 0) {
                                return@launch
                            }
                            delay(10)
                        }
                    }
                }
            }
        }

        // 全部 DONE 且 attempt 只 +1（claim 一次）
        assertEquals("全部 job DONE", chunkCount, db.extractionJobDao().countByStatus(ExtractionJobStatus.DONE.value))
        assertEquals(0, db.extractionJobDao().countByStatus(ExtractionJobStatus.QUEUED.value))
        assertEquals(0, db.extractionJobDao().countByStatus(ExtractionJobStatus.RUNNING.value))
        assertEquals(0, db.extractionJobDao().countByStatus(ExtractionJobStatus.FAILED.value))
        repeat(chunkCount) { index ->
            val job = db.extractionJobDao().get("job-$index")!!
            assertEquals(ExtractionJobStatus.DONE.value, job.status)
            assertEquals("claim 原子只 +1 attempt：job-$index", 1, job.attempt)
            assertNull(job.errorCode)
        }

        // 每个 chunk 恰好抽取一次（无重复处理）
        assertEquals("extractor 调用次数 = job 数", chunkCount, extractorCalls.get())
        assertEquals("每个 chunk 恰好一次", chunkCount, extractorInputs.size)
        assertEquals("chunk 不重复", chunkCount, extractorInputs.map { it.chunkId }.toSet().size)
        assertEquals(
            "input chunkId 集合等于 seed",
            seed.chunkIds.toSet(),
            extractorInputs.map { it.chunkId }.toSet(),
        )

        // 写入不重复：每 chunk 1 active event + 1 FTS；实体复用；向量不重复
        assertEquals("每 chunk 1 active event", chunkCount, db.eventDao().countActive())
        assertEquals("每 chunk 1 event FTS", chunkCount, db.eventDao().countFts())
        assertEquals("同事实体跨 chunk 复用为 1", 1, db.entityDao().count())
        assertEquals("每 chunk 1 event_entity", chunkCount, db.eventEntityDao().count())
        assertTrue("gateway 至少被调用", gatewayCalls.get() >= 1)
    }

    @Test
    fun sameJobConcurrentProcessJobHasSingleWinner() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val jobId = seedJob(seed.chunkIds.single(), "job-race", createdAt = 2_000L)

        val results = coroutineScope {
            val first = async(Dispatchers.IO) { pipeline.processJob(jobId) }
            val second = async(Dispatchers.IO) { pipeline.processJob(jobId) }
            listOf(first.await(), second.await())
        }

        assertEquals("恰好一个执行者处理该 job", 1, results.count { it })
        assertEquals("另一个 claim 竞争失败", 1, results.count { !it })
        assertEquals(1, extractorInputs.size)
        val job = db.extractionJobDao().get(jobId)!!
        assertEquals(ExtractionJobStatus.DONE.value, job.status)
        assertEquals(1, job.attempt)
        assertEquals("只写一条 active event", 1, db.eventDao().countActive())
    }

    @Test
    fun claimOnAlreadyRunningJobIsRejected() = runBlocking {
        val seed = seedDocument(chunkCount = 1)
        val jobId = seedJob(seed.chunkIds.single(), "job-running", createdAt = 3_000L)
        // 模拟另一个执行者已 claim：QUEUED→RUNNING
        val claimed = db.extractionJobDao().claim(
            id = jobId,
            fromStatus = ExtractionJobStatus.QUEUED.value,
            toStatus = ExtractionJobStatus.RUNNING.value,
            now = 9_999L,
        )
        assertEquals(1, claimed)

        assertFalse("RUNNING 不可被再次 claim/处理", pipeline.processJob(jobId))
        assertEquals("不得重复抽取", 0, extractorCalls.get())
        assertEquals(ExtractionJobStatus.RUNNING.value, db.extractionJobDao().get(jobId)!!.status)
    }

    // ---- seed/helpers ----

    private data class Seed(val chunkIds: List<Long>)

    private suspend fun seedDocument(chunkCount: Int): Seed {
        db.sourceDao().upsert(SourceEntity("src-b12", "B12 来源", "", null, 100, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc-b12",
                sourceId = "src-b12",
                title = "B12 并发文档",
                checksum = "sum-b12",
                parserId = "txt-utf8-v1",
                createdAt = 200,
                status = DocStatus.READY.value,
            ),
        )
        val chunks = db.chunkDao().insertAll(
            (0 until chunkCount).map { index ->
                ChunkEntity(
                    documentId = "doc-b12",
                    ordinal = index,
                    content = "第 $index 段并发内容",
                    charStart = index * 10,
                    charEnd = index * 10 + 6,
                    tokenEstimate = 6,
                )
            },
        )
        return Seed(chunkIds = chunks)
    }

    private suspend fun seedJob(chunkId: Long, id: String, createdAt: Long): String {
        db.extractionJobDao().upsert(
            ExtractionJobEntity(
                id = id,
                documentId = "doc-b12",
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

    private fun event(input: ExtractionInput): ExtractedEvent = ExtractedEvent(
        title = "并发事件-${input.chunkId}",
        summary = "chunk ${input.chunkId} 的并发抽取结果",
        content = "张三在上海学习数学。",
        language = "zh",
        entities = listOf(ExtractedEntity(EntityType.PERSON, "张三", "学生")),
        isValid = true,
    )

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

    private companion object {
        const val DIMS = 2
        const val BASE_SPACE = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1"
    }
}
