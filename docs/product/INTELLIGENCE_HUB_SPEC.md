# 智能状态中枢（Intelligence Hub）设计规格

> 状态：**草案 v0.1（待评审）** ｜ 范围：Android 首页头部 + 设置页 ｜ 方案：**B（状态中枢 + 深链设置）**
> 关联文档：`docs/product/UI_MODULE_FEATURE_INVENTORY.md`、`DESIGN.md`、`docs/product/PRD_ZhiBox_SAG_App_v1.0.md`
> 本文只描述设计与交互契约，**不含实现**；代码改动另开任务。

---

## 0. 一句话结论

把首页左上角那个"永远绿、永远闪"的装饰性信号点，改造成**会说实话的智能状态入口**：点开是一个只读的「智能状态」面板（状态 + 快捷动作 + 待处理 + 深链），而**所有配置项仍然只存在于设置页**（新增「智能」分组作为唯一配置源）。

---

## 1. 背景与结论

### 1.1 现状事实（代码级核对，非推测）

| # | 事实 | 证据 | 影响 |
|---|---|---|---|
| F1 | 绿点颜色/文案是**硬编码**的，不读任何真实状态 | `HomeScreen.kt` `OfflineReadyDot()`：颜色固定 `colorScheme.secondary`，`contentDescription = home_status_offline_ready`（"端侧离线运行就绪"） | 没模型、索引坏、LLM 未配，仍然是"就绪"——**信号不可信** |
| F2 | ping 动画为无限循环（1800ms，scale 1→2.4，alpha 0.45→0），无减少动画处理 | 同上 `rememberInfiniteTransition` | 对前庭/光敏感用户不友好；"一直闪"也暗示"一直在算" |
| F3 | 命中区只有 16dp 容器 / 10dp 圆点，且不可点击 | `Modifier.size(16.dp)`，无 clickable | 作为入口不达标（需 ≥48dp） |
| F4 | 首页不知道"有没有模型" | `HomeViewModel` 只聚合文档/来源/首块摘要，无模型状态 | 新装无模型时，空态仍引导「去导入」→ 必然失败（`ImportScreen` 才提示缺模型） |
| F5 | 设置页是**单个 `Column(verticalScroll)`**，17 个 section 顺序排列 | `SettingsScreen.kt:420`、各 section 标题行号见附录 B | 深链需要"锚点"方案（见 §7.2） |
| F6 | GPU 的**真实可用性事前不可知** | `EmbeddingRuntime` 在 auto 模式首次推理时 `probeGpuWithTimeout` | 面板只能显示"档位偏好"，不能显示"GPU 可用" |
| F7 | LLM 的**可达性事前不可知** | `LlmConfigUi` 只有 baseUrl/model/timeout/maskedKey；连接性靠"测试连接" | 只能区分"已配置/未配置" |
| F8 | UI 层没有设置页相关测试 | `app/src/test/java/com/zhibox/memory/ui/` 无 settings 测试 | 重构无测试网，需先补聚合层单测 |

### 1.2 方案 B 定义

```
首页头部 [●]  ──点击(≥48dp)──▶  「智能状态」bottom sheet
                                   ├─ ① 状态卡：一句话结论 + 4 个状态 pill
                                   ├─ ② 快捷动作：模型 / 嵌入路径 / GPU / LLM
                                   ├─ ③ 待处理：旧空间 / 抽取失败 / 索引不一致（有才显示）
                                   └─ ④ 打开设置 · 智能  ──▶ 设置页「智能」分组（唯一写入口）
```

**分工原则**：面板**读多写少**（状态 + 1 击快捷动作 + 深链），设置页**承担所有配置表单**。

### 1.3 目标与非目标

**目标**
1. 绿点说真话：4 态（就绪 / 部分就绪 / 未就绪 / 异常），颜色 + 动效 + 文案一致。
2. 用户能在首页 1 击看到"能不能用、哪里没配好、下一步点什么"。
3. 新装无模型时，给出正确的下一步（下载/选择模型），不再误导去导入。
4. 无障碍达标：可点击语义、状态播报、减少动画、非纯色。

**非目标（本规格明确不做）**
- ❌ 不把 4 组配置**迁移/复制**到面板（避免两套配置 UI 漂移；LLM 表单在 sheet 里不可用）。
- ❌ 不删除设置页任何现有功能（只重组分组 + 加锚点）。
- ❌ 不新增第三方依赖（bottom sheet 用现有 material3）。
- ❌ 不做"AI 健康度评分""推理耗时监控看板"等新能力。
- ❌ 不改动备份/迁移/隐私声明等非智能项。

### 1.4 术语

| 术语 | 含义 |
|---|---|
| L1 核心能力 | 嵌入模型 + 向量空间 + 索引，缺失则导入/检索/问答全不可用 |
| 状态点 | 首页左上角信号点（本规格的入口） |
| 状态中枢 | 点击状态点打开的面板 |
| 待处理 | 不阻塞主流程、但需要用户处理的数据告警（旧空间/抽取失败/索引不一致） |
| 不可知项 | 事前无法判定、只能显示偏好或"未探测"的状态（GPU 可用性、LLM 可达性） |

---

## 2. 能力分层模型

