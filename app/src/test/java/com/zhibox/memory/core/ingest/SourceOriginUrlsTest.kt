package com.zhibox.memory.core.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B14.2 staging 旁车 `<fileName>.origin` 纯文件/URL 校验单测（无 Android/Room）：
 * - 只接受 HTTPS、无 userinfo、host 非空、≤2048；其它 → null（无效按无 URL 处理）；
 * - 旁车命名/读取/消费（成功删除）/孤儿清理（无源文件或不可导入的源文件）。
 */
class SourceOriginUrlsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun normalizeAcceptsOnlyHttpsWithoutUserInfo() {
        assertEquals("https://example.com/a", SourceOriginUrls.normalize("https://example.com/a"))
        assertEquals("https://example.com/a?b=1#c", SourceOriginUrls.normalize("  https://example.com/a?b=1#c\n"))
        assertEquals("https://例子.测试/路径", SourceOriginUrls.normalize("https://例子.测试/路径"))
        assertEquals("scheme 大小写也应接受（保留原文）", "HTTPS://example.com/x", SourceOriginUrls.normalize("HTTPS://example.com/x"))
        assertEquals("https://example.com:8443/a", SourceOriginUrls.normalize("https://example.com:8443/a"))

        assertNull("http 拒绝", SourceOriginUrls.normalize("http://example.com/a"))
        assertNull("userinfo 拒绝", SourceOriginUrls.normalize("https://user:pass@example.com/a"))
        assertNull("无 host 拒绝", SourceOriginUrls.normalize("https:///path"))
        assertNull("相对/非 URL 拒绝", SourceOriginUrls.normalize("not a url"))
        assertNull("空白拒绝", SourceOriginUrls.normalize("   "))
        assertNull("null 拒绝", SourceOriginUrls.normalize(null))
        assertNull("超长拒绝", SourceOriginUrls.normalize("https://example.com/" + "a".repeat(2048)))
        assertEquals(
            "恰好 2048 应接受",
            "https://example.com/" + "a".repeat(2048 - "https://example.com/".length),
            SourceOriginUrls.normalize("https://example.com/" + "a".repeat(2048 - "https://example.com/".length)),
        )
    }

    @Test
    fun sidecarNameFollowsStagingFileWithSuffix() {
        val dir = tmp.newFolder("staging")
        val file = File(dir, "note.txt")
        assertEquals(File(dir, "note.txt.origin"), SourceOriginUrls.sidecarFor(file))
        assertEquals(SourceOriginUrls.SIDECAR_SUFFIX, ".origin")
    }

    @Test
    fun readSidecarReturnsTrimmedUrlOrNullWhenMissing() {
        val dir = tmp.newFolder("read")
        val file = File(dir, "a.md").apply { writeText("正文") }
        assertNull("无旁车 → null", SourceOriginUrls.read(file))

        SourceOriginUrls.sidecarFor(file).writeText("  https://example.com/read\n")
        assertEquals("https://example.com/read", SourceOriginUrls.read(file))
    }

    @Test
    fun invalidSidecarIsDeletedAndReturnsNull() {
        val dir = tmp.newFolder("invalid")
        val cases = listOf(
            "http://example.com/x",
            "https://user:pass@example.com/x",
            "not-a-url",
            "https://example.com/" + "x".repeat(4096),
        )
        cases.forEachIndexed { index, raw ->
            val file = File(dir, "bad$index.txt").apply { writeText("正文") }
            val sidecar = SourceOriginUrls.sidecarFor(file).apply { writeText(raw) }
            assertNull("无效旁车 $index 应返回 null", SourceOriginUrls.read(file))
            assertFalse("无效旁车 $index 应删除", sidecar.exists())
        }
    }

    @Test
    fun consumeDeletesSidecar() {
        val dir = tmp.newFolder("consume")
        val file = File(dir, "note.md").apply { writeText("x") }
        val sidecar = SourceOriginUrls.sidecarFor(file).apply { writeText("https://example.com/note") }
        SourceOriginUrls.consume(file)
        assertFalse(sidecar.exists())
        assertTrue("源文件不受 consume 影响", file.exists())
    }

    @Test
    fun cleanupOrphansRemovesSidecarsWithoutImportableBaseFile() {
        val dir = tmp.newFolder("cleanup")
        fun write(name: String) = File(dir, name).apply { writeText("x") }
        val importable = { name: String ->
            name.endsWith(".txt") || name.endsWith(".md")
        }

        // 保留：源文件存在且可导入
        val keepBase = write("keep.md")
        val keepSidecar = write("keep.md.origin")
        // 删除：源文件不存在
        val orphanSidecar = write("missing.txt.origin")
        // 删除：源文件存在但扩展名不可导入
        write("note.zip")
        val unsupportedSidecar = write("note.zip.origin")
        // 删除：源文件隐藏（不可导入）
        write(".hidden.txt")
        val hiddenSidecar = write(".hidden.txt.origin")
        // 非旁车文件不受影响
        val other = write("plain.txt")

        SourceOriginUrls.cleanupOrphans(dir, importable)

        assertTrue("可导入源文件对应旁车应保留", keepSidecar.exists())
        assertTrue(keepBase.exists())
        assertFalse("无源文件孤儿旁车应删除", orphanSidecar.exists())
        assertFalse("不可导入源文件的旁车应删除", unsupportedSidecar.exists())
        assertTrue("不可导入源文件本身不动", File(dir, "note.zip").exists())
        assertFalse("隐藏源文件的旁车应删除（清理器跳过隐藏旁车本身 → 视实现）", hiddenSidecar.exists())
        assertTrue(other.exists())
    }
}
