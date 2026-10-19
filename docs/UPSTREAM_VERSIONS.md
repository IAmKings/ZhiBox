# 上游版本与模型资产锚点

> **B7 路线收敛（2026-10-17）**：旧 EmbeddingGemma `.task`（Path C / mediapipe tasks-text）、
> Granite（Path B 模型路线）与 LFM2.5 已从产品下线并从构建依赖移除；生产路线仅保留
> **EGemma2 三强度模型**（440M / 270M / 740M + SM8750 NPU 变体）。本文档下方 granite / `.task` /
> LFM 条目为历史审计记录，保留用于回溯，**不再代表当前支持范围**（当前清单见
> `ModelCatalog.ENTRIES` 与 `docs/RELEASE.md`）。

> 本文件是实施计划（docs/SAG_Android_MVP_Implementation_Plan_v1.0.md）第 2 节"参考版本"决策的落地记录。
> 规则：实施期间不追新；任何锚点变更须走独立 PR 并重新执行对照 diff（见 docs/reference/zleap-sag-0.14.0/README.md 升级流程）。

## SAG 应用/论文锚点（2026-10-18 记录，用于后续功能 PRD/对齐报告）

| 锚点 | 值 | 说明 |
|---|---|---|
| SAG 应用 README-CN | main commit `a6f6a00f36ce0cbe43cd2fc0445b479e4ae4fff6`；README-CN blob `6b0df82e0a90457dc8f83efbd258b20d0b2b4596`（33,492 B） | 2026-10-16 抓取；产品功能/接口/集成/解析器/部署说明来源 |
| SAG 论文 | **arXiv:2606.15971v2**（SAG: SQL-Retrieval Augmented Generation with Query-Time Dynamic Hyperedges；2026-08-25 修订） | 事件-实体索引、动态超边、双路径检索、消融/局限/成本；后续 PRD 的机制依据 |
| 应用要求引擎版本 | `zleap-sag==0.13.0`（README-CN 当前要求） | 与本地参考 **0.14.0** 存在版本漂移；对齐报告已标注，升级参考须独立 PR + diff |

> 说明：应用仓库 README 约定了 `zleap-sag==0.13.0`，而本工程 `docs/reference/zleap-sag-0.14.0/` 为 0.14.0
> wheel 提取；引用其 API/行为时以本地参考为准，涉及应用层行为时以 README-CN 对应 commit 为准。

## 锚点三件套（2026-10-15 记录）

| 锚点 | 值 | 说明 |
|---|---|---|
| SAG 主仓库参考 commit | `56f073c1c0c300b46707c7249d48732730656cb0` | 2026-10-09 main 快照；`apps/api/pyproject.toml` 锁定 `zleap-sag==0.14.0` |
| 参考引擎包 | `zleap-sag==0.14.0` | PyPI；MIT；关系库默认 SQLite（SQLAlchemy + aiosqlite），向量库默认 LanceDB |
| 引擎 wheel SHA-256 | `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429` | 提取参考素材前已校验 |