| 层 | 包含 | 判定源 | 缺失后果 | 是否影响状态点颜色 |
|---|---|---|---|---|
| **L1 核心** | 嵌入模型、向量空间、索引 | `EmbeddingRuntime.findModel()`、`spaces`、`indexHealth` | 导入失败、检索拒绝、问答不可用 | ✅ 决定灰/绿 |
| **L2 加速** | GPU 档位（auto/off/on） | `PathPreferences.gpuMode()` | 仅影响速度 | ❌ 不进颜色，只进 pill |
| **L3 增强** | LLM 配置、SAG 增强、问答生成、联网同意、禁网 | `LlmPreferences`/`SettingsUiState` | 问答与图谱增强不可用 | ❌ 不进颜色，只进 pill/待处理 |
| **L4 维护** | 旧模型空间、抽取失败、索引不一致、重嵌入未完成 | `legacySpaces`、`extractionCounts`、`indexHealth`、`reembed*` | 数据能看但质量下降 | ✅ 决定"部分就绪"（琥珀角标） |

**关键原则**：颜色只表达 **L1 是否可用 + L4 是否有告警**；L2/L3 用 pill 与行内状态表达，**绝不用灰色"惩罚"可选功能**。

---

## 3. 状态矩阵

### 3.1 四态判定（自上而下短路，先命中者生效）

```
if (L1 缺失)       -> NOT_READY      // model == null
else if (L1 异常)  -> ERROR          // model 存在但模型损坏 / 空间不匹配 / 元数据不可读
else if (L4 告警)  -> PARTIAL        // 旧空间 / 抽取失败 / 索引不一致 / 目录授权丢失 / LLM 读取失败
else               -> READY
// 注意：LLM 密钥读取失败属于 L3 待处理项（E7），只让点变 PARTIAL，不判 L1 异常
// 繁忙（下载中 / 抽取中 / 重嵌入中）不改变底色，只改变动效节奏
```

### 3.2 状态表（唯一事实源）

| 状态 | 判定条件 | 颜色 | 动效 | 一句话文案（点旁/面板首行） | 主 CTA | 角色语义 |
|---|---|---|---|---|---|---|
| **就绪** `READY` | L1 正常 且 L4 无告警 | 呼吸绿 `#2E7D32` | 慢呼吸 4s，scale 1→1.15，alpha 0.35→0.12 | 智能就绪 · 端侧 | 查看（无强制动作） | `role=Button`，`stateDescription="智能就绪"` |
| **部分就绪** `PARTIAL` | L1 正常 且 L4 有 ≥1 项告警 | 绿点 + 琥珀角标（数字 = 待处理项数，>9 显示 `9+`） | 慢呼吸（同就绪） | 就绪，{n} 项待处理 | 去处理 | `stateDescription="就绪，{n} 项待处理"` |
| **未就绪** `NOT_READY` | `model == null` | **灰** `inkMeta`（#736B63） | **静止，不闪** | 未配置模型 | 下载 / 选择模型 | `stateDescription="未就绪，未配置模型"` |
| **异常** `ERROR` | 模型损坏 / 空间不匹配 / 模型元数据不可读 | **琥珀** `#B45309`（warm amber，非红色） | 静止 | 需要修复 | 修复 | `stateDescription="异常，需要修复"` |

> 灰色取值说明：初稿写 `inkFaint`（#9E948A），对米底 `#F7F5F0` 仅 2.4:1；状态点是纯图形，WCAG 非文本对比要求 ≥3:1，故实现改为 `inkMeta`（4.8:1）。
>
> 为什么不给 ERROR 用红色：这是"可修复的配置问题"，不是危险/错误操作；红色会引发不必要恐慌，且与删除类危险操作撞语义。**错误色仅用于破坏性操作确认**。

### 3.3 优先级

`ERROR` 与 `NOT_READY` 互斥（前者 L1 存在但坏了，后者 L1 缺失）；其余按 `PARTIAL > READY` 取更严重者。

### 3.4 动效规则

| 条件 | 动效 |
|---|---|
| 状态 = READY/PARTIAL，且**无进行中任务** | 慢呼吸：周期 4000ms，`scale 1→1.15`，`alpha 0.35→0.12`（比现状 1800ms/2.4x 更克制） |
| 状态 = READY/PARTIAL，且**有进行中任务**（下载/抽取/重嵌入） | 快脉冲：周期 1400ms，`scale 1→1.6`，`alpha 0.45→0` |
| 状态 = NOT_READY / ERROR | **完全静止**（无动画） |
| 系统"减少动画"开启（`ANIMATOR_DURATION_SCALE == 0`） | 全部静止，仅保留颜色与角标 |

**实现约束**：所有动画必须读系统动画缩放，不允许无条件 `infiniteRepeatable`。

### 3.5 无障碍

- 状态点必须是**可点击语义节点**：`role = Button`，`onClickLabel = "打开智能状态"`。
- `stateDescription` 使用 §3.2 的文本；禁用时（如面板已打开）`disabled()`。
- 命中区 ≥ **48×48dp**，不得依赖 10dp 视觉尺寸。
- **不得只靠颜色**传达状态：角标数字 + 面板文案 + TalkBack 播报三重冗余。
- 焦点顺序：状态点 → 品牌标题（不可聚焦）→ 搜索 → 更多。

---

## 4. 空态与异常矩阵

> 回答"全部为空是否灰色"：**只有 L1 缺失才灰**；L2/L3 为空用中性"未启用"，L4 用琥珀待处理。

