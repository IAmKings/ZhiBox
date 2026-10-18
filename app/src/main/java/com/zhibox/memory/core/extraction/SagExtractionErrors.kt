package com.zhibox.memory.core.extraction

import com.zhibox.memory.core.extract.ExtractionErrorCodes
import com.zhibox.memory.core.llm.LlmErrorCode

/**
 * B9 抽取管线稳定错误码（写入 `extraction_job.errorCode`；UI/测试可按码分支，禁止随意改名）。
 *
 * 命名与 core/llm、导入管线 `CODE:detail` 风格一致；`errorDetail` 为可读原因，
 * **不得包含 API Key 或 chunk/LLM 正文**（见 [SagExtractionSupport.safeErrorDetail]）。
 */
object SagExtractionErrorCodes {
    /** 端侧嵌入模型缺失（filesDir/models 无可用模型）——可重试（用户下载/启用模型后恢复） */
    const val EMBEDDING_UNAVAILABLE: String = "EMBEDDING_UNAVAILABLE"

    /** 端侧嵌入执行失败（引擎异常/数量不匹配）——可重试（引擎瞬时错误） */
    const val EMBEDDING_FAILED: String = "EMBEDDING_FAILED"

    /** 抽取结果未通过业务校验（isValid=false / title 为空）——终态（重试无意义） */
    const val INVALID_OUTPUT: String = "SAG_INVALID_OUTPUT"

    /** chunk 行不存在（FK 级联后不应发生，防御）——终态 */
    const val CHUNK_MISSING: String = "SAG_CHUNK_MISSING"

    /** 文档不存在或已不再 ready（被取消/重导入）——终态；job 可能被整篇取消 */
    const val DOCUMENT_NOT_READY: String = "SAG_DOCUMENT_NOT_READY"

    /** 未预期的内部异常（DB/其他）——可重试（attempt 上限内） */
    const val INTERNAL: String = "SAG_INTERNAL"
}

/**
 * 抽取失败可重试性分类（纯函数，B9 重试策略单测锚点）。
 *
 * 规则（与 task-43 一致）：
 * - 可重试：LLM 限流/超时/网络/5xx、端侧嵌入暂缺/瞬时失败、未预期内部异常；
 * - 终态：鉴权、输出解析/字段/实体类型错误、业务无效输出、配置缺失（NOT_CONFIGURED 单独置
 *   not_indexed，不走失败路径）；
 * - 未知码默认**不重试**（避免不可诊断的失败被无限重试放大）。
 */
object SagErrorClassifier {

    private val RETRYABLE_CODES: Set<String> = setOf(
        ExtractionErrorCodes.LLM_CALL_FAILED,
        LlmErrorCode.TIMEOUT,
        LlmErrorCode.NETWORK,
        LlmErrorCode.HTTP,
        LlmErrorCode.RATE_LIMITED,
        SagExtractionErrorCodes.EMBEDDING_UNAVAILABLE,
        SagExtractionErrorCodes.EMBEDDING_FAILED,
        SagExtractionErrorCodes.INTERNAL,
    )

    /**
     * "暂不可用"错误码集合：应把任务置 `not_indexed`（文档仍 ready，配置补齐后 backfill 重入队），
     * 而不是终态 failed。
     *
     * 覆盖两层来源：
     * - 抽取契约层：EXTRACT_NOT_CONFIGURED；
     * - LLM 层（RemoteLlmEventEntityExtractor 透传）：未配置 / 禁网 / 本地配置存储失败。
     */
    private val NOT_INDEXED_CODES: Set<String> = setOf(
        ExtractionErrorCodes.NOT_CONFIGURED,
        LlmErrorCode.NOT_CONFIGURED,
        LlmErrorCode.OFFLINE,
        LlmErrorCode.STORE,
    )

    fun isRetryable(code: String): Boolean = code in RETRYABLE_CODES

    fun isNotIndexed(code: String): Boolean = code in NOT_INDEXED_CODES
}
