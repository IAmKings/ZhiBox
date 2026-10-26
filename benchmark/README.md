# Macrobenchmark（`:benchmark`）

独立 `com.android.test` 模块，被测目标 `:app` 的 `benchmark` 变体
（`initWith(release)` + debug 签名 + `isDebuggable=false`）。
用于守住两类回归：**冷启动**与**设置页导航/快速滑动**的帧耗时。

## 用到的库（离线缓存已预热）

| 依赖 | 版本 |
|---|---|
| `androidx.benchmark:benchmark-macro-junit4` | 1.5.0 |
| `androidx.test.uiautomator:uiautomator` | 2.4.0 |
| `androidx.test.ext:junit` | 1.2.1 |

新增依赖的 SHA-256 已补入 `gradle/verification-metadata.xml`（严格校验，既有条目未改动）。

## 命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 只做编译/打包（无设备、CI 可用，离线也可）
./gradlew :benchmark:assembleBenchmark :app:assembleBenchmark --offline

# 真机跑（先关动画；benchmark 库也会尝试自动处理）
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
./gradlew :benchmark:connectedBenchmarkAndroidTest --offline

# 只跑某个用例（类名见下）
./gradlew :benchmark:connectedBenchmarkAndroidTest --offline \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhibox.benchmark.SettingsBenchmark
```

结果 JSON 在
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<设备>/com.zhibox.benchmark-benchmarkData.json`，
逐次 trace 也在同目录。

## 用例

| 用例 | 测什么 | 指标 |
|---|---|---|
| `StartupBenchmark.startupCold` / `startupColdPartial` | 冷启动到首帧 | `FrameTimingMetric` + `StartupTimingMetric`（编译模式 None / Partial） |
| `SettingsBenchmark.settingsScrollFrames` | 首页 →「更多设置」→「设置」→「高级与诊断」→ 3 次快速上下滑动 | `FrameTimingGfxInfoMetric`（`dumpsys gfxinfo framestats`） |

> 2026-10 设置页简化后：默认「简单首页」只有 5 个 item、一屏放得下，滑不动；
> `setupBlock` 需再点一次「高级与诊断」，在原 17 项列表上采集滚动帧耗时。

`SettingsBenchmark` 用 gfxinfo 而不是 perfetto 的 `FrameTimingMetric`：部分 OEM ROM
（实测 Redmi 23078PND5G / Android 14 / MIUI）trace 里 RenderThread 线程无名字，
`FrameTimingMetric` 会抛 `Observed no renderthread slices in trace`；gfxinfo 口径不依赖线程命名。

## 基线（Redmi 23078PND5G / Android 14）

### 2026-10-27 · 设置页简化后（采集页 = 设置 → 高级与诊断，原 17 项列表）

> ⚠️ 设置页简化为「简单首页 + 高级与诊断」后，滚动采集页从旧平铺页变为高级页；
> 下表**与 2026-10-23 旧基线不可直接对比**。JSON：`baseline/2026-10-27-23078PND5G-settings-scroll.json`。

`SettingsBenchmark`（5 轮，min / median / max）：

| 指标 | min | median | max |
|---|---|---|---|
| `gfxFrameTime50thPercentileMs` | 6 | 7 | 7 |
| `gfxFrameTime90thPercentileMs` | 8 | 9 | 13 |
| `gfxFrameTime95thPercentileMs` | 13 | 17 | 22 |
| `gfxFrameTime99thPercentileMs` | 21 | 25 | 44 |
| `gfxFrameJankPercent` | 5.80% | 6.85% | 15.71% |

注：5 轮 P95 呈 `22 → 18 → 17 → 15 → 13` 的暖机下降趋势（首轮含页面首次组合/着色器成本）。
本轮未改基准方法（仍是 setup 导航 + measureBlock 3 次 swipe）；如后续需要更稳的 CI 信号，
可在 setup 末尾加一次不计入测量的预热滑动，再重录基线。

### 2026-10-23 · 旧平铺设置页（历史基线，不可直接对比）

| 指标 | min | median | max |
|---|---|---|---|
| `gfxFrameTime50thPercentileMs` | 6 | 7 | 7 |
| `gfxFrameTime95thPercentileMs` | 9 | 9 | 11 |
| `gfxFrameJankPercent` | 3.08% | 6.15% | 11.76% |

`StartupBenchmark`（冷启动）历史记录：
- v0.1.4 发布前（2026-10-23）：`CompilationMode.Partial` TTLD median **342.7ms**，
  `CompilationMode.None` 365.7ms（单次 run，设备状态与后台负载未固定，噪声较大）。
- 智能中枢 A–D 补齐后（2026-10-24）：`Partial` **397.4ms**（min 372.4 / max 448.6）、
  `None` 425.9ms（min 393.5 / max 555.2）；同一天改动前复测为 Partial 397.1ms / None 424.2ms
  → 本轮改动 **+0.3ms / +1.7ms**（在噪声范围内）。基线 JSON 见 `baseline/2026-10-24-23078PND5G-startup.json`。
  建议：以固定设备状态（飞行模式/清后台/充电）重录启动基线后再作为 CI 门槛。

建议 CI 门槛（设置页·高级与诊断）：`gfxFrameTime95thPercentileMs ≤ 20`、`gfxFrameJankPercent ≤ 10`
（旧 16ms 门槛针对旧平铺页，已不适用；20ms 基于 2026-10-27 基线 median 17ms + 缓冲，
待预热方法固定后可收紧到 16ms）；启动：TTLD median 不超过基线 +10%。

## ⚠️ 会清空被测应用数据

Macrobenchmark 在准备/重建目标应用（清理编译状态、模拟冷启动）时会 clear 目标应用数据，
**不要把 benchmark 跑在存有真实数据的设备上**；本机测试后可先备份再跑：
`adb shell run-as com.zhibox.memory cat databases/zhibox.sqlite3 > backup.db`（跑完再推回）。

## 已知环境注意

- 离线缓存中的 benchmark 依赖由 Aliyun public 镜像预热，Gradle 离线元数据按仓库 URL 记录：
  `settings.gradle.kts` 里为 `com.squareup.*` / `org.jspecify` 追加了限定 group 的 public 镜像
  fallback（Central 优先，CI 正常走 Central）。
- AGP 的 `com.android.test` 模块 `benchmarkTestedApks` 配置缺少 KMP 变体属性，本项目
  （Compose/Room/Lifecycle 为 KMP 发布）需要补 `kotlin.platform.type=androidJvm` 等属性，
  见 `benchmark/build.gradle.kts` 顶部注释。