| # | 场景 | 判定 | 状态点 | 面板首屏（关键行） | 文案 key | 主 CTA |
|---|---|---|---|---|---|---|
| E1 | 全新安装，全空 | `model==null && models.isEmpty()` | 灰·静止 | ①「还没有可用的本地模型」②「下载模型后即可导入与检索」 | `intelligence_empty_title` / `_message` | **下载模型**（跳模型商店） |
| E2 | 无模型但已有模型文件 | `model==null && models.isNotEmpty()` | 灰·静止 | 「已检测到 {n} 个模型文件，尚未启用」 | `intelligence_models_found_not_enabled` | 去启用 / 选择嵌入路径 |
| E3 | 有模型，库为空 | `model!=null && chunkCount==0` | 绿（READY） | 「模型就绪 · 知识库还是空的」 | `intelligence_ready_empty_kb` | 去导入 |
| E4 | 模型商店未配置 | `storeState==NOT_CONFIGURED` | 灰（若同时 E1）；否则绿 + pill 提示 | 「模型存于应用私有目录，卸载即删」 | `settings_store_not_configured`（复用） | 选择共享目录 |
| E5 | 共享目录授权丢失 | `storeState==PERMISSION_LOST` | 绿 + 琥珀角标 +1 | 「共享目录授权失效，重选一次即可恢复」 | `settings_store_permission_lost`（复用） | 重选目录 |
| E6 | LLM 未配置 | `llm==null` | **不灰**（绿/灰由 L1 决定），pill 显示「LLM 未启用」 | 「问答未启用（可选）」 | `settings_llm_disabled_hint`（复用） | 配置 LLM |
| E7 | LLM 密钥解密失败 | `llmError!=null` | 绿 + 琥珀角标 +1 | 「LLM 配置读取失败，请重新保存密钥」 | `settings_llm_load_failed`（复用） | 去设置 |
| E8 | SAG 增强关闭 / 禁网开启 | `!sagEnabled \|\| networkDisabled` | 不变 | pill：「SAG 未启用」/「网络已禁用」 | `settings_sag_unavailable`（复用） | 去设置 |
| E9 | 旧模型空间存在 | `legacySpaces.isNotEmpty()` | 绿 + 琥珀角标 +1 | 「{n} 个旧模型空间的数据不参与当前检索」 | `settings_legacy_spaces_warning`（复用） | 去来源页重导 |
| E10 | 抽取失败 | `extractionCounts.failed > 0` | 绿 + 琥珀角标 +1 | 「{n} 个分块抽取失败」 | `settings_sag_retry_failed`（复用） | 重试（面板内） |
| E11 | 索引不一致 | `indexHealth != null && !healthy` | 绿 + 琥珀角标 +1 | 「索引不一致：FTS 孤儿 {a} / 向量孤儿 {b}」 | `settings_index_unhealthy`（复用） | 检查并修复（面板内） |
| E12 | 模型下载中 | `downloading==true` | 绿·快脉冲 | 「正在下载 {name} · {pct}%」+ 进度条 | `settings_downloading`（复用） | 无（禁用其他行） |
| E13 | 重嵌入同步中 | `reembedDone < reembedTotal` | 绿·快脉冲 | 「正在同步旧数据 {done}/{total}」 | `settings_reembed_progress`（复用） | 停止同步（面板内） |
| E14 | 首次联网同意未授予 | `!answerNetworkConsent` | 不变 | pill：「问答首次联网未同意」 | `settings_answer_consent_required`（复用） | 去设置 |
| E15 | 模型文件损坏 | 推理探测失败（导入/检索报错后回写） | 琥珀·静止 | 「模型文件需要重新下载」 | 新增 `intelligence_model_corrupt` | 重新下载 |

**空态组合判定顺序**：`E1 > E2 > E15 > E12 > E9/E10/E11 > E3 > 正常`。

---

## 5. 入口设计

### 5.1 布局与尺寸

| 项 | 规格 |
|---|---|
| 位置 | 首页顶栏左侧（现绿点位置），距屏幕左 20dp，与品牌锁版垂直居中 |
| 视觉 | 10dp 实心圆 + 可选 ping 光晕（总视觉 ≤ 24dp） |
| 命中区 | **48×48dp**（`Modifier.size(48.dp)` + 内容居中，视觉不变） |
| 角标 | 直径 14dp 琥珀圆，数字 9sp，右上游标偏移 (6dp, -2dp)；`>9` 显示 `9+` |
| 点击 | 打开状态中枢；无长按、无双击 |
| 禁用 | 面板打开期间不重复响应（防连点） |

### 5.2 三种入口形态（开放问题 Q1）

```
A) 裸点（推荐，改动最小）        B) 点 + 文案 chip                 C) 图标按钮
   ┌──────────────────┐          ┌──────────────────────┐        ┌──────────────┐
   │ ●     智匣        │          │ ● 智能就绪   智匣      │        │ [◉]   智匣   │
   │       ZHIBOX     │          │              ZHIBOX  │        │      ZHIBOX  │
   └──────────────────┘          └──────────────────────┘        └──────────────┘
   发现性低，保持现设计            发现性最好，占用顶栏宽度         像"设置"入口，语义偏移
```

- **默认 A**：保持现有视觉节奏（设计稿是裸点），靠"状态色 + 角标 + 首次引导"提升可发现性。
- 若评审认为发现性不足，升级 B（代价：顶栏需要重新对齐，品牌锁版左移）。
- C 仅作为"更多"菜单里的第二入口补充，不替换裸点。

### 5.3 首次可发现性（必做，否则入口形同虚设）

- 首次进入首页且状态为 `NOT_READY` 时，除灰点外，在时间线区域顶部显示一条**一次性引导条**（可关闭，`rememberSaveable` + 持久化标记）：
  「本地 AI 尚未就绪 · 去下载模型」→ 点击进入面板。
- 用户完成 L1 配置后，引导条永久不再出现（`app_kv` 持久化 `intelligence_onboarded`）。

---

## 6. 状态中枢（Bottom Sheet）信息架构

### 6.1 容器规范

