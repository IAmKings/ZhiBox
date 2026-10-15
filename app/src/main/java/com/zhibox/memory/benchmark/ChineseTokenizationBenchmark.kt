package com.zhibox.memory.benchmark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.huaban.analysis.jieba.JiebaSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * G3 判定门（实施计划 P0-5）：中文关键词检索三方案对照。
 *
 * 方案：a) RAW——FTS4 原样（unicode61，对照组）；b) BIGRAM——字符二元组预分词；
 *       c) JIEBA——结巴分词预分词（com.huaban.analysis:jieba-analysis，纯 Java）。
 *
 * 语料：filesDir/g3/corpus/ 下的中文 Markdown（本项目规划文档，经 adb 投放），
 *       按空行分段聚合为 ≤800 字符的 chunk（目标 ≥200 chunks）。
 * 查询：50 条中文关键词（词或短语），ground truth = 原文包含全部词（子串）的 chunk 集合。
 * 指标：hit@50（命中任一 GT chunk 的查询占比）、平均返回数、误报率
 *       （返回 chunk 不含任何查询词子串的比例——分词方案的两词分离召回不算误报）。
 * 判定：BIGRAM/JIEBA hit ≥ 0.8 且显著优于 RAW（≥+30pp）且误报 ≤0.3；
 *       BIGRAM 与 JIEBA 命中差距 ≤10pp 时选 BIGRAM（零依赖），否则 JIEBA。
 */
object ChineseTokenizationBenchmark {

    private val QUERIES: List<List<String>> = listOf(
        listOf("向量检索"), listOf("知识图谱"), listOf("任务模板"), listOf("判定门"),
        listOf("先修"), listOf("通分"), listOf("蓝牙"), listOf("重连"),
        listOf("量化"), listOf("余弦相似度"), listOf("掌握度"), listOf("端侧"),
        listOf("离线"), listOf("隐私"), listOf("幂等"), listOf("验收"),
        listOf("里程碑"), listOf("风险"), listOf("基准"), listOf("设备矩阵"),
        listOf("嵌入"), listOf("维度"), listOf("归一化"), listOf("中文分词"),
        listOf("索引重建"), listOf("冷启动"), listOf("峰值内存"), listOf("导入"),
        listOf("知识点"), listOf("学习者"), listOf("错题"), listOf("教材"),
        listOf("版权"), listOf("许可证"), listOf("事件抽取"), listOf("实体"),
        listOf("引用"), listOf("断点续传"), listOf("校验"), listOf("模型下载"),
        listOf("分块"), listOf("检索质量"), listOf("评测集"), listOf("降级策略"),
        listOf("版本锚点"), listOf("拓扑排序"), listOf("先修关系"), listOf("分词"),
        listOf("向量", "门槛"), listOf("模型", "校验"),
    )

    private const val MAX_RESULTS = 50
    private const val CHUNK_TARGET = 600

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val corpusDir = File(appContext.filesDir, "g3/corpus")
        val files = corpusDir.listFiles()?.filter { it.extension == "md" } ?: emptyList()
        check(files.isNotEmpty()) { "缺少 ${corpusDir.path} 语料（先运行 scripts/run_g3_fts.sh）" }

        // ── 语料切块 ──────────────────────────────────────────
        val chunks = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val paras = text.split(Regex("\\n\\s*\\n"))
            val sb = StringBuilder()
            for (p in paras) {
                val t = p.trim()
                if (t.isEmpty()) continue
                if (sb.isNotEmpty() && sb.length + t.length > CHUNK_TARGET) {
                    chunks.add(sb.toString()); sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
                if (sb.length >= CHUNK_TARGET) { chunks.add(sb.toString()); sb.setLength(0) }
            }
            if (sb.isNotEmpty()) chunks.add(sb.toString())
        }
        check(chunks.size >= 200) { "语料不足：仅 ${chunks.size} chunks（需 ≥200）" }

        // ── 三方案索引 ────────────────────────────────────────
        val workDir = File(appContext.filesDir, "g3_results").apply { mkdirs() }
        val dbFile = File(workDir, "fts.db")
        dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val jieba = JiebaSegmenter()

        data class Scheme(val name: String, val tokenize: (String) -> String)

        val schemes = listOf(
            Scheme("RAW") { it.replace(Regex("\\s+"), " ").trim() },
            Scheme("BIGRAM") { text -> bigramize(text).joinToString(" ") },
            Scheme("JIEBA") { text ->
                jieba.sentenceProcess(text.replace(Regex("\\s+"), " "))
                    .joinToString(" ") { t -> t.trim() }.filter { !it.isWhitespace() }.split(" ")
                    .filter { it.isNotBlank() }.joinToString(" ")
            },
        )

