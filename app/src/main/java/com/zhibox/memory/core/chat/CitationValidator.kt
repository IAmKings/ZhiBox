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
    /** 规则检测到的“无引用断言句”数量（按中文句号/换行切分；仅警示，不自动删除正文）。 */
    val unreferencedSentenceCount: Int = 0,
) {
    /** ID 级引用合法性（兼容旧语义；不含逐句 coverage）。 */
    val valid: Boolean
        get() = citations.isNotEmpty() && invalidCitationTokens.isEmpty() && !unverified

    /** 更严格的“逐句有引用”状态（B11 发布/评测门禁用）。 */
    val fullyCited: Boolean
        get() = valid && unreferencedSentenceCount == 0
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
                unreferencedSentenceCount = 0,
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
        val unreferenced = countUnreferencedSentences(replaced.toString())
        return CitationValidation(
            text = replaced.toString(),
            citations = citations,
            invalidCitationTokens = invalid.toList(),
            hasAnyCitationToken = TOKEN.containsMatchIn(answer),
            unverified = citations.isEmpty(),
            unreferencedSentenceCount = unreferenced,
        )
    }

    /**
     * 规则版“未引用断言”检测：按中文/英文句末与换行切分，统计非空、含文字但
     * 无 `[S#]` token 的句子数量。它是保守提示（标题/列表前缀可能误报），
     * 不自动删改正文；真实支持率仍由 50 条抽样人工/LLM judge 承担。
     */
    private fun countUnreferencedSentences(text: String): Int {
        if (text.trim() == ChatPrompt.REFUSAL_MESSAGE) return 0
        return text.split(Regex("[。！？!?\\n]+"))
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .count { sentence ->
                sentence.length >= 4 &&
                    !sentence.startsWith("#") &&
                    sentence.any { it.isLetterOrDigit() } &&
                    !TOKEN.containsMatchIn(sentence)
            }
    }
}
