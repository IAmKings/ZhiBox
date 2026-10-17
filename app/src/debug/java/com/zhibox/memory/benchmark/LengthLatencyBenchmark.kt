package com.zhibox.memory.benchmark

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 长度 × delegate 延迟矩阵基准（W1 性能归因修正 + GPU 路线评估）：
 * - 长度档：30 字（P0-2 短句基线）/ 400 / 800（真实 chunk 尺寸）/ 2000 字（长块）
 * - 两种 delegate：CPU(XNNPack) 与 GPU；GPU 失败按 R4 预期记录错误不中断 CPU 结果
 * - 每档 2 预热 + 12 计时，报告 mean/p50/p95
 *
 * 输出：filesDir/lenbench/{mode}_v1.json；一键脚本 scripts/run_latency_matrix.sh
 */
object LengthLatencyBenchmark {

    /** 长度阶梯同时探测 1024 token 运行时上限的边界（实测 800字≈1080token 越界） */
    private val LENGTHS = intArrayOf(30, 200, 400, 600, 700, 800)
    private const val WARMUP = 2
    private const val RUNS = 12

    /** 确定性生成目标长度的中文技术散文（按真实 chunk 形态：多句自然段） */
    private fun textOf(targetChars: Int): String {
        val sentences = listOf(
            "向量检索在个人知识库中承担语义召回职责，与关键词检索互补。",
            "导入管线的幂等性保证进程被杀后重跑安全，代价是重复计算的浪费。",
            "任务模板策略由应用层注入，运行时本身不区分查询与文档角色。",
            "精确扫描的复杂度随向量数量线性增长，但预载数组可显著降低常数。",
            "中文关键词检索依赖字符二元组分词，索引膨胀约为原文的二点七倍。",
            "嵌入模型的推理延迟随输入 token 数线性增长，与上下文窗口无关。",
            "WorkManager 唯一链将导入任务串行化，避免多模型实例耗尽内存。",
            "证据回溯要求每个检索结果都能定位到原文的具体字符区间。",
            "The exact scan path remains viable well beyond ten thousand chunks.",
            "模型量化采用感知训练的 INT4 权重，距离计算保持无失真语义。",
        )
        val sb = StringBuilder()
        var i = 0
        while (sb.length < targetChars) {
            sb.append(sentences[i % sentences.size])
            i++
        }
        return sb.substring(0, targetChars.coerceAtMost(sb.length))
    }

    data class Row(
        val chars: Int,
        val tokenEstimate: Int,
        val runs: Int,
        val meanMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
    )

    suspend fun runPass(context: Context, modelFile: File, useGpu: Boolean): File =
        withContext(Dispatchers.Default) {
            val mode = if (useGpu) "gpu" else "cpu"
            val outDir = File(context.filesDir, "lenbench").apply { mkdirs() }
            val rssBefore = readVmHwmKb()
            try {
                val provider = UniversalEmbedderProvider(
                    context, modelFile, useGpu = useGpu,
                )
                val rows = JSONArray()
                var passError: String? = null
                try {
                    provider.use {
                        for (len in LENGTHS) {
                            val text = textOf(len)
                            try {
                                val times = ArrayList<Double>(RUNS)
                                for (r in 0 until WARMUP + RUNS) {
                                    val s = SystemClock.elapsedRealtime()
                                    provider.embedQuery(text)
                                    if (r >= WARMUP) times.add((SystemClock.elapsedRealtime() - s).toDouble())
                                }
                                val sorted = times.sorted()
                                rows.put(JSONObject()
                                    .put("chars", len)
                                    .put("token_estimate", com.zhibox.memory.core.text.Chunker.estimateTokens(text))
                                    .put("runs", RUNS)
                                    .put("mean_ms", round1(times.average()))
                                    .put("p50_ms", round1(sorted[sorted.size / 2]))
                                    .put("p95_ms", round1(sorted[((sorted.size - 1) * 0.95).toInt()])))
                            } catch (t: Throwable) {
                                // 逐长度捕获：越界/单长度失败不丢弃已测成的长度
                                rows.put(JSONObject()
                                    .put("chars", len)
                                    .put("error", "${t.javaClass.simpleName}: ${t.message?.take(300)}"))
                                passError = "length ${len} failed: ${t.message?.lineSequence()?.last()?.take(120)}"
                            }
                        }
                    }
                } finally {
                    runCatching { provider.close() }
                }
                write(outDir, mode, rows, provider.descriptor.dimensions, rssBefore, passError)
            } catch (t: Throwable) {
                write(outDir, mode, JSONArray(), -1, rssBefore,
                    "${t.javaClass.simpleName}: ${t.message?.take(600)}")
            }
        }

    private suspend fun write(
        outDir: File, mode: String, rows: JSONArray, dims: Int,
        rssBefore: Long, error: String?,
    ): File {
        val out = JSONObject()
            .put("experiment", "length_latency_matrix")
            .put("mode", mode)
            .put("model", "embeddinggemma-2-text-vision-440m")
            .put("dimensions", dims)
            .put("warmup", WARMUP).put("runs", RUNS)
            .put("device_model", Build.MODEL)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "")
            .put("android", Build.VERSION.RELEASE)
            .put("rss_before_kb", rssBefore)
            .put("rss_after_kb", readVmHwmKb())
            .put("rows", rows)
            .put("error", error ?: JSONObject.NULL)
        val f = File(outDir, "${mode}_v1.json")
        f.writeText(out.toString(2))
        return f
    }

    private fun readVmHwmKb(): Long = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
        }
    } catch (_: Exception) { -1L }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}
