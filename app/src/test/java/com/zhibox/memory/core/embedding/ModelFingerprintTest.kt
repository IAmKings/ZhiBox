package com.zhibox.memory.core.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * P1-6 模型指纹单测（纯 JVM）：目录 SHA 优先 / 未收录流式 SHA-256 / 同名换文件检测 / 缓存。
 */
class ModelFingerprintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha12(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    @Test
    fun `目录已收录模型直接取 catalog sha 前 12 位（零 IO）`() {
        // 文件甚至不需要存在：已收录且尺寸无法核对时信任供应链哈希
        val file = File(tmp.root, "embeddinggemma-2-text-vision-440m.litertlm")
        assertEquals("92dcbea10889", ModelFingerprint.revision(file))
    }

    @Test
    fun `未收录模型流式计算 SHA-256 前 12 位`() {
        val file = tmp.newFile("custom-model.litertlm")
        file.writeText("hello zhibox")
        assertEquals(sha12("hello zhibox"), ModelFingerprint.revision(file))
    }

    @Test
    fun `同名换文件（尺寸与目录不符）改算实际哈希（P1-6）`() {
        val file = tmp.newFile("embeddinggemma-2-text-vision-440m.litertlm")
        file.writeText("tiny replacement")
        val revision = ModelFingerprint.revision(file)
        assertEquals(sha12("tiny replacement"), revision)
        assertNotEquals("92dcbea10889", revision) // 不再信任目录 SHA → 同名新版本可检出
    }

    @Test
    fun `文件缺失且未收录时返回 null`() {
        assertNull(ModelFingerprint.revision(File(tmp.root, "missing-default.litertlm")))
    }

    @Test
    fun `进程内缓存命中且文件替换后变化`() {
        val file = tmp.newFile("cache-probe.litertlm")
        file.writeText("version-A")
        val first = ModelFingerprint.revision(file)
        assertEquals(sha12("version-A"), first)
        // 同内容同 size+mtime：命中缓存（结果稳定）
        assertEquals(first, ModelFingerprint.revision(file))
        // 替换内容：size 变化 → 缓存 key 变化 → 重新计算
        file.writeText("version-B-longer")
        assertEquals(sha12("version-B-longer"), ModelFingerprint.revision(file))
    }

    @Test
    fun `缓存可按测试清空（不串扰其他用例）`() {
        ModelFingerprint.clearCacheForTest()
        assertNull(ModelFingerprint.revision(File(tmp.root, "x-unlisted.bin")))
    }
}
