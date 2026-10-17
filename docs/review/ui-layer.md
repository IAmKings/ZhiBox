# UI 层审查

审查对象：当前工作区文件系统上的代码（含未提交改动；`git diff --stat` 显示 MainActivity +110、BenchmarkScreen +387、ImportScreen +18、AndroidManifest +8，风险新增恰好集中在本层）。只读审查，未修改任何源码、未运行 gradle。

## 总评

- UI 层功能已经"跑得通"：检索→证据回溯、导入进度、来源删除、设置迁移都闭环，危险操作普遍有二次确认与可读错误文案，明显比一般 MVP 的 UI 完成度高。
- 但**没有 ViewModel / 导航库 / DI** 的代价已实际发生：64 个 `remember` 状态散落在 7 个 Composable 里（SettingsScreen 27 个、BenchmarkScreen 22 个），旋转/进程重建即全部丢失；MainActivity 用 String 状态机 + 20 个 autostart extra 手工驱动实验。
- 最严重的是**检索链路在 Main 线程做全量向量扫描**（SearchService 全文无 `withContext`）：10k chunks 时主线程要解析 30MB 向量并做 1536 万次乘加，项目自己的 G4 门限就允许 10k p95 ≤ 800ms，存在 jank/ANR 风险。
- 实验能力（BenchmarkScreen，743 行、18 个 Boolean 参数 + 19 个总参数）与产品 UI 完全耦合、无 debug 门禁；release 中任意 App 可发 Intent 触发删来源/FTS 重建等破坏性 runner（数据丢失）。
- 实验入口自身有一个确定性 bug：`runProbe` 不复位 `running`，点一次"连通矩阵"后全屏按钮与返回永久禁用；实验自动跑批在 Activity 重建时会重放。
- 手写字符串导航没有返回栈、没有 `BackHandler`：任何子屏按系统返回键直接退出 App；从检索结果进证据详情再返回会丢失全部检索结果。
- 资源维度几乎空白：strings.xml 只有 1 条 app_name，UI 层实测 249 处硬编码中文字符串，i18n 无从谈起；深色主题只覆盖 6 个 color role，XML 主题固定 Light。
- 总体判断：P0 1 条、P1 8 条、P2 14 条。若按"Phase 1 实验工具"标准，质量可接受；若按"可发布产品 UI"标准，状态管理与线程边界两项必须先补（约 2-4 人日），实验屏应从产品包剥离（约 1-2 人日）。

## 量化数据（grep/wc 实测）

| 文件 | 行数 | 屏幕级 Composable | `mutableStateOf` | 硬编码中文字符串字面量 |
|---|---:|---:|---:|---:|
| MainActivity.kt | 219 | 1（AppSkeleton，另 1 个 Preview） | 3 | 9 |
| ui/BenchmarkScreen.kt | 743 | 1 | 22 | 107 |
| ui/SettingsScreen.kt | 606 | 1 | 27 | 91 |
| ui/ImportScreen.kt | 205 | 1 | 5 | 11 |
| ui/SearchScreen.kt | 160 | 1 | 4 | 11 |
| ui/SourcesScreen.kt | 133 | 1 | 3 | 13 |
| ui/EvidenceScreen.kt | 78 | 1 | 0 | 7 |
| ui/theme/Theme.kt | 40 | 1（ZhiBoxTheme） | 0 | 0 |
| ui/theme/Color.kt | 15 | 0 | 0 | 0 |
| **合计** | **2199** | **8 业务 Composable（+1 Preview）** | **64** | **249** |

其他实测计数：

- `MainActivity.EXTRA_AUTO_*` 常量 **20 个**（MainActivity.kt:134-153）；`BenchmarkScreen` 参数 **19 个**（18 个 `autoStartXxx: Boolean` + `onBack`，BenchmarkScreen.kt:53-73）。
- `strings.xml` 仅 **1 条**字符串（app_name）；全 UI 层 `stringResource` 调用仅 **1 处**（MainActivity.kt:169）。
- `LaunchedEffect` 调用 **22 处**（BenchmarkScreen 16、ImportScreen 4、SettingsScreen 2）；`DisposableEffect` **0 处**；`rememberCoroutineScope` **5 处**。
- `ViewModel` / `rememberSaveable` / `BackHandler` / `NavHost` / `SavedStateHandle` 在全 `app/src/main/java` 中 **0 命中**；`BuildConfig` **0 命中**（无 debug/release 门禁）。
- UI 层直接构造依赖 **10 处**（`AppDatabase.get` 3、`SearchService` 1、`IngestRepository` 6）。
- `@Preview` 全仓 **1 处**（MainActivity.kt:213-219）；Compose UI 测试 **0**（androidTest 仅 `MigrationTest.kt`）。
- `contentDescription` **8 处**，全部为硬编码中文；`Scaffold + TopAppBar + 返回按钮` 在 6 个屏幕重复。

---

## 发现

### P0

#### P0-1 检索在全量向量扫描阶段占用主线程（jank / ANR 风险）

