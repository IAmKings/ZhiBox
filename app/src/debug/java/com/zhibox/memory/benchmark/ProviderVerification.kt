package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.embedding.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * 嵌入 provider 验证基准（provider 无关，B7 后由原 `PathBVerification` 泛化而来）。
 *
 * 旧 `PathBVerification` 的默认入口服务于已下线的 Granite（路径 B）；B7 删除 Granite/LFM 后，
 * 这里只保留与具体引擎无关的验证骨架，由调用方显式传入 provider 工厂：
 * - G1E：EGemma2-740M/440M 通用版 × UniversalEmbedder 通道 × GPU；
 * - G1D：EGemma2 SOC 特化版（NPU 编译）× UniversalEmbedder × Delegate.NPU。
 *
 * 判定契约（与旧 G1B 一致）：
 * 1. 初始化计时：首次 + 二次（cacheDir/持久缓存生效性观察）；
 * 2. 配对检索 R@1/R@5/MRR（复用 G1 的 50 样本 qNN↔dNN），按语言子集（zh/en/mix）分列；
 * 3. 确定性：同会话重复编码逐位一致 + 全体查询向量 SHA-256（跨设备/跨会话比对凭据）；
 * 4. 长度×延迟（30/200/400 CJK）+ 序列上限探针（500/550/600/800）。
 *
 * 门槛：确定性必须通过；R@1 ≥ 0.8；维度与 descriptor 一致。
 *
 * 输入（与 G1 共用投放）：filesDir/g1/samples.json（缺省时从 g1/reference.json 内嵌 samples 提取）
 * 输出：filesDir/g1b_results/<experiment>_<model>.json
 */
object ProviderVerification {

    private const val RETRIEVAL_RECALL1_GATE = 0.8
    private val MEASURE_LENGTHS = listOf(30, 200, 400)
    private val SEQ_PROBE_LENGTHS = listOf(500, 550, 600, 800)

    suspend fun run(
        context: Context,
        modelFile: File,
        experiment: String,
        buildProvider: (android.content.Context, File) -> EmbeddingProvider,
    ): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val samples = loadSamples(appContext)
            ?: throw IllegalStateException(
                "缺少 filesDir/g1/samples.json 与 g1/reference.json（先投放 G1 样本集）"
            )

        val initMs = JSONArray()
        val t0 = System.currentTimeMillis()
        var provider = buildProvider(appContext, modelFile)
        val firstInitMs = System.currentTimeMillis() - t0
        provider.close()
        val t1 = System.currentTimeMillis()
        provider = buildProvider(appContext, modelFile)
        val secondInitMs = System.currentTimeMillis() - t1
        initMs.put(JSONObject().put("first_ms", firstInitMs).put("second_ms", secondInitMs))

        try {
            val providerId = provider.descriptor.providerId
            val dims = provider.descriptor.dimensions.takeIf { it > 0 } ?: run {
                provider.embedQuery("维度探测")
                provider.descriptor.dimensions
            }

            // ---- 编码全部样本（query 走 embedQuery，document 走 embedDocuments 批）----
            val queries = HashMap<String, FloatArray>()
            val documents = HashMap<String, FloatArray>()
            val langById = HashMap<String, String>()
            val docIds = mutableListOf<String>()
            val textById = HashMap<String, String>()
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val id = s.getString("id")
                val text = s.getString("text")
                textById[id] = text
                // 样本自带 lang 字段（zh/en/mix）优先；缺省回退 CJK 占比启发式
                langById[id] = s.optString("lang").ifBlank { classifyLang(text) }
                if (s.getString("role") == "query") queries[id] = provider.embedQuery(text)
                else docIds += id
            }
            provider.embedDocuments(docIds.map { textById.getValue(it) })
                .forEachIndexed { idx, v -> documents[docIds[idx]] = v }

            // ---- 配对检索（qNN↔dNN）+ 语言子集 ----
            data class Agg(var hits1: Int = 0, var hits5: Int = 0, var n: Int = 0, var rr: Double = 0.0)
            val overall = Agg()
            val byLang = HashMap<String, Agg>()
            for (n in 1..25) {
                val qid = "q%02d".format(n)
                val did = "d%02d".format(n)
                val q = queries[qid] ?: continue
                if (!documents.containsKey(did)) continue
                val ranked = docIds.sortedByDescending { cosine(q, documents.getValue(it)) }
                val rank = ranked.indexOf(did) + 1
                for (agg in listOf(overall, byLang.getOrPut(langById[qid] ?: "unknown") { Agg() })) {
                    agg.n++
                    if (rank == 1) agg.hits1++
                    if (rank <= 5) agg.hits5++
                    agg.rr += 1.0 / rank
                }
            }
            fun JSONObject.fill(a: Agg): JSONObject = put("n", a.n)
                .put("recall_at_1", round6(a.hits1.toDouble() / a.n))
                .put("recall_at_5", round6(a.hits5.toDouble() / a.n))
                .put("mrr", round6(a.rr / a.n))
            val retrieval = JSONObject().put("overall", JSONObject().fill(overall))
            for ((lang, agg) in byLang) retrieval.put(lang, JSONObject().fill(agg))

