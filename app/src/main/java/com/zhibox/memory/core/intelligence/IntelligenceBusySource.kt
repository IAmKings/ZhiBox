package com.zhibox.memory.core.intelligence

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhibox.memory.core.ingest.ReembedScheduler
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 进行中的智能任务（跨屏幕共享）。
 *
 * 背景：下载进度原本只存在于 `SettingsViewModel`、重嵌入进度只由设置页观察 WorkManager，
 * 因此首页状态点永远拿不到 Busy 分支（恒 `NONE`，不会出现快脉冲）。
 * 这里把两者收敛成进程级单一来源：
 * - 下载：由发起下载的 `SettingsViewModel` 上报（`SharedModelStore.downloadEntry` 的进度回调）；
 * - 重嵌入：本类直接观察 `ReembedScheduler.UNIQUE_WORK` 的进度，不需要用户停留在设置页。
 *
 * 优先级：下载 > 重嵌入（同时发生极少；下载是用户显式动作，优先展示）。
 * 本文件是纯数据（除 WorkManager 观察），UI 侧再映射到 `IntelligenceBusy`。
 */
sealed interface IntelligenceBusyTick {

    data object Idle : IntelligenceBusyTick

    /** 模型下载中；[pct] 为 0..100（未知总量时保持 0）。 */
    data class Downloading(val modelLabel: String, val pct: Int) : IntelligenceBusyTick

    /** 重嵌入/旧数据同步中；[total] <= 0 表示总量未知。 */
    data class Reembedding(val done: Int, val total: Int) : IntelligenceBusyTick
}

class IntelligenceBusySource private constructor(app: Application) {

    private val workManager = WorkManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val downloading = MutableStateFlow<IntelligenceBusyTick.Downloading?>(null)
    private val reembedding = MutableStateFlow<IntelligenceBusyTick.Reembedding?>(null)

    /** 合并后的任务信号；无任务时为 [IntelligenceBusyTick.Idle]。 */
    val state: StateFlow<IntelligenceBusyTick> = combine(downloading, reembedding) { d, r ->
        d ?: r ?: IntelligenceBusyTick.Idle
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), IntelligenceBusyTick.Idle)

    init {
        // 重嵌入进度：直接观察唯一工作流的 progress（设置页观察同一份数据，互不影响）
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ReembedScheduler.UNIQUE_WORK).collect { infos ->
                val work = infos.firstOrNull() ?: return@collect
                reembedding.value = when (work.state) {
                    WorkInfo.State.RUNNING -> IntelligenceBusyTick.Reembedding(
                        done = work.progress.getInt("done", 0),
                        total = work.progress.getInt("total", -1),
                    )
                    // 结束/排队/阻塞都清掉，避免残留脉冲
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    -> null
                }
            }
        }
    }

    /** 下载进度上报（0..100）。 */
    fun reportDownload(modelLabel: String, pct: Int) {
        downloading.value = IntelligenceBusyTick.Downloading(modelLabel, pct.coerceIn(0, 100))
    }

    /** 下载结束（成功/失败/取消都调用）。 */
    fun clearDownload() {
        downloading.value = null
    }

    companion object {
        private val instance = AtomicReference<IntelligenceBusySource?>(null)

        fun get(app: Application): IntelligenceBusySource =
            instance.get() ?: IntelligenceBusySource(app).also { created ->
                instance.compareAndSet(null, created)
            }
    }
}
