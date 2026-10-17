# 上游动态监控清单

> 背景：本项目处于 Google AI Edge 生态快速迭代窗口（EmbeddingGemma 2 于 2026-10-06 发布，周边栈一个月内多次破坏性变更）。本清单定义"盯什么、何时重评、谁触发"。
> 最近核查：2026-10-17（B7 路线收敛后）

## 监控项与触发动作

| # | 监控对象 | 当前状态（2026-10-15） | 检查方式 | 触发条件 → 动作 |
|---|---|---|---|---|
| W1 | **ML Kit EmbeddingGemma 2 API** | 未发布；官方博客（10-06）称"未来几周"，**产品计划月底前 GA**；博客口径"最低 0.5GB RAM" vs 我们实测 Universal Embedder 路径 1.4GB RSS | [ML Kit 门户](https://developers.google.com/ml-kit) + 官方博客 | **GA 即触发**：跑通 demo、实测 RAM/延迟/设备覆盖/NPU、是否托管模型分发、任务模板 API 有无；若 RAM 显著优于 1.4GB → 重评 G2 与 Provider 默认值（实施计划 Phase 3 预留位提前） |
| W2 | **MediaPipe tasks-retrieval** | 1.1.1（10-14 发布）；已知 bug：POM 传递依赖 litertlm alpha1 缺 ActivationDataType，已用 `dependencies.constraints` 固定 0.18.0（B4-1a） | [Google Maven 版本列表](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-retrieval/maven-metadata.xml)（Java 抓取，curl 被阻断） | 新版本发布 → 移除 constraints 覆盖重测；关注：原生任务模板 API、UniversalEmbedder 对 text-only 模型的支持（B7 后 270M 走 litertlm，不再阻塞） |
| W3 | **litertlm-android** | 0.18.0（强制解析）；0.17.0-alpha1 被上游 POM 错误引用 | [Maven 元数据](https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml) | 新 stable → 评估移除 constraints 覆盖；关注 ActivationDataType 相关 API 变更与 text-only 加载支持 |
| W4 | **HF litert-community 模型资产** | 270M（164,626,432 B）发布但不可被 Universal Embedder 加载（B7 后改由 litertlm EmbeddingEngine 承载）；440M 可用（387,710,976 B）；各 SoC 专用 740M 构建存在 | [litert-community 组织页](https://huggingface.co/litert-community)（经 hf-mirror 可达） | 新增 text-only 可加载变体 / 更小量化版 → 重跑 G2 资源画像 |
| W5 | **SAG / zleap-sag** | 锚点 0.14.0 + commit 56f073c；不追新 | [SAG releases](https://github.com/Zleap-AI/SAG/releases)（GitHub API 可达） | 仅在 Phase 1 设计定稿前核对一次 schema 变更；升级走独立 PR + 参考 diff（UPSTREAM_VERSIONS.md 流程） |
| W6 | **EmbeddingGemma 2 模型卡/权重** | google/embeddinggemma-2 锁定 @`914f7f8` | HF 模型卡 + ai.google.dev | revision 更新 → G1 参考向量需重新生成（`scripts/g1/gen_reference.py`）；prompt 规范变更 → 直接影响 TaskTemplatePolicy 定义 |
| W7 | **litertlm 1024 token 输入上限** | litertlm 0.18.0 签名限制（非模型卡 8192）；中文 1.35 token/字；700-800 字红线区行为不一致 | litertlm 发行说明 / tasks-retrieval 更新 | 上限放宽或 `setMaxInputLength` 生效 → 重测长度阶梯（`run_latency_matrix.sh`）并评估恢复 800 字块（更大块 = 更少块数 = 更少次调用） |
| W8 | **GPU delegate 设备覆盖** | **2026-10-16 更新**：GPU 自动策略已上线（4×OpenCL 声明 + 15s 超时探针 + AUTO 白名单=440M）；granite int8@8 Elite GPU 确定性挂死（两轮复现）；440M@8 Elite 6.2×（600字 449→72ms）；**740M×UE 通道 GPU 新通（G1E：32-44ms 几乎无长度增长，两次跨进程一致，740M 已改路由 UE 通道）**；B7 后 granite/LFM 已下线，270M 纯文本 litertlm 无法走 UE（vision encoder 缺失被拒）；AUTO 白名单现仅 440M 通用版；**MT 两平台 GPU 实为可用（三档矩阵收官，K60U/K40 与旗舰同构——Phase 0 失败系缺 manifest OpenCL 声明）；740M UE-GPU 三档 warm 32-348ms，向量按 GPU 家族聚簇（两 Mali 同 SHA）→ accelerator 已并入空间指纹** | litertlm 发行说明 / granite 仓库新量化变体 | （历史：granite 已随 B7 下线，不再加入 AUTO 白名单）MT GPU 支持出现 → 重评三档 GPU 列；740M UE-GPU 覆盖变化 → 重跑 G1E |
| W11 | **NPU 特化版生态（SOC 编译 .litertlm）** | **❌ 当前公开生态完全不可用**（2026-10-16 旗舰 4 次实验：litertlm 通道 / UniversalEmbedder+Delegate.NPU+NpuOptions 通道 × NPU/CPU 全部挂死；无 QNN dispatch runtime 可获取；Gallery 17 个线上 allowlist 版本零 NPU 引用=休眠代码）。已复刻 Gallery 通道代码（UniversalEmbedderProvider useNpu + NpuOptions），G1D 一键复测就绪 | [Gallery allowlist 目录](https://github.com/google-ai-edge/gallery/tree/main/model_allowlists) 新版本 + litertlm 发行说明 | **Gallery allowlist 首次出现 NPU/socToModelFiles 配置** 或 litertlm 新版提及 NPU 修复 → 拉 Gallery 对应 commit 模型复测 G1D；通过则特化版进入 perfMode 极速推荐 |
| W9 | **tasks-text（Text Embedder）**（**历史：B7 已下线，任务关闭**） | **0.10.35 已审计（2026-10-15）**：实际 API 仅 `embed(String)` + l2Normalize/quantize；文档宣传的 `TextFormatContext`（RETREVAL_QUERY/DOCUMENT）未随产物发布。**2026-10-16 转折：`.task` 资产由用户投放（`models/embedding_gemma.task`，gemma.tflite+spm，EGemma 300M 体量）→ 路径 C 已可跑**，K40 G1C 双策略（prefix/RAW）R@1=1.0、确定性过、768d、init 426ms；遗留：延迟与长度无关恒 ~1580ms（疑固定 seq 窗口），三档扩展中 | [tasks-text 版本列表](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-text/maven-metadata.xml)（Java 抓取） | 新版本发布 → javap 查 `TextFormatContext`（发布则替换应用层前缀，预期向量空间不变需复验）；`.task` 构建来源确认（用户投放件无出处标注，W2 确认是否固定窗口/能否换 seq256 变体降恒定延迟） |
| W10 | **litertlm EmbeddingEngine API（路径 B 载体）**（**部分历史：B7 后 Granite 下线，EmbeddingEngine 仍为 270M 载体**） | 0.18.0 公共 API：`EmbeddingEngine`/`EmbeddingEngineConfig(modelPath/modelFd, backend, cacheDir, maxInputLength)`/`EmbeddingOptions(normalize)`；granite-311m wi8fc `.litertlm` 为首个验证模型（未 gated，Apache-2.0，768d，签名上限 512 token） | litertlm 发行说明 + litert-community 模型仓库 | 新 stable → 复验 EmbeddingEngine 签名兼容（现载 270M）；关注 512 上限是否有动态长度变体；270M 可载性以验证门为准 |

## 检查节奏

- **Phase 1 收尾 / 发布后：每周一次（周一）**；B7 后重点为 W1/W2/W4/W7/W8/W11（EGemma2 相关），W9/W10 已转历史。
- 生态发布窗口（如 ML Kit GA）期间：每日一次。
- 任何一次检查结果记入本文件"核查日志"，有触发命中才开实现任务。

## 核查日志

| 日期 | 检查项 | 结果 | 动作 |
|---|---|---|---|
| 2026-10-15 | W1–W4 | ML Kit 未发布；tasks-retrieval 1.1.1 为最新；litertlm 0.18.0 为最新 stable；270M 仍不可加载 | 无（基线记录） |
| 2026-10-15 | W6 + G1 实验 | EGemma2 @914f7f8 锁定；**R1 解除**：应用层前缀方案通过 G1（检索 R@1=1.0）；0.99 等值门槛因 INT4 量化不可达，判定逻辑改为"模板跃升+检索功能"双证据 | 模板策略锁定为 app-prefix |
| 2026-10-15 | W9/W10 首查（路径 B 调查） | tasks-text 0.10.35 无 TextFormatContext（文档超前于产物）；EGemma300 gated+无 .task → 字面路线双重阻塞；**发现 litertlm 0.18.0 公共 EmbeddingEngine** → 路径 B 落地为 EmbeddingEngine + granite-311m（代码已落地，G1B 待真机） | 依赖树不引入 tasks-text；W9 转双条件触发监控 |

## 已知问题账本（上游修复后清理）

| 问题 | 我们的规避 | 上游修复信号 |
|---|---|---|
| tasks-retrieval 1.1.1 POM → litertlm 0.17.0-alpha1 缺 ActivationDataType | `dependencies.constraints` 固定 0.18.0（app/build.gradle.kts，含注释；B4-1a） | tasks-retrieval 新版 POM 指向 ≥0.18.0 |
| Universal Embedder 拒绝 270M 纯文本模型（缺 vision encoder 签名） | 基线用 440M；B7 后 270M 走 litertlm EmbeddingEngine（text-only 一等公民，无 vision 要求）→ **已闭环** | litertlm/tasks-retrieval 发行说明提及 text-only 支持 |
| litert-community/embeddinggemma-300m 仓库 gated（403）且无 .task 资产 | **B7 后不适用**：Path C（`.task`）已下线，产品不再依赖该仓库 | —（历史项） |
| ~~Universal Embedder 无任务模板/prompt API（R1）~~ **已解决（G1，2026-10-15）** | 应用层前缀：query 用 `task: search result \| query: `，document 用 `title: none \| text: `；运行时确认不加模板（RAW≈0.88 → 前缀对齐≈0.987，检索 R@1=1.0）。上游若发布原生 prompt API 可替换（预期向量空间不变，需复验） | tasks-retrieval/litertlm API 出现 prompt/task 参数 |

---

> **B7（2026-10-17）**：tasks-text / 旧 EmbeddingGemma `.task`（Path C）与 Granite/LFM 路线已下线，相关依赖从 APK 移除；本文件 W9/W10 及 granite 相关观察项标记为历史，无需再跟踪；产品路线仅 EGemma2（270M/440M/740M + SM8750 NPU 变体）。
