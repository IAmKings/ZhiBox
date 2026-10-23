package com.zhibox.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zhibox.memory.ui.BenchmarkScreen
import com.zhibox.memory.ui.ChatScreen
import com.zhibox.memory.ui.HomeScreen
import com.zhibox.memory.ui.IntelligenceSection
import com.zhibox.memory.ui.ImportScreen
import com.zhibox.memory.ui.SearchScreen
import com.zhibox.memory.ui.SettingsScreen
import com.zhibox.memory.ui.SourcesScreen
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * 应用入口（P0-3 实验隔离 UI 侧 + P1-8 导航/生命周期）：
 *
 * 1. **P0-3 门禁**：20 个 `autostart_*` extra 与首页「实验/诊断」按钮只在
 *    `(applicationInfo.flags and FLAG_DEBUGGABLE) != 0` 时生效；release 构建忽略所有实验
 *    extra、不渲染实验入口，且 `BenchmarkScreen` 在 release 中只是空实现 stub。
 * 2. **一次性消费**：autostart 只在首次创建（`savedInstanceState == null`）读取，读取后立即
 *    清空 intent extras；Activity 重建（旋转/深色/分屏/进程恢复）不会重放删除来源、FTS 重建
 *    等破坏性 runner。
 * 3. **导航**：手写 String 导航保留（改动小），但 `screen` 用 `rememberSaveable` 跨重建保留，
 *    并加 `BackHandler`：任何子屏按系统返回键回首页（证据详情在 SearchScreen 内部先关证据页）。
 * 4. **B3-11 路由收敛**：20 个 extra 先落入单一 [AutoStart] 数据类，再由 debug 源集的
 *    `BenchmarkScreen` 路由表（`DebugRun`）分派；release 不读取任何实验 extra。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebug =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            // W4-5 ANR 防护监测（仅 debug）：主线程磁盘/网络访问打日志——推理/DB/解析均应在
            // Worker/Dispatchers.Default（生产基准已证），此为回归防线
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        // P0-3 + P1-8：release 不解析任何实验 extra；首次创建才读取，读取后清空 extras。
        val autoStart = if (isDebug && savedInstanceState == null) readAutoStartExtras() else AutoStart()
        if (isDebug && savedInstanceState == null) {
            // 一次性消费：清空 extras，保证同一 intent 不会被再次解析（重建/复用兜底）。
            intent?.replaceExtras(Bundle())
        }

        setContent {
            ZhiBoxTheme {
                var screen by rememberSaveable { mutableStateOf(autoStart.initialScreen()) }
                // UI 改版：首页输入岛提交的检索词 → 检索屏预填（空串表示不预填）
                var pendingSearchQuery by rememberSaveable { mutableStateOf("") }
                // B3-11：AutoStart 在本 Activity 生命周期内只交给 BenchmarkScreen 消费一次；
                // 离开实验屏即清空，避免「首页 → 实验/诊断」二次进入时重放 autostart 实验。
                var pendingAutoStart by remember { mutableStateOf(autoStart) }
                // B3-11 审查修复：导入/取消的 autostart 也必须一次性消费——离开导入屏后清空，
                // 避免再次进入导入屏时重放（不再依赖 ImportViewModel 内部布尔兜底）。
                var pendingImport by remember { mutableStateOf(autoStart.import) }
                var pendingImportCancel by remember { mutableStateOf(autoStart.importCancel) }
                // M2 深链：面板 → 设置页锚点；null = 打开设置页顶部（智能分组）
                var settingsSection by remember { mutableStateOf<IntelligenceSection?>(null) }
                val goHome = {
                    pendingAutoStart = AutoStart()
                    pendingImport = false
                    pendingImportCancel = false
                    pendingSearchQuery = ""
                    screen = "home"
                }
                BackHandler(enabled = screen != "home") { goHome() }
                when (screen) {
                    // B3-11：18 个 autoStart Boolean 收敛为单一 AutoStart，路由表在 debug
                    // BenchmarkScreen 内（release stub 接收同一类型但不读字段）。
                    "benchmark" -> BenchmarkScreen(
                        autoStart = pendingAutoStart,
                        onBack = goHome,
                    )
                    "import" -> ImportScreen(
                        autoStart = pendingImport,
                        autoCancel = pendingImportCancel,
                        onBack = goHome,
                        onOpenSettings = {
                            settingsSection = null
                            screen = "settings"
                        },
                    )
                    "search" -> SearchScreen(
                        onBack = goHome,
                        initialQuery = pendingSearchQuery,
                    )
                    "chat" -> ChatScreen(onBack = goHome)
                    "sources" -> SourcesScreen(
                        onBack = goHome,
                        onOpenImport = { screen = "import" },
                    )
                    "settings" -> SettingsScreen(
                        onBack = {
                            settingsSection = null
                            goHome()
                        },
                        initialSection = settingsSection,
                    )
                    else -> HomeScreen(
                        // 首页输入岛/搜索图标 → 检索屏；query 为空时沿用检索屏上次输入
                        onOpenSearch = { query ->
                            pendingSearchQuery = query
                            screen = "search"
                        },
                        onOpenImport = { screen = "import" },
                        onOpenChat = { screen = "chat" },
                        onOpenSources = { screen = "sources" },
                        onOpenSettings = {
                            settingsSection = null
                            screen = "settings"
                        },
                        // P0-3：仅 debug 构建暴露实验/诊断入口（收进首页「更多」菜单）
                        onOpenDiagnostics = if (isDebug) {
                            { screen = "benchmark" }
                        } else {
                            null
                        },
                        // 文档详情屏尚未落地：先落到来源页（见 HomeScreen 顶部注释）
                        onOpenDocument = { screen = "sources" },
                        // M2：状态点打开「智能状态」面板；面板动作带 section 深链到设置页对应锚点
                        onOpenSettingsSection = { section ->
                            settingsSection = section
                            screen = "settings"
                        },
                    )
                }
            }
        }
    }

    /**
     * 读取一次性自动启动参数（仅 debuggable 构建 + 首次创建时调用）。
     * extra 常量名保留（scripts/验收文档依赖），release 中常量仍在但不被读取。
     */
    private fun readAutoStartExtras(): AutoStart {
        val i = intent ?: return AutoStart()
        return AutoStart(
            benchmark = i.getBooleanExtra(EXTRA_AUTO_BENCHMARK, false),
            g1 = i.getBooleanExtra(EXTRA_AUTO_G1, false),
            scan = i.getBooleanExtra(EXTRA_AUTO_SCAN, false),
            g3 = i.getBooleanExtra(EXTRA_AUTO_G3, false),
            len = i.getBooleanExtra(EXTRA_AUTO_LEN, false),
            pathB = i.getBooleanExtra(EXTRA_AUTO_PATH_B, false),
            g1c = i.getBooleanExtra(EXTRA_AUTO_G1C, false),
            searchEval = i.getBooleanExtra(EXTRA_AUTO_SEARCH_EVAL, false),
            evalSet = i.getBooleanExtra(EXTRA_AUTO_EVAL, false),
            deleteCheck = i.getBooleanExtra(EXTRA_AUTO_DELETE_CHECK, false),
            prod = i.getBooleanExtra(EXTRA_AUTO_PROD, false),
            prodGpu = i.getBooleanExtra(EXTRA_AUTO_PROD_GPU, false),
            prodAuto = i.getBooleanExtra(EXTRA_AUTO_PROD_AUTO, false),
            g1d = i.getBooleanExtra(EXTRA_AUTO_G1D, false),
            g1e = i.getBooleanExtra(EXTRA_AUTO_G1E, false),
            probe = i.getBooleanExtra(EXTRA_AUTO_PROBE, false),
            ftsRecovery = i.getBooleanExtra(EXTRA_AUTO_FTS, false),
            w4plus = i.getBooleanExtra(EXTRA_AUTO_W4PLUS, false),
            import = i.getBooleanExtra(EXTRA_AUTO_IMPORT, false),
            importCancel = i.getBooleanExtra(EXTRA_AUTO_IMPORT_CANCEL, false),
        )
    }

    companion object {
        const val EXTRA_AUTO_BENCHMARK = "autostart_benchmark"
        const val EXTRA_AUTO_G1 = "autostart_g1"
        const val EXTRA_AUTO_SCAN = "autostart_scan"
        const val EXTRA_AUTO_G3 = "autostart_g3"
        const val EXTRA_AUTO_LEN = "autostart_lenbench"
        const val EXTRA_AUTO_PATH_B = "autostart_pathb"
        const val EXTRA_AUTO_G1C = "autostart_g1c"
        const val EXTRA_AUTO_SEARCH_EVAL = "autostart_search_eval"
        const val EXTRA_AUTO_EVAL = "autostart_eval"
        const val EXTRA_AUTO_DELETE_CHECK = "autostart_delete_check"
        const val EXTRA_AUTO_PROD = "autostart_prodbench"
        const val EXTRA_AUTO_PROD_GPU = "autostart_prodbench_gpu"
        const val EXTRA_AUTO_PROD_AUTO = "autostart_prodbench_auto"
        const val EXTRA_AUTO_G1D = "autostart_g1d"
        const val EXTRA_AUTO_G1E = "autostart_g1e"
        const val EXTRA_AUTO_PROBE = "autostart_probe"
        const val EXTRA_AUTO_FTS = "autostart_fts_recovery"
        const val EXTRA_AUTO_W4PLUS = "autostart_w4plus"
        const val EXTRA_AUTO_IMPORT = "autostart_import"
        const val EXTRA_AUTO_IMPORT_CANCEL = "autostart_import_cancel"
    }
}

