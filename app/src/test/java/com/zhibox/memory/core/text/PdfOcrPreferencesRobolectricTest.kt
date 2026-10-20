package com.zhibox.memory.core.text

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P1.1：PDF OCR 开关默认开启、可关闭/持久化（Robolectric 真实 SharedPreferences）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfOcrPreferencesRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun prefs() = app.getSharedPreferences("pdf_ocr_preferences", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs().edit().clear().commit()
    }

    @Test
    fun `ocr default is enabled`() {
        assertTrue("默认应开启 PDF OCR", PdfOcrPreferences.enabled(app))
    }

    @Test
    fun `ocr can be disabled and persisted`() {
        PdfOcrPreferences.setEnabled(app, false)
        assertFalse(PdfOcrPreferences.enabled(app))
        // 再读一次模拟 UI 重入/进程内新读取路径
        assertFalse(PdfOcrPreferences.enabled(app))
    }

    @Test
    fun `ocr can be re-enabled`() {
        PdfOcrPreferences.setEnabled(app, false)
        PdfOcrPreferences.setEnabled(app, true)
        assertTrue(PdfOcrPreferences.enabled(app))
    }
}
