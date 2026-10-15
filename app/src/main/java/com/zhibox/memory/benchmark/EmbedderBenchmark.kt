package com.zhibox.memory.benchmark

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.zhibox.memory.core.embedding.TaskTemplatePolicy
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * P0-2 资源画像基准（实施计划 P0-2）：
 * 模型冷加载、热推理 p50/p95、峰值 RSS（VmHWM）、维度/有限值/归一化校验。
 * 结果导出 JSON 至应用私有目录 filesDir/benchmark/，便于 adb pull 归档到
 * docs/PHASE0_REPORT.md。
 */
object EmbedderBenchmark {

    private const val WARM_RUNS = 30

    /** 中英混合样本，与后续 G1 判定门的样本集保持同源 */
    val SAMPLE_TEXTS = listOf(
        "MQTT 是一种基于发布订阅模式的消息协议。",
        "蓝牙断线后客户端应执行受限重连，并在连接状态变更后更新连接状态机。",
        "异分母分数相加时，先将分数通分为相同分母，再把分子相加。",
        "The quick brown fox jumps over the lazy dog.",
        "Kotlin coroutines use structured concurrency to cancel child jobs.",
        "向量检索在个人知识库中用于语义召回。",
        "2026-10-15 上海 天气 多云 气温 22 度",
        "def add(a, b): return a + b",
    )

    data class Report(
        val modelFile: String,
        val modelBytes: Long,
        val policy: String,
        val coldLoadMs: Long,
        val dimensions: Int,
        val allFinite: Boolean,
        val l2NormDelta: Double,
        val selfCosine: Double,
        val warmSamples: Int,
        val warmP50Ms: Double,
        val warmP95Ms: Double,
        val firstEmbedMs: Double,
        val rssBaselineKb: Long,
        val peakRssKb: Long,
        val deviceModel: String,
        val socModel: String,
        val androidVersion: String,
        val timestamp: String,
        val error: String? = null,
    )

    suspend fun run(
        context: Context,
        modelFile: File,
        policy: TaskTemplatePolicy = TaskTemplatePolicy.RAW,
        useGpu: Boolean = false,
    ): Report = withContext(Dispatchers.Default) {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val base = baseReport(modelFile, policy, ts)
        val baselineHwm = readVmHwmKb()
        try {
            val t0 = SystemClock.elapsedRealtime()
            val provider = UniversalEmbedderProvider(
                context, modelFile,
                queryPolicy = policy,
                useGpu = useGpu,
            )
            val coldLoadMs = SystemClock.elapsedRealtime() - t0
            try {
                val firstStart = SystemClock.elapsedRealtime()
                val first = provider.embedQuery(SAMPLE_TEXTS.first())
                val firstEmbedMs = (SystemClock.elapsedRealtime() - firstStart).toDouble()

                val dims = first.size
                val allFinite = first.all { it.isFinite() }
                val norm = sqrt(first.map { it * it }.sum())
                val l2Delta = abs(norm - 1.0)
                val second = provider.embedQuery(SAMPLE_TEXTS[1])
                val selfCos = cosine(first, first)

                val latencies = ArrayList<Double>(WARM_RUNS)
                repeat(WARM_RUNS) { i ->
                    val s = SystemClock.elapsedRealtime()
                    provider.embedQuery(SAMPLE_TEXTS[i % SAMPLE_TEXTS.size])
                    latencies.add((SystemClock.elapsedRealtime() - s).toDouble())
                }
                val sorted = latencies.sorted()
                val peakRss = readVmHwmKb()

                Report(
                    modelFile = modelFile.name,
                    modelBytes = modelFile.length(),
                    policy = policy.name,
                    coldLoadMs = coldLoadMs,
                    dimensions = dims,
                    allFinite = allFinite,
                    l2NormDelta = l2Delta,
                    selfCosine = selfCos,
                    warmSamples = WARM_RUNS,
                    warmP50Ms = percentile(sorted, 0.50),
                    warmP95Ms = percentile(sorted, 0.95),
                    firstEmbedMs = firstEmbedMs,
                    rssBaselineKb = baselineHwm,
                    peakRssKb = peakRss,
                    deviceModel = Build.MODEL ?: "unknown",
                    socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else "",
                    androidVersion = Build.VERSION.RELEASE ?: "?",
                    timestamp = ts,
                ).also { second.size } // second 仅为再入验证保留
            } finally {
                provider.close()
            }
        } catch (t: Throwable) {
            base.copy(
                rssBaselineKb = baselineHwm,
                peakRssKb = readVmHwmKb(),
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun baseReport(modelFile: File, policy: TaskTemplatePolicy, ts: String) = Report(
        modelFile = modelFile.name,
        modelBytes = modelFile.length(),
        policy = policy.name,
        coldLoadMs = -1,
        dimensions = -1,
        allFinite = false,
        l2NormDelta = -1.0,
        selfCosine = Double.NaN,
        warmSamples = 0,
        warmP50Ms = -1.0,
        warmP95Ms = -1.0,
        firstEmbedMs = -1.0,
        rssBaselineKb = -1,
        peakRssKb = -1,
        deviceModel = Build.MODEL ?: "unknown",
        socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else "",
        androidVersion = Build.VERSION.RELEASE ?: "?",
        timestamp = ts,
    )

    fun exportJson(context: Context, report: Report): File {
        val dir = File(context.filesDir, "benchmark").apply { mkdirs() }
        val json = JSONObject().apply {
            put("model_file", report.modelFile)
            put("model_bytes", report.modelBytes)
            put("policy", report.policy)
            put("cold_load_ms", report.coldLoadMs)
            put("dimensions", report.dimensions)
            put("all_finite", report.allFinite)
            put("l2_norm_delta", report.l2NormDelta)
            put("self_cosine", if (report.selfCosine.isNaN()) JSONObject.NULL else report.selfCosine)
            put("warm_samples", report.warmSamples)
            put("warm_p50_ms", report.warmP50Ms)
            put("warm_p95_ms", report.warmP95Ms)
            put("first_embed_ms", report.firstEmbedMs)
            put("rss_baseline_kb", report.rssBaselineKb)
            put("peak_rss_kb", report.peakRssKb)
            put("device_model", report.deviceModel)
            put("soc_model", report.socModel)
            put("android_version", report.androidVersion)
            put("timestamp", report.timestamp)
            put("error", report.error ?: JSONObject.NULL)
        }
        val outFile = File(dir, "bench_${report.modelFile}_${System.currentTimeMillis()}.json")
        outFile.writeText(json.toString(2))
        return outFile
    }

    private fun readVmHwmKb(): Long = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
        }
    } catch (_: Exception) {
        -1L
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        return if (na == 0.0 || nb == 0.0) Double.NaN else dot / (sqrt(na) * sqrt(nb))
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return -1.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
