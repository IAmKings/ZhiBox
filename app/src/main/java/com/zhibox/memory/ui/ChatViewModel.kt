package com.zhibox.memory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.core.chat.AnswerEvent
import com.zhibox.memory.core.chat.AnswerGenerator
import com.zhibox.memory.core.chat.AnswerRequest
import com.zhibox.memory.core.chat.ChatCitation
import com.zhibox.memory.core.chat.ChatConversation
import com.zhibox.memory.core.chat.ChatEvidence
import com.zhibox.memory.core.chat.ChatMessage
import com.zhibox.memory.core.chat.ChatRepository
import com.zhibox.memory.core.chat.ChatRole
import com.zhibox.memory.core.chat.ChatPrompt
import com.zhibox.memory.core.chat.OpenAiAnswerGenerator
import com.zhibox.memory.core.chat.RoomChatRepository
import com.zhibox.memory.core.extraction.SagAvailability
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmQueryEntityExtractor
import com.zhibox.memory.core.llm.LlmSagContextSelector
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import com.zhibox.memory.core.sag.RetrievalMode
import com.zhibox.memory.core.sag.SagHit
import com.zhibox.memory.core.sag.SagQuery
import com.zhibox.memory.core.sag.SagResult
import com.zhibox.memory.core.sag.SagRetriever
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.QueryTraceEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 带引用问答（B11 P0 单轮）UI 状态。
 *
 * 设计要点：
 * - 单轮：每次只把「本轮问题 + 本轮检索证据」发给 LLM，**不注入历史**（PRD FR-06）；
 * - 引用：只有 `AnswerEvent.Completed`（已过 [com.zhibox.memory.core.chat.CitationValidator]）
 *   才持久化 user + assistant + citations；失败/停止不保存半成品最终答案；
 * - 隐私：首次联网前必须显式同意（[LlmPreferences.answerNetworkConsent]）；默认禁网；
 * - 诊断：每轮写 `query_trace`（mode="qa"，仅 queryHash/耗时/降级原因，无正文）。
 */
data class ChatUiState(
    /** 最近会话（侧栏/列表） */
    val conversations: List<ChatConversation> = emptyList(),
    val current: ChatConversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** assistant messageId → 引用（chips 展示/点击回溯证据） */
    val citations: Map<String, List<ChatCitation>> = emptyMap(),
    val input: String = "",
    /** 显式选择的检索模式；默认 AUTO（运行期按 SAG/LLM 可用性选 multi 或 fast）。 */
    val retrievalMode: RetrievalMode? = null,
    /** 来源多选（空=全库）；与 SagQuery.sourceIds 对齐。 */
    val sourceScope: List<String> = emptyList(),
    val availableSources: List<ChatSourceOption> = emptyList(),
    val streaming: Boolean = false,
    val streamingText: String = "",
    /** 用户停止后保留的部分文本（仅展示，不持久化） */
    val stoppedPartial: String? = null,
    val errorMessage: String? = null,
    /** 资料不足/引用校验警示（可读） */
    val warning: String? = null,
    /** 阶段提示：检索证据中/生成中 */
    val infoMessage: String? = null,
    /** 本轮证据快照（S1..Sn；用于点击 chips 前展示/调试） */
    val evidence: List<ChatEvidence> = emptyList(),
    /** 当前点开的引用证据（复用 EvidenceScreen 展示） */
    val selectedEvidenceHit: SearchService.SearchHit? = null,
    val lastQuery: String? = null,
    val canRetry: Boolean = false,
    // ---- 隐私/可用性 ----
    val answerEnabled: Boolean = false,
    val networkAllowed: Boolean = false,
    val llmConfigured: Boolean = false,
    val consentGranted: Boolean = false,
    val consentRequired: Boolean = false,
    /** 首次联网确认展示的目标 host（来自已保存 baseUrl；不含 key/path）。 */
    val llmHost: String = "",
    // ---- 诊断 ----
    val effectiveMode: String? = null,
    val degradedReason: String? = null,
) {
    /** LLM 问答是否就绪（开关 + 允许联网 + 已配置 Key）。 */
    val llmReady: Boolean get() = answerEnabled && networkAllowed && llmConfigured

    /** 会话展示标题（空列表用「新对话」占位）。 */
    val displayTitle: String get() = current?.title ?: "新对话"
}

