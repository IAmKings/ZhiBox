package com.zhibox.memory.core.sag

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B12 预算档位（PRD §7.2）与本地偏好：
 * - HIGH/MID/LOW 六项预算与 PRD 表格逐项一致（表格是唯一数值来源）；
 * - wire 稳定/往返、未知 wire 回退 HIGH（默认 HIGH）；
 * - [SagBudgetPreferences] 默认 HIGH、set/get 往返、只存 wire 字符串（不存数值）；
 * - 存量未知/空字符串回退 HIGH。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SagBudgetTierTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("sag_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    // ---- PRD §7.2 精确值 ----

    @Test
    fun highTierMatchesPrdBudgetTable() {
        val tier = SagBudgetTier.HIGH
        assertEquals("high", tier.wire)
        assertEquals(
            SagBudget(kPool = 500, kSeed = 50, frontier = 50, kCand = 100, kEvent = 5, kOut = 10),
            tier.budget,
        )
        assertEquals(500, tier.budget.kPool)
        assertEquals(50, tier.budget.kSeed)
        assertEquals(50, tier.budget.frontier)
        assertEquals(100, tier.budget.kCand)
        assertEquals(5, tier.budget.kEvent)
        assertEquals(10, tier.budget.kOut)
    }

    @Test
    fun midTierMatchesPrdBudgetTable() {
        val tier = SagBudgetTier.MID
        assertEquals("mid", tier.wire)
        assertEquals(
            SagBudget(kPool = 300, kSeed = 30, frontier = 30, kCand = 60, kEvent = 4, kOut = 10),
            tier.budget,
        )
    }

    @Test
    fun lowTierMatchesPrdBudgetTable() {
        val tier = SagBudgetTier.LOW
        assertEquals("low", tier.wire)
        assertEquals(
            SagBudget(kPool = 150, kSeed = 20, frontier = 20, kCand = 40, kEvent = 3, kOut = 10),
            tier.budget,
        )
    }

    @Test
    fun tiersAreMonotonicAndKeepOutputConstant() {
        assertEquals(listOf("high", "mid", "low"), SagBudgetTier.entries.map { it.wire })
        assertEquals(3, SagBudgetTier.entries.size)
        val high = SagBudgetTier.HIGH.budget
        val mid = SagBudgetTier.MID.budget
        val low = SagBudgetTier.LOW.budget
        assertTrue("kPool 随档位递减", high.kPool > mid.kPool && mid.kPool > low.kPool)
        assertTrue(high.kSeed > mid.kSeed && mid.kSeed > low.kSeed)
        assertTrue(high.frontier > mid.frontier && mid.frontier > low.frontier)
        assertTrue(high.kCand > mid.kCand && mid.kCand > low.kCand)
        assertTrue(high.kEvent > mid.kEvent && mid.kEvent > low.kEvent)
        assertEquals("输出上限 kOut 三档一致", 10, high.kOut)
        assertEquals(high.kOut, mid.kOut)
        assertEquals(mid.kOut, low.kOut)
    }

    // ---- wire 契约 ----

    @Test
    fun wireRoundTripAndUnknownFallsBackToHigh() {
        SagBudgetTier.entries.forEach { tier ->
            assertEquals(tier, SagBudgetTier.fromWire(tier.wire))
        }
        assertEquals("mid", SagBudgetTier.fromWire("mid").wire)
        assertEquals("low", SagBudgetTier.fromWire("low").wire)
        // 未知/空/大小写不符 → 默认 HIGH
        assertEquals(SagBudgetTier.HIGH, SagBudgetTier.fromWire(null))
        assertEquals(SagBudgetTier.HIGH, SagBudgetTier.fromWire(""))
        assertEquals(SagBudgetTier.HIGH, SagBudgetTier.fromWire("unknown"))
        assertEquals(SagBudgetTier.HIGH, SagBudgetTier.fromWire("HIGH"))
        assertEquals(SagBudgetTier.HIGH, SagBudgetTier.fromWire(" mid "))
    }

    // ---- 偏好 ----

    @Test
    fun defaultTierIsHighWhenUnset() {
        assertEquals(SagBudgetTier.HIGH, SagBudgetPreferences.tier(app))
    }

    @Test
    fun setTierRoundTrip() {
        SagBudgetPreferences.setTier(app, SagBudgetTier.MID)
        assertEquals(SagBudgetTier.MID, SagBudgetPreferences.tier(app))
        SagBudgetPreferences.setTier(app, SagBudgetTier.LOW)
        assertEquals(SagBudgetTier.LOW, SagBudgetPreferences.tier(app))
        SagBudgetPreferences.setTier(app, SagBudgetTier.HIGH)
        assertEquals(SagBudgetTier.HIGH, SagBudgetPreferences.tier(app))
    }

    @Test
    fun preferencesStoreOnlyWireStringNotBudgetNumbers() {
        SagBudgetPreferences.setTier(app, SagBudgetTier.MID)
        val all = prefs.all
        assertEquals("只存一个 key", 1, all.size)
        val entry = all.entries.single()
        assertEquals("budget_tier", entry.key)
        assertEquals("只存 wire 字符串", "mid", entry.value)
        assertTrue("不得存预算数值", entry.value is String)
    }

    @Test
    fun unknownOrBlankStoredWireFallsBackToHigh() {
        prefs.edit().putString("budget_tier", "ultra").commit()
        assertEquals(SagBudgetTier.HIGH, SagBudgetPreferences.tier(app))

        prefs.edit().putString("budget_tier", "  ").commit()
        assertEquals(SagBudgetTier.HIGH, SagBudgetPreferences.tier(app))

        prefs.edit().putString("budget_tier", "LOW").commit()
        assertEquals("大小写不匹配也回退 HIGH", SagBudgetTier.HIGH, SagBudgetPreferences.tier(app))
    }
}
