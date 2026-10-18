# ZhiBox Memory（端侧个人知识记忆 MVP）

本地优先的 Android 端侧 RAG 记忆系统骨架：文档导入 → 分块 → 端侧 embedding → SQLite 检索 → 原文证据回溯，全程可离线。架构移植自 SAG 的检索数据模型思想（Kotlin 重写，非直接复用 Python 引擎）。

当前状态：**Phase 1（离线搜索 MVP）已收尾，MVP-1 内部发布就绪**（验收清单 `docs/phase1/MVP1-ACCEPTANCE.md`：§9.1 十项中 8 项通过、2 项部分通过并记录遗留；§9.3 评测集现状 **262 条 / 50 卡**，MVP-1 验收时为 220 条/45 卡，B2 后扩容（见 `docs/phase1/W3-ROBUSTNESS-EVAL.md` §升级））；**B1–B7 修复批次已完成**（B6 文档/脚本同步随本版本发布）。版本 `versionName = 0.1.0-mvp`（仓库尚未打 tag，发布流程见 `docs/RELEASE.md`）。行为变更与升级/回滚见 [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)。

## 模型路线（B7 起）

产品仅保留 **EGemma2** 路线（3 种强度；SM8750 NPU 特化变体保留但需验证门通过后才可启用）：

| 档位 | 模型 | 运行时通道 |
|---|---|---|
| 轻量 | `embeddinggemma-2-text-270m.litertlm` | litertlm EmbeddingEngine（Path B） |
| 均衡（默认） | `embeddinggemma-2-text-vision-440m.litertlm` | MediaPipe UniversalEmbedder（Path A） |
| 性能上限 | `embeddinggemma-2-740m.litertlm` | UniversalEmbedder（Path A） |

旧 EmbeddingGemma `.task`（Path C）、Granite、LFM2.5 路线已下线；旧模型空间的向量不再参与检索，
升级后设置页会提示"旧模型空间"，相关文档需重新导入。模型文件不随 APK 分发（运行时下载/投放），
B7 后 release APK ≈49.07MB（arm64）。

## 构建

