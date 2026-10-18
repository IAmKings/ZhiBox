package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.extraction.SagAvailability
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmQueryEntityExtractor
import com.zhibox.memory.core.llm.LlmSagContextSelector
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.core.sag.SagHit
import com.zhibox.memory.core.sag.SagQuery
import com.zhibox.memory.core.sag.SagResult
import com.zhibox.memory.core.sag.SagRetriever
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 检索屏状态（B10）：包装 `SagRetriever` 的三模式检索（vector「混合」/ multi_es_fast / multi）。
 *
 * - 默认 `MULTI_ES_FAST`（SAG 快速，离线）；事件层无数据/模型空间缺失时由 core 自动降级 vector，
 *   UI 通过 `SagResult.effectiveMode + degraded` 明示；
 * - `multi` 需要配置并允许联网的 LLM：`llmAvailable` 用 `SagAvailability.isAvailable(context)`
 *   注入 retriever；条件不满足时 core 返回 `refused`，UI 提示切换 fast/vector；
 * - 保留 query/running/loadingModel/selectedHit（证据详情覆盖层）与 B3-2 首查模型加载提示；
 * - trace：结果区展示精简 trace；`exportTrace()` 从 QueryTraceDao 取最近 50 条写 JSON
 *   （仅 queryHash/timing/降级原因，不含正文；文件落在应用 filesDir/trace_exports/）。
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication()

    /** 延迟初始化：首次检索（IO/Default 上下文）才打开 DB / 构造 retriever，避免主线程初始化磁盘。 */
    private val db: AppDatabase by lazy { AppDatabase.get(app) }
    private val retriever: SagRetriever by lazy {
        val provider = OpenAiCompatibleLlmProvider.from(app)
        val store = KeystoreLlmConfigStore(app)
        SagRetriever(
            context = app,
            db = db,
            llmAvailable = { SagAvailability.isAvailable(app) },
            // B10 multi：LLM 可用时真正做 query 实体抽取 + context 选择；失败由 retriever 回退确定性
            llmExtractor = LlmQueryEntityExtractor(provider, store),
            llmSelector = LlmSagContextSelector(provider, store),
        )
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** B3-2：本次检索是否处于“进程内首次加载端侧模型”阶段（warm 检索为 false）。 */
    private val _loadingModel = MutableStateFlow(false)
    val loadingModel: StateFlow<Boolean> = _loadingModel.asStateFlow()

    /** B10：当前选择的检索模式（可选混合/SAG 快速/SAG 精排）。 */
    private val _mode = MutableStateFlow(RetrievalMode.MULTI_ES_FAST)
    val mode: StateFlow<RetrievalMode> = _mode.asStateFlow()

    /** B10：最近一次 SAG 检索结果（含 hits/trace/refused/degraded/effectiveMode）。 */
    private val _result = MutableStateFlow<SagResult?>(null)
    val result: StateFlow<SagResult?> = _result.asStateFlow()

    /** B10：检索异常（可读；refused 走 SagResult.refused，不放这里）。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** B10：trace 导出中/导出结果（仅诊断，不含正文）。 */
    private val _exportingTrace = MutableStateFlow(false)
    val exportingTrace: StateFlow<Boolean> = _exportingTrace.asStateFlow()

    private val _traceExportMessage = MutableStateFlow<String?>(null)
    val traceExportMessage: StateFlow<String?> = _traceExportMessage.asStateFlow()

    /** 证据详情选中项：放在 ViewModel 而非 Composable remember——旋转后仍停留在证据页。 */
    private val _selectedHit = MutableStateFlow<SagHit?>(null)
    val selectedHit: StateFlow<SagHit?> = _selectedHit.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setMode(mode: RetrievalMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        // 切换模式后旧的 refused/结果对应的模式已不同，清掉避免误导；query 保留。
        _errorMessage.value = null
        _traceExportMessage.value = null
    }

    fun selectHit(hit: SagHit) {
        _selectedHit.value = hit
    }

    fun clearSelectedHit() {
        _selectedHit.value = null
    }

    /** 清除错误（ErrorState 的动作；refused 也一并清除）。 */
    fun retryAfterError() {
        _errorMessage.value = null
        _result.value = null
    }

    fun search() {
        val q = _query.value.trim()
        if (_running.value || q.isEmpty()) return
        val requestMode = _mode.value
        safeLaunch {
            _running.value = true
            _errorMessage.value = null
            _traceExportMessage.value = null
            // B3-2 审查修复：按“当前模型+GPU 模式”的引擎 key 判断，换模型/偏好导致的重建
            // 也会显示阶段提示；只有同 key 已构建（warm）才不提示。
            _loadingModel.value = !EmbeddingRuntime.isEngineWarm(app)
            try {
                val request = SagQuery(query = q, mode = requestMode, topK = 10)
                _result.value = withContext(Dispatchers.Default) { retriever.retrieve(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _result.value = null
                _errorMessage.value = app.getString(
                    R.string.search_failed,
                    t.readableMessage(),
                )
            } finally {
                _running.value = false
                _loadingModel.value = false
            }
        }
    }

    /**
     * 导出最近 50 条检索 trace（脱敏诊断）：只写 queryHash/mode/timingJson/degradedReason/createdAt，
     * 不含查询正文与文档内容；文件写应用私有 filesDir（可 adb pull），不参与备份。
     */
    fun exportTrace() {
        if (_exportingTrace.value) return
        _exportingTrace.value = true
        safeLaunch(
            onError = { t ->
                _exportingTrace.value = false
                _traceExportMessage.value = app.getString(
                    R.string.search_trace_export_failed,
                    t.readableMessage(),
                )
            },
        ) {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val rows = db.queryTraceDao().recent(TRACE_EXPORT_LIMIT)
                    if (rows.isEmpty()) {
                        0 to null
                    } else {
                        val array = JSONArray()
                        rows.forEach { row ->
                            array.put(
                                JSONObject()
                                    .put("id", row.id)
                                    .put("queryHash", row.queryHash)
                                    .put("mode", row.mode)
                                    .put("timingJson", row.timingJson)
                                    .put("degradedReason", row.degradedReason ?: JSONObject.NULL)
                                    .put("createdAt", row.createdAt),
                            )
                        }
                        val dir = File(app.filesDir, TRACE_EXPORT_DIR).apply { mkdirs() }
                        val file = File(dir, "query_trace_${System.currentTimeMillis()}.json")
                        file.writeText(array.toString(2))
                        rows.size to file.absolutePath
                    }
                }
                val count = outcome.first
                val path = outcome.second
                _traceExportMessage.value = if (path == null) {
                    app.getString(R.string.search_trace_export_empty)
                } else {
                    app.getString(R.string.search_trace_export_done, count, path)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _exportingTrace.value = false
            }
        }
    }

    companion object {
        /** B10：trace 导出条数（DAO recent(limit)，最近 50 条）。 */
        const val TRACE_EXPORT_LIMIT = 50

        /** trace JSON 落盘目录（相对 filesDir；不参与备份）。 */
        const val TRACE_EXPORT_DIR = "trace_exports"
    }
}