- **【级别】P0（主线程重计算；实际 ANR 需真机复测 → 待验证）**
- **位置**：`app/src/main/java/com/zhibox/memory/ui/SearchScreen.kt:50,87-98`；`app/src/main/java/com/zhibox/memory/core/search/SearchService.kt:59,151-183`
- **证据**：
  - SearchScreen 用 `rememberCoroutineScope()`（SearchScreen.kt:50）启动 `scope.launch { result = runCatching { service.search(query) } ... }`（SearchScreen.kt:87-98）。该 scope 的上下文是 composition 的 `AndroidUiDispatcher.Main.immediate`，即**主线程**。
  - `SearchService.search` 为 `suspend` 但整个文件 **0 处** `withContext`/`Dispatchers`（grep 实测）；`rankChannels`（SearchService.kt:151-183）在调用方线程依次执行：
    - L164 `FloatArray(n * dim)` + L166-170 `ByteBuffer.wrap(...).asFloatBuffer().get(...)` 解析**全部**向量 blob（10k chunks × 768 dim × 4B ≈ 30MB）；
    - L174-180 两轮 `n × dim` 的 **Double 乘加**（10k×768×2 ≈ 1536 万次）+ L181 `sortedByDescending` 排序。
  - 对比：Room 的 suspend DAO 调用会由 Room 调度到查询线程；`provider.embedQuery` 在实现内部 `withContext(Dispatchers.Default)`（如 UniversalEmbedderProvider.kt:92-93），因此**仅扫描段留在主线程**。
- **影响**：每次点"检索"主线程阻塞量级与数据量线性相关。项目自己的 G4 门限为 10k p95 ≤ 800ms（BenchmarkScreen.kt:207-208 展示了该门限），即仅扫描段就允许吃掉约 0.8s 主线程预算；叠加首查模型加载时更久。表现为键盘/按钮掉帧、输入延迟，低端机或 10k 以上数据量可能触发 ANR。且该路径不依赖实验屏，是产品主流程。
- **建议**（S）：把扫描段移出主线程，最小改动：
  ```kotlin
  // SearchService.kt
  suspend fun search(query: String, topK: Int = 10): SearchResult = withContext(Dispatchers.Default) {
      // 现有实现整体包一层即可；状态赋值在 UI 侧仍在 Main 完成
  }
  ```
  或在 UI 侧 `scope.launch(Dispatchers.Default) { ... }`，回 Main 再赋值。修复后真机用 `VectorScanBenchmark`/StrictMode 复测主线程占用。
- **工作量**：S

### P1

#### P1-1 无 ViewModel / rememberSaveable：屏幕状态在配置变更与进程重建后全部丢失

- **【级别】P1**
- **位置**：`MainActivity.kt:73-83`；`SearchScreen.kt:52-54`；`SettingsScreen.kt:66-91`；`BenchmarkScreen.kt:77-97`；`ImportScreen.kt:61-64`
- **证据**：全仓 `ViewModel|rememberSaveable|SavedStateHandle` **0 命中**；`screen`/`evidence` 用 `remember { mutableStateOf(...) }`（MainActivity.kt:73-83），7 个屏幕共 64 个 `mutableStateOf`，全部是 `remember`；依赖在 composition 中 `remember { SearchService(context) }`（SearchScreen.kt:51）、`remember { IngestRepository(context) }`（SourcesScreen.kt:48）。
- **影响**：旋转屏幕、分屏、系统深色切换、后台被回收后重建 → 直接回首页（`screen` 复位 home）；检索词/检索结果/滚动位置、导入进行中界面、设置页下载百分比/迁移对话框、Benchmark 摘要全部丢失；进行中的 `rememberCoroutineScope` 任务被取消且无恢复。没有 ViewModel 也意味着业务逻辑与 Compose 生命周期强耦合，无法在 JVM 单测，没有 DI 使替换/测试依赖困难。
- **建议**（止血 S / 规范 M）：
  1. 最小止血：`screen` 改 `rememberSaveable`（String 可存），SearchScreen 的 `query`/`result` 视情况 saveable；
  2. 推荐：引入 `androidx.lifecycle:lifecycle-viewmodel-compose`（1 个依赖即可，不必上 Hilt），每屏一个 `ViewModel` + `StateFlow<UiState>`，仓库通过简单 `AppContainer`/多参数构造注入；已有 `libs.androidx.lifecycle.runtime.ktx`（app/build.gradle.kts:82）可复用生命周期版本；
  3. 导航状态（当前屏、证据栈）放 `SavedStateHandle` 或导航库。
- **工作量**：M（若只做 1+3 的止血约 S）

#### P1-2 autostart 实验随 Activity 重建重放，含破坏性 runner，可造成数据丢失

- **【级别】P1（当前触发场景是无头验收；如发布前保留此机制则升级为 P0）**
- **位置**：`MainActivity.kt:51-70`（每次 `onCreate` 重新读 intent）、`MainActivity.kt:73-82`（screen 状态 remember）；`BenchmarkScreen.kt:77-97`（`started/g1Started/...` 全部 remember）、`BenchmarkScreen.kt:222-228,482-561`（`LaunchedEffect(autoStartXxx)`，key 是常量 Boolean）；破坏性 runner：`BenchmarkScreen.kt:550-561`（`DeleteCascadeRunner` 删首个 source）、`511-518`（`FtsRecoveryRunner` 破坏→重建 FTS）、`531-547`（Prod 基准）、`454-479`（SearchEval/EvalSet）。
- **证据**：`val autoDeleteCheck = intent?.getBooleanExtra(EXTRA_AUTO_DELETE_CHECK, false) == true`（MainActivity.kt:60）在 `onCreate` 中执行，Activity 重建后 intent 不变、值仍为 true；`started` 等标志是 `remember`，重建后复位 false；`LaunchedEffect(autoStartDeleteCheck) { if (autoStartDeleteCheck) { ... DeleteCascadeRunner.run(context) ... } }` 在新 composition 中会再次执行。`DeleteCascadeRunner` 会实际删除一个 source。
- **影响**：adb 无头验收/演示时旋转屏幕、切深色、分屏、多窗口 → 实验重放：delete_check 会真的删掉用户来源（数据丢失），FTS 恢复会重建索引，导入实验会重复登记；实验产物被污染。
- **建议**（M）：
  1. 一次性消费：`if (savedInstanceState == null) { /* 读 extra 并触发 */ }`，或把 `autostartConsumed` 存 `rememberSaveable`/`onSaveInstanceState`；
  2. 实验触发改为 Activity 级一次性动作：`lifecycleScope.launch { ... }` 而非 composition 的 `LaunchedEffect`，或 Application scope 的 runner + 状态查询；
  3. 后续由 debug source set 的专用 Activity 承载实验（见 P1-5）。
