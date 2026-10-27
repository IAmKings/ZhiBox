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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
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
        var cached: UpdateInfo? = null
        override fun cachedInfo(): UpdateInfo? = cached
        override fun saveCached(info: UpdateInfo) {
            cached = info
        }

        override fun clearCache() {
            cached = null
        }
    }

    private class FakeApi(
        private val results: MutableList<Result<UpdateInfo>> = mutableListOf(),
        private val delayMs: Long = 0L,
    ) : UpdateApi {
        var calls: Int = 0
        override suspend fun fetchLatest(): Result<UpdateInfo> {
            calls++
            // 先取结果再 delay：模拟"请求已发出、结果已确定但尚未返回"，
            // 这样被取消的旧任务不会吃掉下一次调用的返回值。
            val result = results.removeFirstOrNull()
                ?: Result.failure(UpdateException(UpdateFailure.Network))
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakeDownloader(
        private val content: ByteArray = ByteArray(16) { it.toByte() },
        private val failure: UpdateFailure? = null,
        private val onProgressToo: Boolean = true,
    ) : UpdateDownloader {
        override suspend fun download(
            info: UpdateInfo,
            targetDir: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): File {
            failure?.let { throw UpdateException(it) }
            val file = File(targetDir, "zhibox-${info.versionName}.apk")
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            if (onProgressToo) {
                onProgress(content.size.toLong() / 2, content.size.toLong())
                onProgress(content.size.toLong(), content.size.toLong())
            }
            return file
        }
    }

    private class FakeValidator(private val failure: UpdateFailure? = null) : ApkValidator {
        override fun validate(apk: File): UpdateFailure? = failure
    }

    private class FakeInstaller(
        var result: InstallResult = InstallResult.Launched,
    ) : UpdateInstaller {
        var installed: File? = null
        override fun canInstallPackages(): Boolean = true
        override fun install(apk: File): InstallResult {
            installed = apk
            return result
        }

        override fun installPermissionIntent(): Intent = Intent()
    }

    private fun info(version: String) = UpdateInfo(
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
    )

    private fun manager(
        scope: CoroutineScope,
        store: UpdateStore = FakeStore(),
        api: UpdateApi = FakeApi(),
        downloader: UpdateDownloader = FakeDownloader(),
        validator: ApkValidator = FakeValidator(),
        installer: UpdateInstaller = FakeInstaller(),
        now: Long = 1_700_000_000_000L,
    ): AppUpdateManager = AppUpdateManager(
        appContext = ApplicationProvider.getApplicationContext<Context>(),
        api = api,
        downloader = downloader,
        installer = installer,
        validator = validator,
        store = store,
        scope = scope,
        clock = { now },
        currentVersionName = "0.1.6",
        downloadDir = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "updates-test",
        ),
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
    fun downloadVerifiesAndLaunchesInstaller() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val installer = FakeInstaller()
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                installer = installer,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
            advanceUntilIdle()

            assertTrue(installer.installed != null)
            assertTrue(manager.state.value.phase is UpdatePhase.Idle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun checksumFailureBecomesDownloadFailed() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                downloader = FakeDownloader(failure = UpdateFailure.ChecksumMismatch),
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
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
            val manager = manager(
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                api = FakeApi(mutableListOf(Result.success(info("0.1.7")))),
                validator = FakeValidator(UpdateFailure.ApkInvalid),
                installer = installer,
            )
            manager.onAppEntered()
            advanceUntilIdle()
            manager.startDownload()
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
}
