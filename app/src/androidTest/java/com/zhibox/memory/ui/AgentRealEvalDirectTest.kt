package com.zhibox.memory.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PJZ110 真实 LLM Agent 质量采集（直接驱动生产 ChatViewModel，不依赖 UI 中文输入）。
 *
 * 前置：已安装 release-signed debuggable v0.1.2、LLM/联网/问答已配置且已通过 consent。
 * 测试只在内存中操作聊天，不含/不输出 API key；answer/citation 从 app DB 读取。
 */
@Ignore("manual on-device real-LLM eval; requires configured LLM, fixtures and 20+ cases; not a CI gate")
@RunWith(AndroidJUnit4::class)
class AgentRealEvalDirectTest {

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

    @Test
    fun collectDirect() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.get(app)
        val vm = ChatViewModel(app)
        val instr = InstrumentationRegistry.getInstrumentation()
        // 等待设置/可用性状态加载；llmReady 由 answerEnabled+networkAllowed+configured 决定。
        val ready = withTimeoutOrNull(20_000) {
            while (!vm.state.value.llmReady) kotlinx.coroutines.delay(200)
            true
        } ?: false
        check(ready) { "LLM 未就绪（answerEnabled/network/configured 任一未满足）" }
        check(vm.state.value.consentGranted) { "首次联网 consent 未授予；请先在 UI 同意" }
        instr.runOnMainSync { vm.setAgentEnabled(true) }
        val outDir = File(app.cacheDir, "agent_direct_eval").apply {
            deleteRecursively(); mkdirs()
        }
        val results = JSONArray()
        queries.forEachIndexed { idx, query ->
            val before = assistantCount(db)
            instr.runOnMainSync {
                vm.setInput(query)
                vm.send()
            }
            val answer = waitForAssistant(db, before)
            results.put(
                JSONObject().apply {
                    put("index", idx + 1)
                    put("query", query)
                    put("answer", answer.first)
                    put("citations", JSONArray(answer.second))
                },
            )
            File(outDir, "result.json").writeText(results.toString(2), Charsets.UTF_8)
        }
        println("P1_4_AGENT_DIRECT cases=${results.length()} dir=${outDir.absolutePath}")
    }

    private suspend fun assistantCount(db: AppDatabase): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM chat_message WHERE role='assistant'").use {
            it.moveToFirst(); return it.getInt(0)
        }
    }

    private suspend fun waitForAssistant(db: AppDatabase, before: Int): Pair<String, List<String>> {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val row = db.openHelper.readableDatabase.query(
                "SELECT id, content FROM chat_message WHERE role='assistant' ORDER BY createdAt DESC, id DESC LIMIT 1",
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
            }
            val count = assistantCount(db)
            if (count > before && row != null) {
                val cites = ArrayList<String>()
                db.openHelper.readableDatabase.query(
                    "SELECT chunkId, rank FROM message_citation WHERE messageId=? ORDER BY rank",
                    arrayOf(row.first),
                ).use { c ->
                    while (c.moveToNext()) cites += "S${c.getLong(1) + 1}:chunk${c.getLong(0)}"
                }
                return row.second to cites
            }
            kotlinx.coroutines.delay(1_000)
        }
        error("等待 assistant 回答超时")
    }

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
}
