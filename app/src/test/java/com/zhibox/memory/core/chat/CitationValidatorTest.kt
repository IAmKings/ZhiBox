package com.zhibox.memory.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B11 引用强制校验（JVM 纯逻辑）：
 * - 只接受本次证据 `S1..Sn`；`S0`/`S99`/小写 `[s1]`/重复/无证据时 token 的处理；
 * - 合法引用去重、按正文出现顺序、rank 从 0 递增；
 * - 非法 token 替换为 `[未通过证据校验]`，不静默删除；
 * - `unverified`：无有效引用的非空答案；`valid` 语义 = 有引用 && 无非法 token && 非 unverified；
 * - 多行/中文/emoji 文本保持原样（只改 citation token）。
 */
class CitationValidatorTest {

    // ---- 无证据 / 空白答案 ----

    @Test
    fun blankAnswerReturnsUnverifiedFalseAndNoTokens() {
        val evidence = listOf(evidence(1L))
        val result = CitationValidator.validate("   ", evidence)
        assertEquals("   ", result.text)
        assertTrue(result.citations.isEmpty())
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertFalse("空白答案不产生 token", result.hasAnyCitationToken)
        assertFalse("空白答案不算 unverified（上层应直接拒答）", result.unverified)
        assertFalse(result.valid)
    }

    @Test
    fun noEvidenceMarksNonBlankAnswerUnverifiedAndDoesNotRewrite() {
        val result = CitationValidator.validate("这是没有证据的回答 [S1]", emptyList())
        assertEquals("无证据时不做 token 替换（上层应拒答/不调用 LLM）", "这是没有证据的回答 [S1]", result.text)
        assertTrue(result.citations.isEmpty())
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertFalse(result.hasAnyCitationToken)
        assertTrue(result.unverified)
        assertFalse(result.valid)
    }

    // ---- 合法引用 ----

    @Test
    fun validCitationsAreDedupedAndOrderedByFirstAppearance() {
        val evidence = listOf(evidence(101L), evidence(202L))
        val answer = "结论一[S2]，结论二[S1]，再次[S2]。"
        val result = CitationValidator.validate(answer, evidence)

        assertEquals("合法 token 原样保留", answer, result.text)
        assertEquals("按正文首次出现顺序", listOf("S2", "S1"), result.citations.map { it.evidenceId })
        assertEquals("chunkId 按 evidence 顺序映射 S1→101/S2→202", listOf(202L, 101L), result.citations.map { it.chunkId })
        assertEquals("rank 使用原始 token 编号-1（S2→1, S1→0），不按首次出现顺序", listOf(1, 0), result.citations.map { it.rank })
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertTrue(result.hasAnyCitationToken)
        assertFalse(result.unverified)
        assertTrue(result.valid)
    }

    @Test
    fun duplicateTokenKeepsSingleCitationButTextUnchanged() {
        val result = CitationValidator.validate("[S1][S1][S1]", listOf(evidence(7L)))
        assertEquals(1, result.citations.size)
        assertEquals(0, result.citations.single().rank)
        assertEquals("[S1][S1][S1]", result.text)
        assertTrue(result.valid)
    }

    @Test
    fun citationMapsEvidenceByListIndex() {
        val evidence = listOf(
            evidence(chunkId = 10L, evidenceId = "ignored"),
            evidence(chunkId = 20L, evidenceId = "ignored"),
        )
        val result = CitationValidator.validate("[S1][S2]", evidence)
        assertEquals(listOf(10L, 20L), result.citations.map { it.chunkId })
        assertEquals("evidenceId 由位置决定（S1 起始）", listOf("S1", "S2"), result.citations.map { it.evidenceId })
    }

    // ---- 非法引用 ----

    @Test
    fun outOfRangeCitationsAreReplacedWithMarker() {
        val result = CitationValidator.validate("结论[S0]与[S99]。", listOf(evidence(1L)))
        assertEquals("结论[未通过证据校验]与[未通过证据校验]。", result.text)
        assertEquals(listOf("[S0]", "[S99]"), result.invalidCitationTokens)
        assertTrue(result.citations.isEmpty())
        assertTrue(result.unverified)
        assertFalse(result.valid)
        assertTrue(result.hasAnyCitationToken)
    }

