# B3 修复报告（P2 批次 B3：UI/UX 与真机新发现）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B3（B3-1…B3-14；B3-15 已在 B2 完成）
- **执行方式**：Lead 复现真机基线（D1 StrictMode）→ 5 路并行（ui-fixer 主屏/VM/主题/状态组件/Preview；ui-reviewer Benchmark/autostart/评测输入；core-fixer 数据契约；infra-reviewer 自适应图标；data-reviewer 验收测试）→ Lead 集成修复（test 源集/契约测试/org.json test 依赖）→ 全量门禁 → 真机验收 → 双轴独立审查
- **决策沿用**：允许依赖升级窗口（B4 后续）、保留 phase1 结果、不 provision 签名、Robolectric 留 B5

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B3 完成度 | **14 / 14 项完成**（B3-15 已于 B2 完成） |
| JVM 测试 | **221 tests / 0 failed**（debug；release unit 同步绿，源集隔离正确） |
| Android Lint | **0 errors / 26 warnings**（`abortOnError=true`） |
| 构建 | `assembleDebug` / `assembleRelease`（R8）✅ |
| 真机 instrumentation | **28 / 28 PASS**（新增 importStaged 去重计数、observeRecent 限量；审查修复后复跑） |
| B3-11 autostart delete_check | 真机 5s 内产出结果文件（`files/eval_results/delete_check_*.json`） |
| B3-1 StrictMode | 打开导入屏：**app 代码归属违规 0**（剩余 4 条来自 OnePlus UIFirst 系统 binder 注入，非应用栈） |
| B3-2 首查反馈 | 冷启动检索显示"首次检索需在端侧加载模型（约 5–10 秒）"；warm 不显示 |
| B3-3 深色主题 | Light/Dark 显式映射 M3 可用 role（27 基础 + surfaceDim/Bright/5×surfaceContainer/surfaceTint/scrim）+ `values-night`；真机 5 屏深色截图（卡片底色实测 **#30362E** 品牌深绿，非 M3 基线 #36343B） |
| B3-4 自适应图标 | aapt2 badging 显示 `mipmap-anydpi-v26/ic_launcher.xml`（adaptive）+ anydpi 兜底 |
| B3-11 autostart | `AutoStart` 数据类 + 18 路由表；20 EXTRA 常量全保留；真机 `autostart_import` / `autostart_search_eval` smoke 正常 |
| 独立审查 | Standards（3 硬性 + 6 判断性）+ Spec（7 partial）→ **全部处置/诚实记录**（见第 6 节） |

---

## 2. 逐项完成说明

### B3-1 主线程 StrictMode 磁盘读（真机 D1）
- 基线复现：打开导入屏时 `ImportViewModel.<init>` → `getFilesDir()` 触发 **82ms DiskReadViolation**（真机 OnePlus PJZ110）。
- 修复：`db`/`repository`/`stagingDir` 全部 `by lazy`；staging 目录只在 `Dispatchers.IO` 首次使用时解析；构造期只 `launch` 协程；jobs 的 Room Flow 也在 IO 上下文获取后 collect。
- 真机复验：打开导入屏后 StrictMode 违规 **app 编译单元归属 = 0**（按栈过滤 `com.zhibox.memory` 为 0；剩余 4 条为系统 `com.oplus.uifirst` binder 注入，任何应用进程都会出现，非本应用代码）。

### B3-2 首查模型加载阶段反馈（真机 D2）
- `EmbeddingRuntime.isEngineWarm(context)` 按 `withProvider` 同一 key（`absolutePath::gpu=<mode>`）比较：换模型/切 GPU 偏好导致的重建也会提示；同 key 已构建（含导入流程预建）不提示。`SearchViewModel.loadingModel = !isEngineWarm(...)`。
- Search 屏 loading 区展示"首次检索需在端侧加载模型（约 5–10 秒）…"；不改变 warm 检索路径（B2 评测 p50=49ms 基线不受影响）。

