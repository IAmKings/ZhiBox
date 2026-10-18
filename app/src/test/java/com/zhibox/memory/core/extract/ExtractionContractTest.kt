package com.zhibox.memory.core.extract

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 抽取契约单测（JVM，纯逻辑，不依赖网络/Android/LLM）：
 * - [EntityType] 11 类封闭集合 + wire 名稳定（DB/提示词/JSON 共用）+ 大小写/枚举名兼容；
 * - [ExtractionJson.parse] 严格 schema：合法/围栏/空输出/坏 JSON/缺字段/类型错误/未知类型/未知字段；
 *   任何输入不得抛异常；
 * - `isValid=false` 仍解析成功（由上层决定落库/丢弃）；
 * - [ExtractionPrompts] v1：11 类注入、单 chunk 单 event、严格 JSON、previousContext 截断语义；
 * - [MockEventEntityExtractor] 调用计数/输入记录/失败注入（供 B9 worker 测试）。
 */
class ExtractionContractTest {

    // ---- EntityType：11 类封闭集合 ----

    @Test
    fun entityTypeHasElevenStableWireNames() {
        assertEquals("B8 契约固定 11 类", 11, EntityType.entries.size)
        assertEquals(
            listOf(
                "time", "location", "person", "organization", "group", "subject",
                "work", "product", "action", "metric", "tags",
            ),
            EntityType.WIRE_NAMES,
        )
        EntityType.entries.forEach { type ->
            assertTrue("wire 应为小写英文：${type.name}", type.wire == type.wire.lowercase())
            assertTrue("label 不得为空：${type.name}", type.label.isNotBlank())
        }
        assertEquals("ALL 与 entries 一致", EntityType.entries, EntityType.ALL)
    }

    @Test
    fun fromWireIsCaseInsensitiveAndAcceptsEnumName() {
        assertEquals(EntityType.PERSON, EntityType.fromWire("person"))
        assertEquals(EntityType.PERSON, EntityType.fromWire(" PERSON "))
        assertEquals(EntityType.PERSON, EntityType.fromWire("PERSON"))
        assertEquals(EntityType.TIME, EntityType.fromWire("Time"))
        assertNull(EntityType.fromWire("unknown"))
        assertNull(EntityType.fromWire(""))
        assertNull(EntityType.fromWire(null))
    }

    // ---- ExtractionJson.parse：合法 JSON ----

    private val validJson = """
        {
          "title": "上海学习数学",
          "summary": "张三在上海学习数学",
          "content": "张三在上海学习数学。",
          "language": "zh",
          "entities": [
            {"type": "person", "name": "张三", "description": "学生"},
            {"type": "location", "name": "上海", "description": "地点"},
            {"type": "subject", "name": "数学", "description": "学科"}
          ],
          "isValid": true
        }
    """.trimIndent()

    @Test
    fun validJsonParsesToSuccess() {
        val result = ExtractionJson.parse(validJson)
        assertTrue("应成功：$result", result is ExtractionResult.Success)
        val event = (result as ExtractionResult.Success).event
        assertEquals("上海学习数学", event.title)
        assertEquals("张三在上海学习数学", event.summary)
        assertEquals("张三在上海学习数学。", event.content)
        assertEquals("zh", event.language)
        assertTrue(event.isValid)
        assertEquals(3, event.entities.size)
        assertEquals(EntityType.PERSON, event.entities[0].type)
        assertEquals("张三", event.entities[0].name)
        assertEquals("学生", event.entities[0].description)
        assertEquals(EntityType.SUBJECT, event.entities[2].type)
    }

    @Test
    fun lowercaseEnumNameInEntityTypeIsAccepted() {
        val json = validJson.replace("\"type\": \"person\"", "\"type\": \"PERSON\"")
        val event = ExtractionJson.parseOrNull(json)
        assertEquals(EntityType.PERSON, event?.entities?.first()?.type)
    }

    @Test
    fun entitiesMayBeEmptyArray() {
        val json = validJson.replace(
            Regex("""\"entities\": \[[\s\S]*?]"""),
            "\"entities\": []",
        )
        val event = ExtractionJson.parseOrNull(json)
        assertEquals(emptyList<ExtractedEntity>(), event?.entities)
    }

    @Test
    fun isValidFalseStillParsesAsSuccess() {
        val json = validJson.replace("\"isValid\": true", "\"isValid\": false")
        val result = ExtractionJson.parse(json)
        assertTrue(result is ExtractionResult.Success)
        assertFalse((result as ExtractionResult.Success).event.isValid)
    }

