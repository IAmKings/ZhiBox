# 上游版本锚点记录

> 本文件是实施计划（docs/SAG_Android_MVP_Implementation_Plan_v1.0.md）第 2 节"参考版本"决策的落地记录。
> 规则：实施期间不追新；任何锚点变更须走独立 PR 并重新执行对照 diff（见 docs/reference/zleap-sag-0.14.0/README.md 升级流程）。

## 锚点三件套（2026-10-15 记录）

| 锚点 | 值 | 说明 |
|---|---|---|
| SAG 主仓库参考 commit | `56f073c1c0c300b46707c7249d48732730656cb0` | 2026-10-09 main 快照；`apps/api/pyproject.toml` 锁定 `zleap-sag==0.14.0` |
| 参考引擎包 | `zleap-sag==0.14.0` | PyPI；MIT；关系库默认 SQLite（SQLAlchemy + aiosqlite），向量库默认 LanceDB |
| 引擎 wheel SHA-256 | `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429` | 提取参考素材前已校验 |

## 本工程构建链版本（2026-10-15 记录，同日因依赖要求升级）

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | **8.14.3** | wrapper distributionUrl 指向华为云镜像（本机网络环境下官方源经 GitHub releases 不可达）；其他环境可改回 `services.gradle.org` |
| Android Gradle Plugin | 8.9.1 | 支持 compileSdk 36 |
| Kotlin | **2.4.21** | **升级原因**：`tasks-retrieval:1.1.1` 传递依赖 `litertlm-android:0.17.0-alpha1`（Kotlin 2.4.0 元数据），Kotlin 2.1 无法读取其符号 |
| MediaPipe tasks-retrieval | 1.1.1 | 2026-10-14 发布；含 UniversalEmbedder（`universalembedder` 子包）、TextChunker、SqliteVectorStore |
| LiteRT-LM (litertlm-android) | **0.18.0（强制）** | tasks-retrieval 1.1.1 POM 传递依赖 0.17.0-alpha1 缺 `ActivationDataType`（运行时崩溃）；0.18.0 经核验包含该类，`resolutionStrategy.force` 固定 |
| Compose BOM | 2025.01.00 | — |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 | arm64-v8a 优先 |
| 构建用 JDK | Android Studio JBR 21（`/Applications/Android Studio.app/Contents/jbr`） | 系统默认 Java 25 不被当前 Gradle 支持，构建时通过 JAVA_HOME 指定 JBR 21 |

> 已核验 API（经 AAR javap）：`BaseOptions.Builder` 仅支持 `setModelAssetPath` / `setModelAssetFileDescriptor(Integer)` / `setModelAssetBuffer`，无 `setModelAssetFilePath`；文件路径加载须持 `ParcelFileDescriptor` 传 `fd`。`Embedding.floatEmbedding()` 返回 `float[]`。

## 模型资产（2026-10-15 下载并记录，本地 `models/` 不入库）

| 资产 | 体积 | SHA-256 | 来源 |
|---|---|---|---|
| `embeddinggemma-2-text-270m.litertlm` | 157MB (164,892,672B 级) | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` | `litert-community/embeddinggemma-2-text-270m-litert-lm`（经 hf-mirror） |
| `embeddinggemma-2-text-vision-440m.litertlm` | 370MB | `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0` | `litert-community/embeddinggemma-2-text-vision-440m-litert-lm`（经 hf-mirror） |

**G2 相关发现**：官方示例的 `download.gradle` 明确列出 270M 纯文本模型发布于独立仓库（调查报告 R2 的疑问已解决——纯文本 MVP 不必携带 388MB 的 440M 文件）。

| 待补资产 | 状态 |
|---|---|
| Text Embedder + EmbeddingGemma v1（路径 B 回退） | 待 G2 盘点后记录 |
| HuggingFace 参考权重 `google/embeddinggemma-2`（G1 对照用） | ✅ 已锁定：revision `914f7f89142e33e77833254d9c9b90c3cef7303b`（768d，text-only 模式；经 hf-mirror）；参考向量 `scripts/g1/reference.json`（50 样本 × query/document 双模式）；生成环境 torch 2.14.1 / sentence-transformers 6.1.0 / transformers 5.19.0（需 pillow + torchvision，EmbeddingGemma2Processor 导入链依赖） |
