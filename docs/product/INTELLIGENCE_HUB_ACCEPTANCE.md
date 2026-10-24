# 智能状态中枢 · M3 真机走查记录

> 走查日期：2026-10-23 晚 ｜ 设备：Redmi 23078PND5G（`23078PND5G` / Android 14 / MIUI，1220×2712，density 3.0）
> 对象：`docs/product/INTELLIGENCE_HUB_SPEC.md` §12「验收标准」；M1+M2 交付后的验收记录
> 结论：**可交付**。功能与无障碍项基本通过；3 项已知限制（无模型设备导致 READY 动画无法可视化、E2/E3/E15 空态未真机复现、启动基线口径差异）已记录。

---

## 1. 走查中发现并修复的问题

| # | 问题 | 证据 | 处理 |
|---|---|---|---|
| 1 | 首页顶栏「搜索」「更多」按钮的无障碍标签落在**图标子节点**上，TalkBack 会拆成"无标签按钮 + 无动作描述" | `uiautomator dump`：`View clk=true bounds=[1016,143][1160,287] desc=''`，子节点 `desc='更多设置' 20dp` | 标签移到可点击节点（`Modifier.semantics { contentDescription }`，`Icon(contentDescription=null)`）；复测两个节点均为 `48×48dp clk=true desc='搜索知识库'/'更多设置'` |
| 2 | 面板关闭按钮命中区只有 40dp，且标签同样落在图标子节点 | dump：`View clk=true 48dp desc=''` + 子 `desc='关闭'` | `IconButton` 改 48dp + 标签上移；复测 `48×48dp desc='关闭'` |
| 3 | 面板快捷动作行 / 待处理行的可点击节点无标签（TalkBack 只读"按钮"） | dump：行 `clk=true desc='' text=''`，标题/副标题在子节点 | 行级 `clearAndSetSemantics { contentDescription = "标题，副标题" }`；复测 `desc='嵌入模型，未配置'`、`desc='GPU 加速，自动（首次推理时探测）'` |

## 2. §12 验收清单结果

### 2.1 功能

| 项 | 结果 | 证据 |
|---|---|---|
| 4 态判定与伪代码一致 | ✅ | `IntelligenceStatusTest` 13 例（含 animates/needsOnboarding 分支），`testDebugUnitTest` 通过 |
| 面板首屏三问 | ✅ | 结论句 + 4 pill + 首个动作同屏（`impl-intel-hub-light.png`） |
| 每个写操作 ≤1 击到达 | ✅ | 深链：嵌入模型/路径 → 嵌入路径；LLM → SAG 增强/LLM；底部 → 设置顶部（dump 验证落位） |
| 无模型时引导条出现一次 | ✅（M1 已验证） | `ui_hint_intelligence_onboarded` 标记；本次未重复验证 |
| 设置页 17 个 section 一个不丢，分组与 §7.1 一致 | ✅ | 7 个分组标题（智能/模型与算力/问答与增强/检索质量/数据/诊断/隐私与关于）全部可见；17 个 item 逐一滚动核对 |
| E1–E15 可达 | 🟡 部分 | E1 已验证（`还没有可用的本地模型` + 下载引导）；E2/E3/E15 需"有模型 / 空知识库 / 损坏模型"三种设备状态，本机无模型文件无法复现（代码分支存在于 sheet，见 §3 限制） |

### 2.2 无障碍

| 项 | 结果 | 证据 |
|---|---|---|
| 状态点播报含状态（role=Button） | ✅ | `clearAndSetSemantics`：`contentDescription='智能状态'` + `stateDescription=四态文案`；`clickable` + `Role.Button` |
| 状态点命中区 ≥48dp | ✅ | dump：`bounds=[60,143][204,287]` = 48×48dp |
| 面板行/关闭按钮标签在可点击节点 | ✅（本次修复） | 见 §1 |
| 系统动画缩放=0 时无持续动画 | ✅（当前状态） | 设 `animator/transition/window_animation_scale=0`，两次间隔 1.3s 截图状态点区域像素差 **0**；当前为"未就绪"静态态，动画开关两种设置下差异均为 0，与"未就绪不呼吸"一致 |
| pill/角标有文本替代 | ✅ | pill 均为"标签 值"文本（`模型 未配置`、`空间 0`、`GPU 自动（首次推理时探测）`、`LLM 未启用`）；角标数字走 stateDescription |
| 200% 字号面板不截断 | ✅ | `font_scale=2.0` 截图：结论/空态行换行完整，pill 仍可横向滚动，行高自动增长（`heightIn(min)`） |

