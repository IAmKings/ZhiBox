# 发布说明（v0.1.1 release candidate；含 v0.1.0-mvp 历史）

> 适用：**v0.1.1 release candidate**（B8–B12 SAG P0 能力 + 代码质量 R2/R3 修复）。本文档面向升级用户与后续维护者，说明行为变更、迁移影响与回滚步骤；实现细节/验收证据见 `docs/review/B*_FIX_REPORT.md`、`docs/CODE_QUALITY_REVIEW_R2.md`/`R3.md`。
> **发布状态**：本地签名 RC 已准备；**未打 tag、未推送 Release**；发布动作由负责人确认后执行（`docs/RELEASE.md`）。

## 0. v0.1.1 release candidate（未发布）

### 0.1 版本与兼容性

| 项 | v0.1.1 值 |
|---|---|
| versionName / versionCode | **`0.1.1` / `2`** |
| 包名 / minSdk / targetSdk | `com.zhibox.memory` / 24 / 36 |
| 数据库 schema | **v7**（自 v5 升级；Room 迁移 v5→v7） |
| 备份格式 | **v3**（可导入 v1/v2/v3；v0.1.0-mvp 不能读取 v3） |
| 模型路线 | EGemma2 270M/440M/740M（+ SM8750 NPU 变体；与 v0.1.0 相同） |
| release 签名 | 沿用现有 release keystore（与 v0.1.0-mvp 同证书；可覆盖升级） |
| APK（signed RC） | `dist/zhibox-0.1.1-462b09c-release-signed.apk`（49387732 bytes，SHA-256 `e13ee1cbb7ca00f4b281483f1adf1d7ac6a2a20fa0076efe1d9c583c40b1d65b`） |

### 0.2 用户可见变化

1. **SAG 抽取与索引（B8/B9）**：文档 ready 后，配置 LLM 且开启 SAG 时后台抽取 1 event + 11 类 entity，写入 event/entity/incidence/SAG 向量/FTS；失败可重试/补抽取；抽取并发可调 1–4（默认 1 串行）。
2. **三种检索模式（B10）**：混合 / SAG 快速（离线可用）/ SAG 精排；source 限定、预算档位、降级原因与脱敏 trace。
3. **带引用问答（B11）**：单轮流式 Chat；证据 `[S1]…[Sn]` 可点回原文；无证据拒答、非法/未校验引用警示、停止/重试；首次联网 consent；API Key 存 Keystore，不进日志/备份。
4. **图谱/设置/备份/诊断（B12）**：来源页「图谱」tab（event 卡片/详情/entities/chunk 原文/共享实体相关事件、source 过滤、分页上限 200）；Settings 增加预算档位、抽取并发、trace 诊断导出、备份 v3/隐私说明；备份 v3 支持导出/导入、模型/空间依赖提示、旧包补抽取。
5. **代码质量/稳定性（R2/R3）**：事件向量扫描去除 5000 条静默截断（分页全量+有界 top-K）；抽取热路径去除整篇 chunk 物化；SSE 有界逐行读取；抽取 claim/not-indexed 并发守卫；UI 生命周期收集/进场刷新/文案资源化；trace 导出去重；详见两份审查报告。

### 0.3 升级步骤（v0.1.0-mvp → v0.1.1）

1. **先备份**：用 v0.1.0 导出 v2 备份并保存到设备外；记录本机模型/API Key 配置（Key 不入备份，升级后需重新输入或由 Keystore 保留——同签名覆盖升级通常保留，但回滚/清装会丢）。
2. 用**同一 release keystore** 签名覆盖安装 v0.1.1（versionCode 2 > 1）；不要卸载，否则 App 数据/Keystore alias 丢失。
3. 首次启动执行 Room v5→v7 迁移；SAG 抽取任务/事件层按新 schema 可用。
4. 备份导入 v2/v1 仍兼容；导入 v3 后 FTS 从原文重建；旧包/空 SAG 会提示补抽取。
5. 升级后建议运行一次「设置→诊断」与一次检索/问答 smoke；确认 `versionName=0.1.1`。

