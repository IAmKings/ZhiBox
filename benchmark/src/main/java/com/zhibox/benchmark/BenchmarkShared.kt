// Macrobenchmark 公共常量与导航动作（SettingsBenchmark 复用）。
//
// 依赖 UI 文案而非 resource id：Compose 节点无稳定 view id，UiAutomator 通过
// text / contentDescription 定位。文案来源：
//   · 首页右上「更多」按钮 contentDescription = R.string.home_action_more = "更多设置"
//   · 更多菜单项 = R.string.home_more_settings = "设置"
//   · 设置页标题 = R.string.settings_title = "设置"
// 若 UI 文案改动，这两处（res/values/strings.xml 与这里）需同步。

package com.zhibox.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** 被测应用包名（app/build.gradle.kts applicationId）。 */
internal const val TARGET_PACKAGE: String = "com.zhibox.memory"

/** 首页「更多」按钮的 contentDescription（= 更多设置）。 */
internal const val HOME_MORE_DESC: String = "更多设置"

/** 「更多设置」菜单项与设置页标题的文案（同名）。 */
internal const val SETTINGS_TEXT: String = "设置"

/**
 * 从首页进入设置页（不含测量窗口，供 setupBlock 使用）：
 *   1. 等首页顶栏「更多设置」出现（冷启动后 Compose 首帧可能稍晚）；
 *   2. 点击打开下拉菜单；
 *   3. 点菜单里的「设置」→ 进入设置页；
 *   4. 等首页入口消失（页面已切换，避免菜单同名文案误判）+ 等设置页标题「设置」出现。
 */
internal fun openSettingsFromHome(device: UiDevice) {
    val more = device.wait(Until.findObject(By.desc(HOME_MORE_DESC)), 10_000)
        ?: error("未找到首页「$HOME_MORE_DESC」入口（contentDescription 变更？）")
    more.click()
    val settingsItem = device.wait(Until.findObject(By.text(SETTINGS_TEXT)), 10_000)
        ?: error("「更多设置」菜单中未找到「$SETTINGS_TEXT」菜单项")
    settingsItem.click()
    // 首页入口消失 = 已离开首页；随后等设置页标题渲染（两者文案相同，先 gone 再 has 最稳）。
    device.wait(Until.gone(By.desc(HOME_MORE_DESC)), 3_000)
    if (!device.wait(Until.hasObject(By.text(SETTINGS_TEXT)), 3_000)) {
        error("设置页标题「$SETTINGS_TEXT」3s 内未出现")
    }
    // 首次进入会触发 SettingsViewModel.onScreenStarted() 刷新，等列表稳定再开始测量。
    device.waitForIdle(2_000)
}

/** 设置简单首页 →「高级与诊断」入口文案（与 strings.xml settings_page_advanced 同步）。 */
internal const val ADVANCED_SETTINGS_TEXT: String = "高级与诊断"

/** 高级页顶部固定行文案（用于确认页面已切换，不与首页 4 卡混淆）。 */
internal const val TECHNICAL_DETAILS_TEXT: String = "显示技术细节"

/**
 * 从设置简单首页进入「高级与诊断」页（设置页简化为二级页后新增，供滚动基准使用）。
 *
 * 简单首页只有 5 个 item、基本一屏放得下，继续在首页上做 swipe 测不到列表性能；
 * 基准必须在原 17 项所在的「高级与诊断」页上采集。
 */
internal fun openAdvancedSettingsFromSettings(device: UiDevice) {
    val advanced = device.wait(Until.findObject(By.text(ADVANCED_SETTINGS_TEXT)), 5_000)
        ?: error("设置首页未找到「$ADVANCED_SETTINGS_TEXT」入口（文案变更？）")
    advanced.click()
    if (!device.wait(Until.hasObject(By.text(TECHNICAL_DETAILS_TEXT)), 5_000)) {
        error("「$ADVANCED_SETTINGS_TEXT」页 5s 内未出现「$TECHNICAL_DETAILS_TEXT」")
    }
    device.waitForIdle(1_000)
}
