package com.zhibox.memory.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhibox.memory.AutoStart
import com.zhibox.memory.benchmark.ChineseTokenizationBenchmark
import com.zhibox.memory.benchmark.ConnectivityProbe
import com.zhibox.memory.benchmark.DeleteCascadeRunner
import com.zhibox.memory.benchmark.EmbedderBenchmark
import com.zhibox.memory.benchmark.EvalSetRunner
import com.zhibox.memory.benchmark.FtsRecoveryRunner
import com.zhibox.memory.benchmark.G1Comparison
import com.zhibox.memory.benchmark.LengthLatencyBenchmark
import com.zhibox.memory.benchmark.ProviderVerification
import com.zhibox.memory.benchmark.ProductionBenchRunner
import com.zhibox.memory.benchmark.SearchEvalRunner
import com.zhibox.memory.benchmark.VectorScanBenchmark
import com.zhibox.memory.benchmark.W4PlusRunner
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Phase 0 实验界面（**仅 debug source set**；release 由 stub 顶替，P0-3）。
 * autostart：20 个 `autostart_*` extra 经 [AutoStart] 快照传入，[DebugRun] 路由表分派。
 *
 * B3-6：同构 Card 收敛为 [SectionCard]/[ExperimentCard]/[ReportCard]；整屏单个 LazyColumn
 * （小屏可滚动、报告不再 0 高）；runner JSON 统一走 [runReport]（坏文件转可读摘要）。
 * B3-11：单一自动启动 LaunchedEffect（无 delay 串联）；autoRunStarted 一次性，重建不重放。
 * B3-12：模型扫描 `LaunchedEffect + Dispatchers.IO`，组合期零磁盘 IO。
 * B7：删除 Path B（Granite）/Path C（TextEmbedder/.task）下线入口——PATH_B/G1C 路由与按钮、
 * `PathBVerification` 骨架改为 [ProviderVerification]（仅保留 G1E UE-GPU / G1D NPU 验证）；
 * 对应 legacy extra（autostart_pathb / autostart_g1c）仍保留常量，但本屏不再执行，命中时提示已下线。
 * 现役路由 16 条：BENCHMARK/G1/SCAN/G3/LEN/SEARCH_EVAL/EVAL_SET/DELETE_CHECK/PROD×3/G1D/G1E/PROBE/FTS_RECOVERY/W4_PLUS。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(
    autoStart: AutoStart = AutoStart(),
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // B3-12：组合期零磁盘 IO——模型列表在 IO 上扫描。
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var modelsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { discoverModels(context) }
        modelsLoaded = true
    }

    // 运行状态（P1-8：activeRun 统一表达 running，try/finally 复位）
    var activeRun by rememberSaveable { mutableStateOf<String?>(null) }
    val running = activeRun != null
    var status by rememberSaveable { mutableStateOf("待运行") }
    var autoRunStarted by rememberSaveable { mutableStateOf(false) }
    var reports by remember { mutableStateOf(listOf<EmbedderBenchmark.Report>()) }
    var g1Summary by remember { mutableStateOf(listOf<String>()) }
    var scanSummary by remember { mutableStateOf(listOf<String>()) }
    var g3Summary by remember { mutableStateOf(listOf<String>()) }
    var lenSummary by remember { mutableStateOf(listOf<String>()) }
    var g1DSummary by remember { mutableStateOf(listOf<String>()) }
    var g1ESummary by remember { mutableStateOf(listOf<String>()) }

    /** 统一实验入口：同一时刻只跑一个；取消重抛；异常转文案；finally 必复位。 */
    fun launchExperiment(name: String, block: suspend () -> Unit) {
        if (activeRun != null) return
        activeRun = name
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                status = "$name 失败：${t.readableMessage()}"
            } finally {
                activeRun = null
            }
        }
    }

    /** 一次性 runner 实验的统一骨架（B3-6：状态文案/异常边界不再逐函数复制）。 */
    fun runSimple(name: String, produce: suspend () -> File, done: String) {
        if (running) return
        launchExperiment(name) {
            status = "$name 运行中..."
            try {
                produce()
                status = done
            } catch (e: CancellationException) {
                throw e // 审查修复：runCatching 会吞掉取消，必须重抛（与 launchExperiment 一致）
            } catch (t: Throwable) {
                status = "$name 失败：${t.readableMessage()}"
            }
        }
    }

    // 重建后旧协程已随组合销毁：清理 saveable 的 activeRun，避免整屏永久 disabled。
    LaunchedEffect(Unit) {
        if (activeRun != null) {
            activeRun = null
            status = "上次运行因界面重建中断（可重新运行）"
        }
    }

    /** G1E：EGemma2-740M/440M 通用版 × UniversalEmbedder 通道 × GPU（B7 保留） */
    fun runG1E() {
        if (running) return
        val model = models.firstOrNull { it.name.contains("740m") && !it.name.contains("_") }
            ?: models.firstOrNull { it.name.contains("440m") && !it.name.contains("_") }
            ?: models.firstOrNull { it.name.contains("740m") }
        if (model == null) {
            g1ESummary = listOf("缺少 740m 模型")
            return
        }
        launchExperiment("G1E") {
            status = "G1E 运行中: ${model.name}（UniversalEmbedder + GPU）"
            runReport({
                ProviderVerification.run(context, model, "G1E_ue_gpu") { ctx, f ->
                    com.zhibox.memory.core.embedding.UniversalEmbedderProvider(ctx, f, useGpu = true)
                }
            }) { d ->
                val v = d.getJSONObject("verdict")
                val r = d.getJSONObject("retrieval").getJSONObject("overall")
                val init = d.getJSONArray("init").getJSONObject(0)
                listOf(
                    "G1E_${if (v.getBoolean("g1b_pass")) "PASS" else "FAIL"}：" +
                        "R@1=${r.getDouble("recall_at_1")} · 维度=${d.getInt("dimensions")} · " +
                        "init ${init.getLong("first_ms")}/${init.getLong("second_ms")}ms",
                    latencyLine(d.getJSONArray("length_latency")) +
                        " · 上限: " + d.getString("seq_limit").take(30),
                )
            }.onSuccess {
                g1ESummary = it
                status = "G1E 完成；结果在 filesDir/g1b_results/"
            }.onFailure {
                g1ESummary = listOf("G1E 失败: ${it.readableMessage()}")
                status = "G1E 失败（详见摘要）"
            }
        }
    }

    fun runAll() {
        if (running) return
        if (!modelsLoaded) {
            status = "模型扫描中…"
            return
        }
        if (models.isEmpty()) {
            status = "未找到模型文件（filesDir/models 或 external files/models）"
            return
        }
        launchExperiment("基准") {
            val collected = mutableListOf<EmbedderBenchmark.Report>()
            for (m in models) {
                status = "运行中: ${m.name}"
                // 审查修复：EmbedderBenchmark.run/exportJson 是阻塞磁盘/推理调用，
                // 不能在 Main（rememberCoroutineScope）直接执行；整体放 IO。
                val r = withContext(Dispatchers.IO) {
                    EmbedderBenchmark.run(context, m).also { EmbedderBenchmark.exportJson(context, it) }
                }
                collected += r
                reports = collected.toList()
            }
            status = "完成 ${collected.size} 个模型；报告已导出 filesDir/benchmark/"
        }
    }

    fun runG1() {
        if (running) return
        launchExperiment("G1") {
            val hasRef = withContext(Dispatchers.IO) {
                File(context.filesDir, "g1/reference.json").isFile
            }
            if (!hasRef) {
                status = "缺少 filesDir/g1/reference.json（先运行 scripts/run_g1_experiment.sh）"
                return@launchExperiment
            }
            val lines = mutableListOf<String>()
            for (m in models) {
                status = "G1 对照中: ${m.name}"
                // 单模型失败（如 270M 结构不支持）不阻断其余模型
                runReport({ G1Comparison.run(context, m) }) { d ->
                    val v = d.getJSONObject("verdict")
                    val appPrefix = v.getJSONObject("scheme_app_prefix")
                    "${m.name}: G1_${if (v.getBoolean("g1_pass")) "PASS" else "FAIL"}" +
                        "（app-prefix min: doc=${appPrefix.getDouble("doc_side_min")} " +
                        "query=${appPrefix.getDouble("query_side_min")}）"
                }.onSuccess { lines += it }
                    .onFailure { lines += "${m.name}: 运行失败 ${it.readableMessage()}" }
                g1Summary = lines.toList()
            }
            status = "G1 完成；结果已导出 filesDir/g1_results/"
        }
    }

    fun runScan() {
        if (running) return
        launchExperiment("G4 扫描") {
            runReport({ VectorScanBenchmark.run(context) }) { d ->
                val gates = d.getJSONObject("gates")
                listOf(
                    "G4_${if (d.getBoolean("g4_pass")) "PASS" else "FAIL"}：" +
                        "1k p95=${gates.getDouble("best_p95_1k")}ms（≤150） " +
                        "10k p95=${gates.getDouble("best_p95_10k")}ms（≤800）",
                    "Top-10 跨实现一致: ${d.getJSONObject("consistency_top10")}",
                )
            }.onSuccess {
                scanSummary = it
                status = "G4 扫描基准完成；结果在 filesDir/scan_bench/"
            }.onFailure {
                scanSummary = listOf("运行失败: ${it.readableMessage()}")
                status = "G4 扫描基准失败"
            }
        }
    }

    fun runG3() {
        if (running) return
        launchExperiment("G3 分词") {
            runReport({ ChineseTokenizationBenchmark.run(context) }) { d ->
                val v = d.getJSONObject("verdict")
                listOf(
                    "G3_${if (v.getBoolean("g3_pass")) "PASS" else "FAIL"} " +
                        "选定方案: ${v.getString("chosen_scheme")}",
                    "hit率: raw=${v.getDouble("raw_hit")} bigram=${v.getDouble("bigram_hit")} " +
                        "jieba=${v.getDouble("jieba_hit")}（语料 ${d.getInt("chunks")} chunks）",
                )
            }.onSuccess {
                g3Summary = it
                status = "G3 完成；结果在 filesDir/g3_results/"
            }.onFailure {
                g3Summary = listOf("运行失败: ${it.readableMessage()}")
                status = "G3 分词失败（详见摘要）"
            }
        }
    }

    fun runLenBench() {
        if (running) return
        launchExperiment("长度×延迟") {
            val lines = mutableListOf<String>()
            // 与 IngestRepository.findModel 同一偏好：仅 440M 已被验证可加载（270M 会造成引擎失败）
            val model = models.firstOrNull { it.name.contains("440m") } ?: models.firstOrNull()
            if (model == null) {
                lenSummary = listOf("缺少模型文件")
                status = "长度基准失败：缺少模型文件"
                return@launchExperiment
            }
            for (useGpu in listOf(false, true)) {
                val mode = if (useGpu) "GPU" else "CPU"
                status = "长度×延迟矩阵（$mode）运行中..."
                runReport({ LengthLatencyBenchmark.runPass(context, model, useGpu) }) { d ->
                    if (!d.isNull("error")) {
                        "$mode: 失败 ${d.getString("error").take(60)}"
                    } else {
                        "$mode: " + d.getJSONArray("rows").let { rows ->
                            (0 until rows.length()).joinToString("  ") {
                                val r = rows.getJSONObject(it)
                                "${r.getInt("chars")}字=${r.getDouble("p50_ms")}/${r.getDouble("p95_ms")}ms"
                            }
                        }
                    }
                }.onSuccess { lines += it }
                    .onFailure { lines += "$mode: 解析/运行失败 ${it.readableMessage()}" }
                lenSummary = lines.toList()
            }
            status = "长度基准完成；结果在 filesDir/lenbench/"
        }
    }

    /** G1D：EGemma2 SOC 特化版（NPU 编译）终审——复用 ProviderVerification 判定，backend=NPU */
    fun runG1D() {
        if (running) return
        val model = models.firstOrNull {
            it.name.contains("_Qualcomm", true) || it.name.contains("_MediaTek", true)
        }
        if (model == null) {
            g1DSummary = listOf("缺少 SOC 特化版模型（文件名含 _Qualcomm/_MediaTek，见模型目录）")
            return
        }
        launchExperiment("G1D") {
            status = "G1D 运行中: ${model.name}（NPU 后端）"
            runReport({
                ProviderVerification.run(context, model, "G1D_npu") { ctx, f ->
                    com.zhibox.memory.core.embedding.EmbeddingRuntime.createProvider(
                        ctx, f, useGpu = false, useNpu = true,
                    )
                }
            }) { d ->
                val v = d.getJSONObject("verdict")
                val r = d.getJSONObject("retrieval").getJSONObject("overall")
                val init = d.getJSONArray("init").getJSONObject(0)
                G1DResult(
                    lines = listOf(
                        "G1D_${if (v.getBoolean("g1b_pass")) "PASS" else "FAIL"}：" +
                            "R@1=${r.getDouble("recall_at_1")} MRR=${r.getDouble("mrr")} · " +
                            "维度=${d.getInt("dimensions")}",
                        "init ${init.getLong("first_ms")}/${init.getLong("second_ms")}ms · " +
                            latencyLine(d.getJSONArray("length_latency")),
                        "序列上限: ${d.getString("seq_limit").take(60)}",
                    ),
                    passed = v.getBoolean("g1b_pass"),
                )
            }.onSuccess { result ->
                // 验证门：通过则记录（fileName→实验标记解除的运行依据）
                val entry = if (result.passed) ModelCatalog.byFileName(model.name) else null
                if (entry != null) PathPreferences.setVerifiedSha(context, model.name, entry.sha256)
                g1DSummary = result.lines +
                    listOfNotNull(entry?.let { "验证门通过，已记录（该模型可正式启用）" })
                status = "G1D 完成；结果在 filesDir/g1b_results/"
            }.onFailure {
                g1DSummary = listOf("G1D 运行失败: ${it.readableMessage()}")
                status = "G1D 失败（详见摘要）"
            }
        }
    }

    fun runSearchEval() = runSimple(
        "检索验收",
        { SearchEvalRunner.run(context) },
        "检索验收完成；结果在 filesDir/search_results/",
    )

    fun runEvalSet() = runSimple(
        "评测集跑批",
        { EvalSetRunner.run(context) },
        "评测集跑批完成；结果在 filesDir/eval_results/",
    )

    fun runW4Plus() = runSimple(
        "W4+ 验证",
        { W4PlusRunner.run(context) },
        "W4+ 验证完成；结果在 filesDir/probe_results/",
    )

    fun runFtsRecovery() = runSimple(
        "FTS 恢复",
        { FtsRecoveryRunner.run(context) },
        "FTS 恢复自证完成；结果在 filesDir/probe_results/",
    )

    fun runProdBench(gpuMode: String) = runSimple(
        "生产基准（GPU=$gpuMode）",
        { ProductionBenchRunner.run(context, gpuMode) },
        "生产基准完成（GPU=$gpuMode）；结果在 filesDir/eval_results/",
    )

    fun runDeleteCheck() = runSimple(
        "删除级联验证",
        { DeleteCascadeRunner.run(context) },
        "删除级联验证完成；结果在 filesDir/eval_results/",
    )

    fun runProbe() = runSimple(
        "连通矩阵",
        { ConnectivityProbe.run(context) },
        "连通矩阵完成；结果在 filesDir/probe_results/",
    )

    /** B3-11 路由表分派：AutoStart 快照 → 唯一实验入口。 */
    fun dispatch(run: DebugRun) {
        when (run) {
            DebugRun.BENCHMARK -> runAll()
            DebugRun.G1 -> runG1()
            DebugRun.SCAN -> runScan()
            DebugRun.G3 -> runG3()
            DebugRun.LEN -> runLenBench()
            DebugRun.SEARCH_EVAL -> runSearchEval()
            DebugRun.EVAL_SET -> runEvalSet()
            DebugRun.DELETE_CHECK -> runDeleteCheck()
            DebugRun.PROD -> runProdBench(PathPreferences.GPU_OFF)
            DebugRun.PROD_GPU -> runProdBench(PathPreferences.GPU_ON)
            DebugRun.PROD_AUTO -> runProdBench(PathPreferences.GPU_AUTO)
            DebugRun.G1D -> runG1D()
            DebugRun.G1E -> runG1E()
            DebugRun.PROBE -> runProbe()
            DebugRun.FTS_RECOVERY -> runFtsRecovery()
            DebugRun.W4_PLUS -> runW4Plus()
        }
    }

    // B3-11：单一自动启动入口（替代 17 个 LaunchedEffect + delay 串联）；只跑一次。
    LaunchedEffect(autoStart, modelsLoaded) {
        if (autoRunStarted) return@LaunchedEffect
        val run = autoStart.route()
        if (run == null) {
            // B7：legacy extra（Path B/Granite、Path C/.task）已下线，显式提示而非静默忽略。
            if (autoStart.pathB || autoStart.g1c) {
                autoRunStarted = true
                status = "该实验已下线（B7：Path B/Granite、Path C/.task）；可选实验见上方按钮"
            }
            return@LaunchedEffect
        }
        if (run.requiresModels && !modelsLoaded) return@LaunchedEffect
        autoRunStarted = true
        status = "${run.label}（自动启动）…"
        dispatch(run)
    }

    // ---- UI：单 LazyColumn 统一滚动（B3-6：修复小屏 Column 套 LazyColumn 的 0 高/不可滚动） ----
    ZhiBoxScreen(title = "Phase 0 · Embedder 基准", onBack = onBack, backEnabled = !running) { padding ->
        val summaries = listOf(
            "G1 判定摘要" to g1Summary,
            "G4 扫描基准摘要" to scanSummary,
            "G3 中文分词摘要" to g3Summary,
            "长度×delegate 延迟（p50/p95）" to lenSummary,
            "UE 通道 GPU 验证（G1E）摘要" to g1ESummary,
            "SOC 特化版终审（G1D·NPU）摘要" to g1DSummary,
        ).filter { it.second.isNotEmpty() }
        val modelsReady = modelsLoaded && models.isNotEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "device") { DeviceLine() }
            item(key = "actions") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExperimentButton(
                        label = if (running) "运行中…" else "运行基准 (${models.size})",
                        enabled = !running && modelsReady,
                    ) { runAll() }
                    ExperimentButton("G1 对照", !running && modelsReady) { runG1() }
                    ExperimentButton("G4 扫描", !running) { runScan() }
                    ExperimentButton("G3 分词", !running) { runG3() }
                    ExperimentButton("长度×GPU", !running && modelsReady) { runLenBench() }
                    ExperimentButton("G1D·NPU", !running && modelsReady) { runG1D() }
                    ExperimentButton("G1E·UE-GPU", !running && modelsReady) { runG1E() }
                    ExperimentButton("连通矩阵", !running) { runProbe() }
                }
            }
            if (running) {
                item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item(key = "status") {
                Text(
                    if (activeRun != null) "$status · 当前: $activeRun" else status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            summaries.forEach { (title, lines) ->
                item(key = "summary:$title") { ExperimentCard(title, lines) }
            }
            if (modelsLoaded && models.isEmpty()) {
                item(key = "missing-models") { MissingModelsCard() }
            }
            items(reports, key = { "report:${it.modelFile}" }) { ReportCard(it) }
        }
    }
}

