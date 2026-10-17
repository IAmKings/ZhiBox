package com.zhibox.memory.core.ingest

import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-11 revision 惰性回填 + B1-4 active 单一不变式（纯函数部分，无需 Room）。
 * 事务写入路径（ensureMetaLazyMaintenance / registerSpace）由集成编译与代码审查保证；
 * 这里锁定纯函数契约，避免"矩阵只写在注释里"。
 *
 * B7：模型目录收敛为 EGemma2（440M/270M/740M，全部 768d）；Granite/LFM 已下线，
 * 旧空间不再回填 revision（由 legacySpacesReport 提示重新导入）。
 */
class MetaRevisionBackfillTest {

    private fun meta(
        id: String,
        modelId: String,
        revision: String? = null,
        active: Boolean = false,
    ) = EmbeddingMetaEntity(
        id = id,
        providerId = "test-provider",
        modelId = modelId,
        modelRevision = revision,
        dimensions = 768,
        normalization = "l2",
        taskTemplate = "raw-symmetric",
        createdAt = 0L,
        active = active,
        accelerator = "cpu",
        quantization = "f32",
    )

    @Test
    fun `catalog modelId 反查 sha 前 12 位（EGemma2-only）`() {
        assertEquals(
            "92dcbea10889",
            EmbeddingRuntime.catalogRevisionForModelId("embeddinggemma-2-text-vision-440m"),
        )
        assertEquals(
            "2d079ee2f6f0",
            EmbeddingRuntime.catalogRevisionForModelId("embeddinggemma-2-text-270m"),
        )
        assertEquals(
            "e7a8a2204b91",
            EmbeddingRuntime.catalogRevisionForModelId("embeddinggemma-2-740m"),
        )
        // SM8750 NPU 变体也在目录内（各自 sha 独立）
        assertEquals(
            "c5c05cb59833",
            EmbeddingRuntime.catalogRevisionForModelId("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750"),
        )
    }

    @Test
    fun `已下线模型与未收录模型不回填（B7）`() {
        // B7 下线：granite/LFM/Path C .task 均不在 ModelCatalog
        assertNull(EmbeddingRuntime.catalogRevisionForModelId("granite-embedding-311m-r2_wi8fc"))
        assertNull(EmbeddingRuntime.catalogRevisionForModelId("LFM2.5-Embedding-350M_wi8fc"))
        assertNull(EmbeddingRuntime.catalogRevisionForModelId("embedding_gemma"))
        assertNull(EmbeddingRuntime.catalogRevisionForModelId("user-dropped-model"))
        assertNull(EmbeddingRuntime.catalogRevisionForModelId(""))
    }

    @Test
    fun `只为 revision 为空且 catalog 可查的 meta 生成回填副本`() {
        val metas = listOf(
            meta("legacy-440m", "embeddinggemma-2-text-vision-440m"),
            meta("already-known", "embeddinggemma-2-text-vision-440m", revision = "abcdef123456"),
            meta("unlisted", "user-dropped-model"),
            meta("legacy-270m", "embeddinggemma-2-text-270m"),
        )
        val updates = EmbeddingRuntime.revisionBackfillFor(metas)
        assertEquals(listOf("legacy-440m", "legacy-270m"), updates.map { it.id })
        assertTrue(updates.all { !it.modelRevision.isNullOrBlank() })
        assertEquals("92dcbea10889", updates[0].modelRevision)
        assertEquals("2d079ee2f6f0", updates[1].modelRevision)
        // 纯函数不修改输入（不可变 data class，防御性断言）
        assertNull(metas[0].modelRevision)
    }

    @Test
    fun `无历史 meta 时回填为空`() {
        assertTrue(EmbeddingRuntime.revisionBackfillFor(emptyList()).isEmpty())
        val allKnown = listOf(
            meta("a", "embeddinggemma-2-text-vision-440m", revision = "111111111111"),
        )
        assertTrue(EmbeddingRuntime.revisionBackfillFor(allKnown).isEmpty())
    }

