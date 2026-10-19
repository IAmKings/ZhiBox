package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zhibox.memory.R
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** B12：来源图谱的来源筛选项（id=null 表示全部来源）。 */
data class GraphSourceFilter(val id: String?, val title: String)

/** B12：图谱事件卡片 UI 模型（已批量补齐 source/document/entity 名称）。 */
data class GraphEventUi(
    val id: Long,
    val chunkId: Long,
    val title: String,
    val summary: String,
    val sourceId: String,
    val sourceTitle: String,
    val documentId: String,
    val documentTitle: String,
    /** 实体显示名（最多展示 8 个 chip） */
    val entityNames: List<String>,
    /** 关联实体总数（>8 时卡片显示 +N） */
    val entityTotal: Int,
)

data class GraphEntityUi(
    val id: Long,
    val name: String,
    val type: String?,
    val description: String,
)

/** B12：事件详情中的"共享实体串联事件"（entity 连接视图）。 */
data class GraphRelatedEventUi(
    val id: Long,
    val title: String,
    /** 与当前事件共享的实体显示名（首个命中实体）。 */
    val sharedEntityName: String,
)

/** B12：事件详情（entities 全量 + chunk 原文 + 相关事件）。 */
data class GraphEventDetailUi(
    val event: GraphEventUi,
    val entities: List<GraphEntityUi>,
    val chunkContent: String?,
    val chunkOrdinal: Int?,
    val charStart: Int?,
    val charEnd: Int?,
    /** 与当前事件共享实体的其它 active 事件（最多 [GraphViewModel.RELATED_EVENTS_LIMIT] 个） */
    val relatedEvents: List<GraphRelatedEventUi> = emptyList(),
)

/** B12 图谱 UiState：分页（每页 ≤50，总渲染上限 [GraphViewModel.MAX_RENDERED_EVENTS]=200）。 */
data class GraphUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val sources: List<GraphSourceFilter> = emptyList(),
    /** null=全部来源 */
    val selectedSourceId: String? = null,
    val events: List<GraphEventUi> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val detailLoading: Boolean = false,
    val selectedEvent: GraphEventDetailUi? = null,
)

/**
 * B12 来源图谱 ViewModel：懒 DB；source 过滤 + active event 分页（EventDao.activeGraphPage /
 * countActiveGraph），详情用 EventEntityDao.byEvents / EntityDao.byIds / ChunkDao.chunksByIds /
 * DocumentDao.byIds / SourceDao.byIds 批量补齐（避免逐行 N+1）。
 *
 * 约束：每页 [PAGE_SIZE]=50、总渲染上限 [MAX_RENDERED_EVENTS]=200；防重入；IO 线程；取消重抛。
 */
class GraphViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication()
    private val db: AppDatabase by lazy { AppDatabase.get(app) }

    private val _state = MutableStateFlow(GraphUiState())
    val state: StateFlow<GraphUiState> = _state.asStateFlow()

    /** B12：请求代际/Job，防止切源/切 tab/loadMore 旧结果回写造成混入。 */
    private var requestSeq: Int = 0
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun update(block: (GraphUiState) -> GraphUiState) {
        _state.update(block)
    }

    /** 页面进入：加载来源筛选 + 第一页事件（内部防重入）。 */
    fun onScreenStarted() {
        val seq = ++requestSeq
        loadJob?.cancel()
        loadMoreJob?.cancel()
        update { it.copy(loading = true, error = null) }
        val selectedAtStart = _state.value.selectedSourceId
        loadJob = safeLaunch(
            onError = { t ->
                if (seq == requestSeq) {
                    update {
                        it.copy(loading = false, error = str(R.string.graph_load_failed, t.readableMessage()))
                    }
                }
            },
        ) {
            try {
                val sources = withContext(Dispatchers.IO) {
                    db.sourceDao().all()
                        .filter { it.status == SourceStatus.ACTIVE.value }
                        .map { GraphSourceFilter(id = it.id, title = it.title) }
                }
                if (seq != requestSeq || _state.value.selectedSourceId != selectedAtStart) return@safeLaunch
                val snapshot = withContext(Dispatchers.IO) {
                    loadPageSnapshot(selectedAtStart, offset = 0)
                }
                if (seq != requestSeq || _state.value.selectedSourceId != selectedAtStart) return@safeLaunch
                update {
                    it.copy(
                        loading = false,
                        error = null,
                        sources = listOf(
                            GraphSourceFilter(id = null, title = str(R.string.graph_filter_all)),
                        ) + sources,
                        events = snapshot.events,
                        totalCount = snapshot.totalCount,
                        hasMore = snapshot.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (seq == requestSeq) {
                    update {
                        it.copy(loading = false, error = str(R.string.graph_load_failed, t.readableMessage()))
                    }
                }
            }
        }
    }

    /** 来源筛选（null=全部）：重置分页并重新加载第一页。 */
    fun selectSource(sourceId: String?) {
        if (_state.value.loading) return
        update { it.copy(selectedSourceId = sourceId, events = emptyList(), error = null) }
        onScreenStarted()
    }

    /** 加载更多（offset=已渲染数；达到 200 上限后不再请求）。 */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        if (current.events.size >= MAX_RENDERED_EVENTS) {
            update { it.copy(hasMore = false) }
            return
        }
        val seq = requestSeq
        val sourceAtStart = current.selectedSourceId
        update { it.copy(loadingMore = true, error = null) }
        loadMoreJob?.cancel()
        loadMoreJob = safeLaunch(
            onError = { t ->
                if (seq == requestSeq) {
                    update {
                        it.copy(loadingMore = false, error = str(R.string.graph_load_failed, t.readableMessage()))
                    }
                }
            },
        ) {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    loadPageSnapshot(sourceAtStart, offset = current.events.size)
                }
                // 旧请求/旧来源不得回写（切源竞态修复）
                if (seq != requestSeq || _state.value.selectedSourceId != sourceAtStart) return@safeLaunch
                val merged = (current.events + snapshot.events)
                    .distinctBy { it.id }
                    .take(MAX_RENDERED_EVENTS)
                update {
                    it.copy(
                        loadingMore = false,
                        events = merged,
                        totalCount = snapshot.totalCount,
                        hasMore = snapshot.hasMore && merged.size < MAX_RENDERED_EVENTS,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (seq == requestSeq) {
                    update {
                        it.copy(loadingMore = false, error = str(R.string.graph_load_failed, t.readableMessage()))
                    }
                }
            }
        }
    }

    /** 打开事件详情（覆盖层）：批量补齐实体与 owning chunk 原文。 */
    fun openEvent(event: GraphEventUi) {
        if (_state.value.detailLoading) return
        update { it.copy(detailLoading = true, error = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        detailLoading = false,
                        error = str(R.string.graph_detail_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val detail = withContext(Dispatchers.IO) { loadDetail(event) }
                update { it.copy(detailLoading = false, selectedEvent = detail) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(
                        detailLoading = false,
                        error = str(R.string.graph_detail_failed, t.readableMessage()),
                    )
                }
            }
        }
    }

    fun closeEvent() {
        update { it.copy(selectedEvent = null) }
    }

    fun dismissError() {
        update { it.copy(error = null) }
    }

    /** 重试：清空当前页后刷新（保留来源筛选）。 */
    fun retry() {
        update { it.copy(error = null, events = emptyList(), selectedEvent = null) }
        onScreenStarted()
    }

    // ───────────────────────── IO 数据组装 ─────────────────────────

    private data class PageSnapshot(val events: List<GraphEventUi>, val totalCount: Int, val hasMore: Boolean)

    private suspend fun loadPageSnapshot(sourceId: String?, offset: Int): PageSnapshot {
        val eventDao = db.eventDao()
        val totalCount = eventDao.countActiveGraph(sourceId)
        val page = eventDao.activeGraphPage(sourceId, PAGE_SIZE, offset)
        val events = enrich(page)
        val rendered = offset + events.size
        val hasMore = page.isNotEmpty() &&
            rendered < totalCount &&
            rendered < MAX_RENDERED_EVENTS
        return PageSnapshot(events = events, totalCount = totalCount, hasMore = hasMore)
    }

    /** 批量补齐 event → chunk/document/source/entity（避免逐行查询）。 */
    private suspend fun enrich(events: List<EventEntity>): List<GraphEventUi> {
        if (events.isEmpty()) return emptyList()
        val chunks = db.chunkDao().chunksByIds(events.map { it.chunkId }.distinct())
            .associateBy { it.id }
        val documents = db.documentDao().byIds(chunks.values.map { it.documentId }.distinct())
            .associateBy { it.id }
        val sources = db.sourceDao().byIds(documents.values.map { it.sourceId }.distinct())
            .associateBy { it.id }
        val relations = db.eventEntityDao().byEvents(events.map { it.id })
        val entities = db.entityDao().byIds(relations.map { it.entityId }.distinct())
            .associateBy { it.id }
        val relationsByEvent = relations.groupBy { it.eventId }
        return events.map { event ->
            val chunk = chunks[event.chunkId]
            val document = chunk?.let { documents[it.documentId] }
            val source = document?.let { sources[it.sourceId] }
            val names = relationsByEvent[event.id].orEmpty()
                .mapNotNull { relation ->
                    val entity = entities[relation.entityId]
                    entity?.name?.takeIf { it.isNotBlank() } ?: entity?.normalizedName
                }
            GraphEventUi(
                id = event.id,
                chunkId = event.chunkId,
                title = event.title.takeIf { it.isNotBlank() }
                    ?: event.canonicalText.take(60).ifBlank { str(R.string.graph_event_untitled) },
                summary = event.summary,
                sourceId = source?.id ?: "",
                sourceTitle = source?.title ?: "",
                documentId = document?.id ?: "",
                documentTitle = document?.title ?: "",
                entityNames = names.take(ENTITY_CHIP_LIMIT),
                entityTotal = names.size,
            )
        }
    }

    private suspend fun loadDetail(event: GraphEventUi): GraphEventDetailUi {
        val base = loadDetailBase(event)
        return base.copy(relatedEvents = loadRelatedEvents(event.id, base.entities))
    }

    private suspend fun loadDetailBase(event: GraphEventUi): GraphEventDetailUi {
        val chunk = db.chunkDao().chunksByIds(listOf(event.chunkId)).firstOrNull()
        val relations = db.eventEntityDao().byEvents(listOf(event.id))
        val entities = db.entityDao().byIds(relations.map { it.entityId }.distinct())
        return GraphEventDetailUi(
            event = event,
            entities = relations.mapNotNull { relation ->
                entities.firstOrNull { it.id == relation.entityId }?.let { entity ->
                    GraphEntityUi(
                        id = entity.id,
                        name = entity.name.ifBlank { entity.normalizedName },
                        type = entity.entityType,
                        description = entity.description,
                    )
                }
            }.distinctBy { it.id },
            chunkContent = chunk?.content,
            chunkOrdinal = chunk?.ordinal,
            charStart = chunk?.charStart,
            charEnd = chunk?.charEnd,
        )
    }

    /**
     * B12 entity 共享连接：对每个实体取 active event ids（limit=20），排除当前 event、
     * 按 eventId 去重（保留第一个 sharedEntityName），批量 byIds 后最多返回 20 个。
     */
    private suspend fun loadRelatedEvents(
        currentEventId: Long,
        entities: List<GraphEntityUi>,
    ): List<GraphRelatedEventUi> {
        if (entities.isEmpty()) return emptyList()
        val dao = db.eventEntityDao()
        val shared = LinkedHashMap<Long, String>()
        for (entity in entities) {
            val ids = dao.activeEventIdsByEntityIds(listOf(entity.id), RELATED_QUERY_LIMIT)
            for (id in ids) {
                if (id == currentEventId) continue
                shared.putIfAbsent(id, entity.name)
            }
        }
        if (shared.isEmpty()) return emptyList()
        val events = db.eventDao().byIds(shared.keys.toList()).associateBy { it.id }
        return shared.entries
            .mapNotNull { (id, sharedEntityName) ->
                val event = events[id] ?: return@mapNotNull null
                GraphRelatedEventUi(
                    id = id,
                    title = event.title.takeIf { it.isNotBlank() }
                        ?: event.canonicalText.take(60).ifBlank {
                            str(R.string.graph_event_untitled)
                        },
                    sharedEntityName = sharedEntityName,
                )
            }
            .take(RELATED_EVENTS_LIMIT)
    }

    /** 按 eventId 打开相关事件详情（复用 enrich + loadDetail；详情加载中防重入）。 */
    fun openRelatedEvent(eventId: Long) {
        if (_state.value.detailLoading) return
        update { it.copy(detailLoading = true, error = null) }
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        detailLoading = false,
                        error = str(R.string.graph_detail_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            try {
                val detail = withContext(Dispatchers.IO) {
                    val entity = db.eventDao().byId(eventId)
                        ?: return@withContext null
                    val graphEvent = enrich(listOf(entity)).firstOrNull()
                        ?: return@withContext null
                    loadDetail(graphEvent)
                }
                if (detail == null) {
                    update {
                        it.copy(
                            detailLoading = false,
                            error = str(R.string.graph_detail_failed, str(R.string.graph_event_missing)),
                        )
                    }
                } else {
                    update { it.copy(detailLoading = false, selectedEvent = detail) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                update {
                    it.copy(
                        detailLoading = false,
                        error = str(R.string.graph_detail_failed, t.readableMessage()),
                    )
                }
            }
        }
    }

    companion object {
        /** 每页事件数（task 要求 ≤50）。 */
        const val PAGE_SIZE = 50

        /** 总渲染上限（task 要求 ≤200）。 */
        const val MAX_RENDERED_EVENTS = 200

        /** 卡片实体 chips 展示上限（超出显示 +N）。 */
        const val ENTITY_CHIP_LIMIT = 8

        /** 每个实体查询相关 active event 的每实体上限（task 契约 limit=20）。 */
        const val RELATED_QUERY_LIMIT = 20

        /** 详情中"相关事件"展示上限。 */
        const val RELATED_EVENTS_LIMIT = 20
    }
}
