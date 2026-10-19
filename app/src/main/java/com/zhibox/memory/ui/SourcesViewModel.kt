package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * 来源屏状态（P1-8）：source/document 的 Room Flow 在 ViewModel 中只订阅一次并
 * `stateIn`（顺带修 P2-12：此前每次重组都会在 composition 里新建 Flow 并重订阅）；
 * 删除来源包异常边界，失败给可读提示而不是崩溃。
 */
class SourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IngestRepository(application)
    private val db = AppDatabase.get(application)

    /** B3-5 审查修复：首次 DB 结果到达前显示 LoadingState，避免"先空态再列表"闪烁。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val sources: StateFlow<List<SourceEntity>> = db.sourceDao().observeAll(SourceStatus.ACTIVE.value)
        .onEach { _loaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<DocumentEntity>> = db.documentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * B9：每个 document 的 SAG 抽取聚合状态（来源页逐文档展示）。
     * 抽取任务变化没有 Flow 观察器，这里在 documents 变化与页面进入时按 byDocument 聚合刷新。
     */
    private val _extractionByDocument = MutableStateFlow<Map<String, ExtractionDocUi>>(emptyMap())
    val extractionByDocument: StateFlow<Map<String, ExtractionDocUi>> =
        _extractionByDocument.asStateFlow()

    init {
        viewModelScope.launch {
            documents.collect { docs ->
                try {
                    refreshExtractionFor(docs)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // 抽取状态属诊断信息：失败不阻塞来源页原有文档状态展示
                }
            }
        }
    }

    private suspend fun refreshExtractionFor(docs: List<DocumentEntity>) {
        if (docs.isEmpty()) {
            _extractionByDocument.value = emptyMap()
            return
        }
        val map = withContext(Dispatchers.IO) {
            // R2 复审修复（P2-12）：一次（分批）IN 查询聚合——旧实现逐文档 byDocument，
            // documents 每次发射都放大为 N 次查询。分批 ≤400 对齐 SQLite 变量上限。
            val byDoc = docs.map { it.id }.distinct()
                .chunked(SQL_BATCH)
                .flatMap { ids -> db.extractionJobDao().byDocumentIds(ids) }
                .groupBy { it.documentId }
            docs.associate { doc -> doc.id to ExtractionDocUi.from(byDoc[doc.id].orEmpty()) }
        }
        _extractionByDocument.value = map
    }

    /** B9：页面进入时主动刷新一次抽取状态（任务在后台变化时避免长时间陈旧）。 */
    fun refreshExtractionStatuses() {
        safeLaunch {
            refreshExtractionFor(documents.value)
        }
    }

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun clearDeleteError() {
        _deleteError.value = null
    }

    fun deleteSource(sourceId: String, onSuccess: () -> Unit = {}) {
        if (_deleting.value) return
        _deleting.value = true
        safeLaunch(
            onError = {
                _deleteError.value = getApplication<Application>().getString(
                    R.string.sources_delete_failed,
                    it.readableMessage(),
                )
            },
        ) {
            try {
                repository.deleteSource(sourceId)
                _deleteError.value = null
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } finally {
                _deleting.value = false
            }
        }
    }

    private companion object {
        /** P2-12：byDocumentIds 的 IN 分批（SQLite 变量上限 999，留余量） */
        const val SQL_BATCH = 400
    }
}
