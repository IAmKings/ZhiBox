# ZhiBox 当前底座 vs zleap-sag（SAG）功能/论文架构对齐报告

- **报告版本**：v1.0（供 Lead / PRD / B8–B12 评审）
- **日期**：2026-10-18
- **作者**：独立分析师（不参与实现）；2026-10-18 双轴审查后修订（行 15 降为 🟡、证据引用/计数/模式语义已同步）
- **分析对象**：ZhiBox `0003d76`（Phase 1 MVP + B1–B7）↔ SAG 应用 README-CN ↔ 论文 arXiv:2606.15971v2 ↔ 参考引擎 `zleap-sag==0.14.0`
- **写入范围**：仅本文件；未改代码/文档，未跑 Gradle，未 commit（工作区其他文档的并行更新见 §1.1 说明，非本报告改动）
- **结论先行**：**总体不对齐（NOT ALIGNED）；仅在 chunk 级离线检索、证据回溯、模型/空间管理、备份迁移等“基础外围”部分对齐。SAG 核心的 event-entity 索引、潜在超边、双 seed、1 跳扩展、LLM 上下文选择、双路径输出，以及 README 应用层的问答/图谱/探索/集成均未对齐。**

---

## 0. 执行摘要

### 0.1 一句话结论

当前 ZhiBox 底座是一个**以 chunk 为单位的离线混合检索（向量 + FTS4 bigram + RRF）+ 原文证据 + 端侧模型管理**产品；它复用了 SAG 的“证据必须回到原 chunk”这一输出边界，但没有实现 SAG 的“1 chunk = 1 event + 11 类 entities = 潜在超边”表示，也没有实现查询时的双 seed、SQL 扩展、LLM 选择与双路径输出，因而**不具备论文意义上的多跳检索能力**；README 所描述的完整知识导入（PDF/Office/网页/解析器回退）、图谱/探索、带引用问答、MCP/REST/OpenAI/Dify/dsh-sag 等应用层能力同样未实现。

### 0.2 对齐计数（按第 5 节 28 行矩阵实际统计）

| 判定 | 含义 | 行数 | 行号 |
|---|---|---|---|
| ✅ 对齐 | 行为与语义和 SAG/论文可直接核对一致 | **3** | 8、27、28 |
| 🟡 部分 | 有基础实现，但覆盖范围/参数/语义不足 | **9** | 1、7、9、15、18、19、21、24、25 |
| ❌ 缺失 | 未实现，或仅有未写入的 schema 占位 | **15** | 2、4、5、6、10、11、12、13、14、16、17、20、22、23、26 |
| ⚠️ 偏离 | 已实现但与论文/参考存在有意或已知偏离 | **1** | 3 |
| **合计** | — | **28** | — |

> 判定定义见 §1.3。计数只按“每行一个主判定”统计，不把同一行的子能力重复计数。

### 0.3 关键发现（按影响排序）

1. **数据层是“schema 骨架”，不是“SAG 索引”**：`event`、`entity`、`event_entity`、`chunk_entity` 四表已建（`Entities.kt:137-212`，`AppDatabase.kt:12-22`），但 `AppDatabase` 只暴露 7 个 DAO、其中没有任何 event/entity DAO（`AppDatabase.kt:30-36`）；全仓库除 `Entities.kt`/`AppDatabase.kt` 的 schema 与迁移清理外，没有 event/entity 的业务读写。业务上 event 数、entity 数恒为 0。
2. **检索只有一条 chunk 单路**：`SearchService.search(query, topK=10)` 没有 `mode`、没有 `sourceIds`、没有 event 层（`SearchService.kt:80`）；实现是 chunk 向量精确扫描 + FTS4 bigram + RRF（K=60），没有 seed 双路径、没有 SQL join、没有 1 跳 frontier、没有 LLM 选择、没有双路径 `Kout=10`。
3. **应用层基本为空**：代码中没有 LLM、Chat、Graph、MCP/REST/OpenAI/Dify/dsh-sag 模块；`MainActivity` 只有 home/import/search/sources/settings 五个路由（外加 debug benchmark）（`MainActivity.kt:93-112`）。README 的“带引用问答、图谱、探索模式、对外集成、桌面/账号”均无对应实现。
4. **已经做扎实的部分应保留**：chunk→document→source 的证据链与 `charStart/End` 原文定位（`SearchService.kt:51-65,121-169`；`EvidenceScreen.kt:24-41`）、schema v5 的 8 条 CASCADE 外键（B1 报告）、端侧模型商店/空间指纹（`ModelCatalog.kt:53-109`）、备份 v2 与 v1 兼容（`KnowledgeBackup.kt:37-40`；`BackupValidator.kt:29-30`）、默认离线与仅模型下载联网（`AndroidManifest.xml:9,17-22`）。这些是 B8–B12 的地基，不应在引入 SAG 时破坏。
5. **当前评测不能证明“多跳”**：`scripts/eval/evalset.json` 为 262 条查询、50 篇文档；本报告统计发现 **220 条正例每条只有 1 个 relevant 文档**（其中 50 条标为 `multihop` 的也全部只有 1 个 relevant），42 条 adversarial 为 0 个 relevant；R@10=1.0 已到天花板。它是“单语料、单证据检索”的回归集，不是论文式 2–4 跳多证据评测。
6. **版本与语义存在两处需在 B8 前澄清的漂移；另有两处数字口径已在并行定稿中修正**：
   - README-CN（main `a6f6a00`，2026-10-16）写“应用要求 `zleap-sag==0.13.0`”，但本地只读参考是 **0.14.0**（`56f073c`，2026-10-09）；0.13.0 的行为差异未验证。`UPSTREAM_VERSIONS.md:12-22` 已新增 SAG 应用/论文锚点并记录该漂移。
   - 参考 0.14.0 的抽取提示词允许“一个 chunk 拆多个事项 + children 层级 + references”（`extract_document.yaml:29,62,66`），与论文“1 chunk = 1 event”及 PRD FR-01“每个 ready chunk 最多 1 个 event”不一致；B9 不能直接照搬该提示词的拆分语义。
   - 写作过程中发现的两处数字口径（PRD §15.1 的 15.93s 归因、`W3:77` 的 262 构成式）已由 Lead 在 2026-10-18 并行定稿中修正：PRD §15.1 现为“15.93s @ Kcand=50 / 18.67s @ Kcand=100”，`W3:77` 现为“50×4 + adversarial 42 + numdate 20”。本报告不再把它们列为 open issue。

### 0.4 本报告不做什么

- 不替代实现设计/PRD/ROADMAP、不评价工期；
- 不运行 Gradle/真机/LLM，不修改被引用文件；
- 不复现论文跑分，不宣称当前产品“等同 SAG”或“复现 SOTA”；
- 对无法从来源核对的内容标“未验证”（见附录 D）。

---

## 1. 方法与锚点

### 1.1 必读来源与本次核验

