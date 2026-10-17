# 工程基建与安全审查

> 审查人：infra-reviewer（工程基建与安全维度）
> 基线：`HEAD 9ca20d6` + 工作区未提交改动（66 个顶层变更条目 / 153 个未跟踪文件，2026-10-17）
> 方法：只读审查（read/grep/glob/git/aapt2/unzip/shasum），按 Lead 要求**未运行 gradle 构建**；结论均基于源码、清单、已构建产物与 Gradle 缓存元数据实测。
> 写入范围：仅本文件。

## 总评

工程质量整体高于 MVP 平均水平：版本目录集中管理、依赖 forcing 有 AAR 实证注释、模型下载器（断点续传+流式 SHA-256+原子改名）设计扎实、嵌入「空间指纹」把 GPU/NPU/换模型隔离做成结构性兜底、迁移测试真实覆盖 1→2/2→3/1→3、11 个实验脚本全部 `set -euo pipefail` 且无硬编码设备序列号——这些都是可以保留的资产。

但**发布通道尚未建立、实验代码与产品代码未隔离**是当前最刺眼的问题：release 无签名配置、从未产出 release APK，MVP-1 交付物是 62.4MB 的 **debug APK**（`application-debuggable`）；而 `main` sourceSet 里 37.6% 的 Kotlin 代码（benchmark 1969 行 + BenchmarkScreen 743 行）随 release 一起打包、首页还有「实验/诊断」入口、`MainActivity` 导出的 20 个 `autostart_*` extra 可被任意应用触发。Phase 1 收尾若直接进入内部/外部发布，该问题会立刻变成阻断项。

安全/隐私侧的三个关键缺口：① `allowBackup=true` 且无备份规则，个人知识库 SQLite（原文+向量）与 SAF 授权偏好会进入 Android Auto Backup，与「本地优先」定位冲突；② 模型完整性校验只在「下载」路径生效，SAF 共享目录启用与知识库导入只比对文件名/长度（1% 容差），且 `verified` 验证门只写不读——被替换的同长模型可直接喂给 native 引擎；③ 知识库导入「先 `clearAllTables()` 后逐表写、无事务无回滚」，中途失败会丢失既有全部知识库。

基建侧缺口集中在可复现性：Room schema 2/3 未入库导致迁移测试在 clean clone/CI 必失败；无 CI、无 lint/detekt/ktlint、37 个用例只覆盖纯逻辑；`.gitignore` 未覆盖 `dist/`（62MB APK）与 2.8MB 评测结果；README/交付文档与实现已有 7 处实测不一致。建议 P0 在发布前完成，P1 在一个迭代内消化。

## 量化数据（均为本机实测）

| 项目 | 实测值 | 命令/来源 |
|---|---|---|
| 工具链 | AGP 8.13.2 / Kotlin 2.4.21 / Gradle wrapper 8.14.3 / KSP 2.3.12 / JDK 17 target | `gradle/libs.versions.toml:2-19`、`gradle/wrapper/gradle-wrapper.properties:3` |
| SDK/ABI | compileSdk=36 / targetSdk=36 / minSdk=24 / 仅 `arm64-v8a` | `app/build.gradle.kts:10-22` |
| 依赖规模 | 18 个 version、22 个 library、4 个 plugin | `libs.versions.toml` 统计 |
| 主源码 | 43 个 `.kt` / 7211 行；其中 benchmark 包 13 个 / **1969 行**，`BenchmarkScreen.kt` 743 行 | `find app/src/main -name '*.kt' \| xargs wc -l` |
| 实验代码占比 | (1969+743)/7211 = **37.6%** | 上两项计算 |
| 未提交改动 | 18 files changed, +947/−120（已跟踪）；新增 `.kt` 24 个 / 4004 行 | `git diff --stat`、`git ls-files --others` |
| 测试 | `src/test` 3 文件 / 388 行 / 34 个 `@Test`；`androidTest` 1 文件 / 199 行 / 3 个 `@Test`；合计 **4 文件 / 587 行 / 37 用例** | `wc -l`、`grep -c @Test` |
| 脚本 | 11 个 `.sh` / **796 行**（11/11 含 `set -euo pipefail`）；2 个 `.py` / 536 行；eval 语料 50 个 txt / 244KB；evalset **262 条**查询（zh 218 / en 28 / mix 16） | `wc -l scripts/*.sh`、`python3 -c json` 统计 |
| 模型资产 | `models/` **1.4GB / 8 文件**，最大 740M = 484,622,336B；`SHA256SUMS` 8 条；实测 270M 与 task 的 SHA 与登记一致 | `du -sh models`、`shasum -a 256` |
| Git 状态 | tracked 117 文件 / untracked **153** 文件；`.git` 仅 2.4MB，历史最大 blob 304,158B（无大二进制入库）；20 个 commit | `git ls-files`、`git rev-list --objects --all \| git cat-file` |
| 工作区体积 | 4.4GB（`.venv-g1` 1.1G、`.hf-cache` 1.4G、`models` 1.4G 均已 ignore、`dist` 59M 未 ignore） | `du -sh` |
| 交付产物 | `dist/MVP-1/zhibox-mvp1-debug.apk` **62,371,642B**；`app/build/outputs` 仅有 debug/androidTest APK，**无 release APK** | `find app/build/outputs -name '*.apk'` |
| APK 内容 | `application-debuggable`；`native-code: 'arm64-v8a'`；合并权限 5 个系统权限（INTERNET/WAKE_LOCK/ACCESS_NETWORK_STATE/RECEIVE_BOOT_COMPLETED/FOREGROUND_SERVICE）+ 1 个应用自定义动态接收器权限；12 个 `classes*.dex`（解压约 36MB）+ 4 个 arm64 so（litertlm 22.2MB、mediapipe 14.4MB、sqlite_vector_store 5.2MB、icing 3.8MB） | `aapt2 dump badging`、`unzip -l` |
| Room schema | 磁盘 3 个：`1.json` 20,189B / `2.json` 20,579B / `3.json` 21,610B；**git 仅跟踪 1.json** | `git ls-files app/schemas`、`ls -l` |
| 质量门禁 | 无 `.github`、无 detekt/ktlint/editorconfig/lint.xml、无 `gradle/verification-metadata.xml`、`.gitattributes` 不存在 | `find`/`ls` 实测 |
| 文档漂移抽查 | 7 处不一致（见 P1-8） | 逐条 grep/ls 对照 |

