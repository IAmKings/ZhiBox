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

        /** 缓存版本命中灰度但当前设备不在投放比例内：只显示分批推送提示，不弹窗。 */
        data class RolloutPending(val info: UpdateInfo) : LaunchAction
        data object Check : LaunchAction
        data object Skip : LaunchAction
    }

    fun decideOnLaunch(
        nowMillis: Long,
        store: UpdateStore,
        currentVersionName: String,
    ): LaunchAction {
        val cached = store.cachedInfo()
        if (cached != null && isNewerVersion(cached.versionName, currentVersionName)) {
            // 强制更新优先级最高：即使用户取消过该版本、或关闭了自动检查，
            // 冷启动也应展示（不联网、纯缓存），且由 UI 层禁止取消。
            if (UpdateRollout.isForced(cached, currentVersionName)) {
                // 自动检查关闭：强制缓存只展示、不联网（隐私开关优先）。
                if (!store.autoCheckEnabled) return LaunchAction.ShowCached(cached)
                val age = if (store.cachedAtMillis > 0L) {
                    nowMillis - store.cachedAtMillis
                } else {
                    Long.MAX_VALUE
                }
                return if (age <= UpdateConfig.FORCED_CACHE_MAX_AGE_MS) {
                    LaunchAction.ShowCached(cached)
                } else {
                    // 强制缓存过期：先联网校验，失败时 manager 回退缓存（避免 Release 已删除仍被强制）。
                    LaunchAction.Check
                }
            }
            // 用户关闭自动检查后：冷启动完全不联网，也不展示非强制缓存（手动检查始终可用）。
            if (!store.autoCheckEnabled) return LaunchAction.Skip
            // 旧版本缓存没有灰度/强制元数据，不能用于分桶判断：强制联网刷新一次。
            if (!store.cachedMetaKnown) return LaunchAction.Check
            // 普通/灰度缓存过期后自动重新校验；受失败退避约束，避免每次启动都请求。
            val age = if (store.cachedAtMillis > 0L) {
                nowMillis - store.cachedAtMillis
            } else {
                Long.MAX_VALUE
            }
            val stale = age >= UpdateConfig.CACHE_REVALIDATE_INTERVAL_MS
            if (stale &&
                nowMillis - store.lastAttemptAtMillis >= UpdateConfig.AUTO_RETRY_INTERVAL_MS
            ) {
                return LaunchAction.Check
            }
            // 冷启动同样必须遵守灰度分桶：未命中只显示分批推送，不能弹更新框。
            if (!UpdateRollout.isEligible(store.installId, cached)) {
                return LaunchAction.RolloutPending(cached)
            }
            if (cached.versionName != store.dismissedVersion) {
                return LaunchAction.ShowCached(cached)
            }
        } else if (!store.autoCheckEnabled) {
            return LaunchAction.Skip
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