- **工作量**：M

#### P1-3 手写字符串导航无返回栈，无 BackHandler：系统返回键直接退出 App

- **【级别】P1**
- **位置**：`MainActivity.kt:88-128`（`when (screen) { "benchmark" -> ... "import" -> ... else -> AppSkeleton }`，所有 `onBack = { screen = "home" }`）；`MainActivity.kt:84-87`（证据屏通过提前 return 替换整棵树）；各屏自建返回按钮：`SearchScreen.kt:56-66`、`SourcesScreen.kt:73-83`、`SettingsScreen.kt:266-276`、`ImportScreen.kt:140-150`、`BenchmarkScreen.kt:563-573`、`EvidenceScreen.kt:36-46`。
- **证据**：全仓 grep `BackHandler|NavHost|navigation-compose` **0 命中**；按 Android 默认行为，未拦截的返回键 = `finish()` Activity → 从检索/导入/来源/设置/证据/实验任一子屏按返回键都会退出整个应用（而不是回首页）；实验运行中自定义返回按钮 `enabled = !running`（BenchmarkScreen.kt:568、SearchScreen.kt:61）被禁，但系统返回键不受控，可直接杀掉进行中的实验。
- **影响**：明显违背 Android 用户预期；实验中返回会取消/中断任务且无确认；无返回栈、无转场、无法支持预测性返回/深链。
- **建议**（S/M）：
  ```kotlin
  BackHandler(enabled = evidence != null || screen != "home") {
      if (evidence != null) evidence = null else screen = "home"
  }
  ```
  运行中再包一层 `BackHandler { showCancelConfirm = true }`。中期引入 `navigation-compose`（或自写 `sealed class Screen` 栈）替代 String 状态机。
- **工作量**：S（BackHandler 止血）/ M（导航库）

#### P1-4 打开证据详情导致检索结果丢失（状态未提升）

- **【级别】P1**
- **位置**：`MainActivity.kt:83-87`（`evidence?.let { EvidenceScreen(hit = it, onBack = { evidence = null }); return@ZhiBoxTheme }`）；`SearchScreen.kt:52-54`（`query`/`result`/`running` 均为 `remember`）；`MainActivity.kt:115-118`。
- **证据**：`evidence != null` 时整个 `when(screen)` 被跳过 → SearchScreen 离开 composition → 其 `remember` 状态销毁；`onBack` 只把 `evidence` 置 null，`screen` 仍是 "search"，SearchScreen 重新创建，query/result 均为初值。
- **影响**：用户检索 → 点开任一条证据 → 返回 → 回到空搜索框（实际停留在检索屏但结果全没），需重新输入检索；高频主路径体验断裂。
- **建议**（M）：把检索状态提升到 MainActivity/ViewModel（screen 切换不销毁），或采用导航库 + 每屏 ViewModel；最低成本：`evidence` 详情作为 ModalBottomSheet/Dialog 叠加，或把 query/result 用 `rememberSaveable`/moveTo StateHolder 让返回时恢复。
- **工作量**：M

#### P1-5 BenchmarkScreen 参数爆炸 + 实验能力无门禁进入产品 UI；release 可被外部 Intent 触发实验（含破坏性）

- **【级别】P1（如发布 release 则为 P0，数据丢失链成立）**
- **位置**：`BenchmarkScreen.kt:53-73`（19 参数，18 个 Boolean autostart）；`MainActivity.kt:51-70`（逐个读取 20 个 extra）、`134-153`（20 个常量）、`76-80`（`when` 里 17 个布尔 OR 压成 "benchmark"）、`205`（首页无条件"实验/诊断"按钮）；`AndroidManifest.xml:26-34`（Activity `exported="true"`，无 debug 变体）；`BuildConfig` 全仓 0 命中。
- **证据**：
  - 新增一个实验需要改 4 处（常量、读取、传参、`BenchmarkScreen` 参数），当前已有 20 个 extra ↔ 18 个 Boolean 的不对称映射，`MainActivity.kt:76` 的 17 项 OR 链可读性极差；
  - release 包中首页按钮可见（MainActivity.kt:205），实验屏无任何 `BuildConfig.DEBUG` 判断；
  - 任意已安装应用可 `am start -n com.zhibox.memory/.MainActivity --ez autostart_delete_check true`（或 autostart_fts_recovery 等），直接触发删来源/FTS 重建/模型下载/写 filesDir 等操作（对应 MainActivity.kt:143-151、BenchmarkScreen.kt:511-561）。
