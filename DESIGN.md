---
name: ZhiBox
colors:
  surface: '#fbf9f4'
  surface-dim: '#dbdad5'
  surface-bright: '#fbf9f4'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f5f3ee'
  surface-container: '#f0eee9'
  surface-container-high: '#eae8e3'
  surface-container-highest: '#e4e2dd'
  on-surface: '#1b1c19'
  on-surface-variant: '#444748'
  inverse-surface: '#30312e'
  inverse-on-surface: '#f2f1ec'
  outline: '#747878'
  outline-variant: '#c4c7c7'
  surface-tint: '#5f5e5e'
  primary: '#000000'
  on-primary: '#ffffff'
  primary-container: '#1c1b1b'
  on-primary-container: '#858383'
  inverse-primary: '#c8c6c5'
  secondary: '#1b6d24'
  on-secondary: '#ffffff'
  secondary-container: '#a0f399'
  on-secondary-container: '#217128'
  tertiary: '#000000'
  on-tertiary: '#ffffff'
  tertiary-container: '#1c1b1a'
  on-tertiary-container: '#868381'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e5e2e1'
  primary-fixed-dim: '#c8c6c5'
  on-primary-fixed: '#1c1b1b'
  on-primary-fixed-variant: '#474646'
  secondary-fixed: '#a3f69c'
  secondary-fixed-dim: '#88d982'
  on-secondary-fixed: '#002204'
  on-secondary-fixed-variant: '#005312'
  tertiary-fixed: '#e6e1df'
  tertiary-fixed-dim: '#cac6c3'
  on-tertiary-fixed: '#1c1b1a'
  on-tertiary-fixed-variant: '#484645'
  background: '#fbf9f4'
  on-background: '#1b1c19'
  surface-variant: '#e4e2dd'
  paper-cream: '#F7F5F0'
  obsidian: '#121212'
  breathing-green: '#2E7D32'
  card-pure: '#FFFFFF'
  border-subtle: '#E5E1DA'
typography:
  headline-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-xl-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 22px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1rem
  margin: 1.25rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style

This design system is forged for **ZhiBox (智匣)**, an offline-first on-device AI knowledge base application. The design style merges **Warm Cream Paper Minimalism** with **Tactile Modernism**, evoking the tranquil, focused ambiance of a physical study while maintaining the crisp precision required for complex data retrieval and edge-AI interactions.

- **Brand Personality:** Intellectual, serene, reassuringly secure, and impeccably organized.
- **Target Audience:** Researchers, privacy-conscious professionals, and power users who demand absolute local control over their knowledge graphs and documents.
- **Emotional Response:** A sense of calm ownership, offline confidence, and intellectual clarity. The UI disappears to let thought flow, punctuated by subtle living indicators that confirm local intelligence is active.

## Colors

The palette is anchored by a warm cream paper background (`#F7F5F0`) that reduces eye strain during long reading sessions, contrasted against deep obsidian black (`#121212`) for structural typography and primary actions. 

- **Primary (`#121212`):** Used for dominant headlines, primary buttons, and key interactive borders.
- **Secondary (`#2E7D32`):** A resonant breathing green reserved exclusively for system status indicators, active nodes, synchronization states, and live agent readiness.
- **Neutral (`#F7F5F0` & `#FFFFFF`):** The foundational canvas uses warm cream, while content cards employ pure white (`#FFFFFF`) to pop gracefully against the paper backdrop without harsh optical glare.

## Typography

The typography system pairs the approachable, geometric warmth of **Plus Jakarta Sans** for headers with the systematic legibility of **Inter** for dense data, snippets, and user interface labels.

- **Headlines:** Use generous tracking adjustments on larger sizes to maintain an editorial, bookish feel while remaining modern. Ensure mobile variants scale down gracefully below 32px to preserve vertical rhythm on compact viewports.
- **Body:** Optimized for high-density document scanning and long-form evidence reading with comfortable line-height ratios (1.4x to 1.5x).

## Layout & Spacing

This design system relies on a **Fluid Grid** model paired with generous outer canvas margins (`1.25rem`) to frame content like pages in an open notebook. 

- **Spacing Rhythm:** Built on a 4px/8px modular scale. Component internal padding leans toward generous whitespace (`space-md` to `space-lg`) to prevent cognitive overload in complex local search and knowledge graph screens.
- **Form Factor Adaptation:** On mobile devices, padding collapses slightly to maximize reading width, while preserving the signature `rounded-3xl` card framing for distinct content groupings.

## Elevation & Depth

Depth is conveyed through **Tonal Layers and Low-Contrast Outlines** rather than heavy, artificial drop shadows, reinforcing the tactile paper metaphor.

- **Surface Hierarchy:** Pure white cards (`#FFFFFF`) sit upon the warm cream paper background (`#F7F5F0`), delineated by ultra-subtle borders (`#E5E1DA`) rather than dark lines.
- **Ambient Elevation:** When interactive elements (such as floating action bars or modal sheets) require elevation, use hyper-diffused, low-opacity obsidian shadows tinted with warm undertones to simulate physical distance from the paper plane.

