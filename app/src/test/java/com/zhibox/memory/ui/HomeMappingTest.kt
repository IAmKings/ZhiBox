package com.zhibox.memory.ui

import com.zhibox.memory.data.db.DocStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 首页时间线的纯映射逻辑单测（纯 JVM，不依赖 Android/Room）。
 *
 * 锁三件事：
 * 1. **分类判定**：parserId / MIME / 网页 provenance → 四类徽标的优先级不能漂移
 *    （决定卡片配色、页脚标签、药丸过滤是否一致）；
 * 2. **筛选映射**：`ALL` 必须返回 null（不过滤），其余分类一一对应；
 * 3. **日期分组与摘要**：今天/昨天/更早按本地自然日边界切分，摘要折叠空白并截断。
 */
class HomeMappingTest {

    // ── 分类判定 ──────────────────────────────────────────────────

    @Test
    fun categoryOf_webSnapshotByOriginUrl() {
        // 网页 URL 导入的文档即便解析成 txt，仍应归入「网页快照」而不是「念头」
        assertEquals(
            HomeCategory.SNAPSHOT,
            categoryOf(parserId = "txt-utf8-v1", mimeType = "text/plain", originUrl = "https://a.com/x"),
        )
    }

    @Test
    fun categoryOf_htmlParserIsSnapshot() {
        assertEquals(
            HomeCategory.SNAPSHOT,
            categoryOf(parserId = "html-text-v1", mimeType = null, originUrl = null),
        )
        assertEquals(
            HomeCategory.SNAPSHOT,
            categoryOf(parserId = "pending", mimeType = "text/html", originUrl = null),
        )
    }

    @Test
    fun categoryOf_structuredDocuments() {
        listOf("pdf-pdfbox-v1", "docx-ooxml-v1", "pptx-ooxml-v1", "xlsx-ooxml-v1").forEach {
            assertEquals(it, HomeCategory.DOCUMENT, categoryOf(it, null, null))
        }
    }

    @Test
    fun categoryOf_textNotes() {
        assertEquals(HomeCategory.NOTE, categoryOf("md-utf8-v1", "text/markdown", null))
        assertEquals(HomeCategory.NOTE, categoryOf("txt-utf8-v1", "text/plain", null))
        // 尚未回写解析器的新导入文档：按笔记处理，不丢卡片
        assertEquals(HomeCategory.NOTE, categoryOf("pending", null, null))
    }

    @Test
    fun categoryOf_imageWinsOverOthers() {
        assertEquals(
            HomeCategory.IMAGE,
            categoryOf(parserId = "image-v1", mimeType = "image/png", originUrl = null),
        )
        assertEquals(
            HomeCategory.IMAGE,
            categoryOf(parserId = "pending", mimeType = "image/jpeg", originUrl = null),
        )
    }

    @Test
    fun categoryOf_isCaseInsensitive() {
        assertEquals(HomeCategory.SNAPSHOT, categoryOf("HTML-TEXT-V1", null, null))
        assertEquals(HomeCategory.IMAGE, categoryOf("x", "IMAGE/PNG", null))
    }

    // ── 筛选映射 ──────────────────────────────────────────────────

    @Test
    fun toCategory_allMeansNoFilter() {
        assertNull(HomeFilter.ALL.toCategory())
        assertEquals(HomeCategory.NOTE, HomeFilter.NOTE.toCategory())
        assertEquals(HomeCategory.IMAGE, HomeFilter.IMAGE.toCategory())
        assertEquals(HomeCategory.DOCUMENT, HomeFilter.DOCUMENT.toCategory())
    }

    // ── 状态映射 ──────────────────────────────────────────────────

    @Test
    fun toHomeStatus_readyIsSilent() {
        assertNull(DocStatus.READY.toHomeStatus())
        assertEquals(HomeStatus.PROCESSING, DocStatus.PENDING.toHomeStatus())
        assertEquals(HomeStatus.PROCESSING, DocStatus.PROCESSING.toHomeStatus())
        assertEquals(HomeStatus.FAILED, DocStatus.FAILED.toHomeStatus())
        assertEquals(HomeStatus.CANCELLED, DocStatus.CANCELLED.toHomeStatus())
    }

    // ── 摘要折叠 ──────────────────────────────────────────────────

    @Test
    fun toSnippet_collapsesWhitespaceAndTrims() {
        assertEquals("第一行 第二行", "  第一行\n\n  第二行  ".toSnippet())
    }

    @Test
    fun toSnippet_truncatesWithEllipsis() {
        val long = "字".repeat(200)
        val snippet = long.toSnippet(limit = 10)
        assertEquals(11, snippet.length) // 10 + 省略号
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun toSnippet_keepsShortTextUntouched() {
        assertEquals("短文本", "短文本".toSnippet())
    }

    // ── 日期分组 ──────────────────────────────────────────────────

    @Test
    fun bucketOf_todayYesterdayEarlier() {
        val now = millis(2026, 10, 22, 20, 0)
        assertEquals(HomeGroup.TODAY, millis(2026, 10, 22, 0, 0).bucketOf(now))
        assertEquals(HomeGroup.TODAY, millis(2026, 10, 22, 19, 59).bucketOf(now))
        assertEquals(HomeGroup.YESTERDAY, millis(2026, 10, 21, 23, 59).bucketOf(now))
        assertEquals(HomeGroup.YESTERDAY, millis(2026, 10, 21, 0, 0).bucketOf(now))
        assertEquals(HomeGroup.EARLIER, millis(2026, 10, 20, 23, 59).bucketOf(now))
    }

    @Test
    fun bucketOf_boundaryAtMidnight() {
        val justAfterMidnight = millis(2026, 10, 22, 0, 0)
        assertEquals(HomeGroup.TODAY, millis(2026, 10, 22, 0, 0).bucketOf(justAfterMidnight))
        assertEquals(HomeGroup.YESTERDAY, millis(2026, 10, 21, 23, 59).bucketOf(justAfterMidnight))
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
