# ZhiBox 真机验收报告（DEVICE_ACCEPTANCE）

- **验收日期**：2026-10-17
- **设备**：OnePlus PJZ110（SM8750 / Adreno，`1fc6056b`），Android 16（SDK 36），arm64-v8a，/data 可用 463GB
- **被测对象**：P0/P1 修复后的冻结态（HEAD `9ca20d6` + 工作区修复，见 `FIX_REPORT.md`）
- **测试基线**：设备原有真实数据 = 81 篇文档 / 83 chunk / 83 向量 / 2 个 embedding 空间（schema v3，`...-gpu` 空间 83 条向量），440M `.litertlm` 模型已在 `files/models`
- **验收方式**：androidTest 真机执行 + 带模型/旧数据的功能冒烟 + release（R8）包冒烟；截图见 `docs/review/device-acceptance/`

---

## 1. 结论

| 验收域 | 结果 |
|---|---|
| Instrumentation 测试 | **7/7 PASS**（备份回环、坏包安全、检索可见性、迁移 1→2/2→3/3→4/1→3 链式） |
| 真实旧库升级（v3→v4） | **PASS**：83 chunk 全部保留；非 legacy 空间 `...-gpu` 被正确**采纳**（未出现"旧向量消失"），搜索不再拒绝 |
| 检索质量/性能（真机 GPU） | **PASS**：262 查询 0 refused、0 error；**R@1=0.964 / R@5=0.991 / R@10=1.000**（220 条有标注查询）；p50=48ms / p95=57ms（冷启动首查 6.1s 为模型加载） |
| 导入管线（真机） | **PASS**：20 篇新增导入后 101/101 ready、103 chunk/FTS/向量全覆盖、attempt=0、0 错误、0 StrictMode 违规 |
| UI 状态/导航（真机） | **PASS**：检索→证据→返回结果保留；返回键回上一屏；旋转横屏后查询/结果保留；来源/导入/设置屏正常；warm 检索 40ms |
| Release（R8）包 | **PASS**：首页无"实验/诊断"入口；导入 5/5 100%；检索 Top 5 / 37ms；无 FATAL/ANR、无 UnsatisfiedLink/ClassNotFound |
| 设备状态恢复 | **PASS**：已重装 debug 包并恢复原始 DB（v3，81 文档）+ 模型 + 旋转设置，清理 /data/local/tmp 临时文件 |

**总体判定：P0/P1 修复在真机上达到验收标准；发现 1 个新的 P2 级问题（见第 6 节），不影响本轮 P0/P1 结论。**

---

## 2. Instrumentation 测试（真机 7/7 PASS，33s）

命令：`./gradlew :app:connectedDebugAndroidTest`

| # | 测试 | 结果 |
|---|---|---|
| 1 | `BackupRoundTripTest#exportImportExport_preservesSpacesAndVectors` | PASS（导出→清库→导入→再导出：向量数/空间数不降） |
| 2 | `BackupRoundTripTest#corruptedPackage_failsWithoutWipingExistingDb` | PASS（坏包失败且原库不变） |
| 3 | `SearchVisibilityTest#onlyReadyDocumentsAreSearchable` | PASS（failed/processing 文档的 chunk/FTS/向量均不被检索） |
| 4 | `MigrationTest#migrate1To2_dataPreserved_andAcceleratorBackfilled` | PASS |
| 5 | `MigrationV2ToV3Test#migrate2To3_quantizationDefault_andKvTableUsable` | PASS |
| 6 | `MigrationV2ToV3Test#migrate1To3_chained` | PASS |
| 7 | `MigrationV3ToV4Test#migrate3To4_attemptColumnDefault_andVectorSpaceIndex` | PASS |

原始结果：`app/build/outputs/androidTest-results/connected/debug/TEST-PJZ110 - 16-_app-.xml`（tests=7 / failures=0 / errors=0）。

---

## 3. 真实旧数据升级与检索（P1-6 / P0-2 关键验收）

### 3.1 升级路径
1. 设备原有库为 **schema v3**：`PRAGMA user_version=3`，81 文档 / 83 chunk / 83 向量；embedding_meta 含 `mediapipe-universal/...-gpu`（modelRevision 为空）与 legacy `mp-universal/...` 两个空间。
2. 安装修复版 debug APK 后，触发无头检索评测（`autostart_search_eval`）：首次打开 DB 自动执行 `MIGRATION_3_4`。

