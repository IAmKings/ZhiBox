# Phase 0 技术验证报告（进行中）

- **开始日期**：2026-10-15
- **状态**：P0-1 ✅ / P0-2 代码就绪（真机基准待设备重连） / P0-3–P0-7 未开始
- **判定门总览**：G1 任务模板 / G2 模型资产 / G3 中文分词 / G4 精确扫描 —— 详见实施计划第 3 节

## P0-1 工程初始化 ✅（2026-10-15）

- 可编译工程：`assembleDebug` 通过，包名 `com.zhibox.memory` 0.1.0-mvp，minSdk 24 / target 36，零权限。
- 参考素材：`docs/reference/zleap-sag-0.14.0/` 37 文件（wheel SHA-256 校验通过）。
- 版本锚点：见 `docs/UPSTREAM_VERSIONS.md`。git 初始提交 `7fff4ed`。

## P0-2 Universal Embedder 接入（代码完成，基准待跑）

### 已确认事实

| 项 | 结果 |
|---|---|
| Maven 坐标 | `com.google.mediapipe:tasks-retrieval:1.1.1`（2026-10-14 发布，当前唯一可用版本系列 1.1.0/1.1.1） |
| Kotlin 版本要求 | 传递依赖 `litertlm-android:0.17.0-alpha1` 以 Kotlin 2.4.0 编译 → 工程工具链已升级至 Kotlin 2.4.21 + Gradle 8.14.3 |
| 真实包路径 | `com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder`（非文档示例的裸 `retrieval` 包） |
| 文件加载方式 | `BaseOptions` 无 `setModelAssetFilePath`；须持 `ParcelFileDescriptor` 传 `setModelAssetFileDescriptor(fd)` |
| 向量取值 | `Embedding.floatEmbedding()` 返回 `float[]`；`cosineSimilarity(float[], float[])` 静态可用 |
| 配置面 | `setL2Normalize` / `setTextDelegate` / `setVisionDelegate` / `setMaxInputLength` / `setActivationDataType` / `setCacheDir`；**未见任务模板/prompt 参数**（与调查报告 R1 预判一致，G1 需用向量对照法判定） |

### 意外发现：tasks-retrieval 自带分块器与 SQLite 向量存储

包内含 `retrieval.chunking.TextChunker/DefaultTextChunker` 与 `retrieval.components.SqliteVectorStore/VectorStore/VectorStoreRecord`——**Google 官方端侧检索栈自身即以 SQLite 作为向量存储**。这是调查报告附录 A"SQLite 替代 LanceDB"结论的又一官方佐证；Phase 1 设计时应评估直接复用/对照这两个组件（注意其设计围绕 SemanticRetriever 图像检索流程，需核实文本场景适配性）。

### G2 相关发现（R2 部分解锁）

官方 `download.gradle` 确认 **270M 纯文本模型独立发布**：`litert-community/embeddinggemma-2-text-270m-litert-lm`（157MB）。纯文本 MVP 无需携带 388MB 的 440M 文件；两个模型均已下载并记录 SHA-256（见 UPSTREAM_VERSIONS.md）。

### 资源画像基准（待设备重连后运行）

- 设备（高端档）：PJZ110 · Snapdragon 8 Elite (SM8750) · 16GB RAM · Android 16 —— 中低端档设备待补。
- 运行方式（一键）：`./scripts/run_p02_benchmark.sh`（安装 → 推送模型 → 自动基准 → 拉取 JSON 至 `docs/phase0/bench-results/`）。
- 指标：冷加载 ms / 首条 ms / 热推理 p50·p95（30 次）/ 峰值 RSS（VmHWM）/ 维度 / 有限值 / L2 归一化偏差 / 自余弦。
- 结果表（待填）：

| 模型 | 冷加载 | 首条 | p50 | p95 | 峰值 RSS | 维度 | 校验 |
|---|---|---|---|---|---|---|---|
| embeddinggemma-2-text-270m | — | — | — | — | — | — | — |
| embeddinggemma-2-text-vision-440m | — | — | — | — | — | — | — |

## P0-3 G1 任务模板对照（未开始）

样本集与桌面端参考向量脚本待建；判定标准：三模板模式任一与 HF 参考向量余弦 ≥ 0.99。

## P0-4–P0-7（未开始）
