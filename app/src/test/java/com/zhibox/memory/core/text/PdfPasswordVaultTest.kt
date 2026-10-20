package com.zhibox.memory.core.text

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.1：PDF 密码临时保险箱（纯 JVM）：
 * - put/take 一次性消费（take 后即移除，不残留）；
 * - 覆盖/remove/clearAll 清零持有的 char；
 * - 不持久化（本测试只能验证内存语义，持久化由代码审查保证：无 SharedPreferences/file 写入）。
 */
class PdfPasswordVaultTest {

    @After
    fun cleanup() {
        PdfPasswordVault.clearAll()
    }

    @Test
    fun `put then take returns password and removes it`() {
        val password = "secret".toCharArray()
        PdfPasswordVault.put("doc-1", password)

        val taken = PdfPasswordVault.take("doc-1")
        assertArrayEquals("应取回原密码", "secret".toCharArray(), taken)
        assertNull("take 后即移除（一次性消费）", PdfPasswordVault.take("doc-1"))
    }

    @Test
    fun `put overwrite clears previous char array`() {
        val old = "old-pass".toCharArray()
        PdfPasswordVault.put("doc-1", old)
        PdfPasswordVault.put("doc-1", "new-pass".toCharArray())

        assertTrue("覆盖时旧 char 必须清零", old.all { it == '\u0000' })
        assertArrayEquals("应保留新密码", "new-pass".toCharArray(), PdfPasswordVault.take("doc-1"))
    }

    @Test
    fun `remove clears stored password`() {
        val password = "remove-me".toCharArray()
        PdfPasswordVault.put("doc-1", password)
        PdfPasswordVault.remove("doc-1")

        assertTrue("remove 应清零 char", password.all { it == '\u0000' })
        assertNull(PdfPasswordVault.take("doc-1"))
    }

    @Test
    fun `clearAll clears every stored password`() {
        val first = "first".toCharArray()
        val second = "second".toCharArray()
        PdfPasswordVault.put("doc-1", first)
        PdfPasswordVault.put("doc-2", second)

        PdfPasswordVault.clearAll()

        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
        assertNull(PdfPasswordVault.take("doc-1"))
        assertNull(PdfPasswordVault.take("doc-2"))
    }

    @Test
    fun `empty ids or passwords are ignored`() {
        PdfPasswordVault.put("", "secret".toCharArray())
        PdfPasswordVault.put("doc-1", CharArray(0))
        assertNull(PdfPasswordVault.take(""))
        assertNull(PdfPasswordVault.take("doc-1"))
    }
}