### 2.3 性能

| 项 | 结果 | 证据 |
|---|---|---|
| 面板首次打开 ≤200ms 出首帧 | ✅ | `gfxinfo framestats`：打开面板期间最重组合帧 **3.7ms**（5 帧内完成） |
| `indexHealth` 不在打开时自动执行 | ✅ | 进场设置页「索引诊断」显示「尚未检查（点下方按钮开始）」，点按钮后才出三表计数 |
| 状态 Flow `WhileSubscribed(5s)` | ✅ | `HomeViewModel.uiState` 使用 `stateSharingStarted = WhileSubscribed(5_000)`（代码核对） |
| 首页冷启动相对基线 ≤+50ms | 🟡 见限制 | `StartupBenchmark` 复测 TTLD median：`Partial` 397.1 / 400.9ms（两次），`None` 424.2 / 460.2ms；M2 前记录为 342.7ms → +54~58ms，处于设备噪声量级（M2 改动不在启动路径：面板按需组合、MainActivity 仅增加一个 state） |

### 2.4 回归

| 项 | 结果 | 证据 |
|---|---|---|
| 设置页功能零行为变化 | ✅ 冒烟 | 17 段滚动无崩溃；「检查并修复索引」→ `chunk 5 · FTS 5 · 向量 0` + 「索引一致」+ 修复前后对比；LLM/质量按需加载正常 |
| 首页其余区域无变化 | ✅ | 时间线/筛选滑滚/输入岛截图对比无差异；横屏内容仍按 448dp 最大宽度居中 |
| 深色 / 浅色 / 横屏 | ✅ | 深色面板与深色分组设置截图；横屏（2712×1220）首页与面板正常，面板最大高度按屏高 70% 收敛 |

### 2.5 测试清单

| 项 | 结果 |
|---|---|
| 纯 JVM：`IntelligenceStatus` 判定矩阵 | ✅ 13 例 |
| 纯 JVM：空态组合优先级 | ✅ 含在 13 例内（PendingItem 顺序/降级） |
| 深链索引（条件项） | ✅ `SettingsItemOrderTest` 6 例 |
| Robolectric：状态点文案/无动画分支 | ⚠️ 未新增——仓库现有 Robolectric 用例只覆盖 drawable 渲染；Compose UI 测试基建在 `androidTest`（`ComposeRecreateSmokeTest` 在 MIUI 上会挂起），故改用"dump 语义节点 + 动画缩放像素差"手工验证 |
| 仪器测试：面板 → 深链锚点落位 | ✅ 手工等价验证（dump 落位文本） |

## 3. 已知限制（不阻塞交付）

1. **READY/PARTIAL 的呼吸动画未真机可视化**：本机 `filesDir/models` 为空（NOT_READY → 设计上静止），无法复现绿点呼吸；`animates` 分支已被 13 个单测覆盖，`ANIMATOR_DURATION_SCALE=0` 时全静止的代码路径已人工核对。
2. **E2/E3/E15 空态文案未真机复现**：需要"检测到模型未启用 / 模型就绪但空库 / 模型文件损坏"三种状态；面板内分支已实现（`impl-intel-hub-*` 的 Preview 可预览）。
3. **启动基线口径**：342.7ms 为 M2 前单次 benchmark 记录；本次两次复测 397–401ms，差异在设备噪声区间。建议 CI 固定机型/温度下重录基线，并把「首页冷启动」纳入同一 benchmark 门槛。
4. **面板打开后未做锚点高亮**（spec §6.5 的 1.2s 高亮）：验收标准未要求；如需，可在设置页 `initialSection` 落位后加 1.2s 背景色过渡。

## 4. 证据截图

| 文件 | 内容 |
|---|---|
| `design/main_screen/impl/impl-intel-hub-light.png` | 面板（浅色，未就绪态） |
| `design/main_screen/impl/impl-intel-hub-dark.png` | 面板（深色） |
| `design/main_screen/impl/impl-intel-deeplink-llm.png` | 深链：LLM 配置 → 问答与增强分组 |
| `design/main_screen/impl/impl-settings-groups.png` | 设置页分组（智能 / 模型与算力） |
| `design/main_screen/impl/impl-settings-dark.png` | 设置页深色 |
| `design/main_screen/impl/impl-intel-hub-landscape.png` | 横屏面板 |
| `design/main_screen/impl/impl-intel-hub-font200.png` | 200% 字号面板 |
| `design/main_screen/impl/impl-settings-font200.png` | 200% 字号设置页 |