- **影响**：可维护性差、易漏配；交付给体验者的 release APK 里带着一个"删数据/跑基准"的后门；实验代码、adb 验收产物（写盘 JSON）常驻产品包，体积与风险都不必要。
- **建议**（M）：
  1. 单一入口：`EXTRA_RUN_MODE: String`（或 `enum DebugRun`）替代 20 个 boolean，`BenchmarkScreen(runMode: DebugRun?, onBack: ...)`；runner 用注册表 `Map<DebugRun, suspend (Context) -> Unit>` 分发；
  2. 实验屏与 runner 移到 `app/src/debug/java`（或 `android:enabled="${debug}"` 的独立 Activity/别名），release 首页不渲染实验入口；
  3. 若必须保留入口，加 `if (BuildConfig.DEBUG)` 门禁并清空 intent 消费后状态。
- **工作量**：M

#### P1-6 `runProbe` 不复位 `running`：点一次"连通矩阵"后整屏永久禁用

- **【级别】P1（实验屏确定性功能 bug）**
- **位置**：`BenchmarkScreen.kt:520-529`；受影响 UI：`BenchmarkScreen.kt:587-621`（所有按钮 `enabled = !running`）、`568`（返回按钮 `enabled = !running`）、`622`（进度条）。
- **证据**：`fun runProbe() { if (running) return; running = true; scope.launch { status = ...; runCatching { ... }.onSuccess { status = ... }.onFailure { status = ... } } }` —— 成功/失败分支都没有 `running = false`（对比 `runScan` 有 L218、`runG3` 有 L248、`runG1E` 有 L141 复位）。
- **影响**：手动点一次"连通矩阵"后，本屏 10 个实验按钮 + 返回按钮全部永久 disabled，进度条常亮，只能按系统返回键退出再进（状态随之丢失）。
- **建议**（S）：统一用 `try/finally` 复位，或把 `running: Boolean` 收敛为 `activeRun: String?`，用 helper：
  ```kotlin
  fun launchExperiment(block: suspend () -> Unit) {
      if (running) return
      running = true
      scope.launch { try { block() } finally { running = false } }
  }
  ```
- **工作量**：S

#### P1-7 长期任务绑定 rememberCoroutineScope：离屏即取消、异常无边界、失败状态不复位

- **【级别】P1**
- **位置**：
  - `SearchScreen.kt:87-98`：`runCatching { service.search(query) }` 会把 `CancellationException` 一并吞掉并生成"检索失败：..."的失败结果（协程已取消仍继续执行 `running = false`）；
  - `ImportScreen.kt:96-104`：`importAll()` 的 `scope.launch { IngestRepository(context).importStaged(); importing = false; refreshStaging() }` 无 `try/finally`，异常时 `importing` 永久 true 且未捕获异常会向上抛到主线程（App 崩溃）；
  - `SettingsScreen.kt:257-259`：`scope.launch { target.delete(); refresh() }` 在主线程做文件 IO、无 runCatching；
  - `SettingsScreen.kt:564-570`：`rebuildFtsIndex()` 无异常保护；
  - `SettingsScreen.kt:266-275`：模型下载（L372-394 `downloadEntry`）进行中返回按钮仍可点，离开屏幕 → scope 取消 → 下载协程取消、下载进度状态丢失、无任何提示（`SharedModelStore.downloadEntry` 支持续传但 UI 不告知）；
  - `SettingsScreen.kt:238-244`（clearAll）、`302-310`（PathPreferences.set）、`552-554`（ReembedScheduler.enqueue）等亦无错误边界。
- **影响**：UI 偶发崩溃（导入/删模型/重建索引失败路径）；导入按钮卡在"导入中…"；下载/检索静默中断且用户不知道；取消被当成业务失败展示，误导排查。
- **建议**（M）：引入统一 `safeLaunch`/`ViewModelScope` + 每屏 `UiState`（含 `loading/error`）；长任务（下载、重嵌入、导入前置处理）改用 WorkManager/Application scope 并以 Flow 暴露进度；`catch (e: CancellationException) { throw e }` 后再 `runCatching`；`importing` 用 `try/finally` 复位；下载中禁用返回或确认取消。
- **工作量**：M

#### P1-8 导入编排在主线程做文件 IO（listFiles/rename/copy/readBytes/sha256）

- **【级别】P1（主线程磁盘 IO，卡顿/ANR 风险 → 待真机验证量级）**
- **位置**：`ImportScreen.kt:96-104`（`scope.launch { IngestRepository(context).importStaged() ... }`，主线程）；`app/src/main/java/com/zhibox/memory/core/ingest/IngestRepository.kt:44-83`（文件内 `withContext|Dispatchers` grep 为 0 命中）：
  - L56-63 `staging.listFiles { ... }`；
  - L66-75 `renameTo`/`copyTo`/`delete`；
  - L77 `stored.readBytes()`；
  - L78 `sha256Hex(bytes)`（整文件哈希）。
- **影响**：暂存目录文件多/大时，点"全部导入"会在主线程同步扫描、移动、读文件、算哈希，随后才交给 WorkManager；与 `MainActivity.kt:40-50` 自己加的 debug StrictMode ANR 防线相矛盾（一旦按 StrictMode 日志验收就会命中 diskWrite/diskRead）。
- **建议**（S）：`importStaged()` 首行 `withContext(Dispatchers.IO)`（注意其内部使用 Room suspend 与 WorkManager，均线程安全）；`refreshStaging()`（ImportScreen.kt:66-70）也同样入 IO；UI 协程回 Main 再改状态。
- **工作量**：S