### Flat Surfaces（实现细则，2026-10-25 补充）

> 将上面的 Elevation 原则落成可执行约束，避免"有的卡片有阴影、有的卡片有描边"的漂移。

- **卡片/列表容器**：elevation = **0**，禁止使用 `shadow`；只用 `1dp` `border-subtle`（浅色 `#E5E1DA` / 深色 `#37332E`）发丝描边 + `16dp` 圆角 + `card-pure` 白底分层。
- **可点击卡片**：交互靠 ripple + 描边表达，不加阴影。
- **浮层**（输入岛 / Bottom Sheet / Dialog）：允许轻阴影，但必须 ≤ `8dp` 且颜色统一用暖黑低透明度令牌 `shadow`（浅色 `#142D2319`）；**不得与描边同时使用**（浮层二选一：描边或阴影）。
- **禁止任意值**：页面里不允许写裸 `shadowElevation = 12.dp` 一类的数字；统一走 `theme/Elevation.kt` 的 `ZhiBoxElevation`（`card = 0` / `floating = 8` / `modal = 8`）与 `ZhiBoxCard` composable（`ui/UiCommon.kt`）。
- **圆角澄清**：上方 Shapes 提到的 `rounded-3xl`（24–32dp）仅用于浮层/模态；卡片以渲染稿 `rounded-2xl`（16dp）为准（`Shapes.kt` 已注明冲突取舍）。
- **验收**：同一页面内深度线索只用一种（描边或阴影）；截图检查卡片边缘外 12px 无阴影渐变。

## Shapes

The shape language is defined by **pronounced, friendly curves** (`rounded-3xl` equivalent to `1.5rem` to `2rem` border radii for major containers and cards).

- **Cards & Containers:** Major structural blocks, knowledge base summaries, and import task cards use deep 3xl rounding to impart an approachable, tactile feel.
- **Pills & Badges:** High-contrast pill capsules and search chips use fully rounded pill shapes (`9999px`) to immediately draw the eye to interactive filters, citation tags, and system states.

## Components

- **Buttons:** Primary actions are rendered in solid obsidian black (`#121212`) with pure white text and pill-shaped or heavily rounded profiles. Secondary actions use ghost styles with subtle borders against the cream background.
- **Chips & Pills:** High-contrast capsules featuring dark backgrounds with light text for active filters, and warm neutral backgrounds with dark text for inactive states. Citation chips (`S1`, `S2`) integrate micro-badges showing source context.
- **Input Fields:** Generously padded search and URL inputs styled inside pure white containers with soft, low-contrast outlines. Focus states engage a breathing green or obsidian border ring.
- **Cards:** The core structural unit. Pure white, `rounded-3xl` cards floating on the warm cream background, housing document summaries, import progress bars, and graph node details.
- **Checkboxes & Radios:** Custom geometric toggles with crisp borders, employing the breathing green accent to confirm active states.
- **Lists & Status Indicators:** Document lists featuring inline status pills, accompanied by the signature "breathing green dot" (`#2E7D32`) to denote live model readiness and active offline sync states.

## Settings IA（简单模式 + 高级与诊断，2026-10）

设置页采用**两层信息架构**，原 17 项能力一项不删：

1. **简单首页（默认）**：4 张主卡 + 1 个高级入口，只回答用户要做的 5 件事。
   - 本地 AI（当前档位 + 管理模型）、资料库（资料/片段计数 + 导入/导出）、联网问答（复合开关 + 配置服务）、隐私与关于（一句话结论 + 查看说明）、高级与诊断入口。
   - 每张卡 = 一句话结论（结果语言）+ 当前值 + 1 个主按钮；卡片高度以 2 行说明为上限。
   - 首屏**禁止**出现实现术语：嵌入 / 向量 / 分块 / 重嵌入 / FTS / SAG / GPU / LLM / 并发 / 孤儿 / queryHash / litertlm / B3-14 / 错误码，也不渲染原始文件名、spaceId、维度、trace mode。
2. **二级页**：模型管理 / 资料库 / 联网问答 / 隐私与关于 / 高级与诊断；均为同一路由内的页面状态切换（与检索屏证据详情同构），返回键先回简单首页。
3. **高级与诊断**：原 17 项全量 + 现有分组顺序；顶部固定「显示技术细节」开关（默认关），关闭时隐藏文件名/空间 ID/错误码/诊断计数，打开后恢复现状；长说明默认折叠为「说明 ▾」。

文案规则：新文案 key 统一走 `strings.xml`（`settings_home_*` / `settings_page_*` / `settings_tier_*`），简单模式文案由 `SettingsCopyAuditTest` 做术语黑名单门禁；`SettingsItemOrder` 深链下标在高级页保持不变。