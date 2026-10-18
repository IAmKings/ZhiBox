package com.zhibox.memory.core.extraction

import com.zhibox.memory.core.extract.EntityType
import com.zhibox.memory.core.extract.ExtractedEntity
import com.zhibox.memory.core.extract.ExtractedEvent
import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.sag.SagVectorRole
import com.zhibox.memory.core.sag.SagVectorSpace
import com.zhibox.memory.core.text.BigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B9 抽取管线纯函数（JVM，无 Android/DB）：`SagExtractionSupport` + `SagErrorClassifier`。
 *
 * 覆盖：重试上限/可重试码矩阵、业务无效判定、实体归一化/去重/数量上限、嵌入文本与 FTS 文本口径、
 * canonicalText、错误详情脱敏；另含 `SagVectorSpace` 角色派生空间契约。
 */
class SagExtractionSupportTest {

    // ---- 重试与错误分类 ----

    @Test
    fun shouldRetryRespectsMaxAttemptsAndRetryableCodes() {
        assertEquals(3, SagExtractionSupport.MAX_ATTEMPTS)
        assertTrue(SagExtractionSupport.shouldRetry(1, LlmErrorCode.RATE_LIMITED))
        assertTrue(SagExtractionSupport.shouldRetry(2, SagExtractionErrorCodes.INTERNAL))
        assertFalse("达到 3 次上限应终态失败", SagExtractionSupport.shouldRetry(3, LlmErrorCode.RATE_LIMITED))
        assertFalse("不可重试码即使 attempt=1 也终态失败", SagExtractionSupport.shouldRetry(1, LlmErrorCode.AUTH))
        assertFalse("未知码默认不重试", SagExtractionSupport.shouldRetry(1, "SOME_UNKNOWN_CODE"))
    }

    @Test
    fun errorClassifierSeparatesRetryableAndTerminalCodes() {
        listOf(
            ExtractionErrorCodes.LLM_CALL_FAILED,
            LlmErrorCode.TIMEOUT,
            LlmErrorCode.NETWORK,
            LlmErrorCode.HTTP,
            LlmErrorCode.RATE_LIMITED,
            SagExtractionErrorCodes.EMBEDDING_UNAVAILABLE,
            SagExtractionErrorCodes.EMBEDDING_FAILED,
            SagExtractionErrorCodes.INTERNAL,
        ).forEach { code -> assertTrue("$code 应可重试", SagErrorClassifier.isRetryable(code)) }

        listOf(
            LlmErrorCode.AUTH,
            ExtractionErrorCodes.INVALID_JSON,
            ExtractionErrorCodes.INVALID_FIELD_TYPE,
            ExtractionErrorCodes.INVALID_ENTITY_TYPE,
            ExtractionErrorCodes.NOT_CONFIGURED,
            SagExtractionErrorCodes.INVALID_OUTPUT,
            SagExtractionErrorCodes.CHUNK_MISSING,
            SagExtractionErrorCodes.DOCUMENT_NOT_READY,
        ).forEach { code -> assertFalse("$code 应终态", SagErrorClassifier.isRetryable(code)) }
    }

    // ---- 业务校验 ----

    @Test
    fun invalidReasonFlagsInvalidEventOrBlankTitle() {
        assertNull(SagExtractionSupport.invalidReason(event()))
        assertTrue(SagExtractionSupport.invalidReason(event(isValid = false)).orEmpty().contains("isValid=false"))
        assertTrue(SagExtractionSupport.invalidReason(event(title = "  ")).orEmpty().contains("标题"))
    }

    // ---- 实体规范化 / 去重 / 上限 ----

    @Test
    fun normalizeEntityNameNfcLowerCaseAndCollapseWhitespace() {
        assertEquals("张三", SagExtractionSupport.normalizeEntityName(" 张三 "))
        assertEquals("math subject", SagExtractionSupport.normalizeEntityName("Math   Subject"))
        // NFC：组合字符与预组合字符归一（e + ́ → é）
        assertEquals(
            SagExtractionSupport.normalizeEntityName("caf\u00e9"),
            SagExtractionSupport.normalizeEntityName("cafe\u0301"),
        )
    }

    @Test
    fun prepareEntitiesDropsBlankDedupesAndTrims() {
        val entities = listOf(
            ExtractedEntity(EntityType.PERSON, " 张三 ", "学生"),
            ExtractedEntity(EntityType.PERSON, "张三", "重复"),
            ExtractedEntity(EntityType.LOCATION, "  ", "空名丢弃"),
            ExtractedEntity(EntityType.SUBJECT, "Math", "学科"),
        )
        val prepared = SagExtractionSupport.prepareEntities(entities)
        assertEquals(2, prepared.size)
        assertEquals("张三", prepared[0].name)
        assertEquals(EntityType.PERSON, prepared[0].type)
        assertEquals("Math", prepared[1].name)
    }

    @Test
    fun prepareEntitiesCapsAtMaxEntities() {
        val many = (1..SagExtractionSupport.MAX_ENTITIES_PER_EVENT + 10).map { i ->
            ExtractedEntity(EntityType.TAGS, "entity-$i", "")
        }
        assertEquals(
            SagExtractionSupport.MAX_ENTITIES_PER_EVENT,
            SagExtractionSupport.prepareEntities(many).size,
        )
    }

    // ---- 嵌入文本 / FTS 文本口径 ----

