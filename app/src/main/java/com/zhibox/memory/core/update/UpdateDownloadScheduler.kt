package com.zhibox.memory.core.update

/**
 * 进程内前台状态（供后台下载完成后决定「自动拉起安装器」还是「发通知」）。
 * 由 MainActivity 的 onStart/onStop 更新。
 */
object AppForegroundTracker {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun onForeground() {
        isForeground = true
    }

    fun onBackground() {
        isForeground = false
    }
}

/** 下载工作状态（manager 订阅后映射为 UpdatePhase）。 */
data class DownloadWorkState(
    val status: Status = Status.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val apkPath: String? = null,
    val failure: UpdateFailure? = null,
) {
    enum class Status { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

/** WorkManager 下载调度抽象（便于 manager 单测注入 fake）。 */
interface UpdateDownloadScheduler {
    fun enqueue(info: UpdateInfo)
    fun observe(): kotlinx.coroutines.flow.Flow<DownloadWorkState>
}