### 3.2 验收结果
- `PRAGMA user_version` → **4**；83 chunk / 83 FTS / 83 向量全部保留。
- 检索使用的空间 = **`mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu`**（升级前的旧 ID，**不带 `@revision`**）→ 证明 `resolveSpaceId` 的"存量空间采纳"生效；若未采纳会变成带 `@rev` 的新空间、83 条旧向量不可见、262 查询全部 refused。
- 环境：auto GPU 在 OnePlus SM8750 上正常启用（GPU 通道）；262 查询 0 refused。
- 评测指标（220 条有标注查询）：**R@1=0.964 / R@5=0.991 / R@10=1.000**；延迟 p50=**48ms**、p95=**57ms**（首查含模型加载 6.1s，属冷启动）。
- 频道分布：向量+关键词 936、向量 1453、关键词 231（融合通道工作正常）。

### 3.3 写入路径回填（P1-6 meta 字段）
在随后的真机导入中，`registerSpace` 对采纳的旧空间完成回填，DB 实测：

```
id = mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu
modelRevision = 92dcbea10889   （= catalog SHA-256 前 12 位）
accelerator   = gpu
quantization  = f32
```

→ 空间指纹/元数据由 descriptor 生成、存量空间补 revision，均真机验证通过。

---

## 4. 导入管线真机验收

- 输入：20 篇评测语料（短文档，UTF-8 txt）经 `files/import-staging` 注入，`autostart_import` 无头触发。
- 结果（合并 WAL 后查库）：

| 指标 | 结果 |
|---|---|
| ready 文档 | **101 / 101**（基线 81 + 新增 20） |
| chunk / FTS / 向量 | **103 / 103 / 103**（每篇均有向量，无半成品） |
| ingest_job | 101 条全部 `done`；`MAX(attempt)=0`；`error IS NOT NULL` 数量 = 0 |
| StrictMode 违规 | **0**（导入期间） |
| FATAL/ANR | 0 |

> 验收方法学备注：App 运行时数据库处于 WAL 模式，`adb` 单独拉取主库文件会读到**旧快照**；必须同时拉取 `zhibox.sqlite3-wal` 或先 checkpoint，否则会误判"导入卡住"（本次首次读数曾出现 98 篇假象）。

---

## 5. UI / 导航 / release 冒烟

### 5.1 Debug 包功能冒烟（截图目录 `docs/review/device-acceptance/`）

| 场景 | 结果 | 截图 |
|---|---|---|
| 首页 | 正常；文案已资源化（"ZhiBox · 离线个人知识库"） | `01-home.png` |
| 检索（空/输入） | 正常 | `02/03-*.png` |
| 检索结果 | Top 10；首查 7038ms（模型加载）、warm 重复检索 **40ms**；空间显示 GPU 空间 | `04-search-results.png` |
| 证据详情 | 标题/来源/字符定位/命中通道/原文片段齐全 | `05-evidence.png` |
| 证据返回 | 返回后检索词与结果**完整保留**（P1-8） | `06-back-to-results.png` |
| 横屏旋转 | Activity 未重建丢失状态：查询 "HTTP3" 与结果保留 | `07-landscape-results.png` |
| 来源屏 | "101 个来源 · 101/101 文档就绪" | `08-sources.png` |
| 导入屏 | 任务列表全绿（阶段 完成 · 100%） | `09-import.png` |
| 设置屏 | 嵌入路径/模型商店/SOC（sm8750）/性能偏好/共享目录均正常渲染 | `10-settings.png` |
| 返回键 | 子屏 → 首页（BackHandler 生效，不退出 App） | — |
| 冷启动 | `TotalTime≈405ms`，无 ANR | — |

### 5.2 Release（R8）包冒烟

- 产物：`app-release-unsigned.apk` 用 debug keystore 签名后安装（仅用于验收；正式交付需 release 密钥）。
- 结果：
  - 首页**无"实验/诊断"入口**（P0-3 UI 隔离开关生效）；
  - 首页读取 `dumpsys package` flags **无 DEBUGGABLE**；
  - 导入 5 篇：任务列表 5 个"阶段 完成 · 100%"（R8 下 MediaPipe/LiteRT JNI 正常）；
  - 检索："Top 5 · 37ms"（release 下向量通道正常）；
  - logcat：0 FATAL / 0 ANR / 0 `UnsatisfiedLinkError` / 0 `ClassNotFound`。
- 截图：`11-release-home.png`、`12-release-import-done.png`、`13-release-search-results.png`。

> 注：release 非 debuggable，无法 `run-as` 查库，release 侧以 UI 与 logcat 为验收依据；核心数据正确性已由 debug 侧 + instrumentation 覆盖。

---

## 6. 真机新发现（进入 P2 计划）