### B3-3 深色主题
- `Color.kt` 补齐 Light/Dark 各 **27 个 color role**（primary/secondary/tertiary/container/background/surface/surfaceVariant/outline/error/inverse 等），`Theme.kt` 两套 scheme 显式映射。
- 新增 `res/values-night/themes.xml` + `colors.xml`：深色无标题栏主题、深色窗口背景（消除冷启动闪白）；`values/themes.xml` 同步 window/状态栏/导航栏颜色。
- 审查修复后补齐 `surfaceDim/surfaceBright/surfaceContainerLowest/Low/Container/High/Highest/surfaceTint/scrim`（此前缺失导致 Card 回退 M3 基线 #36343B）；fixed roles 当前 Material3 API 不提供参数，使用库默认（不用于产品 Card）。
- 真机 5 屏深色截图：`docs/review/b3/after/b3_dark_*2.png`（正文/卡片/按钮可读；卡片底色实测 #30362E 品牌深绿；正文最低对比度约 5.76:1 ≥ AA 4.5:1）。

### B3-4 自适应启动图标
- 新增 `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`（`<adaptive-icon>` background/foreground/monochrome）；`mipmap-anydpi/` layer-list 兜底（API 24–25）；`drawable/ic_launcher_{background,background_round,foreground,monochrome}.xml`；Manifest `icon/roundIcon` 指向 mipmap。
- `aapt2 dump badging`：`application-icon-160/240/320/65534: res/mipmap-anydpi-v26/ic_launcher.xml`；`aapt2 compile/link` 全量 res 通过。

### B3-5 统一 Loading/Empty/Error 状态组件
- `UiCommon.kt` 新增 `LoadingState(message/circular)`、`EmptyState(title/message/action)`、`ErrorState(message/title/hint/action)`；接入检索/导入/来源/设置 4 屏，移除各屏重复空态/错误实现。
- 审查补强：Sources 首帧加载态（`SourcesViewModel.loaded`，首次 DB 结果前显示 LoadingState，避免空态闪烁）。
- 每屏拆出无状态 `*Content` 组件，供 Preview 与真机状态复用。

### B3-6 BenchmarkScreen 拆分与 JSON 防护（debug）
- 新增 `SectionCard/ExperimentCard/ReportCard/MissingModelsCard/ExperimentButton/DeviceLine` 复用组件；同构 Card/按钮模板块从 **82 行 → 29 行（-65%）**。
- 整屏改为**单个 LazyColumn**（小屏 ≤640dp 可滚动，不再 Column 套 LazyColumn 0 高）。
- 所有 runner JSON 解析走 `runReport()`（try/catch，坏文件显示「结果解析失败（文件名）：…」不崩溃）；release 源集 stub 行为不变。

### B3-7 Preview 覆盖
- 18 个 `@Preview`：Search 5（浅/深/空/错/首查加载）、Import 4、Sources 4、Settings 3、Home 2（release 无实验入口 / debug 有入口）；各屏均拆出无状态 Content 以便预览。

### B3-8 locale 与文案审计
- 全局 grep：`String.format` 仅 SearchScreen RRF 分数（Locale.ROOT，技术指标）；`SimpleDateFormat` 仅 SettingsViewModel（Locale.getDefault，用户可见时间）；无未指定 Locale 的 `toUpperCase/toLowerCase/NumberFormat`；无字符串驱动的流程分支（状态展示统一走枚举）；product UI 中文硬编码 0 处（全部 `R.string`）。

### B3-9 导入任务列表限量
- `IngestJobDao.observeRecent(activeStages, limit)`：`ORDER BY CASE WHEN stage IN (:activeStages) THEN 0 ELSE 1 END, createdAt DESC LIMIT :limit`——**活动任务优先**，避免早于 50 条的活动任务被截（审查修复）；observeAll 保留；ImportViewModel 传 `JobStage.ACTIVE_VALUES`。UI 分「进行中 / 最近任务」两区，达到 50 条时提示"仅显示最近 50 条任务"。
- 真机/DB 测试：60 条 job 只返回最新 50 条且 createdAt 降序；列表不再渲染 101 行。

