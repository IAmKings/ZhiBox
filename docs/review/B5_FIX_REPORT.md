# B5 修复报告（P2 B5：测试补强）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B5（3 项：B5-1 Robolectric、B5-2 Compose UI test、B5-3 10k/100k 合成库性能基线）
- **决策沿用**：Robolectric 已经用户批准引入；不 provision 签名；不改产品行为（仅测试代码/构建配置/基线文档）
- **执行方式**：build-fixer（Robolectric/Compose test 依赖与 testOptions）+ data-reviewer（Robolectric 用例）+ ui-reviewer（Compose UI 用例）+ Lead（性能基线、集成门禁、真机验收、报告）

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B5-1 Robolectric | 引入 **Robolectric 4.17**；新增 3 个用例（导入取消 2、备份回环 1），`testDebugUnitTest` 实际运行、非 skip |
| B5-2 Compose UI test | 引入 `ui-test-junit4 1.11.4`（BOM）+ `ui-test-manifest`（debug）；新增 **5 个 instrumentation 用例**，真机全部通过 |
| B5-3 性能基线 | 新增 JVM 合成基准（10k 向量扫描/关键词重排）与 100k 流式门限校验；基线落盘 `docs/review/b5/10k-baseline.json`；真机 10k FTS 基线沿用 B2 |
| JVM 测试 | **249 tests / 0 failed**（含 Robolectric 3、合成基准 3） |
| 真机 instrumentation | **40 / 40 PASS**（原 35 + Compose UI 5） |
| Lint / 构建 | **0 errors / 25 warnings**；`assembleDebug/Release` 绿 |
| release APK | 49,068,516 B（B5 仅测试依赖，APK 体积不变） |
| 依赖校验 | verification-metadata 重新生成（**613 components**），strict 门禁通过 |

---

## 2. 逐项完成说明

### B5-1 Robolectric（导入状态机/取消/备份回环）
- 构建（`app/build.gradle.kts` + `libs.versions.toml`）：
  - `testImplementation(libs.robolectric)`，版本 **4.17**（4.14.1 官方兼容表仅 API 21–35，本项目 targetSdk 36；4.16=23→36、4.17=23→37；Android 16 单测需 JDK 21，本机/CI 满足）；
  - `android.testOptions.unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true; all { maxHeapSize = "2g" } }`（Robolectric+Room 需要资源与更大堆）；
  - 仅 test classpath，不影响 APK；首次运行由 Robolectric 自行下载 `android-all-instrumented`（需联网）。
- 新增用例（`app/src/test/**`，`@RunWith(RobolectricTestRunner::class)`、`@Config(sdk=[35])`）：
  - `IngestCancelRobolectricTest`（2 例）：
    - 登记 2 文档（queued/pending，不触发 WorkManager）→ 模拟 1 份完成（done/ready）→ `cancelImport()`：queued job→cancelled、pending doc→cancelled、**ready 不被取消**、无 active/queued 残留；`recoverInterrupted`/`requeueInterrupted` 不复活 cancelled；
    - 在途 parsing/processing 场景：取消后 `recoverInterrupted()` 先 requeue 再因 doc=cancelled 收尾 job=cancelled，不产生非法组合、不复活。
  - `BackupRobolectricTest`（1 例）：小数据（source/document ready/chunk/EGemma2 空间 meta/手写 2 维向量）→ `exportToFile`（2 向量、preview spaces=1/vectorCount=2/docs=1/chunks=2）→ `clearAllTables` → `importFromFileForTest`：doc=ready、chunk=2、FTS 重建=2、向量=2、空间 meta 兼容、**blob 逐字节一致**、bigram 可检索。
- 隔离：Robolectric 每测试独立 Application/临时目录；反射关闭并清空 `AppDatabase` 单例；DB/documents/staging/临时 zip 全部清理。

