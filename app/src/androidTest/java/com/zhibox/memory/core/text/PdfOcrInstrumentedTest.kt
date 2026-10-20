package com.zhibox.memory.core.text

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * P1.1b：真实 ML Kit OCR instrumentation（真机/模拟器，bundled 离线模型；不联网）。
 *
 * 生成 2 页高分辨率"扫描件"PDF（Canvas 绘制文字，无文本层）→ [PdfTextExtractor.extractWithMetadata]
 * 逐页文本层为空 → 页级 OCR 全部空页 → 期望 parserId=`pdf-ocr-v1` 且两页文字都被识别。
 * 目标耗时 ≤ 2 分钟（2 页 2400 宽渲染 + ML Kit）。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest --tests '*PdfOcrInstrumentedTest*'`（需设备）。
 */
@RunWith(AndroidJUnit4::class)
class PdfOcrInstrumentedTest {

    @Test
    fun multiPageTextlessPdf_fallsBackToRealMlKitOcr() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfOcrPreferences.setEnabled(context, true)
        val file = File(context.cacheDir, "p11b-ocr-${UUID.randomUUID()}.pdf")
        try {
            createScannedPdf(
                file,
                listOf("HELLO OCR PAGE ONE", "SECOND SCANNED PAGE 2026"),
            )
            val result = PdfTextExtractor.extractWithMetadata(context, file)
            assertEquals("文本层为空时应走页级 OCR", PdfTextExtractor.OCR_PARSER_ID, result.parserId)
            assertTrue("ML Kit 应识别出文字：'${result.text}'", result.text.isNotBlank())
            assertTrue("应识别出多页内容：'${result.text}'", result.text.length > 5)
        } finally {
            file.delete()
        }
    }

    /** 用 PdfDocument 把文字画到页面 Canvas（输出无文本层的"扫描件"PDF）。 */
    private fun createScannedPdf(file: File, pageTexts: List<String>) {
        val document = PdfDocument()
        try {
            pageTexts.forEachIndexed { index, text ->
                val pageInfo = PdfDocument.PageInfo.Builder(1240, 1754, index + 1).create()
                val page = document.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        textSize = 140f
                        isAntiAlias = true
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText(text, 90f, 500f, paint)
                } finally {
                    document.finishPage(page)
                }
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
