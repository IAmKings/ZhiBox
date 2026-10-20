package com.zhibox.memory.core.extraction

import com.zhibox.memory.core.extract.ExtractedEntity
import com.zhibox.memory.core.extract.ExtractedEvent
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.core.text.TextNormalizer
import java.text.Normalizer
import java.util.Locale

/**
 * B9 抽取管线的纯函数工具（校验/实体规范化/嵌入文本构造/重试决策/错误脱敏）。
 *
 * 放在独立对象中便于纯 JVM 单测（不依赖 Android/DB）；pipeline 与 scheduler 共用，
 * 避免"提示词口径 / FTS 口径 / 校验口径"在多个调用点各写一份而漂移。
 */
internal object SagExtractionSupport {

    /** 单 chunk 最大尝试次数：claim 时 attempt+1，达到上限后转终态 failed（供 UI 手动重试） */
    const val MAX_ATTEMPTS: Int = 3

    /** 单 event 最多保留的实体数（防御 LLM 异常输出膨胀；11 类是类型上限而非数量上限） */
    const val MAX_ENTITIES_PER_EVENT: Int = 64

    /** errorDetail 最大长度（可读原因，不含正文/密钥） */
    const val MAX_ERROR_DETAIL_LENGTH: Int = 200

    /** SAG 不可用时写入 job.errorDetail 的统一说明（文档仍 ready，可离线检索） */
    const val NOT_INDEXED_DETAIL: String =
        "SAG 增强未启用、未配置远程 LLM 或未允许联网，等待补抽取（文档仍可离线检索）"

    /**
     * 业务校验（在 [ExtractionJson][com.zhibox.memory.core.extract.ExtractionJson] 严格解析之后）：
     * - `isValid=false`：LLM 判定本 chunk 不适合作为事件 → 不落库（task-43："成功且 isValid 才写"）；
     * - `title` 空白：无法展示/检索 → 视为无效输出。
     *
     * @return null=合法；非空=可读原因（写 errorDetail）。这些均为终态（重试无意义）。
     */
    fun invalidReason(event: ExtractedEvent): String? = when {
        !event.isValid -> "抽取结果标记为无效（isValid=false），本 chunk 不生成事件"
        event.title.isBlank() -> "抽取结果缺少标题（title 为空），不生成事件"
        else -> null
    }

    /** 实体名归一化：NFC + trim + 小写 + 连续空白合一（跨文档去重键，与 entity.normalizedName 对应） */
    fun normalizeEntityName(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    /**
     * 实体预处理：丢弃空名、按 normalizedName 去重（保留首次出现）、限制数量。
     * 需在算向量之前调用，保证 embedding 数量与最终写入的实体数量一致。
     */
    fun prepareEntities(entities: List<ExtractedEntity>): List<ExtractedEntity> {
        val seen = HashSet<String>(entities.size)
        val out = ArrayList<ExtractedEntity>(entities.size)
        for (entity in entities) {
            val name = entity.name.trim()
            if (name.isEmpty()) continue
            if (!seen.add(normalizeEntityName(name))) continue
            out += entity.copy(name = name)
            if (out.size >= MAX_ENTITIES_PER_EVENT) break
        }
        return out
    }

    /** event 嵌入文本：以 content 为主；content 为空的无效场景已在校验阶段拦截，这里兜底 title/summary */
    fun eventEmbeddingText(event: ExtractedEvent): String =
        event.content.trim().ifBlank { event.summary.trim() }.ifBlank { event.title.trim() }

    /** entity 嵌入文本：description 为主（entity_description 角色语义）；空则回退实体名 */
    fun entityEmbeddingText(entity: ExtractedEntity): String =
        entity.description.trim().ifBlank { entity.name.trim() }

    /** event 关键词文本：title + summary + content（FTS bigram 化前的原始文本） */
    fun eventFtsText(event: ExtractedEvent): String =
        eventFtsText(event.title, event.summary, event.content)

    /** event 关键词文本（raw 字段重载，供 [SagFtsRebuilder] 从 EventEntity 重建同一口径） */
    fun eventFtsText(title: String, summary: String, content: String): String =
        listOf(title, summary, content)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * entity 关键词文本：name + description + aliases（B15.1）。
     *
     * FTS 写入/重建共用同一口径：别名进入 entity_fts 派生文本，使按别名查询也能通过 FTS 召回
     * canonical entity；aliases 为空时输出与 v8 完全一致（无别名回归不变）。
     */
    fun entityFtsText(
        name: String,
        description: String,
        aliases: List<String> = emptyList(),
    ): String =
        (listOf(name.trim(), description.trim()) + aliases.map { it.trim() })
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** event.canonicalText（B8 注释约定：title+summary 归一化文本） */
    fun canonicalEventText(event: ExtractedEvent): String =
        TextNormalizer.normalize(
            listOf(event.title.trim(), event.summary.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" "),
        )

    /** FTS4 预分词文本（与 chunk/entity FTS 同一 bigram 口径） */
    fun toFtsText(text: String): String = BigramTokenizer.toFtsText(text)

    /**
     * 重试决策：claim 已把 attempt+1（[attemptAfterClaim]）；`attempt < 3` 且错误可重试 → 重排队。
     * 达到上限或不可重试码 → 终态 failed。
     */
    fun shouldRetry(attemptAfterClaim: Int, code: String): Boolean =
        attemptAfterClaim < MAX_ATTEMPTS && SagErrorClassifier.isRetryable(code)

    /**
     * 错误详情脱敏：写入 DB 的 detail 不得包含 chunk 正文 / LLM 原始输出 / API Key。
     *
     * - 解析类错误（INVALID_JSON/EMPTY_OUTPUT）：不保留原始 message（可能回显输出片段）；
     * - LLM 调用类：使用固定安全文案（Provider 契约保证 message 不含正文，但为稳妥仍收敛口径）；
     * - 其余（字段缺失/类型错误/实体类型/业务校验）：保留解析器给出的字段级 message（不含正文）。
     */
    fun safeErrorDetail(code: String, message: String, fallback: String): String = when (code) {
        com.zhibox.memory.core.extract.ExtractionErrorCodes.INVALID_JSON ->
            "LLM 输出不是合法抽取 JSON"
        com.zhibox.memory.core.extract.ExtractionErrorCodes.EMPTY_OUTPUT ->
            "LLM 输出为空"
        com.zhibox.memory.core.extract.ExtractionErrorCodes.LLM_CALL_FAILED ->
            "调用远程 LLM 失败（网络/服务端/限流/鉴权），详见错误码"
        else -> message.trim().ifBlank { fallback }.take(MAX_ERROR_DETAIL_LENGTH)
    }
}
