# B7 修复报告（P2 B7：路线收敛——删 Path C + Granite/LFM，仅存 EGemma2 三模型；旧数据兼容）

- **日期**：2026-10-17
- **决策来源**：用户确认——采纳**保守版 A1**（仅删旧 EmbeddingGemma `.task`/Path C），一并清理 Granite（及随之的 LFM），保留 EGemma2 **3 种强度模型**；处理旧数据兼容，最小风险（不自动删除用户数据）；不做 A2（UE 未用 native 的 packaging 排除）。
- **执行方式**：core-fixer（核心收敛 + legacy 检测/备份）、ui-fixer（设置页提示/路径选项）、ui-reviewer（debug 基准清理）、data-reviewer（测试全量更新）、build-fixer（移除 tasks-text）；Lead 集成/文档/门禁/真机验收
- **验收目标**：release APK ≈49MB；220 评测不回退；旧空间可检出、旧备份可读拒绝、导出→导入回环可用

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| 路线收敛 | 旧 EmbeddingGemma `.task`（Path C/tasks-text）、Granite、LFM2.5 已下线；仅保留 **EGemma2 5 条 catalog 条目**（440M/270M/740M + 2×SM8750 NPU） |
| APK 体积 | **release 64,548,559 → 49,068,516 B（−15,480,043 B，−23.98%）**；debug ≈64.46MB；目标 ≈49MB **达成** |
| 移除的 native | `libmediapipe_tasks_textgenai_jni.so` 15,475,712 B（tasks-text 唯一来源）不再打包 |
| JVM 测试 | **243 tests / 0 failed**（debug）；release unit tests 全绿 |
| Lint | **0 errors / 25 warnings** |
| 真机 instrumentation | **35 / 35 PASS**（含 LegacySpacesReportDbTest 2、BackupLegacySpaceTest 3、PathPreferencesLegacyValueTest 1） |
| 真机 220 评测（440M 路径） | **0 refused / 0 error**；p50=49ms、p95=64ms（与 B7 前基线一致） |
| 旧数据兼容 | 旧空间报表（granite/PathC/LFM/孤儿）可检出并在设置页提示；导出过滤旧空间向量；旧备份导入**写库前**可读拒绝、原库不变；受支持包可正常导入 |
| 3 强度模型 | 270M（Path B/litertlm 引擎）、440M/740M（Path A/UE）均保留；SOC NPU 变体保留；路径 UI = 自动 / A·440M / B·270M |
| 独立审查 | Standards + Spec 双轴（见第 6 节） |

---

## 2. 逐项完成说明

### 2.1 删除 Path C（旧 EmbeddingGemma `.task` / MediaPipe TextEmbedder）
- 删除 `core/embedding/TextEmbedderProvider.kt`；`EmbeddingRuntime.createProvider` 移除 `isPathCModel -> TextEmbedder` 分支。
- `IngestRepository` 删除 `isPathCModel` 与 `SPACE_ID_C` 残留；`PathPreferences` 移除偏好 `C`、`.task` 扩展名扫描与 C 文案。
- 构建：`app/build.gradle.kts` 删除 `implementation(libs.mediapipe.tasks.text)`；`libs.versions.toml` 删除 `mediapipeText`/`mediapipe-tasks-text`；`verification-metadata.xml` 保留历史 tasks-text 校验和（未解析不使用，无害，注释说明）。
- 收益：`libmediapipe_tasks_textgenai_jni.so`（15,475,712 B）不再进入 APK（保守版 A1 未做其它 native 排除）。

### 2.2 Granite / LFM 路线清理
- `ModelCatalog.ENTRIES` 收敛为 5 条 EGemma2（440M、270M、740M、440M-SM8750、740M-SM8750）；移除 granite 与 LFM2.5 条目。
- `isPathBModel` 仅匹配 `270m`（Granite/LFM 移除）；`SPACE_ID_B` 与 `EmbeddingRuntime` granite 兼容映射删除；`PathPreferences` 偏好 B 语义改为 EGemma2-270M，标签 `B·270M`。
- 保留 `LiteRtLmEmbeddingProvider`（270M 仍走 litertlm EmbeddingEngine），KDoc 中 granite 例证改为 270M。
- 删除仅服务已下线路线的 debug `PathBVerification.kt`；新增 provider 无关的 `ProviderVerification.kt` 供 G1D/G1E 使用；`BenchmarkScreen` DebugRun 18→16（删除 PATH_B/G1C），旧 EXTRA 保留但显示"已下线"提示。

