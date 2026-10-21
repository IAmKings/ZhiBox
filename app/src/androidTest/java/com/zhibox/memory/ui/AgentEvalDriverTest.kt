package com.zhibox.memory.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P1.4 真实 Agent 20 题采集驱动（manual on-device；需 release/debug 同签名 app + LLM key）。
 *
 * - 直接驱动生产 ChatViewModel（无 UiAutomator 中文输入问题）；
 * - 逐题新会话（防止历史/引用串题），Agent 开关开启；
 * - 记录 answer/citations/latency/failureCode/toolCalls/fallbackReason/refused；
 * - 单题超时 75s，超时停止 generation 并记录 TIMEOUT 后继续，不中断整批；
 * - 结果写 target cacheDir/agent_eval20.json（可 run-as 拉取）；无 key 时 Assume skip。
 */
@RunWith(AndroidJUnit4::class)
class AgentEvalDriverTest {

    @Test
    fun collect20() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.get(app)
        val vm = ChatViewModel(app)
        val instr = InstrumentationRegistry.getInstrumentation()

        val ready = withTimeoutOrNull(20_000) {
            while (!vm.state.value.llmReady) delay(200)
            true
        } ?: false
        Assume.assumeTrue("LLM 未就绪（需配置/联网/问答开启）", ready)
        Assume.assumeTrue("首次联网 consent 未授予", vm.state.value.consentGranted)
        instr.runOnMainSync { vm.setAgentEnabled(true) }

        val cases = readCases()
        val out = JSONArray()
        val outFile = File(app.cacheDir, "agent_eval20.json").apply { delete() }

        cases.forEachIndexed { idx, case ->
            if (vm.state.value.streaming) {
                instr.runOnMainSync { vm.stopGeneration() }
                waitIdle(vm, 10_000)
            }
            // 新对话，避免上一题历史影响。
            instr.runOnMainSync { vm.newConversation() }
            delay(500)
            val before = assistantCount(db)
            val started = System.currentTimeMillis()
            instr.runOnMainSync {
                vm.setInput(case.query)
                vm.send()
            }
            val answer = waitForAssistant(db, before, 75_000)
            val elapsed = System.currentTimeMillis() - started
            if (answer == null) {
                instr.runOnMainSync { vm.stopGeneration() }
                waitIdle(vm, 10_000)
            }
            val st = vm.state.value
            val result = JSONObject().apply {
                put("index", idx + 1)
                put("id", case.id)
                put("query", case.query)
                put("expectedToolRoute", JSONArray(case.expectedToolRoute))
                put("expectedFallback", case.expectedFallback ?: JSONObject.NULL)
                put("expectedCitationIds", JSONArray(case.expectedCitationIds))
                put("answer", answer?.first ?: "")
                put("citations", JSONArray(answer?.second ?: emptyList<String>()))
                put("latencyMs", elapsed)
                put("failureCode", if (answer == null) "TIMEOUT" else (st.errorMessage?.let { "UI_ERROR" } ?: JSONObject.NULL))
                put("warning", st.warning ?: JSONObject.NULL)
                put("agentFallbackReason", st.agentFallbackReason ?: JSONObject.NULL)
                put("toolCalls", JSONArray(st.agentToolCalls.map { it.name }))
            }
            out.put(result)
            outFile.writeText(out.toString(2), Charsets.UTF_8)
            println("P1_4_AGENT_EVAL20 case=${idx + 1}/${cases.size} ms=$elapsed ok=${answer != null}")
        }
        println("P1_4_AGENT_EVAL20_DONE cases=${out.length()} dir=${outFile.absolutePath}")
    }

    private data class CaseVal(
        val id: String,
        val query: String,
        val expectedToolRoute: List<String>,
        val expectedFallback: String?,
        val expectedCitationIds: List<String>,
    )

    private fun readCases(): List<CaseVal> {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets
            .open("agent_eval_cases.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val arr = root.getJSONArray("cases")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CaseVal(
                id = o.getString("id"),
                query = o.getString("query"),
                expectedToolRoute = o.getJSONArray("expectedToolRoute").toStringList(),
                expectedFallback = if (o.isNull("expectedFallback")) null else o.getString("expectedFallback"),
                expectedCitationIds = if (o.isNull("expectedCitationIds")) emptyList() else o.getJSONArray("expectedCitationIds").toStringList(),
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private suspend fun assistantCount(db: AppDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM chat_message WHERE role='assistant'").use {
            it.moveToFirst(); it.getInt(0)
        }

    private suspend fun waitForAssistant(db: AppDatabase, before: Int, timeoutMs: Long): Pair<String, List<String>>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = assistantCount(db)
            if (count > before) {
                val row = db.openHelper.readableDatabase.query(
                    "SELECT id, content FROM chat_message WHERE role='assistant' ORDER BY createdAt DESC, id DESC LIMIT 1",
                ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
                if (row != null) {
                    val cites = ArrayList<String>()
                    db.openHelper.readableDatabase.query(
                        "SELECT chunkId, rank FROM message_citation WHERE messageId=? ORDER BY rank",
                        arrayOf(row.first),
                    ).use { c ->
                        while (c.moveToNext()) cites += "S${c.getLong(1) + 1}:chunk${c.getLong(0)}"
                    }
                    return row.second to cites
                }
            }
            delay(500)
        }
        return null
    }

    private suspend fun waitIdle(vm: ChatViewModel, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && vm.state.value.streaming) delay(200)
    }
}
