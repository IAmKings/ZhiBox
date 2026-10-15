package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.embedding.TaskTemplatePolicy
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * G1 判定门（实施计划 P0-3）：端侧三模板模式 × HF 双模式参考向量的余弦对照。
 *
 * 判定逻辑（对齐实施计划标准"任一方式在 query 与 document 两组参考上均 ≥ 0.99"）：
 * - scheme = app-prefix：DOCUMENT_PREFIX 编码 document 样本对照 document_mode 参考，
 *   且 QUERY_PREFIX 编码 query 样本对照 query_mode 参考（两侧 min ≥ 0.99）
 * - scheme = runtime-default：RAW 编码分别对照两组参考（若运行时自身已区分任务，此项通过）
 *
 * 输入（adb run-as 投放）：filesDir/g1/samples.json（含在 reference.json 内）、reference.json
 * 输出：filesDir/g1_results/g1_<model>_<ts>.json
 */
object G1Comparison {

    private const val THRESHOLD = 0.99
    private val POLICIES = listOf(
        Triple("RAW", TaskTemplatePolicy.RAW, null),
        Triple("DOCUMENT_PREFIX", TaskTemplatePolicy.DOCUMENT_PREFIX, null),
        Triple("QUERY_PREFIX", TaskTemplatePolicy.QUERY_PREFIX, null),
    )

    suspend fun run(context: Context, modelFile: File): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val ref = JSONObject(File(appContext.filesDir, "g1/reference.json").readText())
        val samples = ref.getJSONArray("samples")
        val refVectors = ref.getJSONObject("vectors")
        val refCache = HashMap<String, Pair<FloatArray, FloatArray>>(samples.length())
        for (i in 0 until samples.length()) {
            val id = samples.getJSONObject(i).getString("id")
            val obj = refVectors.getJSONObject(id)
            refCache[id] = toArray(obj.getJSONArray("query_mode")) to
                toArray(obj.getJSONArray("document_mode"))
        }

        val rows = JSONArray()
        // (variant, refMode, sampleRole) -> cosines
        val buckets = HashMap<String, MutableList<Double>>()

        for ((name, policy, _) in POLICIES) {
            val provider = UniversalEmbedderProvider(appContext, modelFile, policy, useGpu = false)
            try {
                for (i in 0 until samples.length()) {
                    val s = samples.getJSONObject(i)
                    val id = s.getString("id")
                    val role = s.getString("role")
                    val emb = provider.embedQuery(s.getString("text"))
                    val (refQ, refD) = refCache.getValue(id)
                    val cosQ = cosine(emb, refQ)
                    val cosD = cosine(emb, refD)
                    rows.put(
                        JSONObject()
                            .put("id", id).put("role", role).put("variant", name)
                            .put("cos_vs_query_mode", round6(cosQ))
                            .put("cos_vs_document_mode", round6(cosD))
                    )
                    buckets.getOrPut("$name|query_mode|$role") { mutableListOf() }.add(cosQ)
                    buckets.getOrPut("$name|document_mode|$role") { mutableListOf() }.add(cosD)
                }
            } finally {
                provider.close()
            }
        }

        fun agg(variant: String, refMode: String, role: String): JSONObject? {
            val list = buckets["$variant|$refMode|$role"] ?: return null
            val mean = list.average()
            val min = list.min()
            return JSONObject()
                .put("variant", variant).put("ref_mode", refMode).put("role", role)
                .put("n", list.size)
                .put("mean", round6(mean)).put("min", round6(min))
        }

        val aggregates = JSONArray()
        for ((name, _, _) in POLICIES) {
            for (refMode in listOf("query_mode", "document_mode")) {
                for (role in listOf("query", "document")) {
                    agg(name, refMode, role)?.let { aggregates.put(it) }
                }
            }
        }

        // 两个候选方案的判定
        val appPrefixDocMin = buckets["DOCUMENT_PREFIX|document_mode|document"]?.min()
        val appPrefixQueryMin = buckets["QUERY_PREFIX|query_mode|query"]?.min()
        val rawDocMin = buckets["RAW|document_mode|document"]?.min()
        val rawQueryMin = buckets["RAW|query_mode|query"]?.min()

        val verdictAppPrefix = (appPrefixDocMin ?: -1.0) >= THRESHOLD &&
            (appPrefixQueryMin ?: -1.0) >= THRESHOLD
        val verdictRuntimeDefault = (rawDocMin ?: -1.0) >= THRESHOLD &&
            (rawQueryMin ?: -1.0) >= THRESHOLD

        val out = JSONObject()
            .put("experiment", "G1_task_template")
            .put("model_file", modelFile.name)
            .put("threshold", THRESHOLD)
            .put("reference", JSONObject()
                .put("model_id", ref.optString("model_id"))
                .put("revision", ref.optString("revision"))
                .put("dim", ref.optInt("dim")))
            .put("aggregates", aggregates)
            .put("verdict", JSONObject()
                .put("scheme_app_prefix", JSONObject()
                    .put("pass", verdictAppPrefix)
                    .put("doc_side_min", round6(appPrefixDocMin ?: -1.0))
                    .put("query_side_min", round6(appPrefixQueryMin ?: -1.0)))
                .put("scheme_runtime_default", JSONObject()
                    .put("pass", verdictRuntimeDefault)
                    .put("doc_side_min", round6(rawDocMin ?: -1.0))
                    .put("query_side_min", round6(rawQueryMin ?: -1.0)))
                .put("g1_pass", verdictAppPrefix || verdictRuntimeDefault))
            .put("rows", rows)

        val dir = File(appContext.filesDir, "g1_results").apply { mkdirs() }
        val outFile = File(dir, "g1_${modelFile.name}_v1.json")
        outFile.writeText(out.toString(2))
        outFile
    }

    private fun toArray(json: JSONArray): FloatArray = FloatArray(json.length()) { json.getDouble(it).toFloat() }

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
