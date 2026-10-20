package com.zhibox.memory.core.graph

import android.app.Application
import androidx.room.withTransaction
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.3 Explore 图谱只读加载（Robolectric + 真实 Room）：
 * - 只加载 active event；sourceId 过滤；query trim/空=全部；
 * - 关键词匹配 event title/summary/content、entity name、**alias**；
 * - 关联实体派生（event_entity）、边去重、source/document 传递；
 * - 事件 ≤200 / 实体 ≤400 上限 + truncated + totalEvents；
 * - 空库、无命中文案、只读（DB 内容不变）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExploreGraphRepositoryRobolectricTest {

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

    // ---- 基础过滤/查询 ----

    @Test
    fun blankQueryLoadsAllActiveEventsAndDerivedEntities() = runBlocking {
        val seed = seedLibrary()
        val graph = ExploreGraphRepository.load(db)

        assertEquals("只含 active event（inactive e4 排除）", 3, graph.eventNodes.size)
        assertEquals(3, graph.totalEvents)
        assertFalse(graph.truncated)
        assertEquals(ExploreGraphBuilder.MAX_EVENTS, graph.maxEvents)
        assertTrue(graph.eventNodes.none { it.eventId == seed.inactiveEventId })

        assertEquals("实体只来自命中事件的 event_entity 关联", 3, graph.entityNodes.size)
        assertEquals(setOf("t:${seed.entityShanghai}", "t:${seed.entityZhang}", "t:${seed.entityBeijing}"),
            graph.entityNodes.map { it.id }.toSet())
        assertEquals("4 条 event→entity 边", 4, graph.edges.size)

        val event1 = graph.eventNodes.single { it.eventId == seed.eventShanghai }
        assertEquals("上海学习", event1.label)
        assertEquals("张三在上海", event1.subtitle)
        assertEquals("src-A", event1.sourceId)
        assertEquals("doc-A1", event1.documentId)
        assertEquals("边只连存在节点", graph.edges.size, graph.edges.map { it.from to it.to }.toSet().size)
    }

    @Test
    fun sourceFilterOnlyLoadsEventsFromThatSource() = runBlocking {
        val seed = seedLibrary()
        val graph = ExploreGraphRepository.load(db, sourceId = "src-A")

        assertEquals(2, graph.eventNodes.size)
        assertEquals(2, graph.totalEvents)
        assertEquals(setOf(seed.eventShanghai, seed.eventMath), graph.eventNodes.map { it.eventId }.toSet())
        assertTrue(graph.eventNodes.all { it.sourceId == "src-A" })
        assertEquals("实体=上海/张三", 2, graph.entityNodes.size)
        assertEquals(3, graph.edges.size)

        val noMatch = ExploreGraphRepository.load(db, sourceId = "src-missing")
        assertTrue(noMatch.nodes.isEmpty())
        assertTrue(noMatch.edges.isEmpty())
        assertEquals(0, noMatch.totalEvents)
    }

    @Test
    fun queryMatchesEventTitleSummaryAndContent() = runBlocking {
        val seed = seedLibrary()
        val byTitle = ExploreGraphRepository.load(db, query = "数学")
        assertEquals(1, byTitle.totalEvents)
        assertEquals(listOf(seed.eventMath), byTitle.eventNodes.map { it.eventId })

        val byContent = ExploreGraphRepository.load(db, query = "历史") // 只在 inactive e4 content 中
        assertEquals("inactive event 不参与查询", 0, byContent.totalEvents)

        val trimmed = ExploreGraphRepository.load(db, query = "   代数   ")
        assertEquals("query trim 后匹配 summary", 1, trimmed.totalEvents)
        assertEquals(listOf(seed.eventMath), trimmed.eventNodes.map { it.eventId })

        val blank = ExploreGraphRepository.load(db, query = "    ")
        assertEquals("空白 query = 全部 active", 3, blank.totalEvents)
    }

    @Test
    fun queryMatchesEntityNameAndAlias() = runBlocking {
        val seed = seedLibrary()

        val byEntityName = ExploreGraphRepository.load(db, query = "张三")
        assertEquals("entity name 命中其关联事件 e1/e2", 2, byEntityName.totalEvents)
        assertEquals(setOf(seed.eventShanghai, seed.eventMath), byEntityName.eventNodes.map { it.eventId }.toSet())

        val byAlias = ExploreGraphRepository.load(db, query = "沪上")
        assertEquals("alias 命中关联事件（event 正文无该词）", 1, byAlias.totalEvents)
        assertEquals(listOf(seed.eventShanghai), byAlias.eventNodes.map { it.eventId })
        assertTrue(byAlias.eventNodes.single().label.contains("上海"))
        assertTrue(
            "alias 命中的实体应进入图",
            byAlias.entityNodes.any { it.entityId == seed.entityShanghai },
        )
    }

    @Test
    fun noMatchReturnsEmptyGraph() = runBlocking {
        seedLibrary()
        val graph = ExploreGraphRepository.load(db, query = "绝对不存在的关键词")
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertEquals(0, graph.totalEvents)
        assertFalse(graph.truncated)
    }

    @Test
    fun emptyDatabaseReturnsEmptyGraph() = runBlocking {
        val graph = ExploreGraphRepository.load(db, maxEvents = 7)
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertEquals(0, graph.totalEvents)
        assertFalse(graph.truncated)
        assertEquals(7, graph.maxEvents)
    }

    // ---- 上限 ----

    @Test
    fun eventLimitCapsNodesAndTotalEventsRecordsFullCount() = runBlocking {
        seedManyEvents(205)
        val graph = ExploreGraphRepository.load(db, maxEvents = 10)

        assertEquals(10, graph.eventNodes.size)
        assertEquals(10, graph.maxEvents)
        assertEquals("totalEvents=未截断全量", 205, graph.totalEvents)
        assertTrue(graph.truncated)
        assertTrue(graph.entityNodes.isEmpty())
    }

    @Test
    fun entityLimitCapsDerivedEntitiesAndEdges() = runBlocking {
        val entityIds = seedOneEventWithEntities(401)
        val graph = ExploreGraphRepository.load(db)

        assertEquals(1, graph.eventNodes.size)
        assertEquals(ExploreGraphBuilder.MAX_ENTITIES, graph.entityNodes.size)
        assertEquals(ExploreGraphBuilder.MAX_ENTITIES, graph.edges.size)
        assertTrue(graph.truncated)
        assertTrue(graph.entityNodes.all { it.entityId in entityIds.take(ExploreGraphBuilder.MAX_ENTITIES) })
    }

    // ---- 只读 ----

    @Test
    fun loadIsReadOnly() = runBlocking {
        seedLibrary()
        val before = counts()
        ExploreGraphRepository.load(db)
        ExploreGraphRepository.load(db, sourceId = "src-A", query = "上海", maxEvents = 5)
        assertEquals("Explore 加载不得写库", before, counts())
    }

    // ---- seed ----

    private data class Seed(
        val eventShanghai: Long,
        val eventMath: Long,
        val inactiveEventId: Long,
        val entityShanghai: Long,
        val entityZhang: Long,
        val entityBeijing: Long,
    )

    private suspend fun seedLibrary(): Seed = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.sourceDao().upsert(SourceEntity("src-A", "来源A", "", null, 1, "active"))
            db.sourceDao().upsert(SourceEntity("src-B", "来源B", "", null, 2, "active"))
            db.documentDao().upsertAll(
                listOf(
                    DocumentEntity("doc-A1", "src-A", "A1", "sum-a1", "txt-utf8-v1", 10, DocStatus.READY.value),
                    DocumentEntity("doc-A2", "src-A", "A2", "sum-a2", "txt-utf8-v1", 11, DocStatus.READY.value),
                    DocumentEntity("doc-B1", "src-B", "B1", "sum-b1", "txt-utf8-v1", 12, DocStatus.READY.value),
                ),
            )
            val chunks = db.chunkDao().insertAll(
                listOf(
                    ChunkEntity(documentId = "doc-A1", ordinal = 0, content = "上海正文", charStart = 0, charEnd = 4, tokenEstimate = 4),
                    ChunkEntity(documentId = "doc-A2", ordinal = 0, content = "正文数学", charStart = 0, charEnd = 4, tokenEstimate = 4),
                    ChunkEntity(documentId = "doc-B1", ordinal = 0, content = "旅行正文", charStart = 0, charEnd = 4, tokenEstimate = 4),
                ),
            )
            val e1 = db.eventDao().upsert(
                EventEntity(
                    chunkId = chunks[0], canonicalText = "上海学习", confidence = 0.9,
                    title = "上海学习", summary = "张三在上海", content = "上海正文",
                    language = "zh", status = EventStatus.ACTIVE.value,
                    extractionVersion = "ev-1", active = true, createdAt = 20,
                ),
            )
            val e2 = db.eventDao().upsert(
                EventEntity(
                    chunkId = chunks[1], canonicalText = "数学笔记", confidence = 0.8,
                    title = "数学笔记", summary = "代数", content = "正文数学",
                    language = "zh", status = EventStatus.ACTIVE.value,
                    extractionVersion = "ev-2", active = true, createdAt = 21,
                ),
            )
            val e3 = db.eventDao().upsert(
                EventEntity(
                    chunkId = chunks[2], canonicalText = "北京旅行", confidence = 0.7,
                    title = "北京旅行", summary = "北京故宫", content = "旅行正文",
                    language = "zh", status = EventStatus.ACTIVE.value,
                    extractionVersion = "ev-3", active = true, createdAt = 22,
                ),
            )
            val e4 = db.eventDao().upsert(
                EventEntity(
                    chunkId = chunks[0], canonicalText = "旧上海事件", confidence = 0.4,
                    title = "旧上海事件", summary = "旧摘要", content = "上海历史",
                    language = "zh", status = EventStatus.SUPERSEDED.value,
                    extractionVersion = "ev-old", active = false, supersededBy = e1, createdAt = 19,
                ),
            )

            val entShanghai = db.entityDao().upsert(
                EntityEntity(normalizedName = "上海", entityType = "location", name = "上海", description = "城市", createdAt = 30),
            )
            val entZhang = db.entityDao().upsert(
                EntityEntity(normalizedName = "张三", entityType = "person", name = "张三", description = "学生", createdAt = 31),
            )
            val entBeijing = db.entityDao().upsert(
                EntityEntity(normalizedName = "北京", entityType = "location", name = "北京", description = "城市", createdAt = 32),
            )
            db.entityAliasDao().upsertAll(
                listOf(
                    EntityAliasEntity(entityId = entShanghai, alias = "沪上", normalizedAlias = "沪上", createdAt = 40),
                    EntityAliasEntity(entityId = entZhang, alias = "小张", normalizedAlias = "小张", createdAt = 41),
                ),
            )
            db.eventEntityDao().upsertAll(
                listOf(
                    EventEntityRel(eventId = e1, entityId = entShanghai, role = "location", weight = 1.0),
                    EventEntityRel(eventId = e1, entityId = entZhang, role = "person", weight = 1.0),
                    EventEntityRel(eventId = e2, entityId = entZhang, role = "person", weight = 1.0),
                    EventEntityRel(eventId = e3, entityId = entBeijing, role = "location", weight = 1.0),
                ),
            )
            Seed(e1, e2, e4, entShanghai, entZhang, entBeijing)
        }
    }

    private suspend fun seedManyEvents(count: Int) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.sourceDao().upsert(SourceEntity("src-many", "多事件来源", "", null, 1, "active"))
            db.documentDao().upsert(
                DocumentEntity("doc-many", "src-many", "多事件文档", "sum-many", "txt-utf8-v1", 2, DocStatus.READY.value),
            )
            val chunkId = db.chunkDao().insertAll(
                listOf(ChunkEntity(documentId = "doc-many", ordinal = 0, content = "多事件正文", charStart = 0, charEnd = 5, tokenEstimate = 5)),
            ).single()
            db.eventDao().upsertAll(
                (1..count).map { index ->
                    EventEntity(
                        chunkId = chunkId,
                        canonicalText = "事件$index",
                        confidence = 0.5,
                        title = "事件$index",
                        summary = "摘要$index",
                        content = "内容$index",
                        status = EventStatus.ACTIVE.value,
                        extractionVersion = "ev-many-$index",
                        active = true,
                        createdAt = 100L + index,
                    )
                },
            )
        }
    }

    private suspend fun seedOneEventWithEntities(entityCount: Int): List<Long> = withContext(Dispatchers.IO) {
        db.sourceDao().upsert(SourceEntity("src-cap", "上限来源", "", null, 1, "active"))
        db.documentDao().upsert(
            DocumentEntity("doc-cap", "src-cap", "上限文档", "sum-cap", "txt-utf8-v1", 2, DocStatus.READY.value),
        )
        val chunkId = db.chunkDao().insertAll(
            listOf(ChunkEntity(documentId = "doc-cap", ordinal = 0, content = "上限正文", charStart = 0, charEnd = 4, tokenEstimate = 4)),
        ).single()
        val eventId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkId,
                canonicalText = "上限事件",
                confidence = 0.9,
                title = "上限事件",
                summary = "上限摘要",
                content = "上限内容",
                status = EventStatus.ACTIVE.value,
                extractionVersion = "ev-cap",
                active = true,
                createdAt = 10,
            ),
        )
        val entityIds = (1..entityCount).map { index ->
            db.entityDao().upsert(
                EntityEntity(
                    normalizedName = "上限实体$index",
                    entityType = "person",
                    name = "上限实体$index",
                    description = "",
                    createdAt = 20L + index,
                ),
            )
        }
        db.eventEntityDao().upsertAll(
            entityIds.map { entityId ->
                EventEntityRel(eventId = eventId, entityId = entityId, role = "person", weight = 1.0)
            },
        )
        entityIds
    }

    private suspend fun counts(): String = withContext(Dispatchers.IO) {
        buildString {
            append("sources=").append(db.sourceDao().all().size).append(';')
            append("docs=").append(db.documentDao().all().size).append(';')
            append("chunks=").append(db.chunkDao().count()).append(';')
            append("events=").append(db.eventDao().all()).append(';')
            append("entities=").append(db.entityDao().all()).append(';')
            append("aliases=").append(db.entityAliasDao().all()).append(';')
            append("links=").append(db.eventEntityDao().count())
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
