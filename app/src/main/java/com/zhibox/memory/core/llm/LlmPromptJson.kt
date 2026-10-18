package com.zhibox.memory.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * LLM JSON 输出解析的共享容错工具（B10 查询实体/上下文选择复用）。
 *
 * 说明：B10 契约要求"严格 JSON"，但真实服务端偶发会把 JSON 包进 markdown 代码块；
 * 这里只做"去 fence"这一步宽容，JSON 结构/字段本身仍按各调用方的封闭 schema **严格校验**，
 * 字段非法一律返回 null/空结果（由调用方降级），不抛业务异常。
 */

/** 去掉 LLM 偶尔包裹的 ```json / ``` fence；不改变 JSON 本体。 */
internal fun stripJsonFence(raw: String): String {
    var text = raw.trim()
    if (!text.startsWith("```")) return text
    // 去掉首行 ``` 或 ```json
    text = text.substringAfter('\n', missingDelimiterValue = "").trim()
    // 去掉末尾 ```
    val closing = text.lastIndexOf("```")
    if (closing >= 0) text = text.substring(0, closing).trim()
    return text
}

/** fence 容错 + 解析为 JsonObject；失败返回 null（不抛异常、不打日志）。 */
internal fun parseJsonObjectLoose(raw: String): JsonObject? = try {
    LLM_JSON.parseToJsonElement(stripJsonFence(raw)).jsonObject
} catch (e: Exception) {
    null
}

/** 压成单行并截断（用于把 chunk/标题片段放进 prompt，避免换行注入与上下文过长）。 */
internal fun toSingleLineExcerpt(text: String, maxChars: Int): String =
    text.replace(Regex("\\s+"), " ").trim().take(maxChars.coerceAtLeast(0))
