package com.zhibox.memory.data.db

/**
 * 状态/阶段枚举单一事实源（P2 B1-5）。
 *
 * 约定（与 Room schema v5 的 TEXT 列对应）：
 * - **写入路径只接受枚举**：实体构造与 DAO 绑定参数一律传 `XxxStatus.value`，代码中不再散落
 *   `"ready"` / `"queued"` 之类的字符串字面量（DAO 的 @Query SQL 也只是 `:param`）；
 * - **读取路径用 [DocStatus.fromValue] 等**：未知/历史/降级写入的值返回 null，调用方原样展示，
 *   保证旧库与未来新增状态不会因为枚举不认识而崩溃或显示空白；
 * - 本文件不依赖 Android，可纯 JVM 单测（`StatusEnumsTest`）。
 *
 * 各枚举的 companion 里同时收口了“跨列语义”的取值集合（活动态/中断态/终态），
 * DAO 的 `IN (:statuses)` 与取消/回收/重试逻辑共用，避免同一组状态在多处手写漂移。
 */

/** source.status：active / deleted（MVP 删除走物理级联，deleted 为降级版本/备份预留） */
enum class SourceStatus(val value: String) {
    ACTIVE("active"),
    DELETED("deleted"),
    ;

    companion object {
        private val byValue: Map<String, SourceStatus> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): SourceStatus? = raw?.let(byValue::get)
    }
}

/** document.status：pending / processing / ready / failed / cancelled（发布语义见 IngestRepository） */
enum class DocStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        /** 未发布状态（pending+processing）：取消导入、失败/取消清理、重试回填 pending 时使用
         *  （*IfNotCancelled 守卫的 guard 值是 CANCELLED，不是本集合） */
        val ACTIVE_VALUES: List<String> = listOf(PENDING, PROCESSING).map { it.value }

        private val byValue: Map<String, DocStatus> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): DocStatus? = raw?.let(byValue::get)
    }
}

/**
 * ingest_job.stage：queued / parsing / chunking / embedding / storing / done / failed / cancelled。
 * 状态机定义见 [com.zhibox.memory.core.ingest.IngestRepository] 类注释。
 */
enum class JobStage(val value: String) {
    QUEUED("queued"),
    PARSING("parsing"),
    CHUNKING("chunking"),
    EMBEDDING("embedding"),
    STORING("storing"),
    DONE("done"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        /** 未终结阶段（queued+处理中）：导入互斥 activeCount、删除守卫、重嵌入让路使用 */
        val ACTIVE_VALUES: List<String> = listOf(QUEUED, PARSING, CHUNKING, EMBEDDING, STORING).map { it.value }

        /** 进程被杀后可能停留的中间态：requeueInterrupted 回收后重做（queued 不需要回收） */
        val INTERRUPTED_VALUES: List<String> = listOf(PARSING, CHUNKING, EMBEDDING, STORING).map { it.value }

        /** 终态：UI 不再显示进度条，也没有自动流转 */
        val TERMINAL: Set<JobStage> = setOf(DONE, FAILED, CANCELLED)

        private val byValue: Map<String, JobStage> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): JobStage? = raw?.let(byValue::get)
    }
}

// ───────────────────────── B8（schema v6）：SAG 层状态枚举 ─────────────────────────

/**
 * event.status（B8/v6）：抽取结果生命周期。
 * - active：当前有效（每 chunk 至多一条 active，由 supersede 事务保证）；
 * - superseded：被同 chunk 的新抽取结果替代（[EventEntity.supersededBy] 指向新 event id）；
 * - discarded：低质量/违规结果主动丢弃（保留行用于审计，不参与检索）。
 */
enum class EventStatus(val value: String) {
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    DISCARDED("discarded"),
    ;

    companion object {
        private val byValue: Map<String, EventStatus> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): EventStatus? = raw?.let(byValue::get)
    }
}

/**
 * extraction_job.status（B8/v6，B9 使用）：
 * - queued / running：队列中/在途（[ACTIVE_VALUES]，worker 互斥/取消守卫使用）；
 * - done：成功写入 event/entity/sag_vector；
 * - failed：失败（[attempt] < 上限时可重排队，超过后保留供 UI 手动重试）；
 * - cancelled：用户取消/删除导入；
 * - not_indexed：无 LLM/开关关闭/禁网时跳过抽取（文档仍 ready，可离线检索原文）。
 */
enum class ExtractionJobStatus(val value: String) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    NOT_INDEXED("not_indexed"),
    ;

    companion object {
        /** 未终结状态（queued+running）：抽取 worker 互斥/取消守卫/回收中断任务使用 */
        val ACTIVE_VALUES: List<String> = listOf(QUEUED, RUNNING).map { it.value }

        /** 终态：不再被 worker 领取（done/failed/cancelled/not_indexed） */
        val TERMINAL: Set<ExtractionJobStatus> = setOf(DONE, FAILED, CANCELLED, NOT_INDEXED)

        private val byValue: Map<String, ExtractionJobStatus> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): ExtractionJobStatus? = raw?.let(byValue::get)
    }
}

/** sag_vector.ownerType（B8/v6）：多态 owner 类型 */
enum class SagOwnerType(val value: String) {
    EVENT("EVENT"),
    ENTITY("ENTITY"),
    ;

    companion object {
        val VALUES: List<String> = values().map { it.value }

        private val byValue: Map<String, SagOwnerType> = values().associateBy { it.value }

        /** 未知值返回 null（读取路径原样展示，不抛异常） */
        fun fromValue(raw: String?): SagOwnerType? = raw?.let(byValue::get)
    }
}

/** chat_message.role（B8/v6，P1 问答） */
enum class ChatRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    ;

    companion object {
        private val byValue: Map<String, ChatRole> = values().associateBy { it.value }

        fun fromValue(raw: String?): ChatRole? = raw?.let(byValue::get)
    }
}