| # | 发现 | 证据 | 级别/处置 |
|---|---|---|---|
| D1 | **主线程 StrictMode 磁盘读**：`ImportViewModel.<init>` 在 ViewModel 构造时调用 `getFilesDir()`（`ensurePrivateDirExists` → `File.exists`） | 真机 logcat：`StrictMode policy violation; ~duration=37ms: DiskReadViolation`，堆栈指向 `ImportViewModel.kt:37` | P2（单次 37ms，不影响功能；随 P2 批 B1/B3 修复：目录路径延迟到 IO 或使用常量路径） |
| D2 | 冷启动首次检索 7.0s（模型加载），期间仅进度条无阶段提示 | `04-search-results` 显示 7038ms；warm 40ms | P2（UX/体验批）：首查加载提示或引擎预热（权衡内存） |
| D3 | release 交付链路：无签名材料时产物 unsigned；OnePlus/ColorOS 全新安装会弹"安装增强防护"引导（需手动深度扫描后才能继续），影响自动化安装 | 本轮 adb 安装复现，已记录自动化步骤 | P2（发布工程批）：签名 provision、交付/验收文档写明 OEM 安装引导 |
| D4 | `meta.modelRevision` 仅在写入路径回填；只读搜索/启动不会回填 | 升级后仅搜索时 meta.revision 仍为空；导入后回填 | P2：可接受（不影响检索）；如需可加"启动后惰性回填"或迁移内回填 |
| D5 | 评测输入存在两套约定：`SearchEvalRunner` 读 `files/search/queries.json`，而设备/脚本留有 `files/eval/evalset.json` | 本轮验收时需手工转换 | P2（脚本/文档一致性批） |
| D6 | release 包不可 `run-as`，验收脚本需明确 debug/release 各自的取证口径 | 本轮 release 冒烟只能靠 UI/logcat | P2（验收文档） |

---

## 7. 验收后设备状态

- 已卸载 release 验收包，重装 **debug APK**（DEBUGGABLE）。
- 已恢复**原始数据库**（验收前备份的 schema v3 主库+WAL，81 文档）；首次访问 DB 时自动迁移 v4。
- 已恢复 440M 模型到 `files/models`；旋转设置恢复（user_rotation=0 / accelerometer_rotation=1）；清理 `/data/local/tmp` 临时文件。
- 测试期间产生的 20 篇导入文档、评测 queries/results、GPU 偏好等位于验收副本中，未留在恢复后的库；原始数据未被破坏（验收前已做文件级备份）。

---

## 8. 验收结论

1. **P0/P1 修复在真实设备（旧数据升级 + 真实模型 + R8 release）上全部关键路径通过**；
2. 真机验证了三条最有风险的修复：**存量非 legacy 空间采纳**（否则旧向量全丢）、**检索只返回 ready 文档**、**备份回环/坏包安全**；
3. 唯一新问题为主线程 `getFilesDir()` 的 37ms StrictMode 违规（D1，P2 级），已纳入 P2 计划；
4. 建议：正式交付前配置 release 签名密钥并复跑本验收清单（release 段）；androidTest 已可在本机以 `./gradlew :app:connectedDebugAndroidTest` 一键复验。

---

## 附录：B4-7 真机取证口径（debug vs release）

> 来源：P2 计划 B4-7（device D6）。release 包不可调试（`android:debuggable=false`），
> 无法使用 `run-as` 读取应用私有目录；两种变体的取证方式不同，验收时不得混用。

| 能力 | debug（`assembleDebug`） | release（`assembleRelease`，unsigned/signed） |
|---|---|---|
| 安装 | `adb install -r app-debug.apk` | `adb install -r app-release-unsigned.apk`（同密钥重装；正式分发须先签名，见 `docs/RELEASE.md`） |
| 读私有文件（DB/日志/结果） | `adb exec-out run-as com.zhibox.memory cat files/...` | **不可用**（非 debuggable）；用 UI 导出/SAF、`/sdcard` 公共目录，或 `adb logcat` |
| 触发评测/验收 runner | `--ez autostart_search_eval true` 等 20 个 EXTRA | **不可用**（release 不解析实验 extra，P0-3） |
| StrictMode 违规 | `adb logcat -d` 管道 `grep StrictMode`（debug 启用 penaltyLog） | 无 StrictMode 日志（release 不启用） |
| 性能/DB 校验 | `run-as` 拉 DB + sqlite3；logcat 计时 | UI 操作 + logcat 计时；DB 通过应用内导出/备份包离线校验 |
| 签名校验 | 无需 | `apksigner verify --print-certs dist/*.apk` 与 `aapt2 dump badging` 管道 `grep application-debuggable`（应无输出） |
| OEM（OnePlus）全新安装 | 可能弹"安装增强防护"：更多 → 开始深度扫描 → 继续安装（或改用 `adb install` 后按引导操作） | 同左；正式发行需在文档中提示用户 |

**发布验收最低清单**：`apksigner verify` 通过 → `aapt2 badging` 无 `application-debuggable` → 冷启动 5 屏可进 → 导入 1 篇文档 → 检索命中 → 备份导出/导入回环 → 日志无 `FATAL`。release 的详细流程见 `docs/RELEASE.md`。
