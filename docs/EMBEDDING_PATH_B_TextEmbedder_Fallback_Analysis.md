> **历史文档（B7, 2026-10-17）**：本文分析的路径 B（Granite/Path B）与 Path C（TextEmbedder/.task）
> 已随产品路线收敛下线（仅保留 EGemma2 三模型）；本文件保留作决策记录，不再更新。
> 当前迁移与旧数据提示见 `docs/review/B7_FIX_REPORT.md`。

# Embedding 路径 B 兜底分析：Universal Embedder 保真度 + Text Embedder 可行性调查

> 日期：2026-10-15 · 状态：**结论已定，代码已落地（路径 B 载体修正为 LiteRT-LM EmbeddingEngine + Granite）**
> 请求：① 深度核对当前 UniversalEmbedderProvider 是否按官方 demo 实现；② 调研 MediaPipe Text Embedder（tasks-text）能否承担 embedding、价值如何、可否作为 Universal Embedder 正式发布前的兜底。
> 关联：`docs/SAG_Android_OnDevice_Feasibility_PRD.md` §4.1（双路径预留）、`docs/PHASE0_REPORT.md`（G1/G2 结论）、`docs/UPSTREAM_WATCH.md` W1–W8。

---

## 结论速览

1. **保真度**：当前 `UniversalEmbedderProvider` 对官方 demo 的实现**忠实**——同一加载方式（PFD→fd）、同一 options 语义（L2Normalize）、同一推理调用链（`embedText().embeddings().first()`）、同一线程契约。发现 **2 处差异**：`setCacheDir` 缺失（已修复）与 OpenCL 原生库声明缺失（GPU 路线才需要，暂不加）。应用层任务模板前缀是有意增强（G1 已验证，demo 场景是跨模态相似度演示而非检索）。
2. **Text Embedder（tasks-text）**：API 本身可以做 embedding，但**当前版本不是可用的兜底载体**——文档宣传的 `TextFormatContext`（RETRIEVAL_QUERY / RETRIEVAL_DOCUMENT 原生检索模板）**未随 0.10.35 发布**；EmbeddingGemma 300M 模型**仓库 gated（403）+ 无 `.task` 资产**（只有 `.tflite` + 独立 tokenizer）。双重阻塞。
3. **路线修正（本轮关键发现）**：已在 classpath 上的 **litertlm-android 0.18.0 公开暴露 `EmbeddingEngine` API**（AAR javap 实证），可直接运行 `.litertlm` 嵌入包。**路径 B 落地为：EmbeddingEngine + granite-embedding-311m-multilingual-r2（wi8fc）**——零新增依赖、模型未 gated（Apache-2.0）、多语言含中文、768d、上游声明与桌面端逐位一致。代码已全部落地，待三档真机 G1B 验证。

---

## 一、保真度对照：我们的实现 vs 官方 demo

取证对象：`google-ai-edge/mediapipe-samples` @ main 的 `examples/universal_embedder/android`（`UniversalEmbedderHelper.kt` 全文、`MainActivity.kt`、`AndroidManifest.xml`、`app/build.gradle`、`download.gradle`）。

| 维度 | 官方 demo | 我们（`UniversalEmbedderProvider`） | 判定 |
|---|---|---|---|
| 依赖 | `tasks-retrieval:1.1.0` + 显式 `litert-android:0.18.0` | `tasks-retrieval:1.1.1` + 强制 `litertlm-android:0.18.0` | ✅ 等价。两者都在钉 0.18.0 LiteRT 家族；我们的 force 针对 POM 坏钉（alpha1 缺 `ActivationDataType`），demo 的显式依赖是同一意图的另一种写法 |
| 模型文件 | assets → filesDir 复制 | 应用内下载器 → filesDir（MVP 不打包 assets） | ✅ 等价（合理差异：370MB 走 assets 不可行） |
| 加载方式 | `ParcelFileDescriptor.open(...).use { pfd → setModelAssetFileDescriptor(pfd.fd) }` | PFD 打开后持有至 `close()` | ✅ 等价。demo 建完引擎即关 PFD，我们持有更久——资源生命周期更保守，无功能差异 |
| Options | `setL2Normalize(true)` + **`setCacheDir(cacheDir)`** + delegate | `setL2Normalize(true)` + delegate | ⚠️ **差异①**：`setCacheDir` 缺失 → 已于本轮修复（demo 保真对齐）。**实测（K40/440M）：冷启动 2016ms（历史无缓存基线）vs 1966ms（热缓存）——无可测收益**，缓存产物仅 18KB；Run1 观察到的 9057ms 为装机后首跑噪声（dexopt/IO 争用），非缓存构建成本。保留该设置的理由是保真而非性能 |
| delegate | UI 开关，默认 GPU | 默认 CPU（`useGpu=false`） | ✅ 有意偏离：GPU 仅 SM8750/Adreno 可用（MT6985/MT6893 引擎创建失败，价值倒挂），CPU/XNNPack 为通用基线（调查报告 R4） |
| 推理调用 | `engine.embedText(text).embeddings().first()` | 同 API + `.floatEmbedding()` + 有限值校验 + 维度回填/漂移检查 | ✅ 一致（我们多三层防御） |
| 线程契约 | "Not thread safe"，单线程 dispatcher 串行 | 进程单例 + `providerMutex` 串行 | ✅ 同契约 |
| 任务模板 | 无——demo 嵌 raw 文本做跨模态相似度演示 | 应用层双前缀（query=`task: search result \| query: `，document=`title: none \| text: `） | ✅ 有意增强：G1 判定门验证（RAW≈0.88 → 前缀对齐≈0.987，检索 R@1=1.0）；检索场景必须区分任务模板 |
| manifest | 4×`uses-native-library`（`libOpenCL.so`/`libOpenCL-car.so`/`libOpenCL-pixel.so`/`libGLES_mali.so`，均 `required=false`，注释：Android 12+ GPU delegate 加载厂商 OpenCL 驱动用） | ✅ **已补齐（2026-10-16，差异②闭环）**：GPU 自动策略上线（见 §六） |
| `noCompress` | `'tflite','litertlm'`（assets 打包要求） | 不打包 assets，N/A | ✅ 合理差异 |
| 相似度计算 | `UniversalEmbedder.cosineSimilarity(a, b)` 静态方法 | 检索扫描内联自算 | ✅ 等价 |

