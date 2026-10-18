package com.zhibox.memory.core.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * B8 抽取 JSON 严格解析（**不抛异常**，失败返回 [ExtractionResult.Failure]）。
 *
 * 契约（与 [ExtractionPrompts] v1 输出 schema 一致）：
 * ```json
 * {
 *   "title": "...", "summary": "...", "content": "...", "language": "zh",
 *   "entities": [{"type": "time", "name": "...", "description": "..."}],
 *   "isValid": true
 * }
 * ```
 * - 顶层必须是 JSON 对象；`title/summary/content/language` 必须是字符串；
 * - `entities` 必须是数组（可为空），元素必须是对象且包含 `type/name/description`；
 *   `type` 必须是 11 类之一（大小写不敏感，见 [EntityType.fromWire]）；
 * - `isValid` 必须是 JSON 布尔值（字符串 "true" 视为类型错误）；
 * - 未知字段忽略（LLM 可能多输出 explanation 等，不应因此整条失败）；
 * - 容忍 LLM 常见的 markdown 代码块包裹（```json ... ```）——这是输出格式噪声而非数据错误，
 *   去掉围栏后仍按严格 schema 校验；其余任何缺字段/类型错误都返回 Failure，不崩溃。
 *
 * 错误码见 [ExtractionErrorCodes]（[ExtractionErrorCodes.INVALID_JSON] /
 * [ExtractionErrorCodes.MISSING_FIELD] / [ExtractionErrorCodes.INVALID_FIELD_TYPE] /
 * [ExtractionErrorCodes.INVALID_ENTITY_TYPE] / [ExtractionErrorCodes.EMPTY_OUTPUT]）。
 */
object ExtractionJson {

    /** 解析配置：不忽略字段级错误（字段在代码里手动严格校验）；未知字段由手动读取天然忽略 */
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = true
    }

    private val fenceStart = Regex("^\\s*```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val fenceEnd = Regex("\\s*```\\s*$")

    /** 解析 LLM 输出为 [ExtractionResult]；任何输入（含空串/坏 JSON/超长文本）都不得抛异常 */
    fun parse(raw: String): ExtractionResult {
        val cleaned = stripCodeFence(raw)
        if (cleaned.isBlank()) {
            return ExtractionResult.Failure(
                ExtractionErrorCodes.EMPTY_OUTPUT,
                "LLM 输出为空，无法抽取",
            )
        }
        return try {
            val root = json.parseToJsonElement(cleaned)
            ExtractionResult.Success(parseEvent(root))
        } catch (e: StrictJsonException) {
            ExtractionResult.Failure(e.code, e.message ?: "LLM 输出不符合抽取 JSON 契约")
        } catch (e: Exception) {
            // 兜底：Json.parseToJsonElement 抛出的序列化异常/深层递归等，一律转可读 Failure
            ExtractionResult.Failure(
                ExtractionErrorCodes.INVALID_JSON,
                "LLM 输出不是合法 JSON：${e.message?.take(160) ?: e.javaClass.simpleName}",
            )
        }
    }

    /** 宽松入口：解析失败返回 null（供测试/工具使用；业务路径请用 [parse] 拿错误码） */
    fun parseOrNull(raw: String): ExtractedEvent? =
        (parse(raw) as? ExtractionResult.Success)?.event

    private fun parseEvent(root: JsonElement): ExtractedEvent {
        val obj = root as? JsonObject
            ?: throw StrictJsonException(
                ExtractionErrorCodes.INVALID_JSON,
                "LLM 输出的顶层必须是 JSON 对象（实际 ${root::class.simpleName}）",
            )
        val title = requireString(obj, "title")
        val summary = requireString(obj, "summary")
        val content = requireString(obj, "content")
        val language = requireString(obj, "language")
        val entities = parseEntities(obj["entities"])
        val isValid = requireBoolean(obj, "isValid")
        return ExtractedEvent(
            title = title,
            summary = summary,
            content = content,
            language = language,
            entities = entities,
            isValid = isValid,
        )
    }

    private fun parseEntities(element: JsonElement?): List<ExtractedEntity> {
        if (element == null) {
            throw StrictJsonException(ExtractionErrorCodes.MISSING_FIELD, "缺少字段：entities")
        }
        val array = element as? JsonArray
            ?: throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "entities 必须是数组（实际 ${element::class.simpleName}）",
            )
        return array.mapIndexed { index, item ->
            val obj = item as? JsonObject
                ?: throw StrictJsonException(
                    ExtractionErrorCodes.INVALID_FIELD_TYPE,
                    "entities[$index] 必须是对象",
                )
            val typeRaw = requireString(obj, "type", "entities[$index]")
            val type = EntityType.fromWire(typeRaw)
                ?: throw StrictJsonException(
                    ExtractionErrorCodes.INVALID_ENTITY_TYPE,
                    "entities[$index].type 不是 11 类之一：$typeRaw",
                )
            val name = requireString(obj, "name", "entities[$index]")
            val description = requireString(obj, "description", "entities[$index]")
            ExtractedEntity(type = type, name = name, description = description)
        }
    }

    private fun requireString(obj: JsonObject, key: String, path: String = ""): String {
        val prefix = if (path.isEmpty()) "" else "$path."
        val element = obj[key]
            ?: throw StrictJsonException(
                ExtractionErrorCodes.MISSING_FIELD,
                "缺少字段：$prefix$key",
            )
        val primitive = element as? JsonPrimitive
            ?: throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "$prefix$key 必须是字符串",
            )
        if (!primitive.isString) {
            throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "$prefix$key 必须是字符串（实际 ${primitive.content}）",
            )
        }
        return primitive.content
    }

    private fun requireBoolean(obj: JsonObject, key: String): Boolean {
        val element = obj[key]
            ?: throw StrictJsonException(ExtractionErrorCodes.MISSING_FIELD, "缺少字段：$key")
        val primitive = element as? JsonPrimitive
            ?: throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "$key 必须是布尔值",
            )
        if (primitive.isString) {
            throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "$key 必须是布尔值（不允许字符串 \"${primitive.content}\"）",
            )
        }
        return primitive.booleanOrNull
            ?: throw StrictJsonException(
                ExtractionErrorCodes.INVALID_FIELD_TYPE,
                "$key 必须是布尔值（实际 ${primitive.content}）",
            )
    }

    /** 去掉 LLM 常见的 ```json 围栏；非围栏文本原样返回（strict 校验在后续步骤） */
    private fun stripCodeFence(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = fenceStart.replace(text, "")
            text = fenceEnd.replace(text, "")
        }
        return text.trim()
    }

    /** 内部严格校验异常：统一携带稳定错误码，对外由 [parse] 转 Failure */
    private class StrictJsonException(val code: String, message: String) : Exception(message)
}
