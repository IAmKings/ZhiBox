# W2 检索服务验收记录（2026-10-16）

> 范围：实施计划 P1-W2-2/3/4——VectorIndex 精确扫描、关键词检索、候选融合与去重、
> 证据回溯、五屏 UI；本轮追加：三路径引擎统一运行时（EmbeddingRuntime）、
> 无头检索验收入口（W3 评测地基）。

## 交付物

| 组件 | 文件 | 说明 |
|---|---|---|
| 引擎统一运行时 | `core/embedding/EmbeddingRuntime.kt` | 进程级单例 Provider 唯一持有者；导入/检索共用互斥串行；三路径路由与空间指纹收敛为单一事实源（原散在 IngestRepository） |
| 检索服务 | `core/search/SearchService.kt` | 向量精确扫描（G4 选型 PRELOADED 展平矩阵）+ FTS4 bigram 关键词（OR 召回+覆盖数重排）+ RRF 融合（K=60）+ chunk 级去重 + 证据字段（charStart/End 回溯）+ 空间不匹配拒绝 |
| 候选融合 | `CandidateFusion`（同文件） | 纯函数，4 条单测（双通道加分/名次保留/截断稳定排序/空输入） |
| 五屏 UI | `ui/SearchScreen` `EvidenceScreen` `SourcesScreen` `SettingsScreen`（+已有 ImportScreen） | 首页五入口；检索卡片→证据详情（原文+定位+回溯链）；设置页含三路径路由说明/空间注册表/隐私离线声明 |
| 无头验收 | `benchmark/SearchEvalRunner.kt` + `scripts/run_search_eval.sh` | queries.json → 逐条检索落盘 JSON；W3 200 条评测集直接复用 |
| DAO 扩展 | `Daos.kt` | `vectorsForSpace`/`countBySpace`/`allMeta`/`searchFtsOrdered` |

## K40 验收结果（§9.1-6/8）

**检索（8 问句，库=3 文档 × 1 块，路径 C/.task）**：

- 3 条可答问句全部 **Top1 = 向量+关键词双通道融合命中**：
  - "加速度和位移是什么关系" → iso_1（二阶导数）· 定位 0-198
  - "古代东西方贸易通道" → iso_2（丝绸之路）· 0-210
  - "Java 内存怎么回收" → iso_3（垃圾回收）· 0-240
- 5 条不可答问句（库中无对应内容）：关键词候选正确为 0，向量通道给出小库噪声命中（预期）
- 单查延迟 1.4-1.8s（.task 查询嵌入 ~1.4s 占绝对大头；换路径 A/B 查询侧约 60-160ms）

**拒绝路径（§9.1-3/7）**：仅 granite 在位（其空间无向量，已有向量在空间 C）→ 2/2 拒绝，
原因含当前模型、空间 ID 与已有空间清单（引导切换）。

## 过程发现与修复

1. **关键词 AND 语义缺陷（真 bug，验收捕获）**：G3 选定的 bigram AND 匹配对自然语言问句过严
   （问句 10+ bigram，个别跨词 bigram 缺失即全空——实测 8 问句关键词候选全 0）。
   修复：`BigramTokenizer.toOrMatchQuery`（OR 召回）+ Kotlin 侧按 bigram 覆盖数重排
   （FTS4 无 bm25）。G3 结论（bigram 预分词）不变，仅 MATCH 语义修正。
2. **suspend 陷阱**：suspend DAO 调用不能置于 filter/map 等非 suspend lambda（编译期捕获）。
3. tasks-text 线程复核（W2 遗留项）结论：`TextEmbedderOptions`/`BaseOptions`（0.10.35）均无
   线程数旋钮，恒定延迟无法通过配置消除——.task 固定窗口构建是根因，转 W9 触发条件（换 seq 短窗构建）。

## 未尽事项（转 W3）

- 设置页只读（三路径切换 = 增删模型文件）；应用内切换 UI + 模型下载管理 → W3
- 五屏 UI 的人工走查（本轮验收走无头通道）；来源删除级联（§9.1-5）→ W3
- 200 条评测集（SearchEvalRunner 已就绪，直接承接）