## 发现

### P0（发布阻断 / 安全 / 隐私 / 数据丢失级）

---

#### P0-1 Release 通道不存在：无签名配置、交付物是 debuggable 的 debug APK

- **【级别】** P0（发布阻断；当前 `dist/MVP-1` 交付物自身也是安全弱点）
- **位置**：`app/build.gradle.kts:27-35`（buildTypes.release）、`app/build.gradle.kts:8-25`（无 signingConfigs）、`dist/MVP-1/zhibox-mvp1-debug.apk`、`app/proguard-rules.pro:1-3`
- **证据**：
  - `app/build.gradle.kts:27-35` 只有 `isMinifyEnabled = false` + `proguardFiles(...)`；全文无 `signingConfig`，`assembleRelease` 只会产出 unsigned APK（无法安装/分发）。
  - `find app/build/outputs -name '*.apk'` 只返回 `apk/debug/app-debug.apk`（62,371,642B，10-17 14:29）与 `apk/androidTest/.../app-debug-androidTest.apk`，**没有任何 release 产物**，说明 release 通道从未被构建/验证。
  - `aapt2 dump badging dist/MVP-1/zhibox-mvp1-debug.apk` → `application-debuggable`，`native-code: 'arm64-v8a'`；`dist/MVP-1/zhibox-mvp1-debug.apk` 与 `app/build/outputs/apk/debug/app-debug.apk` 字节数完全一致（均为 62,371,642B，时间戳 14:31 / 14:29），即 **MVP-1 交付物就是 debug 包**。
  - `app/proguard-rules.pro` 全文件仅 3 行注释（「MVP 骨架阶段未启用混淆」），`isShrinkResources` 未配置。
- **影响**：`./gradlew assembleRelease` 的产物不可用，任何「MVP-1 内部发布 / 对外分发」都只能继续发 debug 包；debug 包 `debuggable=true` 允许 `run-as`/调试器访问应用私有目录（个人知识库 SQLite），且未混淆、体积 62.4MB、无资源裁剪。Phase 1 收尾如果以此作为发布基线，属于发布阻断。
- **建议**（M）：
  1. 新增 `signingConfigs.release`，keystore 路径/口令从 `gradle.properties`（不入库）或环境变量读取；`buildTypes.release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(...) }`。
  2. 补 `proguard-rules.pro` keep 规则（MediaPipe/LiteRT JNI、Room、Compose），并用 `assembleRelease` + `apkanalyzer` 验证。
  3. 交付物改由 release 变体产出；`dist/` 仅放 release 包并在文件名/文档标注 versionCode/commit，禁止把 debug 包当交付物。
  4. 若本阶段确实只做内部验收，需在 `docs/phase1/MVP1-ACCEPTANCE.md` 明确「交付物为 debug 包、不可对外」，并把本项降级跟踪。

---

### P1（高优先，一个迭代内处理）

---

#### P1-1 隐私：`allowBackup=true` 且无备份规则，个人知识库会进入 Android Auto Backup

- **【级别】** P1（隐私）
- **位置**：`app/src/main/AndroidManifest.xml:11-16`；`ls app/src/main/res/xml` → 不存在
- **证据**：`AndroidManifest.xml:12` `android:allowBackup="true"`；manifest 无 `android:fullBackupContent` / `android:dataExtractionRules`，`res/xml/` 目录不存在（无 backup_rules / data_extraction_rules）。Auto Backup 默认包含 `databases/`（`zhibox.sqlite3`：文档原文 chunks、向量 blob、来源）与 `shared_prefs/`（`embedding_paths`、`shared_model_store` 的 SAF tree URI、验证门记录）。`filesDir/models` 也在默认范围（受 25MB 配额限制，1.4GB 模型会令备份失败但 DB 仍会尝试上传）。
- **影响**：与产品「本地优先 / 离线个人记忆」的隐私定位冲突——个人笔记原文与向量默认上传到用户 Google 账户云备份；卸载重装或换机时备份恢复还可能带入失效的 SAF 授权状态（`SharedModelStore` 已有 `PERMISSION_LOST` 兜底，但敏感数据已经外流）。
- **建议**（S）：`android:allowBackup="false"`；若确需跨设备迁移，改用产品自带的 `KnowledgeBackup`（用户显式导出），并配置 `android:dataExtractionRules` 只白名单 `app_kv` 之类的非敏感配置。同时在隐私说明/README 中显式声明。

---

#### P1-2 实验/诊断代码全量进入 release：benchmark 1969 行 + 首页入口 + 20 个可被外部触发的 autostart extra

