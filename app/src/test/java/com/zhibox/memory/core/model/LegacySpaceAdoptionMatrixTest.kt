package com.zhibox.memory.core.model

import com.zhibox.memory.core.embedding.EmbeddingDescriptor
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.ingest.IngestRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B1-12 / B7：EGemma2 旧空间 id 采纳矩阵（纯 JVM，不依赖 Android/Room/真机）。
 *
 * 背景：P1-6 起空间 id 纳入 modelRevision（`...@<rev前12位>`），而升级前数据库里的旧行
 * （440M GPU/NPU、270M、740M 等）**没有 @revision 后缀**。`EmbeddingRuntime.resolveSpaceId`
 * 的采纳规则是：先枚举旧格式候选 id（无 revision 的 raw-symmetric id），命中已有 meta 就沿用
 * （并由 registerSpace 回填 revision）；只有该行 revision 与新文件不同（同名换文件）才生成
 * 带 `@rev` 的新空间。
 *
 * B7 收敛后空间指纹只覆盖 EGemma2 三模型（440M/270M/740M + SM8750 NPU 变体）：
 * - 唯一保留的存量兼容分支：440M **通用 CPU** → [IngestRepository.SPACE_ID]；
 * - Granite 的 SPACE_ID_B 兼容分支已删除（旧 granite/LFM/Path C 空间由 legacySpacesReport 报告，
 *   向量不可复用，迁移方式是重新导入原文）；
 * - 270M 走 litertlm-engine（路径 B）通道，但**不是** legacy：id 始终带 revision 规则。
 */
class LegacySpaceAdoptionMatrixTest {

    private fun descriptor(
        providerId: String,
        modelId: String,
        accelerator: String = "cpu",
        revision: String? = null,
    ) = EmbeddingDescriptor(
        providerId = providerId,
        modelId = modelId,
        modelRevision = revision,
        accelerator = accelerator,
    )

    /** 旧格式 id 的纯函数复现：清空 revision 后解析（= resolveSpaceId 的 oldFormatIds 候选）。 */
    private fun oldFormatId(d: EmbeddingDescriptor): String =
        EmbeddingRuntime.spaceIdResolved(d.copy(modelRevision = null), knownRevision = null)

    private fun newFormatId(d: EmbeddingDescriptor): String =
        EmbeddingRuntime.spaceIdResolved(d, knownRevision = null)

    // ---- EGemma2 旧格式空间（440M GPU/NPU、270M、740M）----

    @Test
    fun `440M GPU 旧格式 id 无 revision 后缀且可被采纳为基线`() {
        val d = descriptor(UniversalProvider, M440, accelerator = "gpu")
        val oldId = oldFormatId(d)
        assertEquals("$UniversalProvider/$M440/raw-symmetric-v1-gpu", oldId)
        assertFalse("旧格式 id 不应含 @revision", oldId.contains("@"))
        assertEquals(oldId, newFormatId(d))
    }

    @Test
    fun `440M NPU 旧格式 id 无 revision 后缀且可被采纳为基线`() {
        val d = descriptor(UniversalProvider, M440, accelerator = "npu")
        val oldId = oldFormatId(d)
        assertEquals("$UniversalProvider/$M440/raw-symmetric-v1-npu", oldId)
        assertFalse(oldId.contains("@"))
        assertEquals(oldId, newFormatId(d))
    }

    @Test
    fun `270M（路径 B 引擎）旧格式 id 无 revision 后缀且可被采纳为基线`() {
        val d = descriptor(LiteRtLmProvider, M270)
        val oldId = oldFormatId(d)
        assertEquals("$LiteRtLmProvider/$M270/raw-symmetric-v1", oldId)
        assertFalse(oldId.contains("@"))
        assertEquals(oldId, newFormatId(d))
    }

    @Test
    fun `740M 旧格式 id 无 revision 后缀且可被采纳为基线`() {
        val cpu = descriptor(UniversalProvider, M740)
        assertEquals("$UniversalProvider/$M740/raw-symmetric-v1", oldFormatId(cpu))
        val gpu = descriptor(UniversalProvider, M740, accelerator = "gpu")
        assertEquals("$UniversalProvider/$M740/raw-symmetric-v1-gpu", oldFormatId(gpu))
        assertFalse(oldFormatId(gpu).contains("@"))
    }

    // ---- 同名换文件：只有文件 revision 已知/变化才产生 @rev 新空间 ----

    @Test
    fun `同名换文件才产生带 revision 的新空间（440M GPU 示例）`() {
        val rev = "0123456789ab"
        val withRev = descriptor(UniversalProvider, M440, accelerator = "gpu", revision = rev)
        val oldId = oldFormatId(withRev)
        val newId = newFormatId(withRev)

        assertEquals("$oldId@$rev", newId)
        assertNotEquals("文件换版必须产生新空间，禁止新旧权重向量混用", oldId, newId)
        assertTrue("新 id 必须以旧 id 为前缀，便于按 -gpu/-npu 解析 accelerator", newId.startsWith(oldId))
        // 同一文件（相同 revision）重复解析必须稳定
        assertEquals(newId, newFormatId(withRev))
    }

