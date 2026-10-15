package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import com.zhibox.memory.benchmark.ChineseTokenizationBenchmark
import com.zhibox.memory.benchmark.EmbedderBenchmark
import com.zhibox.memory.benchmark.G1Comparison
import com.zhibox.memory.benchmark.VectorScanBenchmark
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phase 0 实验界面：P0-2 资源基准、P0-3 G1 判定门、P0-5 G3 中文分词、P0-6 G4 精确扫描。
 *
 * 自动化入口：--ez autostart_benchmark / autostart_g1 / autostart_scan / autostart_g3 true
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(
    autoStart: Boolean = false,
    autoStartG1: Boolean = false,
    autoStartScan: Boolean = false,
    autoStartG3: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { discoverModels(context) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var g1Started by remember { mutableStateOf(false) }
    var scanStarted by remember { mutableStateOf(false) }
    var g3Started by remember { mutableStateOf(false) }
    var reports by remember { mutableStateOf(listOf<EmbedderBenchmark.Report>()) }
    var g1Summary by remember { mutableStateOf(listOf<String>()) }
    var scanSummary by remember { mutableStateOf(listOf<String>()) }
    var g3Summary by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("待运行") }

    fun runAll() {
        if (running || models.isEmpty()) return
        running = true
        started = true
        scope.launch {
            val collected = mutableListOf<EmbedderBenchmark.Report>()
            for (m in models) {
                status = "运行中: ${m.name}"
                val r = EmbedderBenchmark.run(context, m)
                EmbedderBenchmark.exportJson(context, r)
                collected += r
                reports = collected.toList()
            }
            status = "完成 ${collected.size} 个模型；报告已导出 filesDir/benchmark/"
            running = false
        }
    }

    fun runG1() {
        if (running || models.isEmpty()) return
        val hasRef = File(context.filesDir, "g1/reference.json").isFile
        if (!hasRef) {
            status = "缺少 filesDir/g1/reference.json（先运行 scripts/run_g1_experiment.sh）"
            return
        }
        running = true
        g1Started = true
        scope.launch {
            val lines = mutableListOf<String>()
            for (m in models) {
                status = "G1 对照中: ${m.name}"
                // 单模型失败（如 270M 结构不支持）不阻断其余模型
                runCatching { G1Comparison.run(context, m) }
                    .onSuccess { out ->
                        val v = org.json.JSONObject(out.readText()).getJSONObject("verdict")
                        val pass = v.getBoolean("g1_pass")
                        val appPrefix = v.getJSONObject("scheme_app_prefix")
                        lines += "${m.name}: G1_${if (pass) "PASS" else "FAIL"}" +
                            "（app-prefix min: doc=${appPrefix.getDouble("doc_side_min")} " +
                            "query=${appPrefix.getDouble("query_side_min")}）"
                    }
                    .onFailure {
                        lines += "${m.name}: 运行失败 ${it.message?.lineSequence()?.first() ?: it.javaClass.simpleName}"
                    }
                g1Summary = lines.toList()
            }
            status = "G1 完成；结果已导出 filesDir/g1_results/"
            running = false
        }
    }

    fun runScan() {
        if (running) return
        running = true
        scanStarted = true
        scope.launch {
            runCatching { VectorScanBenchmark.run(context) }
                .onSuccess { out ->
                    val d = org.json.JSONObject(out.readText())
                    val gates = d.getJSONObject("gates")
                    val lines = listOf(
                        "G4_${if (d.getBoolean("g4_pass")) "PASS" else "FAIL"}：" +
                            "1k p95=${gates.getDouble("best_p95_1k")}ms（≤150） " +
                            "10k p95=${gates.getDouble("best_p95_10k")}ms（≤800）",
                        "Top-10 跨实现一致: ${d.getJSONObject("consistency_top10")}",
                    )
                    scanSummary = lines
                }
                .onFailure {
                    scanSummary = listOf("运行失败: ${it.message?.lineSequence()?.first() ?: it.javaClass.simpleName}")
                    status = "G4 扫描基准失败"
                }
            if (status != "G4 扫描基准失败") status = "G4 扫描基准完成；结果在 filesDir/scan_bench/"
            running = false
        }
    }

    LaunchedEffect(autoStart) {
        if (autoStart && !started && models.isNotEmpty()) runAll()
    }

    LaunchedEffect(autoStartG1) {
        if (autoStartG1 && !g1Started && models.isNotEmpty()) runG1()
    }

    fun runG3() {
        if (running) return
        running = true
        g3Started = true
        scope.launch {
            runCatching { ChineseTokenizationBenchmark.run(context) }
                .onSuccess { out ->
                    val d = org.json.JSONObject(out.readText())
                    val v = d.getJSONObject("verdict")
                    g3Summary = listOf(
                        "G3_${if (v.getBoolean("g3_pass")) "PASS" else "FAIL"} " +
                            "选定方案: ${v.getString("chosen_scheme")}",
                        "hit率: raw=${v.getDouble("raw_hit")} bigram=${v.getDouble("bigram_hit")} " +
                            "jieba=${v.getDouble("jieba_hit")}（语料 ${d.getInt("chunks")} chunks）",
                    )
                }
                .onFailure { g3Summary = listOf("运行失败: ${it.message?.lineSequence()?.first()}") }
            status = "G3 完成；结果在 filesDir/g3_results/"
            running = false
        }
    }

    LaunchedEffect(autoStartScan) {
        if (autoStartScan && !scanStarted) runScan()
    }

    LaunchedEffect(autoStartG3) {
        if (autoStartG3 && !g3Started) runG3()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Phase 0 · Embedder 基准") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "设备: ${android.os.Build.MODEL} · ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else ""} · Android ${android.os.Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodySmall
            )
            // FlowRow 自动换行：实验入口增至 5 个后，固定 Row 在窄屏会把尾部按钮挤出可视区
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { runAll() }, enabled = !running && models.isNotEmpty()) {
                    Text(if (running) "运行中…" else "运行基准 (${models.size})")
                }
                Button(onClick = { runG1() }, enabled = !running && models.isNotEmpty()) {
                    Text("G1 对照")
                }
                Button(onClick = { runScan() }, enabled = !running) {
                    Text("G4 扫描")
                }
                Button(onClick = { runG3() }, enabled = !running) {
                    Text("G3 分词")
                }
                TextButton(onClick = onBack, enabled = !running) { Text("返回") }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (g1Summary.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("G1 判定摘要", style = MaterialTheme.typography.titleSmall)
                        g1Summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (scanSummary.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("G4 扫描基准摘要", style = MaterialTheme.typography.titleSmall)
                        scanSummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (g3Summary.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("G3 中文分词摘要", style = MaterialTheme.typography.titleSmall)
                        g3Summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (models.isEmpty()) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("未找到模型文件", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "adb push <model>.litertlm " +
                                "/sdcard/Android/data/com.zhibox.memory/files/models/",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(reports) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.modelFile, style = MaterialTheme.typography.titleSmall)
                            if (r.error != null) {
                                Text("错误: ${r.error}", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("维度 ${r.dimensions} · 冷加载 ${r.coldLoadMs}ms · 首条 ${r.firstEmbedMs}ms")
                                Text("热推理 p50 ${"%.1f".format(r.warmP50Ms)}ms · p95 ${"%.1f".format(r.warmP95Ms)}ms（${r.warmSamples} 次）")
                                Text("L2 偏差 ${"%.2e".format(r.l2NormDelta)} · 自余弦 ${"%.6f".format(r.selfCosine)} · 有限值 ${r.allFinite}")
                                Text("峰值 RSS ${r.peakRssKb / 1024}MB（基线 ${r.rssBaselineKb / 1024}MB）", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun discoverModels(context: Context): List<File> {
    val dirs = listOf(
        File(context.getExternalFilesDir(null), "models"),
        File(context.filesDir, "models"),
    )
    return dirs.filter { it.isDirectory }
        .flatMap { it.listFiles { f -> f.extension == "litertlm" }?.toList() ?: emptyList() }
        .distinctBy { it.name }
        .sortedBy { it.name }
}
