package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.search.SearchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 检索屏状态（P1-8 + B3-2）：
 * - ViewModel 挂在 Activity 的 ViewModelStore 上（无导航库场景下的 `viewModel()` 默认 owner），
 *   所以在检索屏 ↔ 证据详情 ↔ 其它屏之间往返时检索词与结果不丢；配置变更（旋转/深色切换）
 *   同样保留。
 * - **B3-2 首查反馈**：`EmbeddingRuntime.isEngineWarm(context)` 为 false（模型/GPU 模式 key 未命中）
 *   说明本次检索会走"模型构建/首推理"（约 5-10s）；此时 `loadingModel = true`，UI 展示
 *   "首次检索需在端侧加载模型…"。模型已加载（warm，含导入流程已构建引擎）时不再展示。
 * - 扫描/嵌入等重活由 core `SearchService` 负责（P0-2 已切 Dispatchers.Default）；这里再
 *   包一层 `Dispatchers.Default`，保证即使 core 侧回退也不会阻塞 Main。
 * - `CancellationException` 原样重抛，不当业务失败；`running`/`loadingModel` 在 finally 复位。
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val service = SearchService(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** B3-2：本次检索是否处于“进程内首次加载端侧模型”阶段（warm 检索为 false）。 */
    private val _loadingModel = MutableStateFlow(false)
    val loadingModel: StateFlow<Boolean> = _loadingModel.asStateFlow()

    private val _result = MutableStateFlow<SearchService.SearchResult?>(null)
    val result: StateFlow<SearchService.SearchResult?> = _result.asStateFlow()

    /** 证据详情选中项：放在 ViewModel 而非 Composable remember——旋转后仍停留在证据页。 */
    private val _selectedHit = MutableStateFlow<SearchService.SearchHit?>(null)
    val selectedHit: StateFlow<SearchService.SearchHit?> = _selectedHit.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun selectHit(hit: SearchService.SearchHit) {
        _selectedHit.value = hit
    }

    fun clearSelectedHit() {
        _selectedHit.value = null
    }

    /** 清除错误结果（ErrorState 的动作）。 */
    fun retryAfterError() {
        _result.value = null
    }

    fun search() {
        val q = _query.value.trim()
        if (_running.value || q.isEmpty()) return
        safeLaunch {
            _running.value = true
            // B3-2 审查修复：按“当前模型+GPU 模式”的引擎 key 判断，换模型/偏好导致的重建
            // 也会显示阶段提示；只有同 key 已构建（warm）才不提示。
            _loadingModel.value = !EmbeddingRuntime.isEngineWarm(getApplication<Application>())
            try {
                _result.value = withContext(Dispatchers.Default) { service.search(q) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _result.value = failureResult(q, t)
            } finally {
                _running.value = false
                _loadingModel.value = false
            }
        }
    }

    private fun failureResult(query: String, t: Throwable) = SearchService.SearchResult(
        query = query,
        spaceId = null,
        providerId = null,
        modelId = null,
        vectorCandidates = 0,
        keywordCandidates = 0,
        elapsedMs = 0,
        hits = emptyList(),
        refused = getApplication<Application>().getString(
            R.string.search_failed,
            t.readableMessage(),
        ),
    )
}