| 来源 | 锚点 | 核验方式 | 用途 |
|---|---|---|---|
| SAG 应用 README-CN | [README-CN.md](https://raw.githubusercontent.com/Zleap-AI/SAG/a6f6a00f36ce0cbe43cd2fc0445b479e4ae4fff6/README-CN.md)；repo main commit `a6f6a00f36ce0cbe43cd2fc0445b479e4ae4fff6`，blob `6b0df82e0a90457dc8f83efbd258b20d0b2b4596`，33,492 B，2026-10-16T11:05:11Z | `raw.githubusercontent.com` 拉取 + GitHub API 核对 commit/blob | 应用功能清单、三检索模式、集成/桌面/账号/存储 |
| 论文 | [arXiv:2606.15971](https://arxiv.org/abs/2606.15971) **SAG: SQL-Retrieval Augmented Generation with Query-Time Dynamic Hyperedges**，v2，2026-08-25 | 本地 `/tmp/sag_paper.txt`（5,038 行）通读正文 §1–6 与附录 A–L 相关部分 | 机制、默认参数、结果、消融、成本、局限 |
| 参考引擎 | `docs/reference/zleap-sag-0.14.0/`（只读），wheel `zleap-sag==0.14.0`，wheel SHA-256 `c58d12f1…bc429`，提取自 SAG 主仓库 `56f073c1c0c300b46707c7249d48732730656cb0`（2026-10-09） | 目录内 README 与 `UPSTREAM_VERSIONS.md:12-29` 对照 | schema、chunk/检索选项、抽取/重排 prompt、六向量角色 |
| 当前 ZhiBox | HEAD `0003d76dc3ac1914afe72d864b376d3cf9b4d6cd`，2026-10-18 00:58；`versionName=0.1.0-mvp`；Room schema v5 | `git log/status` + 源码 grep/read | 实际能力与限制 |
| 当前验收/评测 | `docs/phase1/MVP1-ACCEPTANCE.md`、`docs/phase1/W3-ROBUSTNESS-EVAL.md`、B1–B7 报告 | 文档通读 + 代码交叉核对 | Phase 1 能力、262 条评测、B1–B7 终态 |
| 产品规划 | `docs/product/PRD_ZhiBox_SAG_App_v1.0.md`（v1.0 已评审，2026-10-18）、`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md`（v1.0，2026-10-18） | 通读；PRD 为评审后基准，ROADMAP 为 v1.0 | 差距→FR/B8–B12 映射、P0 门禁 |

> 并行更新说明：本报告写作期间（2026-10-18 01:45–01:52），Lead 并行更新了 `docs/product/PRD_ZhiBox_SAG_App_v1.0.md`、`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md`、`docs/phase1/W3-ROBUSTNESS-EVAL.md:77`、`docs/UPSTREAM_VERSIONS.md:12-29`；本报告以完成时点内容为准，相关修正已在 §0.3 与附录 D 标注。

### 1.2 版本漂移

| 对象 | 版本/锚点 | 日期 | 与当前底座的关系/影响 |
|---|---|---|---|
| SAG 应用 README-CN | SAG badge `v1.8.11`；要求 `zleap-sag==0.13.0`；commit `a6f6a00` | 2026-10-16 | 比本地参考“新 README + 旧引擎 pin”；0.13.0 wheel 未获取，行为差异**未验证** |
| 本地参考引擎 | `zleap-sag==0.14.0`；源 commit `56f073c` | 2026-10-09 | 当前所有 schema/prompt 对照的实际基线；比 README pin 高一个 minor 线（0.13→0.14） |
| 论文 | v2；BGE-Large-EN-v1.5 retriever + Qwen3.6-Flash reader；MySQL + Elasticsearch | 2026-08-25 | 服务端/英文/全量语料设定；与端侧 EGemma2 + 远程 LLM 不可直接横比 |
| 当前 ZhiBox | HEAD `0003d76`；Phase 1 + B1–B7；EGemma2 768d 端侧；SQLite/Room | 2026-10-18 | 无 event/entity/LLM/服务端；与论文/README 应用均不同层 |
| 产品规划 | PRD v1.0 已评审；ROADMAP v1.0；目标 schema v6；B8–B12 | 2026-10-18 | 规划中的 P0 闭环；本报告作为其“对齐证据”（ROADMAP:5） |

**漂移影响（判定）**：
- README 的应用版本号（v1.8.11）**不是**引擎版本；应用锁 0.13.0，而参考素材是 0.14.0。二者在 `pipeline/options.py`、`prompts/extract_document.yaml`、`db/models.py` 上的差异未做 diff，**不能假设 0.14.0 的行为等于 README 所描述的应用行为**。
- 论文的默认 `L=1`、`Kseed=50/frontier=50/Kcand=100` 等，以及参考实现的 `max_tokens=800`，都是服务端/桌面配置；端侧当前 chunk 上限 600 字符（为 1024 token 引擎签名限制），属于**有意偏离**，不能直接套用论文数值。
- PRD 为 v1.0 已评审、ROADMAP 为 v1.0；本报告引用其**计划内容**，不把其数字当作已验证事实。

### 1.3 判定等级定义

| 等级 | 定义 | 典型证据要求 |
|---|---|---|
| ✅ 对齐 | 能力存在，行为/语义与 SAG 或论文对应项一致（允许实现栈不同） | 代码路径 + 文档/测试锚点 |
| 🟡 部分 | 有基础实现，但只覆盖子集，或参数/语义不足以支撑 SAG 用法 | 代码路径 + 明确缺口 |
| ❌ 缺失 | 无实现；或只有未写入/未接线的 schema、接口占位 | schema/路由/调用链证据 |
| ⚠️ 偏离 | 已实现，但和论文/参考的默认值或语义不同，且属于有意取舍或已知风险 | 实现 + 偏离原因/影响 |

### 1.4 统计口径与可复现性

- 能力矩阵共 **28 行**，每行一个主判定；子能力在“差距/影响”列说明，不重复计数。
- 架构阶段映射矩阵使用“**有 / 无 / 替代 / 部分**”，**不参与** ✅/🟡/❌/⚠️ 计数，避免与能力矩阵混淆。
- 所有代码行号以 HEAD `0003d76` 的工作区文件为准。**路径简写约定（全文一致）**：
  - `…/` = `app/src/main/java/com/zhibox/memory/`；
  - 表格/正文中直接写 `Entities.kt`、`AppDatabase.kt`、`SearchService.kt`、`IngestRepository.kt` 等短名时，均指 `…/` 下对应路径（如 `data/db/Entities.kt`、`core/search/SearchService.kt`）；
  - `参考 db/models.py`、`pipeline/options.py`、`prompts/extract_document.yaml` 等 = `docs/reference/zleap-sag-0.14.0/zleap/sag/` 下对应路径；
  - `W3:xx` = `docs/phase1/W3-ROBUSTNESS-EVAL.md`；`B1:xx`/`B7:xx` = `docs/review/B1_FIX_REPORT.md`/`B7_FIX_REPORT.md`；`evalset.json` = `scripts/eval/evalset.json`；`UPSTREAM_VERSIONS` = `docs/UPSTREAM_VERSIONS.md`。
- 论文数字以 Table 1/6/7/8、Appendix B/I/K/L 为准；README 功能以 2026-10-16 的 `README-CN.md` 为准。

---

## 2. 论文核心机制摘要

> 本节的每个数字均可回到论文对应章节/表格；引用的是 v2（2026-08-25）。

### 2.1 表示：event-entity 索引与潜在超边

- **1 chunk = 1 event**：每个 chunk 被独立处理成**一个**语义完整的事件（`e`），事件文本是 chunk 核心内容的简洁陈述；检索在事件文本上进行，**证据始终返回原 chunk**（§3.1、§3.2、Appendix A）。
- **event 带一组 entities**：实体覆盖 **11 类**：`time`、`location`、`person`、`organization`、`group`、`subject`、`work`、`product`、`action`、`metric`、`tags`（§3.1、Appendix I）。
- **潜在超边（latent hyperedge）**：一个 event 及其全部 entities 构成一条超边；SQL 里只存 `(event, entity)` incidence 行，按 event id 分组即可无损还原实体集合；**不物化全局图、不拆三元组**（§3.1、§3.2、Appendix A）。
- **索引写入**：每个 chunk 由**一次 LLM 结构化调用**并行产出 1 个 event 与多个 entities，写入 SQL 多对多行，同时写入向量与全文索引（§3.1、Appendix I）。
- **不做全局实体消歧**：只做字符串规范化 + SQL 去重，保留独立 chunk 处理与 append-only 写入；这是有意的部署取舍（§3.1、§5.3）。

### 2.2 在线流程：双 seed → 1 跳扩展 → 粗排 → LLM 选择 → 双路径输出

1. **Path A（实体引导的结构召回）**：轻量 LLM 从 query 识别关键实体集合 `Q`；在 entity 向量索引上以高阈值 `τ_ent=0.9` 扩展到 `Q̂`；再对共享任一实体的 event 做 SQL join，得到 `R_ent`（§3.3，式 1）。
2. **Path B（query 向量直接召回 event）**：用 query 向量对 event 向量索引检索，采用**低阈值** `τ_evt=0.4` 以保召回，精度留给后续排序；得到 `R_evt`（§3.3）。
3. **合并 seed**：`R = R_ent ∪ R_evt`（式 2）。
4. **扩展**：从 `R` 反向 join 出实体，未见过的实体构成 frontier，按固定预算（论文默认 50）裁剪；再 join 到未见过的 event；每轮只引入未访问实体/事件；默认 **`L=1`**（§3.4）。
5. **粗排**：对候选池 `C = R ∪ X` 按 event 向量与 query 的相似度取 Top `Kcand=100`（式 4）。
6. **LLM 上下文选择**：LLM 读取这 100 个候选事件的上下文，**联合**选择最多 `Kevent=5` 个能共同支撑答案的事件；这不是逐条 pointwise rerank（§3.4、§5.2）。
7. **双路径输出**：结构路径把选中事件经 `φ: event → chunk` 映射为 chunk 集合 `D_evt`；语义路径对 chunk 索引直接排序，去重后补足到 **`Kout=10`**；`K_direct(q)=Kout-|D_evt|` 随查询变化（式 5）。
8. **证据边界**：最终输出是原 chunk 全文（不是 event 文本），用于生成与引用（§3.4、Appendix L）。

### 2.3 论文默认参数

| 参数 | 默认值 | 出处 |
|---|---|---|
| 扩展深度 `L` | 1 | §3.4、§4.2 |
| seed 预算 `Kseed` | 50 | §4.2 |
| frontier 预算 | 50 | §4.2、§3.4 |
| 候选预算 `Kcand` | 100 | §4.2 |
| LLM 选择上限 `Kevent` | 5 | §3.4、§4.2 |
| 输出 chunk 数 `Kout` | 10 | §3.4、§4.2 |
| 实体相似阈值 `τ_ent` | 0.9 | §3.3 |
| 事件相似阈值 `τ_evt` | 0.4 | §3.3 |
| chunk 策略（参考实现） | `max_tokens=800`、`overlap=0`、heading 感知 | 参考 `pipeline/options.py:43-52` |

### 2.4 关键结果

**主结果（Table 1，统一 BGE-Large-EN-v1.5 + Qwen3.6-Flash）**

| Benchmark | SAG R@5 | SAG F1 | 最强结构基线对照 |
|---|---:|---:|---|
| MuSiQue | **80.36** | **61.15** | HippoRAG 2 65.13 / 47.49 |
| 2WikiMultiHopQA | **93.34** | **78.06** | HippoRAG 2 90.35 / 74.34 |
| HotpotQA | **96.50** | **79.66** | HippoRAG 2 94.35 / 77.24 |
| **Avg** | **90.07** | **72.96** | R@5 最强基线 83.28（HippoRAG 2）；F1 最强基线 68.63（HyperRAG） |

- README 所述“平均 R@5/F1 较最强基准 +6.79/+4.33”与上表一致（90.07−83.28=6.79；72.96−68.63=4.33）。
- **MuSiQue +11.52 的口径需要说清楚**：论文摘要的“outperforming the strongest baseline by 11.52 points”对应 **NV-Embed-v2 的 68.84**（80.36−68.84=11.52），NV-Embed-v2 是 dense embedding 基线中的最强项；若对结构基线 HippoRAG 2（65.13），论文 §5.1 明确写的是 **+15.23**。F1 的 +7.01 对应 GraphRAG 的 54.14（61.15−54.14=7.01）。
- 2Wiki 的中间发现：早期不在扩展前后剪枝时 R@5 只有 88.00（低于 HippoRAG 2 的 90.35）；加入前后剪枝后升到 93.34（§5.1）。这说明“剪枝预算”不是可选项。

**消融（Table 6/7，MuSiQue）**

| 变体 | R@5 | 相对默认（80.36） |
|---|---:|---:|
| 默认 SAG | 80.36 | — |
| 无扩展（`L=0`） | 69.41 | **−10.95** |
| 换成 pointwise Qwen3-Reranker-8B | 67.11 | **−13.25** |
| 换成 triple 索引 | 77.61 | **−2.75**（但仍高于 HippoRAG 2 的 65.13，+12.48） |
| 纯语义（`Kevent=0`） | 56.23 | **−24.13** |
| `Kevent=5`（默认） | 80.36 | — |
| `Kevent>5` | 不升反降/持平 | 结构 cap 5 足够 |

- 扩展深度：`L=2..4` 相对 `L=1` 在 **0.35 分以内**，说明 1 跳已捕获大部分收益（§5.2）。
- 候选预算：`Kcand` 50→100 提升 **+1.01 R@5**；100→500 仅再 **+1.88**，但成本大幅上升（§5.2、Appendix G）。
- 选择器的价值是“跨候选联合看证据链”，pointwise reranker 无法替代；但即使换 reranker，SAG 仍高于 HippoRAG 2，说明扩展本身也贡献增益（§5.2）。

**鲁棒性/扩展性**

- 换 embedding：SAG 从 NV-Embed-v2 到 BGE 只降 **1.35**（81.71→80.36），HippoRAG 2 降 **9.42**（74.55→65.13）（§5.2、Figure 3a）。
- 语料持续增长：NQ 76.80→74.81；MuSiQue 87.90→82.57（降 5.33），HippoRAG 2 69.23→60.27（降 8.96）（§5.2、Figure 3c）。
- 有界激活：`Kpool=500` 时 MuSiQue R@5=79.57，约为全量 11,656 chunks 的 80.36 的 99%，只激活 <5% 语料（§5.2、Figure 3b）。
- 语料连通性：SAG 在三个数据集上 giant component 覆盖 **>93%**（MuSiQue/2Wiki >96%），平均度 2.61–3.71；这是“可达性”而非“全局遍历”的证据（Appendix B）。

**NarrativeQA（Appendix K）**

- 293 条 dev 问题，4,111 passages；SAG EM **12.29** / F1 **31.90**，HippoRAG 2 EM 6.48 / F1 22.86。说明 event-entity 组织对叙事理解也有迁移，但这是补充实验，不改变主结论的三多跳基准。

**LLM-as-a-Judge（Appendix H/Table 9）**

- 用 Qwen3.7-Plus 评 answer correctness / coverage / context relevancy / evidence recall；SAG 在三项上最优；context relevancy 为 59.54/76.02/89.50（MuSiQue/2Wiki/HotpotQA），与最优基线差 1.63–5.97。论文解释：SAG 只喂 top-5 原 chunk，上下文比部分基线窄，不能把该单项当作全面落后。

**附录阅读覆盖（A–L）**：A 给出潜在超边的形式化与无损 incidence 编码；B 给出语料级连通性（>93% giant component）；C 给出成功/失败查询时超边激活的定性 trace；D/E 给出全量 Recall@K 与 QA 分表；F 给出完整消融/超参；G 给出运行时/token/扩展性；H 给出 LLM-judge 指标定义；I 给出抽取 prompt 与单 event/11 类约束；J 给出 LLM 重排输入输出（候选关系描述、最多 5 个 useful relation indices）；K 给出 NarrativeQA 补充结果；L 给出各方法 QA 输入上下文格式（SAG 只喂 top-5 原 chunk 全文）。这些附录不改变正文主结论，但 B9/B10 的 prompt 与评测设计应参考 I/J/L。

### 2.5 成本与规模（Appendix G / Table 8）

| 配置 | 离线索引 | retrieval | QA | 端到端 | tokens/query | F1 |
|---|---:|---:|---:|---:|---:|---:|
| SAG `Kcand=50` | 4.57 h / 28.26M tokens（共享） | **15.93 s** | 6.75 s | 22.68 s | 8,236 | 60.41 |
| SAG `Kcand=100`（主结果配置） | 同上 | **18.67 s** | 6.36 s | 25.03 s | 11,536 | 61.15 |

- 离线索引比 GraphRAG/LightRAG/HyperGraphRAG 少 55.0–82.4% 时间、72.6–86.9% tokens；但比 HippoRAG 2（2.30h/15.93M）更贵（Appendix G）。
- 在线 50→100 的代价：F1 +0.74，但 token +40.1%、端到端延迟 +10.4%；因此论文把 100 作为精度优先，把 50 作为更省的替代（Appendix G）。
- **重要限定**：论文明确说“当前 benchmark 规模下 SAG 还没有清晰的端到端成本/延迟优势”；它的优势是“把昂贵计算限制在有界查询工作集”（§5.2、Appendix G）。不要把“15.93s”当作端侧可达的延迟承诺。
- **口径说明（已在 PRD 定稿中同步）**：有些转述会把 15.93s 归到 `Kcand=100`；按 Table 8，15.93s 是 `Kcand=50`，`Kcand=100` 是 18.67s。PRD §15.1 已按此口径修正。

### 2.6 论文自述局限

1. **实体别名不消解**：只做规范化/去重，不能识别 “Apple Inc.” 与 “Apple” 是同一实体；依赖表面形式匹配的连接可能丢失。论文建议未来加轻量 alias table（§5.3）。
2. **append-only 不支持时效修订**：索引只追加，无法修订/退役过期事件；适合静态语料，不适合需要表示事实/偏好/任务状态变化的海量 agent memory；未来方向是版本化事件/双时态（§5.3）。
3. 其他隐含成本/风险：LLM 抽取/查询理解/选择都依赖模型；抽取错误可能产生误导关联；私有语料上的跨文档关联有暴露风险，部署应做 provenance/ACL/人工复核（Ethics statement）。

---

## 3. zleap-sag 应用能力清单（README-CN 提炼）

> 以下“应用”指 SAG 仓库的完整产品（Next.js + FastAPI + Electron），不是 `zleap-sag` Python 引擎单库。

### 3.1 导入与解析

- **信源**：文件与网页信源；信源是知识组织单位，可创建多个信源并跨信源检索。
- **格式**：Markdown、文本、PDF、Office 等；导入后**先规范化成 Markdown**，再后台分块、向量化、事件/实体抽取。
- **解析器**：
  - PDF 在 MinerU 配置完整时优先 MinerU；未配置/失败回退本地 MarkItDown；支持自托管 MinerU 4.x V1 API（`SAG_MINERU_PROVIDER=self_hosted`、`SAG_MINERU_BASE_URL`、私网需 `SAG_MINERU_ALLOW_PRIVATE_BASE_URL=true`）。
  - 可切换 **AnyDoc**：docx/pptx/epub/pdf/csv 本机转换，不上传、不调用托管 OCR；xls/xlsx 仍走 MarkItDown；扫描版/图文 PDF 会整体提示需要 OCR（保留页码）。
  - 其他 Office/文本默认 MarkItDown；CSV 先按编码规范成 UTF-8 再转，避免中文乱码。
- **后台流水线**：上传显示真实进度；后台显示当前阶段与已处理/总分块数（不显示整体百分比）；文档 `ready` 后才可检索。
- **模型前置**：无 API Key 也能启动；Embedding 用于索引/向量检索，LLM 用于事件抽取、查询理解、生成回答。

### 3.2 检索与原文溯源

- **范围**：可跨信源，也可只检索指定信源；未显式选择时，全局检索/默认 Agent 最多使用 **16 个候选信源**；可用 `@` 指定信源。
- **三种模式**：

| 界面名称 | 应用/API 策略 | 引擎实现 | 说明 |
|---|---|---|---|
| 快速（vector） | `vector` | 语义相似度直接召回 | 响应更快 |
| 快速（multi_es-fast） | `multi_es_fast` | BM25 实体召回 + 事件多跳，跳过 LLM 精排 | 需要词法检索支持 |
| 精确（multi） | `multi` | 实体关系 + LLM 精排 | 结果更完整 |

- 应用层把 `multi` 映射到引擎的 `full_expand`，把 `multi_es_fast` 映射到 `pruned_expand_rff`（README「检索模式」）。
- **原文块**：每条检索结果都能在右侧打开对应原文块；Agent 使用前的召回质量可直接核验。

### 3.3 图谱与探索

- **event-entity 图谱**：信源内可从列表切换到图谱，查看 SAG 索引生成的事件、实体和关联关系；README 展示 2D 图谱与 **3D 知识图谱**。
- **探索模式**：把整个知识库展开为可交互的“知识宇宙”；同一视图中搜索事件/实体、沿关联关系漫游、打开事件详情与原文。
- 这些视图的数据基础是 event-entity incidence，而非物化全局图。

### 3.4 Chat / Agent

- **带引用问答**：在本轮知识范围内检索、流式生成回答，附可点击引用；同一套能力通过 OpenAI 兼容接口开放。
- **source scope**：`@` 选择信源缩小检索范围；每个问题选择的信源标签保存在对话历史；点“重试”复用该问题保存的范围；标签表示检索范围，引用表示实际使用的证据。
- **会话行为**：新建对话草稿、历史保留、发送第一条问题后保存、停止生成、重试；一次只生成一个回答（需等当前结束或先停止）。
- **Agent 接口**：`/agents`、`/threads`、`/ask`（SSE 运行与引用）；`/openai/{agent_id}/chat/completions` 返回标准 `chat.completion` + `sag.citations`，支持 `stream:true` SSE。

### 3.5 对外集成

| 集成 | 形态 | 关键点 |
|---|---|---|
| REST/OpenAPI | 自托管 `http://localhost:8000/api/v1`，`/docs`、`/openapi.json` | 信源/文档/检索/图谱/Agent 全量路由 |
| MCP | Streamable HTTP `http://localhost:8000/mcp/` | 可用 `?source_id={id}` 限定单个信源；默认开放全部 |
| OpenAI 兼容 | `POST /openai/{agent_id}/chat/completions` | 标准响应 + `sag.citations`；SSE 流式 |
| Dify | 专用兼容端点 | 接入 Dify“连接外部知识库”，无需改 Dify 源码 |
| dsh-sag | `@zleap-ai/dsh-sag@0.2.0` | 对接 DeepSeek Harness `0.2.0-rc.2`；`dsh plugin ... add` + `dsh-sag doctor` |
| CLI/Skill | `@zleap-ai/sag-cli` | 自动发现 Docker SAG、验证 MCP、接入 Codex/Claude Code；JWT 不写入 Agent 配置 |

- 默认 `local` 模式用“名字”签发 SAG JWT；`password` 模式用邮箱+密码；`SAG_ALLOW_REGISTRATION=false` 关闭后续注册；生产需 HTTPS、强 `SAG_SECRET_KEY`、VPN/IP 白名单/反代限流。
- 默认 Compose 只绑定 `127.0.0.1` 的 3000/8000；`local` 名字不是密码认证，不能直接暴露公网。

### 3.6 桌面、部署、模型与存储

- **桌面客户端**：Electron 打包同一套 Web + 本地 FastAPI；macOS 15+ Apple Silicon 已签名/公证，Windows 10/11 x64 暂不签名；应用内更新由用户分别确认下载和安装；整包更新不覆盖应用数据目录；**检测到不兼容旧知识数据时保持旧数据不变，直到用户确认备份并建立全新知识库；不自动迁移/合并**；账号和模型配置保留，旧知识库/文档/智能体/会话/任务记录不进入新工作区。
- **部署**：`docker compose up -d --build`；Web 3000、API 8000；两个服务 healthy；`docker compose down` 保留数据，`down -v` 永久删除。
- **存储后端**：

| 方式 | 关系型 | 向量 | 安装 extra |
|---|---|---|---|
| 本地默认 | SQLite | LanceDB | 无 |
| 单数据库 | PostgreSQL | pgvector | `zleap-sag[postgres]` |
| 生产拆分 | MySQL/PostgreSQL/OceanBase | Elasticsearch | `[mysql]`/`[postgres]`/`[es]` |
| 单数据库 | OceanBase 4.3.3+ | OceanBase vector | `[mysql]` |

- **模型配置**：首次可用 302.AI 或 DeepSeek 快速配置；DeepSeek 使用 Flash 非思考模式；Embedding 可选 302.AI/智谱/阿里云百炼（不同厂商需分别填 Key）；默认向量模型 `Qwen/Qwen3-Embedding-4B`；升级已有 BGE 索引需保留 `SAG_EMBEDDING_MODEL=bge-large-en-v1.5` 或先删旧索引再换模型；`SAG_LOCK_LLM_CONFIG=true` 可锁定部署配置；可选 Chatbot LLM / query embedding，支持 OpenAI 兼容/Responses API；保存前可测试连接，不返回密钥明文。
- **版本/许可**：SAG v1.8.11；Python 3.11+、Node 20+、MIT；应用要求 `zleap-sag==0.13.0`。

### 3.7 引擎公共 API（`DataEngine`）

- `parse(source)` → `ParsedSource`；`chunk(parsed)` → `ChunkSet`；`ingest(source)` → `ChunkSetRef`；`extract(target)` → `EventSetRef`；`search(request)` → `SearchResult`（`chunks/events/stats`）；`start/aclose/init_schema`。
- `SearchRequest` 含 query/scope/options；`SearchOptions` 含 `strategy`、`top_k`、`return_type`、`fallback_strategy`，以及 `recall/ranking/graph/selection/output` 等子选项（参考 `pipeline/options.py:345-396`）。
- 策略枚举：`vector`、`atomic`、`full_expand`、`pruned_expand_llm`、`pruned_expand_rerank`、`pruned_expand_rff`（`pipeline/options.py:225-231`）。
- 配置：`EngineConfig(...)` 参数注入，或 `EngineConfig.from_env()`；两者不混用。

---

## 4. 当前底座清单（Phase 1 + B1–B7，HEAD `0003d76`）

### 4.1 已交付能力

| 能力 | 实际行为 | 证据 |
|---|---|---|
| 导入 | SAF 选择 TXT/MD；UTF-8 去 BOM；NFC/CRLF 规范化；`parserId=txt-utf8-v1`/`md-utf8-v1` | `…/core/text/DocumentParser.kt:5-23`；`TextNormalizer.kt:7-13` |
| 分块 | 600 字符上限；标题行强制开新块；超长硬切（不劈 surrogate pair）；`charStart/End` 精确保留原文 | `…/core/text/Chunker.kt:17-28,34-72` |
| 嵌入 | EGemma2 三强度（270M/440M/740M + 2 个 SM8750 NPU 变体，共 5 条 catalog）；768d；端侧 LiteRT/UE 两条通道 | `…/core/model/ModelCatalog.kt:53-109`；B7 报告 |
| 向量存储 | Room `chunk_embedding`，float32 LE blob；按 `(spaceId, role)` 唯一；角色列预留 | `…/data/db/Entities.kt:110-135` |
| 关键词 | FTS4 `chunk_fts`，bigram 预分词；OR 召回 + bigram 覆盖分重排 | `…/data/db/Entities.kt:82-88`；`…/core/search/SearchService.kt:218-246` |
| 融合 | RRF，`RRF_K=60`；候选 `topK×3`（向量）/`topK×12`（关键词）；同分按 chunkId 稳定 | `…/core/search/SearchService.kt:411,523-551` |
| 证据 | `chunk→document→source` 批量回填；`charStart/End`、docTitle/sourceTitle/sourceUri、通道与名次 | `…/core/search/SearchService.kt:51-65,137-169`；`…/ui/EvidenceScreen.kt:24-41` |
| 导入状态机 | `queued→parsing→chunking→embedding→storing→done/failed/cancelled`；幂等重跑；进程被杀恢复；取消/重试 | `…/core/ingest/IngestRepository.kt:79-105,334-420` |
| 删除 | source 删除在单事务内清理 embeddings/FTS/chunks/jobs/documents/source（`SourceDao.deleteCascade`）；chunk/event 等由 FK 级联兜底 | `…/data/db/Daos.kt:64-73`；`AppDatabase.kt:100-140`；B1 |
| 备份/迁移 | 单 zip `FORMAT_VERSION=2`：meta/requirements/sources/documents/chunks/app_kv/vectors；兼容 v1；旧空间过滤/拒绝 | `…/core/backup/KnowledgeBackup.kt:37-40`；`BackupValidator.kt:29-30` |
| 模型商店 | 5 条 EGemma2；SHA-256 校验、断点续传、共享目录、路径偏好、旧空间检测 | `…/core/model/ModelCatalog.kt:53-109` |
| 离线/隐私 | 仅 `INTERNET` 权限且只用于显式模型下载；`allowBackup=false`；禁明文，仅 hf-mirror HTTPS 白名单 | `app/src/main/AndroidManifest.xml:9,17-22` |
| UI | home/import/search/sources/settings 五屏（debug 另有 benchmark）；证据详情为检索屏内覆盖层 | `…/MainActivity.kt:93-112`；`EvidenceScreen.kt:24-41` |
| 工程 | B7 终态 243 JVM tests / 35 instrumentation；lint 0 errors；release 49.07MB；v0.1.0-mvp 已签名发布 | B4_FIX_REPORT §1/§3.1；B7_FIX_REPORT §1/§3.3 |

### 4.2 数据层实际状态（关键）

Room schema v5 共 13 个 entity：

| 表 | 状态 | 证据 |
|---|---|---|
| `source`/`document`/`chunk` | 在用 | `Entities.kt:19-73` |
| `chunk_fts` | 在用（bigram） | `Entities.kt:82-88` |
| `embedding_meta`/`chunk_embedding` | 在用（空间/角色/向量） | `Entities.kt:91-135` |
| `event` | **建表，无 DAO/无写入** | `Entities.kt:137-156` |
| `entity` | **建表，无 DAO/无写入；`entityType` 可空 String** | `Entities.kt:158-163` |
| `event_entity` | **建表，无 DAO/无写入** | `Entities.kt:165-188` |
| `chunk_entity` | **建表，无 DAO/无写入** | `Entities.kt:190-212` |
| `ingest_job`/`schema_migrations`/`app_kv` | 在用 | `Entities.kt:216-242` |

- `AppDatabase` 的 entity 列表包含 4 张关系表（`AppDatabase.kt:12-22`），但抽象 DAO 只有 `source/document/chunk/embedding/ingestJob/schemaMigration/appKv` 七个（`AppDatabase.kt:30-36`）；`Daos.kt` 中没有 `EventDao`/`EntityDao`/`EventEntityDao`（`Daos.kt:21,90,150,234,364`）。
- `event` 表的外键指向 `chunk`，`event_entity`/`chunk_entity` 的级联已随 v5 建好（`AppDatabase.kt:253-300`），但迁移中的 `DELETE FROM event/event_entity/chunk_entity` 只是清理历史孤儿，不构成写入路径。
- 结论：这是**为 Phase 2 预留的 schema 骨架**，不是“已实现的 SAG 数据层”。

### 4.3 明确限制

1. **只支持 TXT/MD**：无 PDF、Office、OCR、网页信源；`DocumentParser` 注释明确“PDF/Office 为后续阶段非目标”。
2. **无 event/entity 抽取**：无 `EventEntityExtractor`、无 prompt、无 LLM 调用、无 `NOT_INDEXED/QUEUED/RUNNING/DONE/FAILED` 抽取状态。
3. **无 LLM**：没有 `core/llm`、没有 OpenAI-compatible client、没有 API key/Keystore、没有流式生成；因此无 Chat、无 Query 实体识别、无 LLM 选择、无引用问答。
4. **无图谱/探索**：无 graph view、无 3D、无 event 详情、无 incidence 查询。
5. **无集成/服务端**：没有 REST/OpenAPI、MCP、OpenAI 兼容、Dify、dsh-sag；Android 单机、无账号/JWT/部署形态。
6. **检索能力单一**：无 `vector/multi_es_fast/multi` 策略参数、无 source scope、无 event 双路径、无 stage trace；只有 chunk 融合检索。
7. **评测已天花板**：262 条为单语料/单证据混合集；R@10=1.0，无法区分多跳能力（见 §7）。
8. **备份范围有限**：v2 只覆盖 chunk/向量/空间/导入元数据；不含 event/entity/chat（当前也不存在），PRD 目标为 v3。
9. **chunk 偏离参考默认**：600 字符 vs 参考 `max_tokens=800`；为 1024 token 引擎签名限制的有意取舍，但会改变事件抽取的输入粒度（见 §5 行 3、§8.4）。
10. **FTS4 非 BM25**：bigram + 覆盖分；5000 命中安全上限，超过时按 rowid 截断，存在长尾召回风险（`SearchService.kt:463`）。
11. **模型目录仅 EGemma2**：无远程 embedding provider、无 BGE 等对照模型；跨模型比较需另建评测。
12. **版本/依赖锁定**：B4/B7 的依赖升级与路线收敛已完成，但引入网络/LLM 依赖需重跑 strict metadata 门禁。

### 4.4 B1–B7 的质量证据（用于判断“地基是否可信”）

- B7 终态：243 JVM tests / 0 failed；35/35 instrumentation；lint 0 errors；release 49,068,516 B；旧空间可检出、旧备份写库前拒绝、v3→v5 链式迁移 PASS（B7_FIX_REPORT §1/§3.4）。
- 262 条评测（440M，K60U/K40/旗舰）：fused R@5=0.9955、R@10=1.0、MRR=0.9841、NDCG=0.9881；三端一致（W3:77-82,141-147,205-218）。B1 迁移回归另记录 R@1=.964/R@5=.991/R@10=1.0（口径/模型不同，B1:20-24）。
- 结论：**chunk 检索地基的工程可靠性是可信的**；问题不在“地基不稳”，而在“上层 SAG 机制不存在”。

---

## 5. 能力对齐矩阵（28 行）

> 证据列中 `…/` = `app/src/main/java/com/zhibox/memory/`；`README` = README-CN（commit `a6f6a00`）；`参考` = `docs/reference/zleap-sag-0.14.0/`；`论文 §` = arXiv:2606.15971v2。

| # | 能力 | zleap-sag 描述 | 当前状态 | 证据（`path:line` / 文档锚点） | 差距/影响 |
|---:|---|---|:---:|---|---|
| 1 | 导入格式/解析 | MD/TXT/PDF/Office/网页；先规范化 Markdown；MinerU/AnyDoc/MarkItDown 回退 | 🟡 | `…/core/text/DocumentParser.kt:5-23`；README「导入知识」 | 仅 TXT/MD UTF-8；无 PDF/Office/OCR/网页；导入面窄，但基础链路可用 |
| 2 | 文档结构规范化 | 文档→Markdown，保留标题/表格/图片等结构块 | ❌ | `…/core/text/TextNormalizer.kt:7-13` | 仅 NFC/CRLF；无 Markdown/表格/图片块；event 抽取输入缺结构信息 |
| 3 | chunk 策略 | 参考 `max_tokens=800`、`overlap=0`、heading 感知 | ⚠️ | `…/core/text/Chunker.kt:17-28`；参考 `pipeline/options.py:43-52` | 600 字符（1024 token 引擎签名限制）；有意偏离，但会改变抽取粒度，需 B9 评估 |
| 4 | event 抽取（1 chunk=1 event） | 每 chunk 单次 LLM 结构化调用，1 event + entities | ❌ | `…/data/db/Entities.kt:137-156`（表占位）；无 extractor | 无 prompt/LLM/写入；event 数恒 0；SAG 的语义单元不存在 |
| 5 | entity 类型/归一 | 11 类封闭枚举；字符串规范化 + SQL 去重，不做别名消解 | ❌ | `…/data/db/Entities.kt:158-163`；参考 `db/models.py:744-905` | 无枚举、无归一化/去重逻辑、无写入；`entityType` 可空 String，无法约束 11 类 |
| 6 | event-entity incidence | `(event, entity)` 多对多行；按 event 分组还原实体集；潜在超边 | ❌ | `…/data/db/Entities.kt:165-188`；`AppDatabase.kt:12-22` | 表已建、无 DAO/读写；潜在超边与 SQL join 均未实现 |
| 7 | 向量角色 | 六角色：chunk.heading/content、event.title/content、entity.name、event_entity.relation | 🟡 | `…/data/db/Entities.kt:127-135`；参考 `sag-api/octx_vector_protocol.py:16-48` | 仅写 chunk `content`；event/entity 向量与独立空间未实现 |
| 8 | chunk 向量检索 | dense chunk recall；服务端可用 ES/HNSW/LanceDB | ✅ | `…/core/search/SearchService.kt:186-216`；MVP1:25-26 | 端侧精确扫描；1k/10k p95 达标；与 SAG 的 event 向量不是同一层 |
| 9 | 词法索引 | BM25/tantivy FTS；实体/事件词法召回 | 🟡 | `…/data/db/Entities.kt:82-88`；`…/core/search/SearchService.kt:218-246,463` | FTS4 bigram OR + 覆盖分，不是 BM25；5000 命中上限；仅 chunk |
| 10 | SQL 关系扩展 | seed events→共享 entities（frontier≤50）→未见 events；`L=1` | ❌ | 无对应代码；参考 `pipeline/options.py:269-285`（graph 选项） | 论文扩展项贡献 −10.95 R@5；当前完全没有结构扩展 |
| 11 | 三模式检索 | `vector` / `multi_es_fast`（`pruned_expand_rff`）/ `multi`（`full_expand`） | ❌ | `…/core/search/SearchService.kt:80,345` 仅 chunk 级 `search`/`searchChannels` | 无 mode 参数/策略路由；只有一种 chunk 融合检索 |
| 12 | source scope | 跨信源或指定 `source_id`；全局默认≤16 候选；`@` 限定 | ❌ | `…/core/search/SearchService.kt:80` 无 `sourceIds`；`SearchViewModel.kt:74` | 只能全库检索；无法做 source 限定/多库对比 |
| 13 | 双路径输出 | 结构路径≤5 events→chunks + 语义路径补足 `Kout=10` | ❌ | 无 event 路径；`CandidateFusion` 单路 | 只有 chunk 单路 RRF topK；无“结构证据链 + 语义补位” |
| 14 | LLM 上下文选择/精排 | LLM 联合看 `Kcand=100` 候选，选≤5 events；非 pointwise | ❌ | 无 LLM/selector；参考 `prompts/rerank.yaml` 仅存档 | 论文最大消融项（−13.25）缺失；多跳证据链无法联合选择 |
| 15 | 引用与溯源 | 每条结果/引用打开原文 chunk | 🟡 | `…/core/search/SearchService.kt:51-65,137-169`；`…/ui/EvidenceScreen.kt:24-41` | `charStart/End` + source→doc→chunk 链与论文“chunk 是最终证据”一致；但**仅检索结果溯源，无生成答案的引用/校验**，故降为部分 |
| 16 | 问答/多轮 | Chat/Agent 多轮、流式、引用、`@scope`、停止/重试 | ❌ | `MainActivity.kt:93-112` 无 chat 路由；无 `core/llm` | 未实现；无答案生成/引用校验/会话存储 |
| 17 | 图谱/探索 | event-entity 2D/3D 图谱、Explore 模式、event 详情 | ❌ | 无 graph 代码/路由；README「查看图谱」「探索模式」 | 未实现；incidence 数据也不存在，无法先做图 |
| 18 | 备份/迁移 | 桌面端旧数据不自动迁移；备份/恢复；工作区数据 | 🟡 | `…/core/backup/KnowledgeBackup.kt:37-40`；`BackupValidator.kt:29-30`；B1/B7 | 备份 v2（chunk/向量/空间）稳健；event/chat 未纳入，PRD 目标 v3 |
| 19 | 模型商店 | 内置模型配置/下载；测试连接 | 🟡 | `…/core/model/ModelCatalog.kt:53-109`；MVP1:44-48 | 5 条 EGemma2（270/440/740+NPU）；SHA/断点；形态是端侧模型文件，非云端 Provider |
| 20 | LLM 配置/Provider | 302.AI/DeepSeek/OpenAI-compatible；Chatbot/Query embedding；密钥管理 | ❌ | 无 `core/llm`；PRD FR-08 计划 B8/B11 | 无 base URL/key/streaming/retry/offline guard；multi/chat 全部被阻塞 |
| 21 | 网络/隐私 | 默认本地；password 模式；HTTPS；密钥不泄露 | 🟡 | `AndroidManifest.xml:9,17-22`；MVP1:18-19 | 仅模型下载联网、禁明文、`allowBackup=false`；无 LLM payload 最小化/禁网开关（因为无 LLM） |
| 22 | 集成 | REST/OpenAPI、MCP、OpenAI 兼容、Dify、dsh-sag | ❌ | 无 server/API 代码；README「API 地图」 | 未实现；PRD 列为 P2，不阻塞 P0 |
| 23 | 部署/认证 | Docker/桌面、账号模式、JWT、CORS/HTTPS | ❌ | Android 单机；无 auth | 未实现；与端侧产品形态不同，需单独立项 |
| 24 | 增量更新/时效 | append-only；新 chunk 追加；不做全局重建 | 🟡 | `…/core/ingest/IngestRepository.kt:334-420`；论文 §5.3 | chunk 级追加/ready 语义有；event 级追加/版本化无；继承 append-only 时效局限 |
| 25 | 评测基准 | HotpotQA/2Wiki/MuSiQue/NarrativeQA；R@5/F1/LLM-judge | 🟡 | `scripts/eval/evalset.json:20-27,2278`；W3:77-82 | 262 条单语料、220 正例各 1 relevant；R@10=1.0 天花板；不可与论文横比 |
| 26 | 诊断/trace | 分阶段 trace（seed/expanded/candidates/selected）+ 状态/耗时 | ❌ | `…/core/search/SearchService.kt:67-78` 仅计数/耗时；ingest 有阶段 | 无 SAG 阶段 trace/降级原因；多跳失败无法定位 |
| 27 | 生命周期/级联删除 | 删除 source/document 级联 event/entity/向量 | ✅ | `Daos.kt:64-73`；`AppDatabase.kt:100-140`；B1:20-24；MVP1:14 | 五表显式清理 + 8 条 FK 级联兜底；event 表为空但 FK 已就绪；删除路径可信 |
| 28 | FTS 重建/索引健康 | 派生索引可重建 | ✅ | `…/core/ingest/IngestRepository.kt:760-788,871`；B2/B3 | FTS 重建/检查/修复入口；范围仅 chunk，但机制与 SAG“可重建派生索引”一致 |

**矩阵统计：✅ 3 行（8、27、28）；🟡 9 行（1、7、9、15、18、19、21、24、25）；❌ 15 行（2、4、5、6、10、11、12、13、14、16、17、20、22、23、26）；⚠️ 1 行（3）；合计 28 行。**

### 5.1 矩阵读法（避免误读）

- **✅ 不等于“SAG 对齐”**：行 8/27/28 对齐的是 **chunk 级检索/工程外围**；行 15 仅检索结果溯源（无生成引用）已降为 🟡；SAG 的“event-entity 结构”仍为 ❌。
- **🟡 的行是“有基础但不足以支撑论文语义”**：例如行 9 有词法通道，但不是 BM25/实体/事件级；行 21 有离线/隐私边界，但没有 LLM 联网控制；行 25 有评测脚本，但不是多跳基准。
- **⚠️ 行 3 是唯一“有意偏离”**：600 字符 < 参考 800 tokens，且有 1024 token 引擎限制的硬约束；B9 必须用抽取质量/多跳评测确认是否要调 chunk 上限，不能只按“与参考一致”改。
- **❌ 的 15 行里有 4 行（4/5/6/10）是 SAG 的“表示+扩展”核心**；这 4 行不落地，后面 11/13/14/16/17 即使做 UI 也只是空壳。

---

## 6. 架构阶段映射矩阵（论文 ↔ 当前代码）

> 本矩阵回答“论文的每个阶段，在当前代码里是有、是无，还是用别的机制替代”；不参与 §0.2 的计数。

| 论文阶段 | 论文做法/默认值 | 当前实现 | 判定 | 说明/证据 |
|---|---|---|---|---|
| 离线：解析 | 文档→Markdown，PDF/Office/网页 | TXT/MD UTF-8，NFC/CRLF | **部分** | `DocumentParser.kt:5-23`；无 PDF/Office/OCR/网页 |
| 离线：分块 | 语义 chunk；参考 800 tokens、overlap 0、heading 感知 | 600 字符；标题感知；精确 offsets | **部分/偏离** | `Chunker.kt:17-28`；600 字符为 1024 token 引擎限制 |
| 离线：event/entity 抽取 | 每 chunk 1 次 LLM；1 event + 11 类 entities | 无 | **无** | `Entities.kt:137-163` 仅表；无 extractor/prompt |
| 离线：SQL incidence | `event_entity` 多对多行；append-only | 表已建、无写入 | **无** | `Entities.kt:165-188`；`AppDatabase.kt:12-22` |
| 离线：向量索引 | chunk/event/entity 六角色向量 | 仅 chunk content 向量 | **部分** | `Entities.kt:127-135`；`SearchService.kt:186-216` |
| 离线：全文索引 | chunk/event/entity FTS（BM25） | chunk FTS4 bigram | **部分/替代** | `Entities.kt:82-88`；`SearchService.kt:218-246` |
| 在线：query 理解 | LLM 识别 query entities | 无 | **无** | 无 LLM/query entity 逻辑 |
| 在线：Path A（entity seed） | LLM entities → entity 向量 τ=0.9 → SQL join | 无 | **无** | 无 entity 向量/无 join |
| 在线：Path B（event seed） | query 向量 → event 向量 τ=0.4 | 无 event 层；用 chunk 向量替代 | **无（有 chunk 替代）** | `SearchService.kt:186-216` 是 chunk 召回，不是 event 召回 |
| 在线：扩展 | 反向 join 实体→frontier≤50→未见 events；`L=1` | 无 | **无** | SAG 核心增益阶段 |
| 在线：粗排 | event 向量相似度 Top `Kcand=100` | 替代为 chunk RRF topK=10、候选×3/×12 | **替代** | `SearchService.kt:196,246,523-551`；不是 event 级粗排 |
| 在线：LLM 选择 | 联合看候选，选≤`Kevent=5` events | 无 | **无** | 无 selector；参考 `prompts/rerank.yaml` 仅存档 |
| 在线：双路径输出 | 结构≤5 + 语义补足 `Kout=10`；去重 | 单路 chunk RRF topK=10 | **替代（单路）** | `SearchService.kt:80,523-551`；无结构路径 |
| 输出：证据映射 | event→chunk（φ），最终返回原 chunk | chunk→document→source；charStart/End | **有（chunk 级）** | `SearchService.kt:51-65,137-169`；`EvidenceScreen.kt:24-41`；与论文输出边界一致 |
| 增量：append-only | 新 chunk 追加，不重算全局图 | chunk 追加/ready；重导入幂等 | **部分** | `IngestRepository.kt:334-420`；无 event 追加/版本化 |
| 可观测：trace | seed/expanded/candidates/selected/timings | 仅向量/关键词候选数、elapsed；ingest 有阶段 | **部分/替代** | `SearchService.kt:67-78`；无 SAG 阶段 trace |

**阶段映射结论**：论文的 **离线抽取、SQL incidence、双 seed、1 跳扩展、LLM 选择、双路径输出** 六个关键阶段在当前代码中全部为“无”或“替代”；只有“解析/分块/证据映射/增量写入”存在部分或 chunk 级实现。换句话说，当前底座能“检索到 chunk”，但不能“沿着 event-entity 结构找回证据链”。

---

## 7. 质量/评测对齐

### 7.1 论文 vs 当前评测

| 维度 | 论文 | 当前 ZhiBox |
|---|---|---|
| 数据集 | MuSiQue、2WikiMultiHopQA、HotpotQA；补充 NarrativeQA | 自建 262 条中文/英文/混合查询；50 篇文档 |
| 规模 | 每数据集 1,000 dev questions；pooled corpora 11,656 / 6,119 / 9,811 passages；NarrativeQA 293 q / 4,111 passages | 262 queries；50 docs；无 2–4 跳证据链标注 |
| 多跳性 | MuSiQue 最多 4 跳、反事实过滤；2Wiki 多文档推理；HotpotQA 2 跳 | 220 条正例**每条只有 1 个 relevant 文档**；50 条标 `multihop` 的也全部只有 1 个 relevant；42 条 adversarial 为 0 |
| 指标 | 主表 R@5；附录 R@1/2/5/10；F1；LLM-judge（correctness/coverage/context relevancy/evidence recall） | R@5/R@10、MRR@10、NDCG@10；无 F1/LLM-judge/citation validity |
| 对照 | BM25、Contriever、BGE、GTE-Qwen2、GritLM、NV-Embed-v2、GraphRAG、LightRAG、HippoRAG 2、HyperGraphRAG、HyperRAG | 仅 vector / keyword / fused 三通道对照；无外部基线、无服务端 SAG 对照 |
| 消融 | 无扩展、pointwise reranker、triple、Kcand、Kevent、L | 无 SAG 消融；只有 B2 检索质量修复对照 |
| 结果 | SAG avg R@5/F1 = 90.07/72.96；MuSiQue 80.36/61.15 | 262 条 fused R@5=0.9955、R@10=1.0、MRR=0.9841、NDCG=0.9881（440M，三端一致） |
| 结论 | 多跳检索 SOTA | **仅证明 chunk 检索在自建集上饱和；不能证明多跳** |

> 数据口径说明：262 条构成按 `scripts/eval/evalset.json` 实际统计为 **50×4 + 42 adversarial + 20 numdate = 262**；`W3-ROBUSTNESS-EVAL.md:77` 已同步为同一构成。另，`B1_FIX_REPORT.md:20-24` 的迁移回归 R@5=.991 与 `W3:77-82` 的 .9955 是不同基线/模型口径，不能混用。

### 7.2 当前评测的结构性问题

1. **单证据**：220 条正例的 `relevant` 长度全为 1（本报告对 `evalset.json` 的统计）；这只能测“找到一个 chunk”，不能测“把多个 chunk 组成证据链”。
2. **天花板效应**：R@10=1.0，R@5=0.9955；W3 自己承认“评测集对两模型都偏易，区分度有限”（W3:55）。任何 SAG 改造都无法在这套集上体现增益。
3. **单语料**：50 篇文档，`n_docs=50`（`evalset.json:2278`）；无跨信源、无 source scope、无大规模干扰项。
4. **无引用/无答案**：没有 citation validity/support、没有“资料不足”拒答、没有 LLM 幻觉测试；无法验收 B11。
5. **无消融**：没有 vector vs fast vs multi、无扩展/无 selector/无 event 层的对照；无法判断新机制是否真的带来增益。
6. **人工标注强度**：MVP1 自述“结构化生成+人工校订（非全人工）”；多跳证据 ID 未锁定，存在偏差风险。

### 7.3 建议：新增中文多跳评测集（P0 门禁）

按 PRD FR-11 / ROADMAP B10 的要求，建议在 B10 前完成：

- **规模**：≥120 条中文多跳查询，2–4 跳；每条人工校验 evidence chunk IDs（不是 doc 级，而是 chunk 级）。
- **覆盖**：多文档/单文档、source 限定、同义/别名、数字/日期、无答案问题；至少 20 条 adversarial/不可答。
- **指标**：R@5/R@10、MRR@10、citation validity（引用⊆证据）、citation support（抽样人工/LLM judge）、拒答正确率、延迟/内存。
- **对照/消融**：`vector` / `multi_es_fast` / `multi`；无扩展、无 event 层、无 selector；现有 chunk RRF 基线。
- **P0 门禁（与 PRD/ROADMAP 一致）**：
  - 多跳 R@5 ≥ 当前融合基线 **+5pp**，且绝对值 **≥0.70**；
  - 单跳 262 集 R@5 回退 **≤0.5pp**；
  - 引用有效性 **100%**，抽样答案证据支持率 **≥90%**；
  - 禁网零请求；迁移/备份/取消/失败路径回归通过；
  - 三档真机（PJZ110/K60U/K40）延迟/内存达标。
- **不建议**直接把论文英文基准翻译成中文当门禁：模型、语料、语言、服务端配置都不同；论文数字只能做方向性参考，不能做端侧验收线。

---

## 8. 结论与建议

### 8.1 是否对齐（明确判定）

> **不对齐。更精确地说：矩阵 28 行中仅 3 行 ✅（chunk 向量检索/生命周期/FTS 重建）、9 行 🟡 基础外围（含检索结果溯源但无生成引用），SAG 核心机制（event-entity/SQL 扩展/LLM 选择/双路径）与应用层均未对齐。**

- 如果“对齐”指“能跑通 SAG 论文的 event-entity + 动态超边 + 多跳双路径”，答案是 **❌ 不对齐**；
- 如果“对齐”指“有可复用的地基，能按 B8–B12 增量补齐”，答案是 **✅ 地基可用，但当前不是 SAG**；
- 对外表述建议沿用 MVP1 的诚实定位：“高质量离线 chunk 检索 + SAG 增强路线图”，**不宣称“等同 SAG”或“复现论文 SOTA”**（MVP1:37 已记录该原则）。

### 8.2 差距 → PRD FR / B8–B12 映射

> PRD 路径：`docs/product/PRD_ZhiBox_SAG_App_v1.0.md`（v1.0 已评审）；路线图：`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md`（v1.0）。以下引用其**计划内容**，不代表已实现。

| 差距 | 对应 FR | 批次 | 计划内容（PRD/ROADMAP 摘要） |
|---|---|---|---|
| event/entity/incidence 数据模型、DAO、迁移、备份骨架 | FR-01/02/10 | **B8** | Room schema v6 additive；扩展 event/entity/event_entity；新增 `sag_vector`/`event_fts`/`entity_fts`/`extraction_job`/chat 表；v5→v6 迁移；备份 v3 骨架（PRD §6、ROADMAP B8） |
| 1 chunk=1 event、11 类实体、抽取任务/重试/补抽取 | FR-01/02/09 | **B9** | 导入联动 `extraction_job`；远程 LLM 严格 JSON；中文指令 + 11 类 + 单 event；event/entity 向量/FTS；状态/取消/重试；30–50 chunk 抽检（ROADMAP B9） |
| 双 seed、1 跳 SQL 扩展、三模式、source scope、双路径、trace | FR-03/04/05/09 | **B10** | `SagRetriever`；`multi_es_fast` 离线可用；`multi` 接 selector（B11 真实 LLM，B10 可 mock）；Kpool/Kseed/frontier/Kcand/Kevent 档位；Kout=10；trace（ROADMAP B10） |
| LLM 上下文选择、带引用问答、Chat/隐私 | FR-06/08/09 | **B11** | `ContextualSelector`（≤Kevent）；`AnswerGenerator`（流式，仅证据）；`CitationValidator`（引用⊆证据、可定位、拒答）；Chat UI；禁网/密钥/最小 payload（ROADMAP B11） |
| 图谱/事件详情、诊断、备份 v3、真机验收 | FR-07/09/10/11 | **B12** | 来源页图谱/event 详情；设置/诊断导出；备份 v3；三档真机 P0 验收（ROADMAP B12） |
| 文档结构规范化（Markdown/表格/图片块）及 PDF/Office/网页解析 | P1（README 导入；PRD §3） | B13+ | 当前仅 TXT/MD+NFC；放入 P1 导入增强，不阻塞 P0 |
| PDF/Office/网页、Explore/3D、多轮 Agent/工具、别名表 | P1（PRD §3） | B13+ | 第一版后交付；不阻塞 P0 |
| REST/MCP/OpenAI 兼容/Dify/dsh-sag、本地 LLM、同步、多模态、ANN | P2（PRD §3） | 触发式 | 用户明确需要/规模触发；不进入 P0 |

**映射原则**：B8 只做“地基+契约”，B9 才写 event/entity，B10 才做检索，B11 才做问答，B12 才做图谱/发布；**不要先做 Chat/图谱 UI**，否则没有 incidence 数据只能做假图/空态。

### 8.3 推进顺序建议

1. **B8 先锁 schema/契约/LLM Provider/备份 v3 骨架**，不改现有 chunk 检索行为；以“未配置 LLM 零回归”为门禁。
2. **B9 先做抽取质量抽检，再谈检索**；用 30–50 chunk 人工抽检 11 类/单 event/忠实性；抽取失败隔离，不让 chunk 检索不可用。
3. **B10 先发 `multi_es_fast`（离线、无 LLM）**；`multi` 可先 mock selector，等 B11 接真实 LLM；门禁不达就把 `multi` 标 experimental，不阻塞离线价值。
4. **B11 再做引用问答**；引用校验和拒答必须先于“好看”的 Chat UI。
5. **B12 最后做图谱/真机/P0 验收**；图谱只读 incidence，不物化全局图；大库分页/上限。
6. **P1/P2 不插队**：PDF/Office、Explore/3D、MCP/REST 等是 README 应用能力，但不是当前 P0 的阻塞项。

### 8.4 B8 前必须锁定的设计决策（建议写入 PRD/ADR）

1. **1 event/chunk vs 0.14.0 的多 event/children**：P0 按论文/PRD FR-01 选“最多 1 个 active event”；0.14.0 提示词的聚合/拆分/children 语义只作参考，不直接照搬。否则 event→chunk 映射、去重、Kout 都会变复杂。
2. **11 类封闭枚举 vs 参考的 `entity_type` 表**：P0 用 11 类封闭 schema（PRD §4/FR-01）；参考的 `entity_type` 表（`models.py:639-742`）可配置 scope/weight/threshold，但端侧 P0 不需要动态类型；别名表留给 P1。
3. **event/entity 的输出语言**：论文 prompt 要求英文 title/content/entity names（Appendix I）；参考 0.14.0 是中文抽取。ZhiBox 是中文产品，建议 prompt 中文指令 + 允许中文 event 内容，但 `language` 字段必须记录；检索/评测按语言分层。不要把“英文 event”当硬要求。
4. **chunk 上限 600 字符 vs 800 tokens**：600 是当前引擎限制，不是语义最优；B9 需要比较 600/800（若引擎允许）对抽取完整性和多跳召回的影响，再决定是否调整。不要在没有评测的情况下改 chunk 契约。
5. **向量角色/空间**：`chunk_content`、`event_content`、`entity_description` 必须是独立 spaceId/role 指纹（PRD §6.2）；不能把 event 向量写进 chunk 空间；查询向量复用同一 embedding provider，但角色模板要区分。
6. **LLM 边界**：P0 只承诺 OpenAI-compatible Chat Completions HTTPS 子集（PRD §8.1）；不承诺自定义 headers/证书；密钥 Keystore；禁网开关前置；抽取/选择/问答的 prompt 版本号写入 event/trace。
7. **预算档位**：按 PRD §7.2 的高/中/低档（Kpool 500/300/150；Kseed 50/30/20；frontier 50/30/20；Kcand 100/60/40；Kevent 5/4/3；Kout 10）落地；所有预算进 trace，允许高级设置覆盖。
8. **评测先行**：B10 的 120 条多跳集应在 B9 期间开始建；不要等 B10 代码写完再找标注，否则门禁会倒逼“降标准”。
9. **备份/迁移 additive**：v6 只加表/列/索引，迁移中不做网络/重嵌入；旧 v2 备份导入后 chunk 检索照常、event 为空并提示补抽取（PRD §6.3）。
10. **trace schema**：至少记录 `queryHash/mode/seedEntities/seedEvents/expanded/candidates/selected/degradedReason/timings`；不含正文；可脱敏导出（PRD FR-09）。
11. **模式语义与论文差异**：`vector` 模式是现有 chunk 向量+FTS+RRF 的混合检索（非纯 dense）；`multi_es_fast` 的 seed 必须走 event 向量 + entity，chunk 向量只做语义补位/降级。UI/文档要标注该差异，不能把 `vector` 宣传成 SAG 的 dense vector 或把 fast 说成论文完整流程（PRD FR-03/ROADMAP B10 已按此修订）。

---

## 9. 风险与限制

| 风险 | 说明 | 缓解/建议 |
|---|---|---|
| **端侧/远程混合 vs 论文服务端** | 论文用 MySQL+ES、BGE-Large+Qwen3.6-Flash、全量语料；当前是 SQLite/Room + EGemma2 768d + 远程 LLM；论文 retrieval 15.93s 已是服务端数字，端侧 SQL join/远程首 token 可能更慢 | 不横比论文分数；按 PRD §9.1 设端侧分档门禁；先发离线 fast；multi 失败可降级 |
| **LLM 隐私** | 问答/抽取会把 query、候选 event/chunk 文本发到远程；API key、日志、备份都可能泄露 | Keystore、禁网总开关、首次联网确认、payload 最小化、日志脱敏、备份不含 key；抓包/单测断言零请求（PRD FR-08） |
| **LLM 成本/延迟** | Kcand 50→100 token +40.1%、延迟 +10.4%；远程 LLM 还有网络/限流/费用；端侧 embedding 已占导入 60–90% 时间 | token 预算、超时/重试≤1、fast 降级、缓存/批处理；不把 multi 设为默认 |
| **中文抽取质量** | 11 类实体在中文的边界/类型/别名更难；论文明确不做别名消解；600 字符 chunk 可能切碎事件；抽取错误会污染扩展 | 30–100 chunk 人工抽检；严格 JSON schema；忠实性/类型/覆盖率指标；别名表 P1；抽取失败隔离 |
| **版本漂移** | README 0.13.0 vs 本地 0.14.0；README v1.8.11 是应用版本；0.13.0 行为未验证；0.14.0 prompt 允许多 event | B8 前做 0.13/0.14 diff（若拿得到 wheel）；以论文/PRD 语义为准；升级走独立 PR + 参考 diff（UPSTREAM_VERSIONS 规则） |
| **评测集构建** | 当前 262 条单证据、天花板；多跳标注成本高；LLM 生成标签可能偏差 | 人工校验 chunk 级 evidence IDs；先小规模 120 条；保留 262 回归；不把论文英文集直接翻译当门禁 |
| **许可证/参考素材** | SAG 应用 MIT；`docs/reference/zleap-sag-0.14.0/` 只读；prompt 可参考但不应复制受版权限制内容 | 保留 LICENSE/THIRD_PARTY_NOTICES；中文改写 prompt；参考目录不修改、不编译；升级按 README 的 diff 流程 |
| **技术** | v6 迁移风险；FTS4 非 BM25；SQL 候选爆炸；10k event 图谱内存；备份体积；模型下载网络 | additive 迁移 + 备份先行；预算/分页/上限；流式矩阵；分页导出；SHA/断点/磁盘预检 |
| **范围蔓延** | README 的 3D/Explore/MCP/桌面/账号很吸引人，但不是 P0；容易挤占 B8–B12 | 按 PRD §3 分层：P0=FR-01..11；P1/P2 触发式；发布说明区分“已交付/实验” |
| **文档一致性** | 0.14.0 prompt 的多 event/children 语义与论文/PRD 的 1:1 有偏差；B1/W3 的 R@5 口径不同（.991 vs .9955） | B8 前按论文/PRD 决策提示词语义；引用数字时注明模型/时点；PRD/W3 的 15.93s/262 构成已在 2026-10-18 定稿中修正 |

---

## 10. 附录

### A. 证据索引（关键文件:行号）

**当前代码（前缀 `app/src/main/java/com/zhibox/memory/`）**

| 证据 | 证明什么 |
|---|---|
| `data/db/Entities.kt:65-73` | chunk 表：content/charStart/charEnd/tokenEstimate |
| `data/db/Entities.kt:82-88` | FTS4 `chunk_fts` + bigram |
| `data/db/Entities.kt:110-135` | `chunk_embedding` + role；仅 content 角色 |
| `data/db/Entities.kt:137-212` | event/entity/event_entity/chunk_entity 四表占位；注释“MVP 建表不写入” |
| `data/db/AppDatabase.kt:12-35` | 13 个 entity、version=5、7 个 DAO（无 event/entity DAO） |
| `data/db/Daos.kt:21,90,150,234,364` | 仅 Source/Document/Chunk/Embedding/IngestJob 等 DAO |
| `core/search/SearchService.kt:80` | `search(query, topK=10)`：无 mode/source scope |
| `core/search/SearchService.kt:51-65,137-169` | SearchHit 证据字段 + 批量回填 |
| `core/search/SearchService.kt:186-246` | chunk 向量扫描 + FTS4 关键词 |
| `core/search/SearchService.kt:411,437-463` | 候选×3、查询上限 512、FTS 5000 上限 |
| `core/search/SearchService.kt:523-551` | RRF K=60 |
| `core/text/DocumentParser.kt:5-23` | 仅 TXT/MD UTF-8；PDF/Office 非目标 |
| `core/text/TextNormalizer.kt:7-13` | NFC/CRLF，无 Markdown 结构 |
| `core/text/Chunker.kt:17-28` | 600 字符上限 + 1024 token 引擎限制说明 |
| `core/ingest/IngestRepository.kt:79-105` | 导入编排/状态机/发布语义 |
| `core/ingest/IngestRepository.kt:334-420` | processDocument：解析→分块→embedding→入库 |
| `core/ingest/IngestRepository.kt:630-648` | chunk + FTS 写入 |
| `core/ingest/IngestRepository.kt:760-788,871` | FTS 重建/健康检查/修复 |
| `core/backup/KnowledgeBackup.kt:37-40` | 备份 v2 包结构 |
| `core/backup/BackupValidator.kt:29-30` | FORMAT_VERSION=2 |
| `ui/EvidenceScreen.kt:24-41` | 原文/位置/来源/通道展示 |
| `MainActivity.kt:93-112` | 仅 home/import/search/sources/settings（+debug benchmark） |
| `core/model/ModelCatalog.kt:53-109` | 5 条 EGemma2 模型 |
| `app/src/main/AndroidManifest.xml:9,17-22` | 仅 INTERNET；allowBackup=false；禁明文/白名单 |

**项目文档/评测**

| 证据 | 证明什么 |
|---|---|
| `docs/phase1/MVP1-ACCEPTANCE.md:3-4,14-19,31-38,44-48` | Phase 1 十项 8 过 2 部分；262 条；无 SAG 对照；发布物 |
| `docs/phase1/W3-ROBUSTNESS-EVAL.md:77-82,141-147,205-218` | 262 条构成/结果；三端一致；评测偏易 |
| `docs/review/B1_FIX_REPORT.md:20-24` | schema v5/8 FK/迁移回归 |
| `docs/review/B4_FIX_REPORT.md` §1/§3 | 235 tests/CI 首绿/签名发布 |
| `docs/review/B7_FIX_REPORT.md` §1/§3.4 | 243 tests/35 instrumentation/旧空间/备份兼容 |
| `docs/UPSTREAM_VERSIONS.md:12-29` | SAG 应用/论文锚点（含 0.13.0 vs 0.14.0 漂移）与 0.14.0 wheel/commit/SHA |
| `docs/product/PRD_ZhiBox_SAG_App_v1.0.md:62-66,91-181,192-199,343-347,384-394` | P0/P1/P2、FR-01..11、schema v6、B8–B12、可追溯矩阵 |
| `docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md:37-42,48-62,67-81,85-100,104-119` | B8–B12 范围/门禁/顺序 |
| `scripts/eval/evalset.json:20-27,2278` | 262 queries、50 docs；multihop 样例只有 1 relevant |

**参考源码（只读 `docs/reference/zleap-sag-0.14.0/`）**

| 证据 | 证明什么 |
|---|---|
| `zleap/sag/db/models.py:639-742` | `EntityType` 可配置类型（scope/weight/threshold） |
| `zleap/sag/db/models.py:744-905` | `Entity`（normalized_name/唯一约束）+ `EventEntity`（weight/description） |
| `zleap/sag/db/models.py:909-1060` | `SourceEvent`（chunk_id/source_type/generation） |
| `zleap/sag/core/storage/index_schemas.py:48-105` | 4 张索引：entity_vectors/event_vectors_wide/event_entity_vectors/source_chunks |
| `zleap/sag/pipeline/options.py:43-52` | ChunkOptions：800 tokens/overlap 0/heading 策略 |
| `zleap/sag/pipeline/options.py:225-231` | 检索策略枚举（vector/full_expand/pruned_*） |
| `zleap/sag/pipeline/options.py:269-310` | GraphSearch/Ranking 选项（hops/阈值/LLM 选择） |
| `zleap/sag/pipeline/options.py:345-396` | SearchOptions：strategy/top_k/return_type/fallback |
| `zleap/sag/prompts/extract_document.yaml:29,62,66` | 0.14.0 允许多事项/children/层级 |
| `zleap/sag/prompts/rerank.yaml:1-15` | Qwen3-Reranker 模板（pointwise 对照） |
| `sag-api/octx_vector_protocol.py:16-48` | 六向量角色 + 存储位置 |

**论文锚点**

- §3.1/§3.2/Appendix A：1 event/chunk、11 类、incidence、潜在超边；
- §3.3/式(1)(2)：Path A τ_ent=0.9、Path B τ_evt=0.4；
- §3.4/式(3)(4)(5)：扩展、Kcand、Kevent≤5、Kout=10、双路径；
- §4.2：L=1、Kseed=50、frontier=50、Kcand=100；
- §5.1/Table 1：主结果与 +11.52/+15.23/+7.01 口径；
- §5.2/Table 6/7：−10.95/−13.25/−2.75、Kevent=0、L2–4 0.35 内、Kcand 50→100；
- §5.3：别名不消解、append-only 时效；
- Appendix B：>93% giant component、平均度 2.61–3.71；
- Appendix G/Table 8：4.57h/28.26M、15.93s@50、18.67s@100、token/延迟代价；
- Appendix I：单次 LLM/单 event/11 类/不做全局消歧；
- Appendix K：NarrativeQA EM 12.29/F1 31.90 vs 6.48/22.86；
- Appendix L/Table 12：SAG 只喂 top-5 原 chunk。

### B. 术语表

| 术语 | 含义 |
|---|---|
| event | 一个 chunk 的语义完整事实陈述；论文中 1 chunk=1 event，检索在 event 文本上，证据仍回原 chunk |
| entity | 用于索引/扩展的实体；论文 11 类：time/location/person/organization/group/subject/work/product/action/metric/tags |
| latent hyperedge | 一个 event 与其全部 entities 构成的超边；不物化，只在查询时通过 incidence join 激活 |
| incidence | `(event, entity)` 关系行；按 event 分组可还原实体集合 |
| seed | 在线检索的初始事件集合 `R = R_ent ∪ R_evt` |
| frontier | 扩展时尚未访问的实体集合，按预算裁剪（论文 50） |
| `Kseed`/`Kcand`/`Kevent`/`Kout` | seed 事件预算/候选事件预算/LLM 选择上限/最终输出 chunk 数 |
| 双路径输出 | 结构路径（event→chunk）≤5 + 语义路径补足到 10；两路去重 |
| contextual selection | LLM 联合看候选事件并选择互补证据链，不是逐条 pointwise rerank |
| FTS/BM25 | 全文检索/词法相关性；当前是 FTS4 bigram + 覆盖分，不是 BM25 |
| RRF | Reciprocal Rank Fusion；当前 K=60，对向量/关键词名次融合 |
| `spaceId`/`role` | 向量空间指纹（provider/model/revision/dim/template/accelerator/quantization）与向量角色（content/heading/event/entity 等） |
| `source scope` | 限定检索的信源集合；SAG 支持 `@`/`source_id`，当前无 |
| `NOT_INDEXED` | PRD 计划中的抽取状态：未抽取；当前代码无此状态 |
| `multi_es_fast`/`multi` | 应用层两种检索模式；引擎分别映射 `pruned_expand_rff`/`full_expand` |

### C. 来源版本表

| 来源 | 版本/标识 | 日期 | 校验/说明 |
|---|---|---|---|
| README-CN | repo `a6f6a00f36ce0cbe43cd2fc0445b479e4ae4fff6`；blob `6b0df82e0a90457dc8f83efbd258b20d0b2b4596`；33,492 B；badge SAG v1.8.11；pin `zleap-sag==0.13.0` | 2026-10-16T11:05:11Z | GitHub API + raw 核对；本次分析基准 |
| 论文 | arXiv:2606.15971v2 | 2026-08-25 | 本地抓取 5,038 行；Table 1/6/7/8、Appendix A/B/G/I/K/L |
| 参考引擎 | `zleap-sag==0.14.0`；wheel SHA-256 `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429`；源 commit `56f073c1c0c300b46707c7249d48732730656cb0` | 2026-10-09（commit）/2026-10-15（提取） | 只读；0.13.0 未获取 |
| 当前代码 | HEAD `0003d76dc3ac1914afe72d864b376d3cf9b4d6cd`；`versionName=0.1.0-mvp`；schema v5 | 2026-10-18 00:58 | Phase 1 + B1–B7 |
| 产品规划 | PRD v1.0 已评审；ROADMAP v1.0 | 2026-10-18 | Lead 编写；本报告引用其计划内容 |
| 评测 | `scripts/eval/evalset.json` 262 queries / 50 docs | 当前工作区 | 本报告统计：220 正例各 1 relevant；42 adversarial 0 |

### D. 未验证/待确认清单

1. **0.13.0 行为差异**：README 锁 `zleap-sag==0.13.0`，本地只有 0.14.0；未获取 0.13.0 wheel，未做 diff；“README 行为 = 0.14.0 行为”**未验证**。
2. **SAG 应用运行态**：README 的 16 候选信源、MinerU/AnyDoc、3D 图谱、Explore、MCP/Dify/dsh-sag 等未实际运行验证；本报告只按 README 文本归纳。
3. **论文数字复现**：未搭服务端 SAG 环境，未复现 Table 1/6/8；论文数字仅作对照，不代表端侧可达。
4. **PRD/ROADMAP 定稿**：PRD 已于 2026-10-18 评审为 v1.0，ROADMAP 为 v1.0；FR 编号/门禁可能随后续批次微调；本报告引用不构成发布承诺。
5. **中文抽取质量**：11 类实体在 600 字符中文 chunk 上的类型准确率/覆盖率/忠实性未测；B9 抽检前不能假设可达论文英文 prompt 的效果。
6. **端侧延迟/成本**：远程 LLM 首 token/总时长、SQL join 10k events、图谱 10k 节点内存均未实现/未测；PRD §9.1 门禁需要 B10/B11 实测。
7. **0.14.0 prompt 的多 event/children 语义**：与论文/PRD 1:1 冲突；B9 采用哪套语义**待 Lead 决策**；本报告建议按论文/PRD。
8. **文档数字一致性**：PRD §15.1 的 15.93s 归因与 `W3:77` 的 262 构成式已在 2026-10-18 并行定稿中修正；仍待确认的是 B1/W3 的 R@5 口径差异（不同模型/时点），正文已注明，不修改原文。
9. **“B1 262 条 R@5=.991” vs “W3 262 条 R@5=.9955”**：模型/基线/时点不同，未逐项对齐；引用时需注明口径。
10. **README 的“应用要求 0.13.0”**：仅来自 README 文本；未拉取 `a6f6a00` 的 `apps/api/pyproject.toml` 交叉验证（GitHub API 只核对了 commit/blob/README 内容）。

### E. 矩阵统计复核

- 能力矩阵行数：28；
- ✅：3（行 8、27、28）；
- 🟡：9（行 1、7、9、15、18、19、21、24、25）；
- ❌：15（行 2、4、5、6、10、11、12、13、14、16、17、20、22、23、26）；
- ⚠️：1（行 3）；
- 合计：3+9+15+1 = 28。

**一句话收尾**：当前 ZhiBox 已经把“chunk 检索 + 证据 + 端侧模型 + 备份”这段路修得比较稳；但 SAG 的“event-entity 结构 + 查询时动态超边”这段路还没有开工。B8–B12 的价值不是“再做一个 RAG”，而是把现有 chunk 证据层升级成可解释、可扩展、可多跳的 SAG 结构；建议按 PRD/ROADMAP 的批次和门禁推进，并把本报告第 5 节的 15 个 ❌ 作为 B8–B12 的验收清单。
