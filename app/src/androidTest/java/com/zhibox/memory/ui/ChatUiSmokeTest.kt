package com.zhibox.memory.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.R
import com.zhibox.memory.core.chat.ChatCitation
import com.zhibox.memory.core.chat.ChatConversation
import com.zhibox.memory.core.chat.ChatMessage
import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.ui.theme.ZhiBoxTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B11 Chat Compose 冒烟测试（instrumentation / 真机 PJZ110，`am instrument`）。
 *
 * 只渲染无状态 [ChatContent] + fake [ChatUiState]：**不启动 ViewModel、不访问 DB/LLM/网络**，
 * 用例间无顺序依赖。覆盖 6 条主链路：
 * 1. 空态/新会话：标题/输入占位/发送按钮禁用/空态文案/「新对话」回调；
 * 2. 消息 + 引用：user/assistant 文本可见，引用 chip（实际文案 `引用 S#`）点击回传 [ChatCitation]；
 * 3. 流式：部分文本 + 「生成中…」+「停止」按钮回调（发送按钮不存在）；
 * 4. 错误/重试：错误卡片 + 「知道了」回调、canRetry 时「重试」回调；
 * 5. LLM 未就绪 + 警示：显式不可用文案、未通过引用校验警示 + 「知道了」= onDismissWarning；
 * 6. 输入/发送：performTextInput 触发 onInputChange、内容非空后发送可用、点击回传 onSend。
 *
 * 断言前对消息区（LazyColumn）先 `performScrollToNode`，避免小屏/长文案导致节点未组合或不可见。
 */
