# B4 修复报告（P2 批次 B4：工程化/发布/依赖）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B4（9 项：B4-1a/1b/1c、B4-2、B4-3、B4-4、B4-5、B4-6、B4-7、B4-8、B4-9）
- **执行方式**：build-fixer（构建/依赖/发布）+ core-fixer（SHA/维度/Locale）+ Lead（LICENSE/仓库卫生/取证文档/CI 评估/门禁/依赖冲突修复）→ 全量 strict 门禁 → 真机 smoke → 双轴独立审查
- **用户决策**：允许依赖升级窗口；**不 provision release 签名**（脚本仅支持外部密钥注入）；LICENSE 选择 **MIT**；保留 phase1 结果；Robolectric 属 B5

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B4 完成度 | **9/9 项代码完成**；其中 **B4-8 未完成**（无 git remote，CI 首绿无法产生，外部阻塞）；B4-1c/B4-2/B4-6 的 CI/实测验证依赖外部环境，已如实标注 |
| JVM 测试 | **235 tests / 0 failed**（debug；release unit 215 tests 同步绿） |
| 依赖校验 | **strict 模式（不带 `--write`）全绿**：`gradle/verification-metadata.xml` 565 components（另手工补 CI ubuntu 所需 aapt2 linux jar 校验和） |
| Lint | **0 errors / 25 warnings**（`abortOnError=true`） |
| 构建 | `assembleDebug` / `assembleRelease`（R8）✅；release 无 `application-debuggable` |
| 依赖升级 | Compose BOM 2025.01.00→**2026.06.01**、core-ktx 1.15.0→**1.18.0**、lifecycle 2.8.7→**2.10.0**、activity-compose 1.9.3→**1.13.0**、tasks-text 0.10.35→**1.1.1** |
| 真机 instrumentation | **28 / 28 PASS**（升级后复跑；含 MigrationTest 全链） |
| 真机检索 smoke | 升级后 220 查询：**0 refused / 0 error**、p50=51ms、p95=66ms（基线 49/63ms，可接受波动） |
| 发布脚本实测 | `scripts/release.sh` 真实 unsigned 端到端：`dist/zhibox-0.1.0-mvp-9ca20d6-dirty-release.apk` + `.sha256`，aapt2 校验非 debuggable |
| 可追溯 | debug 与 **release** APK dex 均含 `BUILD_GIT_SHA` / `BUILD_TIME`（审查后加 R8 keep 规则验证通过） |
| 仓库卫生 | LICENSE（MIT）+ THIRD_PARTY_NOTICES；`.zcode/plans` 已取消跟踪并 ignore |
| 发布链路 | `scripts/release.sh`（可选外部签名 + verify + dist 命名 + sha256）；`docs/RELEASE.md` |
| 独立审查 | Standards + Spec 双轴（结果与处置见第 6 节） |

---

## 2. 逐项完成说明

### B4-1a 依赖约束替代全局 force
- 删除全局 `configurations.all { resolutionStrategy.force("...litertlm-android:0.18.0") }`，改为 `dependencies { constraints { implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0") { because(...) } } }`；保留 ActivationDataType 缺失证据注释。
- constraint 仅作用于 implementation 依赖图（debug/release/unitTest 继承），不再全局强制所有配置。

### B4-1b 依赖升级窗口（含实测兼容上限）
- 目标版本先按最新稳定探测，但 AAR metadata 实测显示三个目标要求 **compileSdk 37 + AGP 9.1**（与当前 AGP 8.13.2/compileSdk 36 不兼容），故取各版本线**最后一个兼容版本**：

