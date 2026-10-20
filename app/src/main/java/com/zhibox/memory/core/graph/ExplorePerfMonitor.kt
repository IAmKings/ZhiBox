package com.zhibox.memory.core.graph

/** P1.3c：单次 Explore load 的结果分类（仅结果状态，不含 query/正文）。 */
enum class ExploreLoadResult {
    /** 当前页有节点。 */
    OK,

    /** 空页（无命中/offset 越界）。 */
    EMPTY,
}

/**
 * P1.3c：一次 Explore load 的脱敏性能样本（无 query 正文/节点正文/错误文本）。
 *
 * @param offset 本次请求分页 offset
 * @param pageSize 当前页事件节点数
 * @param result OK/EMPTY
 * @param total totalEvents（全量匹配/有界候选内总数）
 * @param durationMs load 端到端耗时
 * @param usedFts 是否走了 FTS 通道（query 非空且有 bigram）
 * @param usedLike 是否走了 LIKE 通道（query 非空、或 FTS union 时合并）
 * @param truncated 是否被上限截断
 */
data class ExploreLoadSample(
    val offset: Int,
    val pageSize: Int,
    val result: ExploreLoadResult,
    val total: Int,
    val durationMs: Long,
    val usedFts: Boolean,
    val usedLike: Boolean,
    val truncated: Boolean,
)

/** P1.3c：最近 [ExplorePerfMonitor.CAPACITY] 次 load 的统计（空时 p50/p95/max=0、last=null）。 */
data class ExplorePerfStats(
    val count: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val last: ExploreLoadSample?,
)

/**
 * P1.3c：Explore load 性能监控（进程内环形最近 64 次，线程安全）。
 *
 * 只保存 [ExploreLoadSample] 数值/布尔/枚举，**不保存 query 文本、节点 label/正文、错误详情**；
 * Repository 每次成功 load（含空结果）自动记录，失败/取消不记录。
 * UI 性能卡读取 [stats]（p50/p95/max/last）或 [snapshot]（最近样本列表）。
 */
object ExplorePerfMonitor {

    /** 环形缓冲容量（最近 64 次 load）。 */
    const val CAPACITY: Int = 64

    private val lock = Any()
    private val samples = ArrayDeque<ExploreLoadSample>()

    /** 记录一次成功 load；负值防御性夹紧。 */
    fun record(sample: ExploreLoadSample) {
        val normalized = sample.copy(
            offset = sample.offset.coerceAtLeast(0),
            pageSize = sample.pageSize.coerceAtLeast(0),
            total = sample.total.coerceAtLeast(0),
            durationMs = sample.durationMs.coerceAtLeast(0),
        )
        synchronized(lock) {
            samples.addLast(normalized)
            while (samples.size > CAPACITY) samples.removeFirst()
        }
    }

    /** 最近样本（旧→新副本；最多 CAPACITY 条）。 */
    fun snapshot(): List<ExploreLoadSample> = synchronized(lock) { samples.toList() }

    /** 统计：count/p50/p95/max/last（p50/p95 取 sorted[count*p/100]，越界取末尾）。 */
    fun stats(): ExplorePerfStats = synchronized(lock) {
        val recent = samples.toList()
        if (recent.isEmpty()) return@synchronized ExplorePerfStats(0, 0, 0, 0, null)
        val sorted = recent.map { it.durationMs }.sorted()
        fun percentile(p: Int): Long = sorted[(sorted.size * p / 100).coerceAtMost(sorted.size - 1)]
        ExplorePerfStats(
            count = recent.size,
            p50Ms = percentile(50),
            p95Ms = percentile(95),
            maxMs = sorted.last(),
            last = recent.last(),
        )
    }

    /** 清空全部样本（测试/设置页重置）。 */
    fun reset() {
        synchronized(lock) { samples.clear() }
    }
}
