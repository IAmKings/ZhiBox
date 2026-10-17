package com.zhibox.memory.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 评测/检索验收输入统一（B3-10）。
 *
 * 历史上有两份输入、两套解析：
 * - `files/search/queries.json`（`SearchEvalRunner`：W2 检索验收，仅查询文本）；
 * - `files/eval/evalset.json`（`EvalSetRunner`：W3 200+ 条标注评测集，带 id/type/lang/relevant）。
 *
 * 现在两个 runner 共用本对象：
 * 1. 路径解析统一：主 `search/queries.json`，兼容 `eval/evalset.json`；缺失时错误信息列出全部尝试路径；
 * 2. schema 统一，三种形态都接受：
 *    - `{"queries": ["q1", "q2"]}`；
 *    - `{"queries": [{"q": "q1", "id": "...", ...}]}`；
 *    - 裸数组 `["q1"]` / `[{"q": "q1"}]`；
 * 3. 纯解析函数（[parseQueries] / [parseEvalQueries]）不依赖 Android，可被 data-reviewer 单测。
 *
 * 注：本文件位于 **debug source set**，release 包不含评测 runner/解析逻辑。
 */
internal data class EvalQuery(
    val id: String,
    val text: String,
    val type: String = "unknown",
    val lang: String = "unknown",
    val relevant: List<String> = emptyList(),
)

internal object EvalQueryInput {

    /** 输入候选路径（相对 filesDir，按优先级排序）。scripts/run_search_eval.sh 用第一个。 */
    internal val candidatePaths: List<String> = listOf(
        "search/queries.json",
        "eval/evalset.json",
    )

    /**
     * 定位评测输入文件：`preferred` 优先，其余候选作为兼容回退；都不存在时抛出
     * **列出全部尝试路径**的错误。
     *
     * - [SearchEvalRunner] 默认主路径 `search/queries.json`；
     * - [EvalSetRunner] 传 `eval/evalset.json`（保持原主路径语义，避免旧 queries.json 抢跑）。
     *
     * 调用方应在 IO 线程调用（本函数只做 `File.isFile` 判断，开销可忽略）。
     */
    internal fun resolve(
        context: Context,
        preferred: String = candidatePaths.first(),
    ): File {
        val filesDir = context.applicationContext.filesDir
        val orderedPaths = listOf(preferred) + candidatePaths.filter { it != preferred }
        val candidates = orderedPaths.map { File(filesDir, it) }
        candidates.firstOrNull { it.isFile }?.let { return it }
        throw IllegalStateException(
            "缺少评测输入文件。已尝试：" +
                candidates.joinToString("、") { it.absolutePath } +
                "（支持 {\"queries\":[\"...\"]}、{\"queries\":[{\"q\":\"...\"}]}、裸数组三种 schema）",
        )
    }

    /**
     * **纯函数**：解析出查询文本列表（[SearchEvalRunner] 使用；data-reviewer 单测入口为
     * `SearchEvalRunner.parseQueries`）。
     */
    internal fun parseQueries(bytes: ByteArray): List<String> =
        parseEvalQueries(bytes).map { it.text }

    /**
     * **纯函数**：解析出带元数据的评测条目；`queries.json` 的纯文本条目补齐默认元数据
     * （id=q1..qN、type/lang=unknown、relevant=[]），供 `EvalSetRunner` 兼容读取。
     *
     * @throws IllegalArgumentException 空文件 / 无法识别的 schema / 0 条有效查询
     */
    internal fun parseEvalQueries(bytes: ByteArray): List<EvalQuery> {
        val text = bytes.toString(Charsets.UTF_8).trim()
        require(text.isNotEmpty()) { "评测输入为空文件" }
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: runCatching { JSONObject(text).optJSONArray("queries") }.getOrNull()
            ?: throw IllegalArgumentException(
                "无法识别的评测输入 schema（期望对象含 queries 数组，或裸数组）",
            )
        val queries = (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is String -> item.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { EvalQuery(id = "q${index + 1}", text = it) }

                is JSONObject -> {
                    val q = item.optString("q").ifBlank { item.optString("query") }.trim()
                    if (q.isEmpty()) {
                        null
                    } else {
                        EvalQuery(
                            id = item.optString("id").ifBlank { "q${index + 1}" },
                            text = q,
                            type = item.optString("type").ifBlank { "unknown" },
                            lang = item.optString("lang").ifBlank { "unknown" },
                            relevant = item.optJSONArray("relevant")?.let { arr ->
                                (0 until arr.length()).map { arr.optString(it) }
                            } ?: emptyList(),
                        )
                    }
                }

                else -> null
            }
        }
        require(queries.isNotEmpty()) { "评测输入无有效查询条目" }
        return queries
    }
}