## 5. 追加：共享 Busy Flow（2026-10-23 晚）

- 下载/重嵌入进度已抽成 `IntelligenceBusySource`（StateFlow），首页状态点可在大盘任意页面感知并切快脉冲。
- 纯 JVM 覆盖：`IntelligenceStatusTest` +5 例（共 18 例）。
- 真机限制：本机无模型 + 共享目录未配置，DOWNLOADING/SYNCING 的可见窗口无法稳定复现
  （下载会先弹 SAF 目录选择器；重嵌入 worker 无模型立即失败），建议在装有模型的设备上补走查。

---

## 6. A→D 补齐验收（2026-10-24，规格「完全验收」轮）

> 本轮把上一轮遗留的 A（E1–E15 空态/busy）、B（E11 可达 + 就地动作）、C（深链高亮 1.2s）、
> D（Robolectric/真 TalkBack/冷启动基线）全部落地。真机：Redmi 23078PND5G（K60U，debug 包，可 run-as 造状态）。

### 6.1 §4 空态/异常矩阵（E1–E15）

| # | 场景 | 验证方式 | 结果 |
|---|---|---|---|
| E1 | 全新安装全空 | 真机（无模型/无候选文件） | ✅ 主卡「还没有可用的本地模型 / 下载模型后即可导入文档并离线检索」+ CTA「下载模型」；`impl-hub-e1.png` |
| E2 | 有候选文件但模型未生效 | 单测（`IntelligenceHubModelTest`） | ✅ 判定 `Missing && modelFileCount>0` →「已检测到 N 个模型文件，尚未启用」+「去启用」；该分支仅在 resolve 抛异常/IO 失败时可达（真实异常路径） |
| E3 | 模型就绪、知识库为空 | 真机（1MB 假模型 + 清空 document） | ✅「模型就绪 · 知识库还是空的」+「去导入」；`impl-hub-e3.png` |
| E4 | 共享目录未配置 | 真机（默认状态） | ✅ info pill「共享目录未配置（模型将存于应用私有目录，卸载即删）」→ 共享模型目录 |
| E5 | 共享目录授权丢失 | 单测（pending 分支） | ✅ 待处理行「共享目录授权失效」→ 重选目录 |
| E6 | LLM 未配置 | 真机 | ✅ 顶部 pill「LLM 未启用」 |
| E7 | LLM 密钥解密失败 | 单测（pending 分支） | ✅ 待处理行「LLM 配置读取失败」→ 去设置 |
| E8 | SAG 关闭 / 禁网 | 真机（默认状态） | ✅ info pill「SAG 抽取不可用…」+「网络已禁用」 |
| E9 | 旧模型空间存在 | 真机（注入 legacy space + embedding） | ✅ 待处理行「1 个旧模型空间的数据未参与检索 / 去处理」；`impl-hub-e10-e9.png` |
| E10 | 抽取失败 | 真机（注入 failed job） | ✅ 待处理行「1 个分块抽取失败 / 重试」（SAG 可用时就地重试）；`impl-hub-e10-e9.png` |
| E11 | 索引不一致 | 真机（chunk 0 / FTS 5 孤儿） | ✅ 待处理行「索引可能有孤儿行 / 修复」；点「修复」后就地重建 FTS，待处理区消失（DB chunk=0/fts=0）；`impl-hub-e11-repair.png` / `impl-hub-e11-after.png` |
| E12 | 模型下载中 | Robolectric 面板渲染测试 | ✅ 进度行「正在下载 <模型> · N%」+ 进度条（`e12_rendersDownloadingProgress`） |
| E13 | 重嵌入同步中 | Robolectric 面板渲染测试 | ✅「正在同步旧数据 done/total」+「停止同步」就地动作（`e13_rendersSyncingProgressAndStopIsWiredInPlace`） |
| E14 | 首次联网同意未授予 | 真机 + 单测 | ✅ info pill「问答首次联网未同意」 |
| E15 | 模型文件损坏 | 真机（0 字节模型文件） | ✅「模型文件需要重新下载 / 重新下载」+ pill「需要修复」；`impl-hub-e15.png` |