    @Test
    fun lowercaseTokenIsNotACitationAndStaysUntouched() {
        val result = CitationValidator.validate("结论[s1]。", listOf(evidence(1L)))
        assertEquals("小写 token 不匹配规则，原样保留", "结论[s1]。", result.text)
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertTrue(result.citations.isEmpty())
        assertFalse("大写 S 才是引用 token", result.hasAnyCitationToken)
        assertTrue(result.unverified)
        assertFalse(result.valid)
    }

    @Test
    fun mixedValidAndInvalidReplacesInvalidAndKeepsValid() {
        val result = CitationValidator.validate("甲[S1]乙[S9]丙[S2]", listOf(evidence(11L), evidence(22L)))
        assertEquals("甲[S1]乙[未通过证据校验]丙[S2]", result.text)
        assertEquals(listOf("S1", "S2"), result.citations.map { it.evidenceId })
        assertEquals(listOf("[S9]"), result.invalidCitationTokens)
        assertFalse("存在非法 token → valid=false", result.valid)
        assertFalse(result.unverified)
    }

    @Test
    fun answerWithoutAnyCitationIsUnverified() {
        val result = CitationValidator.validate("这是一个没有引用的回答。", listOf(evidence(1L)))
        assertEquals("这是一个没有引用的回答。", result.text)
        assertTrue(result.citations.isEmpty())
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertFalse(result.hasAnyCitationToken)
        assertTrue("无引用但非空 → UI 必须警示", result.unverified)
        assertFalse(result.valid)
    }

    // ---- 多行 / unicode ----

    @Test
    fun multilineUnicodeAnswerOnlyRewritesCitationTokens() {
        val original = "第一行：中文结论 [S2] 😀\n第二行：emoji 🚀 与符号 & < > [S9]\n第三行：[S1]"
        val result = CitationValidator.validate(original, listOf(evidence(1L), evidence(2L)))
        assertTrue(result.text.startsWith("第一行：中文结论 [S2] 😀\n第二行："))
        assertTrue(result.text.contains("emoji 🚀 与符号 & < > [未通过证据校验]"))
        assertTrue(result.text.endsWith("\n第三行：[S1]"))
        assertEquals(listOf("S2", "S1"), result.citations.map { it.evidenceId })
        assertEquals(listOf("[S9]"), result.invalidCitationTokens)
        assertFalse(result.unverified)
    }

    @Test
    fun adjacentTokensAndNoiseAreHandled() {
        val result = CitationValidator.validate("[S1][注释][S2][S 1]", listOf(evidence(1L), evidence(2L)))
        assertEquals("[S1][注释][S2][S 1]", result.text)
        assertEquals(listOf("S1", "S2"), result.citations.map { it.evidenceId })
        assertTrue(result.invalidCitationTokens.isEmpty())
        assertTrue(result.valid)
    }

    // ---- valid 语义 ----

    @Test
    fun validRequiresCitationsNoInvalidAndVerified() {
        val evidence = listOf(evidence(1L))
        assertTrue(CitationValidator.validate("[S1]", evidence).valid)
        assertFalse("无引用", CitationValidator.validate("答案", evidence).valid)
        assertFalse("有非法 token", CitationValidator.validate("[S1][S2]", evidence).valid)
        assertFalse("空答案", CitationValidator.validate("", evidence).valid)
    }

    // ---- ChatRole 契约（同文件锁定 B11 角色 wire/回退） ----

    @Test
    fun chatRoleWireAndUnknownFallback() {
        assertEquals("user", ChatRole.USER.wire)
        assertEquals("assistant", ChatRole.ASSISTANT.wire)
        assertEquals("system", ChatRole.SYSTEM.wire)
        assertEquals(ChatRole.USER, ChatRole.fromWire("user"))
        assertEquals(ChatRole.SYSTEM, ChatRole.fromWire("unknown-role"))
    }

    // ---- 辅助 ----

    private fun evidence(chunkId: Long, evidenceId: String = "S?") = ChatEvidence(
        evidenceId = evidenceId,
        chunkId = chunkId,
        documentId = chunkId * 10,
        sourceId = chunkId * 100,
        docTitle = "文档-$chunkId",
        sourceTitle = "来源-$chunkId",
        sourceUri = "content://test/$chunkId",
        content = "证据正文 $chunkId",
        ordinal = 0,
        charStart = 0,
        charEnd = 10,
    )
}
