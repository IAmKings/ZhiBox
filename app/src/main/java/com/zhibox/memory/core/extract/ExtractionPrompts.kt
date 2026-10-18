package com.zhibox.memory.core.extract

/**
 * B8 抽取提示词（v1）。
 *
 * 设计要点（与 SAG 参考实现的差异，刻意收敛）：
 * - **单 chunk 单 event**：参考实现（0.14.0 多 event + children 树）对本 App 的 K12 个人知识库
 *   过重（LLM 调用成本/失败面/展示复杂度），B9 明确"1 chunk=1 event"，故提示词禁止
 *   `events[]`/`children`/`sub_events` 等结构；
 * - **忠实性优先**：只允许原文出现的事实/实体，禁止推断、补全、编造；
 * - 固定 **11 类实体**（[EntityType]），未知类型归入 tags，不允许自造类型；
 * - **严格 JSON**：只输出一个 JSON 对象，无 markdown 代码块/前后缀文本，字段名固定，
 *   便于 [ExtractionJson] 严格校验与失败可读。
 *
 * 提示词版本升级必须同步：`ExtractionPrompts.VERSION`、`ExtractionJson` 的字段契约、
 * 数据库 `event.promptVersion` 的取值（重抽取决策依据）。
 */
object ExtractionPrompts {

    /** 提示词版本（写入 event.promptVersion；与抽取器实现版本 extractorVersion 分开演进） */
    const val VERSION: String = "v1"

    /** 抽取器实现版本（写入 event.extractorVersion；提示词/解析器/后处理任一变化都应升级） */
    const val EXTRACTOR_VERSION: String = "egemma-sag-extract-v1"

    /**
     * 系统提示词：定义角色、唯一事件约束、忠实性、11 类实体、输出 JSON schema。
     *
     * @param entityTypes 本次允许的实体类型（默认 11 类全量；顺序影响提示词中的列举顺序）
     */
    fun systemPrompt(entityTypes: List<EntityType> = EntityType.ALL): String {
        val types = entityTypes.ifEmpty { EntityType.ALL }
        val typeLines = types.joinToString("\n") { "  - ${it.wire}（${it.label}）" }
        return buildString {
            appendLine("你是个人知识库的结构化抽取器。输入是一段文档分块（chunk），")
            appendLine("请抽取其中**唯一一个**核心事件，并识别与事件相关的实体。")
            appendLine()
            appendLine("硬性规则：")
            appendLine("1. 单 chunk 单 event：每个 chunk 只输出 1 个 event，不要输出 events 数组、")
            appendLine("   不要输出 children / sub_events / 子事件；输入很短或信息不足时也只输出 1 个。")
            appendLine("2. 忠实性：只使用本 chunk 原文中明确出现的事实、数字、名称与关系；")
            appendLine("   禁止推断、补全、编造；原文没有的实体不要输出；")
            appendLine("   不得从上下文提示（previousContext）中抽取事件内容，它只用于消解代词/断句。")
            appendLine("3. 语言：content/language 与原文语言一致；language 用 BCP-47 风格短标记")
            appendLine("   （中文 zh、英文 en、无法判断用 und）。")
            appendLine("4. 实体类型只能从以下 11 类中选择（wire 名必须原样使用）：")
            appendLine(typeLines)
            appendLine("   无法归入前 10 类的关键词/主题标签用 tags；不要自造类型。")
            appendLine("5. 事件有效性：信息过少、纯目录/页眉页脚/无意义片段时 isValid 填 false；")
            appendLine("   其余情况填 true。isValid=false 时仍要给出 title/summary/content（可为空）。")
            appendLine("6. 输出：只输出**一个严格 JSON 对象**，不要 markdown 代码块、不要解释文字、")
            appendLine("   不要在 JSON 前后加任何字符。字段名与类型必须与下面 schema 完全一致：")
            appendLine(
                """
                {
                  "title": "短标题（≤30 字）",
                  "summary": "一句话摘要",
                  "content": "忠实于原文的事实性正文",
                  "language": "zh",
                  "entities": [
                    {"type": "time", "name": "原文中的名称", "description": "一句话简述"}
                  ],
                  "isValid": true
                }
                """.trimIndent()
            )
        }.trim()
    }

    /**
     * 用户消息：包裹 chunk 内容与最小上下文。
     *
     * 用 XML 风格分隔符降低注入风险（chunk 内出现 `</chunk>` 等文本也不改变结构边界；
     * 提示词要求 LLM 把 chunk 当数据而非指令）。
     */
    fun userPrompt(input: ExtractionInput): String = buildString {
        appendLine("<source_title>")
        appendLine(input.sourceTitle.ifBlank { "（未命名来源）" })
        appendLine("</source_title>")
        val previous = input.previousContext?.trim().orEmpty()
        if (previous.isNotEmpty()) {
            appendLine("<previous_context>")
            appendLine("（仅用于指代消解，不得从本段抽取事件内容）")
            appendLine(previous.take(200))
            appendLine("</previous_context>")
        }
        appendLine("<chunk>")
        appendLine(input.content)
        appendLine("</chunk>")
        appendLine("请按系统提示词抽取 1 个 event，只输出严格 JSON。")
    }.trim()
}