### 0.4 回滚步骤

- v0.1.0-mvp **不能读取 v3 备份**；回滚前必须保留升级前导出的 **v2 备份**，或准备旧 APK 可用的数据。
- 回滚安装 v0.1.0 需**卸载/清数据**（签名相同但 versionCode 降级需 `-d`/卸载），然后从 v2 备份导入；API Key/聊天/SAG 事件层不能回迁到 v0.1.0。
- 不要仅依赖 v0.1.1 导出的 v3 包做降级恢复。

### 0.5 验证与质量门禁

- JVM：debug **520/520**、release **501/501**；lint **0 errors**（41 warnings，依赖版本提示）；`assembleDebug`/`assembleRelease`/androidTest 编译通过。
- K40 中端真机：SagRetriever/Graph/备份/迁移/抽取并发等 **41/41** androidTest，另通过 debug 0.1.0→0.1.1 覆盖升级 smoke（数据保留/启动正常）；PJZ110 signed RC 覆盖升级 smoke 通过（versionCode 2、启动 155ms、无 FATAL）；PJZ110 此前 B12 门禁 **36/36**（升级前）。
- 设备矩阵：PJZ110 高端（signed RC clean install/UI）、K60U 高端（debug 非 UI）、K40 中端（debug 非 UI+手动 UI）；**低端 4–6GB 未提供**，按用户接受的“环境受限”口径记录，不宣称完整三档。
- 性能：Graph 10k p95 ≤12ms（PJZ110）、≤21ms（K60U）、≤62ms（K40），目标 1.5s。

### 0.6 已知限制

- 三档矩阵缺 4–6GB 低端；K60U HyperOS 限制 UI instrumentation；K60U/K40 以 debug 包做功能矩阵（未覆盖安装 RC 以保留数据）。
- 单轮问答；多轮/Agent、工具、云同步、PDF/Office/网页、3D/Explore、对外集成与部署认证仍为 P1/P2/非目标。
- 语义支持率为人工复核（B11 support 98%），公共多跳基准横比仍为后续。
- 发布候选未打 tag/未发布；Release 资产需负责人按 `docs/RELEASE.md` 推送 tag 触发。

---

> **以下第 1–8 节为 v0.1.0-mvp 历史发布说明（B1–B7）**，保留用于升级/回滚追溯；当前版本以上方第 0 节为准。

## 1. v0.1.0-mvp 历史：版本与兼容性

| 项 | 当前值 |
|---|---|
| versionName / versionCode | `0.1.0-mvp` / 既有 versionCode（见 `app/build.gradle.kts`） |
| 包名 / minSdk / targetSdk | `com.zhibox.memory` / 24 / 36 |
| 数据库 schema | **v5**（B1 起；迁移链 v1→v5 由 Room 自动执行） |
| 备份格式 | **v2**（含空间元数据/需求清单；旧版 App 不能读取 v2 备份） |
| 模型路线（B7 起） | 仅 **EGemma2**：270M / 440M / 740M（+ SM8750 NPU 变体）；旧 EmbeddingGemma `.task`、Granite、LFM2.5 已下线 |
| APK（release, B7 后） | ≈**49.07MB**（arm64；旧 `.task` 依赖移除后 −15.5MB） |

## 2. 面向用户的行为变更（7 项）

1. **向量空间加入 `@revision` 后缀（非 legacy 空间）**
   - 同一模型文件名**内容变化**（换文件/重新下载）会生成新空间，而不是静默复用旧向量；
   - 升级后首次使用若检测到同名不同内容：旧空间向量保留但不再被新空间复用，需**重新导入**原文或重建向量；
   - 唯一的 legacy 兼容：EGemma2-440M 通用 CPU 保持旧空间 `mp-universal/egemma2-440m/app-prefix-v1`（存量向量继续可用）。

