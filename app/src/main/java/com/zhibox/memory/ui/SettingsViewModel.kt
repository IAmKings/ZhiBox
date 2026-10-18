package com.zhibox.memory.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhibox.memory.R
import com.zhibox.memory.core.backup.KnowledgeBackup
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfig
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmStoreException
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.llm.LlmTestResult
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.ingest.ReembedScheduler
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.model.SharedModelStore
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模型文件条目：文件大小在 ViewModel 的 IO 阶段算好，composition 内不再做 `File.length()` 磁盘 IO。 */
data class ModelFileItem(val file: File, val label: String, val sizeBytes: Long)

/**
 * B8：设置页 LLM 配置视图模型（**只含掩码 key**，明文 apiKey 永不进入 UiState/日志/错误文案）。
 */
data class LlmConfigUi(
    val baseUrl: String,
    val model: String,
    val timeoutSeconds: Int,
    /** 形如 "sk-***abcd"；null = 未保存密钥 */
    val maskedKey: String?,
)

/** 向量空间条目：维度取自 `embedding_meta.dimensions`（不再解析 spaceId 推断，P1-6）。 */
data class SpaceInfo(val id: String, val count: Int, val dimensions: Int)

/**
 * B7：旧模型空间（非 EGemma2）的 UI 模型。
 * `IngestRepository.legacySpacesReport()` 返回 core 的 LegacySpaceInfo，这里只取展示所需字段，
 * 避免 UI 直接耦合 core 数据结构 FQN（`id/providerId` 不展示）。
 */
data class LegacySpaceUi(val modelId: String, val vectorCount: Int)

/**
 * 设置屏 UiState（P1-8）：所有长任务（刷新/模型下载/备份导入导出/索引重建/清空共享目录）
 * 在 `viewModelScope + Dispatchers.IO` 中执行，屏幕离开组合不取消；异常统一转为可读提示。
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val model: String? = null,
    val preference: String = PathPreferences.AUTO,
    val gpuPref: String = PathPreferences.GPU_AUTO,
    val perfPref: String = ModelCatalog.PERF_BALANCED,
    val socTag: String = "",
    val models: List<ModelFileItem> = emptyList(),
    val spaces: List<SpaceInfo> = emptyList(),
    val chunkCount: Int = 0,
    val docReady: Int = 0,
    val storeState: SharedModelStore.State = SharedModelStore.State.NOT_CONFIGURED,
    val sharedNames: List<String> = emptyList(),
    val downloadEntry: ModelCatalog.Entry? = null,
    val downloadPct: Int = -1,
    val downloading: Boolean = false,
    /** 模型商店操作结果（成功/信息） */
    val message: String? = null,
    /** 全局错误（目录授权/下载/删除/清空等），可读单行 */
    val error: String? = null,
    /** 备份迁移状态/错误 */
    val migrationMessage: String? = null,
    val reembedDone: Int = 0,
    val reembedTotal: Int = -1,
    val lastReembed: String? = null,
    /** B7：检测到的旧模型空间（非 EGemma2）；非空时设置页显示“需重新导入”警告，不自动删数据 */
    val legacySpaces: List<LegacySpaceUi> = emptyList(),
    /** B7 审查修复：旧空间报表失败时的可读错误（不静默吞掉；不阻塞整体刷新） */
    val legacySpacesError: String? = null,
    /** B3-14：索引三表健康报告（chunk/FTS/向量计数 + 孤儿行），null = 尚未检查 */
    val indexHealth: IngestRepository.IndexHealth? = null,
    /** B3-14：索引修复进行中（按钮 loading/finally 复位） */
    val indexRepairing: Boolean = false,
    val diagMessage: String? = null,
    val importPreview: KnowledgeBackup.ImportPreview? = null,
    val importUri: Uri? = null,
    // ---- B8：SAG 增强 / LLM（未配置时离线检索不受影响；默认禁网） ----
    /** LLM 配置（仅掩码 key）；null = 未配置 */
    val llm: LlmConfigUi? = null,
    /** 连接测试进行中（finally 复位） */
    val llmTesting: Boolean = false,
    /** 连接测试结果/保存/清除结果（可读；不含明文 key） */
    val llmTestMessage: String? = null,
    /** LLM 配置读取/存储错误（如 Keystore 解密失败；可读且不含 key） */
    val llmError: String? = null,
    /** SAG 增强（事件/实体抽取）开关 */
    val sagEnabled: Boolean = false,
    /** 问答生成开关 */
    val answerEnabled: Boolean = false,
    /** 禁止网络总开关（true = 拒绝所有 LLM 调用；默认 true） */
    val networkDisabled: Boolean = true,
)

