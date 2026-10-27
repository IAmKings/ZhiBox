package com.zhibox.memory.core.update

/**
 * 冷启动自动检查的纯决策逻辑（无 Android 依赖，JVM 单测覆盖）。
 *
 * 优先级：
 * 1. 已有缓存且比当前版本新、且未被用户对该版本「取消」→ 直接弹缓存（不发网络）；
 * 2. 距上次尝试 < 1h → 跳过（失败退避）；
 * 3. 距上次成功 < 24h → 跳过（成功节流）；
 * 4. 否则发起检查。
 *
 * 手动检查（设置里的「检查更新」）不走本策略：始终取消旧任务并强制重查。
 */
object UpdatePolicy {

    sealed interface LaunchAction {
        data class ShowCached(val info: UpdateInfo) : LaunchAction
        data object Check : LaunchAction
        data object Skip : LaunchAction
    }

    fun decideOnLaunch(
        nowMillis: Long,
        store: UpdateStore,
        currentVersionName: String,
    ): LaunchAction {
        // 用户关闭自动检查后：冷启动完全不联网，也不展示缓存（手动检查始终可用）。
        if (!store.autoCheckEnabled) return LaunchAction.Skip
        val cached = store.cachedInfo()
        if (cached != null &&
            isNewerVersion(cached.versionName, currentVersionName) &&
            cached.versionName != store.dismissedVersion
        ) {
            return LaunchAction.ShowCached(cached)
        }
        if (nowMillis - store.lastAttemptAtMillis < UpdateConfig.AUTO_RETRY_INTERVAL_MS) {
            return LaunchAction.Skip
        }
        if (nowMillis - store.lastSuccessAtMillis < UpdateConfig.AUTO_CHECK_INTERVAL_MS) {
            return LaunchAction.Skip
        }
        return LaunchAction.Check
    }

    /** 自动检查成功后是否要自动弹窗（用户取消过的版本不再自动打扰）。 */
    fun shouldAutoPrompt(
        info: UpdateInfo,
        currentVersionName: String,
        dismissedVersion: String?,
    ): Boolean = isNewerVersion(info.versionName, currentVersionName) &&
        info.versionName != dismissedVersion
}
