package com.zhibox.memory.core.text

import java.text.Normalizer

/**
 * 文本规范化——对齐 zleap-sag octx_vector_protocol 的规范化定义：
 * UTF-8 语义、Unicode NFC、换行统一 LF、null 视为空串、不做首尾 trim（保留原文边界）。
 * 规范化结果同时用于分块（char_start/char_end 基于此文本）与 embedding 输入。
 */
object TextNormalizer {
    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        return nfc.replace("\r\n", "\n").replace('\r', '\n')
    }
}