    @Test
    fun embeddingTextsFallbackThroughContentSummaryTitle() {
        assertEquals("正文", SagExtractionSupport.eventEmbeddingText(event(content = "正文")))
        assertEquals("摘要", SagExtractionSupport.eventEmbeddingText(event(content = " ", summary = "摘要")))
        assertEquals("标题", SagExtractionSupport.eventEmbeddingText(event(content = "", summary = "", title = "标题")))
        assertEquals(
            "描述",
            SagExtractionSupport.entityEmbeddingText(ExtractedEntity(EntityType.PERSON, "张三", "描述")),
        )
        assertEquals(
            "张三",
            SagExtractionSupport.entityEmbeddingText(ExtractedEntity(EntityType.PERSON, "张三", " ")),
        )
    }

    @Test
    fun ftsTextJoinsNonBlankFieldsAndUsesBigramTokenizer() {
        val rawEventText = SagExtractionSupport.eventFtsText(" 标题 ", " ", "正文")
        assertEquals("标题 正文", rawEventText)
        assertEquals(
            BigramTokenizer.toFtsText(rawEventText),
            SagExtractionSupport.toFtsText(rawEventText),
        )
        assertEquals("张三 学生", SagExtractionSupport.entityFtsText(" 张三 ", "学生"))
        assertEquals("张三", SagExtractionSupport.entityFtsText("张三", "  "))
    }

    @Test
    fun canonicalEventTextCombinesTitleAndSummary() {
        val canonical = SagExtractionSupport.canonicalEventText(event(title = "标题", summary = "摘要"))
        assertTrue(canonical.contains("标题"))
        assertTrue(canonical.contains("摘要"))
        assertFalse(canonical.contains("\r"))
    }

    // ---- 错误详情脱敏 ----

    @Test
    fun safeErrorDetailHidesRawOutputForParseErrors() {
        val raw = "LLM 原始输出：张三在上海学习数学。"
        assertEquals(
            "LLM 输出不是合法抽取 JSON",
            SagExtractionSupport.safeErrorDetail(ExtractionErrorCodes.INVALID_JSON, raw, "fallback"),
        )
        assertEquals(
            "LLM 输出为空",
            SagExtractionSupport.safeErrorDetail(ExtractionErrorCodes.EMPTY_OUTPUT, raw, "fallback"),
        )
        assertEquals(
            "调用远程 LLM 失败（网络/服务端/限流/鉴权），详见错误码",
            SagExtractionSupport.safeErrorDetail(ExtractionErrorCodes.LLM_CALL_FAILED, raw, "fallback"),
        )
    }

    @Test
    fun safeErrorDetailKeepsFieldMessagesAndTruncates() {
        assertEquals(
            "缺少字段：title",
            SagExtractionSupport.safeErrorDetail(ExtractionErrorCodes.MISSING_FIELD, "缺少字段：title", "fallback"),
        )
        assertEquals(
            "fallback",
            SagExtractionSupport.safeErrorDetail(ExtractionErrorCodes.MISSING_FIELD, "  ", "fallback"),
        )
        val long = "x".repeat(500)
        val detail = SagExtractionSupport.safeErrorDetail("OTHER", long, "fallback")
        assertEquals(SagExtractionSupport.MAX_ERROR_DETAIL_LENGTH, detail.length)
    }

    // ---- 辅助构造 ----

    private fun event(
        title: String = "标题",
        content: String = "正文",
        summary: String = "摘要",
        isValid: Boolean = true,
    ): ExtractedEvent = ExtractedEvent(
        title = title,
        summary = summary,
        content = content,
        language = "zh",
        entities = listOf(ExtractedEntity(EntityType.PERSON, "张三", "学生")),
        isValid = isValid,
    )
}

/**
 * B9 SAG 向量角色/派生空间契约（B9/B10 共用，测试锁定稳定 wire 与分隔符）。
 */
class SagVectorContractTest {

    @Test
    fun roleWiresAreStable() {
        assertEquals(listOf("event_content", "entity_description"), SagVectorRole.WIRES)
        assertEquals(SagVectorRole.EVENT_CONTENT, SagVectorRole.fromWire("event_content"))
        assertEquals(SagVectorRole.ENTITY_DESCRIPTION, SagVectorRole.fromWire("entity_description"))
        assertNull(SagVectorRole.fromWire("content"))
        assertNull(SagVectorRole.fromWire(null))
    }

    @Test
    fun derivedSpaceIsStableAndRoleSeparated() {
        val base = "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1"
        val eventSpace = SagVectorSpace.spaceIdFor(base, SagVectorRole.EVENT_CONTENT)
        val entitySpace = SagVectorSpace.spaceIdFor(base, SagVectorRole.ENTITY_DESCRIPTION)
        assertEquals("$base#role=event_content", eventSpace)
        assertEquals("$base#role=entity_description", entitySpace)
        assertEquals(eventSpace, SagVectorSpace.spaceIdFor(base, SagVectorRole.EVENT_CONTENT))

        SagVectorSpace.requireMatches(eventSpace, base, SagVectorRole.EVENT_CONTENT)
        SagVectorSpace.requireMatches(entitySpace, base, SagVectorRole.ENTITY_DESCRIPTION)
        assertThrows(IllegalArgumentException::class.java) {
            SagVectorSpace.requireMatches(eventSpace, base, SagVectorRole.ENTITY_DESCRIPTION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SagVectorSpace.spaceIdFor("  ", SagVectorRole.EVENT_CONTENT)
        }
    }
}
