package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.search.SearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 检索验收/评测无头入口（W2 验收 + W3 200 条评测集地基）。
 *
 * 输入（B3-10 统一）：主 `filesDir/search/queries.json`，兼容 `filesDir/eval/evalset.json`；
 * 支持 `{"queries":[...]}` / `{"queries":[{"q":...}]}` / 裸数组三种 schema（解析见
 * [EvalQueryInput]，纯函数可单测）。缺失时错误信息列出全部尝试路径。
 * 行为：逐条调用 SearchService.search（topK 可选），记录命中/名次/通道/延迟/拒绝原因
 * 输出：filesDir/search_results/search_eval_<ts>.json（字段只增不改，scripts 兼容）
 *
 * 自动化：am start --ez autostart_search_eval true（仅 debuggable 构建）
 */
object SearchEvalRunner {

    /**
     * **data-reviewer 单测入口**：从评测文件字节解析查询文本列表。
     * 支持三种 schema（`{"queries":[str]}` / `{"queries":[{"q":...}]}` / 裸数组）。
     */
    internal fun parseQueries(bytes: ByteArray): List<String> =
        EvalQueryInput.parseQueries(bytes)

    suspend fun run(context: Context, topK: Int = 10): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val inputFile = EvalQueryInput.resolve(appContext)
        val queries = runCatching { parseQueries(inputFile.readBytes()) }
            .getOrElse {
                throw IllegalArgumentException(
                    "解析评测输入失败（${inputFile.absolutePath}）：${it.message}",
                    it,
                )
            }

        val service = SearchService(appContext)
        val rows = JSONArray()
        var hitsTotal = 0
        var refusedCount = 0
        for (q in queries) {
            val row = JSONObject().put("query", q)
            runCatching { service.search(q, topK) }
                .onSuccess { r ->
                    row.put("elapsed_ms", r.elapsedMs)
                        .put("space", r.spaceId)
                        .put("model", r.modelId)
                        .put("vector_candidates", r.vectorCandidates)
                        .put("keyword_candidates", r.keywordCandidates)
                    if (r.refused != null) {
                        row.put("refused", r.refused)
                        refusedCount++
                    } else {
                        val hitsArr = JSONArray()
                        r.hits.forEach { h ->
                            hitsArr.put(
                                JSONObject()
                                    .put("doc", h.docTitle)
                                    .put("ordinal", h.ordinal)
                                    .put("char", "${h.charStart}-${h.charEnd}")
                                    .put("channels", h.channels)
                                    .put("v_rank", h.vectorRank ?: JSONObject.NULL)
                                    .put("k_rank", h.keywordRank ?: JSONObject.NULL)
                                    .put("rrf", h.fusedScore)
                                    .put("snippet", h.content.take(60)),
                            )
                        }
                        row.put("hits", hitsArr)
                        hitsTotal += r.hits.size
                    }
                }
                .onFailure { row.put("error", "${it.javaClass.simpleName}: ${it.message}") }
            rows.put(row)
        }

        val out = JSONObject()
            .put("experiment", "search_eval")
            .put("top_k", topK)
            .put("n_queries", queries.size)
            .put("total_hits", hitsTotal)
            .put("refused", refusedCount)
            // B3-10：记录实际使用的输入路径，便于验收排查（additive，不影响 scripts）
            .put("input_file", inputFile.absolutePath)
            .put("rows", rows)
        val dir = File(appContext.filesDir, "search_results").apply { mkdirs() }
        val outFile = File(dir, "search_eval_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
