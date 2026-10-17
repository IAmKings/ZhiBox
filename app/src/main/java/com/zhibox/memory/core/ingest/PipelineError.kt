package com.zhibox.memory.core.ingest

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 导入/重嵌入失败的结构化错误分类（P2-B2-7）。
 *
 * 背景：此前 `ingest_job.error` 只存 `"${e.javaClass.simpleName}: ${e.message}"` 纯文本，
 * UI 没有稳定依据决定"重试 / 换模型 / 重新下载 / 重导入"。这里定义：
 *
 * - [PipelineErrorCode]：稳定错误码（写入数据库，禁止改名；UI/脚本可按 code 分支）；
 * - [PipelineError]：分类后的错误（可读用户文案 + 是否可自动重试）；
 * - 持久化格式：`CODE:detail`（detail 单行、截断 500 字）。**向后兼容**：旧纯文本 error
 *   解析为 `UNKNOWN:<原文>`，仍可展示。
 *
 * 使用约定：
 * - 写库前调用 [encode]；UI/诊断读取时调用 [decode]；
 * - 取消（CancellationException）不应写成失败（B1-10 已重排队），`classify` 仅用于兜底诊断。
 */
enum class PipelineErrorCode {
    /** 本机没有可用模型文件（filesDir/models 为空或偏好目标缺失） */
    MODEL_MISSING,

    /** 模型文件存在但加载/校验失败（SHA 不符、损坏、维度漂移） */
    MODEL_CORRUPT,

    /** 当前模型与库中向量空间不匹配（或空间无向量） */
    SPACE_MISMATCH,

    /** IO 失败（源文件被删/权限/磁盘满等） */
    IO,

    /** 内存不足（OOM 等 Error，通常不可自动重试） */
    OOM,

    /** 被取消/中断（仅诊断展示；业务路径不应落失败） */
    CANCELLED,

    /** 未分类 */
    UNKNOWN,
}

sealed interface PipelineError {
    val code: PipelineErrorCode

    /** 单行、可持久化的细节（可能为 null） */
    val detail: String?

    /** 展示给用户的单行可读文案 */
    val userMessage: String

    /** 是否适合自动重试：`failOrRequeue` 仅在 true 且 attempt < 上限时才自动重排队，
     *  否则直接终态 failed（由 UI 提供手动重试入口）。 */
    val retryable: Boolean

    data class ModelMissing(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.MODEL_MISSING
        override val userMessage = "未找到可用模型（请在设置/模型商店下载后重试）"
        override val retryable = true
    }

    data class ModelCorrupt(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.MODEL_CORRUPT
        override val userMessage = "模型文件损坏或与备份版本不匹配（请重新下载/校验模型）"
        override val retryable = false
    }

    data class SpaceMismatch(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.SPACE_MISMATCH
        override val userMessage = "当前模型与知识库向量空间不一致（请切回对应模型或重新导入）"
        override val retryable = false
    }

    data class Io(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.IO
        override val userMessage = "读取/写入文件失败（请检查源文件是否仍存在或磁盘空间）"
        override val retryable = true
    }

    data class OutOfMemory(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.OOM
        override val userMessage = "内存不足（请关闭其他应用或减小单文档大小后重试）"
        override val retryable = false
    }

    data class Cancelled(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.CANCELLED
        override val userMessage = "导入已被取消"
        override val retryable = true
    }

    data class Unknown(override val detail: String?) : PipelineError {
        override val code = PipelineErrorCode.UNKNOWN
        override val userMessage = detail?.takeIf { it.isNotBlank() } ?: "导入失败（未知原因）"
        override val retryable = true
    }

    companion object {
        private const val MAX_DETAIL = 500
        private const val SEPARATOR = ':'

        /**
         * 异常 → 结构化错误。分类依据尽量用稳定类型/消息前缀；未知异常一律 [Unknown]，
         * 保证任何失败都有可展示文案（不会因为分类失败而丢失错误）。
         */
        fun classify(t: Throwable): PipelineError {
            val detail = sanitizeDetail(t.message ?: t.javaClass.simpleName)
            return when {
                t is CancellationException -> Cancelled(detail)
                t is OutOfMemoryError -> OutOfMemory(detail)
                t is IOException -> Io(detail)
                t.message?.contains("未找到可用模型", ignoreCase = true) == true ||
                    t.message?.contains("缺少模型", ignoreCase = true) == true ||
                    t.message?.contains("no loadable", ignoreCase = true) == true -> ModelMissing(detail)
                t.message?.contains("dimension drift", ignoreCase = true) == true ||
                    t.message?.contains("SHA-256 不符", ignoreCase = true) == true ||
                    t.message?.contains("模型尺寸不符", ignoreCase = true) == true ||
                    t.message?.contains("模型完整性校验失败", ignoreCase = true) == true -> ModelCorrupt(detail)
                t.message?.contains("向量空间", ignoreCase = true) == true ||
                    t.message?.contains("空间无数据", ignoreCase = true) == true -> SpaceMismatch(detail)
                else -> Unknown(detail)
            }
        }

        /**
         * 写入 `ingest_job.error` 的稳定格式：`CODE:detail`（detail 可空）。
         * detail 强制单行化并截断，避免换行破坏日志/UI 单行展示（审查 H2）。
         */
        fun encode(error: PipelineError): String =
            error.code.name + SEPARATOR + (sanitizeDetail(error.detail) ?: "")

        /** 单行化 + trim + 截断（encode/decode/classify 共用，保证持久化格式稳定）。 */
        private fun sanitizeDetail(raw: String?): String? =
            raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_DETAIL)?.takeIf { it.isNotEmpty() }

        /**
         * 解析持久化 error。兼容旧格式（无 `CODE:` 前缀的纯文本 → `UNKNOWN:<原文>`）。
         * 返回 null 表示没有错误信息（如 queued/done 的 null error）。
         */
        fun decode(raw: String?): PipelineError? {
            if (raw.isNullOrBlank()) return null
            val idx = raw.indexOf(SEPARATOR)
            if (idx <= 0) return Unknown(sanitizeDetail(raw))
            val code = PipelineErrorCode.entries.firstOrNull { it.name == raw.substring(0, idx) }
                ?: return Unknown(sanitizeDetail(raw))
            val detail = sanitizeDetail(raw.substring(idx + 1))
            return when (code) {
                PipelineErrorCode.MODEL_MISSING -> ModelMissing(detail)
                PipelineErrorCode.MODEL_CORRUPT -> ModelCorrupt(detail)
                PipelineErrorCode.SPACE_MISMATCH -> SpaceMismatch(detail)
                PipelineErrorCode.IO -> Io(detail)
                PipelineErrorCode.OOM -> OutOfMemory(detail)
                PipelineErrorCode.CANCELLED -> Cancelled(detail)
                PipelineErrorCode.UNKNOWN -> Unknown(detail)
            }
        }
    }
}


/** 展示用：把 job.error（可能为编码字符串）转成用户可读文案。 */
fun jobErrorUserMessage(raw: String?): String? = PipelineError.decode(raw)?.userMessage
