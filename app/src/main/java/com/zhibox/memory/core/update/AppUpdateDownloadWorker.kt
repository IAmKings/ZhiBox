package com.zhibox.memory.core.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.await
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zhibox.memory.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/** WorkManager 任务契约（输入/输出 Data key）。 */
object UpdateDownloadContract {
    const val UNIQUE_WORK = "app-update-download"
    const val TAG = "app-update"

    const val KEY_VERSION = "version"
    const val KEY_APK_URL = "apk_url"
    const val KEY_APK_SIZE = "apk_size"
    const val KEY_SHA256 = "sha256"
    const val KEY_SHA256_URL = "sha256_url"

    const val OUT_APK_PATH = "apk_path"
    const val OUT_FAILURE = "failure"
    const val OUT_INSTALLED_ALREADY = "installed_already"
}

/** 失败原因 ↔ Work Data 字符串（data object 用类名；Http 用 `Http:code:rateLimited`）。 */
internal fun encodeUpdateFailure(failure: UpdateFailure): String = when (failure) {
    is UpdateFailure.Http -> "Http:${failure.code}:${failure.rateLimited}"
    else -> failure::class.simpleName ?: "Network"
}

internal fun decodeUpdateFailure(value: String?): UpdateFailure {
    if (value.isNullOrBlank()) return UpdateFailure.Network
    if (value.startsWith("Http:")) {
        val parts = value.split(':')
        val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val rateLimited = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        return UpdateFailure.Http(code, rateLimited)
    }
    return when (value) {
        "Timeout" -> UpdateFailure.Timeout
        "Parse" -> UpdateFailure.Parse
        "NoRelease" -> UpdateFailure.NoRelease
        "IncompleteRelease" -> UpdateFailure.IncompleteRelease
        "NoChecksum" -> UpdateFailure.NoChecksum
        "ChecksumMismatch" -> UpdateFailure.ChecksumMismatch
        "ApkInvalid" -> UpdateFailure.ApkInvalid
        "InsufficientSpace" -> UpdateFailure.InsufficientSpace
        "InstallPermission" -> UpdateFailure.InstallPermission
        "InstallFailed" -> UpdateFailure.InstallFailed
        "FileUnavailable" -> UpdateFailure.FileUnavailable
        "Cancelled" -> UpdateFailure.Cancelled
        "ReleaseUnavailable" -> UpdateFailure.ReleaseUnavailable
        else -> UpdateFailure.Network
    }
}

/**
 * 后台下载 Worker：WorkManager 持久化任务，进程被杀后由系统重新调度；
 * `.part` 续传、镜像回退、SHA 校验全部复用 [HttpUpdateDownloader]。
 * 前台服务通知展示进度；完成后由 manager 校验 APK 并决定自动安装/发通知。
 */
class AppUpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val version = inputData.getString(UpdateDownloadContract.KEY_VERSION)
            ?: return Result.failure(workDataOf(UpdateDownloadContract.OUT_FAILURE to "Parse"))
        if (!hasInternet(applicationContext)) {
            return Result.failure(
                workDataOf(UpdateDownloadContract.OUT_FAILURE to encodeUpdateFailure(UpdateFailure.Network)),
            )
        }
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) {
            return Result.success(
                workDataOf(UpdateDownloadContract.OUT_INSTALLED_ALREADY to true),
            )
        }
        val info = UpdateInfo(
            versionName = version,
            tagName = "v$version",
            title = version,
            notes = "",
            publishedAt = null,
            htmlUrl = inputData.getString("html_url").orEmpty(),
            apkUrl = inputData.getString(UpdateDownloadContract.KEY_APK_URL).orEmpty(),
            apkSizeBytes = inputData.getLong(UpdateDownloadContract.KEY_APK_SIZE, 0L),
            sha256 = inputData.getString(UpdateDownloadContract.KEY_SHA256),
            sha256Url = inputData.getString(UpdateDownloadContract.KEY_SHA256_URL),
        )
        if (info.apkUrl.isBlank()) {
            return Result.failure(workDataOf(UpdateDownloadContract.OUT_FAILURE to "IncompleteRelease"))
        }

        val context = applicationContext
        UpdateNotifications.ensureChannel(context)
        setForeground(
            foregroundInfo(
                UpdateNotifications.progressNotification(context, info, 0L, info.apkSizeBytes),
            ),
        )

        val store = SharedPrefsUpdateStore(context)
        val targetDir = File(context.cacheDir, "updates")
        val downloadedBytes = AtomicLong(0L)
        val totalBytes = AtomicLong(-1L)
        return try {
            // WorkManager.setProgress 是 suspend；下载回调是同步的，用独立 ticker 上报进度/通知。
            val file = coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(NOTIFY_INTERVAL_MS)
                        val downloaded = downloadedBytes.get()
                        val total = totalBytes.get()
                        if (downloaded > 0L) {
                            setProgress(
                                workDataOf(
                                    "downloaded" to downloaded,
                                    "total" to total,
                                ),
                            )
                            UpdateNotifications.notifyProgress(context, info, downloaded, total)
                        }
                    }
                }
                try {
                    withTimeout(UpdateConfig.DOWNLOAD_TIMEOUT_MS) {
                        HttpUpdateDownloader().download(
                            info = info,
                            targetDir = targetDir,
                            routeHealth = store.routeHealth(),
                            onRouteResult = { updated ->
                                val health = store.routeHealth().toMutableMap()
                                health[updated.id] = updated
                                store.updateRouteHealth(health)
                            },
                        ) { downloaded, total ->
                            downloadedBytes.set(downloaded)
                            totalBytes.set(total)
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }
            UpdateNotifications.cancelProgress(context)
            // 即使 Manager/Activity 不存在（系统仅为 Worker 重建进程），也持久化待安装记录并
            // 发完成通知；前台时由 Manager 直接拉起安装，避免重复通知。
            store.downloadedVersion = info.versionName
            store.downloadedApkPath = file.absolutePath
            if (!AppForegroundTracker.isForeground) {
                UpdateNotifications.notifyDownloadComplete(context, info)
            }
            Result.success(
                workDataOf(UpdateDownloadContract.OUT_APK_PATH to file.absolutePath),
            )
        } catch (e: TimeoutCancellationException) {
            UpdateNotifications.cancelProgress(context)
            Result.failure(
                workDataOf(UpdateDownloadContract.OUT_FAILURE to encodeUpdateFailure(UpdateFailure.Timeout)),
            )
        } catch (e: UpdateException) {
            UpdateNotifications.cancelProgress(context)
            Result.failure(
                workDataOf(UpdateDownloadContract.OUT_FAILURE to encodeUpdateFailure(e.failure)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateNotifications.cancelProgress(context)
            Result.failure(
                workDataOf(UpdateDownloadContract.OUT_FAILURE to encodeUpdateFailure(UpdateFailure.Network)),
            )
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 4101
        const val NOTIFY_INTERVAL_MS = 1_000L
    }
}

/** WorkManager 实现：唯一工作 + 网络/存储约束 + 进度/输出映射。 */
class WorkManagerUpdateDownloadScheduler(context: Context) : UpdateDownloadScheduler {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun enqueue(info: UpdateInfo, onRequestId: (String) -> Unit) {
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            // 不加 Network/Storage 约束：约束会让任务长期停留在 ENQUEUED，旧实现把它映射成
            // 「正在下载 0%」且弹窗不可取消。Worker 内做网络预检，失败会快速进入可重试状态。
            .setInputData(
                workDataOf(
                    UpdateDownloadContract.KEY_VERSION to info.versionName,
                    UpdateDownloadContract.KEY_APK_URL to info.apkUrl,
                    UpdateDownloadContract.KEY_APK_SIZE to info.apkSizeBytes,
                    UpdateDownloadContract.KEY_SHA256 to info.sha256,
                    UpdateDownloadContract.KEY_SHA256_URL to info.sha256Url,
                    "html_url" to info.htmlUrl,
                ),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .addTag(UpdateDownloadContract.TAG)
            .build()
        // 先同步回调 id，再等待异步入队结果；Worker 的 RUNNING 观察不会早于 id 绑定。
        onRequestId(request.id.toString())
        // 等待 WorkManager 异步入队结果；失败会抛异常，由 Manager 转为可重试失败态。
        workManager.enqueueUniqueWork(
            UpdateDownloadContract.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        ).await()
    }

    override fun cancel(workId: String?) {
        if (workId == null) {
            workManager.cancelUniqueWork(UpdateDownloadContract.UNIQUE_WORK)
        } else {
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
                .onFailure { workManager.cancelUniqueWork(UpdateDownloadContract.UNIQUE_WORK) }
        }
    }

    override fun observe(): Flow<DownloadWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(UpdateDownloadContract.UNIQUE_WORK)
            .map { infos -> infos.firstOrNull()?.toDownloadState() ?: DownloadWorkState() }
            .distinctUntilChanged()

    private fun WorkInfo.toDownloadState(): DownloadWorkState = when (state) {
        WorkInfo.State.RUNNING -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.RUNNING,
            downloadedBytes = progress.getLong("downloaded", 0L),
            totalBytes = progress.getLong("total", -1L),
        )

        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.WAITING,
        )

        WorkInfo.State.SUCCEEDED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.SUCCEEDED,
            apkPath = outputData.getString(UpdateDownloadContract.OUT_APK_PATH),
        )

        WorkInfo.State.FAILED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.FAILED,
            failure = decodeUpdateFailure(outputData.getString(UpdateDownloadContract.OUT_FAILURE)),
        )

        WorkInfo.State.CANCELLED -> DownloadWorkState(
            workId = id.toString(),
            status = DownloadWorkState.Status.CANCELLED,
            failure = UpdateFailure.Cancelled,
        )
    }
}
