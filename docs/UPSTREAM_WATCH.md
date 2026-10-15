# 上游动态监控清单

> 背景：本项目处于 Google AI Edge 生态快速迭代窗口（EmbeddingGemma 2 于 2026-10-06 发布，周边栈一个月内多次破坏性变更）。本清单定义"盯什么、何时重评、谁触发"。
> 最近核查：2026-10-15

## 监控项与触发动作

| # | 监控对象 | 当前状态（2026-10-15） | 检查方式 | 触发条件 → 动作 |
|---|---|---|---|---|
| W1 | **ML Kit EmbeddingGemma 2 API** | 未发布；官方博客（10-06）称"未来几周"，**产品计划月底前 GA**；博客口径"最低 0.5GB RAM" vs 我们实测 Universal Embedder 路径 1.4GB RSS | [ML Kit 门户](https://developers.google.com/ml-kit) + 官方博客 | **GA 即触发**：跑通 demo、实测 RAM/延迟/设备覆盖/NPU、是否托管模型分发、任务模板 API 有无；若 RAM 显著优于 1.4GB → 重评 G2 与 Provider 默认值（实施计划 Phase 3 预留位提前） |
| W2 | **MediaPipe tasks-retrieval** | 1.1.1（10-14 发布）；已知 bug：POM 传递依赖 litertlm alpha1 缺 ActivationDataType，我们已 force 0.18.0 规避 | [Google Maven 版本列表](https://dl.google.com/android/maven2/com/google/mediapipe/tasks-retrieval/maven-metadata.xml)（Java 抓取，curl 被阻断） | 新版本发布 → 移除 force 重测；关注：270M 纯文本模型加载是否被支持（vision encoder 签名要求是否放宽）、原生任务模板 API 是否出现 |
| W3 | **litertlm-android** | 0.18.0（强制解析）；0.17.0-alpha1 被上游 POM 错误引用 | [Maven 元数据](https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml) | 新 stable → 评估移除 force；关注 ActivationDataType 相关 API 变更与 text-only 加载支持 |
| W4 | **HF litert-community 模型资产** | 270M 发布但不可被 Universal Embedder 加载；440M 可用（370MB）；各 SoC 专用 740M 构建存在 | [litert-community 组织页](https://huggingface.co/litert-community)（经 hf-mirror 可达） | 新增 text-only 可加载变体 / 更小量化版 → 重跑 G2 资源画像 |
| W5 | **SAG / zleap-sag** | 锚点 0.14.0 + commit 56f073c；不追新 | [SAG releases](https://github.com/Zleap-AI/SAG/releases)（GitHub API 可达） | 仅在 Phase 1 设计定稿前核对一次 schema 变更；升级走独立 PR + 参考 diff（UPSTREAM_VERSIONS.md 流程） |
| W6 | **EmbeddingGemma 2 模型卡/权重** | google/embeddinggemma-2 锁定 @`914f7f8` | HF 模型卡 + ai.google.dev | revision 更新 → G1 参考向量需重新生成（`scripts/g1/gen_reference.py`）；prompt 规范变更 → 直接影响 TaskTemplatePolicy 定义 |

## 检查节奏

- **Phase 0 期间（本周）：每日一次**——生态处于发布窗口（ML Kit 月底前 GA 计划）。
- Phase 1 期间：每周一次（周一）。
- 任何一次检查结果记入本文件"核查日志"，有触发命中才开实现任务。

## 核查日志

| 日期 | 检查项 | 结果 | 动作 |
|---|---|---|---|
| 2026-10-15 | W1–W4 | ML Kit 未发布；tasks-retrieval 1.1.1 为最新；litertlm 0.18.0 为最新 stable；270M 仍不可加载 | 无（基线记录） |
| 2026-10-15 | W6 + G1 实验 | EGemma2 @914f7f8 锁定；**R1 解除**：应用层前缀方案通过 G1（检索 R@1=1.0）；0.99 等值门槛因 INT4 量化不可达，判定逻辑改为"模板跃升+检索功能"双证据 | 模板策略锁定为 app-prefix |

## 已知问题账本（上游修复后清理）

| 问题 | 我们的规避 | 上游修复信号 |
|---|---|---|
| tasks-retrieval 1.1.1 POM → litertlm 0.17.0-alpha1 缺 ActivationDataType | `resolutionStrategy.force 0.18.0`（app/build.gradle.kts，含注释） | tasks-retrieval 新版 POM 指向 ≥0.18.0 |
| Universal Embedder 拒绝 270M 纯文本模型（缺 vision encoder 签名） | 基线用 440M | litertlm/tasks-retrieval 发行说明提及 text-only 支持 |
| ~~Universal Embedder 无任务模板/prompt API（R1）~~ **已解决（G1，2026-10-15）** | 应用层前缀：query 用 `task: search result \| query: `，document 用 `title: none \| text: `；运行时确认不加模板（RAW≈0.88 → 前缀对齐≈0.987，检索 R@1=1.0）。上游若发布原生 prompt API 可替换（预期向量空间不变，需复验） | tasks-retrieval/litertlm API 出现 prompt/task 参数 |
