package com.zhibox.memory.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.zhibox.memory.data.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PJZ110 真实 LLM Agent 质量采集（UiAutomator；不读取/输出 API key）。
 *
 * 前置：
 * - PJZ110 已升级到同签名 v0.1.2（保存 LLM 配置）；
 * - 用户已配置 LLM、开启问答/联网、通过首次联网确认；
 * - Agent 开关默认关，测试会点击打开；
 * - 知识库 fixture 由用户准备（测试不写 DB）。
 *
 * 测试通过 UiAutomator setText 输入中文（不依赖 IME），逐题新建会话、启用 Agent、发送，
 * 等待流式结束，将每轮 UI hierarchy/文本保存到 test APK filesDir（可用 run-as 拉取）。
 * 不判断答案正确性；真实 support 需人工抽检。
 */
@Ignore("manual on-device real-LLM eval; requires configured LLM, fixtures and 20+ cases; not a CI gate")
@RunWith(AndroidJUnit4::class)
class AgentRealEvalUiTest {

    private lateinit var device: UiDevice
    private lateinit var outDir: File
    private lateinit var db: AppDatabase

    private val queries = listOf(
        "光合作用主要发生在植物的哪个结构？",
        "上海这个城市在哪些事件里被提到？",
        "他后来去哪了？",
        "张三关联了哪些事件？",
        "简单介绍张三",
        "今天天气怎么样？",
        "查一下上海的相关资料",
        "上海在哪里？",
    )

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        db = AppDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
        outDir = File(
            // 测试进程运行在 target UID；target cacheDir 可写且可 run-as 拉取。
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "agent_real_eval",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
        launchChat()
        ensureAgentEnabled()
    }

    @After
    fun tearDown() {
        // 保留 test filesDir 供 adb run-as 拉取；不自动删除。
    }

    @Test
    fun collectAgentAnswers() {
        val results = JSONArray()
        queries.forEachIndexed { index, query ->
            // 同一会话连续多轮：避免“新对话”导致聊天屏状态切换/输入框不可见；
            // 多轮上下文正是评测的一部分。Agent 开关保持开启。
            ensureAgentEnabled()

            // 让聊天列表/输入区稳定在底部（上一轮长答案可能把“发送”滚出可见区域）。
            scrollToBottom()
            val input = findInput()
                ?: error("找不到输入框（Chat 未就绪或 UI 结构变化）")
            input.text = query
            device.waitForIdle()
            scrollToBottom()

            val assistantBefore = assistantMessages().size
            val send = findClickableText("发送")
                ?: run {
                    device.dumpWindowHierarchy(File(outDir, "send_missing_%02d.xml".format(index + 1)))
                    error("找不到发送按钮（已保存 hierarchy）")
                }
            send.click()
            val answer = waitForAssistantMessage(assistantBefore)
            device.waitForIdle()

            val xml = File(outDir, "case_%02d.xml".format(index + 1))
            device.dumpWindowHierarchy(xml)
            val texts = collectTexts()
            results.put(
                JSONObject().apply {
                    put("index", index + 1)
                    put("query", query)
                    put("answer", answer.content)
                    put("citations", JSONArray(answer.citations))
                    put("texts", JSONArray(texts))
                    put("xml", xml.name)
                    put("collectedAt", System.currentTimeMillis())
                },
            )
            File(outDir, "result.json").writeText(results.toString(2), Charsets.UTF_8)
        }
        // 留一行供 logcat/自动化定位；不含 key。
        println("P1_4_AGENT_REAL_EVAL cases=${results.length()} dir=${outDir.absolutePath}")
    }

