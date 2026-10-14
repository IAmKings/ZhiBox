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
import kotlinx.coroutines.launch
import java.io.File

/**
 * P0-2 基准界面：扫描设备上的模型文件（外部私有目录 models/ 或内部 filesDir/models/，
 * 均经 adb push 放入，应用保持零权限），逐个执行 RAW/CPU 基准并导出 JSON。
 *
 * 自动化入口：
 *   adb shell am start -n com.zhibox.memory/.MainActivity --ez autostart_benchmark true
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    autoStart: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { discoverModels(context) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var reports by remember { mutableStateOf(listOf<EmbedderBenchmark.Report>()) }
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

    LaunchedEffect(autoStart) {
        if (autoStart && !started && models.isNotEmpty()) runAll()
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
                TextButton(onClick = onBack, enabled = !running) { Text("返回") }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium)

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
