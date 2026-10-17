package com.zhibox.memory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7：ModelCatalog 收敛为 EGemma2-only（440M/270M/740M + 两个 SM8750 NPU 特化版）。
 * 已下线的 Granite/LFM2.5/Path C `.task` 不再出现在目录、推荐与显式名单中。
 */
class ModelCatalogTest {

    @Test
    fun `目录仅收录 EGemma2 三强度与 SM8750 NPU 变体`() {
        val names = ModelCatalog.ENTRIES.map { it.fileName }
        assertEquals("B7 后 catalog 条目数应为 5", 5, names.size)
        assertEquals("条目文件名必须唯一", names.size, names.distinct().size)
        assertEquals(
            setOf(
                "embeddinggemma-2-text-vision-440m.litertlm",
                "embeddinggemma-2-text-270m.litertlm",
                "embeddinggemma-2-740m.litertlm",
                "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm",
                "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
            ),
            names.toSet(),
        )
        names.forEach { name ->
            assertTrue("B7 后只应存在 EGemma2 模型：$name", name.contains("embeddinggemma-2"))
            assertFalse("已下线的 Granite 不应出现：$name", name.contains("granite"))
            assertFalse("已下线的 LFM 不应出现：$name", name.contains("lfm2.5", ignoreCase = true))
            assertFalse("已下线的 Path C .task 不应出现：$name", name.endsWith(".task"))
        }
        // 两个 NPU 特化版都只标注 SM8750，且后端为 NPU_SOC
        val npu = ModelCatalog.ENTRIES.filter { it.backend == ModelCatalog.Backend.NPU_SOC }
        assertEquals(2, npu.size)
        assertTrue(npu.all { it.socTags == listOf("qualcomm_sm8750") })
    }

    @Test
    fun `soc 归一化`() {
        assertEquals("qualcomm_sm8750", ModelCatalog.normalize("Qualcomm SM8750"))
        assertEquals("sm8750", ModelCatalog.normalize("SM8750"))
        assertEquals("mt6985", ModelCatalog.normalize("MT6985 "))
        assertEquals("a16_bionic", ModelCatalog.normalize("A16 Bionic"))
    }

    @Test
    fun `soc 匹配包含双向`() {
        val e = ModelCatalog.byFileName("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm")
        assertNotNull(e)
        assertTrue(ModelCatalog.socMatches(e!!, "qualcomm_sm8750"))
        assertTrue(ModelCatalog.socMatches(e, "qualcomm_sm8750_jv")) // 机型后缀
        assertFalse(ModelCatalog.socMatches(e, "qualcomm_sm8650"))
        assertFalse(ModelCatalog.socMatches(e, "mt6985"))
    }

    @Test
    fun `推荐矩阵三档（EGemma2-only）`() {
        val soc = "qualcomm_sm8750"
        // 均衡档默认 440M 通用（B7 后无 granite 候选）
        assertEquals(
            "embeddinggemma-2-text-vision-440m.litertlm",
            ModelCatalog.recommend(ModelCatalog.PERF_BALANCED, soc)?.fileName,
        )
        // 极速：Adreno → 740M 通用（UE-GPU 质量上限；NPU 特化版全为实验态不推荐）
        assertEquals(
            "embeddinggemma-2-740m.litertlm",
            ModelCatalog.recommend(ModelCatalog.PERF_FAST, soc)?.fileName,
        )
        // 极速：非 Adreno SOC → 440M 通用
        assertEquals(
            "embeddinggemma-2-text-vision-440m.litertlm",
            ModelCatalog.recommend(ModelCatalog.PERF_FAST, "mt6985")?.fileName,
        )
        // 轻量 → 270M
        assertEquals(
            "embeddinggemma-2-text-270m.litertlm",
            ModelCatalog.recommend(ModelCatalog.PERF_LIGHT, soc)?.fileName,
        )
        // B7 回归：推荐位不再出现任何已下线模型
        listOf(ModelCatalog.PERF_FAST, ModelCatalog.PERF_LIGHT, ModelCatalog.PERF_BALANCED)
            .forEach { mode ->
                val rec = ModelCatalog.recommend(mode, soc)!!
                assertFalse("推荐位不得出现 granite：${rec.fileName}", rec.fileName.contains("granite"))
                assertFalse("推荐位不得出现 LFM：${rec.fileName}", rec.fileName.contains("lfm2.5", true))
                assertFalse("推荐位不得出现 .task：${rec.fileName}", rec.fileName.endsWith(".task"))
            }
    }

