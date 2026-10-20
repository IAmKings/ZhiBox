package com.zhibox.memory.core.text

import android.content.Context

/**
 * P1.1：PDF OCR 开关（扫描件文本回退）。
 *
 * 存储：SharedPreferences（非敏感配置）；默认 **开启**（[DEFAULT_PDF_OCR_ENABLED] = true）——
 * 用户导入扫描件 PDF 时默认能拿到 OCR 文本；可在设置关闭（关闭时扫描件仍以可读失败提示）。
 *
 * 不涉及网络：ML Kit 使用 bundled 离线模型（`text-recognition-chinese`），识别全程本机完成。
 */
object PdfOcrPreferences {
    private const val PREFS_NAME = "pdf_ocr_preferences"
    private const val KEY_PDF_OCR_ENABLED = "pdf_ocr_enabled"

    /** 默认开启：扫描 PDF 默认走 OCR fallback。 */
    const val DEFAULT_PDF_OCR_ENABLED: Boolean = true

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前是否开启 PDF OCR（默认 true）。 */
    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PDF_OCR_ENABLED, DEFAULT_PDF_OCR_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PDF_OCR_ENABLED, enabled).apply()
    }
}
