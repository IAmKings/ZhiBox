# Phase 0 技术验证报告（进行中）

- **开始日期**：2026-10-15
- **状态**：P0-1 ✅ / **P0-2 ✅（真机基准完成）** / P0-3–P0-7 未开始
- **判定门总览**：G1 任务模板 / G2 模型资产 / G3 中文分词 / G4 精确扫描 —— 详见实施计划第 3 节

## P0-1 工程初始化 ✅（2026-10-15）

- 可编译工程：`assembleDebug` 通过，包名 `com.zhibox.memory` 0.1.0-mvp，minSdk 24 / target 36，零权限。
- 参考素材：`docs/reference/zleap-sag-0.14.0/` 37 文件（wheel SHA-256 校验通过）。
- 版本锚点：见 `docs/UPSTREAM_VERSIONS.md`。git 初始提交 `7fff4ed`。

## P0-2 Universal Embedder 接入 ✅（2026-10-15，高端档真机）

### 接入过程中核验的事实

| 项 | 结果 |
|---|---|
| Maven 坐标 | `com.google.mediapipe:tasks-retrieval:1.1.1`（2026-10-14 发布） |
| 上游打包 bug 与修复 | POM 传递依赖 `litertlm-android:0.17.0-alpha1` 缺少 `com.google.ai.edge.litertlm.ActivationDataType`（运行时 NoClassDefFoundError，经 AAR 反编译定位）；已 `resolutionStrategy.force` 至 **0.18.0**（含该类，核验通过） |
| Kotlin 版本要求 | litertlm 以 Kotlin 2.4.0 编译 → 工程工具链升级至 Kotlin 2.4.21 + Gradle 8.14.3 |
| 真实包路径 | `com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder`（非文档示例的裸 `retrieval` 包） |
| 文件加载方式 | `BaseOptions` 无 `setModelAssetFilePath`；须持 `ParcelFileDescriptor` 传 `setModelAssetFileDescriptor(fd)` |
| 向量取值 | `Embedding.floatEmbedding()` 返回 `float[]`；`cosineSimilarity(float[], float[])` 静态可用 |
| 配置面 | `setL2Normalize` / `setTextDelegate` / `setVisionDelegate` / `setMaxInputLength` / `setActivationDataType` / `setCacheDir`；**未见任务模板/prompt 参数**（与调查报告 R1 预判一致，G1 需用向量对照法判定） |
| 模型投放（Android 16 实测） | adb 直推 `/sdcard/Android/data/<pkg>/` 产生 shell 属主目录，应用 uid 无权列出（UI 显示"未找到模型文件"）；正确方式：`/data/local/tmp` 中转 + `run-as cp` 入内部 `filesDir/models`（已固化进 `scripts/run_p02_benchmark.sh`） |

### 资源画像基准结果（PRD §9.2 门槛：单条 query embedding p95 ≤ 500ms 中高端档）

**三档设备矩阵全部通过**（2026-10-15，CPU/XNNPack，RAW 模板，440M 模型）：

| 设备档 | 机型 · SoC · RAM · Android | 冷加载 | 首条 | p50 | p95 (30次) | 峰值 RSS | 校验 | 结果 |
|---|---|---|---|---|---|---|---|---|
| 高端 | PJZ110 · SD 8 Elite (SM8750) · 16GB · Android 16 | 1032ms | 224ms | **79ms** | **81ms** | ≈1.38GB | 全过 | ✅ 余量 6.2× |
| 中高端 | K60 Ultra · 天玑 9200+ (MT6985) · 15GB · Android 14 | 1932ms | 582ms | **94ms** | **104ms** | ≈1.44GB | 全过 | ✅ 余量 4.8× |
| 低端 | K40 游戏版 (M2012K10C) · 天玑 1200 (MT6893) · 7.3GB · Android 13 | 2016ms | 535ms | **133ms** | **145ms** | ≈1.42GB | 全过 | ✅ 余量 3.4× |

270M 纯文本模型在三台设备（两厂商、Android 13/14/16）**全部加载失败**（同一错误：缺 vision encoder 签名）——确证为运行时结构限制而非设备兼容性问题。

### 低端档可行性与产品策略结论

1. **延迟维度：全矩阵通过。** 2021 年中端芯（天玑 1200）热推理 p95 仍有 3.4 倍余量，PRD §9.2 的 500ms 门槛对 2021+ 设备成立。
2. **内存维度：RSS ≈1.4GB 恒定（模型体积主导、与设备无关）。** 在 7.3GB 的 K40 上约占 19% 物理内存——前台主动使用（导入/搜索）可行；但**后台驻留场景（WorkManager 批量导入时用户开其他大应用）存在被 LMK 回收的实质风险**。
3. **产品策略（写入 Phase 1 要求）**：
   - MVP 声明**最低 RAM 档位 8GB**（覆盖 2021+ 主流机型）；4–6GB 设备标记为"可能受限"，实测后定案；
   - 导入管线必须支持**分批 + 进程被杀恢复**（实施计划 P1-W1-4 本就有此要求，此处为其提供了实证依据）；
   - RSS 优化路径（按优先级）：等待 ML Kit（官方口径 0.5GB，W1 监控项）→ 路径 B（EGemma v1，体积小一个量级）→ 评测 256d MRL 截断（只影响向量存储，不降模型内存）。
4. 芯片厂商覆盖：Qualcomm + MediaTek 双平台；Android 13/14/16 三个版本。首次安装分发（run-as 路径）在三个 Android 版本均验证通过。

原始 JSON 存于 `docs/phase0/bench-results/`（文件名带设备序列号）。

### G2 结论（R2 解锁）

1. **270M 纯文本模型不可用于当前 Universal Embedder 路径**：加载报 `tf_lite_vision_encoder not found in the model`——LiteRT-LM 运行时（0.18.0）要求 .litertlm 文件必须包含视觉编码器签名，纯文本变体被拒。该文件虽官方发布（download.gradle 列出），但属"为更低层 API 或未来运行时准备"的资产。
2. **440M（370MB 下载、含按需模态加载）是 Universal Embedder 路径的最小可用模型**，实测可用且性能达标。
3. 峰值 RSS ≈1.38GB（含文件页缓存口径）远高于博客的"文本 191MB"宣传口径——**中低端机（4–6GB RAM）可行性存疑，列入设备矩阵重点验证项**；若不可行，回退路径 B（Text Embedder + EGemma v1，模型体积小一个量级）或等待 ML Kit。
4. 修正模型分发预算：MVP 按 **370MB 下载体积**规划（Play Asset Delivery 或应用内下载器，P1-W1-5）。

### 意外发现：tasks-retrieval 自带分块器与 SQLite 向量存储

包内含 `retrieval.chunking.TextChunker/DefaultTextChunker` 与 `retrieval.components.SqliteVectorStore/VectorStore/VectorStoreRecord`——**Google 官方端侧检索栈自身即以 SQLite 作为向量存储**。这是调查报告附录 A"SQLite 替代 LanceDB"结论的又一官方佐证；Phase 1 设计时应评估直接复用/对照这两个组件（注意其设计围绕 SemanticRetriever 检索流程，需核实文本场景适配性）。

## P0-3 G1 任务模板对照（未开始）

- 基准模型锁定为 **440M**（270M 不可加载）；HF 参考向量用 `google/embeddinggemma-2` 文本编码器（270M text-only 对应权重）生成，桌面端脚本待建。
- 判定标准不变：三模板模式（RAW / 文档前缀 / 查询前缀）任一与 HF 参考向量余弦 ≥ 0.99。

## P0-4–P0-7（未开始）