| 项 | 规格 |
|---|---|
| 组件 | `ModalBottomSheet`（material3，需 `@OptIn(ExperimentalMaterial3Api::class)`） |
| 高度 | 内容自适应，最大 **屏高 70%**；超出内部滚动 |
| 圆角 | 顶部 28dp（与 `ZhiBoxRadius.omniBar` 一致） |
| 底色 | `zhiBoxColors.card`（浅色白 / 深色 #1B1A18），顶部加 `border` 发丝线 |
| 拖拽 | 支持下滑关闭；`skipPartiallyExpanded = false`（先半展开再全展开） |
| 遮罩 | M3 默认 scrim，深色下 60% |
| 沉浸 | 面板内 `navigationBarsPadding()`；键盘不弹出（面板无输入框） |

### 6.2 首屏"三问"原则

面板首屏（不滚动）必须能回答：

1. **能不能用？** → 状态卡一句话结论
2. **哪里没配好？** → 4 个 pill（模型 / 向量空间 / GPU / LLM）
3. **下一步点什么？** → 首个可用快捷动作高亮（如"下载模型"）

### 6.3 分区与线框

```
┌───────────────────────────────────────────┐  ← 顶部 28dp 圆角
│  ▁▁▁▁                                     │  拖拽把手
│  智能状态                          [关闭]  │  标题 18sp/600
│  本地模型已就绪 · 端侧推理                 │  结论 13sp/inkSecondary
│                                           │
│  ┌────────┐┌────────┐┌────────┐┌────────┐ │  ① 状态 pill（横向可滚）
│  │ 模型 ✓ ││ 空间 2 ││ GPU 自动││ LLM 未启用│ │
│  └────────┘└────────┘└────────┘└────────┘ │
│                                           │
│  快捷动作                                  │  ② 分组标题 13sp/500/inkMeta
│  ┌───────────────────────────────────────┐│
│  │ 嵌入模型    EGemma2 · 440M        ›   ││  行高 56dp，右侧 chevron
│  ├───────────────────────────────────────┤│
│  │ 嵌入路径    自动（440M 优先）       ›   ││
│  ├───────────────────────────────────────┤│
│  │ GPU 加速    自动 · 首次推理时探测    ›   ││
│  ├───────────────────────────────────────┤│
│  │ LLM 配置    未启用（可选）          ›   ││
│  └───────────────────────────────────────┘│
│                                           │
│  待处理（2）                               │  ③ 无告警时整区隐藏
│  ┌───────────────────────────────────────┐│
│  │ 3 个旧模型空间的数据未参与检索   去处理 ›││
│  │ 12 个分块抽取失败              重试   ›││
│  └───────────────────────────────────────┘│
│                                           │
│  ┌───────────────────────────────────────┐│  ④ 底部主按钮（可选，视状态）
│  │           打开设置 · 智能              ││
│  └───────────────────────────────────────┘│
└───────────────────────────────────────────┘
```

### 6.4 分区规则

| 区 | 内容 | 何时隐藏 | 交互 |
|---|---|---|---|
| ① 状态卡 | 标题 + 一句话结论 + 4 个 pill | 永不隐藏 | pill 只读，超长横向滚动 |
| ② 快捷动作 | 4 行固定：嵌入模型 / 嵌入路径 / GPU / LLM | 永不隐藏 | 点击 → 关闭面板 + 跳设置对应锚点 |
| ③ 待处理 | 旧空间 / 抽取失败 / 索引不一致 / 授权丢失 | 无告警时整区隐藏 | 行内动作直接执行（重试/修复/重选目录），其余跳转 |
| ④ 底部 | 「打开设置 · 智能」 | 面板高度不足时可省略（保证首屏三问） | 跳设置「智能」分组顶部 |

**禁令**：面板内**不放任何输入框/表单/开关组**；需要输入（LLM Key、目录选择）一律跳设置。允许的"就地写"只有幂等动作：重试抽取、修复索引、停止同步。

### 6.5 深链映射

| 面板行 | 目标设置锚点 | 跳转后行为 |
|---|---|---|
| 嵌入模型 | `Section.Embedding` | 滚动到「嵌入路径」，高亮 1.2s |
| 嵌入路径 | `Section.Embedding` | 同上 |
| GPU 加速 | `Section.Gpu` | 滚动到「GPU 加速」 |
| LLM 配置 | `Section.Llm` | 滚动到「SAG 增强 / LLM」 |
| 旧模型空间 | `Section.Legacy`(来源页入口) → 推荐跳来源页 | 直接打开来源屏（可删除/重导） |
| 抽取失败 | 面板内重试；「详情」→ `Section.Sag` | — |
| 索引不一致 | 面板内修复；「详情」→ `Section.Diagnostics` | — |
| 打开设置 · 智能 | 设置页顶部（智能分组） | — |

### 6.6 状态更新

- 面板打开时订阅 `IntelligenceStatus` Flow（`WhileSubscribed(5s)`），下载/抽取进度实时刷新。
- 数据源读取失败时，**该 pill 显示「未知」**，不影响其他区域（降级，不整屏报错）。

---

## 7. 设置页重组与深链

### 7.1 分组重组（只分组，不删功能）

现有 17 个 section 按语义归入 4 组（**顺序调整，功能不变**）：

| 分组 | 包含 section（现状行号） |
|---|---|
| **智能** | 嵌入路径(439)、模型商店(477)、GPU 加速(621)、模型文件(649)、向量空间注册表(687)、SAG 增强/LLM(1129)、问答首次联网隐私说明(1284)、检索预算档位(1332)、SAG 抽取状态(1384)、抽取并发度(1416) |
| **数据** | 知识库迁移(716)、旧数据同步(754)、知识库(1110) |
| **诊断** | 索引诊断(845)、检索/问答 trace(922)、抽取质量(974) |
| **隐私与关于** | 隐私与离线声明(1478) |