### 2.3 旧数据兼容（最小风险）
- 新增核心 API（UI/测试已对齐）：
  - `data class LegacySpaceInfo(id, providerId, modelId, vectorCount)`（top-level）
  - `IngestRepository.legacySpacesReport(): List<LegacySpaceInfo>`（遍历 meta ∪ chunk_embedding，按空间计数，不载 BLOB）
  - `isSupportedSpace(providerId, modelId)`：provider ∈ {`mediapipe-universal`, `litertlm-engine`} 且 modelId 含 `embeddinggemma-2`（忽略大小写）；granite / `embedding_gemma.task` / LFM / 未知孤儿空间 → legacy。
- **设置页提示**：SettingsViewModel 在 IO 中读取报表；≥1 时显示警告卡（N、逐空间 `modelId`（空显示"未知模型"）与 `vectorCount`、检测失败提示），文案明确"向量仍保留但不参与 EGemma2 检索 / 不会自动删除数据 / 需重新导入"；不提供删除动作。
- **备份导出**：`KnowledgeBackup` 只导出受支持空间的 meta/向量（旧空间向量跳过），documents/chunks/sources/app_kv 照常 → 新 App 导出的包可再导入。
- **审查补强（导出一致性）**：仅含旧模型向量的 `ready` 文档在**包内降级为 failed**（有受支持向量的文档保持 ready）。否则恢复后会出现"ready 但零向量"的静默不可检索数据，且同 checksum 重导会被去重拦截；降级后文档可见、可删除，重新导入同 checksum 原文（failed 允许重导）即可重建向量。
- **备份导入**：parse 阶段（写库前）校验空间，含已下线 provider/model 时抛可读 `IllegalStateException`（最多列 8 个 `modelId(providerId)` + 迁移指引），原库不变。
- 旧模型文件仍留在用户磁盘但不被识别/选中（安全；不自动删除，符合最小风险）。

### 2.4 3 种强度模型与检索路径
| 档位 | 模型 | 通道 | 状态 |
|---|---|---|---|
| 轻量 | EGemma2-270M | litertlm EmbeddingEngine（Path B） | 实验（verified=false，验证门后启用） |
| 均衡（默认） | EGemma2-440M | MediaPipe UniversalEmbedder（Path A） | verified |
| 性能上限 | EGemma2-740M | UniversalEmbedder（Path A） | 实验 |
> 模型资产不进入 APK（运行时下载/投放），保留三模型不影响体积。设置页路径选项 = 自动 / A·440M / B·270M / **D·740M**（显式偏好不受验证门限制；自动模式仍只用通过验证门的模型）。270M/740M 仍为实验模型，建议验证门后再作为默认。

### 2.5 文档/脚本同步
- `README.md` 新增"模型路线（B7 起）"（EGemma2 三模型、旧数据提示、APK ≈49MB）。
- `docs/RELEASE.md`：依赖表移除 tasks-text、体积说明改为 49.07MB、验收清单改为 EGemma2 Path A/B + 旧数据兼容项。
- `docs/UPSTREAM_VERSIONS.md` 顶部 B7 收敛横幅（下方 granite/.task/LFM 条目为历史审计）；`docs/UPSTREAM_WATCH.md` 标注 tasks-text 历史；`docs/EMBEDDING_PATH_B_TextEmbedder_Fallback_Analysis.md` 加历史文档横幅。
- 删除 `scripts/run_g1b.sh`（Path B granite）与 `scripts/run_g1c.sh`（Path C）；`scripts/run_probe.sh` 只扫 `.litertlm`（通道 UE/LM/NPU）；`run_eval.sh` 示例 tag 改 440M/270M/740M。

---

## 3. 验证证据

### 3.1 静态门禁

```text
$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin \
            :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL
JVM debug = 243 tests / 0 failed；release unit 全绿
lint = 0 errors / 25 warnings
```

