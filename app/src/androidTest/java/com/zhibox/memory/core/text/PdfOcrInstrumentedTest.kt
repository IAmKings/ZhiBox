package com.zhibox.memory.core.text

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * P1.1：真实 ML Kit OCR instrumentation（真机/模拟器，bundled 离线模型；不联网）。
 *
 * 生成一页"扫描件"PDF（纸张上绘制文字，无文本层）→ [PdfTextExtractor.extractWithMetadata]
 * 文本层为空 → OCR fallback → 期望 parserId=`pdf-ocr-v1` 且识别出文字。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest --tests '*PdfOcrInstrumentedTest*'`（需设备）。
 */
@RunWith(AndroidJUnit4::class)
class PdfOcrInstrumentedTest {

    @Test
    fun textlessPdf_fallsBackToRealMlKitOcr() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfOcrPreferences.setEnabled(context, true)
        val file = File(context.cacheDir, "p11-ocr-${UUID.randomUUID()}.pdf")
        try {
            createScannedPdf(file)
            val result = PdfTextExtractor.extractWithMetadata(context, file)
            assertEquals("文本层为空时应走 OCR", PdfTextExtractor.OCR_PARSER_ID, result.parserId)
            assertTrue("ML Kit 应识别出文字：'${result.text}'", result.text.isNotBlank())
        } finally {
            file.delete()
        }
    }

    /** 用 PdfDocument 把文字画到页面 Canvas（输出无文本层的"扫描件"PDF）。 */
    private fun createScannedPdf(file: File) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(1240, 1754, 1).create()
            val page = document.startPage(pageInfo)
            try {
                val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                try {
                    val bitmapCanvas = Canvas(bitmap)
                    bitmapCanvas.drawColor(Color.WHITE)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        textSize = 140f
                        isAntiAlias = true
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    bitmapCanvas.drawText("HELLO OCR 2026", 90f, 500f, paint)
                    // 画成位图后再嵌入 PDF：没有文本层，等价扫描件。
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                document.finishPage(page)
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
