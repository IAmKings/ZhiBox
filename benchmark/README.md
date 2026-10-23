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
| `SettingsBenchmark.settingsScrollFrames` | 首页 →「更多设置」→「设置」→ 3 次快速上下滑动 | `FrameTimingGfxInfoMetric`（`dumpsys gfxinfo framestats`） |

`SettingsBenchmark` 用 gfxinfo 而不是 perfetto 的 `FrameTimingMetric`：部分 OEM ROM
（实测 Redmi 23078PND5G / Android 14 / MIUI）trace 里 RenderThread 线程无名字，
`FrameTimingMetric` 会抛 `Observed no renderthread slices in trace`；gfxinfo 口径不依赖线程命名。

## 基线（Redmi 23078PND5G / Android 14，2026-10-23，设置页已做 LazyColumn + 卡片拆分后）

`SettingsBenchmark`（5 轮）：

| 指标 | min | median | max |
|---|---|---|---|
| `gfxFrameTime50thPercentileMs` | 6 | 7 | 7 |
| `gfxFrameTime90thPercentileMs` | 8 | 9 | 10 |
| `gfxFrameTime95thPercentileMs` | 9 | 9 | 11 |
| `gfxFrameTime99thPercentileMs` | 11 | 13 | 24 |
| `gfxFrameJankPercent` | 3.08% | 6.15% | 11.76% |

`StartupBenchmark`（冷启动，`CompilationMode.Partial`）：TTLD median 342.7ms，
`frameDurationCpuMs` P90 133.3 / P95 150.1；`CompilationMode.None` 下 TTLD median 365.7ms。

建议 CI 门槛（设置页）：`gfxFrameTime95thPercentileMs ≤ 16`、`gfxFrameJankPercent ≤ 10`；
启动：TTLD median 不超过基线 +10%。

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