### B5-2 Compose UI test（真机）
- 构建：`androidTestImplementation(platform(BOM))` + `ui-test-junit4`（1.11.4，传递 `androidx.test.ext:junit`）；`debugImplementation(ui-test-manifest)`（测试宿主 Activity，仅 debug）。
- 新增 `app/src/androidTest/java/com/zhibox/memory/ui/ComposeUiSmokeTest.kt`（2 类 5 用例）：
  1. `SearchContent` 渲染 2 条命中 + 点击命中卡片 → `onHitClick(doc-a)`；
  2. `SearchContent` 拒绝态（refused）→ 可读错误/hint → 点击「知道了」→ `onDismissError`；
  3. `EvidenceScreen` 渲染证据 → 点击顶栏返回 → `onBack`；
  4. `SourcesContent` 空态 → 「去导入」→ `onOpenImport`；
  5. `ActivityScenario.launch(MainActivity)` → `recreate()`（等价旋转/配置变更）→ 首页仍渲染。
- 数据隔离：fake `SearchResult/SearchHit` 全量构造；不访问 DB/模型/网络；断言文案取自 `R.string`；用例无顺序依赖。

### B5-3 10k/100k 合成库性能基线
- 新增 `app/src/test/java/com/zhibox/memory/core/search/SyntheticRetrievalScalingTest.kt`（纯 JVM，无 Room/模型）：
  - **10k×768 向量通道扫描**（topK=10）：warm 3 + 10 轮，p50=**4ms**、p95=**4ms**；矩阵 29MB；used heap 实测 46–287MB（非增量/峰值，受 GC 时机影响，仅打印/病态兜底 <1GB）；
  - **10k 候选关键词覆盖分重排**（每条 content **1200 字符**、cap=120）：p50=**416ms**、p95=**436ms**（同机多轮 401–436ms）（生产 83 chunk 场景可忽略，作为回归基线）；
  - **100k 规模**：仅断言必须走流式扫描（预估矩阵 **292MB**、`shouldStreamMatrix=true`、100 页×1000），不实际分配 ~300MB。
- 基线落盘：`docs/review/b5/10k-baseline.json` + `README.md`（生成命令/说明）；真机 10k FTS 基线沿用 B2 `Fts10kPerfTest`（p50=5ms/p95=7ms，OnePlus PJZ110）。
- 阈值只作病态兜底（p95<5s、heap<1GB），避免不同机器绝对毫秒误差导致 flaky；基线数字用于同机回归对比。

---

## 3. 验证证据

### 3.1 静态门禁（strict 依赖校验）

```text
$ ./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease \
            :app:assembleDebugAndroidTest :app:testDebugUnitTest
→ verification-metadata 613 components

$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin \
            :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug \
            :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL
JVM debug = 249 tests / 0 failed；release unit 全绿
lint = 0 errors / 25 warnings
```

### 3.2 真机 instrumentation（OnePlus PJZ110 / Android 16 / arm64）

`./gradlew :app:connectedDebugAndroidTest` → **40 tests / 0 failures**（原 35 + B5 Compose UI 5）。

### 3.3 性能基线（记录）

| 场景 | 结果 |
|---|---|
| 10k×768 向量扫描（JVM） | p50=4ms、p95=4ms；矩阵 29MB；used heap 46–287MB（GC 波动） |
| 10k 关键词重排（JVM，1200 字/条） | p50=416ms、p95=436ms（多轮 401–436） |
| 100k 矩阵 | 预估 292MB；流式门限生效（100 页 ×1000） |
| 10k FTS DAO（真机，B2） | warm p50=5ms、p95=7ms |

### 3.4 依赖隔离
- Robolectric/`ui-test-junit4` 仅 test/androidTest；`ui-test-manifest` 仅 `debugImplementation`；
- release APK 49,068,516 B 与 B7 终态一致（B5 未增加 APK 体积）；verification metadata 覆盖测试配置。

---

## 4. 计划偏差记录