### 3.2 APK 体积（release，arm64）

| 项 | 数值 |
|---|---|
| B7 前 release | 64,548,559 B |
| B7 后 release（审查修复后终态） | **49,068,516 B（−15,480,043 B，−23.98%）** |
| 移除 native | `libmediapipe_tasks_textgenai_jni.so` = 15,475,712 B（不再打包） |
| 剩余 native（arm64） | liblitertlm_jni 22.16MB、libmediapipe_tasks_jni 14.37MB、libsqlite_vector_store_jni 5.23MB、libicing 3.78MB |
| debug | 64,460,194 B（B7 前 79,974,749 B） |
| 说明 | 保守版 A1：**未**做 `packaging.jniLibs.excludes`（UE 未用 VectorStore native 的进一步 −9MB 留待后续评估） |

### 3.3 真机（OnePlus PJZ110 / Android 16 / arm64）

| 项 | 结果 |
|---|---|
| `connectedDebugAndroidTest` | **35 / 35 PASS**（迁移全链、备份 v1 fixture、导入去重、空间采纳（EGemma2 矩阵）、LegacySpacesReportDbTest 2、BackupLegacySpaceTest 3、PathPreferencesLegacyValueTest 1 等） |
| 真机 220 评测 | 0 refused / 0 error；hits=2620；p50=49ms、p95=64ms（B7 前 49/63ms，波动范围内） |
| 旧空间提示 | 设置页警告卡（代码/Preview + instrumentation 报表测试覆盖；真机 DB 注入演示受设备 DB 保护机制影响未保留截图，已如实记录） |
| 旧备份拒绝 | BackupLegacySpaceTest 验证写库前拒绝、原库不变、错误含"已下线"+modelId |

### 3.4 旧数据兼容测试明细
- JVM `LegacySpaceSupportTest`（4 例）：`isSupportedSpace` 白名单/大小写/常量契约。
- instrumentation `LegacySpacesReportDbTest`（2 例）：混合库仅返回 granite/PathC/LFM/孤儿 4 空间、排序与 vectorCount 正确；仅支持空间→空。
- instrumentation `BackupLegacySpaceTest`（3 例）：导出过滤旧空间向量且包可再导入；**仅旧向量文档导出后状态 failed、可同 checksum 重导**；旧包写库前拒绝且原库不变；受支持最小包导入成功。
- instrumentation `PathPreferencesLegacyValueTest`（1 例）：旧持久化 "C"/非法值回退 `AUTO`。
- `LegacySpaceAdoptionDbTest`/`MatrixTest` 重写为 EGemma2 矩阵（440M GPU/NPU、270M、740M、换文件、交叉隔离）。

---

## 4. 计划偏差记录