### P2

#### P2-1 249 处硬编码中文字符串，资源文件仅 1 条；内部 Phase/文档路径泄漏到用户文案

- **【级别】P2**
- **位置**：`app/src/main/res/values/strings.xml:3-4`（全文件仅 `app_name`）；实测每文件硬编码中文：BenchmarkScreen 107、SettingsScreen 91、SourcesScreen 13、SearchScreen 11、ImportScreen 11、MainActivity 9、EvidenceScreen 7；典型泄漏：`MainActivity.kt:180`"Phase 1 · 离线个人知识搜索 MVP"、`MainActivity.kt:194`"依据：docs/SAG_Android_MVP_Implementation_Plan_v1.0.md P1-W2"、`ImportScreen.kt:143`"导入管线 · P1-W1"、`BenchmarkScreen.kt:566`"Phase 0 · Embedder 基准"。
- **影响**：无法本地化、文案无法集中修改；给体验者/评审者看的 App 里出现内部文档路径和阶段编号，产品感差。
- **建议**（M）：至少把用户可见文案（TopAppBar 标题、按钮、空态、错误）迁到 strings.xml 并用 `stringResource`；开发内部引用只在 debug 构建展示或移到注释；可先只治理 5 个产品屏，BenchmarkScreen 随 P1-5 整体剥离。
- **工作量**：M

#### P2-2 六屏重复 Scaffold + TopAppBar + 返回按钮

- **【级别】P2**
- **位置**：`SearchScreen.kt:56-66`、`SourcesScreen.kt:73-83`、`SettingsScreen.kt:266-276`、`ImportScreen.kt:140-150`、`BenchmarkScreen.kt:563-573`、`EvidenceScreen.kt:36-46`；每处 11 行（`Scaffold` + `TopAppBar(title)` + `IconButton(ArrowBack, contentDescription="返回")`），合计约 66 行近似复制。
- **影响**：改返回/标题/Insets 行为要改 6 处；`contentDescription` 文案分散；后续加统一标题栏样式成本高。
- **建议**（S）：抽 `ZhiBoxTopBar(title: String, onBack: (() -> Unit)?, backEnabled: Boolean = true)` 及 `ZhiBoxScreen(title, ...) { padding -> ... }`；顺带把"返回"等文案进资源。
- **工作量**：S

#### P2-3 超大 Composable / 状态爆炸：BenchmarkScreen 743 行 1 个 Composable（22 状态）、SettingsScreen 606 行 1 个（27 状态）

- **【级别】P2**
- **位置**：`BenchmarkScreen.kt:53-729`；`SettingsScreen.kt:64-600`。
- **证据**：两文件各只有 1 个屏幕级 Composable；BenchmarkScreen 22 个 `mutableStateOf`（L77-97 连续 14 个布尔 + 8 个摘要 list，L491 还夹着一个 `probeStarted` 声明在函数中间）；SettingsScreen 27 个（L66-91），卡片区 L286-597 是 9 段几乎同构的 `Card { Column { Text(title) ... } }`；BenchmarkScreen L625-695 的 7 段摘要卡完全同构（只差标题和 list 变量）。
- **影响**：任何一处状态变化都可能触发大范围重组；无法单独预览/测试某个实验；阅读与改动成本高，review 只能整体看。
- **建议**（M）：抽 `ExperimentCard(title: String, lines: List<String>)`（立即消掉 ~70 行复制）、`SectionCard(title, content)`；状态用 `data class BenchmarkUiState(...)`/sealed `RunResult` 收敛；按实验拆 `@Composable` 子函数；中期配合 ViewModel。
- **工作量**：M

#### P2-4 主线程小颗粒 IO 与 composition 内副作用

- **【级别】P2**
- **位置**：
  - `SettingsScreen.kt:491`：`"$label · ${file.length() / 1024 / 1024}MB"` —— 每次重组对每个模型文件做一次 `stat`（磁盘 IO）；
  - `BenchmarkScreen.kt:76`：`val models = remember { discoverModels(context) }` —— 在 composition 体内执行 `isDirectory`/`listFiles`（BenchmarkScreen.kt:731-743），既是副作用位置错误，也导致模型文件后续变更（adb push 新模型）后列表不刷新；
  - `ImportScreen.kt:66-70`：`refreshStaging()` 的 `listFiles` 在 `LaunchedEffect(Unit)`（L106-107）与按钮回调（L102/L122）主线程执行；
  - `SettingsScreen.kt:257-259`：`target.delete()` 主线程。
- **影响**：列表页每次重组都读盘；`remember` 缓存让诊断信息过期，需退出重进才能看到新模型；StrictMode 下产生噪音日志。
- **建议**（S）：文件大小在 `refresh()` 的 `withContext(IO)` 中一次算好（`List<ModelEntry(file, label, sizeBytes)>`）；`discoverModels` 放 `LaunchedEffect(Unit) { withContext(IO) { models = discoverModels(context) } }`（或 `produceState`）并加下拉/按钮刷新；`refreshStaging`/`delete` 切 IO。
- **工作量**：S

#### P2-5 BenchmarkScreen 布局：不可滚动 Column 内嵌 LazyColumn，小屏报告列表会被压成 0 高

