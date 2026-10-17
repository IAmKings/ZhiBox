package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.embedding.EmbeddingProvider
import com.zhibox.memory.core.embedding.LiteRtLmEmbeddingProvider
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 模型 × 通道 × 后端 连通性矩阵探针（W4 阶段探测；B7 适配）。
 *
 * B7：Granite/LFM 已从 [ModelCatalog] 删除，Path C（.task/TextEmbedder）已下线。探针不再硬编码
 * 任何模型名，而是按 **ModelCatalog.ENTRIES × 实际在位模型文件** 动态遍历；catalog 删除条目后
 * 自动不再探测（同时输出 missing_models 供主机侧诊断）。
 *
 * 通道（由 catalog 条目与引擎路由决定）：
 *  - UE = tasks-retrieval UniversalEmbedder（EGemma2 通用版 440M/740M；SOC 特化版走 NPU）
 *  - LM = litertlm EmbeddingEngine（EGemma2-270M 轻量路径）
 * 后端：CPU / GPU / NPU；每格 15s 超时（挂死如实标记 "TIMEOUT"）。
 *
 * 输出：filesDir/probe_results/matrix_<ts>.json
 * 自动化：am start --ez autostart_probe true
 */
object ConnectivityProbe {

    private const val PROBE_TIMEOUT_MS = 15_000L
    private const val PROBE_TEXT = "端侧连通性探测 probe 123"

    private data class Cell(val channel: String, val backend: String)

    /**
     * 按 catalog 条目动态生成候选格（B7）：
     * - SOC 特化版（NPU 编译）→ UE + Delegate.NPU；
     * - EGemma2-270M（isPathBModel，litertlm 引擎）→ LM + CPU/GPU；
     * - 其余 EGemma2 通用版 → UE + CPU/GPU。
     */
    private fun cellsFor(entry: ModelCatalog.Entry): List<Cell> = when {
        entry.backend == ModelCatalog.Backend.NPU_SOC -> listOf(Cell("UE", "NPU"))
        IngestRepository.isPathBModel(entry.fileName) ->
            listOf(Cell("LM", "CPU"), Cell("LM", "GPU"))
        else -> listOf(Cell("UE", "CPU"), Cell("UE", "GPU"))
    }

    private fun build(cell: Cell, context: Context, model: File): EmbeddingProvider = when (cell.channel) {
        "LM" -> LiteRtLmEmbeddingProvider(
            context, model,
            useGpu = cell.backend == "GPU",
            useNpu = false,
        )
        else -> UniversalEmbedderProvider(
            context, model,
            useGpu = cell.backend == "GPU",
            useNpu = cell.backend == "NPU",
        )
    }

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        // 探测期间固定 CPU 偏好，避免 GPU 白名单逻辑干扰（本探针直接建 provider，不经 Runtime 缓存）
        PathPreferences.setGpuMode(appContext, PathPreferences.GPU_OFF)

        val available = PathPreferences.candidates(appContext).associateBy { it.name }
        // 动态遍历 catalog：只探测"catalog 收录且设备在位"的模型，删除 catalog 条目即自动下线
        val targets = ModelCatalog.ENTRIES.mapNotNull { entry ->
            available[entry.fileName]?.let { entry to it }
        }
        val missing = ModelCatalog.ENTRIES
            .filter { !available.containsKey(it.fileName) }
            .map { it.fileName }

        val rows = JSONArray()
        var okCount = 0
        var timeoutCount = 0

        for ((entry, model) in targets) {
            for (cell in cellsFor(entry)) {
                val row = JSONObject()
                    .put("model", model.name)
                    .put("label", entry.label)
                    .put("catalog_backend", entry.backend.name)
                    .put("channel", cell.channel)
                    .put("backend", cell.backend)
                val t0 = System.currentTimeMillis()
                var provider: EmbeddingProvider? = null
                try {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        withContext(Dispatchers.Default) {
                            provider = build(cell, appContext, model)
                            val vec = provider!!.embedQuery(PROBE_TEXT)
                            row.put("ok", true)
                                .put("dims", vec.size)
                                .put("first_embed_ms", System.currentTimeMillis() - t0)
                        }
                    }
                    okCount++
                } catch (e: TimeoutCancellationException) {
                    row.put("ok", false).put("error", "TIMEOUT>${PROBE_TIMEOUT_MS / 1000}s（挂死类）")
                    timeoutCount++
                } catch (e: Throwable) {
                    row.put("ok", false)
                        .put("error", "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()?.take(140)}")
                } finally {
                    runCatching { provider?.close() }
                }
                rows.put(row)
            }
        }

        val out = JSONObject()
            .put("experiment", "connectivity_matrix")
            .put("device", JSONObject()
                .put("model", android.os.Build.MODEL)
                .put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "")
                .put("android", android.os.Build.VERSION.RELEASE))
            .put("catalog_entries", ModelCatalog.ENTRIES.size)
            .put("n_models", targets.size)
            .put("missing_models", JSONArray(missing))
            .put("n_cells", rows.length())
            .put("ok", okCount)
            .put("timeouts", timeoutCount)
            .put("rows", rows)

        val dir = File(appContext.filesDir, "probe_results").apply { mkdirs() }
        val outFile = File(dir, "matrix_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
