package com.zhibox.memory.core.sag

import android.content.Context

/**
 * B12：SAG 预算档位本地偏好（UI 设置；默认 HIGH）。
 *
 * 只存档位字符串，不存预算数值；读取时映射到 [SagBudgetTier.budget]，
 * 保证 PRD §7.2 表格是唯一数值来源。
 */
object SagBudgetPreferences {
    private const val PREFS = "sag_prefs"
    private const val KEY_TIER = "budget_tier"

    fun tier(context: Context): SagBudgetTier {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SagBudgetTier.fromWire(prefs.getString(KEY_TIER, null))
    }

    fun setTier(context: Context, tier: SagBudgetTier) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TIER, tier.wire)
            .commit()
    }
}
