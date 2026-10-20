package com.zhibox.memory.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B15.1 schema v9：EntityAliasDao roundtrip（instrumentation，真实 Room/SQLite）：
 * - 按 entity/normalizedAlias/id/page 读取、count、delete；upsertAll；
 * - normalizedAlias 唯一约束（insertIgnore 返回 -1）；
 * - FK CASCADE：删除 entity 自动清理 aliases；`PRAGMA foreign_key_check` 空。
 */
class EntityAliasDaoTest {

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
    fun roundTripAndUniqueAndCascade() = runBlocking {
        val entityId = db.entityDao().insertIgnore(
            EntityEntity(normalizedName = "上海", entityType = "location", name = "上海", description = "城市", createdAt = 1),
        )
        val otherId = db.entityDao().insertIgnore(
            EntityEntity(normalizedName = "北京", entityType = "location", name = "北京", description = "城市", createdAt = 2),
        )

        val first = db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = entityId, alias = "沪上", normalizedAlias = "沪上", createdAt = 10),
        )
        val second = db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = entityId, alias = "申城", normalizedAlias = "申城", createdAt = 11),
        )
        db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = otherId, alias = "京城", normalizedAlias = "京城", createdAt = 12),
        )
        assertTrue(first > 0 && second > 0)
        assertEquals(3, db.entityAliasDao().count())

        // 唯一约束：normalizedAlias 冲突返回 -1
        val duplicate = db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = otherId, alias = "沪上", normalizedAlias = "沪上", createdAt = 13),
        )
        assertEquals(-1L, duplicate)

        // 读取
        assertEquals(listOf("沪上", "申城"), db.entityAliasDao().byEntity(entityId).map { it.normalizedAlias })
        assertEquals("沪上", db.entityAliasDao().byNormalizedAlias("沪上")?.alias)
        assertEquals(2, db.entityAliasDao().byNormalizedAliases(listOf("沪上", "申城", "不存在")).size)
        assertEquals(2, db.entityAliasDao().byEntities(listOf(entityId, otherId)).count { it.entityId == entityId })
        assertEquals(3, db.entityAliasDao().all().size)
        assertEquals(2, db.entityAliasDao().page(limit = 2, offset = 0).size)
        assertNull(db.entityAliasDao().byNormalizedAlias("不存在"))

        // 删除单个
        assertEquals(1, db.entityAliasDao().deleteById(second))
        assertEquals(2, db.entityAliasDao().count())
        assertEquals(1, db.entityAliasDao().deleteByEntityAndNormalized(entityId, "沪上"))
        assertEquals(1, db.entityAliasDao().count())

        // FK CASCADE：删除 entity → 其余别名级联清理
        db.entityAliasDao().insertIgnore(
            EntityAliasEntity(entityId = entityId, alias = "魔都", normalizedAlias = "魔都", createdAt = 14),
        )
        db.entityDao().deleteById(entityId)
        assertEquals("删除 entity 应级联清理 alias", 1, db.entityAliasDao().count())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
}
