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
    /** WorkManager WorkRequest id（跨进程/旧回调归属判断）。 */
    val workId: String? = null,
    val status: Status = Status.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val apkPath: String? = null,
    val failure: UpdateFailure? = null,
) {
    enum class Status { IDLE, WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

/** WorkManager 下载调度抽象（便于 manager 单测注入 fake）。 */
interface UpdateDownloadScheduler {
    /**
     * 入队并等待 WorkManager 异步结果。
     * [onRequestId] 必须在任务实际提交前同步回调，让 Manager 先绑定 WorkRequest id，
     * 避免 RUNNING 观察早于 enqueue 返回时被看门狗误杀。
     */
    suspend fun enqueue(info: UpdateInfo, onRequestId: (String) -> Unit)
    fun observe(): kotlinx.coroutines.flow.Flow<DownloadWorkState>

    /** 终止并清理下载任务；传入 workId 时只取消该任务，避免旧回调取消新重试。 */
    fun cancel(workId: String? = null)
}
