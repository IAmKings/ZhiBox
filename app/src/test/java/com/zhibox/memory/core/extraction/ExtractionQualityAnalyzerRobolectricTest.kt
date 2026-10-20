package com.zhibox.memory.core.extraction

import android.app.Application
import androidx.room.withTransaction
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B15.2 抽取质量只读诊断（Robolectric + 真实 Room）：
 * - seed 文档/ chunk / event / entity / alias / job，断言 counts/覆盖率/类型分布/状态与 errorCode 聚合；
 * - 空库全 0/空 map/比率 0（0 保护）；
 * - JSON 稳定键顺序 + 脱敏（不含正文/errorDetail/实体名/密钥；含 errorCode 聚合）；
 * - 只读：analyze 前后 DB 行内容完全一致（不写、不改派生索引）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtractionQualityAnalyzerRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    // ---- seed 聚合断言 ----

    @Test
    fun analyzeSeededLibraryReportsCountsCoverageTypesAndJobs() = runBlocking {
        val seed = seedLibrary()

        val report = ExtractionQualityAnalyzer.analyze(db)

        assertEquals("documentsTotal", 3, report.documentsTotal)
        assertEquals("readyDocuments", 2, report.readyDocuments)
        assertEquals("eventsTotal", 3, report.eventsTotal)
        assertEquals("eventsActive", 2, report.eventsActive)
        assertEquals("eventsDiscarded = total-active", 1, report.eventsDiscarded)
        assertEquals("blank title", 1, report.blankEventFields.title)
        assertEquals("blank summary", 1, report.blankEventFields.summary)
        assertEquals("blank content", 1, report.blankEventFields.content)

        assertEquals(3, report.entitiesTotal)
        assertEquals(2, report.entitiesWithAliases)
        assertEquals(3, report.aliasesTotal)
        assertEquals(mapOf("person" to 1, "unknown" to 2), report.entityTypeCounts)
        assertEquals("null/空白 entityType 归 unknown", listOf("person", "unknown"), report.entityTypeCounts.keys.toList())
        assertEquals(2, report.eventEntityLinks)

        assertEquals("doc1 + doc2 有 event，doc3 没有", 2, report.documentsWithEvents)
        assertEquals(1, report.documentsWithoutEvents)

        assertEquals(4, report.extractionJobsTotal)
        assertEquals(
            mapOf(
                ExtractionJobStatus.DONE.value to 1,
                ExtractionJobStatus.FAILED.value to 1,
                ExtractionJobStatus.NOT_INDEXED.value to 1,
                ExtractionJobStatus.QUEUED.value to 1,
            ),
            report.jobsByStatus,
        )
        assertEquals(
            "按字典序输出，只含非空 errorCode",
            listOf(ExtractionErrorCodes.NOT_CONFIGURED, "LLM_TIMEOUT"),
            report.jobsByErrorCode.keys.toList(),
        )
        assertEquals(1, report.jobsByErrorCode["LLM_TIMEOUT"])
        assertEquals(1, report.jobsByErrorCode[ExtractionErrorCodes.NOT_CONFIGURED])
        assertEquals("未配置项应进入 not_indexed 状态", 1, report.jobsByStatus[ExtractionJobStatus.NOT_INDEXED.value])

        assertEquals("extractionCoverage = 2/3", 2.0 / 3.0, report.extractionCoverage, 1e-9)
        assertEquals("aliasCoverage = 2/3", 2.0 / 3.0, report.aliasCoverage, 1e-9)
        assertEquals(seed.entityWithAliases, 2)
    }

    @Test
    fun emptyDatabaseReturnsAllZerosAndZeroRatios() = runBlocking {
        val report = ExtractionQualityAnalyzer.analyze(db)

        assertEquals(0, report.documentsTotal)
        assertEquals(0, report.readyDocuments)
        assertEquals(0, report.eventsTotal)
        assertEquals(0, report.eventsActive)
        assertEquals(0, report.eventsDiscarded)
        assertEquals(BlankEventFields(0, 0, 0), report.blankEventFields)
        assertEquals(0, report.entitiesTotal)
        assertEquals(0, report.entitiesWithAliases)
        assertEquals(0, report.aliasesTotal)
        assertTrue("空库类型分布为空", report.entityTypeCounts.isEmpty())
        assertEquals(0, report.eventEntityLinks)
        assertEquals(0, report.documentsWithEvents)
        assertEquals(0, report.documentsWithoutEvents)
        assertEquals(0, report.extractionJobsTotal)
        assertTrue(report.jobsByStatus.isEmpty())
        assertTrue(report.jobsByErrorCode.isEmpty())
        assertEquals(0.0, report.extractionCoverage, 0.0)
        assertEquals(0.0, report.aliasCoverage, 0.0)

        val json = JSONObject(report.toJson())
        assertEquals(0, json.getInt("documentsTotal"))
        assertEquals(0.0, json.getDouble("extractionCoverage"), 0.0)
    }

    // ---- JSON 脱敏/稳定顺序 ----

    @Test
    fun jsonIsRedactedAndStableWithoutBodiesOrErrorDetail() = runBlocking {
        seedLibrary()
        val report = ExtractionQualityAnalyzer.analyze(db)

        val json = report.toJson()
        assertEquals("同一报告多次序列化必须稳定", json, report.toJson())
        assertEquals("analyzer.toJson(report) 与 report.toJson 等价", json, ExtractionQualityAnalyzer.toJson(report))

        // 脱敏：不含 seed 的正文/标题/摘要/实体名/errorDetail/密钥
        listOf(
            "正文一", "事件一", "摘要一", "旧正文", "旧事件", "旧摘要",
            "张三", "无类型", "空类型", "小张", "某实体",
            "SECRET_ERROR_DETAIL_SHOULD_NOT_LEAK", "errorDetail",
            "sk-", "apiKey",
        ).forEach { secret ->
            assertFalse("JSON 不得包含敏感内容：$secret", json.contains(secret))
        }
        // 错误码/状态/计数可导出（不带 detail）
        assertTrue(json.contains("LLM_TIMEOUT"))
        assertTrue(json.contains(ExtractionErrorCodes.NOT_CONFIGURED))
        assertTrue(json.contains("\"not_indexed\":1"))

        // 稳定键顺序
        val orderedKeys = listOf(
            "documentsTotal", "readyDocuments", "eventsTotal", "eventsActive", "eventsDiscarded",
            "blankEventFields", "entitiesTotal", "entitiesWithAliases", "aliasesTotal",
            "entityTypeCounts", "eventEntityLinks", "documentsWithEvents", "documentsWithoutEvents",
            "extractionJobsTotal", "jobsByStatus", "jobsByErrorCode", "extractionCoverage", "aliasCoverage",
        )
        var lastIndex = -1
        orderedKeys.forEach { key ->
            val index = json.indexOf("\"$key\"")
            assertTrue("JSON 缺少键 $key：$json", index >= 0)
            assertTrue("JSON 键顺序不稳定（$key）：$json", index > lastIndex)
            lastIndex = index
        }

        val obj = JSONObject(json)
        assertEquals(3, obj.getInt("documentsTotal"))
        assertEquals(1, obj.getJSONObject("blankEventFields").getInt("title"))
        assertEquals(1, obj.getJSONObject("blankEventFields").getInt("summary"))
        assertEquals(1, obj.getJSONObject("blankEventFields").getInt("content"))
        assertEquals(2, obj.getJSONObject("entityTypeCounts").getInt("unknown"))
        assertEquals(1, obj.getJSONObject("jobsByErrorCode").getInt("LLM_TIMEOUT"))
        assertEquals(1, obj.getJSONObject("jobsByErrorCode").getInt(ExtractionErrorCodes.NOT_CONFIGURED))
        assertEquals(0.6667, obj.getDouble("extractionCoverage"), 1e-9)
    }

    // ---- 只读 ----

    @Test
    fun analyzeIsReadOnlyAndLeavesDatabaseUnchanged() = runBlocking {
        seedLibrary()
        val before = snapshot()

        val first = ExtractionQualityAnalyzer.analyze(db)
        val second = ExtractionQualityAnalyzer.analyze(db)

        assertEquals("同一快照两次分析结果一致", first, second)
        assertEquals("analyze 不得修改数据库任何行", before, snapshot())
    }

    // ---- seed/helpers ----

    private data class Seed(val entityWithAliases: Int)

    private suspend fun seedLibrary(): Seed {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.sourceDao().upsert(SourceEntity("src-q", "质量来源", "", null, 1, "active"))
                db.documentDao().upsertAll(
                    listOf(
                        DocumentEntity(
                            id = "doc-q1", sourceId = "src-q", title = "质量文档1", checksum = "sum-q1",
                            parserId = "txt-utf8-v1", createdAt = 2, status = DocStatus.READY.value,
                        ),
                        DocumentEntity(
                            id = "doc-q2", sourceId = "src-q", title = "质量文档2", checksum = "sum-q2",
                            parserId = "txt-utf8-v1", createdAt = 3, status = DocStatus.READY.value,
                        ),
                        DocumentEntity(
                            id = "doc-q3", sourceId = "src-q", title = "质量文档3", checksum = "sum-q3",
                            parserId = "txt-utf8-v1", createdAt = 4, status = DocStatus.FAILED.value,
                        ),
                    ),
                )
                val chunkIds = db.chunkDao().insertAll(
                    listOf(
                        ChunkEntity(documentId = "doc-q1", ordinal = 0, content = "正文一", charStart = 0, charEnd = 3, tokenEstimate = 3),
                        ChunkEntity(documentId = "doc-q1", ordinal = 1, content = "正文二", charStart = 3, charEnd = 6, tokenEstimate = 3),
                        ChunkEntity(documentId = "doc-q2", ordinal = 0, content = "正文三", charStart = 0, charEnd = 3, tokenEstimate = 3),
                        ChunkEntity(documentId = "doc-q3", ordinal = 0, content = "正文四", charStart = 0, charEnd = 3, tokenEstimate = 3),
                    ),
                )

                val e1 = db.eventDao().upsert(
                    EventEntity(
                        chunkId = chunkIds[0],
                        canonicalText = "事件一",
                        confidence = 0.9,
                        title = "事件一",
                        summary = "摘要一",
                        content = "正文一",
                        language = "zh",
                        status = EventStatus.ACTIVE.value,
                        extractionVersion = "ev-q1",
                        active = true,
                        createdAt = 10,
                    ),
                )
                // 空 title/summary/content 各计 1
                db.eventDao().upsert(
                    EventEntity(
                        chunkId = chunkIds[2],
                        canonicalText = "",
                        confidence = 0.5,
                        title = "",
                        summary = " ",
                        content = "",
                        language = "",
                        status = EventStatus.ACTIVE.value,
                        extractionVersion = "ev-q2",
                        active = true,
                        createdAt = 11,
                    ),
                )
                // 历史 superseded（active=0）→ eventsDiscarded=1
                db.eventDao().upsert(
                    EventEntity(
                        chunkId = chunkIds[0],
                        canonicalText = "旧事件",
                        confidence = 0.4,
                        title = "旧事件",
                        summary = "旧摘要",
                        content = "旧正文",
                        status = EventStatus.SUPERSEDED.value,
                        extractionVersion = "ev-old",
                        active = false,
                        supersededBy = e1,
                        createdAt = 9,
                    ),
                )

                val personId = db.entityDao().upsert(
                    EntityEntity(normalizedName = "张三", entityType = "person", name = "张三", description = "学生", createdAt = 20),
                )
                val nullTypeId = db.entityDao().upsert(
                    EntityEntity(normalizedName = "无类型", entityType = null, name = "无类型", description = "", createdAt = 21),
                )
                db.entityDao().upsert(
                    EntityEntity(normalizedName = "空类型", entityType = "", name = "空类型", description = "", createdAt = 22),
                )
                db.entityAliasDao().upsertAll(
                    listOf(
                        EntityAliasEntity(entityId = personId, alias = "小张", normalizedAlias = "小张", createdAt = 30),
                        EntityAliasEntity(entityId = personId, alias = "张生", normalizedAlias = "张生", createdAt = 31),
                        EntityAliasEntity(entityId = nullTypeId, alias = "某实体", normalizedAlias = "某实体", createdAt = 32),
                    ),
                )
                db.eventEntityDao().upsertAll(
                    listOf(
                        EventEntityRel(eventId = e1, entityId = personId, role = "person", weight = 1.0),
                        EventEntityRel(eventId = e1, entityId = nullTypeId, role = "other", weight = 0.5),
                    ),
                )

                db.extractionJobDao().upsertAll(
                    listOf(
                        job("job-q1", "doc-q1", chunkIds[0], ExtractionJobStatus.DONE, null, null),
                        job("job-q2", "doc-q1", chunkIds[1], ExtractionJobStatus.FAILED, "LLM_TIMEOUT", "SECRET_ERROR_DETAIL_SHOULD_NOT_LEAK"),
                        job("job-q3", "doc-q2", chunkIds[2], ExtractionJobStatus.QUEUED, null, null),
                        job("job-q4", "doc-q3", chunkIds[3], ExtractionJobStatus.NOT_INDEXED, ExtractionErrorCodes.NOT_CONFIGURED, "SECRET_ERROR_DETAIL_SHOULD_NOT_LEAK"),
                    ),
                )
            }
        }
        return Seed(entityWithAliases = 2)
    }

    private fun job(
        id: String,
        documentId: String,
        chunkId: Long,
        status: ExtractionJobStatus,
        errorCode: String?,
        errorDetail: String?,
    ) = ExtractionJobEntity(
        id = id,
        documentId = documentId,
        chunkId = chunkId,
        status = status.value,
        attempt = if (status == ExtractionJobStatus.DONE) 1 else 0,
        errorCode = errorCode,
        errorDetail = errorDetail,
        createdAt = 40,
        updatedAt = 41,
    )

    private suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        buildString {
            append("events=").append(db.eventDao().all()).append('\n')
            append("entities=").append(db.entityDao().all()).append('\n')
            append("aliases=").append(db.entityAliasDao().all()).append('\n')
            append("links=").append(db.eventEntityDao().count()).append('\n')
            append("jobs=").append(db.extractionJobDao().all()).append('\n')
            append("documents=").append(db.documentDao().all()).append('\n')
            append("chunks=").append(db.chunkDao().allChunks()).append('\n')
        }
    }

    private fun resetDatabaseSingleton() {
        val outerField = runCatching { AppDatabase::class.java.getDeclaredField("instance") }.getOrNull()
        val companionField = runCatching {
            Class.forName("com.zhibox.memory.data.db.AppDatabase\$Companion")
                .getDeclaredField("instance")
        }.getOrNull()
        val field = outerField ?: companionField
        checkNotNull(field) { "无法定位 AppDatabase.instance 单例字段" }
        val holder: Any? = if (field.declaringClass == AppDatabase::class.java) {
            null
        } else {
            field.declaringClass.getField("INSTANCE").get(null)
        }
        field.isAccessible = true
        runCatching { (field.get(holder) as? AppDatabase)?.close() }
        field.set(holder, null)
    }

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