- **【级别】** P1（发布质量 + 攻击面）
- **位置**：`app/src/main/java/com/zhibox/memory/benchmark/`（13 文件 1969 行）、`app/src/main/java/com/zhibox/memory/ui/BenchmarkScreen.kt:36-41`（743 行）、`app/src/main/java/com/zhibox/memory/MainActivity.kt:51-73,76-118,122,205`、`app/build.gradle.kts:46-49,94-95`
- **证据**：
  - benchmark 包位于 **main** sourceSet（不是 `src/debug`）；`app/build.gradle.kts:46-49` 只配置了 androidTest 的 assets，没有任何 debug sourceSet/ProductFlavor 隔离。
  - `BenchmarkScreen.kt:36-41` 直接 import benchmark 包；`MainActivity.kt:122,205` 首页固定渲染「实验/诊断」按钮，无 `FLAG_DEBUGGABLE` 判断；`MainActivity.kt:76-118` 依据 20 个 `EXTRA_AUTO_*` extra（`MainActivity.kt:134-157`）直接进入 benchmark/import/eval 流程，而 `MainActivity` 是 `exported="true"`（`AndroidManifest.xml:26-34`）——**任何第三方应用都能 `am start -n com.zhibox.memory/.MainActivity --ez autostart_xxx true` 触发实验/跑批**。
  - `app/build.gradle.kts:95` `implementation(libs.jieba.analysis)`（jieba 仅被 `benchmark/ChineseTokenizationBenchmark.kt:6,79` 使用）→ 约 1MB 词典打进 release。
  - `unzip -l` 显示 debug APK 有 12 个 `classes*.dex`（解压约 36MB），叠加 `isMinifyEnabled=false`（P0-1），实验 UI/基准代码无裁剪地进入最终包。
- **影响**：release 包带调试/实验入口，普通用户可见；实验代码调用 native 推理/全库扫描（`ProductionBenchRunner`、`W4PlusRunner` 等）可被外部 Intent 触发造成耗电/发热/ANR；基准/诊断代码与产品代码共享 main sourceSet，长期维护成本高、误触发风险大；jieba 属于已判定淘汰（G3 bigram 胜出）的死依赖却随包分发。
- **建议**（M）：
  1. benchmark 包 + `BenchmarkScreen.kt` 迁入 `app/src/debug/java/...`，main 只留 `interface DiagnosticsEntry { fun create(...) }`，release 变体给空实现；或使用 `productFlavors { internal/debug }`。
  2. 首页按钮与 `autostart_*` 分支用 `(applicationInfo.flags and FLAG_DEBUGGABLE) != 0` 门禁；或把 autostart 处理移入 debug-only Activity。
  3. `jieba-analysis` 改 `debugImplementation`，Phase 1 直接删除（`app/build.gradle.kts:94` 注释自己已写「Phase 1 检视移除」）。
  4. 开启 R8 后确认 benchmark 类不被 main 引用残留。

---

#### P1-3 模型完整性校验链断裂：SAF 启用/知识库导入只比长度，`verified` 验证门只写不读

- **【级别】** P1（安全/数据完整性）
- **位置**：`app/src/main/java/com/zhibox/memory/core/model/SharedModelStore.kt:100-115,158-196`、`app/src/main/java/com/zhibox/memory/core/backup/KnowledgeBackup.kt:139,253-266`、`PathPreferences.kt:59-66`、`BenchmarkScreen.kt:435-440`、`ModelCatalog.kt:167-168`
- **证据**：
  - `SharedModelStore.enable()` 复用缓存只判 `cache.length() == doc.length()`（`SharedModelStore.kt:105`）；`downloadEntry()` 发现共享目录已有「同名等长」文件直接跳过下载（`:168`）——**都不会重新计算 SHA-256**。校验只发生在 `ModelDownloader.download(expectedSha256=...)` 这一路（`:178-182`）。
  - `KnowledgeBackup` 导出时写入 `catalog_sha256`（`KnowledgeBackup.kt:139`，文档注释声称「导入端据此校验」`KnowledgeBackup.kt:33`），但 `buildPreview()` 只用 `ModelCatalog.sizeMatches()`（1% 容差，`KnowledgeBackup.kt:257-261`），**从未比对 sha256**。
  - `ModelCatalog.Entry.verified` 在 main 代码中无任何消费方；验证门写入 `PathPreferences.setVerifiedSha`（`BenchmarkScreen.kt:435-439`）后，`PathPreferences.verifiedSha()` 全项目无读取（`grep -rn "verifiedSha(" app/src` 只有定义）。`ModelCatalog.isVerifiedForNpu()` 恒返回 false（`ModelCatalog.kt:167-168`）→ 推荐门是死代码。
  - 因此设置页的「下载到共享目录 / 启用」对 `verified=false` 的实验模型（270M、740M、SM8750 特化版）没有任何拦截；`PathPreferences.resolve()` 在只存在实验模型时会直接选中它（`PathPreferences.kt:99-115`）。而 `docs/UPSTREAM_VERSIONS.md` 已实测 SM8750 特化版「NPU 后端挂死 >7min；CPU 后端亦挂死」。
- **影响**：① 威胁模型下（共享 SAF 目录可被其他应用/用户替换文件）同长恶意或损坏模型可绕过下载校验直接加载进 MediaPipe/LiteRT native 解析器；② 导入的跨设备知识库不校验模型真伪，模型被替换后向量空间语义错位却无感知；③ 文档承诺的「验证门后启用」未实现，用户可一键启用已知会挂死的模型，造成 ANR/长时间卡死（搜索/导入路径的 `EmbeddingRuntime.withProvider` 对 CPU 建引擎没有超时）。
- **建议**（M）：抽出 `ModelIntegrity.verify(file, entry): Boolean`（流式 SHA-256 + 尺寸），在 `enable()` 前、`downloadEntry()` 跳过逻辑前、`KnowledgeBackup.import` 前统一调用，失败则删除/拒绝并在 UI 提示；实现验证门：`verified=false` 的 entry 在设置页禁用「启用」或要求用户二次确认，把 `setVerifiedSha` 真正接入 `resolve()`/推荐逻辑；文档同步。

