package com.zhibox.memory.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 模型完整性统一校验单测（P1-7 / P1-10）：纯 JVM。
 *
 * 背景：SAF 共享目录/备份导入此前只比文件长度（"同名等长"直接信任），
 * 模型被替换后 native 解析行为不可预测、向量空间语义静默错位。
 * 这里验证流式 SHA-256 + 精确尺寸的两条判定与可读错误信息。
 */
class ModelIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `已知内容的流式 SHA256 与标准向量一致`() = runBlocking {
        val f = tmp.newFile("m.litertlm")
        f.writeText("abc")
        // 用流式 API 计算，避免把整模型读进内存（真实模型 300-500MB）
        assertEquals(abcSha, ModelIntegrity.sha256Hex(f))
        assertTrue(ModelIntegrity.verify(f, abcSha, 3L).isSuccess)
    }

    @Test
    fun `sha256Hex 与 MessageDigest 直算一致（1MB 分块）`() {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val f = tmp.newFile("big.litertlm")
        f.writeBytes(bytes)
        assertEquals(expected, ModelIntegrity.sha256Hex(f))
    }

    @Test
    fun `尺寸不符时快速失败且错误可读`() = runBlocking {
        val f = tmp.newFile("m.litertlm")
        f.writeText("abc")
        val r = ModelIntegrity.verify(f, expectedSha256 = null, expectedSize = 99L)
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("尺寸不符"))
        assertTrue(msg, msg.contains("m.litertlm"))
    }

    @Test
    fun `SHA 不符时失败且错误可读`() = runBlocking {
        val f = tmp.newFile("m.litertlm")
        f.writeText("abc")
        val r = ModelIntegrity.verify(f, expectedSha256 = "0".repeat(64), expectedSize = 3L)
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("SHA-256 不符"))
        assertTrue(msg, msg.contains("重新下载"))
    }

    @Test
    fun `文件不存在时失败`() = runBlocking {
        val r = ModelIntegrity.verify(File(tmp.root, "absent.litertlm"), null, 1L)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("不存在"))
    }

    @Test
    fun `期望值为空时只校验文件存在（未收录模型兼容）`() = runBlocking {
        val f = tmp.newFile("legacy.task")
        f.writeText("x")
        assertTrue(ModelIntegrity.verify(f, null, null).isSuccess)
        assertTrue(ModelIntegrity.verify(f, "  ", 0L).isSuccess) // 空白 sha / 非正 size 均视为未提供
    }

    @Test
    fun `SAF 流式校验（verifyStream）`() = runBlocking {
        val input = { "abc".byteInputStream() as InputStream }
        assertTrue(ModelIntegrity.verifyStream(input(), abcSha).isSuccess)
        val r = ModelIntegrity.verifyStream(input(), "f".repeat(64))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("SHA-256 不符"))
        // 期望为空：不做哈希，直接通过（调用方另行核对长度）
        assertTrue(ModelIntegrity.verifyStream(input(), null).isSuccess)
    }

}