| # | 计划/决策 | 实际实现 | 说明 |
|---|---|---|---|
| 1 | B5-1 "in-memory Room" | Robolectric + **真实 AppDatabase（Robolectric 临时目录）**，非 Room.inMemoryDatabaseBuilder | `AppDatabase.get` 为单例且无 DI；真实临时库在 Robolectric 下等价隔离，且能覆盖迁移后的 schema/FTS/WAL；in-memory 需 main 加注入 seam（未改产品代码） |
| 2 | B5-2 "覆盖 5 屏关键路径" | 5 用例覆盖 搜索/证据/来源空态/重建；Import/Settings 屏未覆盖 | 优先覆盖交互链与旋转；其余屏已有 Preview（B3-7）与状态组件复用；可后续补 |
| 3 | B5-3 "10k/100k" | 10k JVM 基线 + 真机 FTS 沿用；100k 只验证流式门限 | 计划允许"JVM 纯逻辑 + 真机 instrumentation 可选"；100k 全量扫描（~300MB 矩阵/10 万 DAO 行）不在单测预算内 |
| 4 | Robolectric 版本 | 4.17（非缓存 4.14.1） | 4.14.1 官方仅支持 API 21–35，targetSdk 36 不可用；4.17 支持 36/37，首次运行需联网下载 android-all |
| 5 | heap 断言 | 仅病态兜底 <1GB，基线打印 used heap 实测值 | used heap（total-free 绝对值）随 GC 46–235MB 波动，硬阈值会 flaky；JSON 记录观测区间与测量口径 |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | Robolectric 首次运行需联网 | 需下载 `android-all-instrumented`（API 35/36） | CI/新机器首次需网络；缓存后离线可跑；文档已注明 |
| 2 | Robolectric 使用真实 AppDatabase | 依赖反射清空单例（main 无 DI） | 测试隔离已验证；若未来加 DI seam 可简化 |
| 3 | 10k 关键词重排 416ms 偏高 | 极端场景（10k 候选×1200 字符）纯 JVM | 作为回归基线；生产 83 chunk 无影响；超基线时人工/CI 预警 |
| 4 | 100k 未做端到端 | 仅门限与预估内存 | 计划允许；需要时上真机/宏基准（B6/后续） |
| 5 | Compose 覆盖不全 | 未覆盖 Import/Settings 交互、真实搜索链路（需模型/DB） | 已有 fake 状态链；后续可补 TestActivity/DI |
| 6 | recreate 用例用真实 MainActivity | ui-test-manifest 的空 ComponentActivity 不 setContent | 已用 ActivityScenario；稳定通过；如需 fake-content 重建需 androidTest 专用 TestActivity |
| 7 | 测试依赖维护 | Robolectric/Compose test 版本随依赖窗口升级 | 与 B4 依赖策略一致；verification metadata 重新生成流程已文档化 |
| 8 | Robolectric 运行期下载不在 metadata | android-all-instrumented 由 Robolectric 运行期下载，不经 Gradle → 不在 613 components 校验范围 | libs 注释 + b5/README 已注明；首次运行需网络，缓存后离线可跑 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | 合成基准 content = 300×"合成语料" = **1200 字符**，但测试注释/日志/JSON/README 记为 600 | **已修**：测试注释/日志、`10k-baseline.json`（content_chars=1200 + content_note）、B5 报告统一为 1200 |
| S2 | 硬性 | JSON `heap_limit_mb=200` 与代码 1024 冲突；release-unit 实测 235MB 已超 200 | **已修**：JSON 阈值改为 1024（病态兜底口径），并新增 vector/keyword p95 阈值 500/2000ms |
| S3 | 判断性 | build.gradle/verification-metadata 头部仍写 "565 components"（实际 613）；build.gradle 注释写 "2-3 条/createAndroidComposeRule"，实际 5 条/createComposeRule+createEmptyComposeRule | **已修**：三处注释更新 |
| S4 | 判断性 | Robolectric 反射清 `AppDatabase.instance` 用 `runCatching` 吞失败，字段重命名会静默泄漏单例 | **已修**：清空后显式 `check(field.get(holder)==null)`，失败即测试失败 |
| S5 | 判断性 | `@Config(sdk=[35])` 与 targetSdk 36 不一致（libs 注释称 4.17 支持 36） | **已记录**：libs 注释说明固定 35 是为本机/CI 已缓存 android-all 的稳定性优先，4.17 已具备 36 支持、后续可切；不再声称当前覆盖 36 |
| S6 | 判断性 | p95 上限 5s 对实测 4ms/436ms 过松；heap 指标实为 used heap（非增量/峰值）且 1024MB 对 2g 堆近乎不设防 | **已修**：阈值收紧为 500ms（向量）/2000ms（关键词）；字段改名 `heap_used_mb`；JSON/README/报告注明"used heap 非增量/峰值，仅病态兜底" |
| S7 | 判断性 | Robolectric 运行期下载 android-all-instrumented 不在 verification-metadata（与"锁定所有远程依赖"有缺口） | **已记录**：libs 注释 + `b5/README` 明确该下载不经 Gradle、不在 613 components 校验范围；首次需联网，缓存后离线 |
| S8 | — | 作用域/测试名/D8 约束/100k 门限/Compose fake 隔离，未发现问题 | 保留 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B5-1 | partial（真实 AppDatabase 临时库，非 Room.inMemoryDatabaseBuilder） | **偏差保留并已在 §4-1 记录**：AppDatabase 为单例无 DI，真实临时库在 Robolectric 下等价隔离且能覆盖 FTS/WAL/schema；in-memory 需 main 注入 seam（超出测试批次）。3 例实跑非 skip，取消/恢复/回环覆盖 |
| B5-2 | partial（5 屏中仅 Search/Evidence/Sources 空态/Home recreate；Import/Settings 未覆盖；旋转仅首页） | **偏差保留并记录（§4-2/§5-5）**：验收要求"2–3 条真机通过"已超额（5 条）；优先覆盖交互链与重建；Import/Settings 已有 Preview/状态组件复用，可后续补 |
| B5-3 | partial/wrong（内存方法不成立、阈值/数值矛盾） | **已修**：字符数/阈值/字段名口径统一（见 6.1）；10k p95 与 used heap 区间已落盘；100k 仅门限（计划允许） |
| 隔离/验证 | pass | 确认：测试依赖仅 test/androidTest + debug manifest；release 49,068,516B 不变；JVM 249/0、真机 40/40、lint 0/25 与产物一致 |
| 偏差记录 | partial | **已补**：§4 增 heap/字符数口径；§5 增 android-all 元数据缺口；§6 逐项处置 |