- **【级别】P2**
- **位置**：`BenchmarkScreen.kt:575-728`（外层 `Column(Modifier.fillMaxSize())`，无 `verticalScroll`）；`BenchmarkScreen.kt:587-621`（10 个按钮 FlowRow，通常 2-3 行）、`625-695`（最多 8 张摘要卡）、`697-708`（空模型卡）、`710-726`（LazyColumn 报告列表，在最后）。
- **证据**：非滚动 `Column` 中无 weight 的子项按剩余高度分配；前面固定内容超过屏幕后，末尾 LazyColumn 高度趋于 0，整屏又无法滚动。
- **影响**：连续跑多个实验后摘要卡变多，底部 `reports` 列表（维度/延迟/RSS 结果）不可见，用户误以为无输出；小屏设备更早触发。
- **建议**（S）：整屏改单个 `LazyColumn`：`item { header/buttons/status }` + 摘要卡 `item` + `items(reports)`；或摘要收进可展开 Card。`SearchScreen.kt:130`、`ImportScreen.kt:180` 的 LazyColumn 也建议加 `Modifier.weight(1f)` 明确占满剩余高度。
- **工作量**：S

#### P2-6 BenchmarkScreen `.onSuccess` 内解析 JSON 未受保护，schema 变化即崩溃

- **【级别】P2**
- **位置**：`BenchmarkScreen.kt:120-136`、`178-188`、`203-211`、`236-245`、`273-284`、`311-329`、`374-384`、`418-443`、`459-477` 等；`runCatching { runner.run(...) }.onSuccess { JSONObject(out.readText()) ... }`。
- **证据**：`runCatching` 只包住 runner 调用；`out.readText()`、`getJSONObject/getDouble/getInt/getJSONArray` 全在 `.onSuccess` lambda 内，异常不会被外层捕获。
- **影响**：任一 runner 输出字段改名/缺失（调试期高频）→ 实验屏直接崩溃（而不是显示"解析失败"）；崩溃发生在实验屏，不需要产品代码变更。
- **建议**（S）：`runCatching { runner.run(...) }.mapCatching { out -> parseXxx(out) }` 并把解析函数拆出；统一在 `.onFailure { status = "解析/运行失败：..." }` 收口。
- **工作量**：S

#### P2-7 空态/加载/错误反馈不均衡，缺少统一反馈通道

- **【级别】P2**
- **位置**：
  - `SourcesScreen.kt:92-123`：无来源时只有一行"0 个来源 · 0/0 文档就绪"，无空态引导（"去导入"）、无加载态、无错误态；
  - `SettingsScreen.kt:151-176`：`refresh()` 无 try/catch、无 loading 指示（L562 "检查中…" 是唯一占位，失败会静默停在检查中或崩溃，见 P1-7）；
  - 错误/成功提示分散：`storeMessage`/`migrationMessage`/`diagMessage`/`lastReembed` 各自渲染，互相覆盖（SettingsScreen.kt:80,88,131,445-448,528-531,574-575），无 Snackbar/统一优先级。
- **影响**：首装用户进入"来源"不知道该做什么；后台失败时页面空白无解释；多条操作结果互相覆盖，用户错过关键错误。
- **建议**（M）：每屏 `UiState { Loading / Empty / Error(message) / Content }`；`Scaffold(snackbarHost = ...)` 统一反馈；SourcesScreen 空态加"前往导入"按钮（回调可从 MainActivity 传入）。
- **工作量**：M

#### P2-8 深色主题不完整 + XML 主题固定 Light

- **【级别】P2**
- **位置**：`ui/theme/Theme.kt:22-29`（darkColorScheme 只设了 `primary = PrimaryContainer`、`secondary = SecondaryContainer`、`surface`、`onSurface`；`onPrimary`/`onSecondary`/`background`/`onBackground`/`surfaceVariant`/`outline`/`error` 全部落回 M3 基线紫）；`Theme.kt:10-20`（light 同样缺 `background`/`onSurface`/`outline`/`error`）；`app/src/main/res/values/themes.xml:4`（`android:Theme.Material.Light.NoActionBar` 固定 Light parent，且无 `values-night/themes.xml`）。
- **影响**：深色模式下 TopAppBar/Scaffold/默认组件使用基线的紫色系 role，与自定义绿色系不协调；冷启动时 window 背景仍是浅色（深色模式闪白）；Material You 未启用（Theme.kt:9 注释说明是 minSdk 24 取舍，可接受，但未覆盖 role 不是）。
- **建议**（S）：补齐 `background/onBackground/surfaceVariant/onSurfaceVariant/outline/error/onError` 等常用 role，或直接用 Material Theme Builder 输出整套；加 `values-night/themes.xml`（parent `android:Theme.Material.NoActionBar`）消除启动闪白。
- **工作量**：S

#### P2-9 Manifest 的 allowBackup=true 与设置页隐私声明冲突；无自适应图标

