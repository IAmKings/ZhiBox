package com.zhibox.memory.core.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B11 真实问答评测 harness（20 条：16 有证据 + 4 无证据）。
 *
 * **只评生成/引用/拒答**：证据直接取自 B10 已验证的多跳评测资产
 * （`androidTest/assets/b10/kb.json` + `queries.json`，80 chunk / 120 query），
 * 不跑 SAG 检索、不写 DB、不加载端侧 embedding 模型；对每条构造 [ChatEvidence] `S1..Sn`
 * 后直接调用真实 [OpenAiAnswerGenerator.from] 流式生成。
 *
 * 门控（默认跳过，避免 CI/IDE 误触发真实网络）：
 * ```
 * adb shell am instrument -w -e b11Eval 1 \
 *   com.zhibox.memory.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 另需在设备上满足 B11 隐私门禁：允许联网 + 开启带引用问答 + 网络同意 + 已保存真实 LLM 配置；
 * 任一不满足时**不发起网络请求**，每条记录 refused/code 后正常结束（不崩溃、不打印 key）。
 *
 * 输出：`files/b11-eval/answers.json`（含 gate 元数据 + 每条记录；无 key 也不落 key）。
 * 宿主机计分：`python3 scripts/eval/score_b11_qa.py --answers <pull 出的 answers.json>`。
 *
 * 选择口径与 `scripts/eval/b11_qa_manifest.json` 保持一致（改选择需两处同步）。
 */
@RunWith(AndroidJUnit4::class)
class B11QaEvalTest {

    private companion object {
        /** 有证据的 16 条：2 跳×6 + 3 跳×5 + 4 跳×5（来自 b10 queries.json，qid 见 manifest）。 */
        val WITH_EVIDENCE_QIDS: List<String> = listOf(
            "q001", "q007", "q013", "q019", "q025", "q031", // 2 跳
            "q003", "q009", "q015", "q021", "q027", // 3 跳
            "q005", "q011", "q017", "q023", "q029", // 4 跳
        )

        /** 4 条 KB 外问题：无证据 → 期望零网络拒答（NO_EVIDENCE）。 */
        val NO_EVIDENCE_QUERIES: List<Pair<String, String>> = listOf(
            "noev-01" to "爱因斯坦的相对论首次实验验证是在哪一年？",
            "noev-02" to "珠穆朗玛峰的海拔是多少米？",
            "noev-03" to "《三体》的作者是谁？",
            "noev-04" to "Python 语言最初由谁设计？",
        )

        const val ARG_KEY = "b11Eval"
        const val EVAL_DIR = "b11-eval"
    }

    @Test
    fun runB11QaEval() = runBlocking {
        // 默认跳过：只有显式 -e b11Eval 1 才运行（真实 LLM/网络/耗时）
        Assume.assumeTrue(
            "B11 QA 评测需手动开启（adb shell am instrument -w -e b11Eval 1 ...）",
            InstrumentationRegistry.getArguments().getString(ARG_KEY) == "1",
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val kb = JSONObject(readAsset(instrumentation, "kb.json"))
        val chunks = kb.getJSONArray("chunks")
        val documents = kb.getJSONArray("documents")
        val queries = JSONObject(readAsset(instrumentation, "queries.json")).getJSONArray("queries")

        val docIndexById = LinkedHashMap<String, Long>()
        val docTitleById = LinkedHashMap<String, String>()
        for (i in 0 until documents.length()) {
            val doc = documents.getJSONObject(i)
            docIndexById[doc.getString("id")] = i + 1L
            docTitleById[doc.getString("id")] = doc.getString("title")
        }
        val queryById = LinkedHashMap<String, JSONObject>()
        for (i in 0 until queries.length()) {
            val query = queries.getJSONObject(i)
            queryById[query.getString("id")] = query
        }

        val generator = OpenAiAnswerGenerator.from(context)
        val store = KeystoreLlmConfigStore(context)
        val providerConfigured = runCatching { store.load() != null }.getOrDefault(false)
        val model = runCatching { store.load()?.model }.getOrNull()
        val answerEnabled = LlmPreferences.answerEnabled(context)
        val networkAllowed = LlmPreferences.networkAllowed(context)
        val consent = LlmPreferences.answerNetworkConsent(context)
        val gateOpen = providerConfigured && answerEnabled && networkAllowed && consent
        val blockedCode: String? = when {
            !providerConfigured -> "KEY_MISSING"
            !answerEnabled -> "ANSWER_DISABLED"
            !networkAllowed -> "LLM_OFFLINE"
            !consent -> "ANSWER_CONSENT_MISSING"
            else -> null
        }

        val records = JSONArray()

        // 1) 4 条无证据：零网络拒答路径（不读配置、不发请求，可在任何设备/无 key 下验证）。
        for ((qid, question) in NO_EVIDENCE_QUERIES) {
            records.put(
                evalCase(
                    generator = generator,
                    qid = qid,
                    question = question,
                    hops = 0,
                    expectedChunkIds = emptyList(),
                    isNoEvidence = true,
                    evidence = emptyList(),
                ),
            )
        }

        // 2) 16 条有证据：门禁不满足 → 全部记录 refused（不发起任何请求）；满足才真实生成。
        for (qid in WITH_EVIDENCE_QIDS) {
            val query = queryById[qid] ?: continue
            val question = query.getString("query")
            val hops = query.optInt("hops", 0)
            val chunkIndices = query.getJSONArray("evidence").let { array ->
                (0 until array.length()).map { array.getInt(it) }
            }
            val expectedChunkIds = chunkIndices.map { it.toLong() }
            if (blockedCode != null) {
                records.put(
                    blockedRecord(qid, question, hops, expectedChunkIds, blockedCode),
                )
                continue
            }
            val evidence = buildEvidence(
                chunkIndices = chunkIndices,
                chunks = chunks,
                docIndexById = docIndexById,
                docTitleById = docTitleById,
            )
            records.put(
                evalCase(
                    generator = generator,
                    qid = qid,
                    question = question,
                    hops = hops,
                    expectedChunkIds = expectedChunkIds,
                    isNoEvidence = false,
                    evidence = evidence,
                ),
            )
        }

        val output = JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("total", records.length())
            .put(
                "gate",
                JSONObject()
                    .put("providerConfigured", providerConfigured)
                    .put("answerEnabled", answerEnabled)
                    .put("networkAllowed", networkAllowed)
                    .put("answerNetworkConsent", consent)
                    .put("gateOpen", gateOpen)
                    .put("model", model ?: JSONObject.NULL),
            )
            .put("records", records)
        val dir = File(context.filesDir, EVAL_DIR).apply { mkdirs() }
        val file = File(dir, "answers.json")
        file.writeText(output.toString(2))

        println(
            "B11_EVAL_DONE records=${records.length()} gateOpen=$gateOpen " +
                "dir=${dir.absolutePath}",
        )
    }

    // ─────────────────────────── 执行/构造 ───────────────────────────

    private suspend fun evalCase(
        generator: AnswerGenerator,
        qid: String,
        question: String,
        hops: Int,
        expectedChunkIds: List<Long>,
        isNoEvidence: Boolean,
        evidence: List<ChatEvidence>,
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val streamed = StringBuilder()
        var completed: AnswerEvent.Completed? = null
        var failed: AnswerEvent.Failed? = null
        try {
            generator.generate(AnswerRequest(query = question, evidence = evidence)).collect { event ->
                when (event) {
                    is AnswerEvent.Delta -> streamed.append(event.text)
                    is AnswerEvent.Completed -> completed = event
                    is AnswerEvent.Failed -> failed = event
                    AnswerEvent.Stopped -> Unit // harness 不主动停止
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            failed = AnswerEvent.Failed("HARNESS_EXCEPTION", e.javaClass.simpleName)
        }
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        val refused = failed != null || completed == null
        val record = JSONObject()
            .put("qid", qid)
            .put("question", question)
            .put("hops", hops)
            .put("isNoEvidence", isNoEvidence)
            .put("expectedEvidenceChunkIds", JSONArray(expectedChunkIds))
            .put("evidenceIds", JSONArray(evidence.map { it.evidenceId }))
            .put("answer", completed?.text ?: streamed.toString())
            .put("citations", JSONArray(citationsJson(completed?.citations)))
            .put("invalid", JSONArray(completed?.invalidCitationTokens ?: emptyList<String>()))
            .put("unverified", completed?.unverified ?: false)
            .put("unreferencedSentenceCount", completed?.unreferencedSentenceCount ?: 0)
            .put(
                "fullyCited",
                completed != null &&
                    completed!!.citations.isNotEmpty() &&
                    !completed!!.unverified &&
                    completed!!.invalidCitationTokens.isEmpty() &&
                    completed!!.unreferencedSentenceCount == 0,
            )
            .put("refused", refused)
            .put("code", failed?.code ?: JSONObject.NULL)
            .put("model", completed?.model ?: JSONObject.NULL)
            .put("elapsedMs", elapsedMs)
        return record
    }

    private fun blockedRecord(
        qid: String,
        question: String,
        hops: Int,
        expectedChunkIds: List<Long>,
        code: String,
    ): JSONObject = JSONObject()
        .put("qid", qid)
        .put("question", question)
        .put("hops", hops)
        .put("isNoEvidence", false)
        .put("expectedEvidenceChunkIds", JSONArray(expectedChunkIds))
        .put("evidenceIds", JSONArray(emptyList<String>()))
        .put("answer", "")
        .put("citations", JSONArray(emptyList<JSONObject>()))
        .put("invalid", JSONArray(emptyList<String>()))
        .put("unverified", false)
        .put("unreferencedSentenceCount", 0)
        .put("fullyCited", false)
        .put("refused", true)
        .put("code", code)
        .put("model", JSONObject.NULL)
        .put("elapsedMs", 0L)

    private fun citationsJson(citations: List<ChatCitation>?): List<JSONObject> =
        citations.orEmpty().map { citation ->
            JSONObject()
                .put("evidenceId", citation.evidenceId)
                .put("chunkId", citation.chunkId)
                .put("rank", citation.rank)
        }

    private fun buildEvidence(
        chunkIndices: List<Int>,
        chunks: JSONArray,
        docIndexById: Map<String, Long>,
        docTitleById: Map<String, String>,
    ): List<ChatEvidence> = chunkIndices.mapIndexed { order, oneBasedIndex ->
        val chunk = chunks.getJSONObject(oneBasedIndex - 1)
        val content = chunk.getString("content")
        val documentId = chunk.getString("documentId")
        ChatEvidence(
            evidenceId = "S${order + 1}",
            chunkId = oneBasedIndex.toLong(),
            documentId = docIndexById[documentId] ?: 0L,
            sourceId = 1L,
            docTitle = docTitleById[documentId] ?: "",
            sourceTitle = "B10 评测合成语料",
            sourceUri = "asset://b10/$documentId",
            content = content,
            ordinal = chunk.optInt("ordinal", oneBasedIndex - 1),
            charStart = 0,
            charEnd = content.length,
            eventId = null,
            score = null,
        )
    }

    private fun readAsset(instrumentation: android.app.Instrumentation, name: String): String =
        instrumentation.context.assets.open("b10/$name")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
