package com.zhibox.memory.ui

import android.app.Application
import com.zhibox.memory.R
import com.zhibox.memory.core.llm.LlmPreferences
import com.zhibox.memory.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B14.1：网页 URL 导入的 URL 校验（纯 JVM / Robolectric，零网络、零真实设备）。
 *
 * 背景：Compose instrumentation 在当前设备批次不可用（K60U HyperOS 进程崩溃、M2012K10C 挂起，
 * 均未通过），按 task-75 兜底改为可稳定运行的 JVM 测试（不引入不稳定的 Compose instrumentation）。
 *
 * 覆盖 [ImportViewModel.importUrl] 的**同步前置校验**（不触达 WebPageFetcher/网络）：
 * 1) http:// → 可读“仅 HTTPS”提示；
 * 2) 空 URL → 可读非法提示；
 * 3) 非 https 协议（ftp://）→ 可读非法提示；
 * 4) https + 禁网（默认隐私值 false）→ 可读“已开启禁止网络”提示，且 urlFetching 复位。
 *
 * 说明：仅验证 UI 侧前置校验与状态；真实抓取（HTTPS/2MB/标题正文）由 core `WebPageFetcher`
 * 的 JVM 测试覆盖，端到端留 Lead 设备批次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportUrlValidationRobolectricTest {

    private lateinit var app: Application
    private lateinit var viewModel: ImportViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        // 隐私默认值：允许联网=false（ImportViewModel.init 后台协程不会在断言前跑完；这里显式复位）
        app.getSharedPreferences("llm_preferences", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        LlmPreferences.setNetworkAllowed(app, false)
        viewModel = ImportViewModel(app)
    }

    @Test
    fun httpUrl_给出仅HTTPS可读提示() {
        viewModel.importUrl("http://example.com/article")

        assertEquals(
            app.getString(R.string.import_url_invalid),
            viewModel.errorMessage.value,
        )
        assertFalse(viewModel.urlFetching.value)
    }

    @Test
    fun emptyUrl_给出可读非法提示() {
        viewModel.importUrl("   ")

        assertEquals(
            app.getString(R.string.import_url_invalid),
            viewModel.errorMessage.value,
        )
        assertFalse(viewModel.urlFetching.value)
    }

    @Test
    fun nonHttpsProtocol_给出可读非法提示() {
        viewModel.importUrl("ftp://example.com/file.txt")

        assertEquals(
            app.getString(R.string.import_url_invalid),
            viewModel.errorMessage.value,
        )
        assertFalse(viewModel.urlFetching.value)
    }

    @Test
    fun httpsButNetworkBlocked_给出禁网可读提示_且不进入抓取态() {
        viewModel.importUrl("https://example.com/article")

        assertEquals(
            app.getString(R.string.import_url_network_blocked),
            viewModel.errorMessage.value,
        )
        assertFalse(viewModel.urlFetching.value)
    }

    /** 与既有 Robolectric 测试相同的 AppDatabase 单例清理（仅测试侧反射，不改 main）。 */
    private fun resetDatabaseSingleton() {
        val outerField = runCatching {
            AppDatabase::class.java.getDeclaredField("instance")
        }.getOrNull()
        val companionField = runCatching {
            Class.forName("com.zhibox.memory.data.db.AppDatabase\$Companion")
                .getDeclaredField("instance")
        }.getOrNull()
        val field = outerField ?: companionField
        checkNotNull(field) { "无法定位 AppDatabase.instance 单例字段" }
        val holder: Any? = if (field.declaringClass == AppDatabase::class.java) {
            null
        } else {
            field.declaringClass.getField("INSTANCE").get(null)
        }
        field.isAccessible = true
        runCatching { (field.get(holder) as? AppDatabase)?.close() }
        field.set(holder, null)
    }
}