data class ChatSourceOption(val id: String, val title: String)

/**
 * Chat ViewModel：懒初始化 DB/SagRetriever/AnswerGenerator（对齐 [SearchViewModel] 模式），
 * 检索走 B10 [SagRetriever]（默认 multi，LLM 未就绪时 multi_es_fast；multi 真实接线
 * `LlmQueryEntityExtractor` + `LlmSagContextSelector`），生成走 B11
 * [OpenAiAnswerGenerator]（内部强制 [com.zhibox.memory.core.chat.CitationValidator]）。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication()

    private val db: AppDatabase by lazy { AppDatabase.get(app) }
    private val llmStore: LlmConfigStore by lazy { KeystoreLlmConfigStore(app) }
    /** B11：真实 provider（禁网策略实时读设置）；仅 lazy 内构造，避免主线程初始化。 */
    private val llmProvider: OpenAiCompatibleLlmProvider by lazy {
        OpenAiCompatibleLlmProvider.from(app)
    }

    /**
     * B10/B11：multi 模式的 LLM 路径必须真实接线——查询实体抽取 + 上下文选择器均使用
     * 同一 provider/store（失败时两者内部确定性降级，不抛业务异常）。
     */
    private val retriever: SagRetriever by lazy {
        SagRetriever(
            context = app,
            db = db,
            llmAvailable = { SagAvailability.isAvailable(app) },
            llmExtractor = LlmQueryEntityExtractor(provider = llmProvider, store = llmStore),
            llmSelector = LlmSagContextSelector(provider = llmProvider, store = llmStore),
        )
    }
    private val repository: ChatRepository by lazy { RoomChatRepository(db.chatDao()) }
    private val answerGenerator: AnswerGenerator by lazy { OpenAiAnswerGenerator.from(app) }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pendingConsentQuery: String? = null
    private var sendJob: Job? = null

    init {
        refreshAvailability()
        loadConversations()
        loadSources()
    }

    private fun str(id: Int): String = app.getString(id)
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun update(block: (ChatUiState) -> ChatUiState) {
        _state.update(block)
    }

    // ───────────────────────── 入口/会话管理 ─────────────────────────

    /** 屏幕进入时刷新 LLM/隐私状态（设置页可能刚改过开关）。 */
    fun onScreenStarted() {
        refreshAvailability()
        loadConversations()
        loadSources()
    }

    fun refreshAvailability() {
        safeLaunch {
            val snapshot = withContext(Dispatchers.IO) {
                val answerEnabled = LlmPreferences.answerEnabled(app)
                val networkAllowed = LlmPreferences.networkAllowed(app)
                val consentGranted = LlmPreferences.answerNetworkConsent(app)
                val cfg = runCatching { llmStore.load() }.getOrNull()
                val configured = cfg != null
                val host = cfg?.baseUrl?.let { base ->
                    runCatching { java.net.URI(base).host }.getOrNull().orEmpty()
                }.orEmpty()
                AvailabilitySnapshot(
                    answerEnabled = answerEnabled,
                    networkAllowed = networkAllowed,
                    consentGranted = consentGranted,
                    configured = configured,
                    host = host,
                )
            }
            update {
                it.copy(
                    answerEnabled = snapshot.answerEnabled,
                    networkAllowed = snapshot.networkAllowed,
                    consentGranted = snapshot.consentGranted,
                    llmConfigured = snapshot.configured,
                    llmHost = snapshot.host,
                )
            }
        }
    }

    fun loadConversations() {
        safeLaunch {
            val list = withContext(Dispatchers.IO) { repository.conversations(CONVERSATION_LIMIT) }
            update { it.copy(conversations = list) }
        }
    }

    /** B11：加载来源列表（仅 active），供 Chat 的 source scope 多选；空=全库。 */
    fun loadSources() {
        safeLaunch {
            val sources = withContext(Dispatchers.IO) {
                db.sourceDao().observeAll(SourceStatus.ACTIVE.value).first()
                    .map { ChatSourceOption(it.id, it.title.ifBlank { it.uri }) }
            }
            update { st ->
                // 来源被删除时自动收缩 scope，避免残留 id
                val valid = st.sourceScope.filter { id -> sources.any { it.id == id } }
                st.copy(availableSources = sources, sourceScope = valid)
            }
        }
    }

    /** B11：切换检索模式；null=自动（multi 可用则 multi，否则 fast）。 */
    fun setRetrievalMode(mode: RetrievalMode?) {
        if (_state.value.streaming) return
        update { it.copy(retrievalMode = mode) }
    }

    /** B11：设置来源范围（空=全库）。 */
    fun setSourceScope(ids: List<String>) {
        if (_state.value.streaming) return
        update { it.copy(sourceScope = ids.distinct()) }
    }

    fun newConversation() {
        if (_state.value.streaming) return
        update {
            it.copy(
                current = null,
                messages = emptyList(),
                citations = emptyMap(),
                evidence = emptyList(),
                streamingText = "",
                stoppedPartial = null,
                errorMessage = null,
                warning = null,
                canRetry = false,
                lastQuery = null,
                effectiveMode = null,
                degradedReason = null,
            )
        }
    }

    fun selectConversation(conversationId: String) {
        if (_state.value.streaming) return
        safeLaunch {
            val loaded = withContext(Dispatchers.IO) {
                val conversation = repository.conversation(conversationId) ?: return@withContext null
                val messages = repository.messages(conversationId)
                val citations = messages
                    .filter { it.role == ChatRole.ASSISTANT }
                    .associate { it.id to repository.citations(it.id) }
                LoadedConversation(conversation, messages, citations)
            }
            if (loaded == null) {
                update { it.copy(errorMessage = str(R.string.chat_failed, "会话不存在")) }
                return@safeLaunch
            }
            update {
                it.copy(
                    current = loaded.conversation,
                    messages = loaded.messages,
                    citations = loaded.citations,
                    evidence = emptyList(),
                    streamingText = "",
                    stoppedPartial = null,
                    errorMessage = null,
                    warning = null,
                    canRetry = false,
                    lastQuery = null,
                )
            }
        }
    }

    // ───────────────────────── 输入/发送/停止/重试 ─────────────────────────

    fun setInput(value: String) {
        update { it.copy(input = value) }
    }

    fun send() {
        val st = _state.value
        if (st.streaming) return
        val query = st.input.trim()
        if (query.isEmpty()) return
        if (!st.llmReady) {
            update { it.copy(errorMessage = llmNotReadyMessage(st)) }
            return
        }
        if (!st.consentGranted) {
            pendingConsentQuery = query
            update { it.copy(consentRequired = true) }
            return
        }
        update { it.copy(input = "") }
        performSend(query)
    }

    /** 首次联网隐私说明确认后继续发送。 */
    fun confirmConsentAndSend() {
        val query = pendingConsentQuery ?: return
        pendingConsentQuery = null
        safeLaunch(
            onError = { t ->
                update {
                    it.copy(
                        consentRequired = false,
                        errorMessage = str(R.string.chat_failed, t.readableMessage()),
                    )
                }
            },
        ) {
            withContext(Dispatchers.IO) { LlmPreferences.setAnswerNetworkConsent(app, true) }
            update { it.copy(consentRequired = false, consentGranted = true, input = "") }
            performSend(query)
        }
    }

    fun dismissConsent() {
        pendingConsentQuery = null
        update { it.copy(consentRequired = false) }
    }

    fun clearError() {
        update { it.copy(errorMessage = null) }
    }

    fun clearWarning() {
        update { it.copy(warning = null) }
    }

    fun clearSelectedEvidence() {
        update { it.copy(selectedEvidenceHit = null) }
    }

    /** 停止生成：取消 Flow，保留部分文本仅展示、不持久化。 */
    fun stopGeneration() {
        sendJob?.cancel()
    }

    /** 重试：同一 query/会话范围重新执行（重新检索 + 生成）。 */
    fun retry() {
        val st = _state.value
        val query = st.lastQuery ?: return
        if (st.streaming) return
        // 与 send 相同的隐私/可用性门禁：关闭问答/禁网/无 key 时不得发起生成。
        if (!st.llmReady) {
            update { it.copy(errorMessage = llmNotReadyMessage(st)) }
            return
        }
        if (!st.consentGranted) {
            pendingConsentQuery = query
            update { it.copy(consentRequired = true) }
            return
        }
        // 重试复用同 query/会话：用户消息已在上一次 send 落库，不重复追加。
        performSend(query, appendUser = false)
    }

    /** 点击引用 chip：按 chunkId 回读原文（chunk→document→source）后复用 EvidenceScreen。 */
    fun openCitation(citation: ChatCitation) {
        safeLaunch(
            onError = { t ->
                update { it.copy(errorMessage = str(R.string.chat_failed, t.readableMessage())) }
            },
        ) {
            val hit = withContext(Dispatchers.IO) {
                val chunk = db.chunkDao().chunksByIds(listOf(citation.chunkId)).firstOrNull()
                    ?: return@withContext null
                val doc = db.documentDao().get(chunk.documentId) ?: return@withContext null
                val source = db.sourceDao().get(doc.sourceId) ?: return@withContext null
                SearchService.SearchHit(
                    chunkId = chunk.id,
                    documentId = chunk.documentId,
                    ordinal = chunk.ordinal,
                    content = chunk.content,
                    charStart = chunk.charStart,
                    charEnd = chunk.charEnd,
                    docTitle = doc.title,
                    sourceTitle = source.title,
                    sourceUri = source.uri,
                    vectorRank = null,
                    keywordRank = null,
                    fusedScore = 0.0,
                    channels = "引用 ${citation.evidenceId}",
                )
            }
            if (hit == null) {
                update { it.copy(errorMessage = str(R.string.chat_evidence_stale)) }
            } else {
                update { it.copy(selectedEvidenceHit = hit) }
            }
        }
    }

    // ───────────────────────── 单轮执行 ─────────────────────────

    private fun performSend(query: String, appendUser: Boolean = true) {
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch { runTurn(query, appendUser) }
    }

    private suspend fun runTurn(query: String, appendUser: Boolean = true) {
        var retrieveMs = 0L
        var generateMs = 0L
        var result: SagResult? = null
        var evidence: List<ChatEvidence> = emptyList()
        update {
            it.copy(
                streaming = true,
                streamingText = "",
                stoppedPartial = null,
                errorMessage = null,
                warning = null,
                evidence = emptyList(),
                effectiveMode = null,
                degradedReason = null,
                canRetry = false,
                lastQuery = query,
            )
        }
        try {
            val conversationId = ensureConversation(query)
            // 先落 user 消息（即使随后失败，用户问题保留；assistant 半成品绝不落库）。
            // 重试（appendUser=false）复用已存在的用户消息，不重复写入。
            if (appendUser) {
                withContext(Dispatchers.IO) {
                    repository.appendMessage(conversationId, ChatRole.USER, query, model = null)
                }
                refreshMessages(conversationId)
            }

            // 1) B10 检索证据：
            // - multi 需要 SAG 增强（SagAvailability）与问答 LLM 同时可用；
            // - 只开“问答生成”而关闭 SAG 时，绝不能选 multi（否则 retriever 直接 refused），
            //   使用 fast/vector 证据 + 问答 LLM 生成。
            val sagAvailable = withContext(Dispatchers.IO) { SagAvailability.isAvailable(app) }
            val mode = when {
                _state.value.retrievalMode == RetrievalMode.MULTI && !(_state.value.llmReady && sagAvailable) ->
                    RetrievalMode.MULTI_ES_FAST
                _state.value.retrievalMode == RetrievalMode.MULTI -> RetrievalMode.MULTI
                _state.value.retrievalMode == RetrievalMode.VECTOR -> RetrievalMode.VECTOR
                _state.value.retrievalMode == RetrievalMode.MULTI_ES_FAST -> RetrievalMode.MULTI_ES_FAST
                _state.value.llmReady && sagAvailable -> RetrievalMode.MULTI
                else -> RetrievalMode.MULTI_ES_FAST
            }
            update { it.copy(infoMessage = str(R.string.chat_retrieving)) }
            val retrieveStarted = System.currentTimeMillis()
            result = withContext(Dispatchers.Default) {
                retriever.retrieve(
                    SagQuery(
                        query = query,
                        mode = mode,
                        topK = TOP_K,
                        sourceIds = _state.value.sourceScope,
                    ),
                )
            }
            retrieveMs = System.currentTimeMillis() - retrieveStarted
            update { it.copy(infoMessage = null) }
            val retrieved = result
            if (retrieved.hits.isEmpty() || retrieved.refused != null) {
                update {
                    it.copy(
                        streaming = false,
                        infoMessage = null,
                        warning = str(R.string.chat_no_evidence),
                        canRetry = true,
                        degradedReason = retrieved.trace.degradedReason,
                        effectiveMode = retrieved.effectiveMode.wire,
                    )
                }
                writeTrace(query, retrieved, retrieveMs, 0L, 0)
                return
            }

            // 2) 证据快照（S1..Sn；最多 10 条，与生成器预算一致）
            evidence = retrieved.hits
                .take(ChatPrompt.DEFAULT_MAX_EVIDENCE)
                .mapIndexed { index, hit -> hit.toChatEvidence(index) }
            update {
                it.copy(
                    evidence = evidence,
                    effectiveMode = retrieved.effectiveMode.wire,
                    degradedReason = retrieved.trace.degradedReason,
                    infoMessage = str(R.string.chat_generating),
                )
            }

            // 3) 流式生成（不注入历史；只发本轮 query+evidence）
            val generateStarted = System.currentTimeMillis()
            var completed: AnswerEvent.Completed? = null
            var failed: AnswerEvent.Failed? = null
            answerGenerator.generate(AnswerRequest(query = query, evidence = evidence)).collect { event ->
                when (event) {
                    is AnswerEvent.Delta -> update { it.copy(streamingText = it.streamingText + event.text) }
                    is AnswerEvent.Completed -> completed = event
                    is AnswerEvent.Failed -> failed = event
                    AnswerEvent.Stopped -> Unit
                }
            }
            generateMs = System.currentTimeMillis() - generateStarted
            update { it.copy(infoMessage = null) }

            val failure = failed
            val done = completed
            when {
                failure != null -> {
                    val message = if (failure.code == OpenAiAnswerGenerator.NO_EVIDENCE_CODE) {
                        str(R.string.chat_no_evidence)
                    } else {
                        str(R.string.chat_failed, failure.message + "（" + failure.code + "）")
                    }
                    update {
                        it.copy(
                            streaming = false,
                            streamingText = "",
                            errorMessage = message,
                            warning = if (failure.code == OpenAiAnswerGenerator.NO_EVIDENCE_CODE) {
                                str(R.string.chat_no_evidence)
                            } else {
                                null
                            },
                            canRetry = true,
                        )
                    }
                }
                done != null -> {
                    // 4) 仅 Completed（已过引用校验）持久化 user + assistant + citations
                    withContext(Dispatchers.IO) {
                        val assistant = repository.appendMessage(
                            conversationId = conversationId,
                            role = ChatRole.ASSISTANT,
                            content = done.text,
                            model = done.model,
                        )
                        repository.appendCitations(assistant.id, done.citations)
                    }
                    refreshMessages(conversationId)
                    loadConversations()
                    val validationWarning = when {
                        done.unverified -> str(R.string.chat_unverified_warning)
                        done.invalidCitationTokens.isNotEmpty() -> str(
                            R.string.chat_invalid_citation_warning,
                            done.invalidCitationTokens.joinToString(" "),
                        )
                        else -> null
                    }
                    update {
                        it.copy(
                            streaming = false,
                            streamingText = "",
                            warning = validationWarning,
                            canRetry = true,
                        )
                    }
                }
                else -> {
                    update {
                        it.copy(
                            streaming = false,
                            streamingText = "",
                            errorMessage = str(R.string.chat_failed, "未返回结果"),
                            canRetry = true,
                        )
                    }
                }
            }
            writeTrace(
                query = query,
                result = result,
                retrieveMs = retrieveMs,
                generateMs = generateMs,
                evidenceCount = evidence.size,
                citationValid = completed?.let { it.citations.isNotEmpty() && !it.unverified && it.invalidCitationTokens.isEmpty() },
                invalidCitations = completed?.invalidCitationTokens?.size ?: 0,
            )
        } catch (e: CancellationException) {
            // 用户停止：保留部分文本仅展示（不落库、不作为最终答案）
            val partial = _state.value.streamingText
            update {
                it.copy(
                    streaming = false,
                    streamingText = "",
                    stoppedPartial = partial.takeIf { text -> text.isNotBlank() },
                    infoMessage = null,
                )
            }
            throw e
        } catch (t: Throwable) {
            update {
                it.copy(
                    streaming = false,
                    streamingText = "",
                    errorMessage = str(R.string.chat_failed, t.readableMessage()),
                    canRetry = true,
                )
            }
        } finally {
            update { it.copy(streaming = false, infoMessage = null) }
        }
    }

    private suspend fun ensureConversation(query: String): String {
        _state.value.current?.let { return it.id }
        val title = query.trim().take(TITLE_MAX_CHARS).ifBlank { str(R.string.chat_new_conversation) }
        val conversation = withContext(Dispatchers.IO) {
            repository.createConversation(
                title = title,
                sourceScope = _state.value.sourceScope,
            )
        }
        update {
            it.copy(
                current = conversation,
                conversations = (listOf(conversation) + it.conversations).take(CONVERSATION_LIMIT),
            )
        }
        return conversation.id
    }

    private suspend fun refreshMessages(conversationId: String) {
        val loaded = withContext(Dispatchers.IO) {
            val messages = repository.messages(conversationId)
            val citations = messages
                .filter { it.role == ChatRole.ASSISTANT }
                .associate { it.id to repository.citations(it.id) }
            messages to citations
        }
        update { it.copy(messages = loaded.first, citations = loaded.second) }
    }

    /** 每轮写 query_trace（仅 queryHash/耗时/降级原因，无正文；失败不影响问答）。 */
    private suspend fun writeTrace(
        query: String,
        result: SagResult?,
        retrieveMs: Long,
        generateMs: Long,
        evidenceCount: Int,
        citationValid: Boolean? = null,
        invalidCitations: Int = 0,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val timing = JSONObject()
                    .put("retrieveMs", retrieveMs)
                    .put("generateMs", generateMs)
                    .put("evidence", evidenceCount)
                    .put("citationValid", citationValid ?: JSONObject.NULL)
                    .put("invalidCitations", invalidCitations)
                    .toString()
                val dao = db.queryTraceDao()
                dao.insert(
                    QueryTraceEntity(
                        queryHash = sha256Hex(query),
                        mode = "qa",
                        timingJson = timing,
                        degradedReason = result?.trace?.degradedReason ?: result?.refused,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                dao.keepRecent(TRACE_KEEP)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 诊断落库失败不影响问答结果
            }
        }
    }

    private fun llmNotReadyMessage(state: ChatUiState): String = when {
        !state.answerEnabled -> str(R.string.chat_answer_disabled)
        !state.networkAllowed -> str(R.string.chat_network_blocked)
        !state.llmConfigured -> str(R.string.chat_llm_not_configured)
        else -> str(R.string.chat_llm_not_ready)
    }

    private fun SagHit.toChatEvidence(index: Int) = ChatEvidence(
        evidenceId = "S${index + 1}",
        chunkId = chunkId,
        // 契约 ChatEvidence 的 documentId/sourceId 为 Long，而本项目数据层 id 是字符串：
        // 这里用稳定哈希占位；引用校验/持久化只使用 chunkId，展示字段均单独携带。
        documentId = documentId.hashCode().toLong(),
        sourceId = sourceId.hashCode().toLong(),
        docTitle = docTitle,
        sourceTitle = sourceTitle,
        sourceUri = sourceUri,
        content = content,
        ordinal = ordinal,
        charStart = charStart,
        charEnd = charEnd,
        eventId = eventId,
        score = fusedScore ?: score,
    )

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    private data class AvailabilitySnapshot(
        val answerEnabled: Boolean,
        val networkAllowed: Boolean,
        val consentGranted: Boolean,
        val configured: Boolean,
        val host: String,
    )

    private data class LoadedConversation(
        val conversation: ChatConversation,
        val messages: List<ChatMessage>,
        val citations: Map<String, List<ChatCitation>>,
    )

    companion object {
        const val TOP_K = 10
        const val CONVERSATION_LIMIT = 50
        const val TITLE_MAX_CHARS = 24
        const val TRACE_KEEP = 50
    }
}