    @Test
    fun `已验证标记只落在已验证组合（B7 后仅 440M 通用）`() {
        val verified = ModelCatalog.ENTRIES.filter { it.verified }
        assertEquals(1, verified.size)
        assertEquals("embeddinggemma-2-text-vision-440m.litertlm", verified.single().fileName)
        assertEquals(ModelCatalog.Backend.CPU_GENERIC, verified.single().backend)
        // 默认推荐必须已验证
        val balanced = ModelCatalog.recommend(ModelCatalog.PERF_BALANCED, "anything")
        assertTrue(balanced!!.verified)
    }

    @Test
    fun `偏好 D 与 740M 文案契约（B7 审查修复）`() {
        assertEquals("偏好 D 语义 = 740M UE 档", "D", PathPreferences.D)
        // 设置页标签：通用 740M / SM8750 NPU 特化版
        assertEquals("EGemma2-740M", PathPreferences.pathLabel("embeddinggemma-2-740m.litertlm"))
        assertEquals(
            "EGemma2-740M · SM8750 NPU",
            PathPreferences.pathLabel("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"),
        )
        // 推荐理由文案（两档真机验证 + 实验）
        val e740 = ModelCatalog.byFileName("embeddinggemma-2-740m.litertlm")!!
        assertTrue(ModelCatalog.recommendReason(e740, "qualcomm_sm8750").contains("两档真机验证"))
        val e270 = ModelCatalog.byFileName("embeddinggemma-2-text-270m.litertlm")!!
        assertTrue(ModelCatalog.recommendReason(e270, "mt6985").contains("轻量"))
        // D 偏好跨模块契约：440M 在位时仍能选到 740M（全量候选）
        val picked = PathPreferences.pickModel(
            files = listOf(
                java.io.File("embeddinggemma-2-text-vision-440m.litertlm"),
                java.io.File("embeddinggemma-2-740m.litertlm"),
            ),
            preference = PathPreferences.D,
            gatePassNames = setOf("embeddinggemma-2-text-vision-440m.litertlm"),
        )
        assertEquals("embeddinggemma-2-740m.litertlm", picked?.name)
    }

    @Test
    fun `尺寸校验容差`() {
        val e = ModelCatalog.byFileName("embeddinggemma-2-text-270m.litertlm")!!
        val f = org.junit.rules.TemporaryFolder().apply { create() }
        val file = f.newFile("m.litertlm")
        file.writeText("x")
        assertFalse(ModelCatalog.sizeMatches(e, file)) // 空文件
        file.writeBytes(ByteArray(e.sizeBytes.toInt()))
        assertTrue(ModelCatalog.sizeMatches(e, file))
        f.delete()
    }
}

class PathRoutingTest {

