package com.zhibox.memory.core.update

import android.content.Context
import android.util.Log
import com.zhibox.memory.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内更新的全局服务（Application 作用域单例）。
 *
 * 职责：
 * - 冷启动自动检查（24h 节流、可开关）与设置里的手动强制检查；
 * - **任务代际**：手动检查取消在途检查并生成新任务，旧结果丢弃；
 * - 后台下载交给 WorkManager（[UpdateDownloadScheduler]）：任务持久化、通知栏进度、
 *   进程被杀后由系统重新调度并续传 `.part`；
 * - 下载完成后校验 APK：前台自动拉起安装器；后台发「点击安装」通知，
 *   下次进入应用/点通知时再安装；未知来源权限引导。
 *
 * 设计/验收见 `docs/product/APP_UPDATE_SPEC.md`；测试可注入 fake 依赖（internal 构造）。
 */
class AppUpdateManager internal constructor(
    private val appContext: Context,
    private val api: UpdateApi,
    private val scheduler: UpdateDownloadScheduler,
    private val installer: UpdateInstaller,
    private val validator: ApkValidator,
    private val store: UpdateStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val currentVersionName: String,
    private val foreground: () -> Boolean = { AppForegroundTracker.isForeground },
) {

    private val _state = MutableStateFlow(UpdateUiState(currentVersionName = currentVersionName))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val entered = AtomicBoolean(false)
    private var taskId = 0L
    private var checkJob: Job? = null
    private var verifyCachedJob: Job? = null
    private var downloadWatchdogJob: Job? = null
    /** 当前下载对应的 WorkRequest id；旧任务回调不能影响新重试。 */
    @Volatile
    private var activeDownloadWorkId: String? = null
    /** 强制更新下载失败次数（3 次后允许暂时退出，避免锁死）。 */
    private var downloadFailureCount = 0
    private var activeForced = false

    /**
     * 本进程是否正在跟踪一次下载。
     * WorkManager 会在进程重启后回放历史的 FAILED/CANCELLED 终端状态；若没有本进程的
     * 下载上下文（startDownload 或先观察到 RUNNING），这些历史失败不能重新弹「更新失败」。
     */
    @Volatile
    private var downloadActiveInProcess = false
    private var lastForcedVerifyAtMillis = 0L
    private val installInProgress = AtomicBoolean(false)

    init {
        scope.launch {
            _state.update {
                it.copy(
                    autoCheckEnabled = store.autoCheckEnabled,
                    channel = store.channel,
                    routeHealth = store.routeHealth(),
                )
            }
        }
        // WorkManager 状态是下载阶段的单一事实来源（跨进程恢复）：订阅一次即可。
        scope.launch {
            scheduler.observe().collect { work -> onWorkState(work) }
        }
    }

    // ── 检查 ─────────────────────────────────────────────────────

    fun onAppEntered() {
        if (!entered.compareAndSet(false, true)) return
        scope.launch {
            // 后台下载已完成但尚未安装：先处理安装，再做常规检查。
            // 如果安装恢复/授权提示已接管流程，不能被冷启动检查覆盖。
            if (installPendingIfAny()) return@launch
            cleanupOldDownloadCache()
            if (isDownloadInProgressPhase(_state.value.phase)) return@launch
            when (val action = UpdatePolicy.decideOnLaunch(clock(), store, currentVersionName)) {
                is UpdatePolicy.LaunchAction.ShowCached -> {
                    if (isDownloadInProgressPhase(_state.value.phase)) return@launch
                    // 冷启动展示缓存时也必须重新计算 forced 与灰度资格：
                    // - 否则「暂时退出」后重启的强制弹窗会丢失 forced，变成可取消；
                    // - 未命中灰度的设备也不能因为缓存而绕过 rollout。
                    val forced = UpdateRollout.isForced(action.info, currentVersionName)
                    val eligible = UpdateRollout.isEligible(store.installId, action.info)
                    val phase = if (forced) {
                        UpdatePhase.Available(UpdateTrigger.AUTO, action.info, forced = true)
                    } else if (eligible) {
                        UpdatePhase.Available(UpdateTrigger.AUTO, action.info)
                    } else {
                        UpdatePhase.RolloutPending(action.info, action.info.rolloutPercent)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(phase = phase, lastCheckAtMillis = store.lastSuccessAtMillis)
                        }
                    }
                    if (forced) verifyCachedForced()
                }

                is UpdatePolicy.LaunchAction.RolloutPending -> {
                    if (isDownloadInProgressPhase(_state.value.phase)) return@launch
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(
                                phase = UpdatePhase.RolloutPending(
                                    action.info,
                                    action.info.rolloutPercent,
                                ),
                                lastCheckAtMillis = store.lastSuccessAtMillis,
                            )
                        }
                    }
                }

                UpdatePolicy.LaunchAction.Check -> startCheck(UpdateTrigger.AUTO)
                UpdatePolicy.LaunchAction.Skip -> Unit
            }
        }
    }

    /** 设置里的「检查更新」：取消在途检查 + 代际 +1，强制重查。 */
    fun checkNow() {
        startCheck(UpdateTrigger.MANUAL)
    }

    private fun startCheck(trigger: UpdateTrigger) {
        val id = ++taskId
        checkJob?.cancel()
        _state.update { it.copy(phase = UpdatePhase.Checking(trigger)) }
        checkJob = scope.launch {
            val result = api.fetchLatest(store.channel)
            if (id != taskId) return@launch
            result.fold(
                onSuccess = { info ->
                    store.lastSuccessAtMillis = clock()
                    store.lastAttemptAtMillis = clock()
                    store.saveCached(info)
                    // 新检查结果代表新一轮更新：清空上一次强制更新的失败计数。
                    downloadFailureCount = 0
                    val newer = isNewerVersion(info.versionName, currentVersionName)
                    val forced = UpdateRollout.isForced(info, currentVersionName)
                    val eligible = UpdateRollout.isEligible(store.installId, info)
                    val phase = when {
                        !newer -> {
                            store.clearCache()
                            UpdatePhase.UpToDate(trigger, currentVersionName)
                        }
                        // 强制更新优先于「用户取消过该版本」与灰度分桶。
                        forced -> UpdatePhase.Available(trigger, info, forced = true)
                        trigger == UpdateTrigger.AUTO &&
                            !UpdatePolicy.shouldAutoPrompt(
                                info,
                                currentVersionName,
                                store.dismissedVersion,
                            ) -> UpdatePhase.UpToDate(trigger, currentVersionName)
                        eligible -> UpdatePhase.Available(trigger, info)
                        else -> UpdatePhase.RolloutPending(info, info.rolloutPercent)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(phase = phase, lastCheckAtMillis = clock())
                        }
                    }
                },
                onFailure = { error ->
                    val failure = (error as? UpdateException)?.failure ?: UpdateFailure.Network
                    store.lastAttemptAtMillis = clock()
                    val fallbackCached = if (trigger == UpdateTrigger.AUTO) {
                        store.cachedInfo()?.takeIf {
                            isNewerVersion(it.versionName, currentVersionName)
                        }
                    } else {
                        null
                    }
                    val fallbackPhase = when {
                        trigger == UpdateTrigger.MANUAL ->
                            UpdatePhase.CheckFailed(trigger, failure)

                        fallbackCached == null -> UpdatePhase.Idle

                        UpdateRollout.isForced(fallbackCached, currentVersionName) ->
                            UpdatePhase.Available(trigger, fallbackCached, forced = true)

                        !UpdateRollout.isEligible(store.installId, fallbackCached) ->
                            UpdatePhase.RolloutPending(fallbackCached, fallbackCached.rolloutPercent)

                        fallbackCached.versionName == store.dismissedVersion ->
                            UpdatePhase.Idle

                        else -> UpdatePhase.Available(trigger, fallbackCached)
                    }
                    _state.update { current ->
                        if (isDownloadInProgressPhase(current.phase)) {
                            current
                        } else {
                            current.copy(phase = fallbackPhase, lastCheckAtMillis = clock())
                        }
                    }
                },
            )
        }
    }

    // ── 设置项 ───────────────────────────────────────────────────

    fun setAutoCheckEnabled(enabled: Boolean) {
        scope.launch {
            store.autoCheckEnabled = enabled
            _state.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    fun setChannel(channel: UpdateChannel) {
        scope.launch {
            if (store.channel == channel) return@launch
            store.channel = channel
            store.clearCache()
            store.lastSuccessAtMillis = 0L
            store.lastAttemptAtMillis = 0L
            store.dismissedVersion = null
            taskId++
            checkJob?.cancel()
            _state.update { it.copy(channel = channel, phase = UpdatePhase.Idle) }
        }
    }

    fun resetRouteHealth() {
        scope.launch {
            store.updateRouteHealth(emptyMap())
            _state.update { it.copy(routeHealth = emptyMap()) }
        }
    }

    // ── 下载（WorkManager） ─────────────────────────────────────

    /** 弹窗点「立即更新」：入队 WorkManager 后台下载（约束：联网 + 存储非低）。 */
    fun startDownload() {
        val available = _state.value.phase as? UpdatePhase.Available ?: return
        val info = available.info
        UpdateNotifications.ensureChannel(appContext)
        clearPendingInstall()
        store.installerLaunchedVersion = null
        store.consumedWorkId = null
        activeForced = available.forced
        downloadActiveInProcess = true
        activeDownloadWorkId = null
        cancelDownloadWatchdog()
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading(info, 0L, info.apkSizeBytes),
                forcedEscapeAllowed = false,
            )
        }
        // 进入下载态立即武装启动看门狗（不能等 WAITING 回调，异步入队失败时也要能恢复）。
        armDownloadWatchdog(info, workId = null)
        scope.launch {
            try {
                val id = scheduler.enqueue(info)
                activeDownloadWorkId = id
                // 看门狗可能已在入队期间失败并取消；此时取消迟到的任务。
                if (_state.value.phase !is UpdatePhase.Downloading) {
                    scheduler.cancel(id)
                }
            } catch (e: Exception) {
                cancelDownloadWatchdog()
                activeDownloadWorkId = null
                downloadActiveInProcess = false
                if (_state.value.phase is UpdatePhase.Downloading) {
                    failDownload(info, UpdateFailure.Network)
                }
            }
        }
    }

    private suspend fun onWorkState(work: DownloadWorkState) {
        if (!claimWorkEvent(work)) return
        when (work.status) {
            DownloadWorkState.Status.IDLE -> Unit
            DownloadWorkState.Status.WAITING -> {
                val info = currentActionInfo() ?: store.cachedInfo() ?: return
                downloadActiveInProcess = true
                _state.update { current ->
                    if (current.phase is UpdatePhase.Downloading) current
                    else current.copy(phase = UpdatePhase.Downloading(info, 0L, info.apkSizeBytes))
                }
                armDownloadWatchdog(info, work.workId)
            }

            DownloadWorkState.Status.RUNNING -> {
                cancelDownloadWatchdog()
                val info = currentActionInfo() ?: store.cachedInfo() ?: return
                downloadActiveInProcess = true
                _state.update { current ->
                    // 下载任务已在运行：即使冷启动检查尚在 Checking，也以真实下载态为准。
                    current.copy(
                        phase = UpdatePhase.Downloading(info, work.downloadedBytes, work.totalBytes),
                    )
                }
            }

            DownloadWorkState.Status.SUCCEEDED -> {
                cancelDownloadWatchdog()
                downloadActiveInProcess = false
                activeDownloadWorkId = null
                // 只取消当前任务；不要 cancelUniqueWork，否则可能误伤刚重试的新任务。
                scheduler.cancel(work.workId)
                handleDownloadSucceeded(work.apkPath, work.workId)
            }

            DownloadWorkState.Status.FAILED, DownloadWorkState.Status.CANCELLED -> {
                cancelDownloadWatchdog()
                if (!downloadActiveInProcess) {
                    // 历史会话的 FAILED/CANCELLED（进程重启后 WorkManager 回放）：
                    // 静默清理，不再用缓存信息弹「更新失败」。
                    scheduler.cancel(work.workId)
                    clearPendingInstall()
                    _state.update { current ->
                        if (current.phase is UpdatePhase.Downloading || current.phase is UpdatePhase.Verifying) {
                            current.copy(phase = UpdatePhase.Idle)
                        } else {
                            current
                        }
                    }
                    return
                }
                downloadActiveInProcess = false
                activeDownloadWorkId = null
                val info = currentActionInfo() ?: store.cachedInfo()
                clearPendingInstall()
                // 失败弹窗展示后同样取消该任务；重试会重新入队，不再回放旧 FAILED。
                scheduler.cancel(work.workId)
                if (info != null) {
                    failDownload(info, work.failure ?: UpdateFailure.Network)
                } else {
                    _state.update { it.copy(phase = UpdatePhase.Idle) }
                }
            }
        }
    }

    /**
     * 判断回调是否属于当前下载：忽略旧 WorkRequest 的 CANCELLED/FAILED 回调，
     * 避免旧任务取消刚重试的新任务；进程重启时允许首个 RUNNING/WAITING 被接管。
     */
    private fun claimWorkEvent(work: DownloadWorkState): Boolean {
        val id = work.workId ?: return true
        val active = activeDownloadWorkId
        if (active != null) return id == active
        return when (work.status) {
            DownloadWorkState.Status.RUNNING,
            DownloadWorkState.Status.WAITING,
            -> {
                if (downloadActiveInProcess) {
                    // 本进程刚入队、id 尚未返回：先忽略，入队返回后由后续事件/看门狗处理。
                    false
                } else {
                    activeDownloadWorkId = id
                    downloadActiveInProcess = true
                    true
                }
            }

            DownloadWorkState.Status.SUCCEEDED -> {
                // 进程重启后恢复的历史成功结果仍需消费（安装/通知），由 consumedWorkId 去重。
                activeDownloadWorkId = id
                true
            }

            DownloadWorkState.Status.FAILED,
            DownloadWorkState.Status.CANCELLED,
            DownloadWorkState.Status.IDLE,
            -> false
        }
    }

    private suspend fun handleDownloadSucceeded(apkPath: String?, workId: String?) {
        val info = currentActionInfo() ?: store.cachedInfo()
        if (info == null) {
            // 无对应更新信息：旧 WorkInfo 残留，静默清理，不能弹失败。
            cleanupStaleDownload(apkPath, workId)
            return
        }
        if (workId != null && store.consumedWorkId == workId) {
            // 同一个 SUCCEEDED 结果已经处理过（用户可能取消过系统安装器）：不能重启后再拉起。
            finishConsumedSuccess(workId)
            return
        }
        if (!isNewerVersion(info.versionName, currentVersionName)) {
            if (workId != null) store.consumedWorkId = workId
            cleanupStaleDownload(apkPath, workId)
            return
        }
        if (store.installerLaunchedVersion == info.versionName) {
            // 已交给系统安装器但用户取消安装：重启后不自动再次拉起安装器。
            finishConsumedSuccess(workId)
            return
        }
        // 先标记消费，防止异常/取消安装后重启再次自动安装。
        if (workId != null) store.consumedWorkId = workId
        val file = apkPath?.let { File(it) }
        _state.update { it.copy(phase = UpdatePhase.Verifying(info)) }
        if (file == null || !file.isFile) {
            failDownload(info, UpdateFailure.FileUnavailable)
            return
        }
        val invalid = validator.validate(file)
        if (invalid != null) {
            file.delete()
            failDownload(info, invalid)
            return
        }
        store.downloadedVersion = info.versionName
        store.downloadedApkPath = file.absolutePath
        if (foreground()) {
            if (installInProgress.compareAndSet(false, true)) {
                try {
                    launchInstaller(info, file)
                } finally {
                    installInProgress.set(false)
                }
            }
        } else {
            // Android 后台启动 Activity 受限：发通知，用户点通知/下次进入时安装。
            UpdateNotifications.notifyDownloadComplete(appContext, info)
            _state.update { it.copy(phase = UpdatePhase.Idle) }
        }
    }

    /** 已消费的成功结果：只清待安装记录/取消旧任务，不删除安装器可能仍在读取的 APK。 */
    private fun finishConsumedSuccess(workId: String?) {
        if (workId != null) store.consumedWorkId = workId
        clearPendingInstall()
        scheduler.cancel(workId)
        _state.update { if (it.phase is UpdatePhase.Idle) it else it.copy(phase = UpdatePhase.Idle) }
    }

    /** 清理“目标版本已安装/无更新信息”场景的残留下载与 WorkManager 状态。 */
    private fun cleanupStaleDownload(apkPath: String?, workId: String? = null) {
        runCatching { apkPath?.let { File(it).delete() } }
        runCatching {
            File(appContext.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }
        clearPendingInstall()
        scheduler.cancel(workId)
        _state.update { if (it.phase is UpdatePhase.Idle) it else it.copy(phase = UpdatePhase.Idle) }
    }

    /** 前台/点通知后安装已下载的 APK（从持久化 store 恢复，跨进程可用）。 */
    fun installDownloadedIfReady() {
        scope.launch { installPendingIfAny() }
    }

    /** @return true 表示安装/授权/校验流程已接管 UI，冷启动检查不得覆盖。 */
    private suspend fun installPendingIfAny(): Boolean {
        if (!installInProgress.compareAndSet(false, true)) return true
        return try {
            installPendingIfAnyLocked()
        } finally {
            installInProgress.set(false)
        }
    }

    private suspend fun installPendingIfAnyLocked(): Boolean {
        val version = store.downloadedVersion ?: return false
        val path = store.downloadedApkPath ?: return false
        if (!isNewerVersion(version, currentVersionName)) {
            File(path).delete()
            clearPendingInstall()
            return false
        }
        if (store.installerLaunchedVersion == version) {
            // 已经拉起过安装器（用户取消/未完成）：不自动重复拉起。
            clearPendingInstall()
            return false
        }
        val file = File(path)
        if (!file.isFile) {
            clearPendingInstall()
            return false
        }
        val info = store.cachedInfo()?.takeIf { it.versionName == version }
            ?: UpdateInfo(
                versionName = version,
                tagName = "v$version",
                title = version,
                notes = "",
                publishedAt = null,
                htmlUrl = "",
                apkUrl = "",
                apkSizeBytes = 0L,
                sha256 = null,
                sha256Url = null,
            )
        _state.update { it.copy(phase = UpdatePhase.Verifying(info)) }
        val invalid = validator.validate(file)
        if (invalid != null) {
            file.delete()
            clearPendingInstall()
            failDownload(info, invalid)
            return true
        }
        if (!installer.canInstallPackages()) {
            _state.update { it.copy(phase = UpdatePhase.NeedInstallPermission(info)) }
            return true
        }
        launchInstaller(info, file)
        return true
    }

    private suspend fun launchInstaller(info: UpdateInfo, file: File) {
        val result = withContext(Dispatchers.Main) { installer.install(file) }
        when (result) {
            InstallResult.Launched -> {
                store.installerLaunchedVersion = info.versionName
                clearPendingInstall()
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            InstallResult.PermissionRequired ->
                _state.update { it.copy(phase = UpdatePhase.NeedInstallPermission(info)) }

            is InstallResult.Failed -> failDownload(info, result.failure)
        }
    }

    /**
     * 强制缓存展示后后台校验：Release 被删除/元数据回滚时，服务端结果会清缓存并关闭弹窗；
     * 网络失败则保留缓存的强制提示（离线降级）。
     */
    private fun verifyCachedForced() {
        if (!store.autoCheckEnabled) return
        if (verifyCachedJob?.isActive == true) return
        val now = clock()
        if (now - lastForcedVerifyAtMillis < UpdateConfig.AUTO_RETRY_INTERVAL_MS) return
        lastForcedVerifyAtMillis = now
        val id = taskId
        verifyCachedJob = scope.launch {
            val result = api.fetchLatest(store.channel)
            if (id != taskId) return@launch
            if (_state.value.phase !is UpdatePhase.Available) return@launch
            result.onSuccess { latest ->
                store.lastSuccessAtMillis = clock()
                store.lastAttemptAtMillis = clock()
                val newer = isNewerVersion(latest.versionName, currentVersionName)
                val forced = UpdateRollout.isForced(latest, currentVersionName)
                val eligible = UpdateRollout.isEligible(store.installId, latest)
                val phase = when {
                    !newer -> {
                        store.clearCache()
                        UpdatePhase.UpToDate(UpdateTrigger.AUTO, currentVersionName)
                    }

                    forced -> {
                        store.saveCached(latest)
                        UpdatePhase.Available(UpdateTrigger.AUTO, latest, forced = true)
                    }

                    !eligible -> {
                        store.saveCached(latest)
                        UpdatePhase.RolloutPending(latest, latest.rolloutPercent)
                    }

                    else -> {
                        store.saveCached(latest)
                        UpdatePhase.Available(UpdateTrigger.AUTO, latest)
                    }
                }
                _state.update { it.copy(phase = phase, lastCheckAtMillis = clock()) }
            }.onFailure {
                // 离线/网络异常：保留缓存强制，1h 内不再重复后台校验（手动检查不受限）。
                store.lastAttemptAtMillis = clock()
            }
        }
    }

    private fun armDownloadWatchdog(info: UpdateInfo, workId: String?) {
        if (downloadWatchdogJob?.isActive == true) return
        downloadWatchdogJob = scope.launch {
            delay(DOWNLOAD_START_TIMEOUT_MS)
            if (_state.value.phase is UpdatePhase.Downloading) {
                // 异步入队失败/WorkManager 长时间不启动：主动失败并取消任务，
                // 避免不可取消的「0%」界面卡死。
                scheduler.cancel(workId ?: activeDownloadWorkId)
                activeDownloadWorkId = null
                downloadActiveInProcess = false
                failDownload(info, UpdateFailure.Timeout)
            }
        }
    }

    private fun cancelDownloadWatchdog() {
        downloadWatchdogJob?.cancel()
        downloadWatchdogJob = null
    }

    private fun isDownloadInProgressPhase(phase: UpdatePhase): Boolean =
        phase is UpdatePhase.Downloading ||
            phase is UpdatePhase.Verifying ||
            phase is UpdatePhase.NeedInstallPermission

    /** 清理已安装/过期的 APK 缓存；活跃下载（.part 或更高版本）保留。 */
    private fun cleanupOldDownloadCache() {
        val dir = File(appContext.cacheDir, "updates")
        val files = dir.listFiles() ?: return
        val now = clock()
        files.forEach { file ->
            val name = file.name
            val version = name
                .removePrefix("zhibox-")
                .removeSuffix(".part")
                .removeSuffix(".apk")
            val keepForUpdate = version.isNotBlank() &&
                isNewerVersion(version, currentVersionName)
            val tooOld = now - file.lastModified() >= 7L * 24 * 60 * 60 * 1_000L
            if (!keepForUpdate || tooOld) file.delete()
        }
    }

    private fun currentActionInfo(): UpdateInfo? = when (val phase = _state.value.phase) {
        is UpdatePhase.Available -> phase.info
        is UpdatePhase.Downloading -> phase.info
        is UpdatePhase.Verifying -> phase.info
        is UpdatePhase.NeedInstallPermission -> phase.info
        is UpdatePhase.DownloadFailed -> phase.info
        else -> null
    }

    private fun failDownload(info: UpdateInfo, failure: UpdateFailure) {
        if (failure == UpdateFailure.ReleaseUnavailable) {
            // Release/资产已下架：清缓存与强制状态，弹可关闭的一次性失败提示，
            // 避免用同一缓存 URL 无限重试/重启后再次强制。
            store.clearCache()
            store.lastSuccessAtMillis = 0L
            store.lastAttemptAtMillis = 0L
            clearPendingInstall()
            scheduler.cancel()
            downloadActiveInProcess = false
            activeForced = false
            _state.update {
                it.copy(
                    phase = UpdatePhase.DownloadFailed(
                        info = info,
                        failure = failure,
                        forced = false,
                        escapeAllowed = true,
                    ),
                    forcedEscapeAllowed = true,
                )
            }
            return
        }
        if (!isNewerVersion(info.versionName, currentVersionName)) {
            // 已安装完成后的迟到失败（旧工作/旧回调）不应再打扰用户。
            cleanupStaleDownload(null)
            return
        }
        val forced = activeForced || (_state.value.phase as? UpdatePhase.DownloadFailed)?.forced == true
        downloadFailureCount++
        val escapeAllowed = forced && downloadFailureCount >= FORCED_ESCAPE_FAILURES
        _state.update {
            it.copy(
                phase = UpdatePhase.DownloadFailed(
                    info = info,
                    failure = failure,
                    forced = forced,
                    escapeAllowed = escapeAllowed,
                ),
                forcedEscapeAllowed = escapeAllowed,
            )
        }
    }

    private fun clearPendingInstall() {
        store.downloadedVersion = null
        store.downloadedApkPath = null
        UpdateNotifications.cancelComplete(appContext)
    }

    // ── 弹窗交互 ────────────────────────────────────────────────

    fun dismiss() {
        when (val phase = _state.value.phase) {
            is UpdatePhase.Available -> {
                if (phase.forced) return
                val version = phase.info.versionName
                scope.launch { store.dismissedVersion = version }
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            is UpdatePhase.DownloadFailed -> {
                if (phase.forced && !phase.escapeAllowed) return
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }

            is UpdatePhase.NeedInstallPermission ->
                _state.update { it.copy(phase = UpdatePhase.Idle) }

            else -> Unit
        }
    }

    fun retry() {
        when (_state.value.phase) {
            is UpdatePhase.CheckFailed -> checkNow()
            is UpdatePhase.DownloadFailed -> {
                val failed = _state.value.phase as UpdatePhase.DownloadFailed
                if (failed.failure == UpdateFailure.ReleaseUnavailable) {
                    checkNow()
                    return
                }
                _state.update {
                    it.copy(
                        phase = UpdatePhase.Available(
                            UpdateTrigger.MANUAL,
                            failed.info,
                            forced = failed.forced,
                        ),
                    )
                }
                startDownload()
            }

            else -> Unit
        }
    }

    fun onResumeForInstallPermission() {
        if (_state.value.phase !is UpdatePhase.NeedInstallPermission) return
        if (!installer.canInstallPackages()) return
        scope.launch { installPendingIfAny() }
    }

    /**
     * 应用回到前台：处理「安装权限」返回续装，以及**通知被拒/后台完成**场景。
     * 后台下载完成时若通知不可见，管理器只会把 APK 落到 store；用户下一次回到应用时
     * 在这里自动继续安装（无需等进程重启）。
     */
    fun onAppResumed() {
        onResumeForInstallPermission()
        scope.launch {
            when (_state.value.phase) {
                is UpdatePhase.Idle, is UpdatePhase.UpToDate -> installPendingIfAny()
                else -> Unit
            }
        }
    }

    fun openInstallPermissionSettings() {
        runCatching { appContext.startActivity(installer.installPermissionIntent()) }
    }

    /**
     * Debug/验收专用：注入 mock 状态（仅 MainActivity 在 debuggable 构建读取 autostart extra 时调用）。
     */
    fun debugInjectMockState(kind: String) {
        val fake = UpdateInfo(
            versionName = "9.9.9",
            tagName = "v9.9.9",
            title = "ZhiBox v9.9.9",
            notes = "· 设置页简化：简单首页 4 卡 + 高级与诊断\n· 原 17 项能力零缺失\n· 修复若干问题",
            publishedAt = "2026-10-27T00:00:00Z",
            htmlUrl = "https://github.com/${UpdateConfig.repoOwner}/${UpdateConfig.repoName}/releases",
            apkUrl = "https://github.com/${UpdateConfig.repoOwner}/${UpdateConfig.repoName}/releases/download/v9.9.9/zhibox-9.9.9-0000000-release-signed.apk",
            apkSizeBytes = 70L * 1024 * 1024,
            sha256 = "0".repeat(64),
            sha256Url = null,
        )
        val phase = when (kind) {
            "available" -> UpdatePhase.Available(UpdateTrigger.MANUAL, fake)
            "downloading" -> UpdatePhase.Downloading(fake, 34L * 1024 * 1024, 70L * 1024 * 1024)
            "failed" -> UpdatePhase.DownloadFailed(fake, UpdateFailure.Network)
            "permission" -> UpdatePhase.NeedInstallPermission(fake)
            else -> return
        }
        _state.update { it.copy(phase = phase) }
    }

    companion object {
        private const val FORCED_ESCAPE_FAILURES = 3
        private const val DOWNLOAD_START_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: AppUpdateManager? = null

        fun get(context: Context): AppUpdateManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(appContext: Context): AppUpdateManager = AppUpdateManager(
            appContext = appContext,
            api = HttpUpdateApi(),
            scheduler = WorkManagerUpdateDownloadScheduler(appContext),
            installer = AndroidUpdateInstaller(appContext),
            validator = AndroidApkValidator(appContext),
            store = SharedPrefsUpdateStore(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = System::currentTimeMillis,
            currentVersionName = BuildConfig.VERSION_NAME,
        )
    }
}
