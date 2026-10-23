// ---------------------------------------------------------------------------
// :benchmark —— Macrobenchmark 独立测试模块（AndroidX Benchmark 1.5.0）
//
// 形态：com.android.test + targetProjectPath(":app")，测试代码直接写在 main 源集
// （com.android.test 模块的 main = 测试 APK 自身）。因此依赖用 implementation，
// 而不是 androidTestImplementation。
//
// 变体：buildType "benchmark"（test APK）→ 任务 `:benchmark:connectedBenchmarkAndroidTest`。
// 与 :app 的 buildType "benchmark" 按名称匹配（:app 侧 initWith(release) + debug 签名，
// 见 app/build.gradle.kts）；matchingFallbacks 仅作目标工程缺少同名变体时的回落。
//
// 为什么不用 androidx.benchmark Gradle 插件：
//   benchmark-gradle-plugin 1.5.0 明确只支持 com.android.library（jar 内文案：
//   "currently supports only android library modules ... to run macrobenchmarks,
//   this plugin is not required"），宏基准不需要它；这里只依赖
//   benchmark-macro-junit4 运行时库，避免额外插件对依赖校验元数据的侵入。
//
// 依赖校验：新增依赖的 SHA-256 已补入 gradle/verification-metadata.xml；
// 构建必须带 --offline（本机离线缓存已有 1.5.0 / uiautomator 2.4.0）。
// ---------------------------------------------------------------------------

import org.gradle.api.attributes.Category
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    // AGP 的 com.android.test 插件由根工程 plugins 块（alias android.application, apply false）
    // 已经加载到 buildscript classpath，这里直接用无版本的 id 应用，离线可用。
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// AGP 为 com.android.test 生成的 `<variant>TestedApks`（继承 testedApks）配置只带
// {BuildTypeAttr, usage=java-runtime, AgpVersionAttr}。它要解析 `project(":app")` 的
// benchmark APK，但传递依赖会沿用这组属性——本项目 App 依赖里有大量 Kotlin Multiplatform
// 发布的库（Compose 1.11.x、Room 2.8.x、Lifecycle 2.10 等），缺 `kotlin.platform.type` /
// `jvm.environment` 时 Gradle 会选中 jvmStubs/desktop 变体（离线缓存里没有这些 artifact），
// 导致 `:benchmark:connectedBenchmarkAndroidTest` 在 benchmarkTestedApks 上直接解析失败。
//
// 修复：给 testedApks 系列配置补齐 Android 变体选择所需的属性（与 :app 的
// benchmarkRuntimeElements 及 androidx KMP 库 androidRuntimeElements 完全对齐）。
// 只影响测试模块用于安装/校验被测 APK 的这条内部配置，不影响任何产物依赖图。
// ---------------------------------------------------------------------------
configurations.configureEach {
    if (name == "testedApks" || name.endsWith("TestedApks")) {
        attributes {
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID),
            )
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        }
    }
}

android {
    namespace = "com.zhibox.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        // 宏基准用普通 runner：benchmark-macro 自己管理 force-stop / perfetto / 帧时间线，
        // 不需要微基准的 AndroidBenchmarkRunner。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 真机（K60U / MIUI）常见告警不阻断数据采集：低电量与未锁屏。
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "LOW-BATTERY,UNLOCKED"
    }

    // 被测工程：:app（AGP 会为 benchmark 变体建立 target app 依赖并安装其 benchmark APK）
    targetProjectPath = ":app"
    // com.android.test 模块自持测试代码（不是“主工程 + androidTest”结构）
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            // 测试 APK 允许调试无所谓；能被安装即可（真机需要签名）。
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            // 目标工程若无同名变体时回落到 release（通常用不到，:app 已有 benchmark 变体）
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 宏基准核心：MacrobenchmarkRule / StartupTimingMetric / FrameTimingMetric / CompilationMode
    implementation(libs.benchmark.macro.junit4)
    // UiAutomator：等待并点击「更多设置」→「设置」、swipe 复现设置页滑动卡顿。
    // benchmark-macro 1.5.0 传递要求 uiautomator 2.4.0（本模块独立 classpath，与 app androidTest 的 2.3.0 不冲突）。
    implementation(libs.androidx.test.uiautomator.benchmark)
    // @RunWith(AndroidJUnit4::class)：benchmark-macro-junit4 只在 runtime 传递 ext:junit，
    // 编译期需显式声明（版本 1.2.1 与 benchmark-macro 1.5.0 的运行时要求一致）。
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