- **【级别】P2**
- **位置**：`app/src/main/AndroidManifest.xml:12`（`android:allowBackup="true"`）vs `ui/SettingsScreen.kt:590-594`（"知识数据（文档/向量/索引）仅存于本机应用沙箱，不上传不备份"）；`AndroidManifest.xml:13`（`android:icon="@drawable/ic_launcher"`，无 `mipmap`/adaptive icon/`roundIcon`）。
- **影响**：Auto Backup 会把数据库与 SharedPreferences 备份到云端/迁移到新设备，与"端侧、不上传不备份"的定位和界面文案矛盾（若刻意要备份则文案要改）；`drawable` 图作为 launcher icon 在 Android 8+ 会被系统裁剪/缩放，显示效果差。
- **建议**（S）：`allowBackup="false"`（或用 `dataExtractionRules` 明确排除知识库）并同步文案；补 `mipmap-anydpi-v26/ic_launcher.xml` 自适应图标与 `roundIcon`。
- **工作量**：S

#### P2-10 死状态 + 隐藏验收产物常驻产品 UI

- **【级别】P2**
- **位置**：`ImportScreen.kt:63`（`var stats by remember { mutableStateOf("") }`）与 `ImportScreen.kt:176`（`if (stats.isNotEmpty()) Text(stats, ...)`）—— 全文件再无对 `stats` 的赋值，`stats` 永远为空、该行永不显示；`ImportScreen.kt:72-94` + `106-116`：产品导入界面在每次任务状态变化后（300ms 去抖）把 `filesDir/import_status.json` 写到磁盘，这是 adb 验收专用副作用；`ImportScreen.kt:134` `val n = ... cancelImport()` 未使用。
- **影响**：死代码/未用变量误导维护者；产品运行产生无意义写盘；验收/调试机制与产品路径未分离（同类问题在 BenchmarkScreen 更严重）。
- **建议**（S）：删除 `stats` 或把它接到用户可见的导入摘要；`import_status.json` 导出改到 `BuildConfig.DEBUG`/src/debug；清理未用变量。
- **工作量**：S

#### P2-11 预览与测试覆盖不足；Compose 参数稳定性待验证

- **【级别】P2**
- **位置**：全仓 `@Preview` 仅 `MainActivity.kt:213-219`（AppSkeleton）；其余 7 个屏幕级 Composable 无预览；`app/src/androidTest` 仅 `MigrationTest.kt`（0 个 Compose UI 测试）；`SearchService.kt:34-57` 的 `SearchHit`/`SearchResult` 含 `List` 字段且未标 `@Immutable`（Compose 编译器会视 `List` 为不稳定类型；`SearchResult` 仅作为 `SearchScreen` 的 `remember` 局部状态，不跨 Composable 传递，故当前影响有限，重组行为 **待验证**：未开启 Compose compiler metrics）。
- **影响**：UI 改动只能真机手测，无回归网；深浅色/大字号/窄屏等状态无人验证；未来若把 `SearchResult` 传入子组件会触发无谓重组。
- **建议**（M）：每屏至少 1 个 `@Preview(showBackground = true)` + 1 个 dark/fontScale 预览；引入 `androidx.compose.ui:ui-test-junit4` 加 SearchScreen/ImportScreen 冒烟测试；可选开启 Compose compiler reports 检查 skippability，必要时用 `kotlinx.collections.immutable` 或 `@Immutable` 包装。
- **工作量**：M

#### P2-12 SourcesScreen 每次重组重建 Room Flow 实例并重启订阅

- **【级别】P2**
- **位置**：`SourcesScreen.kt:51-52`：`val sources by db.sourceDao().observeAll().collectAsState(initial = emptyList())`、`documents` 同理。
- **证据**：`observeAll()` 在 composition 体内调用，每次重组返回新的 `Flow` 对象；`collectAsState` 以该 Flow 实例作为 `produceState` key，每次重组都会取消旧收集、重新订阅并重查 Room。
- **影响**：无谓的 DB 查询与订阅抖动（内容相同时不会无限重组，但每次重组重启收集）；数据量大/频繁重组时列表页卡顿。
- **建议**（S）：`val sourcesFlow = remember { db.sourceDao().observeAll() }` 后再 `collectAsState`；或由 ViewModel 暴露 `StateFlow`。
- **工作量**：S

#### P2-13 用文案字符串控制流程 + locale 敏感格式化

- **【级别】P2**
- **位置**：`BenchmarkScreen.kt:217`：`if (status != "G4 扫描基准失败") status = "G4 扫描基准完成..."` —— 用展示文案做状态判断，文案一改逻辑就错；`SearchScreen.kt:149` `"%.4f".format(hit.fusedScore)`、`BenchmarkScreen.kt:719-720` `"%.1f"/"%.2e".format(...)` 未指定 Locale（部分语区小数分隔符不同；JVM 默认 Locale 首次使用会触发 ICU/资源初始化，在滚动列表中格式化开销不可忽略）。
- **影响**：隐藏的逻辑脆弱点；显示可能不符合语区习惯或出现意外格式。
- **建议**（S）：用局部布尔/枚举替代文案比较；`String.format(Locale.ROOT, ...)` 或 `DecimalFormat`；列表项里可 `remember(hit.chunkId)` 缓存格式化结果。
- **工作量**：S

#### P2-14 autostart 编排依赖 300ms delay 与两个 LaunchedEffect 串联，时序脆弱

