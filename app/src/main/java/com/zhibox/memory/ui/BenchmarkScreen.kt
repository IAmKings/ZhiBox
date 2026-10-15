package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.zhibox.memory.benchmark.EmbedderBenchmark
import com.zhibox.memory.benchmark.G1Comparison
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phase 0 实验界面：P0-2 资源基准 与 P0-3 G1 判定门对照。
 *
 * 自动化入口：
 *   基准: adb shell am start -n com.zhibox.memory/.MainActivity --ez autostart_benchmark true
 *   G1:   adb shell am start -n com.zhibox.memory/.MainActivity --ez autostart_g1 true
 * 输入文件均经 adb run-as 投放到应用私有目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    autoStart: Boolean = false,
    autoStartG1: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { discoverModels(context) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var g1Started by remember { mutableStateOf(false) }
    var reports by remember { mutableStateOf(listOf<EmbedderBenchmark.Report>()) }
    var g1Summary by remember { mutableStateOf(listOf<String>()) }
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

    LaunchedEffect(autoStart) {
        if (autoStart && !started && models.isNotEmpty()) runAll()
    }

    LaunchedEffect(autoStartG1) {
        if (autoStartG1 && !g1Started && models.isNotEmpty()) runG1()
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runAll() }, enabled = !running && models.isNotEmpty()) {
                    Text(if (running) "运行中…" else "运行基准 (${models.size})")
                }
                Button(onClick = { runG1() }, enabled = !running && models.isNotEmpty()) {
                    Text("G1 对照")
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
