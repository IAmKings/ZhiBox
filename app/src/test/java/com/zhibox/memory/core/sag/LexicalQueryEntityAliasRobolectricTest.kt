package com.zhibox.memory.core.sag

import android.app.Application
import com.zhibox.memory.core.extraction.EntityAliasRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B15.1：LexicalQueryEntityExtractor 别名查询扩展（Robolectric + 真实 Room）：
 * - 别名精确命中 → canonical entity，source="entity_alias"、score=1.0；
 * - 问句中的子串别名（"我在沪上读书"）同样解析；
 * - canonical 精确名优先（source="exact"，不被 alias 覆盖）；
 * - 别名进入 entity_fts → bigram FTS 命中（source="entity_fts"）；
 * - 删除别名后不再命中；无别名时行为/排序不变（回归）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LexicalQueryEntityAliasRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var aliasRepository: EntityAliasRepository
    private lateinit var extractor: LexicalQueryEntityExtractor

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        aliasRepository = EntityAliasRepository(db)
        extractor = LexicalQueryEntityExtractor(db)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    @Test
    fun `exact alias resolves canonical entity with entity_alias source`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        aliasRepository.addAlias(entityId, "沪上")

        val entity = extractor.extract("沪上").firstOrNull { it.id == entityId }

        assertTrue("别名精确应命中：${extractor.extract("沪上")}", entity != null)
        assertEquals("canonical normalizedName", "上海", entity!!.normalizedName)
        assertEquals("entity_alias", entity.source)
        assertEquals(1.0, entity.score, 1e-9)
    }

    @Test
    fun `substring alias inside natural language query resolves canonical entity`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        aliasRepository.addAlias(entityId, "沪上")

        val entity = extractor.extract("我在沪上读书").firstOrNull { it.id == entityId }
        assertTrue("子串别名应命中：${extractor.extract("我在沪上读书")}", entity != null)
        assertEquals("entity_alias", entity!!.source)
    }

    @Test
    fun `canonical exact name keeps exact source when alias also exists`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        aliasRepository.addAlias(entityId, "沪上")

        val entity = extractor.extract("上海").firstOrNull { it.id == entityId }
        assertTrue(entity != null)
        assertEquals("canonical 名称精确命中优先 exact", "exact", entity!!.source)
        assertEquals(1.0, entity.score, 1e-9)
    }

    @Test
    fun `alias bigrams in entity_fts are fts-retrievable`() = runBlocking {
        val entityId = seedEntity("华东理工大学", "华东理工大学", "高校")
        aliasRepository.addAlias(entityId, "华东理工")

        // query 不是完整别名（不满足精确/子串别名），但 bigram 与 FTS 中的 alias 文本重叠
        val result = extractor.extract("东理工")
        val entity = result.firstOrNull { it.id == entityId }
        assertTrue("FTS 应召回：$result", entity != null)
        assertEquals("entity_fts", entity!!.source)
    }

    @Test
    fun `removed alias no longer resolves`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        val alias = aliasRepository.addAlias(entityId, "沪上")
        assertTrue(aliasRepository.removeAlias(alias.id))

        assertTrue(
            "删除别名后不应再通过别名命中：${extractor.extract("沪上")}",
            extractor.extract("沪上").none { it.id == entityId },
        )
    }

    @Test
    fun `no alias behavior remains unchanged`() = runBlocking {
        val entityId = seedEntity("张三", "张三", "学生")
        seedEntity("上海", "上海", "城市")

        val result = extractor.extract("张三")
        val entity = result.firstOrNull { it.id == entityId }
        assertTrue(result.isNotEmpty())
        assertTrue(entity != null)
        assertEquals("无别名时 exact 行为不变", "exact", entity!!.source)
        assertEquals(1.0, entity.score, 1e-9)
    }

    private suspend fun seedEntity(normalizedName: String, name: String = normalizedName, description: String = ""): Long {
        val id = db.entityDao().insertIgnore(
            EntityEntity(
                normalizedName = normalizedName,
                entityType = "person",
                name = name,
                description = description,
                createdAt = 1,
            ),
        )
        assertNotEquals(-1L, id)
        return id
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