    @Test
    fun `active 收敛只返回需要置 false 的其他 active 行`() {
        val metas = listOf(
            meta("space-a", "m", active = true),
            meta("space-b", "m", active = true),
            meta("space-c", "m", active = false),
            meta("space-target", "m", active = false),
        )
        val deactivated = EmbeddingRuntime.deactivateOthers(metas, "space-target")
        assertEquals(listOf("space-a", "space-b"), deactivated.map { it.id })
        assertTrue(deactivated.all { !it.active })
        // 目标空间不在返回列表（由 registerSpace 以 active=true upsert）；已 inactive 的行不产生多余写入
        assertTrue(deactivated.none { it.id == "space-target" || it.id == "space-c" })
    }

    @Test
    fun `目标空间已是唯一 active 时无需收敛写入`() {
        val metas = listOf(
            meta("space-target", "m", active = true),
            meta("space-old", "m", active = false),
        )
        assertTrue(EmbeddingRuntime.deactivateOthers(metas, "space-target").isEmpty())
    }

    @Test
    fun `惰性维护纯函数：同时回填 revision 并把 active 收敛到解析空间`() {
        val metas = listOf(
            meta("resolved", "embeddinggemma-2-text-vision-440m", active = true),
            meta("legacy-other", "embeddinggemma-2-text-270m", revision = null, active = true),
            meta("unlisted", "user-dropped-model", revision = null, active = true),
        )
        // resolved 空间本身无 revision → 也应回填（catalog sha）
        val updates = EmbeddingRuntime.lazyMetaUpdates(metas, resolvedSpaceId = "resolved")
        // 1) resolved 与 legacy-other 回填 revision；2) 所有非解析空间的 active 收敛为 false
        assertEquals(setOf("resolved", "legacy-other", "unlisted"), updates.map { it.id }.toSet())
        val byId = updates.associateBy { it.id }
        assertEquals("92dcbea10889", byId["resolved"]?.modelRevision)
        assertEquals("2d079ee2f6f0", byId["legacy-other"]?.modelRevision)
        assertTrue(byId["resolved"]!!.active)
        assertFalse(byId["legacy-other"]!!.active) // 其他 active 收敛为 false
        // unlisted 无 catalog sha：revision 保持 null；active 由 true 收敛为 false
        assertNull(byId["unlisted"]?.modelRevision)
        assertFalse(byId["unlisted"]!!.active)
    }

    @Test
    fun `惰性维护纯函数：解析空间不在 meta 时不改 active`() {
        val metas = listOf(
            meta("old-active", "embeddinggemma-2-text-vision-440m", active = true),
            meta("old-idle", "user-dropped-model", active = false),
        )
        // 新建空间（resolved 不在 meta）：只回填 revision，不动 active（避免误清空默认空间）
        val updates = EmbeddingRuntime.lazyMetaUpdates(metas, resolvedSpaceId = "brand-new-space")
        assertEquals(listOf("old-active"), updates.map { it.id })
        assertTrue(updates.single().active) // active 保持
        assertFalse(updates.single().modelRevision.isNullOrBlank())
    }

    @Test
    fun `惰性维护纯函数：已一致的行不产生写入`() {
        val metas = listOf(
            meta("space", "embeddinggemma-2-text-vision-440m", revision = "92dcbea10889", active = true),
            meta("idle", "user-dropped-model", revision = null, active = false),
        )
        assertTrue(EmbeddingRuntime.lazyMetaUpdates(metas, resolvedSpaceId = "space").isEmpty())
        assertTrue(EmbeddingRuntime.lazyMetaUpdates(emptyList(), resolvedSpaceId = "space").isEmpty())
    }

    @Test
    fun `收敛后最多一个 active（不变式模拟）`() {
        val metas = listOf(
            meta("a", "m", active = true),
            meta("b", "m", active = true),
            meta("target", "m", active = false),
        )
        val deactivated = EmbeddingRuntime.deactivateOthers(metas, "target")
        val target = metas.first { it.id == "target" }.copy(active = true)
        // 模拟 registerSpace 的 upsertMetaAll(deactivated + target) 结果
        val finalRows = metas.filter { m -> deactivated.none { it.id == m.id } } + deactivated + target
        val activeIds = finalRows.filter { it.active }.map { it.id }.distinct()
        assertEquals(listOf("target"), activeIds)
        assertFalse(activeIds.size > 1)
    }
}
