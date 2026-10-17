package com.zhibox.memory.core.embedding

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1-3 运行时生命周期单测（纯 JVM，无 Android 依赖）。
 * 覆盖：缓存命中不重建 / key 变化关闭旧实例 / 重建失败自愈 / shutdown 后重建 / shutdown 关闭实例。
 */
class EngineCacheTest {

    /** 可观测 close 的 fake provider；[probeDelayMs] 用于模拟 GPU 探针挂起 */
    private class FakeProvider(
        private val name: String,
        private val probeDelayMs: Long = 0,
        private val onClose: (String) -> Unit = {},
    ) : EmbeddingProvider {
        override var descriptor = EmbeddingDescriptor(providerId = "fake", modelId = name)
        var closed = false
            private set

        override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
            texts.map { FloatArray(4) }

        override suspend fun embedQuery(text: String): FloatArray {
            if (probeDelayMs > 0) delay(probeDelayMs)
            return FloatArray(4)
        }

        override fun close() {
            closed = true
            onClose(name)
        }
    }

    @Test
    fun `key 不变复用实例，key 变化关闭旧实例并重建`() = runTest {
        val cache = EngineCache()
        val closed = mutableListOf<String>()
        var builds = 0
        val build: suspend () -> EmbeddingProvider = {
            builds++
            FakeProvider("p$builds") { closed += it }
        }
        assertEquals("p1", cache.access("k1", build) { it.descriptor.modelId })
        assertEquals(1, builds)
        // 命中缓存：不重建
        assertEquals("p1", cache.access("k1", build) { it.descriptor.modelId })
        assertEquals(1, builds)
        // key 变化：旧实例关闭一次 + 重建
        assertEquals("p2", cache.access("k2", build) { it.descriptor.modelId })
        assertEquals(2, builds)
        assertEquals(listOf("p1"), closed)
        assertEquals("k2", cache.sharedKey)
    }

    @Test
    fun `重建失败时清空缓存且下一次调用自愈重试（P1-3 根因）`() = runTest {
        val cache = EngineCache()
        // 先成功建一次，再让重建失败（模拟切 GPU/换模型时构建抛 OOM）
        cache.access("k1", { FakeProvider("old") }) { it.descriptor.modelId }
        try {
            cache.access("k2", { error("gpu build failed") }) { it.descriptor.modelId }
            fail("expected build failure")
        } catch (e: IllegalStateException) {
            assertEquals("gpu build failed", e.message)
        }
        // 关键断言：失败后缓存必须为空——否则下次会复用"已关闭"的旧实例并永远报 provider already closed
        assertNull(cache.shared)
        assertNull(cache.sharedKey)
        // 自愈：下一次调用重新构建
        var builds = 0
        val recovered = cache.access("k2", {
            builds++
            FakeProvider("fresh")
        }) { it.descriptor.modelId }
        assertEquals("fresh", recovered)
        assertEquals(1, builds)
        assertEquals("k2", cache.sharedKey)
    }

    @Test
    fun `重建前先关闭旧实例且构建失败不残留引用`() = runTest {
        val cache = EngineCache()
        val closed = mutableListOf<String>()
        cache.access("k1", { FakeProvider("old") { closed += it } }) { }
        try {
            cache.access("k2", { throw IllegalArgumentException("constructor failed") }) { it.descriptor.modelId }
            fail("expected build failure")
        } catch (_: IllegalArgumentException) {
            // 构造阶段失败（模拟 createFromOptions 抛错）
        }
        assertEquals(listOf("old"), closed) // 旧实例已关闭
        assertNull(cache.shared)
        assertNull(cache.sharedKey)
    }

    @Test
    fun `shutdown 关闭实例并加锁重建（P1-3 锁语义）`() = runTest {
        val cache = EngineCache()
        var builds = 0
        val build: suspend () -> EmbeddingProvider = { builds++; FakeProvider("p$builds") }
        cache.access("k", build) { }
        assertEquals(1, builds)
        cache.shutdown()
        assertNull(cache.shared)
        assertNull(cache.sharedKey)
        // shutdown 后再次访问：重建而不是返回已关闭实例
        cache.access("k", build) { }
        assertEquals(2, builds)
    }

    @Test
    fun `block 异常不影响缓存中的健康实例`() = runTest {
        val cache = EngineCache()
        var builds = 0
        val build: suspend () -> EmbeddingProvider = { builds++; FakeProvider("p") }
        try {
            cache.access("k", build) { error("business failure") }
            fail("expected block failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, builds)
        // block 抛错后实例仍健康：命中缓存，不重建
        cache.access("k", build) { }
        assertEquals(1, builds)
        assertTrue(cache.shared != null)
    }

    @Test
    fun `GPU 试探成功返回实例且不关闭`() = runTest {
        val provider = FakeProvider("gpu")
        val result = probeGpuWithTimeout(
            timeoutMs = 1_000,
            build = { provider },
            probe = { it.embedQuery("probe") },
        )
        assertSame(provider, result)
        assertTrue(!provider.closed)
    }

    @Test
    fun `GPU 试探超时关闭已构建实例（P1-3 泄漏修复）`() = runTest {
        val provider = FakeProvider("gpu", probeDelayMs = 10_000)
        try {
            probeGpuWithTimeout(
                timeoutMs = 50, // runTest 虚拟时间：立即超时
                build = { provider },
                probe = { it.embedQuery("probe") },
            )
            fail("expected timeout")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        }
        // 超时后必须 close：否则被放弃的 GPU 实例与回退的 CPU 实例同时驻留（2× 模型内存）
        assertTrue("GPU 实例超时未关闭（泄漏）", provider.closed)
    }

    @Test
    fun `GPU 探针业务失败也关闭已构建实例`() = runTest {
        val provider = FakeProvider("gpu")
        try {
            probeGpuWithTimeout(
                timeoutMs = 1_000,
                build = { provider },
                probe = { error("probe crashed") },
            )
            fail("expected probe failure")
        } catch (e: IllegalStateException) {
            assertEquals("probe crashed", e.message)
        }
        assertTrue(provider.closed)
    }
}