**判定：实现忠实。** 唯一实质缺口 `setCacheDir` 已修复；demo 冷启动复测见 §四。

---

## 二、Text Embedder（tasks-text）调查：可行但有双重阻塞

### 2.1 API 事实（文档 vs 产物，全部一手核验）

| 证据 | 内容 |
|---|---|
| 文档（developers.google.com，Android 指南 2026-08-17 / 总览 2026-10-01 更新，**非 legacy**） | 描述 `TextEmbedder` + `TextFormatContext`（`EmbeddingType.RETREVAL_QUERY` / `RETRIEVAL_DOCUMENT`），模板串 `task: <task> \| query: <text>` 与 `title: <title> \| text: <text>`——与 G1 手工验证的前缀族完全同构；宣称支持 EmbeddingGemma 300M（约 200ms CPU @S26U，512 token 上限）；依赖坐标 `com.google.mediapipe:tasks-text:latest.release` |
| **产物（AAR javap，0.10.35 = 当前最新，38 个版本）** | 实际 API 面只有：`createFromFile(Context, String/File)`、`createFromOptions`、`embed(String): TextEmbedderResult`、`cosineSimilarity(a,b)`；Options 仅 `setBaseOptions` / `setL2Normalize` / `setQuantize`。**没有 `TextFormatContext`、没有 `EmbeddingType`、没有 `embed(text, formatContext)` 重载** |
| GitHub issue [#6217](https://github.com/google-ai-edge/mediapipe/issues/6217)（2026-01-14，Open） | EmbeddingGemma 未被 MediaPipe **Web** Text Embedder 支持；维护者 01-20 回复"考虑在未来版本支持"。平台标签 javascript——不阻塞 Android，但佐证 EGemma × TextEmbedder 的支持是进行时而非完成时 |
| 第三方信号（flutter_edge_ai，2026-10-04 更新） | 将 MediaPipe 标注为"maintenance-only upstream，prefer .litertlm"；EGemma1/Gecko 分类为 ".tflite + tokenizer file" 模型（即不经 MediaPipe .task 通道） |

### 2.2 模型资产事实

| 资产 | 状态（2026-10-15 实测） |
|---|---|
| `litert-community/embeddinggemma-300m` | **gated**：匿名 `resolve` 下载 403（"You must have access… authenticated"）；文件清单 39 个：seq256/512/1024/2048 × mixed-precision `.tflite`（含 Tensor G4/G5/G6、MT6991/6993、SM8550/8650/8750/8850 NPU 专版）+ 通用版 + `sentencepiece.model`。**无任何 `.task` 文件** |
| Google 官方模型存储 | `storage.googleapis.com/mediapipe-models/text_embedder/embeddinggemma*` → 404（多种路径组合均不存在） |
| Kaggle `google/gemma` | 未验证到可直接匿名拉取的 EGemma `.task`（搜索摘要提及存在，无直接 URL） |
| 转换自建 | [HF → MediaPipe Task 转换指南](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)存在（safetensors → .task），但属自建资产管线，超出兜底定位的成本预算 |

### 2.3 结论

- **"能否进行 embedding 操作"**：能——`TextEmbedder.embed(String)` 是正式发布、长期维护的 API（自 0.10.x 起），`createFromFile` + `setL2Normalize` 用法与我们的 Provider 抽象同构。
- **"价值"**：如果 `TextFormatContext` 发布且 EGemma `.task` 可得，价值 = 原生检索模板（替掉应用层前缀 hack）+ 正式依赖树。**当前两者皆未就绪。**
- **"能否作为兜底"**：**字面上不能**（API 未发布 + 模型 gated/无 .task 双重阻塞）。此前调查报告预留的"路径 B = Text Embedder + EGemma v1"判定为**资产链未通**，改由 §三的载体承接。
- 已从 `app/build.gradle.kts` 撤下试加的 tasks-text 依赖（避免无用原生库进 APK）；版本与 API 面已登记 UPSTREAM_WATCH W9，`TextFormatContext` 发布 + `.task` 资产可得时重评。

---

## 三、路径 B 落地：LiteRT-LM EmbeddingEngine + Granite（本轮关键发现）

### 3.1 发现链

1. Granite 模型卡（`litert-community/granite-embedding-311m-multilingual-r2`，未 gated）明确写道："**Since litert-lm 0.17.0 the same weights are also published as LiteRT-LM EmbeddingEngine bundles (.litertlm) that the runtime loads without any host-side tokenization or pooling code**"。
2. 我们 classpath 上**强制钉住的 `litertlm-android:0.18.0`** 满足 ≥0.17.0。解包本地 Gradle 缓存 AAR javap 实证公共 API：

```
com.google.ai.edge.litertlm.EmbeddingEngine          — computeEmbedding / computeEmbeddingBatch（+Async/Callback 变体）
com.google.ai.edge.litertlm.EmbeddingEngineConfig    — modelPath|modelFd, backend, visionBackend, audioBackend, cacheDir, maxInputLength, activationDataType
com.google.ai.edge.litertlm.EmbeddingOptions         — normalize, insertSpecialTokens, outputSize
com.google.ai.edge.litertlm.EmbeddingResponse        — embedding: FloatArray
com.google.ai.edge.litertlm.InputData.Text(String)
```

3. **即：兜底路径不引入任何新依赖**——把模型从 EGemma2 `.litertlm` 换成 Granite `.litertlm`，运行时、加载管线、工程链全部复用。

### 3.2 首个验证模型：granite-embedding-311m-multilingual-r2（wi8fc）

| 属性 | 值 | 对项目意义 |
|---|---|---|
| 许可 / 分发 | Apache-2.0 / **未 gated**（hf-mirror 匿名可下） | 兜底可用性的硬前提（EGemma300 403 的对照面） |
| 架构 / 语言 | ModernBERT bi-encoder，多语言（含中文） | 中文检索质量待 G1B 实测（见风险） |
| 维度 / 模板 | 768d（与路径 A 同维但**向量空间完全不同**）/ **对称无模板契约**——上游实测加前缀反而降低检索（nDCG 0.836→0.832，R@5 0.900→0.860） | TaskTemplatePolicy = RAW/RAW；比路径 A 的前缀策略更简单 |
| 量化 / 体积 | int8 动态量化（wi8fc）/ 317MB | 与 440M（370MB）同量级 |
| 序列上限 | 签名 64/128/256/**512** token（超过即抛错，不截断） | **低于路径 A 的 1024** → 路径级 maxChars=300（保守值，G1B 探针后校准） |
| 一致性 | 上游声明与桌面端**逐位一致**（跨 5 种文字 × 4 签名长度） | 跨设备确定性预期与路径 A 相同（G1B 验证） |
| RAM | 上游实测 int8 峰值 518MiB（iPhone 17 Pro，全签名） | 与 440M 路径同量级（G1B 顺带观察） |
| 性能参考 | 上游：Pixel 8 ≈286ms/条（seq256，flutter_edge_ai 通道） | 预期慢于 440M 路径 A 的旗舰曲线——兜底可接受，G1B 实测三档 |

同仓库备选：`LFM2.5-Embedding-350M`（1024d、lfm1.0 许可、无中文标签）——未选用（维度不符 schema 预期、许可弱、中文无官方声明）。

### 3.3 已落地代码（本轮）

| 变更 | 文件 | 说明 |
|---|---|---|
| 保真修复 | `core/embedding/UniversalEmbedderProvider.kt` | 补 `.setCacheDir(cacheDir)`（差异①；litertlm 层 `EmbeddingEngineConfig.cacheDir` 同义已用于路径 B） |
| 路径 B Provider | `core/embedding/LiteRtLmEmbeddingProvider.kt` | `EmbeddingEngine` + `EmbeddingOptions(normalize=true)`，RAW 对称策略，维度回填/漂移检查/协作取消与路径 A 同构；`PROVIDER_ID="litertlm-engine"` |
| G1B 验证基准 | `benchmark/PathBVerification.kt` | 初始化计时（首/二次，量化 cacheDir 收益）、50 样本配对检索 R@1/R@5/MRR（总体 + zh/en/mix 子集）、会话内逐位确定性 + 向量 SHA-256 指纹（跨设备比对凭据）、长度×延迟（30/200/400）+ 序列上限探针（500/550/600/800）。门槛：确定性必须过、R@1≥0.8 |
| 入口 | `ui/BenchmarkScreen.kt` + `MainActivity` | 新按钮"路径B验证"；自动化 extra `autostart_pathb` |
| 双路径导入 | `core/ingest/IngestRepository.kt` | 模型显式名单（440m 优先 / granite 兜底）；`createProvider`/`spaceIdFor`/`chunkerFor`（路径 B 块长 300）；空间指纹 `SPACE_ID_B="litertlm-engine/granite-311m/raw-symmetric-v1"`——**768d 相同也绝不混用**（providerId 区分） |
| 依赖 | `app/build.gradle.kts` | 撤下 tasks-text（§2.3），留注释指回本报告 |

### 3.4 决策记录（ADR）

- **ADR-PB-1**（2026-10-16 修订）：**默认路径切换为 B（granite）**——依据 W3 质量终审：220 条评测与 440M 等效（全指标差距 ≤0.006）、单查快 2.3×（72 vs 163ms @K40）、RSS 峰值省近半（688MB vs ~1.35GB）。落地为 `PathPreferences` auto 顺序 granite→440M→.task；440M 降级为可选项（偏好 A 仍可指定）。切换即全量重嵌入（`embedding_space_id` 隔离）。
- **ADR-PB-2**：tasks-text 不进依赖树，转 UPSTREAM_WATCH W9；触发条件：`TextFormatContext` 出现在发布产物 **且** EGemma（或同级多语言模型）有未 gated `.task` 资产。
- **ADR-PB-3**：路径 B chunker 保守块长 300 字，G1B 探针给出实测 token/字比后校准（联动 Chunker.estimateTokens 的路径级参数化，W2 检索服务一并收口）。
- **ADR-PB-4**：OpenCL 原生库声明（demo 差异②）不随本轮添加——CPU 是默认与兜底路线的共同基线；GPU 重启时（W8）与 `useGpu` 开关一起加。

## 三·五、2026-10-16 增补：路径 C（tasks-text TextEmbedder + embedding_gemma.task）

`.task` 资产到位（用户投放：`models/embedding_gemma.task`，175MB，内部 `gemma.tflite` + `sentencepiece.model` + manifest，EGemma 300M 体量，SHA `913b7a1e…`）——§2.3 的"模型资产链不通"判定解除（API 未发布 TextFormatContext 的部分仍成立，模板由应用层前缀承担，与路径 A 同一套 `TaskTemplatePolicy`）。

**落地**：`TextEmbedderProvider`（`createFromOptions` + `setModelAssetPath` + `setL2Normalize`，providerId=`mediapipe-text`）；`PathBVerification` 泛化为 provider 无关；G1C 入口（按钮 + `autostart_g1c` extra + `scripts/run_g1c.sh`，前缀/RAW 双策略对照）；IngestRepository 三路径路由（440M → granite → .task），`SPACE_ID_C="mediapipe-text/egemma-300m/app-prefix-v1"`。

**K40 实测（2026-10-16 01:40，G1C）**：

| 指标 | prefix 策略 | RAW 策略 |
|---|---|---|
| 配对检索 R@1 / MRR | **1.0 / 1.0**（zh 13、en 7、mix 5 全 1.0） | 1.0 / 1.0（天花板效应，配对集无法区分模板质量；采用模型卡契约的 prefix 为默认） |
| 确定性 | 会话内逐位一致，指纹 `5d640055…` | 会话内逐位一致，指纹 `ccb42646…` |
| 维度 / init | 768 / **426→321ms（三路径最快**：A≈2000ms、B≈7093ms） | 同 |
| 延迟曲线 | **与长度无关恒 ~1580ms**（30/200/400 字全等）——疑固定 seq 窗口构建（≥800 字可编，对应 seq≥1024/2048 包） | 轻微长度相关（1779→2079ms） |
| 导入冒烟 | 3/3 ready，向量全落 `SPACE_ID_C`，空间隔离正确 | — |

**K60 Ultra 实测（2026-10-16 01:56，G1C + G1B）**：

- G1C：双策略 R@1=1.0；跨设备逐位一致——prefix 两机同 `5d640055…`，raw 两机同 `ccb42646…`（策略改变向量、各自跨设备稳定）；init 344ms；延迟恒 ~817ms
- G1B（granite）：R@1=1.0；跨设备逐位一致（`89d43f2d…` 与 K40 相同）；init 1302ms；延迟 30字=32ms / 400字=152ms

**旗舰 PJZ110 实测（2026-10-16 02:15，G1C + G1B）——三档收官**：

- G1C：双策略 R@1=1.0；init 218ms（三机最快）；延迟恒 ~1178ms（**反直觉地慢于 K60U 的 797ms**——实测记录，疑与 tasks-text CPU delegate 固定线程数/大核调度有关，W2 复核）
- G1B（granite）：R@1=1.0；init 1024ms；延迟 30字=25ms / 200字=49ms / 400字=112ms（三档最快）

### 三档最终矩阵（2026-10-16 收官）

| | 旗舰 PJZ110 (SM8750) | K60U (MT6985) | K40 (MT6893) | 跨设备确定性 |
|---|---|---|---|---|
| **B granite**（30字/200字/400字） | **25/49/112ms** | **32/66/152ms** | 157/354/1118ms | ✅ 三机逐位一致 `89d43f2d…` |
| **C .task prefix** | 1178/1147/1178ms | 797/797/806ms | 1580/1583/1580ms | ✅ 三机逐位一致 `5d640055…` |
| C .task raw（对照） | 1172/1173/1142ms | 817/813/819ms | 1779/2077/2079ms | ✅ 三机逐位一致 `ccb42646…` |
| A 440M（历史 lenbench 参照，30字/600字） | 62/346ms | 97/553ms | 126/1026ms | ✅（G1 已证） |

**判定**：
1. **三路径 × 三档全部 R@1=1.0、768d、序列 ≥800 字可用、跨设备逐位一致**——路径 B/C 作为可切换生产路径的全部硬性门槛通过。
2. granite 在旗舰/K60U 快于路径 A 2.5-3×（25 vs 62ms；32 vs 97ms）；K40 短文本略慢（157 vs 126ms）——**中高端机型上 granite 是性能优选**（质量终审待 W3 200 条评测集）。
3. `.task` 恒定延迟三档复现（797-1580ms，与长度无关），固定 seq 窗口构建坐实；旗舰反而慢于 K60U 的现象待 W2 复核线程配置。
4. init：C（218-426ms）< A（~1000-2000ms）< B（1024-7093ms，对 SoC 敏感）。

**路径对比小结（K40 + K60U 实测）**：短文本单次调用 K40 上 A=126ms / B=157ms / C=1580ms（C 恒定，慢 10×）；**K60U 上 B=32ms 反超 A=97ms（3×）**；初始化 C 最快（344-426ms）。C 的固定窗口代价使它在批量导入场景不经济；B（granite）在中低端机呈现性能优势。旗舰档待测。

## 四、待验证清单（真机，G1B）

| # | 项目 | 设备 | 通过标准 | 结果 |
|---|---|---|---|---|
| 1 | G1B 全项（R@1/确定性/维度/延迟/序列探针） | 三档（PJZ110 / K60U / K40） | 确定性=逐位一致（三机向量 SHA-256 相同）；R@1≥0.8；zh 子集不塌方（对照总体 −0.1 以内） | **三档全 ✅（2026-10-16 收官，见 §三·五矩阵）**：granite R@1=1.0 三机同指纹 `89d43f2d…`；.task prefix/raw 双策略 R@1=1.0 各自三机同指纹 |
| 2 | 路径 A setCacheDir 冷启动复测 | K40（已测） | — | **已测（2026-10-16 00:07-00:10）**：冷缓存 9057ms（装机首跑噪声）/ 热缓存 1966ms vs 历史无 setCacheDir 基线 2016ms → **无性能差异**，保真修复照留；首跑噪声数据同时提示"装机后首轮基准不可作数" |
| 3 | 路径 B 导入冒烟 | 任一真机 | granite 单模型在位 → 导入 10 文档 → done；移除 granite 只留 440M → 再导入 → 空间隔离（两空间各行其是，混用被 spaceId 拒绝） | **K40 ✅（2026-10-16 01:10）**：granite 单模型 10/10 ready、10 向量全落 `litertlm-engine/granite-311m/raw-symmetric-v1`（768d）；加回 440M 再导 3 篇 → 3/3 ready、落 `mp-universal/egemma2-440m/app-prefix-v1`，**双空间行共存、同 768d 互不混用**，findModel 的 440M 优先路由正确 |

### 验证过程中的两个附带收获

1. **WAL 读取教训**：`run-as cat databases/zhibox.sqlite3` 不带 `-wal`/`-shm` 会读到陈旧快照（曾误判"导入卡死 6/10"，实际 10/10 早已完成）。后续所有拉库脚本必须三文件齐拉。
2. **中断任务回收修复**（顺手落码）：`claim` 只认 `queued`，进程被杀时处于 parsing..storing 中间态的 job 将永久悬挂。已加 `IngestJobDao.requeueInterrupted()` 并在 `IngestBatchWorker.doWork()` 起点——唯一 worker 启动时回收上一代遗留中间态（幂等重做）。属 W3 健壮性范畴的前置修复。

## 五、参考

- 官方 demo：[mediapipe-samples/examples/universal_embedder/android](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/universal_embedder/android)
- Text Embedder 文档：[总览](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder) · [Android 指南](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android)
- 模型仓库：[litert-community/embeddinggemma-300m](https://huggingface.co/litert-community/embeddinggemma-300m)（gated）· [litert-community/granite-embedding-311m-multilingual-r2](https://huggingface.co/litert-community/granite-embedding-311m-multilingual-r2)（未 gated）
- [mvnrepository：tasks-text 版本史](https://mvnrepository.com/artifact/com.google.mediapipe/tasks-text) · [mediapipe issue #6217](https://github.com/google-ai-edge/mediapipe/issues/6217) · [flutter_edge_ai](https://pub.dev/packages/flutter_edge_ai)
- 上游核验脚本：`/tmp/litertlm_aar`（AAR 解包 javap 记录）、`docs/phase0/`（G1–G4 原始数据）


## 六、GPU 自动策略终审（2026-10-16，demo 保真差异②闭环）

**实现**：manifest 4×OpenCL 声明（demo 对齐）；三 provider 增加 useGpu；`EmbeddingRuntime` GPU 策略
（偏好 auto/off/on）——auto = 对已验证可用的组合先试 GPU（建引擎+探针首推理，**15s 超时兜底**，
JNI 阻塞不可取消、超时放弃该线程），失败回退 CPU 并记录原因；设置屏 GPU 偏好行。

**三档实测矩阵**：

| 组合 | GPU 结果 |
|---|---|
| granite int8 + 8 Elite GPU | **不可用**：强制模式确定性挂死（两轮 200s/500s 无异常无进展，进程存活）；AUTO 偶发快速异常（`LiteRtLmJniException`） |
| **440M + 8 Elite GPU** | **可用且 6.2×**：600字 449→72ms（OpenCL 声明下新测；高于 Phase 0 记录的 2.4×）；**长文本反超 granite+CPU**（72ms@600字 vs ~112ms@400字） |
| MT6985 / MT6893 + 任意模型 | 引擎创建失败（Phase 0 结论不变），AUTO 快速异常回退 |

**生产默认（精化）**：AUTO 仅对 440M 路径试探 GPU（唯一验证可用组合）；granite/task 直接 CPU——
避免旗舰 granite 日常首用白付 2-15s 试探成本（实测精化前 406ms/篇 → 精化后 205ms/篇恢复基线）。
验证：granite AUTO 零试探直达 CPU（fallback 空）✅；440M AUTO 试探成功走 GPU ✅。

**"GPU 能否进一步加速"的答案**：granite（默认路径）不能（挂死）；440M 能且幅度 6.2×，
长文本场景"440M+GPU（偏好强开）"是可选最快档——但 granite+CPU 已满足全部性能门槛，
默认组合不变（质量等效 + 通用性 + 内存）。

W8 监控更新：litertlm 修复 granite GPU 挂死后，将 `isPathAModel` 白名单扩至 granite 即可自动启用。


## 七、模型目录与 NPU 特化版终审（2026-10-16，W4 模型商店首验）

SM8750 NPU 特化版（440M，`Backend.NPU()` 首验）：
- **NPU 后端：挂死**（G1D 通道 >7min 无异常无输出，进程存活）——与 granite GPU 挂死同款症状
- **CPU 后端：同样挂死**（特化版为 NPU 专用编译产物，无 CPU 子图回退）
- **结论：litertlm 0.18.0 上 SM8750 特化版完全不可用**。验证门（G1D）成功拦截在启用前——
  生产路由（auto=granite 优先、特化版不自动启用）全程未受影响，目录 Entry 保留待 litertlm 升级复测

W4 模型商店落地组件：ModelCatalog（7 条注册表 + SOC 检测/推荐矩阵）、SharedModelStore（SAF 文档树
冷存储 + cacheDir/models-link 运行缓存 + cacheDir 中转下载复用断点/SHA）、设置页商店卡（SOC 行 +
perfMode 三档推荐 + 下载进度 + 目录选择/重选/清空 + 启用）、G1D 特化版终审入口（通过后写验证门记录）。
空间指纹泛化：granite/440M 通用保持存量空间；其余（270M/740M/LFM/特化版）按 modelId 独立空间。

## 七·补：Google AI Edge Gallery 通道复测（2026-10-16 晚）

对 github.com/google-ai-edge/gallery 取证后发现其 Embedding 走 tasks-retrieval UniversalEmbedder
+ Delegate.NPU + NpuOptions(dispatchLibraryDirectory=nativeLibraryDir)（与我们路径 A 同通道），
litertlm 同为 0.18.0——据此把 G1D 改为 Gallery 同款通道复测 SM8750 特化版：

- 仍挂死（540s+ 无异常无输出、无 QNN/dispatch 加载日志）——与 litertlm 通道一致
- 关键佐证：Gallery 17 个线上 allowlist 版本中零 NPU/特化版引用（socToModelFiles schema 存在但
  从未配置）——NPU 通道是该项目的休眠代码，无已知可工作的线上先例
- 缺失环节推断：QNN dispatch runtime（libQnnHtp*.so 等）不在任何公开 AAR 中（litertlm/tasks-core
  AAR 均只有主 JNI），Gallery 同样没有——特化版生态尚未打通

终审结论（两通道 × 双后端共 4 次实验）：SM8750 特化版在当前公开生态（litertlm 0.18.0 /
tasks-retrieval 1.1.1 / 无 QNN runtime）完全不可用。验证门持续拦截。W8/W11 触发信号：Gallery
线上 allowlist 首次出现 NPU/socToModelFiles 配置，或 litertlm 新版修复非 CPU 后端挂死——出现即
复测（我们已有 G1D 一键通道与 NpuOptions 代码路径，改动成本为零）。

## 八、GPU 调度问题终版分析（2026-10-16 晚，G1E 突破）

### 8.1 "Gallery 能走通 GPU"的真相

Gallery 的 `tflite/tflite.gpu/tflite.support` 是 org.tensorflow 独立 TFLite Java API 栈，**不经
MediaPipe tasks**，与 embedding GPU 无关。Gallery embedding 的 GPU 走的是
`libmediapipe_tasks_jni.so` 内置 GPU delegate——我们 APK 同样打包（14MB，tasks-core AAR 自带）。
**Gallery 能走通与我们此前走不通的不是同一条路**：Gallery embedding 只用 740M + UniversalEmbedder
通道；我们走不通的是 litertlm EmbeddingEngine 通道的 GPU（granite），Gallery 根本没有这条用例。

### 8.2 GPU 可用性 = 通道 × 模型 矩阵（全部实测）

| 通道（native lib） | 可加载模型 | GPU 结果 |
|---|---|---|
| UE 通道（libmediapipe_tasks_jni.so） | **仅带 vision encoder 的 litertlm**（440M/740M；纯文本被拒——Phase 0 "tf_lite_vision_encoder not found"） | ✅ 可用：440M 6.2×；**740M G1E 新通**（32-44ms 几乎无长度增长） |
| litertlm 通道（liblitertlm_jni.so） | 全部 .litertlm（含纯文本 granite/270M/LFM） | ❌ 0.18.0 挂死（granite 两轮复现）；Gallery 无此用例先例 |
| NPU（两通道） | 特化版 | ❌ 全挂死 + 缺 QNN dispatch runtime + Gallery 线上零配置（W11） |

### 8.3 granite 无 GPU 路径的根因链（终版）

granite 纯文本（无 vision encoder）→ UE 通道拒载 → 只能 litertlm 通道 → litertlm 0.18.0 GPU 挂死
→ **granite 在当前版本无 GPU 路径**。解锁条件：litertlm 新版修复 GPU，或（投机）给 granite 转换出带
vision 签名的包。默认路径维持 granite+CPU（质量等效、32ms 已极快、全平台通用）。

### 8.4 G1E 突破：740M + UE 通道 + GPU（新最快档）

740M 通用版改路由 UE 通道（生产改动；此前误配 litertlm 通道）。旗舰 G1E 两次全绿且跨进程
SHA 一致（`6864bf58…`）：R@1=1.0、768d、逐位一致、init 5.6s、**30字=32ms / 200字=33ms /
400字=44ms**（比 granite CPU 快 3.4×@400 字，比 440M CPU 快 4×，且几乎无长度增长）——
**当前实测最快组合，且是 EGemma2 家族质量上限**。768d 独立空间（切换=重导入，既有语义）。
GPU AUTO 白名单暂不加 740M（仅旗舰单档验证；K40/K60U 复验后再定），偏好强开 GPU 即可用。

## 九、全模型连通性矩阵（2026-10-16 深夜，旗舰 SM8750，40 格 / 15 ok / 0 挂死）

`ConnectivityProbe`（autostart_probe / scripts/run_probe.sh）一次性探测 9 模型 × 双通道 × 三后端。
**修正此前两个过度推广的结论**：

1. **"litertlm 通道 GPU 挂死"是 granite 特有，不是通道级**——EGemma2 全系 LM-GPU 实测可创建+推理
   （首条含编译 2.6-4.4s）：740M ✅2608ms / 440M ✅4410ms / 270M ✅4361ms。挂死仅 granite
   （两轮复现），疑似其 int8 量化图与 litertlm GPU runner 不兼容。
2. **UE 通道加载范围比预想宽**：granite UE-CPU ✅（2507ms，此前以为只能走 LM 通道）、LFM UE-CPU ✅
   （1024d）；但 270M UE ❌（复现 Phase 0 拒载）——UE 可载性与"是否纯文本"不严格相关，
   取决于各包转换时的签名声明，实测为准。

新失败模式（防御有效）：LFM GPU（LM/UE 两通道）构建推理"成功"但输出 NaN/Inf——被有限值校验拦截。
.task GPU 失败（CalculatorGraph::Run failed），tasks-text 仅 CPU（恒定 ~655ms）。

### 终版可用矩阵（旗舰 SM8750 / litertlm 0.18.0 / tasks 1.1.1）

| 模型 | LM-CPU | LM-GPU | UE-CPU | UE-GPU | NPU（全通道） |
|---|---|---|---|---|---|
| 740M 通用 | ✅ | ✅2608ms* | ✅ | ✅7598ms*（warm 32-44ms） | ❌ |
| 440M 通用 | ✅ | ✅4410ms* | ✅ | ✅6792ms*（warm 44-72ms） | ❌ |
| 270M 通用 | ✅ | ✅4361ms* | ❌拒载 | ❌ | ❌ |
| granite | ✅ | ❌挂死类 | ✅2507ms | ❌ | — |
| LFM2.5 | ✅1024d | ❌NaN/Inf | ✅1024d | ❌NaN/Inf | — |
| .task | — | — | — | — | —（TASK-CPU ✅655ms；TASK-GPU ❌） |
| 特化版 ×3（270M/440M/740M _SM8750） | ❌ | ❌ | ❌ | ❌ | ❌ **全灭**（ComputeEmbedding UNKNOWN / Failed to create） |

\* 首条含 GPU 编译 2.6-7.6s；warm 见 G1E（740M UE-GPU 32-44ms）。

### 对生产的直接含义

- **GPU AUTO 白名单维持 440M/UE**（唯一 warm 数据充分的组合）；270M/740M/440M 的强制 GPU 可用
  （首查 +2.6-7.6s 编译，多轮摊销后值得——导入场景收益最大）。
- **特化版全灭定案**：三个 SOC 特化版与 litertlm 0.18.0/tasks 1.1.1 全组合不兼容，验证门持续拦截；
  W11 触发条件不变。
- granite 双通道 GPU 均不可用 → 无 GPU 路径的根因收敛为"granite 量化图 × 两家 GPU runner 均不兼容"。


## 九·补：K60 Ultra（MT6985）矩阵 + accelerator 空间隔离修复（2026-10-16 深夜）

**K60U 矩阵与旗舰完全同构**（40 格 / 15 ok / 0 timeout，同批格过/败）：MT6985 上 EGemma2 全系
LM-GPU/UE-GPU 通过（首条编译 3.8-13.7s）——**推翻 Phase 0"MT 平台 GPU 不可用"结论**，最可能
归因于后续补齐的 manifest 4×OpenCL 声明（Phase 0 时无）。740M UE-GPU warm：150/152/254ms
（Adreno 旗舰 32-44ms 仍显著更快；K60U granite CPU 79ms 依旧更快——MT GPU 编译后性能不及
Adreno，GPU 收益有平台上限）。

**生产缺陷发现与修复（数据完整性）**：740M GPU 向量 SHA 跨设备不同（旗舰 `6864bf58…` vs
K60U `acd0ba1f…`）——GPU 编译随设备驱动而异，**同空间向量跨设备不可比**。原 spaceId 不含
backend → GPU 与 CPU 向量会混入同一空间。修复：descriptor 增 accelerator 字段，GPU/NPU 空间
id 加 `-gpu`/`-npu` 后缀；granite CPU（SPACE_ID_B）与 440M CPU（SPACE_ID）存量 id 不变
（向量兼容）。2 条新单测锁定（隔离性 + 存量兼容），共 34 条全绿。

## 九·终：三档矩阵收官（2026-10-16 深夜，K40 补测）

**K40（MT6893）矩阵与前两台完全同构**（15 ok / 0 挂死）——MT6893 GPU 亦全线通过。
**OpenCL 声明假设验证成立**：Phase 0"MT 平台 GPU 引擎创建失败"系当时缺 manifest
`uses-native-library` OpenCL 声明所致；三档（SM8750/MT6985/MT6893）现全部 GPU 可用。

**740M UE-GPU 三档 warm（G1E，R@1=1.0 全过）**：

| 设备（GPU 家族） | 30字 | 200字 | 400字 | 向量 SHA |
|---|---|---|---|---|
| 旗舰 SM8750（Adreno） | 32ms | 33ms | 44ms | `6864bf58…` |
| K60U MT6985（Mali） | 150ms | 152ms | 254ms | `acd0ba1f…` |
| K40 MT6893（Mali） | 203ms | 191ms | 348ms | `acd0ba1f…`（与 K60U 相同！） |

新发现：GPU 向量按 **GPU 家族聚簇**（两台 Mali 同 SHA、Adreno 另簇）——`-gpu` 后缀按
"GPU/CPU"粒度保守正确（Mali 簇内可迁移是额外红利）。对照 granite CPU warm（27/79/58ms）：
**740M GPU 仅在 Adreno 旗舰快于 granite CPU**；MT 两档 granite CPU 更快。

**GPU AUTO 白名单决策（三档数据齐）**：维持 440M/UE 白名单不变——granite（默认）CPU 在全部
三档都快于或等于自身 GPU 选项；740M GPU 定位"质量上限 + 旗舰/Adreno 场景"（用户强开或
perfMode 极速 + Adreno 检测时推荐，随 W4 商店迭代）。

## 十、战略切换落地：EGemma2 × UE 通道收敛（2026-10-16 深夜，用户决策）

**决策**：放弃 granite/LFM 的默认地位（专一 EGemma2 × UE 通道，为 ML Kit NPU 版 EGemma2
直接切换铺路）；granite/LFM/270M 退役保留（目录可选，litertlm 通道代码保留——270M 轻量档
唯一载体）；新默认 440M 通用。

**MT GPU warm 补测（决策关键数据）**：

| 组合 | 旗舰 Adreno | K60U Mali | K40 Mali |
|---|---|---|---|
| 440M UE-GPU warm | **44ms** ✅ | 待复测（740M 参照 150-254ms） | **153/154/352ms** ❌ |
| 对照 440M CPU | 62-126ms | ~97-109ms | ~126ms |

**结论：GPU 收益为 Adreno 专属**。Mali（K40）440M GPU warm 352ms 反慢于同机 CPU 126ms，
且 Mali 编译 RSS 飙至 **2.6GB**（7.3GB 机型危险，实测）。

**落地改动**：
1. 默认路由 auto：440M 通用优先（granite 退役为可选 B）；ModelCatalog recommend 均衡档→440M；
   granite Entry 标注退役
2. **GPU AUTO 白名单加 Adreno 门**（`soc contains sm8`）：旗舰 AUTO 走 GPU（44ms），MT 两档
   AUTO 直达 CPU。K40 实测门效果：门开（误走 GPU）p50=185ms/RSS 2598MB → 门后 p50=130ms/
   RSS 1399MB——门控既是性能修复也是低端机内存保护
3. G1E 选择器泛化（740M 缺席时落 440M 通用）；34 单测全绿
4. K40 回归（新默认 440M + Adreno 门）：**import 407ms/篇、p50=130ms、RSS 1399MB**——
   对比 granite 时代（406ms/58ms/688MB）：速度 +124%、内存 +711MB——这是战略切换的
   明码标价，换取技术栈收敛与 ML Kit/NPU 演进通道

**后续触发**：ML Kit EGemma2 上线（W1）→ 按其 API/托管形态切换 Provider 实现；litertlm 修复
granite GPU（W8）→ granite 可回归为 MT 档性能优选。

## 十·补：K60U 新配置回归 + 440M UE-GPU warm 细化（2026-10-16 深夜）

**新配置（440M 默认 + Adreno 门）K60U 回归 ✅**：gpu_effective=False（门生效直达 CPU）、
import 613ms/篇、p50=142ms、RSS 1424MB（无 Mali GPU 编译暴涨——门控的内存保护在 K60U 同样成立）。

**440M UE-GPU warm（K60U）**：92/103/168ms——细化了 MT 决策：MT6985 上 440M **短文本 GPU 比
CPU 快 35%**（92 vs 142ms），400 字反超 CPU，首查编译 7s。与 K40（MT6893，GPU 153-352ms 全面
慢于 CPU）分化——MT 内部两代平台 GPU 收益不同。白名单维持 Adreno 门不变（K60U 短文本收益面窄
+ 7s 首查/RSS 代价；用户可手动强开 GPU 获取短文本收益，数据在案）。

**三档 440M UE-GPU warm 终表**：旗舰 Adreno 44ms ✅（AUTO 默认）· K60U Mali 92-168ms（短文本
正收益，手动开启）· K40 Mali 153-352ms（负收益，不建议）。

## 十·终：三档新配置回归收官（2026-10-16，440M 默认 + Adreno 门，APK 858edc03）

| 设备 | gpu_effective | 检索 p50/p95 | RSS 峰值 | 空间 id |
|---|---|---|---|---|
| 旗舰 SM8750（Adreno） | **True**（门放行走 GPU） | **63 / 76 ms** | 725MB | `…raw-symmetric-v1-gpu` ✅ |
| K60U MT6985（Mali） | False（门挡，直达 CPU） | 142 / 152 ms | 1424MB | CPU 存量 id |
| K40 MT6893（Mali） | False（门挡，直达 CPU） | 130 / 142 ms | 1399MB | CPU 存量 id |

全部符合预期：Adreno 门旗舰放行（GPU 63ms，空间带 `-gpu` 后缀）、MT 两档 CPU 直达（无
Mali 编译 RSS 暴涨）。**"440M 默认 + EGemma2 × UE 收敛 + Adreno 门"三档验证闭环。**
