package com.zhibox.memory.core.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
 * B11 支持率抽样评测 harness（50 条有证据 query，人工/LLM judge 支持率）。
 *
 * 与 [B11QaEvalTest] 的区别：只覆盖**可答**场景（50 条抽样），用于评估"答案是否被证据支持 +
 * 引用是否规范"（supportRate 由人工/LLM judge 填写；本 harness 只负责生成与规则校验记录）。
 *
 * 证据直接取自 B10 已验证的多跳评测资产（`androidTest/assets/b10/kb.json` + `queries.json`），
 * **不跑 SAG/DB/模型**：对每条构造 [ChatEvidence] `S1..Sn` 后直接调用真实
 * [OpenAiAnswerGenerator.from] 流式生成。默认跳过，手动门控：
 * ```
 * adb shell am instrument -w -e b11Support 1 -e class com.zhibox.memory.core.chat.B11SupportEvalTest \
 *   com.zhibox.memory.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 另需设备满足 B11 隐私门禁（真实 LLM 配置 + 允许联网 + 带引用问答 + 网络同意）；不满足时
 * **不发起网络请求**，每条记录 refused/code 后正常结束（不崩溃、不打印 key）。
 *
 * 输出：`files/b11-eval/support-answers.json`；宿主机计分/模板：
 * `python3 scripts/eval/score_b11_support.py --answers <pull 出的 support-answers.json>`。
 *
 * 选择口径与 `scripts/eval/b11_support_manifest.json` 保持一致（改选择需两处同步）：
 * b10 `q001..q050`（自然均衡：2 跳 18 / 3 跳 16 / 4 跳 16）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class B11SupportEvalTest {

    private companion object {
        /** 50 条有证据 query（b10 q001..q050）：2 跳 18 + 3 跳 16 + 4 跳 16。 */
        val EVIDENCE_QIDS: List<String> = (1..50).map { "q%03d".format(it) }

        const val ARG_KEY = "b11Support"
        const val EVAL_DIR = "b11-eval"
        const val OUTPUT_FILE = "support-answers.json"
    }

    @Test
    fun runB11SupportEval() = runBlocking {
        // 默认跳过：只有显式 -e b11Support 1 才运行（真实 LLM/网络/耗时）
        Assume.assumeTrue(
            "B11 支持率评测需手动开启（adb shell am instrument -w -e b11Support 1 ...）",
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
        for (qid in EVIDENCE_QIDS) {
            val query = queryById[qid] ?: continue
            val question = query.getString("query")
            val hops = query.optInt("hops", 0)
            val chunkIndices = query.getJSONArray("evidence").let { array ->
                (0 until array.length()).map { array.getInt(it) }
            }
            val expectedChunkIds = chunkIndices.map { it.toLong() }
            if (blockedCode != null) {
                records.put(blockedRecord(qid, question, hops, expectedChunkIds, blockedCode))
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
        val file = File(dir, OUTPUT_FILE)
        file.writeText(output.toString(2))

        println(
            "B11_SUPPORT_EVAL_DONE records=${records.length()} gateOpen=$gateOpen " +
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
        val done = completed
        val failure = failed
        val refused = failure != null || done == null
        val unreferencedSentenceCount = done?.unreferencedSentenceCount ?: 0
        val idLevelValid = done != null &&
            done.citations.isNotEmpty() &&
            done.invalidCitationTokens.isEmpty() &&
            !done.unverified
        val fullyCited = idLevelValid && unreferencedSentenceCount == 0
        return JSONObject()
            .put("qid", qid)
            .put("question", question)
            .put("hops", hops)
            .put("isNoEvidence", false)
            .put("expectedEvidenceChunkIds", JSONArray(expectedChunkIds))
            .put("expectedEvidence", JSONArray(expectedChunkIds))
            .put("evidenceIds", JSONArray(evidence.map { it.evidenceId }))
            .put("answer", done?.text ?: streamed.toString())
            .put("citations", JSONArray(citationsJson(done?.citations)))
            .put("invalid", JSONArray(done?.invalidCitationTokens ?: emptyList<String>()))
            .put("unverified", done?.unverified ?: false)
            .put("unreferencedSentenceCount", unreferencedSentenceCount)
            .put("fullyCited", fullyCited)
            .put("supportManual", JSONObject.NULL)
            .put("refused", refused)
            .put("code", failure?.code ?: JSONObject.NULL)
            .put("model", done?.model ?: JSONObject.NULL)
            .put("elapsedMs", elapsedMs)
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
        .put("expectedEvidence", JSONArray(expectedChunkIds))
        .put("evidenceIds", JSONArray(emptyList<String>()))
        .put("answer", "")
        .put("citations", JSONArray(emptyList<JSONObject>()))
        .put("invalid", JSONArray(emptyList<String>()))
        .put("unverified", false)
        .put("unreferencedSentenceCount", 0)
        .put("fullyCited", false)
        .put("supportManual", JSONObject.NULL)
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
