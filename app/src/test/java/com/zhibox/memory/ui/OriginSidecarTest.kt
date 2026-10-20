package com.zhibox.memory.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B14.2：网页 URL 导入的 `.origin` 旁车写入（纯 JVM，无 Android/网络依赖）。
 *
 * 覆盖 [writeOriginSidecarAtomically]：
 * 1) 成功：旁车与源文件同目录同名 + `.origin`，UTF-8 单行 URL，无临时文件残留；
 * 2) 覆盖：重复写时内容原子更新为目标 URL，不残留 `.tmp`；
 * 3) 非法 URL（http/空/含换行/超长）→ 抛异常且不创建旁车、不污染目录；
 * 4) 源文件不存在父目录以外场景不写脏数据（旁车失败清理策略由调用方删除正文）。
 */
class OriginSidecarTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun stagingFile(name: String = "蓝牙排查手册.txt"): File =
        File(tempFolder.root, name).apply { writeText("网页正文\n", Charsets.UTF_8) }

    @Test
    fun write_success_创建同目录单行UTF8旁车且无临时残留() {
        val target = stagingFile()
        val url = "https://example.com/docs/a?b=1&c=2"

        writeOriginSidecarAtomically(target, url)

        val sidecar = File(target.parentFile, target.name + ".origin")
        assertTrue("旁车应存在", sidecar.isFile)
        assertEquals(url, sidecar.readText(Charsets.UTF_8))
        assertEquals("旁车内容应为单行", 0, sidecar.readText(Charsets.UTF_8).count { it == '\n' })
        assertEquals("不应有临时文件残留", 0, target.parentFile!!.listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun write_twice_原子覆盖为最新URL() {
        val target = stagingFile()
        writeOriginSidecarAtomically(target, "https://example.com/first")
        writeOriginSidecarAtomically(target, "https://example.com/second")

        val sidecar = File(target.parentFile, target.name + ".origin")
        assertEquals("https://example.com/second", sidecar.readText(Charsets.UTF_8))
        assertEquals(0, target.parentFile!!.listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun write_httpUrl_拒绝且不创建旁车() {
        val target = stagingFile()
        try {
            writeOriginSidecarAtomically(target, "http://example.com/a")
            fail("http URL 应被拒绝")
        } catch (expected: IllegalArgumentException) {
            // 契约：仅 HTTPS
        }
        val sidecar = File(target.parentFile, target.name + ".origin")
        assertFalse("非法 URL 不应创建旁车", sidecar.exists())
        assertTrue("源文件应保持原样（回滚由调用方负责）", target.isFile)
    }

    @Test
    fun write_空或含换行URL_拒绝且清理临时文件() {
        val target = stagingFile()
        listOf("", "   ", "https://example.com/a\nhttps://example.com/b").forEach { bad ->
            try {
                writeOriginSidecarAtomically(target, bad)
                fail("非法 URL 应被拒绝：$bad")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
        val sidecar = File(target.parentFile, target.name + ".origin")
        assertNull("非法 URL 不应产生旁车", sidecar.takeIf { it.exists() })
        assertEquals(0, target.parentFile!!.listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun write_url带userinfo_仅基础前缀校验通过_由core兜底() {
        // UI 侧最低要求：单行 https；userinfo 等更强校验在 core SourceOriginUrls.normalize（导入时兜底删除）
        val target = stagingFile()
        writeOriginSidecarAtomically(target, "https://user:pass@example.com/a")

        val sidecar = File(target.parentFile, target.name + ".origin")
        assertTrue(sidecar.isFile)
        assertEquals("https://user:pass@example.com/a", sidecar.readText(Charsets.UTF_8))
    }
}
