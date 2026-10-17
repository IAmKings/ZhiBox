package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhibox.memory.R
import com.zhibox.memory.core.ingest.PipelineError
import com.zhibox.memory.core.ingest.PipelineErrorCode
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * 导入屏（P1-8 + B3-1/B3-5/B3-7/B3-9/B3-13）：暂存目录扫描 → 全部导入 → 任务列表（最近 50 条，
 * 活动任务优先分区）→ 失败重试入口 → 批量导入结果（新导入 X / 已存在 Y）。
 *
 * UI 拆为无状态 [ImportContent]（可 @Preview 渲染），状态/长任务在 [ImportViewModel]
 * （viewModelScope + IO，构造期零磁盘 IO）。无头入口仅 debuggable 构建生效（P0-3）。
 */
@Composable
fun ImportScreen(
    autoStart: Boolean = false,
    autoCancel: Boolean = false,
    onBack: () -> Unit = {},
    /** B2 回归：模型缺失/损坏时的“去设置”动作。MainActivity 已接线；默认空实现便于 Preview。 */
    onOpenSettings: () -> Unit = {},
    viewModel: ImportViewModel = viewModel(),
) {
    val stagingCount by viewModel.stagingCount.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val cancelling by viewModel.cancelling.collectAsState()
    val retrying by viewModel.retrying.collectAsState()
    val message by viewModel.message.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val jobs by viewModel.jobs.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted(autoStart, autoCancel)
    }

    ImportContent(
        stagingCount = stagingCount,
        importing = importing,
        cancelling = cancelling,
        retrying = retrying,
        message = message,
        errorMessage = errorMessage,
        jobs = jobs,
        onImportAll = viewModel::importAll,
        onCancelImport = viewModel::cancelImport,
        onRetryFailed = viewModel::retryFailed,
        onDismissError = viewModel::clearError,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    )
}