        val buildInfo = JSONArray()
        for (s in schemes) {
            db.execSQL("DROP TABLE IF EXISTS fts_${s.name}")
            db.execSQL("CREATE VIRTUAL TABLE fts_${s.name} USING fts4(content)")
            val t0 = SystemClock.elapsedRealtime()
            db.beginTransaction()
            val ins = db.compileStatement("INSERT INTO fts_${s.name}(content) VALUES(?)")
            for (c in chunks) { ins.bindString(1, s.tokenize(c)); ins.executeInsert() }
            db.setTransactionSuccessful(); db.endTransaction()
            val size = db.rawQuery(
                "SELECT SUM(LENGTH(content)) FROM fts_${s.name}", null
            ).use { it.moveToFirst(); it.getLong(0) }
            buildInfo.put(JSONObject()
                .put("scheme", s.name)
                .put("build_ms", SystemClock.elapsedRealtime() - t0)
                .put("content_bytes", size))
        }
        val rawBytes = buildInfo.getJSONObject(0).getLong("content_bytes")

        // ── 查询对照 ──────────────────────────────────────────
        val results = JSONArray()
        for (s in schemes) {
            var hits = 0
            var totalReturned = 0
            var falsePositives = 0
            var emptyGt = 0
            val perQuery = JSONArray()
            for (terms in QUERIES) {
                val gt = chunks.indices.filter { i -> terms.all { chunks[i].contains(it) } }
                if (gt.isEmpty()) { emptyGt++; perQuery.put(-1); continue }
                val qTokens = s.tokenize(terms.joinToString(" "))
                    .split(" ").filter { it.isNotBlank() }
                val matchExpr = qTokens.joinToString(" ") { sanitize(it) }
                val returned = ArrayList<Int>()
                if (matchExpr.isNotBlank()) {
                    db.rawQuery(
                        "SELECT rowid FROM fts_${s.name} WHERE fts_${s.name} MATCH ? LIMIT $MAX_RESULTS",
                        arrayOf("\"$matchExpr\""),
                    ).use { c -> while (c.moveToNext()) returned.add(c.getInt(0) - 1) }
                }
                val hit = returned.any { it in gt }
                if (hit) hits++
                totalReturned += returned.size
                falsePositives += returned.count { idx ->
                    terms.none { chunks.getOrNull(idx)?.contains(it) == true }
                }
                perQuery.put(if (hit) 1 else 0)
            }
            val validQueries = QUERIES.size - emptyGt
            results.put(JSONObject()
                .put("scheme", s.name)
                .put("valid_queries", validQueries)
                .put("hit_rate", round4(hits.toDouble() / validQueries))
                .put("avg_results", round2(totalReturned.toDouble() / validQueries))
                .put("false_positive_rate", round4(
                    if (totalReturned == 0) 0.0 else falsePositives.toDouble() / totalReturned))
                .put("index_bloat", round2(
                    buildInfo.getJSONObject(schemes.indexOf(s)).getLong("content_bytes").toDouble() /
                        rawBytes.toDouble().coerceAtLeast(1.0))))
        }

        // ── 判定 ──────────────────────────────────────────────
        fun scheme(name: String) = (0 until results.length()).map { results.getJSONObject(it) }
            .first { it.getString("scheme") == name }
        val raw = scheme("RAW"); val bigram = scheme("BIGRAM"); val jiebaR = scheme("JIEBA")
        val hitOk = { x: JSONObject -> x.getDouble("hit_rate") >= 0.8 }
        val improvement = { x: JSONObject -> x.getDouble("hit_rate") - raw.getDouble("hit_rate") >= 0.30 }
        val fpOk = { x: JSONObject -> x.getDouble("false_positive_rate") <= 0.3 }
        val bigramQualified = hitOk(bigram) && improvement(bigram) && fpOk(bigram)
        val jiebaQualified = hitOk(jiebaR) && improvement(jiebaR) && fpOk(jiebaR)
        val chosen = when {
            bigramQualified &&
                bigram.getDouble("hit_rate") >= jiebaR.getDouble("hit_rate") - 0.10 -> "BIGRAM"
            jiebaQualified -> "JIEBA"
            else -> "NONE"
        }
        val g3Pass = chosen != "NONE"

        val out = JSONObject()
            .put("experiment", "G3_chinese_tokenization")
            .put("corpus_files", files.size).put("chunks", chunks.size)
            .put("queries", QUERIES.size)
            .put("build", buildInfo)
            .put("results", results)
            .put("verdict", JSONObject()
                .put("raw_hit", raw.getDouble("hit_rate"))
                .put("bigram_hit", bigram.getDouble("hit_rate"))
                .put("jieba_hit", jiebaR.getDouble("hit_rate"))
                .put("bigram_qualified", bigramQualified)
                .put("jieba_qualified", jiebaQualified)
                .put("chosen_scheme", chosen)
                .put("g3_pass", g3Pass))
        db.close()
        val outFile = File(workDir, "fts_bench_v1.json")
        outFile.writeText(out.toString(2))
        outFile
    }

    /** 全部字符 bigram（跳过空白与换行；ASCII 词保持原词 + 内部 bigram） */
    private fun bigramize(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), "")
        val grams = ArrayList<String>(clean.length * 2)
        var i = 0
        while (i < clean.length) {
            if (i + 1 < clean.length) grams.add(clean.substring(i, i + 2))
            i++
        }
        return grams
    }

    /** FTS4 MATCH 词法安全化：仅保留非引号字符 */
    private fun sanitize(token: String) = token.replace("\"", "")

    private fun round4(v: Double) = Math.round(v * 10000) / 10000.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