## 本工程构建链版本（2026-10-15 记录；2026-10-17 B4 依赖升级 / B7 路线收敛后更新）

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | **8.14.3** | wrapper distributionUrl 指向华为云镜像（本机网络环境下官方源经 GitHub releases 不可达）；其他环境可改回 `services.gradle.org`；wrapper 已配置 `distributionSha256Sum`（B4-2） |
| Android Gradle Plugin | **8.13.2** | B4-1b 升级（8.9.1 → 8.13.2，8.x 最新）；支持 compileSdk 36 |
| Kotlin | **2.4.21** | **升级原因**：`tasks-retrieval:1.1.1` 传递依赖 `litertlm-android:0.17.0-alpha1`（Kotlin 2.4.0 元数据），Kotlin 2.1 无法读取其符号；B4 保持不升（KSP 无配对版本） |
| KSP | **2.3.12** | 与 Kotlin 2.4.21 配对的 Room/Compose 注解处理版本 |
| MediaPipe tasks-retrieval | 1.1.1 | 2026-10-14 发布；含 UniversalEmbedder（`universalembedder` 子包）、TextChunker、SqliteVectorStore |
| PDFBox-Android | **2.0.27.0** | P1 导入扩展 PDF 文本抽取（Apache-2.0）；AAR 3,254,019 B（classes.jar 1,756,101 B + assets 4.7MB 未压缩/约 1.6MB 压缩）+ 传递 BouncyCastle 1.72（bcprov 9,342,484 B / bcpkix 1,022,720 B / bcutil 677,620 B，Bouncy Castle Licence）。本机 Maven Central 对该 group 403，`settings.gradle.kts` 配 Aliyun central 限定 fallback（Central 优先，CI 可解析）；strict 校验元数据已含全部 artifact |
| LiteRT-LM (litertlm-android) | **0.18.0（constraints 固定）** | tasks-retrieval 1.1.1 POM 传递依赖 0.17.0-alpha1 缺 `ActivationDataType`（运行时崩溃）；0.18.0 经核验包含该类，改用 `dependencies { constraints { ... } }` 固定（B4-1a） |
| Compose BOM | **2026.06.01** | B4-1b 升级（Compose UI 1.11.4 / Material3 1.4.0）；再新的 BOM 要求 AGP 9.1+，本轮不升 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 | arm64-v8a 优先 |
| 构建用 JDK | Android Studio JBR 21（`/Applications/Android Studio.app/Contents/jbr`） | 系统默认 Java 25 不被当前 Gradle 支持，构建时通过 JAVA_HOME 指定 JBR 21 |

> 已核验 API（经 AAR javap）：`BaseOptions.Builder` 仅支持 `setModelAssetPath` / `setModelAssetFileDescriptor(Integer)` / `setModelAssetBuffer`，无 `setModelAssetFilePath`；文件路径加载须持 `ParcelFileDescriptor` 传 `fd`。`Embedding.floatEmbedding()` 返回 `float[]`。

## 模型资产（2026-10-15 下载并记录，本地 `models/` 不入库；B7 后产品路线仅 EGemma2 三模型）