| 依赖 | 升级前 | 实现版本 | 说明 |
|---|---|---|---|
| Compose BOM | 2025.01.00 | **2026.06.01** | 目标 2026.09.00 需 compileSdk 37/AGP 9.1 |
| androidx.core:core-ktx | 1.15.0 | **1.18.0** | 目标 1.19.1 需 37/9.1 |
| androidx.lifecycle | 2.8.7 | **2.10.0** | 目标 2.11.0 需 37/9.1 |
| androidx.activity:activity-compose | 1.9.3 | **1.13.0** | 满足 36/8.9.1 |
| mediapipe tasks-text | 0.10.35 | **1.1.1** | 与 tasks-retrieval 1.1.1 对齐；javap 验证 API 源码兼容（GraphExecutor 路径不注入 TextFormatContext） |
| （新增）material-icons-core | — | BOM 管理 | 升级后 material3 不再传递 icons；UiCommon 使用 Icons.AutoMirrored |
| （新增）kotlinx-serialization-core | — | 1.8.1 | room-testing 2.8.5 的 GeneratedSerializer 需 1.8.1；lifecycle 2.10 把 app runtime 约束在 1.7.3，consistent resolution 导致 androidTest 无法升级，故放 implementation 并注释 |

- 未升 AGP/Kotlin/KSP：AGP 8.x 已为最新线（9.x 需整体迁移+compileSdk 37）；Kotlin 2.5.0 与 KSP 2.3.12 未配对。原因写入 `libs.versions.toml` 顶部注释。
- **升级回归修复**：升级后 androidTest 的 `MigrationTestHelper` 抛 `AbstractMethodError: GeneratedSerializer.typeParametersSerializers` → 定位为 kotlinx-serialization 1.7.3/1.8.1 混用，按上表修复后 28/28 通过。

### B4-1c 依赖校验（verification metadata）
- 生成并提交 `gradle/verification-metadata.xml`（565 components），覆盖 assemble/unitTest/lint/androidTest 配置。
- 生成/更新命令写入 `app/build.gradle.kts` 注释：`./gradlew --write-verification-metadata sha256 :app:assembleDebug ...`；临时绕过 `--dependency-verification=off`；不启用时删除文件的替代方案已说明。
- 验证：**不带 `--write` 的 strict 构建全绿**。

### B4-2 Gradle wrapper 完整性
- `gradle-wrapper.properties` 增加 `distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`（Gradle 8.14.3 官方 sha256）；保留华为云镜像 URL，注释说明官方源切换与换版本时同步更新 sha 的方法。

### B4-3 ModelCatalog 维度单一来源 + Locale.ROOT
- `ModelCatalog.DEFAULT_DIMS = 768` + `dimsFor(fileName)`（已收录走 catalog dims，含 LFM 1024d；未收录回退 768）；删除 `IngestRepository.EXPECTED_DIM`（全仓 0 引用）。
- `lowercase()/uppercase()` 统一 `Locale.ROOT`：ModelCatalog、ModelIntegrity、KnowledgeBackup（backup-fixer 协作）、SearchService；补土耳其 locale 测试（`Locale.setDefault(tr)` 下 `normalize("INTEL SM8750") == "intel_sm8750"`）。
- 单测：LFM dims=1024、未知/空文件名回退 768、locale 隔离。

### B4-4 构建可追溯
- `buildFeatures.buildConfig = true`；`BUILD_GIT_SHA`（`providers.exec git rev-parse --short=7`，失败回退 `unknown`）与 `BUILD_TIME`（ISO-8601 UTC，支持 `SOURCE_DATE_EPOCH` 可复现）进入 BuildConfig。
- APK 内已验证字段存在（dex 字符串）与构建时间示例值；release 的 R8 裁剪风险与对外追溯口径（versionName + git sha + 归档 sha256）写入注释与 `docs/RELEASE.md`。

### B4-5 仓库卫生与许可证
- 新增根 `LICENSE`（**MIT**，用户决策）与 `docs/reference/THIRD_PARTY_NOTICES.md`（AndroidX/Kotlin/MediaPipe/LiteRT-LM Apache-2.0、jieba MIT debug-only、org.json Public Domain test-only、模型资产声明）。
- `.zcode/plans/*.md` 取消跟踪（`git ls-files .zcode` 为空），`.zcode/` 加入 `.gitignore`；已跟踪结果文件按用户决策保留。

