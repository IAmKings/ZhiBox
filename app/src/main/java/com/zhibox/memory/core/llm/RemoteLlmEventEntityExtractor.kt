package com.zhibox.memory.core.llm

import com.zhibox.memory.core.extract.EventEntityExtractor
import com.zhibox.memory.core.extract.ExtractionInput
import com.zhibox.memory.core.extract.ExtractionJson
import com.zhibox.memory.core.extract.ExtractionPrompts
import com.zhibox.memory.core.extract.ExtractionResult

/**
 * 远程 LLM 事件抽取器（B9）：`EventEntityExtractor` 的生产实现。
 *
 * 流程：读取 [LlmConfigStore] 配置 → [ExtractionPrompts.systemPrompt] + [ExtractionPrompts.userPrompt]
 * → [LlmCompletionProvider.complete] → [ExtractionJson.parse]（严格 schema）。
 *
 * 错误语义（全部以 [ExtractionResult.Failure] 返回值表达，不抛业务异常）：
 * - 未配置：`LLM_NOT_CONFIGURED`（离线检索不受影响）；
 * - 本地配置损坏/解密失败：`LLM_STORE_ERROR`（可读 message，引导重新保存配置）；
 * - LLM 调用失败：透传 [LlmCompletionResult.Failure.code]（`LLM_OFFLINE` / `LLM_TIMEOUT` /
 *   `LLM_AUTH_ERROR` / `LLM_RATE_LIMITED` / `LLM_HTTP_ERROR` / `LLM_PARSE_ERROR` 等）+
 *   `readableMessage()`；
 * - 输出不符合抽取 JSON 契约：透传 [ExtractionJson.parse] 的 `EXTRACT_*` 错误码。
 *
 * 隐私约定：不写任何日志；错误 message 只含错误码/状态/解析位置，**不含 chunk 正文或 API Key**；
 * chunk 正文仅作为请求 userPrompt 发送给用户显式配置的 LLM。
 *
 * 线程/取消：[EventEntityExtractor] 为 suspend；网络与 IO 在 Provider/调用方切 IO 线程，
 * `CancellationException` 由 Provider 原样向上传播，本类不吞。
 */
class RemoteLlmEventEntityExtractor(
    private val provider: LlmCompletionProvider,
    private val store: LlmConfigStore,
    /** 生成长度上限（抽取 JSON 通常 <1k tokens；默认 1024，可在 Worker 侧注入）。 */
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : EventEntityExtractor {

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        val config = try {
            store.load()
        } catch (e: LlmStoreException) {
            return ExtractionResult.Failure(
                LlmErrorCode.STORE,
                "LLM 配置读取失败：${e.message?.take(MAX_ERROR_MESSAGE_CHARS) ?: "本地存储异常"}" +
                    "（可尝试清除配置后重新保存 API Key）",
            )
        } ?: return ExtractionResult.Failure(
            LlmErrorCode.NOT_CONFIGURED,
            "未配置远程 LLM：请在设置中填写并保存 API Key（离线检索不受影响）",
        )

        val completion = provider.complete(
            config = config,
            systemPrompt = ExtractionPrompts.systemPrompt(input.entityTypes),
            userPrompt = ExtractionPrompts.userPrompt(input),
            maxTokens = maxTokens,
        )
        return when (completion) {
            is LlmCompletionResult.Failure -> ExtractionResult.Failure(
                code = completion.code,
                message = completion.readableMessage(),
            )
            is LlmCompletionResult.Success -> ExtractionJson.parse(completion.text)
        }
    }

    companion object {
        /** 抽取 completion 的默认 token 上限（与 [LlmCompletionProvider] 默认一致）。 */
        const val DEFAULT_MAX_TOKENS: Int = DEFAULT_COMPLETION_MAX_TOKENS

        private const val MAX_ERROR_MESSAGE_CHARS = 200
    }
}