    /** 等待流式/Agent 完成：先观察 busy 标记，再等消失；无 busy 时以发送按钮恢复为兜底。 */
    private fun waitForTurnCompletion() {
        val deadline = System.currentTimeMillis() + 120_000
        var sawBusy = false
        var quietSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val busy = device.findObject(By.text("停止")) != null ||
                device.findObject(By.text("生成中…")) != null ||
                device.findObject(By.text("Agent 规划中…")) != null ||
                device.findObject(By.text("规划中…")) != null
            if (busy) {
                sawBusy = true
                quietSince = 0L
            } else {
                if (quietSince == 0L) quietSince = System.currentTimeMillis()
                val quietFor = System.currentTimeMillis() - quietSince
                val send = findClickableText("发送")
                if (sawBusy && quietFor >= 1_500) return
                if (!sawBusy && quietFor >= 5_000 && send?.isEnabled == true) return
            }
            Thread.sleep(500)
        }
    }


    private data class DbAnswer(val messageId: String, val content: String, val citations: List<String>)

    private fun scrollToBottom() {
        // 兼容不同语义树：优先滚动可滚动列表；失败时用向上 swipe（内容向下）。
        runCatching {
            val scrollables = device.findObjects(By.scrollable(true))
            scrollables.lastOrNull()?.let { it.scroll(androidx.test.uiautomator.Direction.DOWN, 1.0f) }
        }
        if (findClickableText("发送") == null) {
            device.swipe(720, 1200, 720, 2600, 20)
            device.waitForIdle()
        }
    }

    private fun assistantMessages(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        db.openHelper.readableDatabase.query(
            "SELECT id, content FROM chat_message WHERE role = 'assistant' ORDER BY createdAt, id",
        ).use { c ->
            while (c.moveToNext()) rows += c.getString(0) to c.getString(1)
        }
        return rows
    }

    private fun waitForAssistantMessage(previousCount: Int): DbAnswer {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val rows = assistantMessages()
            if (rows.size > previousCount) {
                val (id, content) = rows.last()
                val citations = ArrayList<String>()
                db.openHelper.readableDatabase.query(
                    "SELECT chunkId, rank FROM message_citation WHERE messageId = ? ORDER BY rank",
                    arrayOf(id),
                ).use { c ->
                    while (c.moveToNext()) citations += "S${c.getLong(1) + 1}:chunk${c.getLong(0)}"
                }
                return DbAnswer(id, content, citations)
            }
            Thread.sleep(1_000)
        }
        error("等待 assistant 消息超时（Agent/LLM 未完成）")
    }

    private fun launchChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, "com.zhibox.memory.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        device.wait(Until.hasObject(By.text("问答")), 10_000)
        findClickableText("问答")?.click()
        device.wait(Until.hasObject(By.text("Agent 工具")), 10_000)
    }

    private fun ensureAgentEnabled() {
        // Agent Switch 是 Chat 顶部最后一个 checkable；先按文本定位附近，找到后访问其父。
        val label = device.wait(Until.findObject(By.text("Agent 工具")), 5_000) ?: return
        val checkables = device.findObjects(By.checkable(true))
        val switch = checkables.lastOrNull() ?: return
        if (!switch.isChecked) {
            switch.click()
            device.waitForIdle()
        }
        // 若仍是关闭，尝试点击 label 右侧（不同 OEM 语义树）。
        if (switch.isChecked.not() && checkables.lastOrNull()?.isChecked == false) {
            device.click(label.visibleBounds.right + 80, label.visibleBounds.centerY())
        }
        device.waitForIdle()
    }

    private fun findInput(): UiObject2? {
        val direct = device.findObjects(By.clazz("android.widget.EditText")).lastOrNull()
        if (direct != null) return direct
        // 冷启动时 Compose 输入框只暴露占位文本 TextView；先点击占位获得焦点。
        val hint = device.findObjects(By.textContains("输入问题")).lastOrNull()
            ?: device.findObjects(By.textContains("输入")).lastOrNull()
            ?: return null
        hint.click()
        device.waitForIdle()
        return device.findObjects(By.clazz("android.widget.EditText")).lastOrNull()
    }

    private fun findClickableText(text: String): UiObject2? =
        device.findObjects(By.text(text)).lastOrNull { it.isClickable } ?: device.findObject(By.text(text))

    private fun collectTexts(): List<String> {
        val xml = File(outDir, "temp_hierarchy.xml")
        device.dumpWindowHierarchy(xml)
        return Regex("<node[^>]*text=\"([^\"]*)\"")
            .findAll(xml.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
    }
}