    @Test
    fun markdownFenceIsTolerated() {
        val fenced = "```json\n" + validJson + "\n```"
        val event = ExtractionJson.parseOrNull(fenced)
        assertEquals("上海学习数学", event?.title)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra = validJson.replaceFirst("{", """{"explanation":"多给的字段", """)
        val event = ExtractionJson.parseOrNull(withExtra)
        assertEquals("上海学习数学", event?.title)
    }

    // ---- 失败路径：稳定错误码，不抛异常 ----

    @Test
    fun blankOutputReturnsEmptyOutputCode() {
        assertEquals(ExtractionErrorCodes.EMPTY_OUTPUT, extractFailure("   \n ").code)
        assertEquals(ExtractionErrorCodes.EMPTY_OUTPUT, extractFailure("").code)
    }

    @Test
    fun invalidJsonReturnsInvalidJsonCode() {
        assertEquals(ExtractionErrorCodes.INVALID_JSON, extractFailure("not-json").code)
        assertEquals(ExtractionErrorCodes.INVALID_JSON, extractFailure(null).code)
        assertEquals(ExtractionErrorCodes.INVALID_JSON, extractFailure("[1,2,3]").code)
        assertEquals(ExtractionErrorCodes.INVALID_JSON, extractFailure("\"str\"").code)
    }

    @Test
    fun missingRequiredFieldReturnsMissingFieldCode() {
        val missingTitle = validJson.replace(Regex("""\"title\": \"[^\"]*\",\s*"""), "")
        assertEquals(ExtractionErrorCodes.MISSING_FIELD, extractFailure(missingTitle).code)

        val missingEntities = validJson.replace(Regex("""\"entities\": \[[\s\S]*?],"""), "")
        assertEquals(ExtractionErrorCodes.MISSING_FIELD, extractFailure(missingEntities).code)

        val missingIsValid = validJson.replace(Regex(""",\s*\"isValid\": true"""), "")
        assertEquals(ExtractionErrorCodes.MISSING_FIELD, extractFailure(missingIsValid).code)
    }

    @Test
    fun wrongFieldTypeReturnsInvalidFieldTypeCode() {
        val numericTitle = validJson.replace("\"title\": \"上海学习数学\"", "\"title\": 42")
        assertEquals(ExtractionErrorCodes.INVALID_FIELD_TYPE, extractFailure(numericTitle).code)

        val entitiesObject = validJson.replace(
            Regex("""\"entities\": \[[\s\S]*?]"""),
            "\"entities\": {}",
        )
        assertEquals(ExtractionErrorCodes.INVALID_FIELD_TYPE, extractFailure(entitiesObject).code)

        val stringIsValid = validJson.replace("\"isValid\": true", "\"isValid\": \"true\"")
        assertEquals(ExtractionErrorCodes.INVALID_FIELD_TYPE, extractFailure(stringIsValid).code)
    }

    @Test
    fun invalidEntityEntriesAreFilteredNotFailWholeEvent() {
        // PRD FR-01：部分实体无效 → 过滤该实体，event 仍成功；无有效实体由 pipeline 兜底
        val badType = validJson.replace("\"type\": \"person\"", "\"type\": \"animal\"")
        val badTypeResult = ExtractionJson.parse(badType)
        assertTrue("未知实体类型应被过滤而不是整条失败：$badTypeResult", badTypeResult is ExtractionResult.Success)
        assertEquals(2, (badTypeResult as ExtractionResult.Success).event.entities.size)

        val notObject = validJson.replace(
            Regex("""\"entities\": \[[\s\S]*?]"""),
            "\"entities\": [\"person\", {\"type\":\"time\",\"name\":\"今天\",\"description\":\"时间\"}]",
        )
        val notObjectResult = ExtractionJson.parse(notObject)
        assertTrue("非对象实体应被过滤：$notObjectResult", notObjectResult is ExtractionResult.Success)
        assertEquals(1, (notObjectResult as ExtractionResult.Success).event.entities.size)
    }

