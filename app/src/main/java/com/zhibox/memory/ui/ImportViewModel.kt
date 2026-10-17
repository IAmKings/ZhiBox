package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 导入屏状态（P1-8 + B3-1/B3-9/B3-13）：
 * - **B3-1 构造期零磁盘 IO**：`db`/`repository`/`stagingDir` 全部 lazy，且只在 `Dispatchers.IO`
 *   内首次解析；构造器不调用 `getFilesDir()`，打开导入屏不再触发 StrictMode DiskReadViolation
 *   （B3 基线 D1：ImportViewModel.<init> 82ms DiskRead）。jobs 的 Room Flow 也在协程里以
 *   `withContext(IO)` 取得后收集，不在主线程建库/查询。
 * - **B3-9**：任务列表改用 `IngestJobDao.observeRecent(50)`，不再全量渲染历史任务。
 * - **B3-13**：`importStaged()` 返回 `ImportBatchResult`，展示“新导入 X / 已存在 Y”。
 * - 暂存扫描/导入/取消/重试长任务全部在 `viewModelScope` + `Dispatchers.IO`；autostart 一次性消费；
 * - `filesDir/import_status.json` 验收落盘只在 debuggable 构建执行（P2-10）。
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication()
    private val db: AppDatabase by lazy { AppDatabase.get(app) }
    private val repository: IngestRepository by lazy { IngestRepository(app) }

    /**
     * B3-1：staging 路径是磁盘访问（`getFilesDir()` 首次调用会创建目录，真机实测 82ms）。
     * 用 lazy + 只在 `Dispatchers.IO` 中首次解析，构造期不触碰磁盘。
     */
    private val stagingDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(app.filesDir, "import-staging")
    }

    private val _stagingCount = MutableStateFlow(-1)
    val stagingCount: StateFlow<Int> = _stagingCount.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** 一次性操作成功/信息（可读单行文案） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** B3-5：操作失败的可读错误（ErrorState 展示，带“知道了”清除动作） */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** B3-9：最近 50 条任务（创建时间倒序）；活动任务在 UI 上分区优先展示。 */
    private val _jobs = MutableStateFlow<List<IngestJobEntity>>(emptyList())
    val jobs: StateFlow<List<IngestJobEntity>> = _jobs.asStateFlow()

    private var autoStartConsumed = false
    private var autoCancelConsumed = false

    init {
        // B3-1：init 只启动协程，不在构造函数内做磁盘访问/建库。
        refreshStaging()
        observeRecentJobs()
        observeJobsForStatusExport()
    }

    private fun str(id: Int): String = app.getString(id)
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** 屏幕进入时调用：扫描暂存 + 一次性消费 autostart 参数 */
    fun onScreenStarted(autoStart: Boolean, autoCancel: Boolean) {
        // 审查修复：上次导入成功提示不应在重新进入屏幕时残留（错误提示由 ErrorState 自行关闭）。
        _message.value = null
        refreshStaging()
        if (autoStart && !autoStartConsumed) {
            autoStartConsumed = true
            safeLaunch(onError = { setError(R.string.import_failed, it) }) {
                // B3-11：不依赖 sleep——先重新扫描暂存目录，再按文件数决定是否自动导入。
                val files = withContext(Dispatchers.IO) { countStagedFiles() }
                _stagingCount.value = files
                if (files > 0) importAll()
            }
        }
        if (autoCancel && !autoCancelConsumed) {
            autoCancelConsumed = true
            cancelImport()
        }
    }

    fun refreshStaging() {
        safeLaunch(onError = { setError(R.string.import_failed, it) }) {
            _stagingCount.value = withContext(Dispatchers.IO) { countStagedFiles() }
        }
    }

    private fun countStagedFiles(): Int =
        stagingDir.listFiles { f ->
            f.isFile && (f.extension.equals("md", true) || f.extension.equals("txt", true))
        }?.size ?: 0

    fun clearError() {
        _errorMessage.value = null
    }

    private fun setError(id: Int, t: Throwable) {
        _errorMessage.value = str(id, t.readableMessage())
    }

    fun importAll() {
        if (_importing.value) return
        _importing.value = true
        safeLaunch(onError = { setError(R.string.import_failed, it) }) {
            try {
                val result = withContext(Dispatchers.IO) { repository.importStaged() }
                // B3-13：新导入 X / 已存在 Y（重复数为 0 时只报新增；都没有则不打扰）
                _message.value = when {
                    result.duplicateCount > 0 -> str(
                        R.string.import_batch_result,
                        result.documentIds.size,
                        result.duplicateCount,
                    )
                    result.documentIds.isNotEmpty() -> str(
                        R.string.import_batch_new,
                        result.documentIds.size,
                    )
                    else -> null
                }
                _errorMessage.value = null
            } catch (e: CancellationException) {
                throw e
            } finally {
                _importing.value = false
                refreshStaging()
            }
        }
    }

    fun cancelImport() {
        if (_cancelling.value) return
        _cancelling.value = true
        safeLaunch(onError = { setError(R.string.import_cancel_failed, it) }) {
            try {
                val n = withContext(Dispatchers.IO) { repository.cancelImport() }
                _message.value = str(R.string.import_cancelled, n)
            } catch (e: CancellationException) {
                throw e
            } finally {
                _cancelling.value = false
                refreshStaging()
            }
        }
    }

    /**
     * 重试终态 failed 的导入任务（P1-2 重试入口，契约由 core-fixer 提供）：
     * `IngestRepository.retryFailedJobs()` 内部重排队并负责入队批量 Worker，UI 不重复入队。
     */
    fun retryFailed() {
        if (_retrying.value) return
        _retrying.value = true
        safeLaunch(onError = { setError(R.string.import_retry_failed_error, it) }) {
            try {
                val n = withContext(Dispatchers.IO) { repository.retryFailedJobs() }
                _message.value = if (n > 0) {
                    str(R.string.import_retry_queued, n)
                } else {
                    str(R.string.import_retry_none)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _retrying.value = false
            }
        }
    }

    /**
     * B3-9：最近 50 条任务列表。
     * 取 Flow 本身放在 IO：`AppDatabase.get`/DAO 获取在首次使用时可能触碰文件路径与 Room 初始化，
     * 不放在 Main（B3-1）。Room 查询本身在 Room executor；emission 回到 viewModelScope(Main)。
     */
    private fun observeRecentJobs() {
        safeLaunch(onError = { setError(R.string.import_failed, it) }) {
            val flow = withContext(Dispatchers.IO) {
                db.ingestJobDao().observeRecent(JobStage.ACTIVE_VALUES, RECENT_JOB_LIMIT)
            }
            flow.collect { _jobs.value = it }
        }
    }

    /** 验收状态落盘（仅 debug）：300ms 去抖后导出一次快照，避免高频写入放大。 */
    private fun observeJobsForStatusExport() {
        if (!app.isDebuggable()) return
        viewModelScope.launch {
            jobs.collectLatest { list ->
                delay(300)
                runCatchingCancellable { exportStatus(list) }
            }
        }
    }

    /** runCatching 的取消安全版本：CancellationException 必须传播（P1-2 审查修复）。 */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    private suspend fun exportStatus(jobList: List<IngestJobEntity>) = withContext(Dispatchers.IO) {
        val smokeHits = runCatchingCancellable {
            db.chunkDao().searchFts(
                com.zhibox.memory.core.text.BigramTokenizer.toMatchQuery("向量检索"), 20,
            ).size
        }.getOrDefault(-1)
        val json = JSONObject()
            .put("ready_documents", db.documentDao().readyCount(DocStatus.READY.value))
            .put("chunks", db.chunkDao().count())
            .put("fts_rows", runCatchingCancellable { db.chunkDao().ftsCount() }.getOrDefault(-1))
            .put("embeddings", db.embeddingDao().count())
            .put("smoke_fts_hits", smokeHits)
            .put("jobs", JSONArray().apply {
                jobList.forEach {
                    put(
                        JSONObject().put("id", it.id).put("stage", it.stage)
                            .put("progress", it.progress).put("error", it.error ?: JSONObject.NULL),
                    )
                }
            })
        File(app.filesDir, "import_status.json")
            .writeText(json.toString(2))
    }

    companion object {
        /** B3-9：任务列表只渲染最近 50 条（此前 101 行全量渲染）。 */
        const val RECENT_JOB_LIMIT = 50
    }
}