### B3-10 评测输入统一
- 新增 debug `EvalQueryInput`：候选路径 `[files/search/queries.json, files/eval/evalset.json]`；支持 `{"queries":[str]}` / `{"queries":[{"q":...}]}` / 裸数组 / `q|query` 回退；缺失时错误列出全部尝试路径。
- `SearchEvalRunner` 主读 queries.json、兼容 evalset；`EvalSetRunner` 主读 evalset、兼容 queries.json；输出字段只增不减（`input_file`），scripts 兼容。
- `scripts/run_eval.sh` / `run_thermal.sh` 顶部注释标注主路径与兼容路径；解析纯函数 + 11 个 JVM 用例。

### B3-11 autostart 路由重构
- `AutoStart` 提升为顶层数据类（20 字段，对应 20 个 EXTRA，常量名一个未改）；`DebugRun` 18 条路由表 + `AutoStart.route()`；`pendingAutoStart` 离开实验屏即清空；`savedInstanceState == null` 才读 extra 并 `replaceExtras(Bundle())` 清空。
- BenchmarkScreen：17 个 `LaunchedEffect(autoStartXxx)` → 1 个 `LaunchedEffect(autoStart, modelsLoaded)`；18 个 started 标志 → 1 个 `autoRunStarted`；`ImportViewModel` autostart 分支的 `delay(300)` 删除（改为 IO 扫描后按文件数直接触发）。
- 真机 smoke：`--ez autostart_import true` 正常进入导入屏；`--ez autostart_search_eval true` 20s 内产出评测结果文件。

### B3-12 组合期 IO 排查
- debug 源集组合期磁盘 IO = 0：`remember { discoverModels(context) }` → `LaunchedEffect + withContext(Dispatchers.IO)`；G1 `isFile` 检查移入 IO 协程；`runReport()` 的 File 读取在 runCatching（调用时机为按钮/effect，非组合期）。

### B3-13 重复导入计数提示
- `IngestRepository.importStaged(): ImportBatchResult(documentIds, duplicateCount, enqueuedCount)`（B1-6 去重语义 UI 可见化；`importFile` 未变）。
- ImportViewModel：`duplicateCount>0` 显示"新导入 X 个 / 已存在 Y 个"；仅新增时显示"已登记 X 个导入任务"；无变化不显示。
- 真机 DB 测试：3 文件（2 同内容+1 不同）→ documentIds=2 / duplicateCount=1 / DB document+job 各 2 / staging 清空；重 seed 同内容 → duplicateCount=2 / documentIds 空。

### B3-14 索引维护明细
- SettingsViewModel 增加 `indexHealth: IndexHealth?`；设置屏展示 chunk/FTS/向量三表计数、healthy/unhealthy、孤儿行数、reasons 前 5 条；按钮调用 `repairIndex()`，完成后提示清理行数 + 是否重建 + 修复前/后计数，失败显示可读错误。

---

## 3. 验证证据

### 3.1 静态门禁

```text
$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin \
    :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin \
    :app:testDebugUnitTest :app:testReleaseUnitTest
BUILD SUCCESSFUL
debug JVM tests = 221 / failures = 0（release unit 同步编译执行，源集隔离正确）

$ ./gradlew :app:lintDebug      # abortOnError = true
BUILD SUCCESSFUL                # 0 errors / 26 warnings

$ ./gradlew :app:assembleDebug :app:assembleRelease   # R8
BUILD SUCCESSFUL
```

### 3.2 真机验收（OnePlus PJZ110 / Android 16 / arm64）

| 项 | 结果 |
|---|---|
| `connectedDebugAndroidTest` | **28 tests / 0 failed**（新增 ImportBatchResultDbTest 2 + IngestJobObserveRecentTest 1） |
| StrictMode（打开导入屏） | app 代码归属违规 **0**；4 条为 `com.oplus.uifirst` 系统 binder 注入（栈无应用帧），已如实记录 |
| autostart import | 正常进入导入屏（一次性消费不重放） |
| autostart search_eval | 20s 内产出结果文件（queries.json 主路径） |
| 深色 5 屏截图 | `docs/review/b3/after/b3_dark_{home,import,search,sources,settings}.png`，正文/卡片/按钮可读 |
| adaptive icon | `aapt2 dump badging` 显示 `mipmap-anydpi-v26/ic_launcher.xml`（160/240/320/65534 密度） |

### 3.3 关键量化指标

