package com.zhibox.memory.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载线路健康与选择策略纯 JVM 测试。 */
class UpdateRoutesTest {

    private val now = 1_700_000_000_000L

    @Test
    fun directFirstWhenHealthy() {
        val order = UpdateRouteSelector.plan(
            nowMillis = now,
            mirrorIds = listOf("gh-proxy.com", "ghfast.top"),
            health = emptyMap(),
        )
        assertEquals(listOf("direct", "gh-proxy.com", "ghfast.top"), order)
    }

    @Test
    fun directCoolingMovesToEnd() {
        val health = mapOf(
            "direct" to RouteHealth(
                id = "direct",
                consecutiveFailures = 2,
                cooldownUntil = now + 1_000,
            ),
        )
        val order = UpdateRouteSelector.plan(
            nowMillis = now,
            mirrorIds = listOf("gh-proxy.com"),
            health = health,
        )
        assertEquals(listOf("gh-proxy.com", "direct"), order)
    }

    @Test
    fun mirrorsSortedByFailuresThenLatency() {
        val health = mapOf(
            "a.example" to RouteHealth(id = "a.example", consecutiveFailures = 1, lastLatencyMs = 100),
            "b.example" to RouteHealth(id = "b.example", consecutiveFailures = 0, lastLatencyMs = 900),
            "c.example" to RouteHealth(id = "c.example", consecutiveFailures = 0, lastLatencyMs = 200),
        )
        val order = UpdateRouteSelector.plan(
            nowMillis = now,
            mirrorIds = listOf("a.example", "b.example", "c.example"),
            health = health,
        )
        assertEquals(listOf("direct", "c.example", "b.example", "a.example"), order)
    }

    @Test
    fun failureCooldownAfterThreshold() {
        var route = UpdateRouteSelector.record(now, null, success = false, latencyMs = 0, id = "direct")
        assertEquals(1, route.consecutiveFailures)
        assertTrue(route.cooldownUntil <= now)

        route = UpdateRouteSelector.record(now, route, success = false, latencyMs = 0, id = "direct")
        assertEquals(2, route.consecutiveFailures)
        assertTrue(route.cooldownUntil > now)
    }

    @Test
    fun successResetsHealth() {
        val failed = RouteHealth(
            id = "direct",
            consecutiveFailures = 2,
            cooldownUntil = now + 10_000,
            lastFailureAt = now - 1_000,
        )
        val route = UpdateRouteSelector.record(now + 2_000, failed, success = true, latencyMs = 123, id = "direct")
        assertEquals(0, route.consecutiveFailures)
        assertEquals(0L, route.cooldownUntil)
        assertEquals(123L, route.lastLatencyMs)
        assertFalse(route.cooldownUntil > now)
    }

    @Test
    fun allCoolingStillReturnsAllRoutes() {
        val health = mapOf(
            "direct" to RouteHealth(id = "direct", cooldownUntil = now + 100_000),
            "m.example" to RouteHealth(id = "m.example", cooldownUntil = now + 100_000),
        )
        val order = UpdateRouteSelector.plan(
            nowMillis = now,
            mirrorIds = listOf("m.example"),
            health = health,
        )
        assertEquals(setOf("direct", "m.example"), order.toSet())
    }
}
