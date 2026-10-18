package com.zhibox.memory.core.sag

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.extraction.EmbeddingSagGateway
import com.zhibox.memory.core.llm.MockLlmQueryEntityExtractor
import com.zhibox.memory.core.llm.MockSagContextSelector
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EntityFtsEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventFtsEntity
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B10 多跳评测（受控合成 KB，真实端侧 embedding；120 查询 ×3 模式）。
 * 临时/评测型 instrumentation：不做 Gate 断言，结果导出 files/b10-eval/results.json 由宿主机计分。
 */
@RunWith(AndroidJUnit4::class)
class B10MultiHopEvalTest {

    private fun blob(values: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { b.putFloat(it) }
        return b.array()
    }

    private fun readAsset(ctx: Context, name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("b10/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun runMultiHopEval() = runBlocking {
        // 默认门禁/IDE 不跑（需模型+耗时）；手动：-e b10Eval 1
        Assume.assumeTrue(
            "B10 多跳评测需手动开启（-e b10Eval 1）",
            InstrumentationRegistry.getArguments().getString("b10Eval") == "1",
        )
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.get(ctx)
        db.clearAllTables()
        val kb = JSONObject(readAsset(ctx, "kb.json"))
        val qs = JSONObject(readAsset(ctx, "queries.json")).getJSONArray("queries")
        val docs = kb.getJSONArray("documents")
        val chunkArr = kb.getJSONArray("chunks")
        val now = 1_700_000_000_000L
        db.withTransaction {
            db.sourceDao().upsert(SourceEntity("src-b10-eval", "B10 多跳评测", "", null, now, "active"))
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                db.documentDao().upsert(
                    DocumentEntity(
                        id = d.getString("id"), sourceId = "src-b10-eval", title = d.getString("title"),
                        checksum = "b10-${d.getString("id")}", parserId = "txt-utf8-v1",
                        createdAt = now, status = DocStatus.READY.value, sizeBytes = 0,
                    ),
                )
            }
        }
        val chunkIdByIndex = LongArray(chunkArr.length())
        val chunkContent = ArrayList<String>(chunkArr.length())
        for (i in 0 until chunkArr.length()) {
            val c = chunkArr.getJSONObject(i)
            chunkIdByIndex[i] = db.chunkDao().insertAll(
                listOf(ChunkEntity(
                    documentId = c.getString("documentId"), ordinal = c.getInt("ordinal"),
                    content = c.getString("content"), charStart = 0, charEnd = c.getString("content").length,
                    tokenEstimate = c.getString("content").length,
                )),
            ).single()
            chunkContent += c.getString("content")
            db.chunkDao().insertFts(
                listOf(ChunkFtsEntity(chunkId = chunkIdByIndex[i], bigramContent = BigramTokenizer.toFtsText(c.getString("content")))),
            )
        }
        // 事件/实体/incidence/FTS
        val eventIdByIndex = LongArray(chunkArr.length())
        val entityIdByName = LinkedHashMap<String, Long>()
        val entityText = LinkedHashMap<String, String>()
        for (i in 0 until chunkArr.length()) {
            val c = chunkArr.getJSONObject(i)
            val e = c.getJSONObject("event")
            val content = e.getString("content")
            val eventId = db.eventDao().upsert(
                EventEntity(
                    chunkId = chunkIdByIndex[i], canonicalText = content, confidence = 1.0,
                    title = e.getString("title"), summary = e.getString("summary"), content = content,
                    language = "zh", status = EventStatus.ACTIVE.value,
                    extractorVersion = "b10-eval", extractionVersion = "b10-$i",
                    modelId = "b10-eval", promptVersion = "v1", active = true, createdAt = now,
                ),
            )
            eventIdByIndex[i] = eventId
            db.eventDao().insertFts(EventFtsEntity(eventId = eventId, text = BigramTokenizer.toFtsText(e.getString("title") + "\n" + e.getString("summary") + "\n" + content)))
            val ents = c.getJSONArray("entities")
            for (j in 0 until ents.length()) {
                val en = ents.getJSONObject(j)
                val name = en.getString("name").trim()
                val norm = name.lowercase()
                val entId = entityIdByName[norm] ?: run {
                    val id = db.entityDao().upsert(
                        EntityEntity(normalizedName = norm, entityType = en.getString("type"), name = name, description = en.getString("description"), createdAt = now),
                    )
                    entityIdByName[norm] = id
                    entityText[norm] = name + "\n" + en.getString("description")
                    id
                }
                db.eventEntityDao().upsert(EventEntityRel(eventId = eventId, entityId = entId, role = en.getString("type"), weight = 1.0))
            }
        }
        for ((norm, id) in entityIdByName) {
            db.entityDao().insertFts(EntityFtsEntity(entityId = id, text = BigramTokenizer.toFtsText(entityText[norm].orEmpty())))
        }
        // chunk vectors
        val model = EmbeddingRuntime.findModel(ctx) ?: error("B10 eval 需要端侧 embedding 模型")
        EmbeddingRuntime.withProvider(ctx, model) { provider ->
            val spaceId = EmbeddingRuntime.registerSpace(ctx, provider, model)
            val vectors = provider.embedDocuments(chunkContent)
            db.embeddingDao().insertAll(
                chunkIdByIndex.indices.map { i ->
                    ChunkEmbeddingEntity(
                        chunkId = chunkIdByIndex[i], embeddingSpaceId = spaceId, role = "content",
                        vectorBlob = blob(vectors[i]), vectorNorm = 1.0, createdAt = now,
                    )
                },
            )
        }
        // event/entity SAG vectors（真实 embedding gateway；派生空间）
        val gateway = EmbeddingSagGateway(ctx)
        val eventTexts = (0 until chunkArr.length()).map { chunkArr.getJSONObject(it).getJSONObject("event").getString("content") }
        val eventVectors = gateway.embed(SagVectorRole.EVENT_CONTENT, eventTexts)
        db.sagVectorDao().upsertAll(
            eventVectors.mapIndexed { i, v ->
                SagVectorEntity(ownerType = SagOwnerType.EVENT.value, ownerId = eventIdByIndex[i], role = SagVectorRole.EVENT_CONTENT.wire, spaceId = v.spaceId, dimensions = v.dimensions, vectorBlob = v.vectorBlob, vectorNorm = v.vectorNorm, createdAt = now)
            },
        )
        val entNames = entityIdByName.keys.toList()
        val entTexts = entNames.map { entityText[it].orEmpty() }
        val entVectors = gateway.embed(SagVectorRole.ENTITY_DESCRIPTION, entTexts)
        db.sagVectorDao().upsertAll(
            entVectors.mapIndexed { i, v ->
                SagVectorEntity(ownerType = SagOwnerType.ENTITY.value, ownerId = entityIdByName[entNames[i]]!!, role = SagVectorRole.ENTITY_DESCRIPTION.wire, spaceId = v.spaceId, dimensions = v.dimensions, vectorBlob = v.vectorBlob, vectorNorm = v.vectorNorm, createdAt = now)
            },
        )
        println("B10_EVAL_SEED chunks=${chunkArr.length()} events=${db.eventDao().count()} entities=${db.entityDao().count()}")

        val retriever = SagRetriever(ctx, db)
        val multiRetriever = SagRetriever(
            ctx, db, llmAvailable = { true },
            llmExtractor = MockLlmQueryEntityExtractor(emptyList()),
            llmSelector = MockSagContextSelector(),
        )
        val out = JSONArray()
        for (mode in listOf(RetrievalMode.VECTOR, RetrievalMode.MULTI_ES_FAST, RetrievalMode.MULTI)) {
            val r = if (mode == RetrievalMode.MULTI) multiRetriever else retriever
            for (qi in 0 until qs.length()) {
                val q = qs.getJSONObject(qi)
                val expected = q.getJSONArray("evidence")
                val expectedIds = (0 until expected.length()).map { chunkIdByIndex[expected.getInt(it) - 1] }
                val result = r.retrieve(SagQuery(q.getString("query"), mode, topK = 10))
                out.put(JSONObject()
                    .put("mode", mode.wire).put("qid", q.getString("id")).put("query", q.getString("query"))
                    .put("hops", q.getInt("hops")).put("evidence", JSONArray(expectedIds))
                    .put("hits", JSONArray(result.hits.map { it.chunkId }))
                    .put("elapsedMs", result.trace.elapsedMs).put("degraded", result.degraded)
                    .put("refused", result.refused))
            }
        }
        val dir = File(ctx.filesDir, "b10-eval").apply { mkdirs() }
        File(dir, "results.json").writeText(out.toString(2))
        println("B10_EVAL_DONE results=${out.length()} dir=${dir.absolutePath}")
    }
}