### B4-6 发布链路
- 新增 `scripts/release.sh`（755）：`assembleRelease` → 若提供 `ANDROID_KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD` 则 zipalign+apksigner 签名（口令经 `env:` 传递，不落盘/不出现在 argv/日志）→ `apksigner verify --print-certs` → aapt2 检查（debuggable 直接失败）→ 归档 `dist/zhibox-<versionName>-<gitSha7>[-dirty]-<variant>[-signed].apk` + `.sha256`；无密钥则保留 unsigned 并醒目告警。
- 新增 `docs/RELEASE.md`：密钥 provision（用户自备，不入库）、构建/产物命名、依赖校验、可追溯、OnePlus 安装增强防护 3 步引导 + adb 兜底、验收清单、回滚说明。
- 遵守"不 provision 签名"决策：仓库无密钥；release 产物为 unsigned。

### B4-7 debug/release 取证口径
- `docs/review/DEVICE_ACCEPTANCE.md` 新增附录：debug（run-as 读私有目录、autostart EXTRA、StrictMode 日志）vs release（不可 run-as、不解析实验 extra、无 StrictMode、UI/logcat/SAF 取证）差异 + 发布验收最低清单。

### B4-8 CI 首次验证（外部阻塞）
- `ci.yml` 已通过 YAML 语法校验；本地等价门禁（assembleDebug + testDebugUnitTest + lintDebug + androidTest 编译）在 strict 依赖校验下全绿。
- **无 git remote / 未 push**，无法产生 GitHub Actions 首次绿灯记录；`docs/RELEASE.md`/报告已记录"首次 push 后验证 CI；若镜像不可达切换官方源"的操作说明。此项标记为**外部阻塞**，待用户提供远端后执行。

### B4-9 SHA-256 三实现统一
- 新增 `core/model/Sha256.kt`：`hex(ByteArray)`、`hex(File)`（64KB 流式）、`hex(InputStream)`（不关闭，调用方管理）、`newDigest()` + `hex(MessageDigest)` 增量入口；输出小写 hex。
- `ModelIntegrity.sha256Hex(File/InputStream)`、`ModelFingerprint` 内部实现、`IngestRepository.sha256HexFile` 全部委托 `Sha256`（对外 API 不变）；`MessageDigest` 实现全 main 仅剩 `Sha256.kt` 一处。
- 单测 7 例：FIPS 向量 "abc"/空输入、1MB 流式与直算一致、InputStream 不被关闭、增量 digest、两处委托结果一致。

---

## 3. 验证证据

### 3.1 strict 依赖校验 + 全量门禁

```text
$ ./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
            :app:testReleaseUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL（不带 --write；verification-metadata strict 生效）
JVM debug = 235 tests / 0 failed；release unit = 215 tests（全部通过）
lint = 0 errors / 26 warnings
verification-metadata = 565 components
```

### 3.2 真机（OnePlus PJZ110 / Android 16 / arm64，升级后）

| 项 | 结果 |
|---|---|
| `connectedDebugAndroidTest` | **28 / 28 PASS**（含 MigrationV1→V5 全链、孤儿清理、空间采纳、备份 fixture、导入去重、observeRecent；升级引入的 serialization 冲突修复后复跑通过） |
| 真机检索 smoke（220 查询） | 0 refused / 0 error；p50=51ms、p95=66ms（升级前 49/63ms） |
| release 包属性 | `release/app-release-unsigned.apk` 64,548,559 B；`aapt2 badging` **无 application-debuggable** |
| BuildConfig | APK dex 中可读到 `BUILD_GIT_SHA` / `BUILD_TIME`（`2026-10-17T13:24:23Z`） |
| 依赖解析 | activity-compose 1.13.0、core-ktx 1.18.0、lifecycle 2.10.0、Compose BOM 2026.06.01、tasks-text 1.1.1 生效 |

