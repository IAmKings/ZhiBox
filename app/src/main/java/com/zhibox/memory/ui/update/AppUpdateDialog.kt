package com.zhibox.memory.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zhibox.memory.R
import com.zhibox.memory.core.update.UpdateFailure
import com.zhibox.memory.core.update.UpdateInfo
import com.zhibox.memory.core.update.UpdatePhase
import com.zhibox.memory.core.update.UpdateUiState
import com.zhibox.memory.ui.ZhiBoxCard
import com.zhibox.memory.ui.theme.ZhiBoxRadius
import com.zhibox.memory.ui.theme.zhiBoxColors
import java.util.Locale

/**
 * 全局更新弹窗（挂在 MainActivity 根部，覆盖所有屏）。
 *
 * 交互契约（docs/product/APP_UPDATE_SPEC.md）：
 * - Available / DownloadFailed / NeedInstallPermission：可点外部/返回键关闭；
 * - Downloading / Verifying：**不可取消**（dismissOnBackPress/Outside = false，无按钮）；
 * - 下载完成并通过 SHA/签名校验后自动拉起系统安装器（由 manager 完成）。
 *
 * 视觉遵循 DESIGN.md Flat Surfaces：M3 浮层默认轻阴影（≤8dp）+ 卡片 16dp 圆角 +
 * `card` 令牌底色；不与描边叠加（同一层只用一种深度线索）。
 */
@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val phase = state.phase
    val cancellable = when (phase) {
        is UpdatePhase.Available,
        is UpdatePhase.DownloadFailed,
        is UpdatePhase.NeedInstallPermission,
        -> true

        else -> false
    }
    val visible = phase is UpdatePhase.Available ||
        phase is UpdatePhase.Downloading ||
        phase is UpdatePhase.Verifying ||
        phase is UpdatePhase.DownloadFailed ||
        phase is UpdatePhase.NeedInstallPermission
    if (!visible) return

    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (cancellable) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = cancellable,
            dismissOnClickOutside = cancellable,
        ),
        modifier = Modifier.testTag("update_dialog"),
        shape = RoundedCornerShape(ZhiBoxRadius.card),
        containerColor = MaterialTheme.zhiBoxColors.card,
        title = {
            Text(
                dialogTitle(phase),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    is UpdatePhase.Available -> AvailableContent(
                        currentVersion = state.currentVersionName,
                        info = phase.info,
                        onOpenReleasePage = { url ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )

                    is UpdatePhase.Downloading -> DownloadingContent(phase)
                    is UpdatePhase.Verifying -> VerifyingContent()
                    is UpdatePhase.DownloadFailed -> Text(
                        failureText(phase.failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdatePhase.NeedInstallPermission -> Text(
                        stringResource(R.string.update_dialog_permission_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (phase) {
                is UpdatePhase.Available -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.update_dialog_update_now))
                }

                is UpdatePhase.DownloadFailed -> Button(onClick = onRetry) {
                    Text(stringResource(R.string.update_dialog_retry))
                }

                is UpdatePhase.NeedInstallPermission -> Button(onClick = onOpenPermissionSettings) {
                    Text(stringResource(R.string.update_dialog_open_settings))
                }

                else -> Unit
            }
        },
        dismissButton = {
            when (phase) {
                is UpdatePhase.Available -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_dialog_later))
                }

                is UpdatePhase.DownloadFailed -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_dialog_close))
                }

                is UpdatePhase.NeedInstallPermission -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_dialog_cancel))
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun dialogTitle(phase: UpdatePhase): String = when (phase) {
    is UpdatePhase.Available -> stringResource(R.string.update_dialog_title_available)
    is UpdatePhase.Downloading -> stringResource(R.string.update_dialog_title_downloading)
    is UpdatePhase.Verifying -> stringResource(R.string.update_dialog_title_verifying)
    is UpdatePhase.DownloadFailed -> stringResource(R.string.update_dialog_title_failed)
    is UpdatePhase.NeedInstallPermission -> stringResource(R.string.update_dialog_title_permission)
    else -> stringResource(R.string.update_dialog_title_available)
}

