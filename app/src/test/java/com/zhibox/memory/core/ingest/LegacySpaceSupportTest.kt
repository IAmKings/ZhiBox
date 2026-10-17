package com.zhibox.memory.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7 旧空间支持判定纯函数单测（纯 JVM）：[IngestRepository.isSupportedSpace]。
 *
 * 该函数是"备份导出过滤（跳过旧空间向量）/ 备份导入拒绝（写库前列出 modelId）/
 * legacySpacesReport（设置页提示旧数据需重新导入）"三处的唯一判定源，必须可独立验证。
 *
 * 规则：providerId ∈ {mediapipe-universal, litertlm-engine} 且 modelId 含 "embeddinggemma-2"
 * （忽略大小写）。旧路线 granite / Path C `embedding_gemma.task` / LFM 一律不支持。
 */
class LegacySpaceSupportTest {

    @Test
    fun `EGemma2 三模型与 SM8750 NPU 变体受支持`() {
        val supported = listOf(
            "mediapipe-universal" to "embeddinggemma-2-text-vision-440m",
            "mediapipe-universal" to "embeddinggemma-2-text-270m",
            "mediapipe-universal" to "embeddinggemma-2-740m",
            "mediapipe-universal" to "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750",
            "mediapipe-universal" to "embeddinggemma-2-740m_Qualcomm_SM8750",
            // 270M 走 litertlm EmbeddingEngine 通道，但仍是支持的 EGemma2 家族
            "litertlm-engine" to "embeddinggemma-2-text-270m",
        )
        supported.forEach { (provider, model) ->
            assertTrue("应支持：$provider/$model", IngestRepository.isSupportedSpace(provider, model))
        }
    }

    @Test
    fun `已下线路线空间不受支持（granite PathC LFM）`() {
        val legacy = listOf(
            "litertlm-engine" to "granite-embedding-311m-r2_wi8fc",
            "mediapipe-universal" to "granite-embedding-311m-r2_wi8fc",
            "mediapipe-text" to "embedding_gemma", // Path C .task
            "mediapipe-text" to "egemma-300m",
            "litertlm-engine" to "LFM2.5-Embedding-350M_wi8fc",
            "litertlm-engine" to "",
            "" to "embeddinggemma-2-text-vision-440m",
            "" to "",
        )
        legacy.forEach { (provider, model) ->
            assertFalse("不应支持：$provider/$model", IngestRepository.isSupportedSpace(provider, model))
        }
    }

    @Test
    fun `判定规则：provider 白名单 + EGemma2 家族标记（忽略大小写）`() {
        // 家族标记不区分大小写（modelId 来自历史数据/文件名，可能非规范大小写）
        assertTrue(
            IngestRepository.isSupportedSpace("mediapipe-universal", "EmbeddingGemma-2-Text-270m"),
        )
        // 非白名单 provider 即便 modelId 命中也不支持
        assertFalse(IngestRepository.isSupportedSpace("mlkit", "embeddinggemma-2-text-270m"))
        // 白名单 provider 但模型不是 EGemma2 家族也不支持
        assertFalse(IngestRepository.isSupportedSpace("mediapipe-universal", "vit-b16"))
    }

    @Test
    fun `常量集合锁定（备份、UI、报表共用的单一事实源）`() {
        assertEquals(
            setOf("mediapipe-universal", "litertlm-engine"),
            IngestRepository.SUPPORTED_EMBEDDING_PROVIDERS,
        )
        assertEquals("embeddinggemma-2", IngestRepository.SUPPORTED_MODEL_MARKER)
    }
}
