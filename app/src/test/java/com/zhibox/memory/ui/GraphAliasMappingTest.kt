package com.zhibox.memory.ui

import com.zhibox.memory.data.db.EntityAliasEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.2：Graph 详情实体别名映射（纯 JVM，无 Android/DB/网络）。
 *
 * 覆盖 [aliasNamesFor]：批量 `EntityAliasRepository.listFor` 结果 → 每个实体展示别名列表；
 * 保持 repository 顺序、trim、过滤空白；无别名实体返回空列表（实体仍展示 canonical 名称）。
 */
class GraphAliasMappingTest {

    private fun alias(entityId: Long, alias: String, id: Long = 0) = EntityAliasEntity(
        id = id,
        entityId = entityId,
        alias = alias,
        normalizedAlias = alias.trim().lowercase(),
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun 多实体_按实体映射且保持顺序() {
        val map = mapOf(
            1L to listOf(alias(1, "BT 耳机"), alias(1, "earbuds")),
            2L to listOf(alias(2, "协议栈")),
        )
        assertEquals(listOf("BT 耳机", "earbuds"), aliasNamesFor(map, 1))
        assertEquals(listOf("协议栈"), aliasNamesFor(map, 2))
    }

    @Test
    fun 别名trim并过滤空白() {
        val map = mapOf(
            7L to listOf(alias(7, "  TWS  "), alias(7, "   "), alias(7, "")),
        )
        assertEquals(listOf("TWS"), aliasNamesFor(map, 7))
    }

    @Test
    fun 无别名实体_返回空列表() {
        assertTrue(aliasNamesFor(emptyMap(), 1).isEmpty())
        assertTrue(aliasNamesFor(mapOf(1L to listOf(alias(1, "x"))), 2).isEmpty())
    }

    @Test
    fun GraphEntityUi_别名默认空_可携带展示列表() {
        val withoutAliases = GraphEntityUi(1, "蓝牙耳机", "product", "")
        assertTrue(withoutAliases.aliases.isEmpty())

        val withAliases = GraphEntityUi(
            id = 1,
            name = "蓝牙耳机",
            type = "product",
            description = "",
            aliases = listOf("BT 耳机", "earbuds"),
        )
        assertEquals(listOf("BT 耳机", "earbuds"), withAliases.aliases)
    }
}