### 6.3 审查后复验

- `testDebugUnitTest`：**249 tests / 0 failed**（含 Robolectric 3、合成基准 3）
- `testReleaseUnitTest`：全绿
- `lintDebug`：0 errors / 25 warnings
- `assembleDebug/Release`：绿；release APK **49,068,516 B**（B5 未改变）
- 真机 `connectedDebugAndroidTest`：**40/40 PASS**（Compose 5 例）
- 依赖 strict 校验：613 components 通过

---

## 7. 文件清单（B5）

**构建**：`gradle/libs.versions.toml`（robolectric 4.17、ui-test-junit4/manifest alias）、`app/build.gradle.kts`（testOptions/Robolectric/androidTest+debug test 依赖）、`gradle/verification-metadata.xml`（613 components，重新生成）
**B5-1 测试**：`app/src/test/java/com/zhibox/memory/core/ingest/IngestCancelRobolectricTest.kt`（新，2 例）、`app/src/test/java/com/zhibox/memory/core/backup/BackupRobolectricTest.kt`（新，1 例）
**B5-2 测试**：`app/src/androidTest/java/com/zhibox/memory/ui/ComposeUiSmokeTest.kt`（新，5 例 / 2 类）
**B5-3 基准**：`app/src/test/java/com/zhibox/memory/core/search/SyntheticRetrievalScalingTest.kt`（新，3 例）、`docs/review/b5/{10k-baseline.json,README.md}`（新）
**既有基线**：`Fts10kPerfTest`（B2，真机 10k FTS p50/p95）

---

*报告生成：2026-10-17 · B7 后新增测试/构建配置；设备已恢复（debug/v3 DB/模型）。*
