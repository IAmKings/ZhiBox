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
