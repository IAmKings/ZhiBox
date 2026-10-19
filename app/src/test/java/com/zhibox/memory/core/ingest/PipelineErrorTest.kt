package com.zhibox.memory.core.ingest

import com.zhibox.memory.core.text.DocumentParseException
import com.zhibox.memory.core.text.PdfExtractionErrorCodes
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-B2-7：结构化错误码编解码 + 分类 + 旧格式兼容。 */
class PipelineErrorTest {

    @Test
    fun encodeDecode_roundTrip_allCodes() {
        val samples = listOf(
            PipelineError.ModelMissing("no model"),
            PipelineError.ModelCorrupt("sha mismatch"),
            PipelineError.SpaceMismatch("space has no vectors"),
            PipelineError.Io("/data/x missing"),
            PipelineError.OutOfMemory("oom"),
            PipelineError.Cancelled("cancelled"),
            PipelineError.Unknown("boom"),
            PipelineError.DocumentParse("PDF 加密", retryable = false),
        )
        samples.forEach { original ->
            val decoded = PipelineError.decode(PipelineError.encode(original))!!
            assertEquals("code 必须可往返：$original", original.code, decoded.code)
        }
    }

    @Test
    fun decode_legacyPlainText_isUnknownAndStillReadable() {
        val legacy = "IllegalStateException: 文件损坏"
        val decoded = PipelineError.decode(legacy)!!
        assertEquals(PipelineErrorCode.UNKNOWN, decoded.code)
        assertEquals(legacy, decoded.detail)
        assertEquals(legacy, decoded.userMessage) // 旧纯文本原样展示，不丢信息
    }

    @Test
    fun decode_nullOrBlank_returnsNull() {
        assertNull(PipelineError.decode(null))
        assertNull(PipelineError.decode(""))
        assertNull(PipelineError.decode("   "))
    }

    @Test
    fun classify_mapsStableErrorFamilies() {
        assertEquals(PipelineErrorCode.IO, PipelineError.classify(IOException("disk")).code)
        assertEquals(
            PipelineErrorCode.CANCELLED,
            PipelineError.classify(CancellationException("stop")).code,
        )
        assertEquals(
            PipelineErrorCode.MODEL_MISSING,
            PipelineError.classify(IllegalStateException("缺少模型：x")).code,
        )
        assertEquals(
            PipelineErrorCode.MODEL_CORRUPT,
            PipelineError.classify(IllegalStateException("dimension drift: expected 768")).code,
        )
        assertEquals(
            PipelineErrorCode.SPACE_MISMATCH,
            PipelineError.classify(IllegalStateException("当前模型 x 的向量空间无数据（sp）")).code,
        )
        assertEquals(
            PipelineErrorCode.OOM,
            PipelineError.classify(OutOfMemoryError("oom")).code,
        )
        assertEquals(PipelineErrorCode.UNKNOWN, PipelineError.classify(RuntimeException("weird")).code)
    }

    @Test
    fun `document parse failures are deterministic except PDF init`() {
        val encrypted = PipelineError.classify(
            DocumentParseException("PDF 加密", "pdf-text-v1", PdfExtractionErrorCodes.ENCRYPTED),
        )
        assertEquals(PipelineErrorCode.DOCUMENT_PARSE, encrypted.code)
        assertFalse("加密 PDF 不应自动重试", encrypted.retryable)
        assertEquals("PDF 加密", encrypted.userMessage)

        val init = PipelineError.classify(
            DocumentParseException("初始化失败", "pdf-text-v1", PdfExtractionErrorCodes.INIT),
        )
        assertTrue("PDF 初始化瞬时失败才允许自动重试", init.retryable)
    }

    @Test
    fun retryablePolicy_isExplicit() {
        assertTrue(PipelineError.ModelMissing(null).retryable)
        assertTrue(PipelineError.Io(null).retryable)
        assertFalse(PipelineError.ModelCorrupt(null).retryable)
        assertFalse(PipelineError.SpaceMismatch(null).retryable)
        assertFalse(PipelineError.OutOfMemory(null).retryable)
        assertFalse(PipelineError.DocumentParse("parse", retryable = false).retryable)
    }

    @Test
    fun detailWithNewlineWithinLimit_isSanitizedToSingleLine() {
        // 审查 H2：换行必须在前 500 字以内才有意义（原测试把换行放在截断之后，断言必过）
        val encoded = PipelineError.encode(PipelineError.Unknown("line1\nline2\r\nline3"))
        assertFalse("编码串不得含换行：$encoded", encoded.contains('\n') || encoded.contains('\r'))
        assertEquals("UNKNOWN:line1 line2 line3", encoded)
        // 旧纯文本解码同样单行化
        val legacy = PipelineError.decode("旧错误\n第二行")!!
        assertFalse(legacy.detail!!.contains('\n'))
    }

    @Test
    fun encodedMessage_isSingleLineAndBounded() {
        val longDetail = "x".repeat(2000) + "\nsecond line"
        val encoded = PipelineError.encode(PipelineError.Unknown(longDetail))
        assertFalse(encoded.contains('\n'))
        assertTrue(encoded.length <= PipelineErrorCode.UNKNOWN.name.length + 1 + 500)
        assertTrue(jobErrorUserMessage(encoded) != null)
    }
}