> 「智能」分组内再加二级小标题：**模型与算力** / **问答与增强** / **检索质量**，避免 10 项平铺。

### 7.2 锚点实现（两选一，推荐 A）

| 方案 | 做法 | 优点 | 风险 |
|---|---|---|---|
| **A（推荐）** | 保持单 `Column + verticalScroll`，各 section 用 `onGloballyPositioned` 记录 offset 到 `Map<Section, Int>`，深链时 `animateScrollTo(offset)` | 改动小（约 +30 行）、不动物理结构 | offset 依赖布局完成；需在数据加载后重算；字体缩放变化需刷新 |
| B | 改为 `LazyColumn` + `LazyListState.scrollToItem(index)` | 精准、性能更好 | 改动大（1600 行文件的滚动骨架），需要重新验证所有内联展开态 |

**契约**：`SettingsScreen(..., initialSection: Section? = null)`；进入后首帧不滚动，等首帧布局完成再滚动，避免闪跳。

---

## 8. 文案表

### 8.1 命名规范
- 新增 key 统一前缀 `intelligence_`（状态点/面板），设置页复用既有 `settings_*`（不改 key，避免翻译/脚本漂移）。
- 所有文案进 `values/strings.xml`（默认中文），禁止 composable 内字面量。

### 8.2 状态点（入口）

| key | 中文 | 场景 |
|---|---|---|
| `intelligence_status_ready` | 智能就绪 | READY 的 contentDescription |
| `intelligence_status_partial` | 就绪，%1$d 项待处理 | PARTIAL |
| `intelligence_status_not_ready` | 未配置模型 | NOT_READY |
| `intelligence_status_error` | 需要修复 | ERROR |
| `intelligence_open_hub` | 打开智能状态 | onClickLabel |

### 8.3 面板

| key | 中文 | 场景 |
|---|---|---|
| `intelligence_hub_title` | 智能状态 | 面板标题 |
| `intelligence_pill_model` | 模型 | pill 标签 |
| `intelligence_pill_space` | 空间 | pill 标签 |
| `intelligence_pill_gpu` | GPU | pill 标签 |
| `intelligence_pill_llm` | LLM | pill 标签 |
| `intelligence_pill_value_none` | 未配置 | pill 值（模型/LLM 空） |
| `intelligence_pill_value_unknown` | 未知 | 数据源读取失败 |
| `intelligence_pill_value_not_enabled` | 未启用 | L3 可选功能未开启 |
| `intelligence_pill_gpu_auto` | 自动（首次推理时探测） | GPU auto |
| `intelligence_section_quick` | 快捷动作 | 分组标题 |
| `intelligence_section_pending` | 待处理（%1$d） | 分组标题 |
| `intelligence_row_model` | 嵌入模型 | 行标题 |
| `intelligence_row_path` | 嵌入路径 | 行标题 |
| `intelligence_row_gpu` | GPU 加速 | 行标题 |
| `intelligence_row_llm` | LLM 配置 | 行标题 |
| `intelligence_open_settings` | 打开设置 · 智能 | 底部按钮 |

### 8.4 空态/异常（补充新增项）

| key | 中文 | 场景 |
|---|---|---|
| `intelligence_empty_title` | 还没有可用的本地模型 | E1 |
| `intelligence_empty_message` | 下载模型后即可导入文档并离线检索 | E1 |
| `intelligence_models_found_not_enabled` | 已检测到 %1$d 个模型文件，尚未启用 | E2 |
| `intelligence_ready_empty_kb` | 模型就绪 · 知识库还是空的 | E3 |
| `intelligence_model_corrupt` | 模型文件需要重新下载 | E15 |
| `intelligence_onboarding_banner` | 本地 AI 尚未就绪 · 去下载模型 | 5.3 引导条 |
| `intelligence_onboarding_dismiss` | 稍后再说 | 引导条关闭 |

### 8.5 文案基调

- 结论先行、一句话、可执行：`未配置模型` 优于 `嵌入模型加载失败，请检查配置`。
- 不用"错误/失败"吓人（可修复问题用"需要修复"）。
- 数字前置（`3 个旧模型空间…`），便于扫读。
- 可选能力一律带 `（可选）`，避免用户误判应用损坏。

---

## 9. 视觉规范

### 9.1 Token 映射（必须复用，不新增裸色值）

| 用途 | Token | 浅色 | 深色 |
|---|---|---|---|
| 面板底 | `zhiBoxColors.card` | `#FFFFFF` | `#1B1A18` |
| 页面底/遮罩外 | `zhiBoxColors.paper` | `#F7F5F0` | `#121212` |
| 分割线/边框 | `zhiBoxColors.border` / `divider` | `#E5E1DA` / `#E8E3DA` | `#37332E` |
| 主文字 | `zhiBoxColors.ink` | `#1C1A18` | `#EDEAE4` |
| 次文字 | `zhiBoxColors.inkSecondary` | `#736B63` | `#B8B2A9` |
| 弱文字 | `zhiBoxColors.inkMeta` | `#736B63` | `#B8B2A9` |
| 就绪色 | `colorScheme.secondary` | `#2E7D32` | `#8ED08F` |
| 待处理/告警 | `BadgeSnapshotContent` 系（暖琥珀） | `#6E563E` | `#D9C3A6` |
| 未就绪灰 | `zhiBoxColors.inkFaint` | `#9E948A` | `#7A736B` |
| pill 底 | `zhiBoxColors.pillInactive` | `#EFECE6` | `#2A2724` |

