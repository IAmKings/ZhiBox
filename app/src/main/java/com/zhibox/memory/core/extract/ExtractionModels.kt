package com.zhibox.memory.core.extract

/**
 * 抽取实体（B8 契约）：类型 + 原文名称 + 简述。
 *
 * `name` 为原文中出现的规范名称（保留原始大小写/写法）；`description` 为结合 chunk 语境的
 * 一句话简述（LLM 生成，可为空串）。写入数据库时 `name` 会归一化为 `normalizedName` 去重，
 * `description` 汇总到 entity 行。
 */
data class ExtractedEntity(
    val type: EntityType,
    val name: String,
    val description: String,
)

/**
 * 单 chunk 抽取结果中的**唯一一个** event（B8 契约：1 chunk = 1 event）。
 *
 * - `title`：短标题（≤30 字建议）；
 * - `summary`：一句话摘要；
 * - `content`：事实性正文（可包含多条要点，但必须来自本 chunk，禁止编造）；
 * - `language`：BCP-47 风格语言标记（如 `zh`、`en`；无法判断时为 `und`）；
 * - `entities`：至多 11 类实体，可空（空列表表示本 chunk 无实体）；
 * - `isValid`：LLM 对自身抽取结果的置信判断；false 表示"本 chunk 不适合作为事件"，
 *   **仍然成功解析并落库**（active=false / status=discarded 由 B9 决定），供人工/统计追溯。
 */
data class ExtractedEvent(
    val title: String,
    val summary: String,
    val content: String,
    val language: String,
    val entities: List<ExtractedEntity>,
    val isValid: Boolean,
)

/**
 * 抽取输入（B8 契约）：一个 chunk 的文本 + 最小上下文。
 *
 * @param chunkId 数据库 chunk.id（写入 extraction_job/event 的关联键）
 * @param content chunk 正文（已含必要标题前缀，由导入链路决定）
 * @param sourceTitle 来源/文档标题（帮助 LLM 消歧，不单独作为事件内容）
 * @param previousContext 可选的上一 chunk 尾部上下文（≤200 字），仅辅助断句/代词消解；
 *   提示词要求"不得从 previousContext 抽取事件内容"
 * @param entityTypes 本次允许的实体类型（默认 11 类全量；测试/降级可裁剪）
 */
data class ExtractionInput(
    val chunkId: Long,
    val content: String,
    val sourceTitle: String,
    val previousContext: String? = null,
    val entityTypes: List<EntityType> = EntityType.ALL,
)

/**
 * 抽取结果（B8 契约）：成功携带 [ExtractedEvent]；失败携带**稳定错误码**与可读信息。
 *
 * 错误码不要贴到用户界面做逻辑分支（用户看 message）；上层重试/降级按 [ExtractionErrorCodes] 判断。
 * 解析失败也必须返回 Failure，不得抛异常/崩溃（[ExtractionJson.parse] 的契约）。
 */
sealed interface ExtractionResult {
    data class Success(val event: ExtractedEvent) : ExtractionResult

    data class Failure(val code: String, val message: String) : ExtractionResult
}

/** 抽取错误码（稳定字符串；B9 重试/降级/诊断使用） */
object ExtractionErrorCodes {
    /** LLM 输出不是合法 JSON */
    const val INVALID_JSON = "EXTRACT_INVALID_JSON"

    /** 必填字段缺失 */
    const val MISSING_FIELD = "EXTRACT_MISSING_FIELD"

    /** 字段类型错误（如 title 不是字符串、isValid 不是布尔） */
    const val INVALID_FIELD_TYPE = "EXTRACT_INVALID_FIELD_TYPE"

    /** 实体类型不在 11 类封闭集合内 */
    const val INVALID_ENTITY_TYPE = "EXTRACT_INVALID_ENTITY_TYPE"

    /** LLM 输出为空/纯空白 */
    const val EMPTY_OUTPUT = "EXTRACT_EMPTY_OUTPUT"

    /** LLM 调用失败（网络/HTTP/超时；B9 由 core/llm 产出，禁止携带密钥/正文） */
    const val LLM_CALL_FAILED = "EXTRACT_LLM_CALL_FAILED"

    /** 未配置 LLM 或功能开关关闭（文档仍 ready，走离线检索） */
    const val NOT_CONFIGURED = "EXTRACT_NOT_CONFIGURED"

    /** 用户取消 */
    const val CANCELLED = "EXTRACT_CANCELLED"
}