---

#### P1-4 知识库导入有数据丢失风险：先 `clearAllTables()` 再分表写、无事务无回滚、zip 全量读内存

- **【级别】** P1（数据丢失）
- **位置**：`app/src/main/java/com/zhibox/memory/core/backup/KnowledgeBackup.kt:287-350`（尤其 `:297` `db.clearAllTables()`）、`:198-227`（`readAll`）
- **证据**：`importZip()` 顺序为 `readAll(zip)` → `buildPreview()` → **`db.clearAllTables()`（:297）** → 逐表 `upsertAll/insertAll`（source/document/chunk/chunkFts/embedding 多次独立写）。清库与写入不在同一个 Room 事务中，也没有导入前自动导出快照；任意一次写入异常（磁盘满、约束冲突、映射时 JSON 字段缺失、大包 OOM）都会留下「旧库已清空、新库写了一半」的状态。`readAll()` 对 `meta.json`、`sources.jsonl`、`vectors.bin` 等 entry 一律 `zip.readBytes()`（`:198-227`）——**无单 entry/总解压尺寸上限**，损坏或恶意 zip 可导致 OOM。
- **影响**：用户点击「导入」后，一旦中途失败，既有全部知识库（可能数月积累）不可恢复（除非此前手动导出过）；恶意 zip 可让应用 OOM 崩溃（导入是 SAF 任意文件选择，来源不可信）。
- **建议**（M）：用 `db.withTransaction { clear + 全部 insert }` 包裹（Room 支持嵌套 SQLite 事务）；导入前自动 `exportToFile(cacheDir/auto-backup-<ts>.zip)` 并保留最近一份；`importZip` 改为流式处理并设置 `MAX_ENTRY_BYTES`（如 256MB）/`MAX_TOTAL_BYTES`；对 `vectors.bin` 按 header 声明的 `count` 校验长度，防止构造 count 溢出。

---

#### P1-5 Room schema 2/3 未入库，迁移测试在 clean clone / CI 必然失败

- **【级别】** P1（测试/发布门禁阻断）
- **位置**：`git ls-files app/schemas`、`app/build.gradle.kts:46-49,66-68`、`app/src/androidTest/java/com/zhibox/memory/data/db/MigrationTest.kt:71,124,136,189`
- **证据**：磁盘上 `app/schemas/com.zhibox.memory.data.db.AppDatabase/{1,2,3}.json` 三个都在（20,189 / 20,579 / 21,610B），但 **git 只跟踪 `1.json`**；`2.json`、`3.json` 是未跟踪文件。`app/build.gradle.kts:46-49` 把整个 `app/schemas` 作为 androidTest assets；`MigrationTest.kt:71` `runMigrationsAndValidate(testDb, 2, ...)` 需要 assets 里有 `2.json`，`:136` 需要 `3.json`，`:189`（1→3 链式）需要 2/3。AppDatabase 当前 `version = 3`（`AppDatabase.kt:26`）。
- **影响**：其他机器 / CI 全新 clone 后，androidTest 因缺少 schema 资源直接失败（或 Room 无法校验迁移），迁移测试形同虚设；schema JSON 是 Room 迁移的唯一权威口径，丢失后无法审计 v2/v3 结构。
- **建议**（S）：提交 `2.json`、`3.json`（理想做法是引入 `androidx.room` Gradle Plugin 的 `room { schemaDirectory("$projectDir/schemas") }` 并纳入 CI 校验）；在 CI 加一步「若 `AppDatabase.version` 与 schemas 最大版本不一致则失败」。

---

#### P1-6 无 CI / 无静态检查 / 测试只覆盖纯逻辑，androidTest 强依赖 arm64 真机

- **【级别】** P1（质量门禁）
- **位置**：仓库根（无 `.github/`、无 detekt/ktlint/editorconfig/lint 配置）、`app/src/test/**`、`app/src/androidTest/**`、`app/build.gradle.kts:20-22`
- **证据**：`find . -maxdepth 3 -name '*.yml' -o -name 'detekt*' -o -name '.editorconfig'` → 无结果；无 `.github`。测试结构：`src/test` 3 文件 388 行 34 用例，全部是纯 JVM 逻辑（`CandidateFusionTest`、`ModelCatalogTest`、`TextLayerTest`），**没有** ModelDownloader（断点/416/SHA 失败）、SharedModelStore、KnowledgeBackup、ReembedWorker、SearchService、任何 Compose UI 测试；`androidTest` 仅 `MigrationTest` 3 用例且依赖真机（`abiFilters += "arm64-v8a"`，APK `native-code: 'arm64-v8a'` → x86_64 模拟器缺 native so 无法运行）；`coroutines-test` 在 `testImplementation`/`androidTestImplementation` 都声明了，但测试里 `grep runTest` 无使用。项目无 `lint { abortOnError }` 之类门禁，R8/资源/权限回归只能靠人。
- **影响**：任何重构（尤其 P1-2 迁移 benchmark、P1-3 校验改造）都缺乏自动回归；迁移/权限/安全问题只有真机手工验收才能发现；CI 缺失导致「本机能跑」成为唯一标准。
- **建议**（M）：GitHub Actions（或等价）跑 `assembleDebug` + `testDebugUnitTest` + `lint` + detekt；androidTest 用自托管 arm64 真机或 Firebase Test Lab（同时接受「无设备则跳过」并显式记录）；为 `ModelDownloader` 抽出 `ConnectionFactory`/`InputStream` 可注入点 + Robolectric 或本地 HTTP 桩测试；补 `detekt`/`ktlint` 与 `.editorconfig`（`kotlin.code.style=official` 目前只是声明，无强制）。