### 9.2 尺寸/字号

| 元素 | 规格 |
|---|---|
| 面板圆角 | 28dp（`ZhiBoxRadius.omniBar`） |
| 面板左右内边距 | 20dp |
| 状态卡标题 | 18sp / 600 / Plus Jakarta Sans（`titleLarge`） |
| 结论句 | 13sp / 400 / `inkSecondary`（`bodySmall`） |
| pill | 高 28dp，左右 10dp，圆角 999dp，11sp/500 |
| 分组标题 | 13sp / 500 / `inkMeta`，上下间距 16dp/8dp |
| 行 | 高 56dp，标题 14sp/500，副标题 12sp/`inkMeta`，右侧 chevron 16dp |
| 角标 | 14dp 圆，9sp/600 白字，`#B45309` 底 |
| 状态点 | 视觉 10dp，光晕最大 24dp，命中区 48dp |

### 9.3 一致性检查清单
- [ ] 面板底/线/文字全部来自 token，无 `Color(0xFF…)` 裸值
- [ ] 图标使用 `ZhiBoxIcons`（chevron 复用现有 24 视口 2px 线宽）
- [ ] 深色模式对比度 ≥4.5:1（pill 文字、副标题）
- [ ] 与首页卡片/设置卡片圆角（16dp）区分：面板 28dp、内部卡片 16dp

---

## 10. 状态计算契约

### 10.1 数据源

| 字段 | 来源 | 成本 | 刷新时机 |
|---|---|---|---|
| `model: String?` | `EmbeddingRuntime.findModel(app)` | 文件系统扫描，中 | 面板打开 / 设置变更 / 下载完成 |
| `spaces` | `db.embeddingMetaDao()` 聚合 | DB 查询，低 | 面板打开 |
| `chunkCount/docReady` | `db.chunkDao().count()` / `documentDao().readyCount()` | 低 | 面板打开 |
| `gpuPref` | `PathPreferences.gpuMode()` | 低 | 面板打开 |
| `preference`（嵌入路径） | `PathPreferences.preferred()` | 低 | 面板打开 |
| `llm/llmError` | `LlmPreferences` / `KeystoreLlmConfigStore` | Keystore 解密，中 | 面板打开 |
| `sagEnabled/answerEnabled/networkDisabled/consent` | `LlmPreferences` | 低 | 面板打开 |
| `budgetTier` | `SagBudgetPreferences` | 低 | 面板打开 |
| `extractionCounts` | `ExtractionJobDao` 聚合 | DB 查询，低 | Flow 订阅（有任务时） |
| `legacySpaces` | `IngestRepository.legacySpacesReport()` | DB 查询，中 | 面板打开 |
| `indexHealth` | `IngestRepository.indexHealth()` | 全表 COUNT，**高** | **仅用户点「检查」时**，不在打开面板时自动跑 |
| `downloading/downloadPct` | 下载任务 Flow | 低 | Flow |
| `reembedDone/Total` | 重嵌入 Flow | 低 | Flow |

### 10.2 聚合模型（设计契约，非实现）

```kotlin
data class IntelligenceStatus(
    val level: Level,                 // READY / PARTIAL / NOT_READY / ERROR
    val busy: Busy,                   // NONE / DOWNLOADING / EXTRACTING / SYNCING
    val model: ModelStatus,           // Missing / Ready(name) / Corrupt
    val spaces: SpaceStatus,          // None / Ready(count) / Mismatch
    val gpu: GpuStatus,               // Auto / On / Off / Unknown(永不显示"可用")
    val llm: LlmStatus,               // NotConfigured / Configured / LoadError
    val pending: List<PendingItem>,   // 旧空间 / 抽取失败 / 索引不一致 / 授权丢失
)
```

### 10.3 不可知项处理

| 项 | 允许显示 | 禁止显示 |
|---|---|---|
| GPU | `自动（首次推理时探测）` / `CPU` / `GPU` | ❌「GPU 可用」「GPU 已启用」 |
| LLM | `已配置` / `未配置` / `读取失败` | ❌「已连接」（只有测试连接后才能说） |
| 索引健康 | `未检查` / `不一致（n）` / `一致` | ❌ 默认假设健康 |

### 10.4 降级原则

- 单个数据源抛异常 → 该字段 `Unknown`，其余正常渲染，**不整屏报错**。
- `indexHealth` 属重查询：默认 `未检查`，只有用户显式点击或从诊断页返回时刷新。
- 所有耗时读取在 `Dispatchers.IO`，面板打开时先渲染骨架（≤100ms 出首帧）。

---

## 11. 边界与失败模式

| # | 场景 | 期望行为 |
|---|---|---|
| B1 | 首次冷启动、无模型 | 灰点静止；首页显示一次性引导条；面板首屏给"下载模型" |
| B2 | 下载中断/失败 | 点回到 READY/未就绪原状态；面板行显示"下载失败 · 重试"（复用 `settings_download_failed`） |
| B3 | 切换嵌入模型 | 旧空间进入 `legacySpaces` → PARTIAL + 角标；面板提示"需重新导入" |
| B4 | SAF 授权丢失 | PARTIAL + 角标；行内"重选目录"直达系统选择器 |
| B5 | Keystore 解密失败 | LLM pill 显示"读取失败"；不影响 L1 颜色 |
| B6 | 面板打开期间数据变化 | 局部刷新，不重建整块（避免滚动跳动） |
| B7 | 旋转 / 进程重建 | 面板状态用 `rememberSaveable` 保留（展开态）；状态点状态重算 |
| B8 | 无网络 | 只影响 LLM/下载；面板给"离线可用"提示，不报错 |
| B9 | 连点入口 | 面板已开时忽略（幂等），不叠加 sheet |
| B10 | 深色切换 | 面板与状态点即时跟随，无闪烁 |
| B11 | 大规模数据（1万+ 分块） | 面板打开耗时 ≤200ms；`indexHealth` 不自动跑 |