    @Test
    fun `同名换文件才产生带 revision 的新空间（NPU 270M 740M 全矩阵）`() {
        val rev = "abcdef123456"
        val cases = listOf(
            descriptor(UniversalProvider, M440, accelerator = "npu", revision = rev),
            descriptor(LiteRtLmProvider, M270, revision = rev),
            descriptor(UniversalProvider, M740, revision = rev),
        )
        for (d in cases) {
            val oldId = oldFormatId(d)
            val newId = newFormatId(d)
            assertEquals("$oldId@$rev", newId)
            assertNotEquals(oldId, newId)
        }
    }

    @Test
    fun `文件换版只影响新 id：旧格式基线不随 revision 改变`() {
        val r1 = "111111111111"
        val r2 = "222222222222"
        val base = descriptor(UniversalProvider, M440, accelerator = "gpu")
        val oldId = oldFormatId(base)
        assertEquals(oldId, oldFormatId(base.copy(modelRevision = r1)))
        assertEquals(oldId, oldFormatId(base.copy(modelRevision = r2)))
        assertNotEquals(oldId, newFormatId(base.copy(modelRevision = r1)))
        assertNotEquals(oldId, newFormatId(base.copy(modelRevision = r2)))
        assertNotEquals(
            "不同 revision 必须是不同空间",
            newFormatId(base.copy(modelRevision = r1)),
            newFormatId(base.copy(modelRevision = r2)),
        )
    }

    @Test
    fun `revision 截断到 12 位（旧行回填与新空间指纹一致）`() {
        val d = descriptor(LiteRtLmProvider, M270, revision = "0123456789abcdef")
        assertTrue(newFormatId(d).endsWith("@0123456789ab"))
        assertTrue(oldFormatId(d).endsWith("raw-symmetric-v1"))
    }

    // ---- 唯一保留的存量兼容分支（440M 通用 CPU）----

    @Test
    fun `440M 通用 CPU 兼容空间：无换文件时永远采纳旧 id`() {
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdResolved(
                descriptor(UniversalProvider, M440, revision = "abcdef123456"),
                knownRevision = null,
            ),
        )
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdResolved(
                descriptor(UniversalProvider, M440, revision = "abcdef123456"),
                knownRevision = "abcdef123456",
            ),
        )
        // 同名换文件（meta 已回填 revision 且与新文件不同）→ 退化为带 @rev 新空间
        val swapped = EmbeddingRuntime.spaceIdResolved(
            descriptor(UniversalProvider, M440, revision = "999999999999"),
            knownRevision = "abcdef123456",
        )
        assertNotEquals(IngestRepository.SPACE_ID, swapped)
        assertTrue(swapped.endsWith("@999999999999"))
    }

    @Test
    fun `兼容分支只认 440M 通用 CPU：GPU、NPU 走带 accelerator 后缀的独立空间`() {
        val cpu = descriptor(UniversalProvider, M440)
        val gpu = cpu.copy(accelerator = "gpu")
        val npu = cpu.copy(accelerator = "npu")
        assertEquals(IngestRepository.SPACE_ID, oldFormatId(cpu))
        assertNotEquals(IngestRepository.SPACE_ID, oldFormatId(gpu))
        assertNotEquals(IngestRepository.SPACE_ID, oldFormatId(npu))
        assertTrue(oldFormatId(gpu).endsWith("-gpu"))
        assertTrue(oldFormatId(npu).endsWith("-npu"))
    }

    // ---- 交叉校验：EGemma2 旧空间互不混淆 ----

    @Test
    fun `EGemma2 旧格式 id 两两不同（空间隔离基线）`() {
        val ids = listOf(
            oldFormatId(descriptor(UniversalProvider, M440, accelerator = "gpu")),
            oldFormatId(descriptor(UniversalProvider, M440, accelerator = "npu")),
            oldFormatId(descriptor(LiteRtLmProvider, M270)),
            oldFormatId(descriptor(UniversalProvider, M740)),
        )
        assertEquals("四类旧空间 id 必须互不相同", 4, ids.toSet().size)
        assertTrue(ids.none { it.contains("@") })
        // 同一 model 的 CPU / GPU 也是两个空间（跨后端向量不可混用）
        val cpu440 = oldFormatId(descriptor(UniversalProvider, M440))
        val gpu440 = oldFormatId(descriptor(UniversalProvider, M440, accelerator = "gpu"))
        assertNotEquals(cpu440, gpu440)
    }

    private companion object {
        const val UniversalProvider = "mediapipe-universal"
        const val LiteRtLmProvider = "litertlm-engine" // 270M 走 litertlm EmbeddingEngine（路径 B）
        const val M440 = "embeddinggemma-2-text-vision-440m"
        const val M270 = "embeddinggemma-2-text-270m"
        const val M740 = "embeddinggemma-2-740m"
    }
}