2. **备份格式升级为 v2**
   - 导出包含向量空间 meta/requirements 与校验；**旧版 App 无法读取 v2 包**（回滚前必须先导出旧版可读数据或使用旧 APK 自带导出）；
   - 新版 App 可导入旧版包，但按下述 6/7 规则归一化。

3. **导入失败自动重试（最多 3 次总尝试）**
   - 临时性错误（文件被占用、IO 抖动、模型暂时不可用）自动重排队，首次 + 最多 2 次自动重试；之后进入 `failed`，由用户手动重试；
   - 任务看板/来源页可看到尝试次数与错误信息（格式 `CODE:detail`，见第 7 项）。

4. **原文文件不随备份迁移**
   - 备份包携带来源元数据、chunk 文本/向量与 FTS，**不携带原始文档文件**；恢复后证据页展示 chunk 文本，
     "打开原文"依赖原文件（若原文件已删除，可用同文件重新导入）。

5. **同名换文件检测时点**
   - 在**检索/导入使用模型前**根据文件 sha/修订指纹判定；检测到换文件 → 新空间 + 需要重嵌入/重导入；
   - 不会在后台静默把旧向量"借给"新模型。

6. **备份导出改为 ready-only；导入恢复语义（B1 补充）**
   - **导出**：只包含 `ready` 文档及其 sources/chunks/vectors（可用 `includeNonReadyDocuments` 生成导入前快照，但用户导出默认不含非 ready）；
   - **旧 App 导入新包**：非 ready 文档不会出现在旧 App（旧 App 只认 ready 字段），其余字段兼容；
   - **新 App 导入旧包**：`pending/processing` 文档归一为 `failed`（避免半成品变 ready），`cancelled/failed` 原样保留；
   - **恢复时多 active 空间归一**：同一时间仅保留一个 active 空间（按最近使用/优先级收敛），避免检索命中错空间。

7. **检索/索引行为修正（B2 补充）**
   - **emoji/代理对分词修复**：旧版本写入的 FTS 建议升级后执行一次 `indexHealthCheck`/`repairIndex`（设置页"索引诊断/修复"），
     以重建 bigram 索引（否则旧 emoji 文档可能召回偏差）；
   - **query 先 NFC 规范化**再分词/匹配；本应用层不做 Unicode 分解，避免"é/e+组合"不一致；
   - **错误码格式统一为 `CODE:detail`**（如 `MODEL_MISSING:...`、`BACKUP_INVALID:...`），UI 展示可读消息，日志保留 code 便于检索。

## 3. B7 路线收敛的用户影响（重要）

- 旧 **EmbeddingGemma `.task`（Path C）**、**Granite**、**LFM2.5** 路线已下线，旧模型文件不再被识别（保留在磁盘，不自动删除）；
- 升级后设置页会提示"检测到 N 个旧模型空间"（显示 modelId 与向量条数）：相关文档**需要重新导入**才能被 EGemma2 检索；
- **旧备份包**若包含已下线空间：导入会被**写库前拒绝**（错误列出 modelId，原库不变）；
- **新导出的备份**会把"仅有旧模型向量"的文档标记为 `failed`（可见、可删除、可重新导入），不会出现"ready 但零向量"的静默数据；
- 模型路线：270M（轻量，litertlm 引擎）/ 440M（默认，UniversalEmbedder）/ 740M（性能上限，设置页显式选 D·740M）；270M/740M 为实验模型，建议验证门通过后再作为默认。

## 4. 升级步骤

1. 升级前在设置页执行**导出知识库备份**（保留旧版可读数据时请同时保留旧 APK）；
2. 覆盖安装新 APK（同 versionCode 不可降级；升级后旧版 App 不能直接读新备份）；
3. 首次启动等待 Room 迁移 v1→v5（大库可能数十秒，勿强杀）；
4. 打开设置页：查看"索引诊断"，如提示旧 FTS/旧空间 → 执行修复或重新导入；
5. 如使用 270M/740M：将模型文件放入 `filesDir/models` 或经模型商店下载，确认 SHA 校验通过。

## 5. 回滚步骤