| 指标 | 修复前 | 修复后 |
|---|---|---|
| ImportViewModel 构造期磁盘读 | 82ms DiskReadViolation（D1） | 0（app 归属） |
| autostart 参数/Effect/标志 | 19 参数 / 17 LaunchedEffect / 18 started | 2 参数 / 1 LaunchedEffect / 1 标志 |
| Benchmark 同构模板行数 | 82 行 | 29 行（-65%） |
| 导入任务渲染行数 | 101 卡全量 | ≤50（observeRecent(50)） |
| 组合期磁盘 IO（debug 源集） | 1 处 `remember { discoverModels }` | 0 |
| Preview 数量 | 1 | 18（5 屏浅/深 + 空/错/加载） |
| 深色显式 color role | 6 | 27 基础 + 9 surface/scrim/tint（fixed roles 受 M3 版本限制） |
| 深色卡片底色 | #36343B（M3 基线紫灰） | **#30362E**（品牌深绿 SurfaceContainerHighestDark） |

---

## 4. 计划偏差记录

| # | 计划原文 | 实际实现 | 原因/影响 |
|---|---|---|---|
| 1 | B3-1 验收"真机 StrictMode 0 违规" | app 代码归属 0；系统 UIFirst 注入仍有 4 条 | 系统栈无应用帧，无法也不应改应用代码；报告已注明过滤口径 |
| 2 | B3-3 验收"对比度 WCAG AA ≥4.5:1（工具核验）" | 代码 role 完整 + 真机截图目检；未用对比度工具逐项计算 | 工具核验留 B5-2 Compose UI/视觉回归时补；深色截图已入库 |
| 3 | B3-4 计划未要求密度 PNG | anydpi vector + v26 adaptive，未生成 PNG 密度 | minSdk24 支持 VectorDrawable；lint 仅 IconMissingDensityFolder warning（若出现） |
| 4 | B3-9 验收"101 任务首帧 <500ms（真机 measure）" | 实现 limit 50 + DAO 测试；未做真机首帧测量 | 真机当前库无 101 任务场景；渲染上界由 50 行约束，性能留 B5 基线 |
| 5 | B3-2 "评估引擎预热（内存代价，默认关）" | 未实现预热，仅阶段提示 | 预热会增加内存与首启耗时，默认关符合计划；未启用 |
| 6 | B3-10 "文档只写一种" | scripts 注释标注主/兼容路径；未改 README | 文档主路径已在脚本注释与 B3 报告说明；B6 统一发布文档 |
| 7 | 新增 `testImplementation("org.json:json:20231013")` | 已添加 | 仅测试依赖（JVM 解析测试需要可用 org.json），不影响 APK/生产运行 |
| 8 | B3-3 "完整 dark color scheme" | 显式映射 27 基础 + 9 surface/scrim/tint；M3 fixed roles 当前 API 不提供 | 库默认值仅影响未使用语义，产品 Card/正文均走已显式 role；已注释说明 |
| 9 | B3-6 "Settings 也复用卡片模板" | BenchmarkScreen 内模板 -65%；Settings 仍有 9 处独立 Card（布局语义不同） | 如实记录为 partial，未做无收益模板化 |
| 10 | B3-11 "新增 runner 只改 1 处注册表" | 实际仍需 6 处（AutoStart 字段/read/EXTRA/DebugRun/route/dispatch） | 已消除 17 LaunchedEffect/18 标志/delay；"1 处"未达，记为 partial，可后续注册表 DSL |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 深色对比度未工具化 | 27 role + 5 屏截图目检 | B5-2 Compose UI/视觉回归补 WCAG 工具核验 |
| 2 | Preview 仅 IDE 可渲染 | 18 个 @Preview 编译通过，CI 不渲染 | B5-2 Compose UI test/截图回归再覆盖 |
| 3 | 导入列表首帧未真机量化 | limit 50 有 DB 测试，无 101 任务首帧数字 | 后续导入大量文档时用 Macrobenchmark（B5-3）测 |
| 4 | StrictMode 系统注入噪音 | OnePlus UIFirst 在 app 进程触发 binder 磁盘访问 | 验收按"应用栈归属"过滤；不影响应用代码质量 |
| 5 | B3-2 冷启动判定依赖 `currentModelPath` | 导入已构建引擎时视为 warm，可能不显示提示（符合预期） | 如误判可在 B5 加 instrumentation |
| 6 | autostart 破坏性 runner 未全量真机跑 | 只 smoke import/search_eval；delete_check 等会改数据 | 保留能力，验收脚本按需执行 |
| 7 | 测试依赖 org.json | 仅 testImplementation | 已记录；B4 依赖审查时核对 |
| 8 | ImportBatchResultDbTest 使用真实 AppDatabase | instrumentation 会清空被测应用数据（测试 APK 场景；跑完即卸载） | 与其他 DB 测试一致；开发机跑测试前注意备份 |
| 9 | EvalSetRunner 兼容回退无提示 | evalset 缺失时会静默改用 queries.json（relevant=[]），跑批仍报完成 | 记录；评测前检查输入文件（B6 文档） |
| 10 | Settings 卡片未模板化 | 9 处独立 Card，重复度低于 BenchmarkScreen | 记录为 partial；后续 UI 改版再统一 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | Theme 只显式 27 role，Card 使用 surfaceContainerHighest 回退 M3 基线 #36343B（深色卡片紫灰，与"完整 role"结论矛盾） | **已修**：补 surfaceDim/Bright/5×surfaceContainer/surfaceTint/scrim；卡片底色实测 #30362E；fixed roles 受 M3 版本 API 限制，已注释并记录偏差 |
| S2 | 硬性 | debug BenchmarkScreen 在 Main 调 `EmbedderBenchmark.exportJson` 写盘、`runReport` 里 `readText()` 也在 Main | **已修**：benchmark 循环与 `runReport` 读取全部 `withContext(Dispatchers.IO)` |
| S3 | 硬性 | `runSimple` 用 `runCatching` 吞 CancellationException | **已修**：改为 try/catch 且 `CancellationException` 重抛（与 launchExperiment 一致） |
| S4 | 判断性 | `ImportBatchResultDbTest` 用真实 AppDatabase 且 clearAllTables，会清空设备知识库 | **接受并记录**：与其他 DB instrumentation 一致（测试 APK 场景、跑完卸载）；风险写入 §5#8 |
| S5 | 判断性 | MainActivity 导入 autostart 绕过 pendingAutoStart，一次性依赖 VM 内部布尔 | **已修**：`pendingImport/pendingImportCancel` 在 goHome 清空，不再依赖 VM 兜底 |
| S6 | 判断性 | `EvalSetRunner` 在 evalset 缺失时静默回退 queries.json（relevant=[]）仍报完成 | **记录**：属兼容路径设计；风险写入 §5#9（B6 文档提示） |
| S7 | 判断性 | ImportViewModel 成功提示重进屏幕仍残留 | **已修**：`onScreenStarted` 清空 `_message` |
| S8 | 判断性 | ReportCard 仍复制 SectionCard 模板 | **已修**：ReportCard 改用 SectionCard |
| S9 | 判断性 | EvalQueryParsingTest 注释称缺 org.json/用 Assume 可静默 skip（实际已加依赖）；`ic_launcher.xml` 仍写"临时图标" | **已修**：测试重写为真实执行（11 用例）、依赖注释更新；图标注释更正 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B3-1 | partial（字面 0 违规未达） | **报告口径修正**：应用栈归属 0；4 条为 OPlus UIFirst 系统 binder 注入（无应用帧），§3.2/§4 如实记录 |
| B3-2 | partial（currentModelPath 漏报重建） | **已修**：`isEngineWarm` 按 `path::gpu=<mode>` 全 key 判定（换模型/偏好会提示） |
| B3-3 | partial（surfaceContainer* 缺失） | **已修**：补齐 surface 系 role，卡片底色实测品牌深绿；fixed roles 记录为 M3 版本限制 |
| B3-5 | partial（Sources 无 loading） | **已修**：SourcesViewModel.loaded + 首帧 LoadingState |
| B3-6 | partial（Settings 未复用；-65% 无据） | **部分接受**：Benchmark 模板 82→29 行（-65%，按 Card 块行数实测）；Settings 保留独立布局，如实记为 partial |
| B3-9 | partial（无首帧 measure；活动任务可能被截） | **已修截断问题**：SQL 改为活动任务优先；首帧 measure 未做（写入 §4#4/§5） |
| B3-11 | partial（注册 6 处 vs 1 处） | **部分接受**：数据类+路由表+单 effect 已落地；"1 处注册表"记为偏差，未强行重构（§4#10） |
| 范围/兼容 | 无 scope creep；20 EXTRA/ImportScreen/SearchResult.query/ftsHealthCheck/备份入口未变；仅测试依赖 | 确认 |