@Composable
private fun AvailableContent(
    currentVersion: String,
    info: UpdateInfo,
    onOpenReleasePage: (String) -> Unit,
) {
    Text(
        stringResource(
            R.string.update_dialog_version_line,
            currentVersion,
            info.versionName,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    val dateText = info.publishedAt?.take(10).orEmpty()
    Text(
        if (dateText.isNotBlank()) {
            stringResource(
                R.string.update_dialog_meta,
                formatBytes(info.apkSizeBytes),
                dateText,
            )
        } else {
            stringResource(R.string.update_dialog_meta_size, formatBytes(info.apkSizeBytes))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.update_dialog_notes_title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            info.notes,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (info.htmlUrl.isNotBlank()) {
        TextButton(
            onClick = { onOpenReleasePage(info.htmlUrl) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                stringResource(R.string.update_dialog_view_full),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DownloadingContent(phase: UpdatePhase.Downloading) {
    val total = phase.totalBytes
    val progressDesc = stringResource(R.string.update_dialog_progress_desc)
    val fraction = if (total > 0) {
        (phase.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = progressDesc
                },
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        stringResource(
            R.string.update_dialog_progress,
            ((fraction ?: 0f) * 100).toInt(),
            formatBytes(phase.downloadedBytes),
            if (total > 0) formatBytes(total) else stringResource(R.string.update_dialog_size_unknown),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.update_dialog_downloading_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VerifyingContent() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.update_dialog_verifying_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 失败原因 → 可读文案（core 不引资源 ID）。 */
@Composable
internal fun failureText(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.Network -> stringResource(R.string.update_error_network)
    UpdateFailure.Timeout -> stringResource(R.string.update_error_timeout)
    is UpdateFailure.Http -> if (failure.rateLimited) {
        stringResource(R.string.update_error_rate_limited)
    } else {
        stringResource(R.string.update_error_http, failure.code)
    }

    UpdateFailure.Parse -> stringResource(R.string.update_error_parse)
    UpdateFailure.NoRelease -> stringResource(R.string.update_error_no_release)
    UpdateFailure.IncompleteRelease -> stringResource(R.string.update_error_incomplete)
    UpdateFailure.NoChecksum -> stringResource(R.string.update_error_no_checksum)
    UpdateFailure.ChecksumMismatch -> stringResource(R.string.update_error_checksum)
    UpdateFailure.ApkInvalid -> stringResource(R.string.update_error_invalid_apk)
    UpdateFailure.InsufficientSpace -> stringResource(R.string.update_error_space)
    UpdateFailure.InstallPermission -> stringResource(R.string.update_error_install_permission)
    UpdateFailure.InstallFailed -> stringResource(R.string.update_error_install)
    UpdateFailure.FileUnavailable -> stringResource(R.string.update_error_file)
}

/**
 * 设置「隐私与关于」页的「检查更新」行。
 *
 * 状态内联展示；点击会取消在途的启动检查并重新生成查询任务（manager.checkNow）。
 * 下载/校验进行中时不可点击（避免打断不可取消的下载弹窗）。
 */
@Composable
fun UpdateSettingsRow(
    state: UpdateUiState,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val clickEnabled = phase !is UpdatePhase.Downloading && phase !is UpdatePhase.Verifying
    val status = when (phase) {
        is UpdatePhase.Checking -> stringResource(R.string.update_row_checking)
        is UpdatePhase.UpToDate -> stringResource(
            R.string.update_row_up_to_date,
            phase.versionName,
        )

        is UpdatePhase.Available -> stringResource(
            R.string.update_row_available,
            phase.info.versionName,
        )

        is UpdatePhase.Downloading -> {
            val pct = if (phase.totalBytes > 0) {
                (phase.downloadedBytes * 100 / phase.totalBytes).toInt()
            } else {
                0
            }
            stringResource(R.string.update_row_downloading, pct)
        }

        is UpdatePhase.Verifying -> stringResource(R.string.update_row_verifying)
        is UpdatePhase.NeedInstallPermission -> stringResource(R.string.update_row_permission)
        is UpdatePhase.CheckFailed -> stringResource(R.string.update_row_check_failed)
        is UpdatePhase.DownloadFailed -> stringResource(R.string.update_row_download_failed)
        else -> stringResource(R.string.update_row_current, state.currentVersionName)
    }
    val title = stringResource(R.string.update_row_title)
    ZhiBoxCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_update_row"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    enabled = clickEnabled,
                    role = Role.Button,
                    onClickLabel = title,
                    onClick = onCheck,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (phase) {
                is UpdatePhase.Checking, is UpdatePhase.Downloading, is UpdatePhase.Verifying ->
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(4.dp)
                            .semantics { contentDescription = status },
                        strokeWidth = 2.dp,
                    )

                else -> Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.zhiBoxColors.inkMeta,
                )
            }
        }
    }
}

/** 字节数 → 人类可读（MB/GB；下载/更新包以 MB 为主）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
