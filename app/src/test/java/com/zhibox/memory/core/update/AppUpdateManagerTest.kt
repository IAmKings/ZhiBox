package com.zhibox.memory.core.update

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppUpdateManager 状态机测试（Robolectric + kotlinx-coroutines-test）：
 * 节流、代际取消、下载/校验/安装编排、失败路径。全部 fake，无真实网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateManagerTest {

    private class FakeStore : UpdateStore {
        override var lastSuccessAtMillis: Long = 0L
        override var lastAttemptAtMillis: Long = 0L
        override var dismissedVersion: String? = null
        override var autoCheckEnabled: Boolean = true
        override var channel: UpdateChannel = UpdateChannel.STABLE
        override var installId: String = "test-install-id"
        var cached: UpdateInfo? = null
        override var cachedMetaKnown: Boolean = true
        override var cachedAtMillis: Long = 1_700_000_000_000L
        override fun cachedInfo(): UpdateInfo? = cached
        override fun saveCached(info: UpdateInfo) {
            cached = info
        }

        override fun clearCache() {
            cached = null
        }

        private var routes: Map<String, RouteHealth> = emptyMap()
        override fun routeHealth(): Map<String, RouteHealth> = routes
        override fun updateRouteHealth(health: Map<String, RouteHealth>) {
            routes = health
        }

        override var downloadedVersion: String? = null
        override var downloadedApkPath: String? = null
        override var installerLaunchedVersion: String? = null
        override var consumedWorkId: String? = null
    }

    private class FakeApi(
        private val results: MutableList<Result<UpdateInfo>> = mutableListOf(),
        private val delayMs: Long = 0L,
    ) : UpdateApi {
        var calls: Int = 0
        override suspend fun fetchLatest(channel: UpdateChannel): Result<UpdateInfo> {
            calls++
            // 先取结果再 delay：模拟"请求已发出、结果已确定但尚未返回"，
            // 这样被取消的旧任务不会吃掉下一次调用的返回值。
            val result = results.removeFirstOrNull()
                ?: Result.failure(UpdateException(UpdateFailure.Network))
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakeScheduler : UpdateDownloadScheduler {
        var enqueued: UpdateInfo? = null
        private val states = kotlinx.coroutines.flow.MutableSharedFlow<DownloadWorkState>(
            extraBufferCapacity = 64,
        )
        var throwOnEnqueue: Boolean = false
        var nextWorkId: String = "work-1"
        var lastWorkId: String? = null
        override suspend fun enqueue(info: UpdateInfo): String {
            if (throwOnEnqueue) throw IllegalStateException("WorkManager unavailable")
            enqueued = info
            lastWorkId = nextWorkId
            return nextWorkId
        }

        override fun observe(): kotlinx.coroutines.flow.Flow<DownloadWorkState> = states
        var cancelCalls: Int = 0
        var cancelledWorkIds: MutableList<String?> = mutableListOf()
        override fun cancel(workId: String?) {
            cancelCalls++
            cancelledWorkIds.add(workId)
        }

        fun emit(work: DownloadWorkState) {
            check(states.tryEmit(work)) { "work state buffer full" }
        }
    }

    private class FakeValidator(private val failure: UpdateFailure? = null) : ApkValidator {
        override fun validate(apk: File): UpdateFailure? = failure
    }

    private class FakeInstaller(
        var result: InstallResult = InstallResult.Launched,
        var canInstall: Boolean = true,
    ) : UpdateInstaller {
        var installed: File? = null
        var installCalls: Int = 0
        override fun canInstallPackages(): Boolean = canInstall
        override fun install(apk: File): InstallResult {
            installCalls++
            installed = apk
            return result
        }

        override fun installPermissionIntent(): Intent = Intent()
    }

    private fun info(
        version: String,
        rolloutPercent: Int = 100,
        minSupportedVersion: String? = null,
    ) = UpdateInfo(
        versionName = version,
        tagName = "v$version",
        title = "ZhiBox v$version",
        notes = "更新内容",
        publishedAt = "2026-10-27T00:00:00Z",
        htmlUrl = "https://github.com/IAmKings/ZhiBox/releases",
        apkUrl = "https://github.com/IAmKings/ZhiBox/releases/download/v$version/x-release-signed.apk",
        apkSizeBytes = 16,
        sha256 = "e".repeat(64),
        sha256Url = null,
        rolloutPercent = rolloutPercent,
        minSupportedVersion = minSupportedVersion,
    )

    private fun manager(
        scope: CoroutineScope,
        store: UpdateStore = FakeStore(),
        api: UpdateApi = FakeApi(),
        scheduler: FakeScheduler = FakeScheduler(),
        validator: ApkValidator = FakeValidator(),
        installer: UpdateInstaller = FakeInstaller(),
        now: Long = 1_700_000_000_000L,
        foreground: () -> Boolean = { true },
        currentVersionName: String = "0.1.6",
    ): AppUpdateManager = AppUpdateManager(
        appContext = ApplicationProvider.getApplicationContext<Context>(),
        api = api,
        scheduler = scheduler,
        installer = installer,
        validator = validator,
        store = store,
        scope = scope,
        clock = { now },
        currentVersionName = currentVersionName,
        foreground = foreground,
    )

    @Test
    fun autoCheckSkippedWithin24h() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = 1_700_000_000_000L
            val store = FakeStore().apply { lastSuccessAtMillis = now - 1_000L }
            val api = FakeApi()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                api = api,
                now = now,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            assertEquals(0, api.calls)
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun autoCheckFindsNewVersionAndShowsDialogState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = 1_700_000_000_000L
            val store = FakeStore()
            val api = FakeApi(mutableListOf(Result.success(info("0.1.7"))))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                api = api,
                now = now,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            assertEquals(1, api.calls)
            val phase = manager.state.value.phase as UpdatePhase.Available
            assertEquals(UpdateTrigger.AUTO, phase.trigger)
            assertEquals("0.1.7", phase.info.versionName)
            assertEquals(now, store.lastSuccessAtMillis)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualCheckCancelsInFlightAutoCheckAndUsesNewestResult() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val api = FakeApi(
                results = mutableListOf(
                    Result.success(info("0.1.7")),
                    Result.success(info("0.1.8")),
                ),
                delayMs = 1_000L,
            )
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
            )
            manager.onAppEntered()
            runCurrent() // 自动检查已发起并挂在 delay 上
            assertEquals(1, api.calls)

            manager.checkNow() // 取消旧任务 + 代际 +1
            advanceUntilIdle()

            assertEquals(2, api.calls)
            val phase = manager.state.value.phase as UpdatePhase.Available
            assertEquals(UpdateTrigger.MANUAL, phase.trigger)
            assertEquals("0.1.8", phase.info.versionName)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualCheckUpToDateStaysInline() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.6")))),
            )
            manager.checkNow()
            advanceUntilIdle()
            val phase = manager.state.value.phase as UpdatePhase.UpToDate
            assertEquals(UpdateTrigger.MANUAL, phase.trigger)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun downloadWorkSuccessVerifiesAndLaunchesInstaller() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val installer = FakeInstaller()
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                scheduler = scheduler,
                installer = installer,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            assertEquals("0.1.7", scheduler.enqueued?.versionName)

            val apk = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates-test/zhibox-0.1.7.apk",
            )
            apk.parentFile?.mkdirs()
            apk.writeBytes(ByteArray(16))
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = apk.absolutePath,
                ),
            )
            advanceUntilIdle()

            assertTrue(installer.installed != null)
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun downloadWorkFailureBecomesDownloadFailed() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                scheduler = scheduler,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.ChecksumMismatch,
                ),
            )
            advanceUntilIdle()

            val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertEquals(UpdateFailure.ChecksumMismatch, phase.failure)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun apkValidatorFailureBlocksInstaller() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val installer = FakeInstaller()
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                scheduler = scheduler,
                validator = FakeValidator(UpdateFailure.ApkInvalid),
                installer = installer,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            val apk = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates-test/zhibox-0.1.7.apk",
            )
            apk.parentFile?.mkdirs()
            apk.writeBytes(ByteArray(16))
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = apk.absolutePath,
                ),
            )
            advanceUntilIdle()

            assertNull(installer.installed)
            val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertEquals(UpdateFailure.ApkInvalid, phase.failure)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissRecordsVersionAndSuppressesAutoPrompt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.dismiss()
            advanceUntilIdle()
            assertEquals("0.1.7", store.dismissedVersion)
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)

            // 新进程再进入：缓存更新但已被取消 → 不再自动弹窗、也不请求网络。
            val api2 = FakeApi()
            val manager2 = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                api = api2,
            )
            manager2.onAppEntered()
            advanceUntilIdle()
            assertEquals(0, api2.calls)
            assertTrue(manager2.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cachedUpdateShowsWithoutNetwork() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = 1_700_000_000_000L
            val store = FakeStore().apply {
                lastSuccessAtMillis = now
                cached = info("0.1.7")
            }
            val api = FakeApi()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                api = api,
                now = now,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            assertEquals(0, api.calls)
            val phase = manager.state.value.phase as UpdatePhase.Available
            assertEquals("0.1.7", phase.info.versionName)
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test
    fun forcedUpdateBypassesRolloutAndDismiss() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(
                    mutableListOf(
                        Result.success(info("0.1.7", rolloutPercent = 0, minSupportedVersion = "0.1.7")),
                    ),
                ),
            )
            manager.checkNow()
            advanceUntilIdle()
            val phase = manager.state.value.phase as UpdatePhase.Available
            assertTrue(phase.forced)
            manager.dismiss()
            advanceUntilIdle()
            // 强制更新不可取消
            assertTrue(manager.state.value.phase is UpdatePhase.Available)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rolloutNotEligibleShowsPending() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7", rolloutPercent = 0)))),
            )
            manager.checkNow()
            advanceUntilIdle()
            assertTrue(manager.state.value.phase is UpdatePhase.RolloutPending)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun forcedUpdateAllowsEscapeAfterThreeDownloadFailures() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(
                    mutableListOf(
                        Result.success(info("0.1.7", minSupportedVersion = "0.1.7")),
                    ),
                ),
                scheduler = scheduler,
            )
            manager.checkNow()
            advanceUntilIdle()
            repeat(3) { attempt ->
                manager.startDownload()
                advanceUntilIdle()
                scheduler.emit(
                    DownloadWorkState(
                        status = DownloadWorkState.Status.FAILED,
                        failure = UpdateFailure.Network,
                    ),
                )
                advanceUntilIdle()
                val failed = manager.state.value.phase as UpdatePhase.DownloadFailed
                assertTrue(failed.forced)
                if (attempt < 2) {
                    manager.retry()
                    advanceUntilIdle()
                }
            }
            val failed = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertTrue("第三次失败应允许退出", failed.escapeAllowed)
            manager.dismiss()
            advanceUntilIdle()
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingInstallResumesWhenAppReturnsToForeground() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val installer = FakeInstaller()
            val store = FakeStore()
            val apk = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates-test/pending-0.1.7.apk",
            )
            apk.parentFile?.mkdirs()
            apk.writeBytes(ByteArray(8))
            store.downloadedVersion = "0.1.7"
            store.downloadedApkPath = apk.absolutePath
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                installer = installer,
            )
            manager.onAppResumed()
            advanceUntilIdle()
            assertTrue(installer.installed != null)
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleWorkSuccessAfterAppAlreadyUpdatedDoesNotShowFailure() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply { saveCached(info("0.1.7")) }
            val installer = FakeInstaller()
            val scheduler = FakeScheduler()
            val apk = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates-test/stale-0.1.7.apk",
            )
            apk.parentFile?.mkdirs()
            apk.writeBytes(ByteArray(8))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                scheduler = scheduler,
                installer = installer,
                // 模拟自更新已完成：当前进程已经是 0.1.7，而 WorkManager 里还有旧的 SUCCEEDED。
                currentVersionName = "0.1.7",
            )
            advanceUntilIdle()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = apk.absolutePath,
                ),
            )
            advanceUntilIdle()

            assertNull("已安装完成不应再拉起安装器", installer.installed)
            assertTrue("已安装完成不应弹更新失败：${manager.state.value.phase}",
                manager.state.value.phase is UpdatePhase.Idle)
            assertFalse("残留下载文件应被清理", apk.exists())
            assertTrue("应取消旧 WorkManager 任务", scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleWorkSuccessEvenIfInstallersWereIdleIsSilent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore()
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                scheduler = scheduler,
                currentVersionName = "0.1.7",
            )
            advanceUntilIdle()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = null,
                ),
            )
            advanceUntilIdle()
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
            assertTrue(scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cachedForcedUpdateOnLaunchIsNotDismissible() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply {
                saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            }
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            val phase = manager.state.value.phase as UpdatePhase.Available
            assertTrue("冷启动缓存也必须保留 forced", phase.forced)
            manager.dismiss()
            advanceUntilIdle()
            assertTrue("强制更新不可取消", manager.state.value.phase is UpdatePhase.Available)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun historicalFailedWorkInfoAfterRestartDoesNotShowFailure() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply {
                saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            }
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                store = store,
                scheduler = scheduler,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            assertTrue(manager.state.value.phase is UpdatePhase.Available)

            // 模拟重启后 WorkManager 回放上一会话的 FAILED：不能覆盖成「更新失败」。
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.Network,
                ),
            )
            advanceUntilIdle()

            val phase = manager.state.value.phase
            assertTrue("历史 FAILED 不应弹更新失败：$phase", phase is UpdatePhase.Available)
            assertTrue((phase as UpdatePhase.Available).forced)
            assertTrue(scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun historicalFailedWorkInfoWithoutUpdateIsSilentlyIgnored() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                scheduler = scheduler,
            )
            advanceUntilIdle()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.Network,
                ),
            )
            advanceUntilIdle()
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
            assertTrue(scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun forcedCachedUpdateIsClearedWhenServerNoLongerOffersIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply {
                saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            }
            // 服务端最新就是当前版本（force Release 已删除/回滚）
            val api = FakeApi(mutableListOf(Result.success(info("0.1.6"))))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                store = store,
            )
            manager.onAppEntered()
            advanceUntilIdle()

            assertTrue("服务端无更新后应回到最新态：${manager.state.value.phase}",
                manager.state.value.phase is UpdatePhase.UpToDate)
            assertNull("失效的强制缓存应被清理", store.cachedInfo())
            assertEquals(1, api.calls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun forcedCachedUpdateStaysWhenVerificationFails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply {
                saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            }
            val api = FakeApi(
                mutableListOf(Result.failure(UpdateException(UpdateFailure.Network))),
            )
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                store = store,
            )
            manager.onAppEntered()
            advanceUntilIdle()

            val phase = manager.state.value.phase as UpdatePhase.Available
            assertTrue("离线时应保留缓存强制", phase.forced)
            assertNotNull(store.cachedInfo())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun releaseUnavailableClearsForcedCacheAndIsNotForced() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply {
                saveCached(info("0.1.7", minSupportedVersion = "0.1.7"))
            }
            val scheduler = FakeScheduler()
            // verify 返回同一强制目标，让弹窗保持在 Available(forced)
            val api = FakeApi(
                mutableListOf(Result.success(info("0.1.7", minSupportedVersion = "0.1.7"))),
            )
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                store = store,
                scheduler = scheduler,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            assertTrue((manager.state.value.phase as UpdatePhase.Available).forced)

            manager.startDownload()
            runCurrent()
            scheduler.emit(
                DownloadWorkState(
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.ReleaseUnavailable,
                ),
            )
            advanceUntilIdle()

            val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertEquals(UpdateFailure.ReleaseUnavailable, phase.failure)
            assertFalse("版本已下架后不应继续强制", phase.forced)
            assertNull("版本已下架应清缓存", store.cachedInfo())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun startupCleanupRemovesOldApksButKeepsNewerAndPartial() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val dir = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "updates",
        )
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val old = File(dir, "zhibox-0.1.5.apk").apply { writeBytes(ByteArray(4)) }
            val newer = File(dir, "zhibox-0.1.7.apk").apply { writeBytes(ByteArray(4)) }
            val part = File(dir, "zhibox-0.1.7.apk.part").apply { writeBytes(ByteArray(4)) }

            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                currentVersionName = "0.1.6",
            )
            manager.onAppEntered()
            advanceUntilIdle()

            assertFalse("已安装旧版本 APK 应清理：${old.name}", old.exists())
            assertTrue("更高版本应保留", newer.exists())
            assertTrue("下载中的 .part 应保留", part.exists())
        } finally {
            dir.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun waitingWorkStateTimesOutInsteadOfStickingAtZero() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler()
            val api = FakeApi(
                mutableListOf(Result.success(info("0.1.7", minSupportedVersion = "0.1.7"))),
            )
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                scheduler = scheduler,
            )
            manager.checkNow()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()

            scheduler.emit(DownloadWorkState(status = DownloadWorkState.Status.WAITING))
            runCurrent()
            advanceTimeBy(20_001)
            runCurrent()

            val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertEquals(UpdateFailure.Timeout, phase.failure)
            assertTrue("卡在 ENQUEUED 应取消任务", scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun enqueueFailureDoesNotLeaveDialogStuck() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler().apply { throwOnEnqueue = true }
            val api = FakeApi(mutableListOf(Result.success(info("0.1.7"))))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                scheduler = scheduler,
            )
            manager.checkNow()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()

            assertTrue(manager.state.value.phase is UpdatePhase.DownloadFailed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun noWaitingCallbackStillTimesOutFromImmediateWatchdog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler()
            val api = FakeApi(mutableListOf(Result.success(info("0.1.7"))))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                scheduler = scheduler,
            )
            manager.checkNow()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            // 不发送 WAITING，只让启动看门狗到期
            advanceTimeBy(20_001)
            runCurrent()

            val phase = manager.state.value.phase as UpdatePhase.DownloadFailed
            assertEquals(UpdateFailure.Timeout, phase.failure)
            assertTrue("应取消当前任务", scheduler.cancelCalls >= 1)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun oldCancelledWorkIdDoesNotCancelRetriedDownload() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val scheduler = FakeScheduler().apply { nextWorkId = "work-new" }
            val api = FakeApi(mutableListOf(Result.success(info("0.1.7"))))
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = api,
                scheduler = scheduler,
            )
            manager.checkNow()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            assertEquals("work-new", scheduler.lastWorkId)

            scheduler.emit(
                DownloadWorkState(
                    workId = "work-old",
                    status = DownloadWorkState.Status.CANCELLED,
                    failure = UpdateFailure.Cancelled,
                ),
            )
            runCurrent()
            assertTrue(
                "旧任务取消回调不能终止新下载：${manager.state.value.phase}",
                manager.state.value.phase is UpdatePhase.Downloading,
            )

            scheduler.emit(
                DownloadWorkState(
                    workId = "work-new",
                    status = DownloadWorkState.Status.FAILED,
                    failure = UpdateFailure.Timeout,
                ),
            )
            runCurrent()
            assertEquals(
                UpdateFailure.Timeout,
                (manager.state.value.phase as UpdatePhase.DownloadFailed).failure,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun coldStartDoesNotOverwriteRestoredDownloadingState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeStore().apply { cached = info("0.1.7") }
            val scheduler = FakeScheduler()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(),
                store = store,
                scheduler = scheduler,
            )
            advanceUntilIdle()
            scheduler.emit(
                DownloadWorkState(
                    workId = "resumed-work",
                    status = DownloadWorkState.Status.RUNNING,
                    downloadedBytes = 1024,
                    totalBytes = 2048,
                ),
            )
            runCurrent()
            assertTrue(manager.state.value.phase is UpdatePhase.Downloading)

            manager.onAppEntered()
            advanceUntilIdle()
            assertTrue(
                "冷启动检查不能覆盖恢复中的下载：${manager.state.value.phase}",
                manager.state.value.phase is UpdatePhase.Downloading,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun consumedSuccessResultIsNotInstalledTwice() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val installer = FakeInstaller()
            val store = FakeStore()
            val scheduler = FakeScheduler().apply { nextWorkId = "work-success" }
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                store = store,
                scheduler = scheduler,
                installer = installer,
            )
            manager.checkNow()
            advanceUntilIdle()
            manager.startDownload()
            runCurrent()
            val apk = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates/zhibox-0.1.7.apk",
            ).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(16))
            }
            scheduler.emit(
                DownloadWorkState(
                    workId = "work-success",
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = apk.absolutePath,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, installer.installCalls)
            assertEquals("work-success", store.consumedWorkId)

            // 模拟重启后 WorkManager 再次回放同一 SUCCEEDED
            scheduler.emit(
                DownloadWorkState(
                    workId = "work-success",
                    status = DownloadWorkState.Status.SUCCEEDED,
                    apkPath = apk.absolutePath,
                ),
            )
            advanceUntilIdle()
            assertEquals("同一 WorkRequest 只能安装一次", 1, installer.installCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun coldStartDoesNotOverwriteRestoredInstallPermissionState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val dir = File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "updates",
            ).apply { mkdirs() }
            val apk = File(dir, "zhibox-0.1.7.apk").apply { writeBytes(ByteArray(16)) }
            val store = FakeStore().apply {
                cached = info("0.1.7")
                downloadedVersion = "0.1.7"
                downloadedApkPath = apk.absolutePath
            }
            val installer = FakeInstaller(canInstall = false)
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(),
                store = store,
                installer = installer,
            )
            manager.onAppEntered()
            advanceUntilIdle()

            assertTrue(
                "冷启动检查不能覆盖待授权状态：${manager.state.value.phase}",
                manager.state.value.phase is UpdatePhase.NeedInstallPermission,
            )
            assertEquals(0, installer.installCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

}
