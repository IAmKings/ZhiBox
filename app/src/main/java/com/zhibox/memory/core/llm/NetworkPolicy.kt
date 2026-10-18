package com.zhibox.memory.core.llm

import android.content.Context

/**
 * 网络访问策略（B8）：远程 LLM 调用前的唯一"能否联网"判断点。
 *
 * PRD FR-08 要求"禁网必须能测试零请求"：Provider 在发请求前先问 [isAllowed]，
 * 不允许时直接返回 [LlmErrorCode.OFFLINE]，不会创建连接。
 * 默认策略读取 [LlmPreferences.networkAllowed]（默认 false = 禁网），且**每次调用实时读取**，
 * 避免缓存导致用户刚打开开关却仍被拦截。
 */
fun interface NetworkPolicy {
    /** @return true 表示允许发起网络请求。 */
    fun isAllowed(): Boolean

    companion object {
        /** 测试用：始终允许。 */
        fun alwaysAllowed(): NetworkPolicy = NetworkPolicy { true }

        /** 测试/离线模式用：始终禁止（零请求）。 */
        fun alwaysBlocked(): NetworkPolicy = NetworkPolicy { false }

        /** 生产策略：实时读取设置中的"允许联网"开关（默认禁止）。 */
        fun from(context: Context): NetworkPolicy {
            val appContext = context.applicationContext
            return NetworkPolicy { LlmPreferences.networkAllowed(appContext) }
        }
    }
}
