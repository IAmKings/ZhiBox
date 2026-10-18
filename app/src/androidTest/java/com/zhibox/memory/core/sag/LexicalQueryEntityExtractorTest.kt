package com.zhibox.memory.core.sag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EntityFtsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B10 离线词法查询实体抽取（instrumentation，真实 Room；**零网络/零 LLM**）：
 * - 精确 normalizedName → id 绑定、source="exact"、score=1.0；
 * - entity_fts bigram 召回 → source="entity_fts"、名次衰减 score、按 score desc/id asc 排序；
 * - 泛词（疑问/空泛名词）与单字实体过滤；空 query 返回空；未知实体返回空；
 * - maxEntities 截断。
 *
 * 该抽取器是 fast/multi 的离线实体种子来源；LLM 抽取失败/禁网时 Retriever 也回退到它（覆盖缺口见报告）。
 */
class LexicalQueryEntityExtractorTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

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

    @Test
    fun exactNormalizedNameReturnsBoundEntityWithSourceExact() = runBlocking {
        val id = seedEntity(normalizedName = "上海", type = "location", name = "上海", description = "学习地点")

        val result = LexicalQueryEntityExtractor(db).extract("上海")

        val entity = result.firstOrNull { it.id == id }
        assertTrue("应精确命中：$result", entity != null)
        assertEquals("location", entity!!.type)
        assertEquals("上海", entity.normalizedName)
        assertEquals(1.0, entity.score, 1e-9)
        assertEquals("exact", entity.source)
    }

    @Test
    fun ftsBigramMatchReturnsRankedEntitySeeds() = runBlocking {
        val zhang = seedEntity(normalizedName = "张三", type = "person", name = "张三", description = "学生")
        val shanghai = seedEntity(normalizedName = "上海", type = "location", name = "上海", description = "学习地点")
        val math = seedEntity(normalizedName = "数学", type = "subject", name = "数学", description = "学科")

        val result = LexicalQueryEntityExtractor(db).extract("张三在上海学习数学")

        assertTrue("应召回多个实体：$result", result.size >= 3)
        assertEquals("score 降序", result.map { it.score }, result.map { it.score }.sortedDescending())
        assertTrue("应含张三", result.any { it.id == zhang })
        assertTrue("应含上海", result.any { it.id == shanghai })
        assertTrue("应含数学", result.any { it.id == math })
        result.filter { it.id in setOf(zhang, shanghai, math) }.forEach { entity ->
            assertTrue("id 必须绑定 DB", entity.id > 0)
            assertEquals("非精确命中来源为 entity_fts", "entity_fts", entity.source)
            assertTrue("名次衰减分数在 [0.5,0.9]", entity.score in 0.5..0.9)
        }
    }

    @Test
    fun bigramSubstringMatchDoesNotRequireFullName() = runBlocking {
        val id = seedEntity(normalizedName = "上海学习", type = "location", name = "上海学习", description = "地点")

        val result = LexicalQueryEntityExtractor(db).extract("海学")

        val entity = result.firstOrNull { it.id == id }
        assertTrue("bigram「海学」应命中「上海学习」", entity != null)
        assertEquals("entity_fts", entity!!.source)
        assertTrue("非精确命中 score<1.0", entity.score < 1.0)
    }

    @Test
    fun genericWordsAndSingleCharEntitiesAreFiltered() = runBlocking {
        seedEntity(normalizedName = "什么", type = "tags", name = "什么", description = "")
        seedEntity(normalizedName = "学习", type = "subject", name = "学习", description = "")
        seedEntity(normalizedName = "唐", type = "person", name = "唐", description = "")

        val extractor = LexicalQueryEntityExtractor(db)
        assertEquals("泛词/单字不应返回", emptyList<QueryEntity>(), extractor.extract("什么问题"))
        assertEquals("单字实体不应返回", emptyList<QueryEntity>(), extractor.extract("唐"))
    }

    @Test
    fun blankQueryReturnsEmpty() = runBlocking {
        seedEntity(normalizedName = "上海", type = "location", name = "上海", description = "")
        val extractor = LexicalQueryEntityExtractor(db)
        assertTrue(extractor.extract("   ").isEmpty())
        assertTrue(extractor.extract("").isEmpty())
    }

    @Test
    fun unknownEntityReturnsEmpty() = runBlocking {
        seedEntity(normalizedName = "上海", type = "location", name = "上海", description = "")
        val extractor = LexicalQueryEntityExtractor(db)
        assertTrue("库里没有的实体名不应返回候选", extractor.extract("量子纠缠现象").isEmpty())
    }

    @Test
    fun maxEntitiesLimitIsApplied() = runBlocking {
        listOf("张三", "李四", "王五", "赵六", "钱七").forEach { name ->
            seedEntity(normalizedName = name, type = "person", name = name, description = "人物")
        }
        val extractor = LexicalQueryEntityExtractor(db, maxEntities = 2)
        val result = extractor.extract("张三 李四 王五 赵六 钱七")
        assertTrue("应被 maxEntities 截断：$result", result.size <= 2)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun lexicalExtractionIsPureDbAndNeedsNoLlmConfig() = runBlocking {
        // 词法抽取器是纯 DB 实现（零网络）；本用例只断言精确命中，不触碰全局 LLM 开关/配置。
        seedEntity(normalizedName = "上海", type = "location", name = "上海", description = "")

        val result = LexicalQueryEntityExtractor(db).extract("上海")
        assertEquals(1, result.size)
        assertEquals("exact", result.single().source)
        assertFalse(result.isEmpty())
    }

    // ---- helpers ----

    private suspend fun seedEntity(
        normalizedName: String,
        type: String,
        name: String,
        description: String,
    ): Long {
        val id = db.entityDao().upsert(
            EntityEntity(
                normalizedName = normalizedName,
                entityType = type,
                name = name,
                description = description,
                createdAt = 1_000L,
            ),
        )
        // 与 B9 pipeline 口径一致：entity FTS = name + description 的 bigram 文本
        val ftsText = listOf(name, description).filter { it.isNotBlank() }.joinToString(" ")
        db.entityDao().insertFts(
            EntityFtsEntity(
                entityId = id,
                text = BigramTokenizer.toFtsText(ftsText),
            ),
        )
        return id
    }
}