### 3.3 发布脚本
- fake toolchain 实测 6 种路径：unsigned release / signed release / debug / 缺密钥参数 / 未知参数 / `--help`（退出码与行为符合预期）；未在仓库/日志留下密钥。

---

## 4. 计划偏差记录

| # | 计划原文 | 实际实现 | 原因/影响 |
|---|---|---|---|
| 1 | B4-1b 升到最新稳定（Compose BOM 2026.09 等） | 取兼容上限（BOM 2026.06.01 / core 1.18.0 / lifecycle 2.10.0） | 最新版本要求 compileSdk 37+AGP 9.1；整体升级需独立批次（含 AGP 9/Kotlin/compileSdk 迁移） |
| 2 | B4-1b "AGP 小版本" | AGP 保持 8.13.2 | 8.x 已最新；9.x 为大版本迁移，不在本批风险预算内 |
| 3 | B4-1b 未提 serialization | 新增 `implementation(kotlinx-serialization-core:1.8.1)` | 升级后 room-testing 迁移测试 AbstractMethodError；仅测试需要但受 consistent resolution 限制只能放 implementation（APK 已含 serialization-core，体积影响小） |
| 4 | B4-1c "CI 依赖校验通过" | 本地 strict 门禁通过；metadata 补 aapt2 linux；CI 仍未实跑 | 无 git remote；CI 首跑可能仍因平台其他 artifact 缺校验和失败，需首次 push 后按报告处理 |
| 5 | B4-4 "assets 或 BuildConfig" | 仅 BuildConfig | 无 assets 注入任务，避免无法跑 Gradle 时引入自定义 Task 风险；R8 裁剪口径已文档化 |
| 6 | B4-5 "vendored MIT 声明" | LICENSE=MIT（用户确认）+ 第三方 NOTICE | 符合决策 |
| 7 | B4-6 "按文档产出 signed release" | 脚本支持外部密钥签名；仓库未 provision 密钥 | 遵守用户"不 provision 签名"决策；unsigned 分支可产出并验签流程已备 |
| 8 | B4-2 "wrapper 校验通过" | 写入官方 sha256（已与 Gradle 官方 8.14.3 值核对）；未实测镜像包下载校验 | 镜像与官方包同源；若镜像返回不同字节校验会失败（符合预期保护）；CI 侧可加 `gradle/actions/wrapper-validation`（已加入 workflow） |
| 9 | 未计划 | APK 体积：debug 62.5→83.8MB、release 48.6→64.5MB | tasks-text 1.1.1 新增 genai native so（~15.5MB）+ serialization-core；已记录，若不可接受可评估 jniLibs excludes（需真机确认 Path C 不用） |
| 10 | 未计划 | release 加 R8 keep 规则保留 BuildConfig 字段 | B4-4 验收要求 APK 内可读；keep 后 release dex 已实测含两字段 |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 依赖未升到最新 | 最新 AndroidX 需 compileSdk 37 + AGP 9.1；当前 36/8.13.2 取兼容上限 | 建议独立批次完成 AGP 9 + compileSdk 37 + Kotlin/KSP 配对升级，并全量回归 |
| 2 | CI 未实跑 | 无 git remote；首次 push 后需观察 Actions（含镜像可达性、verification metadata 平台差异） | 用户提供远端后执行；文档已写切换官方源步骤 |
| 3 | APK 体积 +22%/+33% | tasks-text 1.1.1 的 genai native so | 记录；如产品不可接受评估 `packaging.jniLibs.excludes`（LiteRT-LM 路径未用时） |
| 4 | ~~BuildConfig 在 release 可能被 R8 裁剪~~ | **已修复**：proguard keep 规则保留两字段，release dex 实测可读 | 保留 keep 规则即长期生效；R8 规则变更时注意回归 |
| 5 | verification-metadata 维护成本 | 每次新增/升级依赖需重新生成；CI 首次可能因其他平台 artifact 缺 checksum 失败 | 更新命令已写全配置；已补 aapt2 linux；若仍失败在 Linux 重生成 |
| 6 | Path C 运行时未真机验证 | tasks-text 升级到 1.1.1 后 GraphExecutor 对旧 `.task` 的兼容性需带模型真机验证（设备无 embedding_gemma.task） | 记入 B5/后续真机验收；升级 API 已静态比对兼容 |
| 7 | Kotlin 2.5.0 未升 | KSP 2.3.12 未配对；Gradle 8.14.3 对 KGP 2.5 已提示弃用阈值 8.14.4 | 独立依赖窗口处理 |
| 8 | release 为 unsigned | 用户决策不 provision 签名 | 脚本支持外部密钥；交付前按 RELEASE.md 签名 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | verification-metadata 头部、build.gradle、RELEASE.md 仍称"模板占位/普通构建会失败"，与已生成 565 components + strict 全绿事实相反 | **已修**：三处注释改为"已生成、strict 生效、更新命令与平台差异" |
| S2 | 硬性 | 再生成命令只跑 `:app:assembleDebug`，漏 unitTest/lint/androidTest 专用 artifact，下次全量门禁会失败 | **已修**：命令改为覆盖 assembleDebug/Release、testDebug/ReleaseUnitTest、lintDebug、compileDebugAndroidTest；并注明"只跑 assembleDebug 会漏" |
| S3 | 硬性 | serialization 注释称"仅测试类路径，不进入 APK"与 implementation 矛盾；版本字面量绕过 catalog | **已修**：注释改为"会进入 APK（约 1MB），用于绕过 consistent resolution"；版本移入 `libs.versions.toml`（kotlinxSerialization=1.8.1） |
| S4 | 硬性 | THIRD_PARTY_NOTICES 把 jieba 标 MIT（上游为 Apache-2.0）；新增 serialization 依赖未入表 | **已修**：jieba → Apache-2.0；org.json → JSON License（test-only）；新增 serialization-core 行 |
| S5 | 判断性 | `ModelCatalog` 仍有 `dims == 768` 硬编码 | **已修**：改为 `DEFAULT_DIMS` |
| S6 | 判断性 | build.gradle 配置期 git 注释"绝不阻塞"不实；BUILD_TIME 每次变化影响增量 | **已修**：BUILD_TIME 改为 SOURCE_DATE_EPOCH → git commit 时间 → now 兜底；注释说明 |
| S7 | 判断性 | UPSTREAM 文档仍写 `resolutionStrategy.force` / `EXPECTED_DIM` | **已修**：UPSTREAM_VERSIONS/UPSTREAM_WATCH 同步为 constraints / `ModelCatalog.dimsFor` |
| S8 | 判断性 | release.sh 仅凭旧 `app-release.apk` 存在判"已签名"，可能归档旧产物 | **已修**：构建前删除 release APK 产物，避免 UP-TO-DATE 旧包误用 |
| S9 | 判断性 | ci.yml 使用可变 `@v4`、无 wrapper-validation | **已修（部分）**：加入 `gradle/actions/wrapper-validation@v4`；action 仍为可变 tag（后续可 pin SHA，记入风险） |
| S10 | 判断性 | DEVICE_ACCEPTANCE 表格内裸 `|` 导致列错位；locale 测试全局 setDefault | **已修**：表格改用文字描述管道；locale 测试 try/finally 恢复，串行下可接受 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B4-1a | implemented | 确认（constraints 0.18.0；metadata 仅 0.18.0） |
| B4-1b | implemented | 确认（两 MediaPipe 同 1.1.1；28/28 与检索 smoke 通过；Path C 运行时未验证已记 §5#6） |
| B4-1c | partial（CI 未跑） | **改善**：补 aapt2 linux 校验和；本地 strict 绿；CI 首跑仍属外部验证（B4-8） |
| B4-2 | partial（未实测下载） | 接受：sha 与官方值核对；镜像同源假设已记录；CI 加 wrapper-validation |
| B4-3 | implemented | 确认；额外补 SearchService Locale.ROOT |
| B4-4 | **wrong（release 被 R8 裁剪）→ 已修复** | 加 proguard keep；release dex 实测含 BUILD_GIT_SHA/BUILD_TIME |
| B4-5 | implemented | 确认（LICENSE MIT/NOTICE/.zcode 0 跟踪） |
| B4-6 | partial（未真实签名） | **改善**：真实 unsigned 端到端跑通（dist 命名+sha256）；签名路径需外部密钥（用户决策不 provision） |
| B4-7 | implemented | 确认 |
| B4-8 | partial → **未完成（外部阻塞）** | 无 git remote；本地等价门禁绿；如实标注，待用户提供远端后首跑 |
| B4-9 | implemented | 确认（main 唯一 MessageDigest；FIPS/流式/不关闭测试） |
| 范围/兼容 | icons-core/serialization 合理；体积 +16~22MB 已记录；EXTRA/搜索/备份未变；MIT 用户确认 | 确认 |