- **【级别】P2（待验证：慢设备/冷启动下是否稳定触发）**
- **位置**：`ImportScreen.kt:118-124`（`LaunchedEffect(autoStart) { if (autoStart && stagingCount == -1) { delay(300); refreshStaging() } }`）与 `ImportScreen.kt:126-128`（`LaunchedEffect(autoStart, stagingCount) { if (autoStart && stagingCount > 0 && jobs.isEmpty()) importAll() }`）、`ImportScreen.kt:106-116`（先 `refreshStaging()` 再 `collectLatest` 写 jobs）。
- **证据**：自动导入的触发依赖三个条件交叉（stagingCount 状态、Room flow 首帧是否已发射、delay 魔法数），且 `L98` 的 `LaunchedEffect(Unit)` 里已同步 `refreshStaging()`，L118 的 delay 分支基本冗余。
- **影响**：无头验收可能偶发不触发导入；时序相关 bug 难以复现与测试。
- **建议**（S）：合并为单个 effect，顺序 await：`LaunchedEffect(Unit) { refreshStaging(); if (autoStart && stagingCount > 0) importAll(); observeJobs() }`；`importStaged()` 内部已有 `activeCount()` 幂等保护，可去掉 jobs.isEmpty 判断；删除 delay。
- **工作量**：S

### P3（顺手项，不单列严重级）

- `ImportScreen.kt:134` 未使用变量 `val n =`；`BenchmarkScreen.kt:78-97` 状态声明被 `status` 插队、`probeStarted` 声明游离在 L491；`LaunchedEffect` 与函数定义交错（BenchmarkScreen.kt:222-254、294-296 等），建议统一顺序：状态 → 函数 → effect → UI。
- SettingsScreen/BenchmarkScreen 在 Composable 内大量使用全限定名（`com.zhibox.memory.core.model.ModelCatalog.PERF_BALANCED` 等约 30 处），可提取 import 提升可读性。
- 首页 `AppSkeleton` 定义在 `MainActivity.kt:159-211` 而非 `ui/` 包（其余屏幕都在 ui 包），包结构不一致，且首页含业务文案（L188-196）。
- `SearchScreen.kt:144` 在 Text 参数里 `hit.content.take(120) + if (...) "…"`，每次重组新建字符串；量级小，可忽略。

---

## 做得好的地方

- **破坏性操作均有明确二次确认与后果说明**：删除来源（`SourcesScreen.kt:55-71`，文案明确"及其全部文档、分块、索引与向量（不可恢复）"）、清空共享目录（`SettingsScreen.kt:232-249`）、删除模型（`SettingsScreen.kt:251-264`）、导入覆盖警告（`SettingsScreen.kt:206-208` 红色提示"导入将替换当前全部知识库数据"）。数据安全意识在产品 UI 层是到位的。
- **运行中抑制误操作**：检索中禁用返回与提交（`SearchScreen.kt:61,100`）、实验运行中禁用返回（`BenchmarkScreen.kt:568`）、导入中禁用按钮并切换文案（`ImportScreen.kt:173-175`）。
- **拒绝态/空态给的是"下一步动作"而不只是错误**：检索拒绝卡片直接指向"设置 → 模型商店"（`SearchScreen.kt:108-119`），空命中提示换表述/多导入（`SearchScreen.kt:127-129`），无模型时给出 adb push 路径（`BenchmarkScreen.kt:697-708`），暂存目录为空/扫描中有明确文案（`ImportScreen.kt:161-166`）。
- **错误信息用户可读**：普遍用 `it.message?.lineSequence()?.first()` 提取首行并加业务前缀（`SearchScreen.kt:94`、`SourcesScreen.kt:65`、`SettingsScreen.kt:101,388`），不把堆栈直接抛给用户。
- **数据流响应式**：来源列表与导入任务用 Room `Flow` + `collectAsState`/`collectLatest`（`SourcesScreen.kt:51-52`、`ImportScreen.kt:108-115`），进度条按 `job.progress` 实时更新（`ImportScreen.kt:189-194`），无需手动轮询/刷新。
- **主线程 ANR 回归防线**：debug 构建开启 StrictMode 检测主线程磁盘/网络并打日志（`MainActivity.kt:40-50`），方向正确——本次审查发现的 P0-1/P1-8 正好是这条防线应捕获的违规点，验证机制本身值得保留。
- **主题集中、支持系统深浅色**：色板集中在 `ui/theme/Color.kt:5-14`、`Theme.kt:31-40`，`isSystemInDarkTheme` 自动切换；首页有 Preview（`MainActivity.kt:213-219`）。
- **无障碍基础在线**：所有图标按钮都有中文 `contentDescription`（8 处，如 `EvidenceScreen.kt:42`、`SourcesScreen.kt:79,106`），删除类图标用 `error` 色；按钮均带可见文字而非纯图标。
- **后台重任务用 WorkManager + 进度观察**：导入批处理、重嵌入均由 WorkManager 承载，设置页用 `getWorkInfosForUniqueWorkFlow` 观察进度（`SettingsScreen.kt:117-129`），架构方向正确（UI 侧启动方式待 P1-7 收敛）。

---

### 备注

- P0/P1 的严重度按"当前工作区代码 + Phase 1 收尾阶段"评估；P1-2/P1-5 若在 release 交付路径上，应按 P0 处理（外部 Intent 可触发数据删除）。
- 标注"待验证"的项需要真机/编译环境验证：P0-1 的实际 ANR 概率（10k chunks 真机复测）、P2-11 的重组跳过行为（需 Compose compiler metrics）、P2-14 的冷启动时序、P1-8 的文件数量级影响。
- 本次未运行 gradle（任务约束），所有结论来自逐文件通读 + grep 实测 + 调用链追踪（SearchService / IngestRepository / EmbeddingRuntime / SharedModelStore 的线程与异常行为）。