| # | 决策/计划 | 实际实现 | 说明 |
|---|---|---|---|
| 1 | 保守版 A1（不做 packaging native 排除） | 已遵守 | release 49.07MB；A2 的 −9MB 未做，留后续评估 |
| 2 | 删除 Granite | 已删 | 连带 LFM（同属非 EGemma2 路线，用户要求仅保留 EGemma2） |
| 2b | 3 强度可选性 | 新增 `PathPreferences.D`（740M），显式偏好 A/B/D 在全量候选中选 | 审查修复：此前 440M 在位时 B/D 均不可选（验证门池过滤后回退 auto）；自动模式门禁不变 |
| 3 | 旧数据"处理" | 检测 + UI 提示 + 导出过滤 + 导入拒绝；**不做自动迁移/删除** | 最小风险；用户需重新导入原始文档恢复检索 |
| 4 | 旧模型文件 | 保留在磁盘，不识别/不删除 | 安全；后续可加"清理旧模型"入口（可选） |
| 5 | Path B/C autostart EXTRA | `autostart_pathb`/`autostart_g1c` 常量保留（scripts 兼容），debug 屏提示"已下线" | 避免破坏既有脚本的 am start 命令 |
| 6 | 旧备份兼容 | 含旧空间向量的包**拒绝导入**（可读错误）；新导出包把仅旧向量文档降级 failed | 恢复后可见可重导，避免静默零向量 |
| 7 | verification-metadata | 保留 tasks-text 历史校验和 | Gradle 不解析未使用依赖，无害；重跑生成命令会自动清理 |
| 8 | 旧路线历史文档 | UPSTREAM/EMBEDDING_PATH_B 保留并加历史横幅 | 保留决策记录，避免丢失上下文 |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 旧路线用户检索降级 | granite/PathC/LFM 空间的文档在 EGemma2 下不可检索，直到重新导入 | 设置页明确警告；**备份恢复后旧-only 文档标 failed（可重导）**；文档/RELEASE 说明；不自动删除数据 |
| 2 | 旧备份不可直接导入 | 含旧空间向量的备份被拒绝 | 可读错误 + 迁移指引；用户可用新模型重新导入后导出 |
| 3 | 设备级旧空间 UI 截图未留存 | 设备 DB 注入演示受系统保护/DB 布局影响未保留 | 报表/备份由 instrumentation 覆盖、UI 由 Preview+代码覆盖；下次真机验收可 seed 后截图 |
| 4 | 270M/740M 仍为实验模型 | verified=false（验证门后启用） | 保持原策略；B7 未改变质量策略 |
| 5 | 未来恢复路径成本 | 若重新支持 `.task`/旧 EmbeddingGemma，将重新引入 15.5MB native | 记录在 RELEASE/报告；需重新评估体积预算 |
| 6 | A2 进一步瘦身未做 | UE 未用 native（icing/vector_store，约 9MB）未排除 | 实验已验证可行（220 评测通过），但保守决策未启用；可后续评估 |
| 7 | `SharedModelStore` 注释/旧包 `stem()` 仍提 `.task` | 仅注释/旧包解析兼容，无运行影响 | 留 B6 文档清理 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | 备份导出过滤旧空间向量但保留 ready docs/chunks，恢复后仅旧向量文档变"ready 零向量"且无提示；注释称"重新导入原文"但同 checksum READY 被判 Duplicate | **已修**：导出时仅含旧向量文档在包内降级为 failed（可见、可删、可重导）；补测试；文档/RELEASE 说明 |
| S2 | 判断性 | ModelCatalog 740M 标"未验证"却在推荐文案称"三档验证"，PERF_FAST 无 verified 门直推 440M/740M | **部分修**：文案改为"两档真机验证（440M）+ 270M/740M 实验"；自动模式仍走验证门池（避免自动选实验模型）；740M 改为显式 D 路径选择 |
| S3 | 判断性 | 设置页旧空间卡未展示 vectorCount、孤儿 modelId 空显示"（）"、报表失败静默吞 | **已修**：逐空间展示 modelId（空→"未知模型"）+ vectorCount；新增检测失败提示（不阻塞刷新） |
| S4 | 判断性 | 旧偏好 `preferred="C"` 未迁移，设置页 chips 无选中且静默落 auto | **已修**：`PathPreferences.get()` 对非法/已下线值回退 AUTO；补测试 |
| S5 | 判断性 | 测试缺口：备份未覆盖"仅旧向量文档"；DB 报表缺 NPU/大写组合（JVM 已覆盖大小写） | **已补**：BackupLegacySpaceTest 增"降级/可重导"；报表 DB 用例补 NPU 变体断言（JVM 大小写已有） |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| A1 范围 | implemented | 确认（tasks-text/代码/UI/偏好/文档清理；未误做 A2） |
| Granite/LFM | implemented | 确认（残留仅为历史注释/文档横幅） |
| 3 强度 | partial（740M 不可选） | **已修**：新增 D·740M 显式路径；自动门禁不变；RELEASE/README 说明三档可切换但 270M/740M 为实验 |
| 旧数据兼容 | partial（UI 缺 vectorCount/孤儿空 modelId；备份恢复静默） | **已修**：UI 展示 + 导出降级 + 测试；旧-only 文档恢复后为 failed（可重导） |
| 兼容性 | partial（评测数据未落盘） | **已补**：B7 真机 220 评测结果写入报告（0 refused/0 error、p50 49/p95 64）；long-tail 沿用 B2 基线（检索代码未改） |
| debug/scripts | implemented | 确认 |
| 验收数据 | partial | **已补**：APK 49,066,772B、JVM 239/220、真机 34/34 均有产物/XML；评测结果记录 |
| 计划/文档 | wrong（“三档可切换/vectorCount/截图”失实） | **已修**：D 路径使"三档可切换"成立；UI 补 vectorCount；截图缺失如实记录并改由测试/Preview 覆盖 |
| 范围/兼容 | partial（备份恢复静默） | **已修**：failed 降级 + 可重导闭环 |

