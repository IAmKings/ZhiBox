package com.zhibox.memory.core.embedding

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 进程级引擎缓存与 GPU 试探（P1-3）——纯 JVM、无 Android 依赖，供 [EmbeddingRuntime]
 * 生产使用与 `core/embedding` 单测注入 fake provider 直接覆盖。
 *
 * 设计要点：
 * - 唯一实例 + 协程 [Mutex] 串行（引擎非线程安全；1.4GB 模型只允许一份）；
 * - **重建失败可自愈**：重建前先清 `shared/sharedKey` 再关闭旧实例；构建失败保持空，
 *   下一次调用会重新构建，而不是永远复用已关闭实例（P1-3 根因）；
 * - [shutdown] 与 [access] 同锁：消除 benchmark/UI 并发时的 use-after-close。
 */
internal class EngineCache {

    @Volatile
    var shared: EmbeddingProvider? = null
        private set

    @Volatile
    var sharedKey: String? = null
        private set

    private val mutex = Mutex()

    /**
     * 串行访问当前 key 对应的 Provider：
     * - key 未变且实例存活 → 直接执行 [block]（缓存命中）；
     * - key 变化/尚未构建 → 关闭旧实例后重建；
     * - 重建抛异常 → 缓存保持空并向上抛出，**下一次调用会重试构建**（自愈）。
     *
     * [block] 内不得再嵌套调用本方法（Mutex 非重入）。
     */
    suspend fun <T> access(
        key: String,
        build: suspend () -> EmbeddingProvider,
        block: suspend (EmbeddingProvider) -> T,
    ): T = mutex.withLock {
        if (shared == null || sharedKey != key) {
            val old = shared
            // 先清引用再关闭：close() 抛错或随后 build() 抛错时都不会把已关闭实例留在缓存里
            shared = null
            sharedKey = null
            runCatching { old?.close() }
            val fresh = build()
            shared = fresh
            sharedKey = key
        }
        block(shared!!)
    }

    /** 关闭并清空缓存；与 [access] 互斥（suspend，替代原先无锁的同步 shutdown） */
    suspend fun shutdown() = mutex.withLock {
        val old = shared
        shared = null
        sharedKey = null
        runCatching { old?.close() }
    }
}

/**
 * auto 模式 GPU 试探（P1-3 泄漏修复）：限时构建 + 探针首推理。
 *
 * 为什么要显式关闭：`build()` 是阻塞 JNI（模型加载/委托编译，秒级且不响应协程取消）。
 * [withTimeout] 超时后协程被取消，但阻塞调用仍会跑完并产出 provider 实例；若只依赖协程取消，
 * 被放弃的 GPU 引擎既未赋值也未 close，会与随后回退的 CPU 引擎同时驻留（峰值 ≈ 2× 模型内存）。
 *
 * @param build 构建 provider（阻塞）；成功才可能产生需要清理的实例
 * @param probe 首推理探针（可挂起，用于暴露"建引擎成功但推理崩"的延迟失败）
 */
internal suspend fun probeGpuWithTimeout(
    timeoutMs: Long,
    build: () -> EmbeddingProvider,
    probe: suspend (EmbeddingProvider) -> Unit,
): EmbeddingProvider {
    var candidate: EmbeddingProvider? = null
    return try {
        withTimeout(timeoutMs) {
            val provider = build()
            candidate = provider // 超时可能发生在后续 suspend 点：先登记待清理引用
            probe(provider)
            provider
        }
    } catch (t: Throwable) {
        runCatching { candidate?.close() }
        throw t
    }
}
