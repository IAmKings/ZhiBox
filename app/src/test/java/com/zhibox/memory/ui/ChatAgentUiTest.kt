package com.zhibox.memory.ui

import com.zhibox.memory.core.chat.ChatEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.4：Chat Agent 工具 UI 侧纯逻辑（纯 JVM，无 Android/DB/网络）。
 *
 * 覆盖 [shouldRunAgent]（开关/LlmReady 组合）、[mergeAgentEvidence]（普通优先按 chunkId 去重/
 * 重新编号/上限）与 [normalizeEntityLookup]（实体查询归一化）。
 */
class ChatAgentUiTest {

    private fun evidence(chunkId: Long, content: String = "c$chunkId", id: String = "S?") =
        ChatEvidence(
            evidenceId = id,
            chunkId = chunkId,
            documentId = chunkId,
            sourceId = chunkId,
            docTitle = "doc-$chunkId",
            sourceTitle = "src",
            sourceUri = "content://demo/$chunkId",
            content = content,
            ordinal = 0,
            charStart = 0,
            charEnd = 10,
        )

    @Test
    fun shouldRunAgent_两条件同时满足才运行() {
        assertTrue(shouldRunAgent(agentEnabled = true, llmReady = true))
        assertFalse(shouldRunAgent(agentEnabled = false, llmReady = true))
        assertFalse(shouldRunAgent(agentEnabled = true, llmReady = false))
        assertFalse(shouldRunAgent(agentEnabled = false, llmReady = false))
    }

    @Test
    fun merge_普通证据优先且按chunkId去重() {
        val normal = listOf(evidence(1, content = "normal-1"), evidence(2, content = "normal-2"))
        val tool = listOf(
            evidence(2, content = "tool-2"), // 与普通重复 → 保留普通
            evidence(3, content = "tool-3"),
        )
        val merged = mergeAgentEvidence(normal, tool, limit = 10)

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.chunkId })
        assertEquals("normal-2", merged[1].content)
        assertEquals("tool-3", merged[2].content)
        assertEquals(listOf("S1", "S2", "S3"), merged.map { it.evidenceId })
    }

    @Test
    fun merge_工具证据去重并截断上限() {
        val merged = mergeAgentEvidence(
            normal = listOf(evidence(1)),
            tool = listOf(evidence(2), evidence(3), evidence(2)),
            limit = 2,
        )
        assertEquals(2, merged.size)
        assertEquals(listOf(1L, 2L), merged.map { it.chunkId })
    }

    @Test
    fun merge_空与非法上限() {
        assertTrue(mergeAgentEvidence(emptyList(), emptyList(), limit = 10).isEmpty())
        assertTrue(mergeAgentEvidence(listOf(evidence(1)), listOf(evidence(2)), limit = 0).isEmpty())
    }

    @Test
    fun normalizeEntityLookup_与实体表同口径() {
        assertEquals("蓝牙耳机", normalizeEntityLookup("  蓝牙耳机  "))
        assertEquals("bt earbuds", normalizeEntityLookup("BT   Earbuds"))
        assertEquals("", normalizeEntityLookup("   "))
        // 全角/组合字符 NFC 归一（与 core 口径一致；这里只验证不抛异常且 trim/lower）
        assertEquals("café", normalizeEntityLookup(" Cafe\u0301 "))
    }
}