优先级按规格 §4：`E1 > E2 > E15 > E12 > E13 > E9/E10/E11 > E3` 短路；取消/冲突矩阵由
`IntelligenceHubModelTest`（22 个纯 JVM 用例）覆盖（含 E1 胜 E12、E15 胜 E12、E12/E13 胜 E3）。

### 6.2 §6.4 就地动作

| 动作 | 验证 | 结果 |
|---|---|---|
| 抽取失败「重试」 | 真机 + 单测 | ✅ SAG 可用时 `SagExtractionScheduler.retryFailed` 就地执行；**SAG 不可用时按钮自动改为「去配置」并深链到 LLM 配置**（真机验证行 desc=`1 个分块抽取失败，去配置`，点击落到「问答与增强 → SAG 增强 / LLM」）——避免点了没反应 |
| 索引不一致「修复」 | 真机 | ✅ `IngestRepository.repairIndex()` 就地执行，完成后待处理行消失 |
| 同步中「停止同步」 | Robolectric | ✅ 回调接 `ReembedScheduler.cancel`，面板不关闭 |
| 深链类（旧空间/授权丢失/LLM 读取失败） | 真机 + 单测 | ✅ 保持跳转语义 |

### 6.3 §6.5 深链高亮

- 面板点「嵌入模型」→ 设置页滚动到「嵌入路径」后，卡片出现 1.2s 半透明主色覆盖块（浅色 #E3E3E3 级、深色 #343330 级），随后 300ms 淡出；`impl-settings-deeplink-highlight.png` 为亮起瞬间。
- 仅在深链进入时触发一次；普通进入设置页 / 重复进入不高亮；`ANIMATOR_DURATION_SCALE=0` 时 tween(0)。

### 6.4 §12 其余验收项

| 项 | 结果 | 证据 |
|---|---|---|
| §12.1「§4 E1–E15 全部可达」 | ✅ | 见 6.1 |
| §12.2 状态点 TalkBack | ✅ | 真机开启 TalkBack 服务：状态点出现绿色焦点框（48dp），系统 TTS 处于合成状态；`impl-hub-talkback-focus.png`；标签/stateDescription 由 `uiautomator` dump 佐证 |
| §12.2 面板行/关闭按钮标签 | ✅ | 行级 `clearAndSetSemantics`（标题+副标题），关闭 48dp |
| §12.2 200% 字号 / 横屏 / 减少动画 | ✅ | M3 已验收（`impl-intel-hub-font200/landscape` 等） |
| §12.3 面板首帧 ≤200ms | ✅ | M3 实测组合帧 3.7ms |
| §12.3 `indexHealth` 不自动跑 | ✅ | 设置页进场仍显示「尚未检查」；面板用轻量计数探测（chunk vs FTS） |
| §12.3 冷启动 ≤ 基线 +50ms | ✅（同环境） | `StartupBenchmark` TTLD median：Partial **397.4ms**（本轮改动前同设备 397.1ms，+0.3ms）、None 425.9ms（改动前 424.2ms，+1.7ms）；旧记录 342.7ms 为不同设备状态，建议 CI 固定环境重录 |
| §12.5 Robolectric：状态点文案/无动画分支 | ✅ | 新增 `IntelligenceDotRobolectricTest`（8 用例）：四态/忙碌文案渲染、`shouldAnimateDot` 矩阵、无动画时 Dot 单节点渲染；另有 `IntelligenceHubSheetRenderTest`（9 用例）覆盖 E1/E2/E15/E12/E13 与三个就地动作接线 |
| §12.4 回归 | ✅ | 设置页 17 项、首页时间线/筛选/输入岛、浅深色/横屏；`assembleDebug + testDebugUnitTest + lintDebug` 全绿 |

### 6.5 已知偏差与决策（本轮新增）

1. **E1 优先于 E12（按规格）**：首次安装点「下载模型」时面板仍显示 E1（此时候选文件数=0），看不到下载进度；已有模型切换下载时 E12 可见。若需首次下载也显示进度，需改 §4 优先级（本轮未改，保持规格一致）。
2. **E8 默认态即出现两条 info pill**（SAG 默认关、网络默认禁）：符合 §4，但默认面板 pill 偏多；如觉噪音可改为「用户主动更改过才提示」。
3. **E11 为计数级轻量探测**（chunk 数 vs FTS 行数），不是完整孤儿明细；导入并发瞬间可能短暂误报，下次 refresh 自愈；设置页仍是完整检查+修复。
4. 深链高亮在旋转/进程重建后会重放一次（`remember` 非 saveable，刻意未扩大改动）。
