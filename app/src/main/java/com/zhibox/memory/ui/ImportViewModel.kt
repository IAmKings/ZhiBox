package com.zhibox.memory.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.ingest.PipelineError
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.text.WebPageFetcher
import com.zhibox.memory.core.text.WebPageResult
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.IOException
import java.util.Locale

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
        File(app.filesDir, STAGING_DIR_NAME)
    }

    private val _stagingCount = MutableStateFlow(-1)
    val stagingCount: StateFlow<Int> = _stagingCount.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** P1：SAF 多选文件复制到暂存目录进行中（UI 显示“复制中…”并禁用重复选择）。 */
    private val _stagingFiles = MutableStateFlow(false)
    val stagingFiles: StateFlow<Boolean> = _stagingFiles.asStateFlow()

    /** P1：网页 URL 抓取进行中。 */
    private val _urlFetching = MutableStateFlow(false)
    val urlFetching: StateFlow<Boolean> = _urlFetching.asStateFlow()

    /** P1.1：PDF 打开密码重试进行中（防连点）。 */
    private val _pdfPasswordRetrying = MutableStateFlow(false)
    val pdfPasswordRetrying: StateFlow<Boolean> = _pdfPasswordRetrying.asStateFlow()

    /** 一次性操作成功/信息（可读单行文案） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** B3-5：操作失败的可读错误（ErrorState 展示，带“知道了”清除动作） */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** B3-9：最近 50 条任务（创建时间倒序）；活动任务在 UI 上分区优先展示。 */
    private val _jobs = MutableStateFlow<List<IngestJobEntity>>(emptyList())
    val jobs: StateFlow<List<IngestJobEntity>> = _jobs.asStateFlow()

    /** B9：SAG 抽取汇总（文档 ready 后展示一行；与导入状态独立，抽取失败不算导入失败）。 */
    private val _extractionSummary = MutableStateFlow<ExtractionCountsUi?>(null)
    val extractionSummary: StateFlow<ExtractionCountsUi?> = _extractionSummary.asStateFlow()

    private var autoStartConsumed = false
    private var autoCancelConsumed = false

    init {
        // B3-1：init 只启动协程，不在构造函数内做磁盘访问/建库。
        refreshStaging()
        observeRecentJobs()
        observeJobsForStatusExport()
        // B9：抽取状态汇总（导入完成后第二阶段的进度；与导入任务流独立）
        refreshExtractionSummary()
        observeExtractionSummary()
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
            f.isFile && !f.name.startsWith(".") &&
                f.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
        }?.size ?: 0

    fun clearError() {
        _errorMessage.value = null
    }

    private fun setError(id: Int, t: Throwable) {
        _errorMessage.value = str(id, t.readableMessage())
    }

    fun importAll() {
        // P1 审查修复：暂存复制/URL 抓取与「全部导入」互斥，防止把半写文件移入 documents。
        if (_importing.value || _stagingFiles.value || _urlFetching.value) return
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
                // B9：ready 文档会进入两阶段抽取；导入结束后刷新一次汇总供 UI 展示
                refreshExtractionSummary()
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

    // ───────────────────────── P1：SAF 多选暂存 ─────────────────────────

    /**
     * P1.1：用户输入 PDF 打开密码（user password）后重试失败任务。
     *
     * 隐私约定：
     * - 密码只以 [CharArray] 形式经 core `PdfPasswordVault` 传递，**不写 prefs/DB、不写日志、不拼接进错误文案**；
     * - 本方法无论如何都会在 `finally` 将传入数组清零（UI 侧同时清空输入框状态）；
     * - 失败只提示可读原因（空密码/非 PDF/状态变化/异常）。
     *
     * @return 通过 [message]/[errorMessage] 反馈：true=已重排队，false=状态已变化不可重试。
     */
    fun retryPdfWithPassword(jobId: String, documentId: String, password: CharArray) {
        if (_pdfPasswordRetrying.value) return
        if (jobId.isBlank() || documentId.isBlank()) {
            password.fill('\u0000')
            _errorMessage.value = str(R.string.import_pdf_password_not_requeued)
            return
        }
        if (password.isEmpty()) {
            password.fill('\u0000')
            _errorMessage.value = str(R.string.import_pdf_password_empty)
            return
        }
        _pdfPasswordRetrying.value = true
        _errorMessage.value = null
        safeLaunch(
            onError = { t ->
                _pdfPasswordRetrying.value = false
                setError(R.string.import_pdf_password_failed, t)
            },
        ) {
            try {
                val requeued = withContext(Dispatchers.IO) {
                    repository.retryPdfWithPassword(jobId, documentId, password)
                }
                if (requeued) {
                    _message.value = str(R.string.import_pdf_password_requeued)
                    refreshStaging()
                } else {
                    _errorMessage.value = str(R.string.import_pdf_password_not_requeued)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // core 的可读校验错误（密码为空/过长/非 PDF 等）；不含密码本身
                _errorMessage.value = str(R.string.import_pdf_password_failed, e.readableMessage())
            } catch (t: Throwable) {
                _errorMessage.value = str(R.string.import_pdf_password_failed, t.readableMessage())
            } finally {
                // 防御性清零（core vault 亦会一次性消费后清零；此处覆盖 UI→VM 的副本）
                password.fill('\u0000')
                _pdfPasswordRetrying.value = false
            }
        }
    }

    /**
     * P1：把 SAF 选中的文件复制到 `filesDir/import-staging`（**不直接写 DB**）。
     *
     * 规则：
     * - 通过 [OpenableColumns] 读取 displayName/size；保留扩展名；
     * - 过滤路径穿越/隐藏文件（`../`、`\\`、点开头）与不支持扩展名；
     * - 单文件超过 [MAX_STAGED_BYTES] 跳过（可读提示跳过数）；
     * - 重名文件加时间戳前缀，避免覆盖；
     * - 复制完成后刷新暂存计数，由既有「全部导入」调 `IngestRepository.importStaged()`。
     */
    fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty() || _stagingFiles.value || _urlFetching.value || _importing.value) return
        _stagingFiles.value = true
        _errorMessage.value = null
        safeLaunch(
            onError = { t ->
                _stagingFiles.value = false
                setError(R.string.import_staged_failed, t)
            },
        ) {
            try {
                val outcome = withContext(Dispatchers.IO) { copyUrisToStaging(uris) }
                _message.value = when {
                    outcome.staged == 0 && outcome.skipped > 0 ->
                        str(R.string.import_staged_skipped)
                    outcome.staged == 0 -> null
                    outcome.skipped > 0 ->
                        str(R.string.import_staged_partial, outcome.staged, outcome.skipped)
                    else -> str(R.string.import_staged_done, outcome.staged)
                }
                refreshStaging()
            } finally {
                _stagingFiles.value = false
            }
        }
    }

    private data class StageOutcome(val staged: Int, val skipped: Int)

    private suspend fun copyUrisToStaging(uris: List<Uri>): StageOutcome {
        val dir = stagingDir.apply { mkdirs() }
        var staged = 0
        var skipped = 0
        for (uri in uris) {
            try {
                val rawName = displayName(uri)
                val safeName = rawName?.let { sanitizeStagedName(it) }
                if (safeName == null) {
                    skipped++
                    continue
                }
                val size = openableSize(uri)
                if (size != null && size > MAX_STAGED_BYTES) {
                    skipped++
                    continue
                }
                val target = uniqueStagingTarget(dir, safeName)
                // P1 审查修复：先写隐藏 .part 临时文件，成功后 rename；失败/取消删除临时文件，
                // 避免半截文件被 staging 扫描当作完整文档导入。
                val temp = File(dir, ".${target.name}.${System.nanoTime()}.part")
                val input = app.contentResolver.openInputStream(uri)
                if (input == null) {
                    skipped++
                    continue
                }
                try {
                    var copied = 0L
                    input.use { source ->
                        temp.outputStream().use { sink ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                copied += read
                                if (copied > MAX_STAGED_BYTES) {
                                    throw IOException("暂存文件超过上限")
                                }
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    if (copied == 0L) {
                        skipped++
                    } else if (!temp.renameTo(target)) {
                        skipped++
                    } else {
                        staged++
                    }
                } finally {
                    temp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单文件失败跳过，不静默丢整批；用户可从错误/跳过数感知
                skipped++
            }
        }
        return StageOutcome(staged = staged, skipped = skipped)
    }

    private fun displayName(uri: Uri): String? {
        val cursor = try {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return c.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun openableSize(uri: Uri): Long? {
        val cursor = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !c.isNull(index)) return c.getLong(index)
            }
        }
        return null
    }

    /** 只保留安全文件名：去路径段、拒绝隐藏文件/不支持扩展名；非法字符替换为下划线。 */
    private fun sanitizeStagedName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base.startsWith(".")) return null
        val extension = base.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in SUPPORTED_EXTENSIONS) return null
        val stem = base.substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(MAX_STEM_CHARS)
        if (stem.isEmpty()) return null
        return "$stem.$extension"
    }

    private fun uniqueStagingTarget(dir: File, safeName: String): File {
        val candidate = File(dir, safeName)
        if (!candidate.exists()) return candidate
        val stem = safeName.substringBeforeLast('.')
        val extension = safeName.substringAfterLast('.')
        val stamp = System.currentTimeMillis()
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val next = File(dir, "$stamp-$stem$suffix.$extension")
            if (!next.exists()) return next
            index++
        }
    }

    // ───────────────────────── P1：网页 URL 导入 ─────────────────────────

    /**
     * P1：网页 URL → 抓取正文 → 写入 staging `.txt`（来源标题=文件名）。
     *
     * UI 侧前置校验：仅 HTTPS；`LlmPreferences.networkAllowed == false` 直接给可读提示，
     * 不发起任何请求。正文抓取/超时/大小上限由 core `WebPageFetcher` 负责（core-fixer 契约）。
     */
    fun importUrl(rawUrl: String) {
        if (_urlFetching.value || _stagingFiles.value || _importing.value) return
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            _errorMessage.value = str(R.string.import_url_invalid)
            return
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            _errorMessage.value = str(R.string.import_url_invalid)
            return
        }
        if (!LlmPreferences.networkAllowed(app)) {
            _errorMessage.value = str(R.string.import_url_network_blocked)
            return
        }
        _urlFetching.value = true
        _errorMessage.value = null
        safeLaunch(
            onError = { t ->
                _urlFetching.value = false
                setError(R.string.import_url_failed, t)
            },
        ) {
            try {
                val fetched = try {
                    withContext(Dispatchers.IO) { fetchWebPage(url) }
                } catch (e: WebPageFetchException) {
                    _errorMessage.value = str(R.string.import_url_failed, e.message)
                    return@safeLaunch
                }
                val title = fetched.title.ifBlank { "web-${System.currentTimeMillis()}" }
                val safeName = sanitizeStagedName(fetched.suggestedFileName)
                    ?: sanitizeStagedName("$title.txt")
                if (safeName == null) {
                    _errorMessage.value = str(R.string.import_url_failed, "标题无法生成合法文件名")
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            stagingDir.mkdirs()
                            val target = uniqueStagingTarget(stagingDir, safeName)
                            val temp = File(stagingDir, ".${target.name}.${System.nanoTime()}.part")
                            try {
                                temp.writeText(fetched.content)
                                if (!temp.renameTo(target)) {
                                    throw IOException("暂存网页正文失败")
                                }
                            } finally {
                                temp.delete()
                            }
                            // B14.2：正文成功后写 `<target>.origin` 旁车（UTF-8 单行原始 HTTPS URL，
                            // 与 core SourceOriginUrls 约定同目录同名）；写旁车失败→**回滚删除正文**并
                            // 报可读错误，避免“有文件无来源 URL”的半成品（旁车本体原子写由 helper 保证）。
                            try {
                                writeOriginSidecarAtomically(target, fetched.sourceUrl)
                            } catch (e: Exception) {
                                target.delete()
                                throw OriginSidecarException(e)
                            }
                        }
                        _message.value = str(R.string.import_url_done, title)
                        refreshStaging()
                    } catch (e: OriginSidecarException) {
                        _errorMessage.value = str(
                            R.string.import_url_origin_failed,
                            e.cause?.readableMessage() ?: "",
                        )
                    }
                }
            } finally {
                _urlFetching.value = false
            }
        }
    }

    private data class FetchedWebPage(
        val title: String,
        val content: String,
        val suggestedFileName: String,
        /** 原始请求 URL（单行 HTTPS；写 .origin 旁车用） */
        val sourceUrl: String,
    )

    /** UI 侧可读抓取失败（携带 core 稳定错误码，message 可直接展示）。 */
    private class WebPageFetchException(
        val code: String,
        override val message: String,
    ) : Exception(message)

    /** B14.2：旁车写入失败（正文已回滚删除，避免无 provenance 的半成品）。 */
    private class OriginSidecarException(cause: Throwable) :
        Exception(cause.message ?: "来源 URL 旁车写入失败", cause)

    /**
     * 网页抓取：调用 core [WebPageFetcher]（HTTPS-only / 15s 超时 / 2MB 上限 / 标题+正文）。
     * networkAllowed 实时读取设置（UI 已在 importUrl 前置校验，这里作为第二道防线零请求失败）。
     */
    private suspend fun fetchWebPage(url: String): FetchedWebPage =
        when (
            val result = WebPageFetcher.fetch(
                url = url,
                networkAllowed = LlmPreferences.networkAllowed(app),
            )
        ) {
            is WebPageResult.Success -> FetchedWebPage(
                title = result.page.title,
                // 保留标题 provenance（正文纯文本前加标题行；文件名用 suggestedFileName）
                content = result.page.title.trim() + "\n\n" + result.page.text,
                suggestedFileName = result.page.suggestedFileName,
                sourceUrl = result.page.requestedUrl.trim(),
            )
            is WebPageResult.Failure -> throw WebPageFetchException(result.code, result.message)
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

    /** B9：刷新 SAG 抽取汇总（五态计数；聚合查询，避免每 job 单独查询）。 */
    fun refreshExtractionSummary() {
        safeLaunch {
            _extractionSummary.value = withContext(Dispatchers.IO) {
                db.extractionJobDao().readCounts()
            }
        }
    }

    /** 导入任务变化（含 ready）后去抖刷新抽取汇总，避免高频查询。 */
    private fun observeExtractionSummary() {
        viewModelScope.launch {
            jobs.collectLatest {
                delay(500)
                runCatchingCancellable {
                    _extractionSummary.value = withContext(Dispatchers.IO) {
                        db.extractionJobDao().readCounts()
                    }
                }
            }
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

        /** P1：暂存目录名（与 core IngestRepository 约定一致）。 */
        const val STAGING_DIR_NAME = "import-staging"

        /** P1：支持导入的扩展名（SAF MIME 与之对应；核心解析器由 core-fixer 扩展）。 */
        val SUPPORTED_EXTENSIONS: Set<String> =
            setOf("txt", "md", "pdf", "docx", "pptx", "xlsx", "html", "htm")

        /** P1：单文件暂存大小上限（超出跳过并提示）。 */
        const val MAX_STAGED_BYTES = 50L * 1024 * 1024

        /** P1：staging 文件名主干最大长度（避免 ext4/SAF 长名失败）。 */
        const val MAX_STEM_CHARS = 100
    }
}

/** B14.2：staging 旁车后缀（与 core `SourceOriginUrls.SIDECAR_SUFFIX` 的约定保持一致）。 */
internal const val ORIGIN_SIDECAR_SUFFIX = ".origin"

/** B14.2 旁车内容上限（单行 URL；与 core 旁车 8KB 读上限留余量）。 */
internal const val MAX_ORIGIN_URL_LENGTH = 2048

/**
 * B14.2：在 staging 源文件**同目录**写 `<fileName>.origin` 旁车（UTF-8、单行原始 HTTPS URL）。
 *
 * 原子策略：先写同目录临时文件（以 `.` 开头，不会被 staging 扫描/导入）再 rename 到目标；
 * 任一步失败 → 删除临时文件并抛出（不留下半写旁车）。调用方约定：旁车失败时删除正文源文件。
 *
 * 仅接受单行 `https://` URL（与 core SourceOriginUrls.normalize 的最低要求一致；更强的
 * userinfo/host 校验由 core 读取侧兜底，无效旁车会被 core 删除并按无 URL 处理）。
 */
internal fun writeOriginSidecarAtomically(stagingFile: File, sourceUrl: String) {
    val url = sourceUrl.trim()
    require(url.isNotEmpty()) { "来源 URL 为空" }
    require(url.length <= MAX_ORIGIN_URL_LENGTH) { "来源 URL 过长" }
    require(url.startsWith("https://", ignoreCase = true)) { "仅支持 HTTPS 来源 URL" }
    require(url.none { it == '\n' || it == '\r' }) { "来源 URL 必须为单行" }
    val parent = stagingFile.parentFile ?: throw IOException("源文件无父目录")
    val sidecar = File(parent, stagingFile.name + ORIGIN_SIDECAR_SUFFIX)
    val temp = File(parent, "." + stagingFile.name + ORIGIN_SIDECAR_SUFFIX + ".tmp")
    temp.delete()
    try {
        temp.writeText(url, Charsets.UTF_8)
        if (!temp.renameTo(sidecar)) throw IOException("来源 URL 旁车 rename 失败")
    } finally {
        temp.delete()
    }
}

/**
 * P1.1：判断失败任务是否属于“PDF 需要打开密码/密码不正确”，用于显示「输入 PDF 密码」入口。
 *
 * 兼容两种 error 形态：
 * - 新格式 `DOCUMENT_PARSE:<detail>`：detail 由 [PipelineError.decode] 还原为 userMessage；
 * - 旧纯文本：直接原文匹配。
 * 仅 FAILED 阶段可重试；非 PDF 的解析失败不会命中这两个文案。
 */
internal fun requiresPdfPassword(job: IngestJobEntity): Boolean {
    if (JobStage.fromValue(job.stage) != JobStage.FAILED) return false
    val message = PipelineError.decode(job.error)?.userMessage ?: job.error ?: return false
    return message.contains("PDF 需要打开密码") || message.contains("PDF 打开密码不正确")
}