---

#### P1-7 依赖健康：全局 force hack、BOM/库版本陈旧且无更新与校验机制

- **【级别】** P1（可维护性/供应链）
- **位置**：`app/build.gradle.kts:70-78`、`gradle/libs.versions.toml:2-19`、`gradle/`（无 verification metadata / lock）
- **证据**：
  - `app/build.gradle.kts:74-78` `configurations.all { resolutionStrategy { force("com.google.ai.edge.litertlm:litertlm-android:0.18.0") } }` 对整个项目所有 configuration（含 debug/unitTest/androidTest）无条件强制；虽然 `:70-73` 注释解释了原因（tasks-retrieval 1.1.1 POM 传 0.17.0-alpha1 缺 `ActivationDataType`），但没有对应测试/断言锁定该约束，未来 tasks-retrieval 升级会静默把版本按回 0.18.0。
  - `libs.versions.toml:7` Compose BOM `2025.01.00` 至本次审查日期（2026-10）已约 21 个月，本机 `~/.gradle/caches/modules-2/files-2.1/androidx.compose/compose-bom/` 中已有 `2025.09.00`（说明更新可得未采用）；`coreKtx 1.15.0` / `lifecycle 2.8.7` / `activityCompose 1.9.3` 同为 2024-2025 代版本；两个 MediaPipe 制品版本独立（`tasks-retrieval 1.1.1` vs `tasks-text 0.10.35`，无 BOM/约束对齐）。
  - 无 Renovate/Dependabot/`dependencyUpdates` 任务；`gradle/verification-metadata.xml`、`gradle/dependency-locks` 均不存在；`roomTesting = "2.8.5"` 与 `room = "2.8.5"` 重复维护同一版本号。
- **影响**：升级窗口没有纪律，安全补丁/AGP-Kotlin-Room 兼容修复无法系统跟进；resolutionStrategy 全局 force 会在依赖升级时产生难排查的「版本被按回」；无依赖校验元数据意味着仓库/镜像被篡改无法发现（当前还从华为云镜像拉 Gradle，见 P2-2）。
- **建议**（M）：把 force 改为 `dependencies { constraints { implementation("...:0.18.0") { because("...") } } }` 或仅对 `runtimeClasspath/androidTestRuntimeClasspath` 生效，并在 `UPSTREAM_VERSIONS.md` 记 issue 链接；引入 `com.github.ben-manes:gradle-versions-plugin` 或 Renovate，每季度集中升级一次并跑真机基准；生成 `gradle/verification-metadata.xml`；`roomTesting` 复用 `version.ref = "room"`。

---

#### P1-8 文档与实现不一致（实测 7 处，含安全/权限声明）

- **【级别】** P1（安全声明失真 + 可复现性；文档是验收依据）
- **位置/证据**：

| # | 文档声明 | 实测 | 证据 |
|---|---|---|---|
| 1 | README「MVP 不申请任何权限（含网络）」 | Manifest 已声明 `INTERNET`，合并清单共 5 个权限 | `README.md:16` vs `AndroidManifest.xml:9`、aapt2 badging（INTERNET/WAKE_LOCK/ACCESS_NETWORK_STATE/RECEIVE_BOOT_COMPLETED/FOREGROUND_SERVICE） |
| 2 | `MVP1-ACCEPTANCE.md`「manifest 仅 INTERNET」 | 合并清单 5+1 个权限（含 WorkManager 带入项） | `docs/phase1/MVP1-ACCEPTANCE.md:18` vs aapt2 badging |
| 3 | README「本机 Gradle 8.11」 | wrapper 8.14.3 | `README.md:10` vs `gradle/wrapper/gradle-wrapper.properties:3` |
| 4 | README「当前状态：Phase 0 · P0-1 完成」 | 已进入 Phase 1 收尾（MVP-1 验收文档、5 屏 UI 均在） | `README.md:5` vs 工作区状态 |
| 5 | UPSTREAM「Android Gradle Plugin 8.9.1」 | 版本目录 8.13.2 | `docs/UPSTREAM_VERSIONS.md:19` vs `libs.versions.toml:2` |
| 6 | UPSTREAM「270M 157MB (164,892,672B 级)」 | 实际 164,626,432B（差 266,240B；SHA 与 catalog 均正确，仅文档数字错） | `docs/UPSTREAM_VERSIONS.md:33` vs `ls -l models/embeddinggemma-2-text-270m.litertlm` |
| 7 | `gen_eval_corpus.py`「40 张知识卡 + 200 条查询」；`run_eval.sh`「45 卡语料 / 220 条」 | 实际 50 卡 / 262 条（fact 50、synonym 50、multihop 50、exact 50、numdate 20、adversarial 42） | `scripts/gen_eval_corpus.py:3`、`scripts/run_eval.sh:2,12` vs `scripts/eval/evalset.json` 统计 |