### 6.3 审查后复验（最终）

- `compileDebugKotlin / compileReleaseKotlin / compileDebugUnitTestKotlin / compileDebugAndroidTestKotlin` ✅
- JVM `testDebugUnitTest`：**243 tests / 0 failed**；`testReleaseUnitTest` 全绿
- `lintDebug`：**0 errors / 25 warnings**
- `assembleDebug` / `assembleRelease`（R8）✅；release **49,068,516 B**（审查修复仅 +1.7KB 文案）
- 真机 `connectedDebugAndroidTest`：**35 / 35 PASS**（含备份降级/重导、旧偏好迁移用例）
- 真机 220 评测（B7 主体改动后）：0 refused / 0 error、p50=49ms/p95=64ms（与 B7 前一致）

---

## 7. 文件清单（B7）

**删除**：`core/embedding/TextEmbedderProvider.kt`、`app/src/debug/.../benchmark/PathBVerification.kt`、`scripts/run_g1b.sh`、`scripts/run_g1c.sh`
**核心**：`core/model/ModelCatalog.kt`、`core/model/PathPreferences.kt`、`core/ingest/IngestRepository.kt`、`core/embedding/{EmbeddingRuntime,LiteRtLmEmbeddingProvider,EmbeddingProvider}.kt`、`core/backup/KnowledgeBackup.kt`
**UI**：`ui/SettingsViewModel.kt`、`ui/SettingsScreen.kt`、`ui/SearchViewModel.kt`（B4 暖机修复）、`res/values/strings.xml`
**debug**：`app/src/debug/.../benchmark/{ProviderVerification(新),W4PlusRunner,ConnectivityProbe}.kt`、`app/src/debug/.../ui/BenchmarkScreen.kt`
**构建**：`app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/verification-metadata.xml`（注释）
**测试**：`test/**`（ModelCatalog/PathPreferences/SpaceFingerprint/LegacySpaceAdoptionMatrix/IngestRetryDecision/MetaRevisionBackfill/ModelDimsLocale/BackupModelPresence/LegacySpaceSupport 新增）、`testDebug/.../AutoStartContractTest.kt`、`androidTest/**`（LegacySpaceAdoptionDb/Migration/LegacySpacesReportDb 新/BackupLegacySpace 新）
**文档/脚本**：`README.md`、`docs/RELEASE.md`、`docs/UPSTREAM_VERSIONS.md`、`docs/UPSTREAM_WATCH.md`、`docs/EMBEDDING_PATH_B_TextEmbedder_Fallback_Analysis.md`、`scripts/run_probe.sh`、`scripts/run_eval.sh`
**审查后修复**：`core/backup/KnowledgeBackup.kt`（导出降级旧-only 文档 failed）、`core/model/PathPreferences.kt`（D=740M/旧值迁移/显式偏好全量候选）、`core/model/ModelCatalog.kt`（验证文案）、`core/ingest/IngestRepository.kt`、`ui/SettingsViewModel.kt`（legacySpacesError）、`ui/SettingsScreen.kt`（D chip/FlowRow/逐空间 modelId+vectorCount/错误提示）、`res/values/strings.xml`、`docs/RELEASE.md`
**审查后测试**：`BackupLegacySpaceTest`（降级/可重导）、`PathPreferencesRoutingTest`（D/显式偏好/旧值）、`PathPreferencesLegacyValueTest`（新）、`ModelCatalogTest`（+D 契约）
**验收数据**：`docs/review/b7/baseline.json`、`docs/review/b7/device-legacy-warning.png`（尝试截图，设备注入未成功保留）

---

*报告生成：2026-10-17 · B7 代码变更未提交；设备已恢复（debug/v3 DB/模型）。*
