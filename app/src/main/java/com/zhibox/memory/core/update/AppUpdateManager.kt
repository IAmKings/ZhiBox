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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 应用内更新的全局服务（Application 作用域单例）。
 *
 * 职责：
 * - 冷启动自动检查（24h 节流）与设置里的手动强制检查；
 * - **任务代际**：手动点击「检查更新」会取消在途检查并生成新任务，旧任务的结果一律丢弃；
 * - 下载/校验/拉起安装器的状态机，供 [UpdateUiState] 驱动全局弹窗与设置行；
 * - 所有磁盘/网络在 `Dispatchers.IO`，安装器 Intent 在 `Dispatchers.Main`。
 *
 * 设计/验收见 `docs/product/APP_UPDATE_SPEC.md`；测试可注入 fake 依赖（internal 构造）。
 */
class AppUpdateManager internal constructor(
    private val appContext: Context,
    private val api: UpdateApi,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val validator: ApkValidator,
    private val store: UpdateStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val currentVersionName: String,
    private val downloadDir: File,
) {

    private val _state = MutableStateFlow(UpdateUiState(currentVersionName = currentVersionName))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val entered = AtomicBoolean(false)
    private var taskId = 0L
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile
    private var downloadedApk: File? = null

    /** 冷启动/进入应用：每进程一次，按 24h 节流决定「弹缓存 / 检查 / 跳过」。 */
    fun onAppEntered() {
        if (!entered.compareAndSet(false, true)) return
        scope.launch {
            when (val action = UpdatePolicy.decideOnLaunch(clock(), store, currentVersionName)) {
                is UpdatePolicy.LaunchAction.ShowCached -> {
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.Available(UpdateTrigger.AUTO, action.info),
                            lastCheckAtMillis = store.lastSuccessAtMillis,
                        )
                    }
                }
                UpdatePolicy.LaunchAction.Check -> startCheck(UpdateTrigger.AUTO)
                UpdatePolicy.LaunchAction.Skip -> Unit
            }
        }
    }

    /**
     * 设置里的「检查更新」：取消在途检查任务 + 任务代际 +1，然后强制重新查询。
     * 正在下载/校验时忽略（保持进度状态，避免把不可取消的下载打断）。
     */
    fun checkNow() {
        if (downloadJob?.isActive == true) return
        startCheck(UpdateTrigger.MANUAL)
    }

    private fun startCheck(trigger: UpdateTrigger) {
        val id = ++taskId
        checkJob?.cancel()
        _state.update { it.copy(phase = UpdatePhase.Checking(trigger)) }
        checkJob = scope.launch {
            val result = api.fetchLatest()
            // 代际守卫：已被新的手动检查取代时，旧结果（成功或失败）一律丢弃。
            if (id != taskId) return@launch
            result.fold(
                onSuccess = { info ->
                    store.lastSuccessAtMillis = clock()
                    store.lastAttemptAtMillis = clock()
                    store.saveCached(info)
                    val newer = isNewerVersion(info.versionName, currentVersionName)
                    val phase = when {
                        !newer -> {
                            store.clearCache()
                            UpdatePhase.UpToDate(trigger, currentVersionName)
                        }
                        trigger == UpdateTrigger.AUTO &&
                            !UpdatePolicy.shouldAutoPrompt(
                                info,
                                currentVersionName,
                                store.dismissedVersion,
                            ) -> UpdatePhase.UpToDate(trigger, currentVersionName)

                        else -> UpdatePhase.Available(trigger, info)
                    }
                    _state.update { it.copy(phase = phase, lastCheckAtMillis = clock()) }
                },
                onFailure = { error ->
                    val failure = (error as? UpdateException)?.failure ?: UpdateFailure.Network
                    store.lastAttemptAtMillis = clock()
                    _state.update {
                        it.copy(
                            phase = if (trigger == UpdateTrigger.MANUAL) {
                                UpdatePhase.CheckFailed(trigger, failure)
                            } else {
                                UpdatePhase.Idle
                            },
                            lastCheckAtMillis = clock(),
                        )
                    }
                },
            )
        }
    }

    /** 弹窗点「立即更新」：下载 → 校验 SHA → APK 校验 → 拉起安装器。 */
    fun startDownload() {
        val available = _state.value.phase as? UpdatePhase.Available ?: return
        if (downloadJob?.isActive == true) return
        val info = available.info
        _state.update { it.copy(phase = UpdatePhase.Downloading(info, 0L, info.apkSizeBytes)) }
        downloadJob = scope.launch {
            try {
                var lastEmitBytes = 0L
                val file = withTimeout(UpdateConfig.DOWNLOAD_TIMEOUT_MS) {
                    downloader.download(info, downloadDir) { downloaded, total ->
                        // 进度节流：每 512KB 或完成时更新一次 StateFlow，避免高频重组。
                        if (downloaded - lastEmitBytes >= PROGRESS_STEP_BYTES || downloaded == total) {
                            lastEmitBytes = downloaded
                            _state.update { current ->
                                if (current.phase is UpdatePhase.Downloading) {
                                    current.copy(phase = UpdatePhase.Downloading(info, downloaded, total))
                                } else {
                                    current
                                }
                            }
                        }
                    }
                }
                downloadedApk = file
                _state.update { it.copy(phase = UpdatePhase.Verifying(info)) }
                val invalid = validator.validate(file)
                if (invalid != null) {
                    file.delete()
                    downloadedApk = null
                    failDownload(info, invalid)
                    return@launch
                }
                launchInstaller(info, file)
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "update download timeout", e)
                failDownload(info, UpdateFailure.Timeout)
            } catch (e: UpdateException) {
                Log.w(TAG, "update download failed: ${e.failure}", e)
                failDownload(info, e.failure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed (unexpected)", e)
                failDownload(info, UpdateFailure.Network)
            }
        }
    }

    /** 拉起系统安装器（Main 线程）；返回结果写回状态机。 */
    private suspend fun launchInstaller(info: UpdateInfo, file: File) {
        val result = withContext(Dispatchers.Main) { installer.install(file) }
        when (result) {
            InstallResult.Launched -> {
                downloadedApk = null
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }
            InstallResult.PermissionRequired -> {
                _state.update { it.copy(phase = UpdatePhase.NeedInstallPermission(info)) }
            }
            is InstallResult.Failed -> failDownload(info, result.failure)
        }
    }

    private fun failDownload(info: UpdateInfo, failure: UpdateFailure) {
        _state.update { it.copy(phase = UpdatePhase.DownloadFailed(info, failure)) }
    }

    /**
     * 弹窗关闭：
     * - Available → 记录 dismissedVersion（对自动检查静默该版本），回 Idle；
     * - DownloadFailed / NeedInstallPermission → 允许关闭回 Idle。
     * 下载/校验中不可关闭（UI 层已禁止，这里做兜底）。
     */
    fun dismiss() {
        when (val phase = _state.value.phase) {
            is UpdatePhase.Available -> {
                val version = phase.info.versionName
                scope.launch { store.dismissedVersion = version }
                _state.update { it.copy(phase = UpdatePhase.Idle) }
            }
            is UpdatePhase.DownloadFailed, is UpdatePhase.NeedInstallPermission ->
                _state.update { it.copy(phase = UpdatePhase.Idle) }

            else -> Unit
        }
    }

    /** 失败态的重试：检查失败 → 重新检查；下载失败 → 重新下载。 */
    fun retry() {
        when (_state.value.phase) {
            is UpdatePhase.CheckFailed -> checkNow()
            is UpdatePhase.DownloadFailed -> {
                val info = (_state.value.phase as UpdatePhase.DownloadFailed).info
                _state.update { it.copy(phase = UpdatePhase.Available(UpdateTrigger.MANUAL, info)) }
                startDownload()
            }
            else -> Unit
        }
    }

    /** 从「去设置授予安装权限」返回时调用：已授权则自动继续安装。 */
    fun onResumeForInstallPermission() {
        if (_state.value.phase !is UpdatePhase.NeedInstallPermission) return
        val apk = downloadedApk?.takeIf { it.isFile } ?: return
        if (!installer.canInstallPackages()) return
        val info = (_state.value.phase as UpdatePhase.NeedInstallPermission).info
        scope.launch { launchInstaller(info, apk) }
    }

    /** 打开「安装未知应用」系统设置页（无对应 Activity 时静默失败，用户可手动返回）。 */
    fun openInstallPermissionSettings() {
        runCatching { appContext.startActivity(installer.installPermissionIntent()) }
    }

    /**
     * Debug/验收专用：注入 mock 状态（仅 MainActivity 在 debuggable 构建读取 autostart extra 时调用）。
     * Release 构建不会读取该 extra，因此状态不会被注入。
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
        private const val TAG = "AppUpdate"
        private const val PROGRESS_STEP_BYTES = 512L * 1024

        @Volatile
        private var instance: AppUpdateManager? = null

        /** 全局单例（Application 作用域；Download 任务不随页面切换取消）。 */
        fun get(context: Context): AppUpdateManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(appContext: Context): AppUpdateManager = AppUpdateManager(
            appContext = appContext,
            api = HttpUpdateApi(),
            downloader = HttpUpdateDownloader(),
            installer = AndroidUpdateInstaller(appContext),
            validator = AndroidApkValidator(appContext),
            store = SharedPrefsUpdateStore(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = System::currentTimeMillis,
            currentVersionName = BuildConfig.VERSION_NAME,
            downloadDir = File(appContext.cacheDir, "updates"),
        )
    }
}