- **影响**：验收依据失真——读者以为应用无网络/无权限，实际已引入下载器与 WorkManager 权限；评测报告的样本量、版本号复现口径与脚本注释不符，跨人复现时易误判。
- **建议**（S）：逐条同步文档；把「版本号/权限声明/数据集规模」改成脚本可校验项（CI 中 `grep` 对照 `libs.versions.toml`、`aapt2 badging`、`evalset.json` 统计），避免再次漂移；Phase 状态改为由 `docs/phase1/MVP1-ACCEPTANCE.md` 单一来源引用。

---

#### P1-9 实验脚本重复度高、少量硬编码，评测流程可复现性依赖未入库数据

- **【级别】** P1（可维护性/可复现性）
- **位置**：`scripts/*.sh`（11 个 / 796 行）、`scripts/score_eval.py`、`scripts/gen_eval_corpus.py`、`scripts/eval/`
- **证据**：
  - 11 个脚本各自重复同一段头：`ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"`（`grep -l 'ADB=' scripts/*.sh` = 11 处，如 `run_eval.sh:7`、`run_g1c.sh:7`、`run_thermal.sh:6`）；`PKG=com.zhibox.memory` / `APK=app/build/...` 样板 8+ 处；`am start -n ... --ez autostart_*` 13 处；轮询/拉取结果逻辑（`run_eval.sh:44-100`、`run_thermal.sh:64-100`）重复实现。
  - 硬编码：固定 `/tmp/evaldb`（`run_eval.sh:19,21,52`，并发跑多设备会互相覆盖）；固定 `sleep 150` 等待 G1C（`run_g1c.sh:39`）；`sqlite3` CLI 依赖未在 README/脚本中声明；`ANDROID_HOME` 缺省回退到 macOS 专属路径。
  - `score_eval.py` 有死代码与未使用输出：`metrics()`（`:19-46`）与内联 `mm()`（`:82-108`）重复实现同一套指标；`lat = defaultdict(list)`（`:61`）采集后未出现在报告中；`sys.argv[1]` 无 usage 保护（`:51`，直接 IndexError）。
  - 可复现性：`scripts/eval/corpus`（50 文件 244KB）与 `scripts/eval/evalset.json`（41KB）均未入库；`docs/phase1/` 下 66 个结果文件 2.8MB 也未入库且未 ignore——报告引用的数据在 clean clone 中无法复现。
  - 优点先记：11/11 脚本有 `set -euo pipefail`，`ANDROID_SERIAL` 均可参数化，未发现硬编码设备序列号（`grep serial` 只命中用法注释）。
- **影响**：脚本复制粘贴导致行为漂移（例如只有 `run_thermal.sh` 显式传 `-s $S`，其余依赖 adb 环境变量）；固定 sleep/临时目录带来 flaky 与并发冲突；评测结果无法从仓库复现。
- **建议**（M）：抽 `scripts/lib/common.sh`（选 adb、`require_device`、`install_apk`、`wipe_and_push_model`、`poll_db_ready`、`pull_results`、`DEV_TAG`）并让 11 个脚本只保留实验参数；`/tmp/evaldb` 改 `mktemp -d`；`sleep` 改轮询上限；README 增「评测脚本前置：adb、sqlite3、ANDROID_HOME」；`score_eval.py` 删死代码、argparse 化、输出 p50/p95 延迟；评测语料与结果明确入库策略（语料入库、结果 gitignore 或外部存储）。

---

### P2（改进项）

---

#### P2-1 网络安全策略未显式化：无 `networkSecurityConfig`、下载域名为第三方镜像且无 host 校验

- **【级别】** P2
- **位置**：`app/src/main/AndroidManifest.xml:11-16`、`app/src/main/java/com/zhibox/memory/core/model/ModelCatalog.kt:33-34`、`ModelDownloader.kt:95-101`
- **证据**：`res/xml/` 不存在（无 `network_security_config.xml`）；`ModelCatalog.kt:33-34` 全部模型 URL 指向 `https://hf-mirror.com/...`（第三方社区镜像，非官方 HF 域名）；`ModelDownloader.open()` 直接用 `URL(url)`，无 scheme/host 白名单（当前仅由 catalog 常量调用，属事实约束而非代码约束）。targetSdk 36 下明文 HTTP 默认已被系统阻断，但策略没有写进配置。
- **影响**：未来若新增下载源/重定向到明文地址，缺少显式防线；第三方镜像可用性/隐私（IP 暴露）与供应链风险无法在配置层审计；SHA-256 已能防内容篡改，但无法防「镜像不可信导致下载失败/被引导」。
- **建议**（S）：新增 `res/xml/network_security_config.xml` 显式 `cleartextTrafficPermitted="false"` + 域名白名单；`ModelDownloader` 校验 `url.startsWith("https://")` 且 host ∈ 允许集合；在 `UPSTREAM_WATCH.md` 记录镜像切换机制。

---

#### P2-2 Gradle 发行版来自第三方镜像且无 `distributionSha256Sum`

- **【级别】** P2（供应链）
- **位置**：`gradle/wrapper/gradle-wrapper.properties:3-5`
- **证据**：`distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-8.14.3-bin.zip`，未配置 `distributionSha256Sum`（`validateDistributionUrl=true` 只校验 URL 形态）；`docs/UPSTREAM_VERSIONS.md:18` 说明「本机网络环境下官方源不可达，其他环境可改回」。
- **影响**：构建链的第一个环节依赖第三方镜像，镜像污染/替换无法被 wrapper 校验；跨机器复现时若有人改回官方源，行为不可控。
- **建议**（S）：追加 `distributionSha256Sum=<官方 SHA>`；在 `UPSTREAM_VERSIONS.md` 记录该 SHA 与切换方法。