---

## 12. 验收标准

### 12.1 功能
- [ ] 4 态判定与 §3.1 伪代码一致，穷举测试覆盖 4 态 × 3 个 busy 状态
- [ ] §4 的 E1–E15 全部可达且文案正确
- [ ] 面板首屏无需滚动即可看到：结论句、4 个 pill、首个可用动作
- [ ] 面板每个写操作 ≤1 击到达目标（设置锚点或就地动作）
- [ ] 无模型时首页引导条出现一次，完成后不再出现
- [ ] 设置页 17 个 section 一个不丢，分组与 §7.1 一致

### 12.2 无障碍
- [ ] 状态点 TalkBack 播报：`智能状态，未就绪，未配置模型，双击打开`（含 `role=Button`）
- [ ] 命中区实测 ≥48dp（可用 `uiautomator dump` 验 bounds）
- [ ] 系统动画缩放 = 0 时，无任何持续动画
- [ ] 所有 pill/角标信息均有文本替代（不只靠颜色）
- [ ] 字体缩放 200% 下，面板不截断、pill 可横向滚动

### 12.3 性能
- [ ] 首页冷启动耗时相对基线增加 ≤50ms（基线：改造前 10 次冷启动中位数）
- [ ] 面板首次打开 ≤200ms 出首帧；`indexHealth` 不在打开时自动执行
- [ ] 状态 Flow 使用 `WhileSubscribed`，离开首页后不持有订阅

### 12.4 回归
- [ ] 设置页现有 30+ 项功能零行为变化（下载/删除/清空/备份/诊断/隐私）
- [ ] 首页其余区域（时间线/筛选/输入岛）无视觉与交互变化
- [ ] 深色模式、浅色模式、横屏（如有）均正常

### 12.5 测试清单
- [ ] 纯 JVM：`IntelligenceStatus` 判定矩阵（4 态 × 3 busy × pending 组合）
- [ ] 纯 JVM：空态组合优先级（§4 判定顺序）
- [ ] Robolectric：状态点文案/无动画分支（减少动画开）
- [ ] 仪器测试（可选）：点开面板 → 深链设置锚点落位

---

## 13. 工作量拆解

| # | 任务 | 产出 | 人日 | 依赖 |
|---|---|---|---|---|
| P0-1 | `IntelligenceStatus` 聚合层（含降级/不可知项处理）+ 纯 JVM 单测 | 1 个 model 文件 + 1 个测试文件 | 1.0 | — |
| P0-2 | 状态点：4 态颜色/文案/动效（慢呼吸、快脉冲、静止）、48dp 命中区、reduce-motion、a11y | `HomeScreen` 局部改造 | 0.5 | P0-1 |
| P0-3 | 首页一次性引导条（E1 场景 + 持久化标记） | `HomeScreen` + `app_kv` 读写 | 0.5 | P0-2 |
| P1-1 | 状态中枢 bottom sheet（状态卡 + pill + 快捷动作 + 待处理 + 底部按钮） | 新文件 `IntelligenceHubSheet.kt` | 1.5 | P0-1 |
| P1-2 | 设置页分组重组 + `initialSection` 锚点（方案 A） | `SettingsScreen` 改造 + 30 行锚点 | 0.5 | P1-1 |
| P1-3 | 空态/异常/下载中/断网落地与文案 | 字符串 + 条件渲染 | 1.0 | P1-1 |
| P2-1 | 真机走查（浅/深色、TalkBack、reduce motion、冷启动耗时、下载/抽取进度实时性） | 验收记录 + 截图 | 1.0 | 全部 |
| — | **合计** | | **6.0 人日** | |

**建议里程碑**
1. **M1（2.0 人日）**：P0-1 + P0-2 + P0-3 —— 交付"绿点说真话 + 首次引导条"，独立可用。
2. **M2（3.0 人日）**：P1-1 + P1-2 + P1-3 —— 交付"状态中枢 + 深链 + 空态"，主线完成。
3. **M3（1.0 人日）**：P2-1 —— 真机走查与验收。

**风险与缓冲**
| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| `indexHealth` 在大库上慢 | 中 | 面板卡顿 | 不自动跑；仅在诊断页执行（已写入 §10.1） |
| 深链偏移量在字体缩放后失效 | 中 | 跳错位置 | 布局完成后重算 offset；验收含 200% 字号 |
| 设置页重组引发既有回归 | 低 | 功能丢失 | §12.1 的"section 零丢失"勾选 + 逐项走查 |
| 状态聚合与 SettingsViewModel 重复实现 | 中 | 两处漂移 | 聚合层独立，设置页迁移调用（P2 可选） |
| +0.5 人日缓冲 | — | — | 合计 6.5 人日封顶 |

---