### 6.3 审查后复验（最终）

- `assembleDebug / assembleRelease / testDebugUnitTest / testReleaseUnitTest / lintDebug / compileDebugAndroidTestKotlin`：**strict 依赖校验下全绿**
- JVM：debug **235 tests / 0 failed**；release unit 215 tests 全绿
- Lint：0 errors / 25 warnings
- release dex：`BUILD_GIT_SHA` / `BUILD_TIME` 可读（keep 规则生效）
- `scripts/release.sh` unsigned 端到端：生成 `dist/zhibox-0.1.0-mvp-9ca20d6-dirty-release.apk` + `.sha256`
- 真机（升级后）：instrumentation 28/28；检索 smoke 0 refused/0 error

---

## 7. 文件清单（B4 相对 B3 终态）

**构建/依赖**：`gradle/libs.versions.toml`、`app/build.gradle.kts`（constraints/版本/buildConfig/serialization 修复/verification 说明）、`gradle/wrapper/gradle-wrapper.properties`、`gradle/verification-metadata.xml`（新，565 components）
**发布**：`scripts/release.sh`（新，755）、`docs/RELEASE.md`（新）
**代码治理**：`core/model/Sha256.kt`（新）、`core/model/ModelCatalog.kt`、`core/model/ModelIntegrity.kt`、`core/embedding/ModelFingerprint.kt`、`core/embedding/EmbeddingRuntime.kt`（dims）、`core/ingest/IngestRepository.kt`（SHA/dims）、`core/backup/KnowledgeBackup.kt`（Locale）、`core/search/SearchService.kt`（Locale）、`core/embedding/TextEmbedderProvider.kt`（注释）
**许可证/仓库**：`LICENSE`（新，MIT）、`docs/reference/THIRD_PARTY_NOTICES.md`（新）、`.gitignore`、取消跟踪 `.zcode/plans/*`
**文档**：`docs/review/DEVICE_ACCEPTANCE.md`（B4-7 附录）
**审查后修复**：`gradle/verification-metadata.xml`（头部事实修正 + aapt2 linux 校验和）、`app/build.gradle.kts`（生成命令补全/注释修正/BUILD_TIME git commit/序列化版本入 catalog）、`gradle/libs.versions.toml`（kotlinxSerialization）、`app/proguard-rules.pro`（BuildConfig keep）、`docs/reference/THIRD_PARTY_NOTICES.md`（jieba Apache-2.0/serialization）、`core/model/ModelCatalog.kt`（DEFAULT_DIMS）、`docs/UPSTREAM_*.md`、`scripts/release.sh`（旧产物清理）、`.github/workflows/ci.yml`（wrapper-validation）、`docs/review/DEVICE_ACCEPTANCE.md`（表格转义）
**测试**：`test/.../core/model/{Sha256Test,ModelDimsLocaleTest}.kt`（新）

---

*报告生成：2026-10-17 · B4 代码变更未提交；设备已恢复（debug/v3 DB/模型）。*