/** B3-11 路由条目：`requiresModels` 控制是否等模型扫描完成后再自动触发。 */
internal enum class DebugRun(val label: String, val requiresModels: Boolean) {
    BENCHMARK("运行基准", true),
    G1("G1 对照", true),
    SCAN("G4 扫描", false),
    G3("G3 分词", false),
    LEN("长度×延迟", true),
    SEARCH_EVAL("检索验收", false),
    EVAL_SET("评测集跑批", false),
    DELETE_CHECK("删除级联验证", false),
    PROD("生产基准", false),
    PROD_GPU("生产基准·GPU", false),
    PROD_AUTO("生产基准·GPU-AUTO", false),
    G1D("G1D·NPU", true),
    G1E("G1E·UE-GPU", true),
    PROBE("连通矩阵", false),
    FTS_RECOVERY("FTS 恢复", false),
    W4_PLUS("W4+ 验证", false),
}

/**
 * **路由表（B3-11）**：AutoStart（20 个 EXTRA 的只读快照）→ 本次自动执行的实验。
 * 优先级沿用旧 `when` 顺序；验收脚本每次只传一个 extra。
 * B7：`pathB`/`g1c` 两个 legacy extra 已下线（route 返回 null），由 [BenchmarkScreen] 提示"已下线"。
 */
