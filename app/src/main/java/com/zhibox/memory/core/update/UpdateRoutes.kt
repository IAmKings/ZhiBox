package com.zhibox.memory.core.update

/**
 * 下载线路健康（本地统计，不上报服务器）。
 *
 * 线路 = `direct`（GitHub 资产直连）或镜像 host。真实下载结果驱动：
 * - 连续 2 次传输失败（Network/Timeout）→ 冷却 6h，期间优先其它线路；
 * - 成功 → 清零失败计数并记录延迟。
 */
data class RouteHealth(
    val id: String,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastLatencyMs: Long = 0L,
    val cooldownUntil: Long = 0L,
) {
    val isCoolingDown: Boolean get() = cooldownUntil > 0L
}

/** 线路选择纯函数（JVM 单测覆盖）。 */
object UpdateRouteSelector {

    const val DIRECT_ID = "direct"
    const val FAILURE_THRESHOLD = 2
    const val COOLDOWN_MS = 6 * 60 * 60 * 1_000L

    /**
     * 返回尝试顺序：
     * 1. 直连未冷却 → 直连优先；直连冷却中 → 镜像优先；
     * 2. 镜像按「未冷却优先 → 连续失败升序 → 最近延迟升序 → 配置顺序」；
     * 3. 全部冷却时仍返回全部线路（按上述排序），不永久禁用。
     */
    fun plan(
        nowMillis: Long,
        mirrorIds: List<String>,
        health: Map<String, RouteHealth>,
        directId: String = DIRECT_ID,
    ): List<String> {
        val mirrors = mirrorIds.distinct().mapIndexed { index, id -> index to id }
        val sortedMirrors = mirrors.sortedWith { left, right ->
            val l = health[left.second]
            val r = health[right.second]
            val leftCooling = (l?.cooldownUntil ?: 0L) > nowMillis
            val rightCooling = (r?.cooldownUntil ?: 0L) > nowMillis
            when {
                leftCooling != rightCooling -> if (leftCooling) 1 else -1
                (l?.consecutiveFailures ?: 0) != (r?.consecutiveFailures ?: 0) ->
                    (l?.consecutiveFailures ?: 0).compareTo(r?.consecutiveFailures ?: 0)

                (l?.lastLatencyMs ?: Long.MAX_VALUE) != (r?.lastLatencyMs ?: Long.MAX_VALUE) ->
                    (l?.lastLatencyMs ?: Long.MAX_VALUE).compareTo(r?.lastLatencyMs ?: Long.MAX_VALUE)

                else -> left.first.compareTo(right.first)
            }
        }.map { it.second }

        val directCooling = (health[directId]?.cooldownUntil ?: 0L) > nowMillis
        return if (directCooling) sortedMirrors + directId else listOf(directId) + sortedMirrors
    }

    /** 根据一次尝试结果更新线路健康。 */
    fun record(
        nowMillis: Long,
        previous: RouteHealth?,
        success: Boolean,
        latencyMs: Long,
        id: String,
    ): RouteHealth {
        val base = previous ?: RouteHealth(id = id)
        return if (success) {
            base.copy(
                lastSuccessAt = nowMillis,
                consecutiveFailures = 0,
                cooldownUntil = 0L,
                lastLatencyMs = latencyMs,
            )
        } else {
            val failures = base.consecutiveFailures + 1
            base.copy(
                lastFailureAt = nowMillis,
                consecutiveFailures = failures,
                cooldownUntil = if (failures >= FAILURE_THRESHOLD) {
                    nowMillis + COOLDOWN_MS
                } else {
                    base.cooldownUntil
                },
            )
        }
    }
}
