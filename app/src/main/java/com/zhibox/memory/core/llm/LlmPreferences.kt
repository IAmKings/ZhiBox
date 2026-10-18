package com.zhibox.memory.core.llm

import android.content.Context
import java.util.UUID

/**
 * LLM/SAG 功能开关（B8）：SAG 增强、带引用问答、以及**默认禁网**的联网开关。
 *
 * 存储：SharedPreferences（非敏感配置；API Key 由 [KeystoreLlmConfigStore] 单独加密存储）。
 * 隐私默认值（PRD FR-08）：
 * - SAG 增强：[DEFAULT_SAG_ENABLED] = false（未配置 LLM 时保持离线检索可用）；
 * - 带引用问答：[DEFAULT_ANSWER_ENABLED] = false；
 * - 允许联网：[DEFAULT_NETWORK_ALLOWED] = **false（默认禁网，零请求）**，必须用户显式打开。
 *
 * 本对象与项目既有 [com.zhibox.memory.core.model.PathPreferences] 风格一致：object + context 传参。
 */
object LlmPreferences {
    private const val PREFS_NAME = "llm_preferences"
    private const val KEY_SAG_ENABLED = "sag_enabled"
    private const val KEY_ANSWER_ENABLED = "answer_enabled"
    private const val KEY_NETWORK_ALLOWED = "network_allowed"
    private const val KEY_OPENCODE_SESSION_ID = "opencode_session_id"

    /** SAG 增强开关默认值：关闭（未配置 LLM 时不得影响离线检索）。 */
    const val DEFAULT_SAG_ENABLED: Boolean = false

    /** 带引用问答开关默认值：关闭。 */
    const val DEFAULT_ANSWER_ENABLED: Boolean = false

    /** 允许联网开关默认值：false = 默认禁网（PRD 隐私默认，零请求）。 */
    const val DEFAULT_NETWORK_ALLOWED: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sagEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAG_ENABLED, DEFAULT_SAG_ENABLED)

    fun setSagEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAG_ENABLED, enabled).apply()
    }

    fun answerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANSWER_ENABLED, DEFAULT_ANSWER_ENABLED)

    fun setAnswerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANSWER_ENABLED, enabled).apply()
    }

    /**
     * 是否允许联网（默认 false = 禁网）。
     * 注意语义是"允许"而非"禁止"：UI 的"禁网开关"= !networkAllowed；
     * [NetworkPolicy.from] 每次都实时读取本值，保证开关即时生效。
     */
    fun networkAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NETWORK_ALLOWED, DEFAULT_NETWORK_ALLOWED)

    fun setNetworkAllowed(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_NETWORK_ALLOWED, allowed).apply()
    }

    /**
     * OpenCode Go 必需：稳定会话 ID（同一安装固定，避免每次请求换 ID 破坏路由/缓存亲和）。
     * 非敏感随机 UUID；只由 [com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider] 注入到
     * 官方 opencode.ai 网关请求头，不写日志、不进备份。
     */
    fun openCodeSessionId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_OPENCODE_SESSION_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        p.edit().putString(KEY_OPENCODE_SESSION_ID, generated).apply()
        return generated
    }
}
