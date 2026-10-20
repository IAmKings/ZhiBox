package com.zhibox.memory.ui

import com.zhibox.memory.core.ingest.PipelineError
import com.zhibox.memory.data.db.IngestJobEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.1：PDF 打开密码失败识别（纯 JVM，无 Android/网络/DB）。
 *
 * 覆盖 [requiresPdfPassword]：只有 FAILED 且用户可读错误是
 * “PDF 需要打开密码”/“PDF 打开密码不正确”才显示「输入 PDF 密码」入口；
 * 兼容新 `DOCUMENT_PARSE:<detail>` 编码与旧纯文本；其它解析失败/非失败阶段不误报。
 */
class PdfPasswordDetectionTest {

    private fun job(
        stage: String = "failed",
        error: String? = null,
    ) = IngestJobEntity(
        id = "job_1",
        sourceId = "src_1",
        documentId = "doc_1",
        stage = stage,
        progress = 100,
        error = error,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun failed_pdf需要打开密码_识别为可输密码() {
        val encoded = PipelineError.encode(
            PipelineError.DocumentParse(
                "PDF 需要打开密码（user password），无法抽取文本：请输入密码后重试",
            ),
        )
        assertTrue(requiresPdfPassword(job(error = encoded)))
    }

    @Test
    fun failed_pdf打开密码不正确_识别为可输密码() {
        val encoded = PipelineError.encode(
            PipelineError.DocumentParse("PDF 打开密码不正确：请重新输入 user password 后重试"),
        )
        assertTrue(requiresPdfPassword(job(error = encoded)))
    }

    @Test
    fun failed_旧纯文本密码错误_兼容识别() {
        assertTrue(requiresPdfPassword(job(error = "PDF 需要打开密码，无法抽取文本")))
        assertTrue(requiresPdfPassword(job(error = "PDF 打开密码不正确")))
    }

    @Test
    fun 非failed阶段_不显示密码入口() {
        val encoded = PipelineError.encode(
            PipelineError.DocumentParse("PDF 需要打开密码（user password）"),
        )
        listOf("queued", "parsing", "done", "cancelled").forEach { stage ->
            assertFalse("$stage 阶段不应显示密码入口", requiresPdfPassword(job(stage, encoded)))
        }
    }

    @Test
    fun 其它解析失败_不误报() {
        val otherErrors = listOf(
            PipelineError.encode(PipelineError.DocumentParse("PDF 无文本层且 OCR 失败")),
            PipelineError.encode(PipelineError.DocumentParse("Office 文档加密，无法解析")),
            PipelineError.encode(PipelineError.Io("源文件不存在")),
            null,
            "",
        )
        otherErrors.forEach { error ->
            assertFalse("不应误报：$error", requiresPdfPassword(job(error = error)))
        }
    }
}
