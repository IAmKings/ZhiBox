package com.zhibox.memory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * B4-3：ModelCatalog 维度单一来源（[ModelCatalog.DEFAULT_DIMS] + [ModelCatalog.dimsFor]）
 * 与 Locale.ROOT 归一化（土耳其 i 问题）单测（纯 JVM）。
 *
 * B7 起仅保留 EGemma2 三模型（440M/270M/740M + SM8750 NPU 变体），全部 768d；
 * LFM2.5（1024d）已随 Granite/LFM 路线下线，本测试同步删除 1024d 用例。
 */
class ModelCatalogDimensionTest {

    @Test
    fun `DEFAULT_DIMS 是唯一无记录回退（768）`() {
        assertEquals(768, ModelCatalog.DEFAULT_DIMS)
    }

    @Test
    fun `已收录 EGemma2 模型全部走 catalog dims（768）`() {
        val names = listOf(
            "embeddinggemma-2-text-vision-440m.litertlm",
            "embeddinggemma-2-text-270m.litertlm",
            "embeddinggemma-2-740m.litertlm",
            "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm",
            "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
        )
        names.forEach { name ->
            assertEquals("$name 的 catalog dims 应为 768", 768, ModelCatalog.byFileName(name)?.dims)
            assertEquals(768, ModelCatalog.dimsFor(name))
        }
        // 已下线的 Granite/LFM/.task 不再收录
        assertNull(ModelCatalog.byFileName("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertNull(ModelCatalog.byFileName("LFM2.5-Embedding-350M_wi8fc.litertlm"))
        assertNull(ModelCatalog.byFileName("embedding_gemma.task"))
    }

    @Test
    fun `未收录模型回退 DEFAULT_DIMS`() {
        assertEquals(ModelCatalog.DEFAULT_DIMS, ModelCatalog.dimsFor("user-dropped-model.litertlm"))
        assertEquals(ModelCatalog.DEFAULT_DIMS, ModelCatalog.dimsFor(""))
        assertNull(ModelCatalog.byFileName("user-dropped-model.litertlm"))
    }

    @Test
    fun `大小写与路径不改变匹配语义（精确文件名匹配）`() {
        // byFileName 是精确匹配：不同大小写不命中 → 回退 DEFAULT_DIMS（与既有 catalog 契约一致）
        assertNull(ModelCatalog.byFileName("embeddinggemma-2-text-270M.litertlm"))
        assertEquals(
            ModelCatalog.DEFAULT_DIMS,
            ModelCatalog.dimsFor("embeddinggemma-2-text-270M.litertlm"),
        )
    }
}

class ModelCatalogLocaleTest {

    @Test
    fun `土耳其 locale 下 normalize 不受点less i 影响`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            // 旧实现 locale-sensitive lowercase：tr 下 "I".lowercase() == "ı"
            val normalized = ModelCatalog.normalize("INTEL SM8750")
            assertEquals("intel_sm8750", normalized)
            assertFalse("normalize 不应产生土耳其点less i：$normalized", normalized.contains('ı'))
            assertTrue(normalized.startsWith("intel"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `土耳其 locale 下 SOC 标识归一化口径不变`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("qualcomm_sm8750", ModelCatalog.normalize("Qualcomm SM8750"))
            assertEquals("sm8750", ModelCatalog.normalize("SM8750"))
            assertEquals("mt6985", ModelCatalog.normalize("MT6985 "))
            assertEquals("a16_bionic", ModelCatalog.normalize("A16 Bionic"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `normalize 常规（非土耳其）行为不变`() {
        assertEquals("qualcomm_sm8750", ModelCatalog.normalize("Qualcomm SM8750"))
        assertEquals("sm8750", ModelCatalog.normalize("SM8750"))
        assertEquals("a16_bionic", ModelCatalog.normalize("A16 Bionic"))
        // 连续非字母数字折叠为单个下划线并去首尾
        assertEquals("abc_123", ModelCatalog.normalize("  ABC -- 123  "))
    }
}