```bash
# Gradle wrapper 8.14.3；系统默认 Java 25 不被该 Gradle 支持，
# 需指定 Android Studio JBR 21：
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

- 工具链（当前）：Gradle **8.14.3**（wrapper，含 `distributionSha256Sum` 校验）/ AGP **8.13.2** / Kotlin **2.4.21** / KSP **2.3.12** / Compose BOM **2026.06.01**。
- 依赖完整性：`gradle/verification-metadata.xml` 已提交（613 components，strict 校验生效）；未登记的依赖会导致构建直接失败，排障时可临时 `--dependency-verification=off`（见 `app/build.gradle.kts` 注释）。
- minSdk 24 / targetSdk 36 / compileSdk 36，仅 `arm64-v8a`。
- 权限：仅声明 `INTERNET`，且只用于用户显式触发的模型下载（`networkSecurityConfig` 禁止明文，仅白名单 `hf-mirror.com` 的 HTTPS；URL 写死 + SHA-256 校验）；检索/导入/嵌入全链路离线，无其他权限。`allowBackup="false"`（个人知识库不参与系统备份）。
- `local.properties` 配置 SDK 路径（本机 `~/Library/Android/sdk`），不入库。

## 升级与迁移（B1–B7）

> 面向从 Phase 0 / 早期 MVP-1 升级的用户与维护者。完整细节、升级步骤与回滚流程见
> [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)；各批次实现与验收证据见 `docs/review/B*_FIX_REPORT.md`。

1. **向量空间加入 `@revision` 后缀**：同一模型文件名内容变化（换文件/重新下载）会生成新空间，不再静默复用旧向量；唯一 legacy 兼容为 EGemma2-440M 通用 CPU 的旧空间。
2. **备份格式升级为 v2**：含向量空间 meta/requirements 与校验；旧版 App 无法读取 v2 包，新版 App 可导入旧包并按第 6 条归一化。
3. **导入失败自动重试（最多 3 次总尝试）**：临时性错误（文件占用/IO 抖动/模型暂不可用）自动重排队，首次 + 最多 2 次自动重试后进入 `failed`，由用户手动重试；任务/来源页可见尝试次数与错误。
4. **原文文件不随备份迁移**：备份包含来源元数据、chunk 文本/向量与 FTS，不含原始文档文件；恢复后"打开原文"依赖原文件。
5. **同名换文件检测时点**：在检索/导入使用模型前按 SHA/修订指纹判定；检测到换文件 → 新空间 + 需重新导入/重嵌入。
6. **备份导出改为 ready-only；导入恢复语义**：导出只含 `ready` 文档及其 sources/chunks/vectors；旧包中 `pending/processing` 归一为 `failed`（避免半成品变 ready），`cancelled/failed` 原样保留；恢复后同一时间仅保留一个 active 空间。
7. **检索/索引行为修正**：emoji/代理对分词修复（升级后建议在设置页执行索引诊断/修复重建 bigram 索引）；query 先 NFC 规范化；错误码统一为 `CODE:detail`（UI 展示可读消息，日志保留 code）。

**B7 路线收敛的额外影响**：旧 `.task`（Path C）、Granite、LFM2.5 下线（旧模型文件保留在磁盘、不再识别）；升级后设置页提示"检测到 N 个旧模型空间"，相关文档需重新导入；含已下线空间的旧备份导入会在写库前被拒绝（原库不变）；新导出的备份会把"仅有旧模型向量"的文档标记为 `failed`（可见/可删/可重新导入）。270M/740M 为实验模型，建议验证门通过后再作为默认。

## 文档导航

| 文档 | 内容 |
|---|---|
| `docs/RELEASE_NOTES.md` | **发布说明**（v0.1.0-mvp：B1–B7 行为变更、升级/回滚步骤、已知限制） |
| `docs/product/PRD_ZhiBox_SAG_App_v1.0.md` | **后续应用功能 PRD**（P0 SAG 事件-实体索引/多跳检索/引用问答/图谱；P1/P2 范围） |
| `docs/review/SAG_APP_ALIGNMENT_REPORT.md` | **底座对齐报告**（当前实现 vs zleap-sag README/论文的能力矩阵与差距） |
| `docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md` | **后续开发路线图**（B8–B12 批次、依赖、门禁、P1/P2 backlog） |
| `docs/review/B6_FIX_REPORT.md` | B6 修复报告（文档漂移同步/脚本健壮性） |
| `docs/review/B8_FIX_REPORT.md` | B8 实施报告（schema v6/抽取契约/LLM 安全基础/备份 v3 骨架） |
| `docs/phase1/MVP1-ACCEPTANCE.md` | MVP-1 验收清单（§9.1/9.2/9.3 逐条对照与遗留项） |
| `docs/RELEASE.md` | release 构建、签名、安装与回滚流程 |
| `docs/SAG_Android_MVP_Implementation_Plan_v1.0.md` | **实施计划**（Phase 0/1 任务分解、判定门 G1–G4、验收映射） |
| `docs/SAG_Android_OnDevice_Feasibility_PRD.md` | 端侧可行性 PRD（需求、架构、验收标准 §9） |
| `docs/SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md` | 可行性调查报告（含附录 A：LanceDB 可替代性分析；附录 B：ANN 引擎选型全景） |
| `docs/Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md` | 最终项目目标（服务端形态） |
| `docs/UPSTREAM_VERSIONS.md` | 上游版本锚点（SAG commit / zleap-sag wheel / 构建链版本 / 模型资产） |
| `docs/UPSTREAM_WATCH.md` | 上游动态监控清单（ML Kit/MediaPipe/litertlm 版本跟踪与触发动作） |
| `docs/PHASE0_REPORT.md` | Phase 0 技术验证报告（判定门结论与实测数据） |
| `docs/reference/zleap-sag-0.14.0/` | 上游参考源码（只读对照，不参与编译） |

## 里程碑

- [x] M0 / P0-1：工程初始化 + 参考素材入库 + 版本锚点记录
- [x] P0-2：Universal Embedder 接入 + 三档设备资源画像（81/104/145ms p95）
- [x] P0-3–P0-6：判定门 G1 ✅（app-prefix，R@1=1.0，跨设备 |Δ|=0）/ G2 ✅（440M 基线）/ G3 ✅（bigram，hit 0.96）/ G4 ✅（最慢设备 10k p95 最优 8ms）
- [x] M-P0：PHASE0_REPORT.md 定稿，**go/no-go = GO**（2026-10-15）
- [x] Phase 1（W1–W3）：离线搜索 MVP → **MVP-1 内部发布**（验收见 `docs/phase1/MVP1-ACCEPTANCE.md`；W3 评测集 **262 条 = fact/synonym/multihop/exact 各 50 + adversarial 42 + numdate 20**（MVP-1 验收时 220 条 = 45×4 + 对抗 40，B2 后扩容））
- [x] B1–B7 修复批次：DB schema v5 / 备份 v2 / 检索质量与索引健康 / UI-UX 与状态组件 / 工程化与发布链路 / 测试补强 / 文档与脚本同步（B6）/ 模型路线收敛（B7，release APK 64.55→≈49.07MB）
