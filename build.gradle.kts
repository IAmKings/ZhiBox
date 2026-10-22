buildscript {
    repositories {
        google()
        // Official R8 repository; fallback when Google Maven is unreachable locally.
        maven("https://storage.googleapis.com/r8-releases/raw") {
            content { includeGroup("com.android.tools") }
        }
        mavenCentral()
    }
    dependencies {
        // Kotlin 2.4 metadata requires R8 9.1.29+; version is owned by libs.versions.toml.
        classpath("com.android.tools:r8:${libs.versions.r8.get()}")
    }
}

// ZhiBox 端侧个人知识记忆 MVP（SAG-like）
// 版本配对依据 docs/SAG_Android_MVP_Implementation_Plan_v1.0.md 第 2 节技术决策基线
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
