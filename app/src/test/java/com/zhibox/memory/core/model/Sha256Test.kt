package com.zhibox.memory.core.model

import com.zhibox.memory.core.embedding.ModelFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * B4-9：SHA-256 唯一实现 [Sha256] 的单测（纯 JVM）。
 *
 * 锁定：标准测试向量、空输入、大文件流式与 MessageDigest 直算一致、InputStream 不被关闭、
 * 增量 digest 入口、以及两处旧 API 委托（ModelIntegrity / ModelFingerprint）行为不变。
 */
class Sha256Test {

    @get:Rule
    val tmp = TemporaryFolder()

    /** FIPS 180-2 标准向量：SHA-256("abc") */
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    /** FIPS 180-2 标准向量：SHA-256("") */
    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `abc 标准向量`() {
        assertEquals(abcSha, Sha256.hex("abc".toByteArray()))
    }

    @Test
    fun `空字节与空文件`() {
        assertEquals(emptySha, Sha256.hex(ByteArray(0)))
        val empty = tmp.newFile("empty-model.litertlm")
        assertEquals(emptySha, Sha256.hex(empty))
        assertEquals(emptySha, ModelIntegrity.sha256Hex(empty))
    }

    @Test
    fun `大文件流式哈希与 MessageDigest 直算一致`() {
        // 1MB 非平凡模式（覆盖跨 64KB 缓冲边界）
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val file = tmp.newFile("big-model.litertlm")
        file.writeBytes(bytes)

        assertEquals(expected, Sha256.hex(file))
        // 旧 API 兼容：ModelIntegrity 委托同一实现，结果一致
        assertEquals(expected, ModelIntegrity.sha256Hex(file))
        // 流式实现不整文件入内存：分段哈希与一次性 byte[] 结果一致
        assertEquals(expected, Sha256.hex(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `InputStream 不被关闭（调用方管理生命周期）`() {
        val tracking = TrackingInputStream("abc".toByteArray())
        assertEquals(abcSha, Sha256.hex(tracking))
        assertFalse("Sha256.hex(InputStream) 不应关闭输入流", tracking.closed)
        tracking.close()
        assertTrue(tracking.closed)
    }

    @Test
    fun `增量 digest 入口`() {
        val digest = Sha256.newDigest()
        digest.update("a".toByteArray())
        digest.update("bc".toByteArray())
        assertEquals(abcSha, Sha256.hex(digest))
        // hex(digest) 是一次性收尾（digest() 内部 reset）：再次 update 后需重新 newDigest 才能得到预期值
        val fresh = Sha256.newDigest()
        fresh.update(byteArrayOf(97, 98, 99))
        assertEquals(abcSha, Sha256.hex(fresh))
    }

    @Test
    fun `分块大小常量为 64KB`() {
        assertEquals(64 * 1024, Sha256.BUFFER_BYTES)
        assertEquals("SHA-256", Sha256.ALGORITHM)
    }

    @Test
    fun `ModelFingerprint 流式哈希委托 Sha256（未收录模型 revision = 前 12 位）`() {
        val file = tmp.newFile("unlisted-model.litertlm")
        file.writeText("hello zhibox")
        val expectedRevision = Sha256.hex(file).take(ModelFingerprint.REVISION_LENGTH)
        assertEquals(expectedRevision, ModelFingerprint.revision(file))
        assertEquals(expectedRevision, Sha256.hex("hello zhibox".toByteArray()).take(12))
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
