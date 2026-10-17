package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.search.SearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * W3 评测集跑批（200+ 条五类标注查询）：逐条输出三通道（向量/关键词/融合）文档级 Top-10
 * 原始排名 + 延迟；指标计算（R@5/10、MRR@10、NDCG@10）在宿主机 scripts/score_eval.py。
 *
 * 输入（B3-10 与 [SearchEvalRunner] 统一）：主 `filesDir/eval/evalset.json`，兼容
 * `filesDir/search/queries.json`（此时条目补默认元数据 id=qN / type=unknown / lang=unknown /
 * relevant=[]）；解析与路径候选统一走 [EvalQueryInput]。
 * 输出：filesDir/eval_results/evalrun_<ts>.json（字段只增不改，scripts/score_eval.py 兼容）
 *
 * 自动化：am start --ez autostart_eval true（仅 debuggable 构建）
 */
object EvalSetRunner {

    /** **data-reviewer 单测入口**：从评测文件字节解析带元数据的条目（三种 schema 均支持）。 */
    internal fun parseEvalQueries(bytes: ByteArray): List<EvalQuery> =
        EvalQueryInput.parseEvalQueries(bytes)

    suspend fun run(context: Context, topK: Int = 10): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val inputFile = EvalQueryInput.resolve(appContext, preferred = "eval/evalset.json")
        val queries = runCatching { parseEvalQueries(inputFile.readBytes()) }
            .getOrElse {
                throw IllegalArgumentException(
                    "解析评测输入失败（${inputFile.absolutePath}）：${it.message}",
                    it,
                )
            }

        val service = SearchService(appContext)
        val rows = JSONArray()
        var refused = 0
        var totalMs = 0L
        for (query in queries) {
            val row = JSONObject()
                .put("id", query.id)
                .put("q", query.text)
                .put("type", query.type)
                .put("lang", query.lang)
                .put("relevant", JSONArray(query.relevant))
            val t0 = System.currentTimeMillis()
            val (ranking, refuse) = service.searchChannels(query.text, topK)
            val ms = System.currentTimeMillis() - t0
            totalMs += ms
            if (refuse != null) {
                row.put("refused", refuse)
                refused++
            } else {
                row.put("ms", ms)
                    .put("vector_docs", JSONArray(ranking.vectorDocs))
                    .put("keyword_docs", JSONArray(ranking.keywordDocs))
                    .put("fused_docs", JSONArray(ranking.fusedDocs))
            }
            rows.put(row)
        }

        val out = JSONObject()
            .put("experiment", "eval_set")
            .put("top_k", topK)
            .put("n_queries", queries.size)
            .put("refused", refused)
            .put("total_ms", totalMs)
            .put("avg_ms", if (queries.isNotEmpty()) totalMs / queries.size else 0)
            // B3-10：记录实际使用的输入路径（additive，不影响 scripts）
            .put("input_file", inputFile.absolutePath)
            .put("rows", rows)
        val dir = File(appContext.filesDir, "eval_results").apply { mkdirs() }
        val outFile = File(dir, "evalrun_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
