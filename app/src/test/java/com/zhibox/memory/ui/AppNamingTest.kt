package com.zhibox.memory.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 应用名契约（产品命名决策，锁死避免回退）：
 * - 英文名 **ZhiBox**（默认资源，非中文/未知语言一律回落它）；
 * - 中文名 **智匣**（`values-zh`）。
 *
 * 为什么要有这个测试：应用名同时出现在桌面标签（Manifest `android:label="@string/app_name"`）
 * 与各语言资源里，改一处漏一处只会被用户看到、不会被编译发现；历史值「ZhiBox Memory」
 * 与设计稿里的「智序」都曾与产品定名不一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNamingTest {

    @Test
    fun defaultLocaleUsesEnglishBrandName() {
        assertEquals(
            "ZhiBox",
            RuntimeEnvironment.getApplication().getString(com.zhibox.memory.R.string.app_name),
        )
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseLocaleUsesChineseBrandName() {
        assertEquals(
            "智匣",
            RuntimeEnvironment.getApplication().getString(com.zhibox.memory.R.string.app_name),
        )
    }

    @Test
    fun homeBrandMarkUsesChineseBrandName() {
        // 首页品牌锁版 = 中文名 + ZHIBOX 字标（设计稿 lockup），与桌面应用名同源
        assertEquals(
            "智匣",
            RuntimeEnvironment.getApplication()
                .getString(com.zhibox.memory.R.string.home_brand_title),
        )
    }
}