| 资产 | 体积 | SHA-256 | 来源 |
|---|---|---|---|
| `embeddinggemma-2-text-270m.litertlm` | **164,626,432 B（157 MiB / 164.6 MB）** | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` | `litert-community/embeddinggemma-2-text-270m-litert-lm`（经 hf-mirror）；B7 后为轻量档（Path B / litertlm 引擎） |
| `embeddinggemma-2-text-vision-440m.litertlm` | **387,710,976 B（369.8 MiB / 387.7 MB）** | `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0` | `litert-community/embeddinggemma-2-text-vision-440m-litert-lm`（经 hf-mirror）；A 默认（UniversalEmbedder） |

**G2 相关发现**：官方示例的 `download.gradle` 明确列出 270M 纯文本模型发布于独立仓库（调查报告 R2 的疑问已解决——纯文本 MVP 不必携带 387.7MB 的 440M 文件）。

| 待补资产 | 状态 |
|---|---|
| Text Embedder + EmbeddingGemma v1（路径 B 回退） | **❌ 判定资产链未通（2026-10-15）**：仓库 gated（403）+ 无 `.task`（仅 `.tflite`+tokenizer）+ tasks-text 0.10.35 无 `TextFormatContext`。路径 B 改由 litertlm EmbeddingEngine + granite 承载，见 `docs/EMBEDDING_PATH_B_TextEmbedder_Fallback_Analysis.md` |
| HuggingFace 参考权重 `google/embeddinggemma-2`（G1 对照用） | ✅ 已锁定：revision `914f7f89142e33e77833254d9c9b90c3cef7303b`（768d，text-only 模式；经 hf-mirror）；参考向量 `scripts/g1/reference.json`（50 样本 × query/document 双模式）；生成环境 torch 2.14.1 / sentence-transformers 6.1.0 / transformers 5.19.0（需 pillow + torchvision，EmbeddingGemma2Processor 导入链依赖） |

## 路径 B（litertlm EmbeddingEngine + granite）审计记录（2026-10-15）——**历史；B7 起 granite 已下线，Path B 改由 EGemma2-270M 承载**

| 项 | 值 |
|---|---|
| tasks-text 版本审计 | 0.10.35（38 版最新）：`createFromFile`/`embed(String)`/`setL2Normalize`/`setQuantize`；**无 TextFormatContext/EmbeddingType**（javap 实证）；不引入依赖树 |
| litertlm EmbeddingEngine | 0.18.0 公共 API：`EmbeddingEngine(EmbeddingEngineConfig).initialize()`；`computeEmbedding(List<InputData.Text>, EmbeddingOptions(normalize=true)).embedding: FloatArray`；`EmbeddingEngineConfig(modelPath, backend=Backend.CPU(), cacheDir)`；含 `maxInputLength`/`modelFd`/`activationDataType` 旋钮 |
| granite-embedding-311m-r2_wi8fc.litertlm | 317 MiB（332,365,313 B）；SHA-256 `beb2be205abc766a670522e651be5713cecb4e4c33e5ef5c30f6a710d4226db5`；来源 `litert-community/granite-embedding-311m-multilingual-r2`（经 hf-mirror，未 gated，Apache-2.0）；768d / int8 / 签名 64-512 token / 对称无模板 / 上游声明与桌面端逐位一致 |
| embedding_gemma.task（路径 C） | 175 MiB（183,816,181 B）；SHA-256 `913b7a1edc7c7c3d1da3979ec1d0648ed9e0a370f181bb59ab177ca4b97707ad`；**用户投放**（2026-10-16）；内部：`gemma.tflite`（179MB）+ `sentencepiece.model`（4.7MB）+ `manifest.pb`；EGemma 300M 体量；实测 K40 延迟与长度无关（~1580ms 恒定）→ 疑似固定 seq 窗口构建（≥800 CJK 字可编） |

## 模型目录扩展资产（2026-10-16 登记，ModelCatalog 数据源）——**B7 后 catalog 仅保留 440M/270M/740M + 两个 SM8750 NPU 变体；下表 LFM 等为历史**

| 资产 | 体积 | SHA-256 | 来源/备注 |
|---|---|---|---|
| `embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm` | **426 MiB（446,611,456 B / 446.6 MB）** | `c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c` | SM8750 NPU 特化版；**❌ 0.18.0 实测不可用**（2026-10-16 旗舰：NPU 后端挂死 >7min；CPU 后端亦挂死——特化版为 NPU 专用编译产物）；验证门拦在启用前，litertlm 升级后复测 |
| `embeddinggemma-2-740m.litertlm` | **484,622,336 B（462.2 MiB / 484.6 MB）** | `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c` | 740M 通用（text+vision+audio）；**实验**（G1E 已通 UE-GPU 验证，文本嵌入可载性以验证门为准） |
| `LFM2.5-Embedding-350M_wi8fc.litertlm` | **353 MiB（370,251,584 B / 370.3 MB）** | `7efac24240da653ac29ccfa9a9bc1ee7d768a43f2073f11de7544f17462b3c7a` | LiquidAI LFM2.5，1024d；**B7 已下线（不在 ModelCatalog）**，仅历史审计 |
| `embeddinggemma-2-740m_Qualcomm_SM8750.litertlm` | **518 MiB（543,522,816 B / 543.5 MB）** | `ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996` | 740M SM8750 特化版；**⚠️ 预判同款不可用**（同构建链；未单独实测，验证门管控） |
| 270M 通用（已登记于上表） | **164,626,432 B（157 MiB / 164.6 MB）** | `2d079ee2…` | **实验**（Path B/litertlm 引擎可载性以验证门为准；此前仅路径 A 拒载记录） |

全部经 hf-mirror 匿名下载（ungated 确认）。EGemma2 三仓各含 11 个 `.litertlm`（generic + SM8550/8650/8750/8850/QCS8275 + MT6991/6993 + Tensor G5/G6 + Intel PTL），特化版命名 `_<Vendor>_<Chipset>`。

> 路径 B 旧空间指纹常量 `SPACE_ID_B`（granite）已随 B7 删除；现空间 ID 由 `EmbeddingRuntime.resolveSpaceId` 按模型指纹动态生成。