@RunWith(AndroidJUnit4::class)
class ChatUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun readyState() = ChatUiState(
        answerEnabled = true,
        networkAllowed = true,
        llmConfigured = true,
        consentGranted = true,
    )

    /** 消息区是 ChatContent 中唯一的 LazyColumn（会话 chips 用 LazyRow，本类 fake 均不设 conversations）。 */
    private fun scrollMessagesTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    // ---------------- 1) 空态 / 新会话 ----------------

    @Test
    fun emptyState_显示输入与空态_新对话回调() {
        val newConversation = AtomicBoolean(false)
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = readyState(),
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onNewConversation = { newConversation.set(true) },
                    onSelectConversation = {},
                    onCitationClick = {},
                    onDismissError = {},
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.chat_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chat_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chat_empty_message)).assertIsDisplayed()
        // 输入占位与禁用的发送按钮（input 为空）
        composeRule.onNodeWithText(str(R.string.chat_input_hint)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chat_send)).assertIsDisplayed().assertIsNotEnabled()
        // 无消息气泡（Q/A 标记不存在）
        composeRule.onNodeWithText("Q").assertDoesNotExist()
        composeRule.onNodeWithText("A").assertDoesNotExist()

        composeRule.onNodeWithText(str(R.string.chat_new_conversation)).performClick()
        composeRule.waitForIdle()
        assertTrue("「新对话」应触发 onNewConversation", newConversation.get())
    }

    // ---------------- 2) 消息 + 引用 chip ----------------

    @Test
    fun messages_显示问答与引用chip_点击回传citation() {
        val userText = "蓝牙断开怎么恢复？"
        val answerText = "先关闭耳机电源再长按配对键 5 秒后重新配对。"
        val clicked = AtomicReference<ChatCitation?>()
        val conversation = ChatConversation(
            id = "conv_test",
            title = "蓝牙断开怎么恢复",
            sourceScope = emptyList(),
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_100_000,
        )
        val messages = listOf(
            ChatMessage(
                id = "msg_user",
                conversationId = conversation.id,
                role = ChatRole.USER,
                content = userText,
                createdAt = 1_700_000_000_000,
                model = null,
            ),
            ChatMessage(
                id = "msg_assistant",
                conversationId = conversation.id,
                role = ChatRole.ASSISTANT,
                content = answerText,
                createdAt = 1_700_000_100_000,
                model = "test-model",
            ),
        )
        val citation = ChatCitation(evidenceId = "S1", chunkId = 101, rank = 0)
        val state = readyState().copy(
            current = conversation,
            messages = messages,
            citations = mapOf("msg_assistant" to listOf(citation)),
        )

        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onNewConversation = {},
                    onSelectConversation = {},
                    onCitationClick = { clicked.set(it) },
                    onDismissError = {},
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        scrollMessagesTo(userText)
        composeRule.onNodeWithText(userText).assertIsDisplayed()
        scrollMessagesTo(answerText)
        composeRule.onNodeWithText(answerText).assertIsDisplayed()

        // 引用 chip 实际文案来自 strings：`引用 %1$s`（不是 [S#]）
        val chipLabel = str(R.string.chat_citation_chip, "S1")
        scrollMessagesTo(chipLabel)
        composeRule.onNodeWithText(chipLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("S1", clicked.get()?.evidenceId)
        assertEquals(101L, clicked.get()?.chunkId)
    }

    // ---------------- 3) 流式：部分文本 + 停止 ----------------

    @Test
    fun streaming_显示部分文本与停止按钮_回调() {
        val stopped = AtomicBoolean(false)
        val state = readyState().copy(
            streaming = true,
            streamingText = "正在根据证据生成回答（部分文本）…",
        )
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = { stopped.set(true) },
                    onRetry = {},
                    onNewConversation = {},
                    onSelectConversation = {},
                    onCitationClick = {},
                    onDismissError = {},
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        scrollMessagesTo(str(R.string.chat_generating))
        composeRule.onNodeWithText(str(R.string.chat_generating)).assertIsDisplayed()
        composeRule.onNodeWithText("正在根据证据生成回答（部分文本）…").assertIsDisplayed()
        // 流式期间发送按钮替换为停止按钮
        composeRule.onNodeWithText(str(R.string.chat_send)).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.chat_stop)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("「停止」应触发 onStop", stopped.get())
    }

    // ---------------- 4) 错误 + 重试 ----------------

    @Test
    fun errorAndCanRetry_显示错误与重试_双向回调() {
        val errorText = "问答失败：模拟错误（测试桩）"
        val dismissed = AtomicBoolean(false)
        val retried = AtomicBoolean(false)
        val state = readyState().copy(
            errorMessage = errorText,
            canRetry = true,
            lastQuery = "蓝牙断开怎么恢复？",
        )
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = { retried.set(true) },
                    onNewConversation = {},
                    onSelectConversation = {},
                    onCitationClick = {},
                    onDismissError = { dismissed.set(true) },
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(errorText).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.action_dismiss)).performClick()
        composeRule.waitForIdle()
        assertTrue("错误卡片动作应触发 onDismissError", dismissed.get())

        composeRule.onNodeWithText(str(R.string.chat_retry)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("canRetry 时「重试」应触发 onRetry", retried.get())
    }

    // ---------------- 5) LLM 未就绪 + 引用校验警示 ----------------

    @Test
    fun llmNotReady_显示不可用文案_警示可关闭() {
        val warningDismissed = AtomicBoolean(false)
        val state = ChatUiState(
            answerEnabled = false,
            networkAllowed = false,
            llmConfigured = false,
            warning = str(R.string.chat_unverified_warning),
        )
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onNewConversation = {},
                    onSelectConversation = {},
                    onCitationClick = {},
                    onDismissError = {},
                    onDismissWarning = { warningDismissed.set(true) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.chat_llm_not_ready)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chat_unverified_warning)).assertIsDisplayed()
        // warning 的关闭按钮（error 未设置 → 只有这一个「知道了」）
        composeRule.onNodeWithText(str(R.string.action_dismiss)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("警示关闭应触发 onDismissWarning", warningDismissed.get())
    }

    // ---------------- 6) 输入 / 发送 ----------------

    @Test
    fun inputChange_更新输入并启用发送_send回调() {
        val state = mutableStateOf(readyState())
        val sent = AtomicBoolean(false)
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state.value,
                    onInputChange = { state.value = state.value.copy(input = it) },
                    onSend = { sent.set(true) },
                    onStop = {},
                    onRetry = {},
                    onNewConversation = {},
                    onSelectConversation = {},
                    onCitationClick = {},
                    onDismissError = {},
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        // 初始发送禁用
        composeRule.onNodeWithText(str(R.string.chat_send)).assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("测试问题：蓝牙断开怎么恢复？")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试问题：蓝牙断开怎么恢复？").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.chat_send)).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertTrue("点击发送应触发 onSend", sent.get())
    }
    // ---------------- 7) B11 模式 + source scope 控件 ----------------

    @Test
    fun modeAndSourceScopeControls_回传选择() {
        val selectedMode = AtomicReference<RetrievalMode?>(RetrievalMode.MULTI)
        val selectedScope = AtomicReference<List<String>>(emptyList())
        val state = readyState().copy(
            availableSources = listOf(
                ChatSourceOption("src-1", "来源一"),
                ChatSourceOption("src-2", "来源二"),
            ),
        )
        composeRule.setContent {
            ZhiBoxTheme {
                ChatContent(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onRetry = {},
                    onNewConversation = {},
                    onSelectConversation = {},
                    onModeChange = { selectedMode.set(it) },
                    onSourceScopeChange = { selectedScope.set(it) },
                    onCitationClick = {},
                    onDismissError = {},
                    onDismissWarning = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.chat_mode_auto)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.search_mode_multi)).performClick()
        composeRule.waitForIdle()
        assertEquals(RetrievalMode.MULTI, selectedMode.get())

        composeRule.onNodeWithText(str(R.string.chat_source_all)).assertIsDisplayed()
        composeRule.onNodeWithText("来源一").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("src-1"), selectedScope.get())
    }

}
