package com.zhibox.memory.core.embedding

import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-6 空间指纹单测（纯 JVM）：modelRevision 参与空间 id、EGemma2-440M 存量兼容分支、
 * 同名换文件自动新空间、meta taskTemplate 由 descriptor 推导。
 *
 * B7：仅保留 EGemma2 三模型。Granite 的 SPACE_ID_B 兼容分支已删除（旧空间由
 * `IngestRepository.legacySpacesReport()` 报告，向量不可复用）；440M 通用 CPU 的 SPACE_ID
 * 存量兼容保留。
 */
class SpaceFingerprintTest {

    private fun desc(
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

    private fun provider(descriptor: EmbeddingDescriptor) = object : EmbeddingProvider {
        override val descriptor = descriptor
        override suspend fun embedDocuments(texts: List<String>) = emptyList<FloatArray>()
        override suspend fun embedQuery(text: String) = FloatArray(0)
        override fun close() {}
    }

    @Test
    fun `存量兼容：440M 通用 CPU 空间 id 不变`() {
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdFor(
                provider(desc("mediapipe-universal", "embeddinggemma-2-text-vision-440m")),
            ),
        )
        // 即使已有 revision，纯函数仍返回旧 id（存量向量兼容；换文件检测在 resolveSpaceId）
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdFor(
                provider(
                    desc(
                        "mediapipe-universal",
                        "embeddinggemma-2-text-vision-440m",
                        revision = "abcdef123456",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `非兼容空间 id 纳入 revision`() {
        val rev = "0123456789ab"
        val cpu = EmbeddingRuntime.spaceIdFor(
            provider(desc("mediapipe-universal", "embeddinggemma-2-740m", revision = rev)),
        )
        assertEquals("mediapipe-universal/embeddinggemma-2-740m/raw-symmetric-v1@$rev", cpu)
        // GPU 后缀在 @revision 之前，便于按 -gpu/-npu 解析 accelerator
        val gpu = EmbeddingRuntime.spaceIdFor(
            provider(desc("mediapipe-universal", "embeddinggemma-2-740m", "gpu", rev)),
        )
        assertEquals("mediapipe-universal/embeddinggemma-2-740m/raw-symmetric-v1-gpu@$rev", gpu)
        assertTrue(cpu != gpu)
    }

    @Test
    fun `270M 路径 B 引擎不再走 legacy：id 纳入 revision`() {
        val rev = "abcdef123456"
        val bare = EmbeddingRuntime.spaceIdFor(
            provider(desc("litertlm-engine", "embeddinggemma-2-text-270m")),
        )
        assertEquals("litertlm-engine/embeddinggemma-2-text-270m/raw-symmetric-v1", bare)
        val withRev = EmbeddingRuntime.spaceIdFor(
            provider(desc("litertlm-engine", "embeddinggemma-2-text-270m", revision = rev)),
        )
        assertEquals("litertlm-engine/embeddinggemma-2-text-270m/raw-symmetric-v1@$rev", withRev)
        assertNotEquals(bare, withRev)
    }

    @Test
    fun `revision 截断到 12 位（纯函数契约）`() {
        val space = EmbeddingRuntime.spaceIdFor(
            provider(desc("litertlm-engine", "embeddinggemma-2-text-270m", revision = "0123456789abcdef")),
        )
        assertTrue(space.endsWith("@0123456789ab"))
    }

    @Test
    fun `无 revision 时保持旧格式（零迁移兼容）`() {
        val space = EmbeddingRuntime.spaceIdFor(
            provider(desc("mediapipe-universal", "embeddinggemma-2-740m", "gpu")),
        )
        assertEquals("mediapipe-universal/embeddinggemma-2-740m/raw-symmetric-v1-gpu", space)
        assertFalse(space.contains("@"))
    }

    @Test
    fun `同名换文件：已知 revision 与当前不同则生成带 revision 的新空间`() {
        val oldRev = "aaaaaaaaaaaa"
        val newRev = "bbbbbbbbbbbb"
        // 440M 兼容分支：既有 meta 记录 aaaa，当前文件 bbbb → 不复用旧空间
        val d440 = desc("mediapipe-universal", "embeddinggemma-2-text-vision-440m", revision = newRev)
        val swapped = EmbeddingRuntime.spaceIdResolved(d440, knownRevision = oldRev)
        assertEquals(
            "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1@$newRev",
            swapped,
        )
        assertNotEquals(IngestRepository.SPACE_ID, swapped)
        // 同一文件（revision 相同）→ 继续用旧空间
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdResolved(d440, knownRevision = newRev),
        )
        // 存量 meta 无 revision（升级前）→ 不判定换文件，继续旧空间
        assertEquals(
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.spaceIdResolved(d440, knownRevision = null),
        )
        // 非 legacy 空间（270M）同样：knownRevision 不同 → @rev 新空间
        val d270 = desc("litertlm-engine", "embeddinggemma-2-text-270m", revision = newRev)
        assertEquals(
            "litertlm-engine/embeddinggemma-2-text-270m/raw-symmetric-v1@$newRev",
            EmbeddingRuntime.spaceIdResolved(d270, knownRevision = oldRev),
        )
    }

    @Test
    fun `meta taskTemplate 由 descriptor 模板策略生成（P1-6）`() {
        assertEquals(
            "app-prefix-v1",
            EmbeddingRuntime.taskTemplateFor(
                desc("mediapipe-universal", "x").copy(
                    queryTaskTemplate = TaskTemplatePolicy.QUERY_PREFIX.name,
                    documentTaskTemplate = TaskTemplatePolicy.DOCUMENT_PREFIX.name,
                ),
            ),
        )
        assertEquals(
            "raw-symmetric",
            EmbeddingRuntime.taskTemplateFor(
                desc("litertlm-engine", "y").copy(
                    queryTaskTemplate = TaskTemplatePolicy.RAW.name,
                    documentTaskTemplate = TaskTemplatePolicy.RAW.name,
                ),
            ),
        )
        // 未声明模板的 descriptor 保守按对称处理
        assertEquals("raw-symmetric", EmbeddingRuntime.taskTemplateFor(desc("p", "z")))
    }

    @Test
    fun `meta 字段可由 descriptor 完整生成（唯一写入口数据形状）`() {
        // 该断言锁定 registerSpace 的字段来源：accelerator/quantization/taskTemplate 不再二次推断
        val d = desc("mediapipe-universal", "embeddinggemma-2-text-vision-440m", "gpu", "abcdef123456")
        val meta = EmbeddingMetaEntity(
            id = EmbeddingRuntime.spaceIdFor(provider(d)),
            providerId = d.providerId,
            modelId = d.modelId,
            modelRevision = d.modelRevision,
            dimensions = 768,
            normalization = d.normalization,
            taskTemplate = EmbeddingRuntime.taskTemplateFor(d),
            createdAt = 0,
            active = true,
            accelerator = d.accelerator,
            quantization = "f32",
        )
        assertEquals("gpu", meta.accelerator)
        assertEquals("f32", meta.quantization)
        assertEquals("raw-symmetric", meta.taskTemplate) // descriptor 未声明模板 → 对称
        assertTrue(meta.id.contains("@abcdef123456"))
    }
}