/**
 * 设置屏 ViewModel：
 * - 模型列表/路径偏好/空间注册表/共享目录状态：一次 IO 快照后写 StateFlow；
 * - 下载：`SharedModelStore.downloadEntry` 在 viewModelScope 中跑，离开页面不取消（P1-7），
 *   进度百分比写入 UiState；
 * - SAF（P1-10）：`setTreeUri` 包异常边界，SecurityException 等失败给可读提示不崩溃；
 * - 备份导入失败展示可读错误（P1-10），导入预览/确认在 UiState 中流转。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val db = AppDatabase.get(application)
    private val repository = IngestRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
        observeReembedProgress()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun update(block: (SettingsUiState) -> SettingsUiState) {
        _state.update(block)
    }

    private data class Snapshot(
        val model: String?,
        val preference: String,
        val gpuPref: String,
        val perfPref: String,
        val socTag: String,
        val models: List<ModelFileItem>,
        val spaces: List<SpaceInfo>,
        val chunkCount: Int,
        val docReady: Int,
        val storeState: SharedModelStore.State,
        val sharedNames: List<String>,
        val lastReembed: String?,
        val indexHealth: IngestRepository.IndexHealth?,
        val legacySpaces: List<LegacySpaceUi>,
        val legacySpacesError: String?,
        // B8
        val llm: LlmConfigUi?,
        val llmError: String?,
        val sagEnabled: Boolean,
        val answerEnabled: Boolean,
        val networkDisabled: Boolean,
    )

    fun refresh() {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        loading = false,
                        error = str(R.string.settings_refresh_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            update { it.copy(loading = true) }
            try {
                val s = withContext(Dispatchers.IO) { loadSnapshot() }
                update {
                    it.copy(
                        loading = false,
                        error = null,
                        model = s.model,
                        preference = s.preference,
                        gpuPref = s.gpuPref,
                        perfPref = s.perfPref,
                        socTag = s.socTag,
                        models = s.models,
                        spaces = s.spaces,
                        chunkCount = s.chunkCount,
                        docReady = s.docReady,
                        storeState = s.storeState,
                        sharedNames = s.sharedNames,
                        lastReembed = s.lastReembed,
                        indexHealth = s.indexHealth,
                        legacySpaces = s.legacySpaces,
                        legacySpacesError = s.legacySpacesError,
                        llm = s.llm,
                        llmError = s.llmError,
                        sagEnabled = s.sagEnabled,
                        answerEnabled = s.answerEnabled,
                        networkDisabled = s.networkDisabled,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(
                        loading = false,
                        error = str(R.string.settings_refresh_failed, t.readableMessage()),
                    )
                }
            }
        }
    }

    private suspend fun loadSnapshot(): Snapshot {
        val preference = PathPreferences.get(app)
        val gpuPref = PathPreferences.gpuMode(app)
        val perfPref = PathPreferences.perfMode(app)
        val socTag = ModelCatalog.detectSocTag()
        val models = PathPreferences.candidates(app).map {
            ModelFileItem(it, PathPreferences.pathLabel(it.name), it.length())
        }
        val model = EmbeddingRuntime.findModel(app)?.name
        val spaces = db.embeddingDao().allMeta().map {
            SpaceInfo(it.id, db.embeddingDao().countBySpace(it.id), it.dimensions)
        }
        val chunkCount = db.chunkDao().count()
        val docReady = db.documentDao().readyCount(DocStatus.READY.value)
        val lastDone = db.appKvDao().get("reembed_last_done")?.toLongOrNull()
        val lastReembed = lastDone?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }
        // B3-14：完整三表健康报告（计数 + 孤儿 chunkId 集合 + reasons），供设置页明细展示
        val indexHealth = repository.indexHealthCheck()
        // B7：旧模型空间（非 EGemma2）检测。只展示警告、不自动删除数据；失败不影响设置页刷新，
        // 但错误要可见（审查修复：此前静默吞成空列表，用户不知道检测失败）。
        var legacySpaces: List<LegacySpaceUi> = emptyList()
        var legacySpacesError: String? = null
        try {
            legacySpaces = repository.legacySpacesReport().map {
                LegacySpaceUi(modelId = it.modelId, vectorCount = it.vectorCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            legacySpacesError = str(
                R.string.settings_legacy_spaces_error,
                t.readableMessage(),
            )
        }
        // B8：LLM 配置（只取掩码 key）与隐私开关。store/prefs 为同步轻量 API，此处已在 IO 上下文。
        // load() 在配置损坏/解密失败时抛 LlmStoreException（message 中文可读）：
        // 不静默吞——把可读原因放进 llmError 展示，同时不阻塞设置页其余内容刷新。
        val llmSnapshot = readLlmSnapshot()
        val llm = llmSnapshot.config
        val llmError = llmSnapshot.error
        val sagEnabled = LlmPreferences.sagEnabled(app)
        val answerEnabled = LlmPreferences.answerEnabled(app)
        val networkDisabled = !LlmPreferences.networkAllowed(app)
        val storeState = SharedModelStore.state(app)
        val sharedNames = if (storeState == SharedModelStore.State.CONNECTED) {
            SharedModelStore.listNamesSync(app)
        } else {
            emptyList()
        }
        return Snapshot(
            model = model,
            preference = preference,
            gpuPref = gpuPref,
            perfPref = perfPref,
            socTag = socTag,
            models = models,
            spaces = spaces,
            chunkCount = chunkCount,
            docReady = docReady,
            storeState = storeState,
            sharedNames = sharedNames,
            lastReembed = lastReembed,
            indexHealth = indexHealth,
            legacySpaces = legacySpaces,
            legacySpacesError = legacySpacesError,
            llm = llm,
            llmError = llmError,
            sagEnabled = sagEnabled,
            answerEnabled = answerEnabled,
            networkDisabled = networkDisabled,
        )
    }

    fun setPreference(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            val changed = _state.value.preference != value
            PathPreferences.set(app, value)
            if (changed) ReembedScheduler.enqueue(app)
            refresh()
        }
    }

    fun setGpuMode(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            PathPreferences.setGpuMode(app, value)
            update { it.copy(gpuPref = value) }
        }
    }

    fun setPerfMode(value: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_pref_save_failed, t.readableMessage())) } },
        ) {
            PathPreferences.setPerfMode(app, value)
            update { it.copy(perfPref = value) }
        }
    }

    /**
     * P1-10：SAF 目录授权可能抛 SecurityException（部分 OEM/云盘），必须捕获并提示。
     * backup-fixer 最终 API：`setTreeUri` 返回 Boolean（false = provider 拒绝持久授权，
     * 不写偏好），这里对 "抛异常" 与 "返回 false" 两条失败路径都给出可读提示。
     */
    fun setTreeUri(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(error = str(R.string.settings_dir_permission_failed, t.readableMessage()))
                }
            },
        ) {
            try {
                val granted = SharedModelStore.setTreeUri(app, uri)
                if (!granted) {
                    update {
                        it.copy(
                            error = str(
                                R.string.settings_dir_permission_failed,
                                str(R.string.settings_dir_permission_denied),
                            ),
                        )
                    }
                    return@safeLaunch
                }
                update { it.copy(error = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(error = str(R.string.settings_dir_permission_failed, t.readableMessage()))
                }
            }
        }
    }

    fun startDownload(entry: ModelCatalog.Entry) {
        if (_state.value.downloading) return
        update {
            it.copy(
                downloading = true,
                downloadEntry = entry,
                downloadPct = 0,
                message = null,
                error = null,
            )
        }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(error = str(R.string.settings_download_failed, t.readableMessage()))
                }
            },
        ) {
            try {
                val result = SharedModelStore.downloadEntry(app, entry) { p ->
                    if (p.total > 0) {
                        update { it.copy(downloadPct = (p.downloaded * 100 / p.total).toInt()) }
                    }
                }
                result.onSuccess {
                    update { it.copy(message = str(R.string.settings_download_done, entry.fileName)) }
                }.onFailure { t ->
                    update {
                        it.copy(error = str(R.string.settings_download_failed, t.readableMessage()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(downloading = false, downloadEntry = null) }
                refresh()
            }
        }
    }

    fun enableShared(name: String) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_store_op_failed, t.readableMessage())) } },
        ) {
            val enabled = SharedModelStore.enable(app, name) != null
            update {
                it.copy(
                    message = if (enabled) {
                        str(R.string.settings_enabled, name)
                    } else {
                        str(R.string.settings_enable_failed, name)
                    },
                )
            }
            refresh()
        }
    }

    fun clearShared() {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_clear_all_failed, t.readableMessage())) } },
        ) {
            val n = SharedModelStore.clearAll(app)
            update { it.copy(message = str(R.string.settings_clear_all_done, n), error = null) }
            refresh()
        }
    }

    fun deleteModel(target: File) {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_delete_model_failed, t.readableMessage())) } },
        ) {
            withContext(Dispatchers.IO) { target.delete() }
            refresh()
        }
    }

    fun export(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update { it.copy(migrationMessage = str(R.string.settings_export_failed, t.readableMessage())) }
            },
        ) {
            update { it.copy(migrationMessage = str(R.string.settings_exporting)) }
            val result = KnowledgeBackup.export(app, uri)
            result.onSuccess { n ->
                update { it.copy(migrationMessage = str(R.string.settings_export_done, n)) }
            }.onFailure { t ->
                update { it.copy(migrationMessage = str(R.string.settings_export_failed, t.readableMessage())) }
            }
        }
    }

    fun previewImport(uri: Uri) {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            },
        ) {
            val result = KnowledgeBackup.preview(app, uri)
            result.onSuccess { preview ->
                update { it.copy(importPreview = preview, importUri = uri, migrationMessage = null) }
            }.onFailure { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_read_failed, t.readableMessage()))
                }
            }
        }
    }

    fun dismissImportPreview() {
        update { it.copy(importPreview = null, importUri = null) }
    }

    fun confirmImport(dropIncompatibleGpuVectors: Boolean) {
        val uri = _state.value.importUri ?: return
        update { it.copy(migrationMessage = str(R.string.settings_backup_importing), importPreview = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_import_failed, t.readableMessage()))
                }
            },
        ) {
            val result = KnowledgeBackup.import(app, uri, dropIncompatibleGpuVectors)
            result.onSuccess { n ->
                update {
                    it.copy(
                        migrationMessage = str(R.string.settings_backup_import_done, n),
                        importUri = null,
                    )
                }
                refresh()
            }.onFailure { t ->
                update {
                    it.copy(migrationMessage = str(R.string.settings_backup_import_failed, t.readableMessage()))
                }
            }
        }
    }

    /**
     * B3-14：检查并修复索引（复用 core 的 [IngestRepository.repairIndex]）。
     * 同一事务清孤儿 FTS/向量，必要时重建 FTS；修复后把 before/after 行数写成可读提示，
     * `indexRepairing` 在 finally 复位（失败也有提示，不会卡在“修复中”）。
     */
    fun repairIndex() {
        if (_state.value.indexRepairing) return
        update { it.copy(indexRepairing = true) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        indexRepairing = false,
                        diagMessage = str(R.string.settings_index_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val report = withContext(Dispatchers.IO) { repository.repairIndex() }
                val rebuiltSuffix = if (report.rebuiltFts) {
                    str(R.string.settings_index_rebuilt_suffix)
                } else {
                    ""
                }
                val message = str(
                    R.string.settings_index_repair_done,
                    report.removedFtsRows,
                    report.removedEmbeddingRows,
                    rebuiltSuffix,
                ) + "\n" + str(
                    R.string.settings_index_before,
                    report.before.chunkCount,
                    report.before.ftsCount,
                    report.before.embeddingCount,
                ) + "\n" + str(
                    R.string.settings_index_after,
                    report.after.chunkCount,
                    report.after.ftsCount,
                    report.after.embeddingCount,
                )
                update {
                    it.copy(
                        indexHealth = report.after,
                        diagMessage = message,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(indexRepairing = false) }
                refresh()
            }
        }
    }

    // ───────────────────────── B8：SAG 增强 / LLM（设置页骨架） ─────────────────────────

    /** 加密配置存储（Keystore AES/GCM）。构造/读写均为轻量同步 API，调用方须在 IO 上下文。 */
    private val llmStore: LlmConfigStore by lazy { KeystoreLlmConfigStore(app) }

    /** B8：LLM 配置读取结果（config=null 且 error=null 表示未配置）。 */
    private data class LlmSnapshot(val config: LlmConfigUi?, val error: String?)

    /**
     * 读取 LLM 配置为 UI 模型：**只调用 maskedKey()**，不把明文 apiKey 带出 core/llm。
     * core 约定：未配置返回 null；配置损坏/解密失败抛 LlmStoreException（message 中文可读）。
     * 这里把失败原因转成可读文案（不含 key），不阻塞设置页其余内容刷新。
     */
    private fun readLlmSnapshot(): LlmSnapshot = try {
        val store = llmStore
        val config = store.load()
        if (config == null) {
            LlmSnapshot(config = null, error = null)
        } else {
            LlmSnapshot(
                config = LlmConfigUi(
                    baseUrl = config.baseUrl,
                    model = config.model,
                    timeoutSeconds = config.timeoutSeconds,
                    maskedKey = store.maskedKey(),
                ),
                error = null,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        LlmSnapshot(
            config = null,
            error = str(R.string.settings_llm_load_failed, t.readableMessage()),
        )
    }

    /**
     * 保存 LLM 配置。`apiKey` 为空 = 保留已保存密钥；无任何可用密钥时给出可读提示。
     * 错误文案会做明文 key 脱敏兜底（正常情况下 core 的异常/toString 已脱敏）。
     */
    fun saveLlmConfig(baseUrl: String, model: String, apiKey: String, timeoutSeconds: Int) {
        val trimmedKey = apiKey.trim()
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTestMessage = str(
                            R.string.settings_llm_save_failed,
                            redactSecret(t.readableMessage(), trimmedKey),
                        ),
                    )
                }
            },
        ) {
            val saved = withContext(Dispatchers.IO) {
                val store = llmStore
                val effectiveKey = if (trimmedKey.isNotBlank()) {
                    trimmedKey
                } else {
                    // 不 runCatching：配置损坏时 load() 的 LlmStoreException 需冒泡为可读错误，
                    // 而不是误报“请填写 API Key”。
                    store.load()?.apiKey.orEmpty()
                }
                check(effectiveKey.isNotBlank()) { str(R.string.settings_llm_api_key_required) }
                store.save(
                    LlmConfig(
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        apiKey = effectiveKey,
                        timeoutSeconds = timeoutSeconds.coerceIn(1, 120),
                    ),
                )
                LlmConfigUi(
                    baseUrl = baseUrl.trim(),
                    model = model.trim(),
                    timeoutSeconds = timeoutSeconds.coerceIn(1, 120),
                    maskedKey = store.maskedKey(),
                )
            }
            update {
                it.copy(
                    llm = saved,
                    llmTestMessage = str(R.string.settings_llm_saved),
                )
            }
        }
    }

    /**
     * 测试连接：统一走 Provider（禁网由 Provider 内部按隐私策略强制返回 LLM_OFFLINE 且零请求，
     * UI **不**仅靠本地开关短路，保证任何调用方都无法绕过网络策略）；保存的配置缺失时提示先保存。
     * 成功展示延迟/模型；失败展示 core 的 `readableMessage()`（不含明文 key），
     * 本地存储损坏与网络错误分开提示。
     */
    fun testLlmConnection() {
        if (_state.value.llmTesting) return
        update { it.copy(llmTesting = true, llmTestMessage = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTesting = false,
                        llmTestMessage = str(R.string.settings_llm_test_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        // 不 runCatching：配置损坏时 LlmStoreException 由下面单独归类展示
                        val config = llmStore.load()
                        if (config == null) {
                            null
                        } else {
                            OpenAiCompatibleLlmProvider.from(app).testConnection(config)
                        }
                    }
                } catch (e: LlmStoreException) {
                    // 本地存储/解密错误 ≠ 网络错误：展示“LLM 配置读取失败”并给出恢复入口
                    update {
                        it.copy(
                            llmTesting = false,
                            llmTestMessage = str(
                                R.string.settings_llm_load_failed,
                                e.readableMessage(),
                            ),
                        )
                    }
                    return@safeLaunch
                }
                update {
                    it.copy(
                        llmTesting = false,
                        llmTestMessage = when (result) {
                            null -> str(R.string.settings_llm_test_needs_saved)
                            is LlmTestResult.Success -> if (result.model.isBlank()) {
                                str(R.string.settings_llm_test_ok_no_model, result.latencyMs)
                            } else {
                                str(R.string.settings_llm_test_ok, result.latencyMs, result.model)
                            }
                            is LlmTestResult.Failure -> when (result.code) {
                                // Provider 在禁网时强制零请求：展示“已拦截”而非笼统连接失败
                                LlmErrorCode.OFFLINE ->
                                    str(R.string.settings_llm_test_network_blocked)
                                else -> buildString {
                                    append(
                                        str(
                                            R.string.settings_llm_test_failed,
                                            result.readableMessage(),
                                        ),
                                    )
                                    // 按稳定错误码追加可执行动作提示；未知 code 保留 readableMessage 兜底
                                    append(
                                        when (result.code) {
                                            LlmErrorCode.AUTH -> str(R.string.settings_llm_hint_auth)
                                            LlmErrorCode.INSECURE_URL ->
                                                str(R.string.settings_llm_hint_https)
                                            LlmErrorCode.RATE_LIMITED ->
                                                str(R.string.settings_llm_hint_rate_limited)
                                            LlmErrorCode.TIMEOUT,
                                            LlmErrorCode.NETWORK ->
                                                str(R.string.settings_llm_hint_network)
                                            else -> ""
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                update { it.copy(llmTesting = false) }
            }
        }
    }

    /**
     * 清除本机保存的 LLM 配置（core `clear()` 会删除整份配置：baseUrl/model/加密 key；下次保存
     * 自动重建 Keystore key）。离线检索与已索引数据不受影响；表单本地输入会保留便于重填。
     */
    fun clearLlmKey() {
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        llmTestMessage = str(R.string.settings_llm_clear_key_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            withContext(Dispatchers.IO) { llmStore.clear() }
            update {
                // core 的 clear() 清除整份配置（含 URL/模型），这里如实置空；表单本地输入会保留，
                // 用户可重新填写后保存。离线检索与已索引数据不受影响。
                it.copy(
                    llm = null,
                    llmTestMessage = str(R.string.settings_llm_clear_key_done),
                )
            }
        }
    }

    fun setSagEnabled(enabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setSagEnabled(app, enabled) },
        ) { it.copy(sagEnabled = enabled) }
    }

    fun setAnswerEnabled(enabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setAnswerEnabled(app, enabled) },
        ) { it.copy(answerEnabled = enabled) }
    }

    /** `disabled = true` 表示禁止网络（持久化为 networkAllowed=false，默认即禁网）。 */
    fun setNetworkDisabled(disabled: Boolean) {
        updateToggle(
            failureId = R.string.settings_llm_toggle_failed,
            write = { LlmPreferences.setNetworkAllowed(app, !disabled) },
        ) { it.copy(networkDisabled = disabled) }
    }

    private fun updateToggle(
        failureId: Int,
        write: () -> Unit,
        apply: (SettingsUiState) -> SettingsUiState,
    ) {
        safeLaunch(
            onError = { t -> update { it.copy(llmTestMessage = str(failureId, t.readableMessage())) } },
        ) {
            withContext(Dispatchers.IO) { write() }
            update { apply(it) }
        }
    }

    /** 错误/提示文案中的明文 key 兜底脱敏（正常情况下 core 不返回含 key 的消息）。 */
    private fun redactSecret(message: String, secret: String): String =
        if (secret.isNotBlank()) message.replace(secret, "***") else message

    fun reembedNow() {
        safeLaunch(
            onError = { t -> update { it.copy(error = str(R.string.settings_reembed_enqueue_failed, t.readableMessage())) } },
        ) {
            ReembedScheduler.enqueue(app)
        }
    }

    fun cancelReembed() {
        ReembedScheduler.cancel(app)
    }

    private fun observeReembedProgress() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ReembedScheduler.UNIQUE_WORK).collect { infos ->
                val work = infos.firstOrNull() ?: return@collect
                when (work.state) {
                    WorkInfo.State.RUNNING -> update {
                        it.copy(
                            reembedTotal = work.progress.getInt("total", -1),
                            reembedDone = work.progress.getInt("done", 0),
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> update {
                        it.copy(
                            reembedTotal = work.progress.getInt("total", it.reembedTotal),
                            reembedDone = work.progress.getInt("total", it.reembedDone),
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
