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
}