/**
 * 一次性实验/验收启动参数快照（B3-11：18 个 `autoStart*` Boolean 收敛为单一数据类）。
 *
 * - 由 [MainActivity.readAutoStartExtras] 在**首次创建**时从 20 个 `autostart_*` EXTRA 读取；
 * - 只有 debuggable 构建才会构造非空值（release 恒为默认值，P0-3）；
 * - 路由表（AutoStart → 具体实验）在 debug 源集的 `BenchmarkScreen.kt` 内，release stub
 *   只接收本类型以保持入口签名同步，不读取任何字段。
 *
 * 常量名（scripts/验收文档依赖）保留在 [MainActivity] companion 中，**不要改名**。
 */
data class AutoStart(
    val benchmark: Boolean = false,
    val g1: Boolean = false,
    val scan: Boolean = false,
    val g3: Boolean = false,
    val len: Boolean = false,
    val pathB: Boolean = false,
    val g1c: Boolean = false,
    val searchEval: Boolean = false,
    val evalSet: Boolean = false,
    val deleteCheck: Boolean = false,
    val prod: Boolean = false,
    val prodGpu: Boolean = false,
    val prodAuto: Boolean = false,
    val g1d: Boolean = false,
    val g1e: Boolean = false,
    val probe: Boolean = false,
    val ftsRecovery: Boolean = false,
    val w4plus: Boolean = false,
    val import: Boolean = false,
    val importCancel: Boolean = false,
) {
    /** 任一实验/诊断 extra 命中 → 直接落到实验屏。 */
    val anyBenchmark: Boolean
        get() = benchmark || g1 || scan || g3 || len || pathB || g1c || searchEval ||
            evalSet || deleteCheck || prod || prodGpu || prodAuto || g1d || g1e ||
            probe || ftsRecovery || w4plus

    fun initialScreen(): String = when {
        anyBenchmark -> "benchmark"
        import || importCancel -> "import"
        else -> "home"
    }
}