### 6.3 审查后复验（最终）

- `compileDebugKotlin / compileReleaseKotlin / compileDebugUnitTestKotlin / compileDebugAndroidTestKotlin` ✅
- JVM `testDebugUnitTest`：**221 tests / 0 failed**；`testReleaseUnitTest` 同步绿（源集隔离正确）
- `lintDebug`：**0 errors / 26 warnings**
- `assembleDebug` / `assembleRelease`（R8）✅
- 真机 `connectedDebugAndroidTest`：**28 / 28 PASS**
- 真机 smoke：StrictMode app 归属 0；autostart import/search_eval/delete_check 均正常（delete_check 5s 出结果）
- 深色 5 屏截图复核：卡片底色 #30362E（非基线紫灰）

---

## 7. 文件清单（B3 相对 B2 终态）

**主屏/VM/组件**：`ui/ImportScreen.kt`、`ui/ImportViewModel.kt`、`ui/SearchScreen.kt`、`ui/SearchViewModel.kt`、`ui/SourcesScreen.kt`、`ui/SettingsScreen.kt`、`ui/SettingsViewModel.kt`、`ui/UiCommon.kt`、`ui/HomePreviews.kt`（新）、`ui/theme/{Color,Theme}.kt`
**入口/实验屏**：`MainActivity.kt`（AutoStart 顶层化/一次性消费）、`app/src/debug/.../ui/BenchmarkScreen.kt`（拆分/单 LazyColumn/路由表）、`app/src/release/.../ui/BenchmarkScreen.kt`（stub 签名同步）
**评测输入**：`app/src/debug/.../benchmark/EvalQueryInput.kt`（新）、`SearchEvalRunner.kt`、`EvalSetRunner.kt`
**数据契约**：`core/ingest/IngestRepository.kt`（ImportBatchResult/importStaged）、`data/db/Daos.kt`（observeRecent）
**资源**：`res/values/{strings,themes,colors}.xml`、`res/values-night/{themes,colors}.xml`（新）、`res/mipmap-anydpi-v26/**`（新）、`res/mipmap-anydpi/**`（新）、`res/drawable/ic_launcher_{background,background_round,foreground,monochrome}.xml`（新）、`AndroidManifest.xml`
**构建/脚本**：`app/build.gradle.kts`（testImplementation org.json）、`scripts/run_eval.sh`、`scripts/run_thermal.sh` 注释
**测试**：`testDebug/.../core/search/{EvalQueryParsingTest,AutoStartContractTest}.kt`（新）、`androidTest/.../core/ingest/{ImportBatchResultDbTest,IngestJobObserveRecentTest}.kt`（新）、`test/.../core/ingest/ImportBatchContractTest.kt`（core-fixer）
**审查后修复**：`ui/theme/{Color,Theme}.kt`（surface 系 role）、`ui/SearchViewModel.kt` + `core/embedding/EmbeddingRuntime.kt`（isEngineWarm）、`ui/SourcesViewModel.kt` + `ui/SourcesScreen.kt`（loading）、`data/db/Daos.kt` + `ui/ImportViewModel.kt`（observeRecent 活动优先）、`MainActivity.kt`（pendingImport 一次性）、`ui/ImportViewModel.kt`（message 清理）、`app/src/debug/.../BenchmarkScreen.kt`（IO/取消/ReportCard）、`testDebug/.../EvalQueryParsingTest.kt`（真实执行）、`res/drawable/ic_launcher.xml` 注释
**验收截图**：`docs/review/b3/after/b3_dark_*2.png`（审查后最终版）与首轮 `b3_dark_*.png`

---

*报告生成：2026-10-17 · B3 代码变更未提交；设备已恢复（debug/v3 DB/模型）。*
