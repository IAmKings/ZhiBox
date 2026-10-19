package com.zhibox.memory.core.text

/**
 * P1 文本抽取预算（安全/性能上限，防 zip bomb、超大文档与异常网页）。
 *
 * 设计原则：
 * - 上限在**解压/读取阶段**就生效（不把解压后的全部内容读进内存）；
 * - 文本输出统一上限 [MAX_TEXT_CHARS]（超出截断，见 [DocumentParser] KDoc）；
 * - 这些是"安全阀"而非产品限制：正常学习资料远小于上限。
 */
object TextExtractionLimits {

    /** Office zip 条目总数上限（含不参与抽取的 entry；防大量小文件）。 */
    const val MAX_OFFICE_ENTRIES: Int = 2_048

    /** 单个解压条目字节上限（document.xml / slideN.xml / sheetN.xml 等）。 */
    const val MAX_OFFICE_ENTRY_BYTES: Long = 16L * 1024 * 1024

    /** 所有被读取条目的解压后总字节上限（防高压缩比 zip bomb）。 */
    const val MAX_OFFICE_TOTAL_BYTES: Long = 64L * 1024 * 1024

    /** 单文档输出文本字符上限（超出截断；200 万字约 3300+ chunk，已远超正常文档）。 */
    const val MAX_TEXT_CHARS: Int = 2_000_000

    /** Office 源文件上限（32MB；zip 解压另有更严格的总量预算）。 */
    const val MAX_OFFICE_SOURCE_BYTES: Long = 32L * 1024 * 1024

    /** 单个 HTML 文件输入字节上限（本地 .html 文件）。 */
    const val MAX_HTML_DOCUMENT_BYTES: Int = 4 * 1024 * 1024

    /** 本地 txt/md 输入字节上限。 */
    const val MAX_TEXT_INPUT_BYTES: Int = 32 * 1024 * 1024

    /** 网页抓取响应体上限（2MB；任务契约）。 */
    const val MAX_WEBPAGE_BYTES: Int = 2 * 1024 * 1024
}

/** Office 解压预算（internal：单测用更小值触发上限分支，不暴露给业务）。 */
internal data class OfficeExtractionLimits(
    val maxEntries: Int = TextExtractionLimits.MAX_OFFICE_ENTRIES,
    val maxEntryBytes: Long = TextExtractionLimits.MAX_OFFICE_ENTRY_BYTES,
    val maxTotalUncompressedBytes: Long = TextExtractionLimits.MAX_OFFICE_TOTAL_BYTES,
    val maxTextChars: Int = TextExtractionLimits.MAX_TEXT_CHARS,
) {
    companion object {
        val DEFAULT: OfficeExtractionLimits = OfficeExtractionLimits()
    }
}