    @Test
    fun parseNeverThrowsOnWeirdInputs() {
        val weirdInputs = listOf(
            "", " ", "{", "}", "null", "[]", "true", "42",
            "{\"title\":{}}", "{\"entities\":[null]}",
            "{\"entities\":[{\"type\":1}]}",
            "{\"title\":\"" + "x".repeat(10_000) + "\"}",
            "{\"isValid\":null}",
            "{\"a\":{\"b\":{\"c\":1}}}",
        )
        weirdInputs.forEach { raw ->
            val result = runCatching { ExtractionJson.parse(raw) }
            assertTrue("parse 不得抛异常：${raw.take(30)} -> ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("非法输入应返回 Failure：${raw.take(30)}", result.getOrThrow() is ExtractionResult.Failure)
        }
    }

    // ---- ExtractionPrompts：v1 契约 ----

    @Test
    fun systemPromptEmbedsAllElevenTypesAndSingleEventRule() {
        val prompt = ExtractionPrompts.systemPrompt()
        EntityType.WIRE_NAMES.forEach { wire ->
            assertTrue("提示词应包含实体类型 wire：$wire", prompt.contains(wire))
        }
        assertTrue("必须声明单 chunk 单 event", prompt.contains("单 chunk 单 event"))
        assertTrue("禁止多 event/children 结构", prompt.contains("children"))
        assertTrue("必须要求严格 JSON", prompt.contains("严格 JSON"))
        assertTrue("必须含 faithful 要求", prompt.contains("禁止推断"))
        assertTrue("schema 字段齐全", prompt.contains("\"title\"") && prompt.contains("\"isValid\""))
        assertEquals("v1", ExtractionPrompts.VERSION)
        assertTrue(ExtractionPrompts.EXTRACTOR_VERSION.isNotBlank())
    }

    @Test
    fun systemPromptHonorsEntityTypeSubset() {
        val prompt = ExtractionPrompts.systemPrompt(listOf(EntityType.PERSON, EntityType.TIME))
        assertTrue(prompt.contains("person"))
        assertTrue(prompt.contains("time"))
        assertFalse(prompt.contains("organization"))
    }

    @Test
    fun userPromptWrapsChunkAndTruncatesPreviousContext() {
        val input = ExtractionInput(
            chunkId = 7L,
            content = "张三在上海学习数学。",
            sourceTitle = "学习笔记",
            previousContext = "前".repeat(500),
        )
        val prompt = ExtractionPrompts.userPrompt(input)
        assertTrue(prompt.contains("<source_title>"))
        assertTrue(prompt.contains("学习笔记"))
        assertTrue(prompt.contains("<chunk>"))
        assertTrue(prompt.contains("张三在上海学习数学。"))
        assertTrue(prompt.contains("<previous_context>"))
        assertTrue("previousContext 应截断到 ≤200 字", prompt.contains("前".repeat(200)))
        assertFalse(prompt.contains("前".repeat(201)))
        assertFalse("不得把 chunk 当指令执行（无 key 概念）", prompt.contains("sk-"))
    }

    // ---- MockEventEntityExtractor：B9 worker 测试替身 ----

    @Test
    fun mockExtractorReturnsDefaultEventAndRecordsCalls() = runBlocking {
        val extractor = MockEventEntityExtractor()
        val input = ExtractionInput(chunkId = 1L, content = "张三在上海学习数学。", sourceTitle = "t")
        val result = extractor.extract(input)
        assertEquals(ExtractionResult.Success(MockEventEntityExtractor.DEFAULT_EVENT), result)
        assertEquals(1, extractor.calls)
        assertEquals(listOf(input), extractor.inputs)
    }

    @Test
    fun mockExtractorCanInjectFailure() = runBlocking {
        val failure = ExtractionResult.Failure(ExtractionErrorCodes.LLM_CALL_FAILED, "timeout")
        val extractor = MockEventEntityExtractor(failure)
        val result = extractor.extract(ExtractionInput(1L, "x", "t"))
        assertEquals(failure, result)
        assertEquals(1, extractor.calls)
    }

    @Test
    fun extractionInputDefaultsToAllEntityTypes() {
        val input = ExtractionInput(chunkId = 1L, content = "x", sourceTitle = "t")
        assertEquals(EntityType.ALL, input.entityTypes)
        assertNull(input.previousContext)
    }

    // ---- 工具 ----

    private fun extractFailure(raw: String?): ExtractionResult.Failure {
        val input = raw ?: return ExtractionResult.Failure(ExtractionErrorCodes.INVALID_JSON, "null")
        val result = ExtractionJson.parse(input)
        assertTrue("应失败：$raw -> $result", result is ExtractionResult.Failure)
        return result as ExtractionResult.Failure
    }
}