## 14. 开放问题（需产品/设计拍板）

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| Q1 | 入口形态 | A 裸点 / B 点+文案 chip / C 图标按钮 | **A**（保持设计稿），靠引导条补发现性 |
| Q2 | 是否增加"测试 GPU"动作 | 加（一次短推理，约 1–3s）/ 不加 | **不加**（P0）；如加，放"诊断"分组 |
| Q3 | 设置页原 4 组是否保留在顶层 | 保留 + 智能分组内重复入口 / 只保留分组内 | **保留**（老用户肌肉记忆 + 深链目标） |
| Q4 | 是否在「更多」菜单加第二入口 | 加 / 不加 | **加**（"智能状态"，零成本提升发现性） |
| Q5 | PARTIAL 的琥珀角标是否常驻 | 常驻 / 仅首次 / 可忽略 | **常驻**（直到处理完），因为它代表数据质量 |
| Q6 | 索引检查是否在面板内提供 | 提供"检查"按钮 / 只跳诊断页 | **只跳诊断页**（避免重查询拖慢面板） |

---

## 15. 实现进度（M1，2026-10-23）

| 项 | 状态 | 说明 |
|---|---|---|
| P0-1 状态聚合层 | ✅ 已实现 | `ui/IntelligenceStatus.kt`（纯 Kotlin，无 Android 依赖）+ `IntelligenceStatusTest`（13 个用例，覆盖四态/优先级/读取失败降级/待处理顺序） |
| P0-2 状态点 | ✅ 已实现 | 四态颜色、慢呼吸 4s / 快脉冲 1.4s / 静止、`ANIMATOR_DURATION_SCALE=0` 时全静止、48dp 命中区、单个 TalkBack 节点（`clearAndSetSemantics` 合并状态描述）、待处理角标（压在圆点右上，14dp） |
| P0-3 首次引导条 | ✅ 已实现 | 仅"未就绪 + 未处理过"时出现；CTA/关闭都写入 `app_kv:intelligence_onboarded`；有模型后自动消失 |
| P1-1 状态中枢面板 | ⏸ M2 | 当前点击状态点落到**设置页**（模型商店/嵌入路径/GPU/LLM 都在该页），M2 换成面板只需改 `HomeScreen.onOpenIntelligence` 一处映射 |
| P1-2 设置页分组 + 深链 | ⏸ M2 | 依赖面板 |
| P1-3 空态/异常文案 | 🟡 部分 | M1 已落地"未就绪 + 引导条"；面板内的 E2–E15 随面板一起做 |

**M1 与规格的偏差（均已在代码注释中说明）**
1. 灰色用 `inkMeta` 而非 `inkFaint`（对比度，见 §3.2 说明）；
2. 引导条 CTA 文案用「去配置」（M1 目标页是设置页，不是模型商店深链）；
3. `IntelligenceStatus` 增加 `IntelligenceStatus.Loading` 占位（冷启动首帧不发绿、不闪）；
4. 角标位置由"48dp 命中区右上角"改为"压在 10dp 圆点右上沿"（角上会与圆点脱开，视觉不成立）。

**M1 未采集但已有判定逻辑的状态**：下载中/重嵌入中的 `Busy` 分支（当前恒 `NONE`，因为下载与重嵌入进度目前只在 `SettingsViewModel` 内，需要 M2 抽共享 Flow 才能实时驱动动效）；索引不一致（重查询，M1 恒"未检查"）。

---

## 附录 A：与现状的差异清单（便于评审逐条确认）

| 现状 | 规格后 |
|---|---|
| 绿点永远绿、文案固定、不可点 | 4 态真实状态 + 48dp 可点 + 状态化 a11y |
| ping 1800ms/2.4x 无限循环 | 慢呼吸 4s（就绪）/ 快脉冲 1.4s（繁忙）/ 静止（未就绪）；响应减少动画 |
| 无模型时首页不提示 | 灰点 + 一次性引导条 + 面板直达下载 |
| 设置页 17 项平铺、无分组 | 4 个分组（智能/数据/诊断/隐私），智能组内二级小标题 |
| 设置项只能从「更多 → 设置」进入 | 增加状态中枢 4 条深链 + 更多菜单第二入口（Q4） |
| GPU/LLM 状态在设置里以开关呈现 | 面板以 pill 呈现"档位/配置态"，且明确标注不可知项 |

## 附录 B：代码锚点（供实现时定位）

| 项 | 位置 |
|---|---|
| 状态点 | `app/src/main/java/com/zhibox/memory/ui/HomeScreen.kt` → `OfflineReadyDot()` |
| 首页顶栏 | 同上 `HomeTopBar()` |
| 首页状态 | `ui/HomeViewModel.kt`（需扩展或新增聚合层） |
| 设置页 | `ui/SettingsScreen.kt:420`（`Column + verticalScroll`）、各 section 行号见 §7.1 |
| 设置状态 | `ui/SettingsViewModel.kt:78+`（`SettingsUiState`） |
| 模型发现 | `core/embedding/EmbeddingRuntime.findModel()` |
| 路径偏好 | `core/model/PathPreferences.kt`（`AUTO/A/B/D`、`GPU_AUTO/GPU_OFF/GPU_ON`） |
| 共享目录状态 | `core/model/SharedModelStore.kt`（`NOT_CONFIGURED/CONNECTED/PERMISSION_LOST`） |
| LLM 配置 | `core/llm/LlmPreferences.kt`、`ui/SettingsViewModel.LlmConfigUi` |
| 预算档位 | `core/sag/SagModels.kt`（`SagBudgetTier.HIGH/MID/LOW`） |
| 抽取并发度 | `core/extraction/SagExtractionPreferences.kt`（默认 1） |
| 设计令牌 | `ui/theme/Color.kt`、`Type.kt`、`Shapes.kt`；`DESIGN.md` |
| 文档一致性门禁 | `scripts/check_docs_consistency.sh`（本文档不重复其校验事实） |