            // ---- 确定性：5 条重复编码逐位一致 + 向量指纹 ----
            val probeIds = queries.keys.sorted().take(5)
            var bitIdentical = true
            for (id in probeIds) {
                val again = provider.embedQuery(sampleText(samples, id))
                if (!again.contentEquals(queries.getValue(id))) bitIdentical = false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for (id in queries.keys.sorted()) {
                val v = queries.getValue(id)
                val bb = java.nio.ByteBuffer.allocate(v.size * 4)
                bb.asFloatBuffer().put(v)
                digest.update(bb.array())
            }
            val vectorsSha = digest.digest().joinToString("") { "%02x".format(it) }

            // ---- 长度×延迟 + 序列上限探针 ----
            val lengthRows = JSONArray()
            for (len in MEASURE_LENGTHS) {
                val text = cjkText(len)
                repeat(2) { provider.embedQuery(text) } // 预热
                val times = (1..7).map {
                    val s = System.currentTimeMillis()
                    provider.embedQuery(text)
                    System.currentTimeMillis() - s
                }.sorted()
                lengthRows.put(
                    JSONObject().put("chars", len)
                        .put("p50_ms", times[3]).put("p95_ms", times.last())
                )
            }
            val seqProbes = JSONArray()
            for (len in SEQ_PROBE_LENGTHS) {
                val row = JSONObject().put("chars", len)
                runCatching { provider.embedQuery(cjkText(len)) }
                    .onSuccess { row.put("ok", true) }
                    .onFailure { row.put("ok", false).put("error", it.message?.take(300) ?: it.javaClass.simpleName) }
                seqProbes.put(row)
            }
            val firstFail = (0 until seqProbes.length())
                .map { seqProbes.getJSONObject(it) }
                .firstOrNull { !it.getBoolean("ok") }
            val seqLimit = if (firstFail == null) "≥${SEQ_PROBE_LENGTHS.last()}" else
                "<${firstFail.getInt("chars")}（${firstFail.optString("error").take(120)}）"

            // ---- 判定 ----
            val retrievalPass = overall.n > 0 &&
                overall.hits1.toDouble() / overall.n >= RETRIEVAL_RECALL1_GATE
            val g1bPass = retrievalPass && bitIdentical && dims > 0

            val out = JSONObject()
                .put("experiment", experiment)
                .put("model_file", modelFile.name)
                .put("provider", providerId)
                .put("policies", JSONObject()
                    .put("query", provider.descriptor.queryTaskTemplate)
                    .put("document", provider.descriptor.documentTaskTemplate))
                .put("init", initMs)
                .put("dimensions", dims)
                .put("retrieval", retrieval)
                .put("determinism", JSONObject()
                    .put("in_session_bit_identical", bitIdentical)
                    .put("query_vectors_sha256", vectorsSha))
                .put("length_latency", lengthRows)
                .put("seq_limit_probes", seqProbes)
                .put("seq_limit", seqLimit)
                .put("verdict", JSONObject()
                    .put("retrieval_gate", RETRIEVAL_RECALL1_GATE)
                    .put("retrieval_pass", retrievalPass)
                    .put("determinism_pass", bitIdentical)
                    .put("g1b_pass", g1bPass))

            val dir = File(appContext.filesDir, "g1b_results").apply { mkdirs() }
            val outFile = File(dir, "${experiment}_${modelFile.name}.json")
            outFile.writeText(out.toString(2))
            outFile
        } finally {
            provider.close()
        }
    }

    private fun loadSamples(appContext: Context): JSONArray? {
        val standalone = File(appContext.filesDir, "g1/samples.json")
        if (standalone.isFile) {
            val txt = standalone.readText()
            return runCatching { JSONObject(txt).optJSONArray("samples") }.getOrNull()
                ?: runCatching { JSONArray(txt) }.getOrNull()
        }
        val ref = File(appContext.filesDir, "g1/reference.json")
        if (ref.isFile) return JSONObject(ref.readText()).optJSONArray("samples")
        return null
    }

    private fun sampleText(samples: JSONArray, id: String): String {
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            if (s.getString("id") == id) return s.getString("text")
        }
        throw IllegalArgumentException("sample $id not found")
    }

    /** CJK 占比启发式：≥0.30 记 zh，>0.05 记 mix，否则 en（仅用于结果分列，不参与判定） */
    private fun classifyLang(text: String): String {
        val chars = text.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return "en"
        val cjk = chars.count { it.code in 0x4E00..0x9FFF }
        val ratio = cjk.toDouble() / chars.length
        return when {
            ratio >= 0.30 -> "zh"
            ratio > 0.05 -> "mix"
            else -> "en"
        }
    }

    private fun cjkText(len: Int): String = buildString {
        val base = "知识检索系统需要在端侧设备上高效运行，向量缓存与分块策略共同决定最终的用户体验。"
        while (length < len) append(base)
        setLength(len)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        return if (na == 0.0 || nb == 0.0) Double.NaN else dot / (sqrt(na) * sqrt(nb))
    }

    private fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6
}
