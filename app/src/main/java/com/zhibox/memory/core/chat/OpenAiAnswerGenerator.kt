package com.zhibox.memory.core.chat

import android.content.Context
import com.zhibox.memory.core.llm.KeystoreLlmConfigStore
import com.zhibox.memory.core.llm.LlmConfigStore
import com.zhibox.memory.core.llm.LlmErrorCode
import com.zhibox.memory.core.llm.LlmStoreException
import com.zhibox.memory.core.llm.LlmStreamEvent
import com.zhibox.memory.core.llm.LlmStreamingProvider
import com.zhibox.memory.core.llm.OpenAiCompatibleLlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * B11：真实 LLM 的带引用单轮问答生成器（OpenAI-compatible SSE 流式）。
 *
 * 流程：
 * 1. 证据为空 → **零网络**直接 [AnswerEvent.Failed]（code `NO_EVIDENCE`、固定拒答文案）；
 * 2. 读取 [LlmConfigStore]：未配置/读取失败 → 可读 Failed（不发起请求）；
 * 3. [ChatPrompt] 构造系统提示词 + 本轮 query + 证据（≤10 条、每条 ≤1200 字，无历史）；
 * 4. [LlmStreamingProvider.stream] 收集 Delta → 透传 [AnswerEvent.Delta]；
 * 5. 正常结束后**强制** [CitationValidator.validate]：非法 `[S#]` 替换警示、去重合法引用、
 *    `unverified` 标记无引用答案；最后发一次 [AnswerEvent.Completed]（带 model/latency）。
 *
 * 失败/取消语义：
 * - Provider 失败（网络/超时/鉴权/限流/解析）→ 原样 [AnswerEvent.Failed]（错误码可读、不含 key/正文）；
 * - 协程取消 → 不发送任何 Failed（取消异常原样传播），Provider 负责关闭连接；
 * - 空回答视为 [LlmErrorCode.PARSE]（与 completion 一致），不会发 Completed 空文本。
 *
 * 隐私：本类不写日志、不落库；不改 Lead 契约（[AnswerGenerator]/[AnswerEvent]/[CitationValidator]）。
 */
class OpenAiAnswerGenerator(
    private val provider: LlmStreamingProvider,
    private val store: LlmConfigStore,
    private val config: AnswerGeneratorConfig = AnswerGeneratorConfig(),
) : AnswerGenerator {

    override fun generate(request: AnswerRequest): Flow<AnswerEvent> = flow {
        // 1) 证据为空：直接拒答，零网络（不读 store、不调用 provider）
        val evidence = ChatPrompt.selectEvidence(request.evidence, config.maxEvidence)
        if (evidence.isEmpty()) {
            emit(AnswerEvent.Failed(NO_EVIDENCE_CODE, ChatPrompt.REFUSAL_MESSAGE))
            return@flow
        }
        val query = request.query.trim()
        if (query.isEmpty()) {
            emit(AnswerEvent.Failed(INVALID_QUERY_CODE, "问题不能为空"))
            return@flow
        }

        // 2) 配置读取（未配置/损坏都不发请求）
        val llmConfig = try {
            // Keystore 解密是同步阻塞；不要把主线程（Compose 收集上下文）卡住。
            withContext(Dispatchers.IO) { store.load() }
        } catch (e: LlmStoreException) {
            emit(
                AnswerEvent.Failed(
                    LlmErrorCode.STORE,
                    "读取 LLM 配置失败：${e.message ?: "请重新保存 API Key"}",
                ),
            )
            return@flow
        }
        if (llmConfig == null) {
            emit(
                AnswerEvent.Failed(
                    LlmErrorCode.NOT_CONFIGURED,
                    "未配置远程 LLM：请先在设置中填写 API 配置后重试（离线检索不受影响）",
                ),
            )
            return@flow
        }

        // 3) 提示词（与 CitationValidator 使用同一 evidence 列表，编号 S1..Sn 对齐）
        val systemPrompt = ChatPrompt.systemPrompt(config.systemPromptVersion)
        val userPrompt = ChatPrompt.userPrompt(
            query = query,
            evidence = evidence,
            maxEvidence = config.maxEvidence,
            maxCharsPerEvidence = config.maxCharsPerEvidence,
        )

        // 4) 流式生成：Delta 透传；Failed 记录；Completed 只记 model/latency
        val answer = StringBuilder()
        var failed: AnswerEvent.Failed? = null
        var completed: LlmStreamEvent.Completed? = null
        provider.stream(llmConfig, systemPrompt, userPrompt, config.maxTokens).collect { event ->
            when (event) {
                is LlmStreamEvent.Delta -> {
                    if (event.text.isNotEmpty()) {
                        answer.append(event.text)
                        emit(AnswerEvent.Delta(event.text))
                    }
                }
                is LlmStreamEvent.Completed -> {
                    completed = event
                }
                is LlmStreamEvent.Failed -> {
                    failed = AnswerEvent.Failed(event.code, event.message)
                }
            }
        }
        failed?.let { failure ->
            emit(failure)
            return@flow
        }
        val rawAnswer = answer.toString()
        if (rawAnswer.isBlank()) {
            emit(
                AnswerEvent.Failed(
                    LlmErrorCode.PARSE,
                    "LLM 未返回内容：请重试，或检查模型/输出上限设置",
                ),
            )
            return@flow
        }

        // 模型按提示词主动拒答：不把「资料不足」当作未通过引用校验的答案持久化。
        if (rawAnswer.trim() == ChatPrompt.REFUSAL_MESSAGE) {
            emit(AnswerEvent.Failed(NO_EVIDENCE_CODE, ChatPrompt.REFUSAL_MESSAGE))
            return@flow
        }

        // 5) 强制引用校验（Lead 契约：任何 Completed 前必须经过 CitationValidator）
        val validation = CitationValidator.validate(rawAnswer, evidence)
        emit(
            AnswerEvent.Completed(
                text = validation.text,
                citations = validation.citations,
                invalidCitationTokens = validation.invalidCitationTokens,
                unverified = validation.unverified,
                unreferencedSentenceCount = validation.unreferencedSentenceCount,
                model = completed?.model?.ifBlank { llmConfig.model } ?: llmConfig.model,
                latencyMs = completed?.latencyMs ?: 0L,
            ),
        )
    }

    companion object {
        /** 无证据拒绝码（Lead 契约：`AnswerEvent.Failed(code, message)`，不做新事件类型）。 */
        const val NO_EVIDENCE_CODE: String = "NO_EVIDENCE"

        /** 空 query 拒绝码（调用方不应发起空问题；防御性失败）。 */
        const val INVALID_QUERY_CODE: String = "INVALID_QUERY"

        /** 生产构造：真实流式 Provider + Keystore 配置存储（网络策略实时读设置，默认禁网）。 */
        fun from(context: Context): OpenAiAnswerGenerator =
            OpenAiAnswerGenerator(
                provider = OpenAiCompatibleLlmProvider.from(context),
                store = KeystoreLlmConfigStore(context),
            )
    }
}
