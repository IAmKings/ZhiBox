package com.zhibox.memory.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.sag.SagVectorRole
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

/**
 * B8 schema v6 新表 DAO roundtrip（instrumentation，真实 Room/SQLite）：
 * event / entity / event_entity / sag_vector / extraction_job / chat(conversation/message/citation)
 * / query_trace + event_fts/entity_fts，以及 FK 级联与 `PRAGMA foreign_key_check` 空。
 *
 * 说明：用 `AppDatabase.get(context)` 真实数据库 + clearAllTables 隔离（与既有 instrumentation
 * 测试一致）；不依赖 WorkManager/网络/模型。
 */
class B8DaoRoundTripTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val now = 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
    }

    // ---- event ----

    @Test
    fun eventDaoRoundTripAndSupersede() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val chunkId = chunkIds.first()

        val firstId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkId,
                canonicalText = "张三在上海学习数学",
                confidence = 0.9,
                title = "上海学习数学",
                summary = "张三在上海学习数学",
                content = "张三在上海学习数学。",
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractorVersion = "egemma-sag-extract-v1",
                extractionVersion = "uuid-1",
                modelId = "gpt-4o-mini",
                promptVersion = "v1",
                active = true,
                createdAt = now,
            ),
        )
        assertTrue("自增 id 应 > 0", firstId > 0)
        val stored = db.eventDao().byId(firstId)!!
        assertEquals("上海学习数学", stored.title)
        assertEquals("zh", stored.language)
        assertTrue(stored.active)
        assertEquals(firstId, db.eventDao().activeByChunk(chunkId)?.id)
        assertEquals(1, db.eventDao().countActive())
        assertNotNull(db.eventDao().byChunkAndExtractionVersion(chunkId, "uuid-1"))

        // 新一次抽取（新 extractionVersion）→ supersede 旧行
        val secondId = db.eventDao().upsert(
            stored.copy(id = 0, extractionVersion = "uuid-2", title = "新版本事件", createdAt = now + 1),
        )
        val superseded = db.eventDao().supersedeOthers(chunkId, secondId, EventStatus.SUPERSEDED.value)
        assertEquals("旧 active 行应被替代", 1, superseded)
        val old = db.eventDao().byId(firstId)!!
        assertFalse(old.active)
        assertEquals(EventStatus.SUPERSEDED.value, old.status)
        assertEquals(secondId, old.supersededBy)
        assertEquals(secondId, db.eventDao().activeByChunk(chunkId)?.id)
        assertEquals(1, db.eventDao().countActive())

        // 同 chunk+同 extractionVersion 唯一键：REPLACE 覆盖，不产生第二行
        db.eventDao().upsert(stored.copy(id = 0, extractionVersion = "uuid-2", title = "再次覆盖"))
        assertEquals(
            "同一 (chunkId, extractionVersion) 只允许一行",
            2,
            db.eventDao().byChunk(chunkId).size,
        )
        assertForeignKeyCheckEmpty()
    }

    @Test
    fun eventFtsRoundTripAndSearch() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val eventId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkIds.first(), canonicalText = "x", confidence = 1.0,
                title = "t", createdAt = now,
            ),
        )
        db.eventDao().clearFts()
        db.eventDao().insertFts(EventFtsEntity(eventId = eventId, text = "hello math world"))
        assertEquals(1, db.eventDao().countFts())
        assertEquals(listOf(eventId), db.eventDao().searchFts("hello", 10))
        assertTrue(db.eventDao().searchFts("missing", 10).isEmpty())
    }

    // ---- entity ----

    @Test
    fun entityDaoRoundTripAndNormalizedNameUniqueness() = runBlocking {
        val firstId = db.entityDao().upsert(
            EntityEntity(
                normalizedName = "张三",
                entityType = "person",
                name = "张三",
                description = "学生",
                createdAt = now,
            ),
        )
        val stored = db.entityDao().byId(firstId)!!
        assertEquals("person", stored.entityType)
        assertEquals("张三", db.entityDao().byNormalizedName("张三")?.name)

        // 跨文档复用：同 normalizedName 再次 upsert → REPLACE 覆盖（仍只有一行）
        db.entityDao().upsert(
            stored.copy(id = 0, description = "学习中的学生", createdAt = now + 1),
        )
        assertEquals(1, db.entityDao().count())
        assertEquals("学习中的学生", db.entityDao().byNormalizedName("张三")?.description)
        assertEquals(listOf("张三"), db.entityDao().byType("person", 10).map { it.normalizedName })
        assertEquals(1, db.entityDao().byNormalizedNames(listOf("张三")).size)

        db.entityDao().insertFts(EntityFtsEntity(entityId = firstId, text = "hello entity"))
        assertEquals(1, db.entityDao().countFts())
        assertEquals(listOf(firstId), db.entityDao().searchFts("hello", 10))

        db.entityDao().deleteById(firstId)
        assertNull(db.entityDao().byId(firstId))
        assertForeignKeyCheckEmpty()
    }

    // ---- event_entity ----

    @Test
    fun eventEntityRelationRoundTrip() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val eventId = db.eventDao().upsert(EventEntity(chunkId = chunkIds.first(), canonicalText = "x", confidence = 1.0))
        val entityId = db.entityDao().upsert(EntityEntity(normalizedName = "上海", entityType = "location", name = "上海"))

        db.eventEntityDao().upsert(
            EventEntityRel(eventId = eventId, entityId = entityId, role = "subject", weight = 0.5, description = "弱关联"),
        )
        val coreRelId = db.eventEntityDao().upsert(
            EventEntityRel(eventId = eventId, entityId = entityId, role = "subject", weight = 1.0, description = "核心关联"),
        )
        val rels = db.eventEntityDao().byEvent(eventId)
        assertEquals(2, rels.size)
        assertEquals("按 weight DESC 排序", 1.0, rels.first().weight, 0.0)
        assertEquals("核心关联", rels.first().description)
        assertEquals(2, db.eventEntityDao().byEntity(entityId).size)
        assertEquals(2, db.eventEntityDao().all().size)
        assertEquals(listOf(coreRelId), db.eventEntityDao().byEvent(eventId).filter { it.weight == 1.0 }.map { it.id })

        db.eventEntityDao().deleteByEvent(eventId)
        assertEquals(0, db.eventEntityDao().count())
        assertForeignKeyCheckEmpty()
    }

    // ---- sag_vector ----

    @Test
    fun sagVectorDaoRoundTrip() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val eventId = db.eventDao().upsert(EventEntity(chunkId = chunkIds.first(), canonicalText = "x", confidence = 1.0))
        val entityId = db.entityDao().upsert(EntityEntity(normalizedName = "数学", entityType = "subject", name = "数学"))
        val spaceId = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu"

        db.sagVectorDao().insertAll(
            listOf(
                sagVector(SagOwnerType.EVENT, eventId, SagVectorRole.EVENT_CONTENT.wire, spaceId),
                sagVector(SagOwnerType.ENTITY, entityId, SagVectorRole.ENTITY_DESCRIPTION.wire, spaceId),
            ),
        )
        assertEquals(2, db.sagVectorDao().count())
        assertEquals(1, db.sagVectorDao().byOwner(SagOwnerType.EVENT.value, eventId).size)
        assertEquals(1, db.sagVectorDao().byOwner(SagOwnerType.ENTITY.value, entityId).size)
        assertEquals(1, db.sagVectorDao().byOwners(SagOwnerType.EVENT.value, listOf(eventId)).size)
        assertEquals(1, db.sagVectorDao().bySpace(spaceId, SagVectorRole.EVENT_CONTENT.wire, SagOwnerType.EVENT.value, 10).size)
        assertEquals(2, db.sagVectorDao().countBySpace(spaceId))
        assertEquals(2, db.sagVectorDao().page(10, 0).size)
        assertEquals(1, db.sagVectorDao().page(2, 1).size)

        val vector = db.sagVectorDao().byOwner(SagOwnerType.EVENT.value, eventId).first()
        assertEquals(2, vector.dimensions)
        assertEquals(8, vector.vectorBlob.size)

        db.sagVectorDao().deleteByOwner(SagOwnerType.EVENT.value, eventId)
        assertEquals(1, db.sagVectorDao().count())
        db.sagVectorDao().deleteBySpace(spaceId)
        assertEquals(0, db.sagVectorDao().count())
        assertForeignKeyCheckEmpty()
    }

    // ---- extraction_job ----

    @Test
    fun extractionJobDaoStateMachineRoundTrip() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val docId = "doc1"
        val queuedId = "job-queued"
        val failedId = "job-failed"
        db.extractionJobDao().upsertAll(
            listOf(
                extractionJob(queuedId, docId, chunkIds[0], ExtractionJobStatus.QUEUED, createdAt = now),
                extractionJob(failedId, docId, chunkIds[1], ExtractionJobStatus.FAILED, createdAt = now + 1),
            ),
        )
        assertEquals(2, db.extractionJobDao().byDocument(docId).size)
        assertEquals(listOf(queuedId), db.extractionJobDao().nextQueued(ExtractionJobStatus.QUEUED.value, 10).map { it.id })
        assertEquals(1, db.extractionJobDao().activeCount(ExtractionJobStatus.ACTIVE_VALUES))
        assertEquals(1, db.extractionJobDao().countByStatus(ExtractionJobStatus.FAILED.value))

        // 原子认领：只有一次成功
        assertEquals(1, db.extractionJobDao().claim(queuedId, ExtractionJobStatus.QUEUED.value, ExtractionJobStatus.RUNNING.value, now + 2))
        assertEquals(0, db.extractionJobDao().claim(queuedId, ExtractionJobStatus.QUEUED.value, ExtractionJobStatus.RUNNING.value, now + 3))
        val claimed = db.extractionJobDao().get(queuedId)!!
        assertEquals(ExtractionJobStatus.RUNNING.value, claimed.status)
        assertEquals(1, claimed.attempt)

        // done 清空错误
        db.extractionJobDao().markFailed(queuedId, ExtractionJobStatus.FAILED.value, "EXTRACT_TIMEOUT", "timeout", now + 4)
        val failed = db.extractionJobDao().get(queuedId)!!
        assertEquals(ExtractionJobStatus.FAILED.value, failed.status)
        assertEquals("EXTRACT_TIMEOUT", failed.errorCode)
        db.extractionJobDao().markDone(queuedId, ExtractionJobStatus.DONE.value, now + 5)
        val done = db.extractionJobDao().get(queuedId)!!
        assertEquals(ExtractionJobStatus.DONE.value, done.status)
        assertNull(done.errorCode)
        assertNull(done.errorDetail)

        // requeue / 中断回收 / 取消 / 删除
        db.extractionJobDao().requeue(failedId, ExtractionJobStatus.QUEUED.value, now + 6)
        assertEquals(ExtractionJobStatus.QUEUED.value, db.extractionJobDao().get(failedId)!!.status)
        db.extractionJobDao().claim(failedId, ExtractionJobStatus.QUEUED.value, ExtractionJobStatus.RUNNING.value, now + 7)
        assertEquals(1, db.extractionJobDao().requeueInterrupted(listOf(ExtractionJobStatus.RUNNING.value), ExtractionJobStatus.QUEUED.value, now + 8))
        assertEquals(ExtractionJobStatus.QUEUED.value, db.extractionJobDao().get(failedId)!!.status)
        db.extractionJobDao().cancelByDocument(docId, ExtractionJobStatus.CANCELLED.value, now + 9)
        assertTrue(db.extractionJobDao().byDocument(docId).all { it.status == ExtractionJobStatus.CANCELLED.value })
        db.extractionJobDao().deleteByDocument(docId)
        assertEquals(0, db.extractionJobDao().all().size)
        assertForeignKeyCheckEmpty()
    }

    @Test
    fun deletingChunkCascadesEventAndExtractionJob() = runBlocking {
        val chunkIds = seedDocumentWithChunks()
        val chunkId = chunkIds.first()
        db.eventDao().upsert(EventEntity(chunkId = chunkId, canonicalText = "x", confidence = 1.0))
        db.extractionJobDao().upsert(extractionJob("job-1", "doc1", chunkId, ExtractionJobStatus.QUEUED))
        assertEquals(1, db.eventDao().count())

        db.openHelper.writableDatabase.execSQL("DELETE FROM chunk WHERE id = $chunkId")
        assertEquals("event 应随 chunk 级联删除", 0, db.eventDao().count())
        assertEquals("extraction_job 应随 chunk 级联删除", 0, db.extractionJobDao().all().size)
        assertForeignKeyCheckEmpty()
    }

    // ---- chat / query_trace ----

    @Test
    fun chatConversationMessageCitationRoundTripAndCascade() = runBlocking {
        db.chatDao().upsertConversation(
            ChatConversationEntity(id = "conv1", title = "问答一", sourceScope = null, createdAt = now, updatedAt = now),
        )
        db.chatDao().upsertMessages(
            listOf(
                ChatMessageEntity("m1", "conv1", ChatRole.USER.value, "问题", now, null),
                ChatMessageEntity("m2", "conv1", ChatRole.ASSISTANT.value, "回答", now + 1, "gpt-4o-mini"),
            ),
        )
        db.chatDao().upsertCitations(
            listOf(MessageCitationEntity(messageId = "m2", chunkId = seedDocumentWithChunks().first(), rank = 0)),
        )
        assertEquals("问答一", db.chatDao().conversation("conv1")?.title)
        assertEquals(listOf("m1", "m2"), db.chatDao().messages("conv1").map { it.id })
        assertEquals(1, db.chatDao().citations("m2").size)
        assertEquals(1, db.chatDao().recentConversations(10).size)

        db.chatDao().renameConversation("conv1", "新标题", now + 2)
        assertEquals("新标题", db.chatDao().conversation("conv1")?.title)

        // 删除会话 → 消息级联 → 引用级联
        db.chatDao().deleteConversation("conv1")
        assertNull(db.chatDao().conversation("conv1"))
        assertTrue(db.chatDao().messages("conv1").isEmpty())
        assertTrue("message_citation 应随 message 级联删除", db.chatDao().citations("m2").isEmpty())
        assertForeignKeyCheckEmpty()
    }

    @Test
    fun queryTraceRetentionAndCleanup() = runBlocking {
        (0 until 5).forEach { i ->
            db.queryTraceDao().insert(
                QueryTraceEntity(
                    queryHash = "hash-$i",
                    mode = "hybrid",
                    timingJson = """{"kwMs":$i}""",
                    degradedReason = if (i == 0) "offline" else null,
                    createdAt = now + i,
                ),
            )
        }
        assertEquals(5, db.queryTraceDao().count())
        assertEquals(listOf("hash-4", "hash-3"), db.queryTraceDao().recent(2).map { it.queryHash })
        assertEquals(3, db.queryTraceDao().keepRecent(2))
        assertEquals(2, db.queryTraceDao().count())
        assertEquals(1, db.queryTraceDao().deleteOlderThan(now + 4))
        db.queryTraceDao().deleteAll()
        assertEquals(0, db.queryTraceDao().count())
        assertForeignKeyCheckEmpty()
    }

    // ---- helpers ----

    private suspend fun seedDocumentWithChunks(): List<Long> {
        db.sourceDao().upsert(SourceEntity("src1", "B8 测试来源", "", null, now, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc1", sourceId = "src1", title = "B8 测试文档", checksum = "sum-b8",
                parserId = "txt-utf8-v1", createdAt = now, status = DocStatus.READY.value,
            ),
        )
        return db.chunkDao().insertAll(
            listOf(
                ChunkEntity(documentId = "doc1", ordinal = 0, content = "第一段", charStart = 0, charEnd = 3, tokenEstimate = 3),
                ChunkEntity(documentId = "doc1", ordinal = 1, content = "第二段", charStart = 3, charEnd = 6, tokenEstimate = 3),
            ),
        )
    }

    private fun sagVector(owner: SagOwnerType, ownerId: Long, role: String, spaceId: String) = SagVectorEntity(
        ownerType = owner.value,
        ownerId = ownerId,
        role = role,
        spaceId = spaceId,
        dimensions = 2,
        vectorBlob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(0.25f).array(),
        vectorNorm = 1.0,
        createdAt = now,
    )

    private fun extractionJob(
        id: String,
        documentId: String,
        chunkId: Long,
        status: ExtractionJobStatus,
        createdAt: Long = now,
    ) = ExtractionJobEntity(
        id = id,
        documentId = documentId,
        chunkId = chunkId,
        status = status.value,
        attempt = 0,
        errorCode = null,
        errorDetail = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private suspend fun assertForeignKeyCheckEmpty() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check")
        cursor.use {
            val violations = it.count
            val details = buildString {
                while (it.moveToNext()) {
                    append("表=").append(it.getString(0)).append(" rowid=").append(it.getLong(1)).append("; ")
                }
            }
            assertEquals("foreign_key_check 应为空：$details", 0, violations)
        }
    }
}
