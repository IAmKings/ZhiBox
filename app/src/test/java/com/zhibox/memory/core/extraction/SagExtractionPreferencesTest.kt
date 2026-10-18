package com.zhibox.memory.core.extraction

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
 * B12 抽取并发度偏好（JVM Robolectric，真实 SharedPreferences）：
 * - 默认 1（串行）；常量 1..4；
 * - set/get 1/2/3/4 往返；写入返回值为夹紧后的实际值；
 * - clamp：0/-1 → 1，5/99 → 4；read 时对存量非法值同样夹紧；
 * - prefs 只存一个 int key（`sag_extraction_prefs` / `parallelism`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SagExtractionPreferencesTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("sag_extraction_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultsAndConstants() {
        assertEquals(1, SagExtractionPreferences.parallelism(app))
        assertEquals(1, SagExtractionPreferences.DEFAULT_PARALLELISM)
        assertEquals(1, SagExtractionPreferences.MIN_PARALLELISM)
        assertEquals(4, SagExtractionPreferences.MAX_PARALLELISM)
        assertTrue(
            "默认值应在合法区间",
            SagExtractionPreferences.DEFAULT_PARALLELISM in
                SagExtractionPreferences.MIN_PARALLELISM..SagExtractionPreferences.MAX_PARALLELISM,
        )
    }

    @Test
    fun setGetRoundTripForAllValidValues() {
        for (value in 1..4) {
            val saved = SagExtractionPreferences.setParallelism(app, value)
            assertEquals("写入返回值应为夹紧后的实际值", value, saved)
            assertEquals(value, SagExtractionPreferences.parallelism(app))
        }
    }

    @Test
    fun setClampsBelowMinToOne() {
        assertEquals(1, SagExtractionPreferences.setParallelism(app, 0))
        assertEquals(1, SagExtractionPreferences.parallelism(app))
        assertEquals(1, SagExtractionPreferences.setParallelism(app, -1))
        assertEquals(1, SagExtractionPreferences.parallelism(app))
        assertEquals(1, SagExtractionPreferences.setParallelism(app, Int.MIN_VALUE))
        assertEquals(1, SagExtractionPreferences.parallelism(app))
    }

    @Test
    fun setClampsAboveMaxToFour() {
        assertEquals(4, SagExtractionPreferences.setParallelism(app, 5))
        assertEquals(4, SagExtractionPreferences.parallelism(app))
        assertEquals(4, SagExtractionPreferences.setParallelism(app, 99))
        assertEquals(4, SagExtractionPreferences.parallelism(app))
        assertEquals(4, SagExtractionPreferences.setParallelism(app, Int.MAX_VALUE))
        assertEquals(4, SagExtractionPreferences.parallelism(app))
    }

    @Test
    fun storedOutOfRangeValueIsCoercedOnRead() {
        // 模拟旧版本/外部写入的非法存量值：读取时应夹紧，不崩溃。
        prefs.edit().putInt("parallelism", 0).commit()
        assertEquals(1, SagExtractionPreferences.parallelism(app))
        prefs.edit().putInt("parallelism", 99).commit()
        assertEquals(4, SagExtractionPreferences.parallelism(app))
        prefs.edit().putInt("parallelism", -7).commit()
        assertEquals(1, SagExtractionPreferences.parallelism(app))
    }

    @Test
    fun preferencesStoreOnlyOneIntKey() {
        SagExtractionPreferences.setParallelism(app, 3)
        val all = prefs.all
        assertEquals("只存一个 key", 1, all.size)
        val entry = all.entries.single()
        assertEquals("parallelism", entry.key)
        assertEquals(3, entry.value)
        assertTrue("只存 int", entry.value is Int)
    }

    @Test
    fun repeatedSetKeepsLastValueAndRange() {
        listOf(2, 4, 1, 3).forEach { SagExtractionPreferences.setParallelism(app, it) }
        assertEquals(3, SagExtractionPreferences.parallelism(app))
        assertEquals(3, prefs.getInt("parallelism", 1))
    }
}
