package com.zhibox.memory.core.chat

/**
 * B11：引用强制校验（纯逻辑，可在 JVM/Robolectric 测试）。
 *
 * 规则（PRD FR-06）：
 * - 只接受本次证据 ID `S1..Sn`；`[S0]`/`[S9]`/重复/大小写不做猜测，全部视为非法；
 * - 非法 token 不静默删除，替换为 `[未通过证据校验]` 供 UI 标注；
 * - 无有效引用的非空答案标记 `unverified=true`（UI 必须警示）；
 * - 空白答案/无证据不产生合法引用（上层应直接拒答，不调用 LLM）。
 */
data class CitationValidation(
    val text: String,
    val citations: List<ChatCitation>,
    val invalidCitationTokens: List<String>,
    val hasAnyCitationToken: Boolean,
    val unverified: Boolean,
) {
    val valid: Boolean
        get() = citations.isNotEmpty() && invalidCitationTokens.isEmpty() && !unverified
}

object CitationValidator {

    private val TOKEN = Regex("\\[S(\\d+)]")

    /**
     * @param answer 模型原始回答
     * @param evidence 本次发送给模型的证据（顺序决定 S1..Sn）
     */
    fun validate(answer: String, evidence: List<ChatEvidence>): CitationValidation {
        if (answer.isBlank() || evidence.isEmpty()) {
            return CitationValidation(
                text = answer,
                citations = emptyList(),
                invalidCitationTokens = emptyList(),
                hasAnyCitationToken = false,
                unverified = answer.isNotBlank(),
            )
        }
        val byId = evidence.mapIndexed { index, item ->
            "S${index + 1}" to item
        }.toMap()
        val valid = LinkedHashMap<String, ChatCitation>()
        val invalid = LinkedHashSet<String>()
        val replaced = StringBuilder(answer.length)
        var last = 0
        TOKEN.findAll(answer).forEach { match ->
            replaced.append(answer, last, match.range.first)
            val token = match.value
            val id = "S${match.groupValues[1]}"
            val hit = byId[id]
            if (hit != null) {
                // rank = 原始 token 编号-1（S2→1）；这样重开会话时可由 rank 重建 S#，
                // 不能使用“首次出现顺序”，否则 [S2][S1] 会错位成 S1/S2。
                valid.putIfAbsent(
                    id,
                    ChatCitation(
                        evidenceId = id,
                        chunkId = hit.chunkId,
                        rank = match.groupValues[1].toInt() - 1,
                    ),
                )
                replaced.append(token)
            } else {
                invalid += token
                replaced.append("[未通过证据校验]")
            }
            last = match.range.last + 1
        }
        replaced.append(answer, last, answer.length)
        val citations = valid.values.toList()
        return CitationValidation(
            text = replaced.toString(),
            citations = citations,
            invalidCitationTokens = invalid.toList(),
            hasAnyCitationToken = TOKEN.containsMatchIn(answer),
            unverified = citations.isEmpty(),
        )
    }
}