    @Test
    fun `路径B名单仅 EGemma2-270M（Granite、LFM 已下线）`() {
        val r = com.zhibox.memory.core.ingest.IngestRepository
        assertTrue(r.isPathBModel("embeddinggemma-2-text-270m.litertlm"))
        // 740M/440M 走 UniversalEmbedder 通道，不属于路径 B
        assertFalse(r.isPathBModel("embeddinggemma-2-740m.litertlm"))
        assertFalse(r.isPathBModel("embeddinggemma-2-text-vision-440m.litertlm"))
        // B7 下线模型不再命中路径 B 名单
        assertFalse(r.isPathBModel("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertFalse(r.isPathBModel("LFM2.5-Embedding-350M_wi8fc.litertlm"))
        assertFalse(r.isPathBModel("embedding_gemma.task"))
    }

    @Test
    fun `路径A仅通用440m`() {
        val r = com.zhibox.memory.core.ingest.IngestRepository
        assertTrue(r.isPathAModel("embeddinggemma-2-text-vision-440m.litertlm"))
        // 特化版含 440m 但由 SOC 判定分流
        assertTrue(r.isPathAModel("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"))
        assertFalse(r.isPathAModel("embeddinggemma-2-text-270m.litertlm"))
        assertFalse(r.isPathAModel("embeddinggemma-2-740m.litertlm"))
    }

    @Test
    fun `SOC 特化判定`() {
        val rt = com.zhibox.memory.core.embedding.EmbeddingRuntime
        assertTrue(rt.isSocSpecialized("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"))
        assertTrue(rt.isSocSpecialized("x_MediaTek_MT6991.litertlm"))
        assertTrue(rt.isSocSpecialized("y_Google_Tensor_G5.litertlm"))
        assertFalse(rt.isSocSpecialized("embeddinggemma-2-text-vision-440m.litertlm"))
        assertFalse(rt.isSocSpecialized("granite-embedding-311m-r2_wi8fc.litertlm"))
    }

    @Test
    fun `已知模型显式名单（EGemma2-only）`() {
        val p = PathPreferences
        assertTrue(p.isKnownModel("embeddinggemma-2-text-vision-440m.litertlm"))
        assertTrue(p.isKnownModel("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"))
        assertTrue(p.isKnownModel("embeddinggemma-2-text-270m.litertlm"))
        assertTrue(p.isKnownModel("embeddinggemma-2-740m.litertlm"))
        assertTrue(p.isKnownModel("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"))
        // B7 下线模型/临时名/非 .litertlm 均拒识
        assertFalse(p.isKnownModel("granite-embedding-311m-r2_wi8fc.litertlm"))
        assertFalse(p.isKnownModel("LFM2.5-Embedding-350M_wi8fc.litertlm"))
        assertFalse(p.isKnownModel("embedding_gemma.task"))
        assertFalse(p.isKnownModel("g.litertlm")) // 临时名拒识教训
        assertFalse(p.isKnownModel("random.bin"))
        // 文件级名单叠加 .litertlm 扩展名约束
        assertFalse(p.isKnownModelFile("names-with-440m-notes.txt"))
        assertTrue(p.isKnownModelFile("embeddinggemma-2-text-270m.litertlm"))
    }
}

class SpaceIsolationTest {
    private fun desc(providerId: String, modelId: String, accel: String) =
        com.zhibox.memory.core.embedding.EmbeddingDescriptor(
            providerId = providerId, modelId = modelId, accelerator = accel)

    @Test
    fun `GPU 向量与 CPU 向量空间隔离（跨设备不逐位一致的实测防御）`() {
        val rt = com.zhibox.memory.core.embedding.EmbeddingRuntime
        val cpu = object : com.zhibox.memory.core.embedding.EmbeddingProvider {
            override val descriptor = desc("litertlm-engine", "embeddinggemma-2-text-270m", "cpu")
            override suspend fun embedDocuments(texts: List<String>) = emptyList<FloatArray>()
            override suspend fun embedQuery(text: String) = FloatArray(0)
            override fun close() {}
        }
        val gpu = object : com.zhibox.memory.core.embedding.EmbeddingProvider {
            override val descriptor = desc("mediapipe-universal", "embeddinggemma-2-740m", "gpu")
            override suspend fun embedDocuments(texts: List<String>) = emptyList<FloatArray>()
            override suspend fun embedQuery(text: String) = FloatArray(0)
            override fun close() {}
        }
        val cpuSpace = rt.spaceIdFor(cpu)
        val gpuSpace = rt.spaceIdFor(gpu)
        assertTrue(cpuSpace != gpuSpace)
        assertTrue(cpuSpace.endsWith("raw-symmetric-v1"))
        assertTrue(gpuSpace.endsWith("raw-symmetric-v1-gpu"))
    }
}