/** 无状态导入内容：供 [ImportScreen] 与 @Preview / UI 测试直接渲染（B3-7）。 */
@Composable
internal fun ImportContent(
    stagingCount: Int,
    importing: Boolean,
    cancelling: Boolean,
    retrying: Boolean,
    message: String?,
    errorMessage: String?,
    jobs: List<IngestJobEntity>,
    onImportAll: () -> Unit,
    onCancelImport: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val hasQueued = remember(jobs) { jobs.any { JobStage.fromValue(it.stage) == JobStage.QUEUED } }
    val failedCount = remember(jobs) { jobs.count { JobStage.fromValue(it.stage) == JobStage.FAILED } }
    // B3-9：活动任务优先展示（查询已按 createdAt DESC，这里只做分区，不打乱顺序）
    val activeJobs = remember(jobs) {
        jobs.filter { job ->
            JobStage.fromValue(job.stage)?.let { it !in JobStage.TERMINAL } ?: true
        }
    }
    val finishedJobs = remember(jobs) {
        jobs.filter { job ->
            JobStage.fromValue(job.stage)?.let { it in JobStage.TERMINAL } ?: false
        }
    }

    ZhiBoxScreen(title = stringResource(R.string.import_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        stagingCount < 0 -> LoadingState(
                            message = stringResource(R.string.import_staging_scanning),
                        )
                        stagingCount == 0 -> Text(
                            stringResource(R.string.import_staging_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        else -> Text(
                            stringResource(R.string.import_staging_pending, stagingCount),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onImportAll,
                            enabled = !importing && stagingCount > 0,
                        ) {
                            Text(
                                stringResource(
                                    if (importing) R.string.import_in_progress else R.string.import_all,
                                ),
                            )
                        }
                        if (hasQueued) {
                            OutlinedButton(
                                onClick = onCancelImport,
                                enabled = !cancelling,
                            ) {
                                Text(
                                    stringResource(R.string.import_cancel),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (failedCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onRetryFailed,
                                enabled = !retrying,
                            ) {
                                Text(
                                    if (retrying) stringResource(R.string.import_retrying)
                                    else stringResource(R.string.import_retry_failed, failedCount),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.import_job_retry_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // B3-13：新导入 X / 已存在 Y（无重复时也展示新增数；都没有则不显示）
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    errorMessage?.let {
                        ErrorState(
                            message = it,
                            actionLabel = stringResource(R.string.action_dismiss),
                            onAction = onDismissError,
                        )
                    }
                }
            }

            if (stagingCount == 0 && jobs.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.import_empty_title),
                    message = stringResource(R.string.import_empty_message),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeJobs.isNotEmpty()) {
                    item(key = "active-header") {
                        SectionHeader(stringResource(R.string.import_section_active))
                    }
                    items(activeJobs, key = { "job-${it.id}" }) { job ->
                        JobCard(job, onOpenSettings)
                    }
                }
                if (finishedJobs.isNotEmpty()) {
                    item(key = "finished-header") {
                        SectionHeader(stringResource(R.string.import_section_recent))
                    }
                    items(finishedJobs, key = { "job-${it.id}" }) { job ->
                        JobCard(job, onOpenSettings)
                    }
                }
                if (jobs.size >= ImportViewModel.RECENT_JOB_LIMIT) {
                    item(key = "limit-hint") {
                        Text(
                            stringResource(
                                R.string.import_jobs_limited,
                                ImportViewModel.RECENT_JOB_LIMIT,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun JobCard(job: IngestJobEntity, onOpenSettings: () -> Unit) {
    // B2-7：job.error 是 `CODE:detail` 编码（旧数据为纯文本，decode 会兼容为 UNKNOWN）。
    // 只向用户展示 decode 后的 userMessage，绝不直接显示编码串。
    val rawError = job.error
    val pipelineError = remember(rawError) { PipelineError.decode(rawError) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.import_job_stage, stageLabel(job.stage), job.progress),
                style = MaterialTheme.typography.bodyMedium,
            )
            // 未知/历史阶段按“进行中”保守处理（B1-5 读取路径兼容）
            if (JobStage.fromValue(job.stage)?.let { it !in JobStage.TERMINAL } ?: true) {
                LinearProgressIndicator(
                    progress = { job.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (rawError != null) {
                Text(
                    stringResource(
                        R.string.import_job_error,
                        pipelineError?.userMessage ?: rawError,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                pipelineError?.let { errorActionHint(it.code, onOpenSettings) }
            }
        }
    }
}

/**
 * B2-7：按结构化错误码给出可执行动作提示（复用页面顶部已有的「重试失败导入」入口）。
 * - MODEL_MISSING / MODEL_CORRUPT → 设置/模型商店下载或校验模型；
 * - SPACE_MISMATCH → 切回对应模型或重新导入；
 * - IO / UNKNOWN → 重试入口；
 * - OOM → 释放内存/减小文档；
 * - CANCELLED → 取消不是失败，不提示重试（核心管线不应把取消落库为 failed）。
 */
@Composable
private fun errorActionHint(code: PipelineErrorCode, onOpenSettings: () -> Unit) {
    val text = when (code) {
        PipelineErrorCode.MODEL_MISSING,
        PipelineErrorCode.MODEL_CORRUPT -> stringResource(R.string.import_error_action_model)
        PipelineErrorCode.SPACE_MISMATCH -> stringResource(R.string.import_error_action_space)
        PipelineErrorCode.OOM -> stringResource(R.string.import_error_action_oom)
        PipelineErrorCode.IO,
        PipelineErrorCode.UNKNOWN -> stringResource(R.string.import_error_action_retry)
        PipelineErrorCode.CANCELLED -> null
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    // B2 回归：模型缺失/损坏提供可点“去设置”（MainActivity 传入导航回调）；其余错误复用顶部重试入口。
    if (code == PipelineErrorCode.MODEL_MISSING || code == PipelineErrorCode.MODEL_CORRUPT) {
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.import_error_open_settings))
        }
    }
}

@Composable
private fun stageLabel(stage: String): String = when (JobStage.fromValue(stage)) {
    JobStage.QUEUED -> stringResource(R.string.import_stage_queued)
    JobStage.PARSING -> stringResource(R.string.import_stage_parsing)
    JobStage.CHUNKING -> stringResource(R.string.import_stage_chunking)
    JobStage.EMBEDDING -> stringResource(R.string.import_stage_embedding)
    JobStage.STORING -> stringResource(R.string.import_stage_storing)
    JobStage.DONE -> stringResource(R.string.import_stage_done)
    JobStage.FAILED -> stringResource(R.string.import_stage_failed)
    JobStage.CANCELLED -> stringResource(R.string.import_stage_cancelled)
    // 未知/历史值：原样展示（B1-5 读取路径兼容）
    null -> stage
}

// ───────────────────────── B3-7 @Preview（浅色/深色/空态/错误态/运行态） ─────────────────────────

private fun previewJob(
    id: String,
    stage: String,
    progress: Int,
    error: String? = null,
) = IngestJobEntity(
    id = id,
    sourceId = "src_$id",
    documentId = "doc_$id",
    stage = stage,
    progress = progress,
    error = error,
    createdAt = 1_700_000_000_000,
    updatedAt = 1_700_000_100_000,
)

private val previewJobs = listOf(
    previewJob("job_running", JobStage.EMBEDDING.value, 45),
    previewJob("job_queued", JobStage.QUEUED.value, 0),
    previewJob("job_done", JobStage.DONE.value, 100),
    previewJob("job_failed", JobStage.FAILED.value, 60, "IO:源文件已被删除"),
)

@Preview(showBackground = true, name = "Import · Light")
@Composable
private fun ImportLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ImportContent(
            stagingCount = 2,
            importing = true,
            cancelling = false,
            retrying = false,
            message = null,
            errorMessage = null,
            jobs = previewJobs,
            onImportAll = {},
            onCancelImport = {},
            onRetryFailed = {},
            onDismissError = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Import · Dark")
@Composable
private fun ImportDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        ImportContent(
            stagingCount = 2,
            importing = false,
            cancelling = false,
            retrying = false,
            message = "新导入 2 个 / 已存在 1 个",
            errorMessage = null,
            jobs = previewJobs,
            onImportAll = {},
            onCancelImport = {},
            onRetryFailed = {},
            onDismissError = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Import · Empty")
@Composable
private fun ImportEmptyPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ImportContent(
            stagingCount = 0,
            importing = false,
            cancelling = false,
            retrying = false,
            message = null,
            errorMessage = null,
            jobs = emptyList(),
            onImportAll = {},
            onCancelImport = {},
            onRetryFailed = {},
            onDismissError = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Import · Error")
@Composable
private fun ImportErrorPreview() {
    ZhiBoxTheme(darkTheme = false) {
        ImportContent(
            stagingCount = 1,
            importing = false,
            cancelling = false,
            retrying = false,
            message = null,
            errorMessage = "导入失败：磁盘空间不足",
            jobs = previewJobs,
            onImportAll = {},
            onCancelImport = {},
            onRetryFailed = {},
            onDismissError = {},
            onBack = {},
        )
    }
}