---

#### P2-3 ModelCatalog 维护性：死代码、维度/名单口径分散、SOC 归一化受 locale 影响

- **【级别】** P2
- **位置**：`ModelCatalog.kt:109,134-135,157,167-168`、`IngestRepository.kt:299`
- **证据**：`isVerifiedForNpu()` 恒 `false`（`:167-168`），`recommend(PERF_FAST)` 的 NPU 分支永远走 fallback，属死代码；LFM2.5 声明 1024d（`:109`）而 `IngestRepository.EXPECTED_DIM = 768`（`IngestRepository.kt:299`），当前靠 `catalogDims` 兜底但两套维度口径并存易踩坑；`normalize()` 使用无 Locale 的 `lowercase()`（`:135`），土耳其语等 locale 下 `I→ı` 会导致 SOC 标签匹配失败；`socTags` 仅 `qualcomm_sm8750`，`socMatches` 用双向 `contains`，`SM8650` 等会命中 `qualcomm_sm8750`？——经测试用例 `socMatches(e,"qualcomm_sm8650") == false` 已覆盖，暂无问题。
- **影响**：维护者容易误信「验证门/维度校验存在」；locale 极端场景下推荐退化。
- **建议**（S）：删除或实现 `isVerifiedForNpu`；把维度校验统一到 catalog 单一来源；`lowercase(Locale.ROOT)`；为 `normalize` 增加非拉丁/低端设备用例。

---

#### P2-4 ModelDownloader 边界条件可再收紧（Range 被忽略时整包重下才正确）

- **【级别】** P2（浪费流量/健壮性）
- **位置**：`app/src/main/java/com/zhibox/memory/core/model/ModelDownloader.kt:41-50,53-58,85-92`
- **证据**：续传时服务端可能忽略 `Range` 返回 `200`（代码未检查 `responseCode == 206`），此时会以追加方式写 `.part`，得到损坏文件——`expectedSha256` 校验会兜底删除并报错（用户需整包重下）；`total = contentLengthLong + resumeFrom`（`:43`）在 200 响应下会把 `resumeFrom` 重复相加，进度条可超过 100%；无「下载体积上限」防御（依赖 catalog 常量，风险低）；`target.parentFile` 为空时 NPE（内部调用不会触发）。
- **影响**：弱网/镜像不支持 Range 时，第一次续传必然失败重下，浪费几百 MB 流量；进度显示异常。
- **建议**（S）：`resumeFrom > 0` 时要求 `206 + Content-Range` 校验，否则删 `.part` 从 0 开始；`total` 只在 206 时加 `resumeFrom`；对 200 且 `resumeFrom>0` 直接重置。

---

#### P2-5 SharedModelStore 共享目录操作的边界风险

- **【级别】** P2
- **位置**：`app/src/main/java/com/zhibox/memory/core/model/SharedModelStore.kt:40-57,136-147,158-196`、`SettingsScreen.kt:143-147`
- **证据**：① `clearAll()` 会删除所选 SAF 目录内**所有** `.litertlm`/`.task` 文件（`:136-147`，非仅 catalog 条目），若用户选了包含其他模型的目录会误删；② 下载到一半失败时共享目录可能已有同名残缺文件，`downloadEntry()` 因长度不等继续下载，`r.createFile()`（`:183`）在多数 provider 下会生成 `name (1)` 而非覆盖，旧残缺文件残留；随后 `enable()` 取 `listFiles().firstOrNull { it.name == fileName }`（`:102`）可能命中残缺副本；③ `setTreeUri()` 内 `takePersistableUriPermission` 未捕获 `SecurityException`（`:50-54`，调用点 `SettingsScreen.kt:143-147` 无 try/catch），部分 provider（云盘/部分 OEM DocumentsUI）不授予持久权限时会崩溃。
- **影响**：共享目录被污染后模型加载失败且难排查；误删用户文件；个别设备选目录即崩溃。
- **建议**（S）：`clearAll` 仅删 `PathPreferences.isKnownModel()` 命中项并二次确认；下载前删除/重命名同名残缺文件，或改用 `DocumentsContract.renameDocument`/先删后建；`setTreeUri` 包 `runCatching` 并向用户提示。

---

#### P2-6 硬编码中文 UI 文案，无 i18n 资源化

- **【级别】** P2
- **位置**：`app/src/main/java/com/zhibox/memory/ui/*.kt`、`MainActivity.kt:174-215`、`app/src/main/res/values/strings.xml`
- **证据**：`grep -rln 'Text("' app/src/main/java` = 7 个文件直接内联中文；`strings.xml` 仅 `app_name` 等少量资源（`MainActivity.kt:171` 用 `stringResource(R.string.app_name)`，其余界面文案全部硬编码）。
- **影响**：无法本地化、文案改动需改 Kotlin；与 Compose 资源规范不一致。
- **建议**（M，可延后）：文案迁 `strings.xml`，加 `localeConfig`。

---

#### P2-7 仓库卫生：`dist/`、评测结果、`.review` 未 ignore，计划文件入库；无 LICENSE

