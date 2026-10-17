package com.zhibox.memory.core.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B7 审查修复（instrumentation）：旧持久化偏好值迁移。
 *
 * `PathPreferences.get()` 把旧版本可能持久化的 `"C"`（.task / Path C 已下线）以及任意非法值
 * 归一为 [PathPreferences.AUTO]（避免设置页"无 chip 选中"），合法值 AUTO/A/B/D 原样返回。
 *
 * 说明：get() 依赖 Android SharedPreferences，纯 JVM 无法构造；本用例直接使用应用真实
 * SharedPreferences（测试前后保存/恢复原值，不影响其它测试）。
 */
class PathPreferencesLegacyValueTest {

    @Test
    fun legacyOrInvalidPersistedPreference_fallsBackToAuto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = PathPreferences.get(context)
        try {
            // 旧版本可能持久化 "C"；以及任何非法值/空串 → 一律回退 AUTO
            for (bad in listOf("C", "c", "INVALID", "", "A ")) {
                PathPreferences.set(context, bad)
                assertEquals(
                    "旧/非法偏好值必须回退 AUTO：'$bad'",
                    PathPreferences.AUTO,
                    PathPreferences.get(context),
                )
            }
            // 合法值（含 B7 新增 D）原样保留
            for (good in listOf(PathPreferences.AUTO, PathPreferences.A, PathPreferences.B, PathPreferences.D)) {
                PathPreferences.set(context, good)
                assertEquals("合法偏好值应原样返回：$good", good, PathPreferences.get(context))
            }
            println("B7_PREFERENCE_MIGRATION legacy_C_and_invalid_fallback=auto valid=A/B/D")
        } finally {
            PathPreferences.set(context, original)
        }
    }
}