1. 回滚前**先导出当前知识库备份**（v2，新版才能读；旧版回滚需用旧版 APK 的导出）；
2. 卸载/覆盖安装旧 APK（注意 versionCode 约束；不可降级时需卸载重装）；
3. 若已升级数据库到 v5：旧版 App 不能打开 v5 数据库 → **卸载并清数据后，用旧版 App 导入旧版可读备份**；
4. 回滚后旧模型路线（如 Granite/.task）不受新版本影响（数据在旧库/旧备份中）。

## 6. 已知限制与注意事项

- 检索质量与模型/语料相关：270M/740M 仍为实验模型；440M 为默认验证模型；
- 备份恢复不迁移原文文件（见 2.4），证据页"打开原文"需要原文件；
- 旧空间（granite/Path C/LFM）向量不会自动迁移，只能重新导入；
- 10k 合成库基线（B5）：向量扫描 p95≈4ms、关键词重排 p95≈436ms（JVM 主机基线，非同机绝对值请以报告为准）；
- release 构建需签名密钥（不入库）；未签名 release 仅用于内部验收。

## 7. 版本历史摘要

| 批次 | 主题 | 关键变更 |
|---|---|---|
| B1 | DB schema v5/备份 | 孤儿清理、FK、备份 v2、失败自动重试（最多 3 次总尝试）、`@revision`、ready-only 导出 |
| B2 | 检索质量 | emoji/代理对分词修复、NFC 规范化、错误码 `CODE:detail`、流式矩阵门限 |
| B3 | UI/UX | 空态/错误态、索引诊断/修复、导入取消与重试、暗色主题 |
| B4 | 工程化/发布 | 依赖升级与 strict 校验、R8 keep、release 脚本、LICENSE/第三方声明、构建信息 |
| B7 | 路线收敛 | 下线 Path C/Granite/LFM；仅 EGemma2 三模型；APK 64.55→49.07MB；旧空间检测/备份兼容 |
| B5 | 测试补强 | Robolectric 集成测试、Compose UI 测试、10k 性能基线 |
| B6 | 文档/脚本 | 文档漂移同步、脚本 common.sh/轮询/mktemp、RELEASE_NOTES（本文件） |

---

*维护：每次发布更新第 1/7 节；破坏性行为变更必须写入第 2/3 节并在 PR 描述中引用本节。*


---

## 8. B8–B12 应用功能累积变更（历史条目，已并入 v0.1.1）

> 本段记录 SAG 应用功能批次（B8–B12）的用户可见变化；B12 已完成代码/自动门禁/签名 RC/单机验收，设备矩阵为“高端+中端、缺低端”（用户 2026-10-19 接受环境受限）；以下不构成正式发布说明，tag/Release 由用户决定。

1. **B8/B9 数据与抽取**：Room 升级到 schema v7；文档 ready 后可在配置 LLM/开启 SAG 时后台抽取 1 event + 11 类 entity，写入事件/实体/SAG 向量/FTS；失败可重试/补抽取。
2. **B10 检索**：搜索页支持「混合 / SAG 快速 / SAG 精排」；multi_es_fast 离线可用；每次请求记录脱敏 trace，结果区可展开/导出。
3. **B11 带引用问答**：Chat 单轮问答，证据 `[S1]…[Sn]` 引用可点回原文；无证据拒答；非法/缺失引用警示；停止/重试；首次联网同意；API Key Keystore、不写日志/备份。真实 QA citation 100%、support 98%（B11 报告）。
4. **B12 图谱/设置/备份/诊断（环境受限通过）**：来源页新增「图谱」tab（event 卡片/详情/entities/chunk 原文/共享实体相关事件，source 过滤、分页上限 200）；设置新增高/中/低检索预算档位、抽取并发 1–4、检索/问答 trace 计数与脱敏导出/errorCode、隐私与备份说明；备份 v3 UI 增加格式/SAG 计数/旧包补抽取/隐私提示。签名 RC 已验签并在 PJZ110 clean install；三档矩阵为高端+中端、缺低端（用户接受环境受限），不自动发布。