- **【级别】** P2
- **位置**：`.gitignore:1-35`、`.zcode/plans/plan-sess_28894d4b-f2e2-4b74-bb48-dd647f31e5a4.md`、`docs/reference/`、仓库根
- **证据**：`git status --porcelain -uall | grep -c '^??'` = **153**；`git check-ignore` 实测：`dist` → NOT-IGNORED、`docs/phase1/eval-results` → NOT-IGNORED、`scripts/eval` → NOT-IGNORED、`.review` → NOT-IGNORED；`dist/MVP-1/zhibox-mvp1-debug.apk` 62.4MB 属「`git add -A` 即入库」状态；`.zcode/plans/*.md`（9 行会话计划）已被跟踪；`docs/reference/zleap-sag-0.14.0/` 引入 39 个上游 MIT 源文件（452KB），仓库根与 reference 目录均无 LICENSE/NOTICE（`find docs/reference -iname 'LICENSE*'` 为空）。
- **影响**：一次误提交即把 62MB 二进制写入历史；评测结果/计划文件污染 diff；vendored MIT 代码缺许可证声明有合规风险。
- **建议**（S）：`.gitignore` 增加 `dist/`、`*.apk`、`docs/phase1/*-results/`、`docs/phase1/*/*.json`（按需）、`.review/`；确定 `scripts/eval/` 与 `docs/phase1` 的入库策略（语料入库、结果不入库）；移除 `.zcode/plans` 跟踪；补 `LICENSE` 与 `docs/reference/README` 中的 MIT 声明；若要保留大结果文件，引入 Git LFS 或外部制品库。

---

#### P2-8 交付产物不可追溯 / 无构建元数据

- **【级别】** P2
- **位置**：`dist/MVP-1/zhibox-mvp1-debug.apk`
- **证据**：APK 为 10-17 14:31 产物，与 `app/build/outputs/apk/debug/app-debug.apk` 同尺寸，但工作区仍有 947 行未提交改动（`git diff --stat`）——无法确认 dist 包对应哪个 commit；APK 内无 build-info（无 `BuildConfig`/git sha/versionName 之外的标识，`versionCode=1`、`versionName=0.1.0-mvp`）。
- **影响**：MVP-1 验收结论与二进制无法一一对应，复现/回滚困难。
- **建议**（S）：CI 构建时写入 git sha/构建时间（`buildConfigField` 或 assets `build-info.json`），dist 文件名带 tag 与 sha。

---

## 做得好的地方（建议保留并写进工程规范）

1. **依赖版本目录规范**：`gradle/libs.versions.toml` 集中 18 个 version / 22 个 library / 4 个 plugin；`app/build.gradle.kts:70-73` 对 litertlm 0.18.0 forcing 给出 AAR 反编译级证据注释——这是同类项目里少见的可追溯依赖决策。
2. **模型下载器设计扎实**：`ModelDownloader.kt:23-25` 超时、`:36-39` 磁盘预检、`:53-80` 流式 SHA-256（续传时先重算已有部分）、`:41-50` 416 处理、`:85-92` 校验失败删 `.part` + 校验后原子 rename；唯一调用方（`SharedModelStore.kt:178`）传入了 catalog SHA。
3. **离线/联网边界清晰**：唯一联网路径集中在 ModelCatalog/ModelDownloader/SharedModelStore；URL 全 HTTPS；Manifest 只显式声明 `INTERNET`（`AndroidManifest.xml:9` + 注释说明官方依据）；全项目 **0 处 `Log.`/`println`/`printStackTrace`**（grep 实测），无日志泄漏。
4. **嵌入空间隔离是结构性兜底**：`EmbeddingRuntime.spaceIdFor()`（`EmbeddingRuntime.kt:167-181`）把模型/GPU/NPU 拆成不同 spaceId，`SearchService.kt:69-86` 空间不匹配时**明确拒绝检索并列出已有空间**而不是混检；配合 `ReembedWorker`/WorkManager unique work——「换模型不重建向量」这个常见 RAG 数据事故被架构层防住了。
5. **Room 迁移被认真对待**：v1/v2/v3 导出 schema + `MigrationTest` 真实覆盖 1→2、2→3、1→3（`MigrationTest.kt:29,121,173`），`app/build.gradle.kts:46-49` 把 schema 作为 androidTest assets；只差 2/3 入库（P1-5）。
6. **脚本纪律好于平均**：11/11 脚本 `set -euo pipefail`；`ANDROID_SERIAL` 参数化、`ANDROID_HOME` 可覆盖；无硬编码设备序列号；脚本自带安装/清库/结果落盘并复用 `scripts/eval` 语料（可复现性只差语料入库）。
7. **资产可核验**：`models/SHA256SUMS` + `docs/UPSTREAM_VERSIONS.md` 记录 8 个模型 SHA/体积/来源；本次实测 270M 与 `.task` 的 SHA 与登记一致；`models/`、`.venv-g1/`、`.hf-cache/`、`local.properties`、keystore 均已被 `.gitignore` 覆盖（`.gitignore:14-35`），git 历史最大 blob 仅 304KB，无大二进制入库历史。
8. **发布/调试取舍有意识**：`MainActivity.kt:42-49` 用 `FLAG_DEBUGGABLE` 而非 BuildConfig 控制 StrictMode；`debugImplementation(libs.androidx.ui.tooling)`（`app/build.gradle.kts:110`）区分 tooling；`android.nonTransitiveRClass=true`、`kotlin.code.style=official` 等基础设置到位。

---

## 附：建议修复顺序

1. **发布前必须**：P0-1（签名 + release 变体）、P1-1（allowBackup）、P1-2（实验代码隔离）。
2. **进入 Phase 1 收尾前一迭代**：P1-3（模型 SHA 校验 + 验证门）、P1-4（导入事务/快照）、P1-5（schema 入库）。
3. **持续工程化**：P1-6（CI/lint）、P1-7（依赖策略）、P1-8（文档同步）、P1-9（脚本公共库）。
4. **顺手清理**：P2-1/2/3/4/5/6/7/8。
