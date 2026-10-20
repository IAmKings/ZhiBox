package com.zhibox.memory.core.extraction

import android.app.Application
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B15.1 schema v9：实体别名仓库（Robolectric + 真实 Room）：
 * - normalize 口径（复用 SagSupport.normalizeEntityName）、长度/控制字符/canonical/重复校验；
 * - 增删事务刷新 entity_fts（name+description+aliases 同级口径，别名可在 FTS 命中）；
 * - listFor 分组；删除后 FTS 不再命中；不改 entity 本体；
 * - SagFtsRebuilder 幂等并包含 aliases（备份恢复路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntityAliasRepositoryRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: EntityAliasRepository

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repository = EntityAliasRepository(db)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    // ---- normalize / validation ----

    @Test
    fun `add alias trims and stores normalized alias`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")

        val row = repository.addAlias(entityId, "  沪上  ")

        assertEquals("alias 应 trim 后存储", "沪上", row.alias)
        assertEquals("沪上", row.normalizedAlias)
        assertEquals(entityId, row.entityId)
        assertTrue("应持久化且自增 id", row.id > 0)
        assertEquals(row, db.entityAliasDao().byId(row.id))
    }

    @Test
    fun `add alias rejects invalid values with readable messages`() = runBlocking {
        val entityId = seedEntity("复旦大学", "复旦大学")

        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.addAlias(entityId, "  ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.addAlias(entityId, "复") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(entityId, "a".repeat(65)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(entityId, "复\u0001旦") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(entityId, "复旦大学") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(999_999L, "复旦") }
        }
        assertEquals("非法输入不得落库", 0, db.entityAliasDao().count())
    }

    @Test
    fun `duplicate normalized alias is rejected globally`() = runBlocking {
        val first = seedEntity("上海", "上海")
        val second = seedEntity("北京", "北京")

        repository.addAlias(first, "沪上")
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(second, " 沪上 ") }
        }
        assertTrue("应提示别名已存在：${error.message}", error.message!!.contains("已存在"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addAlias(first, "沪上") }
        }
        assertEquals(1, db.entityAliasDao().count())
    }

    @Test
    fun `alias cannot collide with another entity canonical name`() {
        runBlocking {
            val first = seedEntity("上海", "上海")
            seedEntity("北京", "北京")
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addAlias(first, "北京") }
            }
            assertTrue("应提示实体名冲突：${error.message}", error.message!!.contains("实体名称冲突"))
            assertEquals(0, db.entityAliasDao().count())
        }
    }

    @Test
    fun `dao unique constraint rejects duplicate normalized alias`() = runBlocking {
        val first = seedEntity("上海", "上海")
        val second = seedEntity("沪上", "沪上")
        db.entityAliasDao().insertIgnore(EntityAliasEntity(entityId = first, alias = "沪上", normalizedAlias = "沪上", createdAt = 1))
        val duplicate = db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = second, alias = "沪上", normalizedAlias = "沪上", createdAt = 2),
        )
        assertEquals("normalizedAlias 全局唯一", -1L, duplicate)
        assertEquals(1, db.entityAliasDao().count())
    }

    @Test
    fun `alias count per entity is capped`() {
        runBlocking {
            val entityId = seedEntity("上海", "上海")
            repeat(EntityAliasRepository.MAX_ALIASES_PER_ENTITY) { index ->
                repository.addAlias(entityId, "别名%02d".format(index))
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addAlias(entityId, "超出上限") }
            }
        }
    }

    @Test
    fun `listFor groups aliases and keeps entities without alias absent`() = runBlocking {
        val first = seedEntity("上海", "上海")
        val second = seedEntity("北京", "北京")
        val third = seedEntity("广州", "广州")
        repository.addAlias(first, "沪上")
        repository.addAlias(second, "京城")

        val map = repository.listFor(listOf(first, second, third))
        assertEquals(setOf(first, second), map.keys)
        assertEquals(listOf("沪上"), map.getValue(first).map { it.alias })
        assertEquals(listOf("京城"), map.getValue(second).map { it.alias })
        assertTrue(repository.listFor(emptyList()).isEmpty())
    }

    @Test
    fun `alias operations do not modify entity row`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        val before = db.entityDao().byId(entityId)

        val alias = repository.addAlias(entityId, "沪上")
        repository.removeAlias(alias.id)

        assertEquals("entity 本体不得被 alias 操作修改", before, db.entityDao().byId(entityId))
    }

    // ---- FTS / rebuild ----

    @Test
    fun `adding alias refreshes entity fts and removal drops alias hit`() = runBlocking {
        val entityId = seedEntity("上海", "上海", "城市")
        repository.addAlias(entityId, "沪上")

        val aliasHit = db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("沪上"), 10)
        assertEquals("别名应可 FTS 命中 canonical entity", listOf(entityId), aliasHit)
        val canonicalHit = db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("上海"), 10)
        assertEquals("canonical 名称仍可命中", listOf(entityId), canonicalHit)

        val aliasRow = db.entityAliasDao().byEntity(entityId).single()
        assertTrue(repository.removeAlias(aliasRow.id))

        assertTrue(
            "删除别名后 FTS 不应再命中该别名",
            db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("沪上"), 10).isEmpty(),
        )
        assertTrue(db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("上海"), 10).contains(entityId))
    }

    @Test
    fun `remove by entity and normalized alias refreshes fts`() = runBlocking {
        val entityId = seedEntity("上海", "上海")
        repository.addAlias(entityId, "沪上")
        assertTrue(repository.removeAlias(entityId, "  沪上 "))
        assertTrue(db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("沪上"), 10).isEmpty())
        assertTrue("重复删除返回 false", !repository.removeAlias(entityId, "沪上"))
    }

    @Test
    fun `sag fts rebuilder includes aliases and is idempotent`() = runBlocking {
        val first = seedEntity("上海", "上海", "城市")
        val second = seedEntity("北京", "北京", "城市")
        repository.addAlias(first, "沪上")
        repository.addAlias(second, "京城")

        // 模拟备份导入后 FTS 为空：重建应恢复 canonical + alias 命中
        db.entityDao().clearFts()
        val rebuilt = SagFtsRebuilder.rebuildAll(db)
        assertEquals("无 event 时重建行数 = entity 数", 2, rebuilt)
        assertEquals("每实体一行 entity_fts（不因别名重复）", 2, db.entityDao().countFts())
        assertEquals(listOf(first), db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("沪上"), 10))
        assertEquals(listOf(second), db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("京城"), 10))

        // 幂等：再次重建结果不变
        val rebuiltAgain = SagFtsRebuilder.rebuildAll(db)
        assertEquals(2, rebuiltAgain)
        assertEquals(2, db.entityDao().countFts())
        assertEquals(listOf(first), db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("沪上"), 10))
    }

    // ---- 工具 ----

    private suspend fun seedEntity(normalizedName: String, name: String = normalizedName, description: String = ""): Long {
        val id = db.entityDao().insertIgnore(
            EntityEntity(
                normalizedName = normalizedName,
                entityType = "location",
                name = name,
                description = description,
                createdAt = 1,
            ),
        )
        assertNotEquals("seed entity 不应重复", -1L, id)
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
