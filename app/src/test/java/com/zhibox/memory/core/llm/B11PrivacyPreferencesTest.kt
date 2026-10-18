package com.zhibox.memory.core.llm

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B11 隐私偏好（JVM Robolectric，真实 SharedPreferences）：
 * - `answerEnabled` / `answerNetworkConsent` 默认 false（隐私优先，未同意前不得发起问答）；
 * - 两个开关 set/get 往返；
 * - 切换 SAG/联网开关不会污染问答开关（反之亦然）；
 * - prefs 只存布尔等非敏感值，不含 API Key / 正文（通过全部 values 类型断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class B11PrivacyPreferencesTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("llm_preferences", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    // ---- 默认值 ----

    @Test
    fun privacyDefaultsAreOff() {
        assertFalse("问答开关默认关闭", LlmPreferences.answerEnabled(app))
        assertFalse("首次联网隐私同意默认 false", LlmPreferences.answerNetworkConsent(app))
        assertFalse("SAG 默认关闭", LlmPreferences.sagEnabled(app))
        assertFalse("联网默认关闭（默认禁网）", LlmPreferences.networkAllowed(app))
        assertFalse(LlmPreferences.DEFAULT_ANSWER_ENABLED)
        assertFalse(LlmPreferences.DEFAULT_ANSWER_NETWORK_CONSENT)
    }

    // ---- set/get 往返 ----

    @Test
    fun answerEnabledRoundTrip() {
        LlmPreferences.setAnswerEnabled(app, true)
        assertTrue(LlmPreferences.answerEnabled(app))
        LlmPreferences.setAnswerEnabled(app, false)
        assertFalse(LlmPreferences.answerEnabled(app))
    }

    @Test
    fun answerNetworkConsentRoundTrip() {
        LlmPreferences.setAnswerNetworkConsent(app, true)
        assertTrue(LlmPreferences.answerNetworkConsent(app))
        LlmPreferences.setAnswerNetworkConsent(app, false)
        assertFalse(LlmPreferences.answerNetworkConsent(app))
    }

    // ---- 开关隔离 ----

    @Test
    fun switchingSagAndNetworkFlagsDoesNotCorruptAnswerFlags() {
        LlmPreferences.setAnswerEnabled(app, true)
        LlmPreferences.setAnswerNetworkConsent(app, true)

        LlmPreferences.setSagEnabled(app, true)
        LlmPreferences.setNetworkAllowed(app, true)
        assertTrue("SAG/联网开关不得影响 answerEnabled", LlmPreferences.answerEnabled(app))
        assertTrue("SAG/联网开关不得影响 consent", LlmPreferences.answerNetworkConsent(app))

        LlmPreferences.setSagEnabled(app, false)
        LlmPreferences.setNetworkAllowed(app, false)
        assertTrue("关闭 SAG/联网也不得重置 consent", LlmPreferences.answerNetworkConsent(app))
        assertTrue(LlmPreferences.answerEnabled(app))
    }

    @Test
    fun switchingAnswerFlagsDoesNotCorruptSagAndNetworkFlags() {
        LlmPreferences.setSagEnabled(app, true)
        LlmPreferences.setNetworkAllowed(app, true)

        LlmPreferences.setAnswerEnabled(app, true)
        LlmPreferences.setAnswerNetworkConsent(app, true)
        assertTrue(LlmPreferences.sagEnabled(app))
        assertTrue(LlmPreferences.networkAllowed(app))

        LlmPreferences.setAnswerEnabled(app, false)
        LlmPreferences.setAnswerNetworkConsent(app, false)
        assertTrue("关闭问答开关不得影响 SAG", LlmPreferences.sagEnabled(app))
        assertTrue("关闭问答开关不得影响联网允许", LlmPreferences.networkAllowed(app))
    }

    @Test
    fun answerEnabledAndConsentAreIndependent() {
        LlmPreferences.setAnswerEnabled(app, true)
        assertFalse("开启问答开关不等于已同意联网隐私", LlmPreferences.answerNetworkConsent(app))
        LlmPreferences.setAnswerNetworkConsent(app, true)
        LlmPreferences.setAnswerEnabled(app, false)
        assertTrue("关闭问答开关不撤销已记录的隐私同意", LlmPreferences.answerNetworkConsent(app))
    }

    // ---- 隐私：prefs 不含敏感/正文数据 ----

    @Test
    fun preferencesStoreOnlyBooleanFlagsNotKeysOrBodies() {
        LlmPreferences.setAnswerEnabled(app, true)
        LlmPreferences.setAnswerNetworkConsent(app, true)
        LlmPreferences.setSagEnabled(app, false)
        LlmPreferences.setNetworkAllowed(app, false)

        val all = prefs.all
        assertFalse("隐私偏好 prefs 不应为空", all.isEmpty())
        all.forEach { (key, value) ->
            assertTrue("prefs 值应为布尔开关：$key=$value", value is Boolean)
            assertFalse("prefs key 不应包含密钥语义：$key", key.contains("key", ignoreCase = true) && key != "network_allowed")
        }
        val dump = all.entries.joinToString("|") { "${it.key}=${it.value}" }
        assertFalse("prefs 不得包含 API Key 明文", dump.contains("sk-"))
        assertFalse("prefs 不得包含问答正文", dump.contains("content") || dump.contains("body"))
    }
}