internal fun AutoStart.route(): DebugRun? = when {
    benchmark -> DebugRun.BENCHMARK
    g1 -> DebugRun.G1
    scan -> DebugRun.SCAN
    g3 -> DebugRun.G3
    len -> DebugRun.LEN
    searchEval -> DebugRun.SEARCH_EVAL
    evalSet -> DebugRun.EVAL_SET
    deleteCheck -> DebugRun.DELETE_CHECK
    prod -> DebugRun.PROD
    prodGpu -> DebugRun.PROD_GPU
    prodAuto -> DebugRun.PROD_AUTO
    g1d -> DebugRun.G1D
    g1e -> DebugRun.G1E
    probe -> DebugRun.PROBE
    ftsRecovery -> DebugRun.FTS_RECOVERY
    w4plus -> DebugRun.W4_PLUS
    else -> null
}

// ---------------- B3-6 复用组件（同构 Card 模板的唯一来源） ----------------

@Composable
private fun DeviceLine() {
    Text(
        "设备: ${Build.MODEL} · " +
            (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "") +
            " · Android ${Build.VERSION.RELEASE}",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 通用分组卡：标题 + 内容列（原 8 段同构 Summary Card 收敛至此）。 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ExperimentCard(title: String, lines: List<String>) {
    SectionCard(title) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ExperimentButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
private fun MissingModelsCard() {
    SectionCard("未找到模型文件") {
        Text(
            "adb push <model>.litertlm " +
                "/sdcard/Android/data/com.zhibox.memory/files/models/",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("也可放到 filesDir/models（.litertlm）", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReportCard(report: EmbedderBenchmark.Report) {
    // 审查修复：复用 SectionCard，避免与 SectionCard 重复维护 Card/Column 模板（B3-6 唯一来源）。
    SectionCard(report.modelFile) {
        if (report.error != null) {
            Text("错误: ${report.error}", color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "维度 ${report.dimensions} · 冷加载 ${report.coldLoadMs}ms · " +
                    "首条 ${fmt("%.1f", report.firstEmbedMs)}ms",
            )
            Text(
                "热推理 p50 ${fmt("%.1f", report.warmP50Ms)}ms · " +
                    "p95 ${fmt("%.1f", report.warmP95Ms)}ms（${report.warmSamples} 次）",
            )
            Text(
                "L2 偏差 ${fmt("%.2e", report.l2NormDelta)} · " +
                    "自余弦 ${fmt("%.6f", report.selfCosine)} · 有限值 ${report.allFinite}",
            )
            Text(
                "峰值 RSS ${report.peakRssKb / 1024}MB（基线 ${report.rssBaselineKb / 1024}MB）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------- B3-6 解析/格式化辅助 ----------------

/** runner 产物解析统一防护：坏文件/缺字段不崩溃，转可读失败文案。 */
private suspend fun <T> runReport(
    produce: suspend () -> File,
    parse: (JSONObject) -> T,
): Result<T> {
    val out = try {
        produce()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        return Result.failure(t)
    }
    return try {
        // 审查修复：读取 runner 产物属于磁盘 IO，不能在 Main 直接 readText。
        val text = withContext(Dispatchers.IO) { out.readText() }
        Result.success(parse(JSONObject(text)))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(IllegalStateException("结果解析失败（${out.name}）：${t.readableMessage()}", t))
    }
}

/** 单模型长度×延迟行（G1E/G1D 共用格式）。 */
private fun latencyLine(rows: JSONArray): String =
    (0 until rows.length()).joinToString("  ") {
        val row = rows.getJSONObject(it)
        "${row.getInt("chars")}字=${row.getDouble("p50_ms")}ms"
    }

private fun fmt(pattern: String, vararg args: Any): String =
    String.format(Locale.ROOT, pattern, *args)

private data class G1DResult(val lines: List<String>, val passed: Boolean)

/** B3-12：模型扫描（只在 IO 线程调用；组合期不再执行）。 */
private fun discoverModels(context: Context): List<File> {
    val dirs = listOf(
        File(context.getExternalFilesDir(null), "models"),
        File(context.filesDir, "models"),
    )
    return dirs.filter { it.isDirectory }
        .flatMap { dir ->
            // B7：仅 EGemma2 .litertlm（.task/Path C 已下线）
            dir.listFiles { f -> f.extension == "litertlm" }?.toList()
                ?: emptyList()
        }
        .distinctBy { it.name }
        .sortedBy { it.name }
}
