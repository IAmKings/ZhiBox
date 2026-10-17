# =============================================================================
# ZhiBox release 通道 R8/ProGuard keep 规则（P0-3）
#
# 策略：**保守优先**。本项目依赖 MediaPipe Tasks / LiteRT-LM / FlatBuffers /
# Room 等大量「JNI + 反射 + 代码生成」栈，R8 过度裁剪的代价是 release 包在
# 真机上 NoClassDefFoundError / 解析失败（见 app/build.gradle.kts 的 litertlm
# 版本强制解析注释——同类问题已有先例）。当前 MVP 体积不是首要矛盾，
# 先保证 release 包可构建、可运行、crash 可诊断，后续按基线做逐项收紧。
# =============================================================================

# --- crash 可诊断性：保留源文件名与行号（否则 release 堆栈不可定位） ---------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# 运行时反射/序列化常见依赖的属性：注解、签名、泛型、内部类、异常
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault

# --- 通用保守规则 -----------------------------------------------------------
# JNI 入口必须保留类名与 native 方法名（MediaPipe/LiteRT 通过 JNI 反查 Java 类）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Parcelable CREATOR（Intent/Bundle 往返）
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
# 枚举 values()/valueOf() 反射
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Kotlin 协程/反射少量依赖（保护 lambda 与伴生对象名，体积代价可接受）
-keepclassmembers class kotlin.Metadata { *; }

# --- MediaPipe Tasks（Universal/Text Embedder 的 Java 侧） -------------------
# JNI 侧按完整类名查找；Tasks 的 graph/runner 又通过反射构造可选组件。
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# --- LiteRT-LM（MediaPipe tasks-retrieval 传递的原生推理依赖） ----------------
# ActivationDataType 等类经 JNI/JNI-onload 反射访问，0.18.0 才补齐（见构建脚本）
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# --- FlatBuffers（Tasks 的模型元数据/TFLite schema 反序列化） ----------------
-keep class com.google.flatbuffers.** { *; }
-keepclassmembers class com.google.flatbuffers.** { *; }

# --- Protobuf（MediaPipe Tasks 的 proto 消息，GeneratedMessageLite 用反射取默认实例）
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }

# --- R8 缺类抑制（首轮 assembleRelease 实测缺失，规则由 AGP missing_rules.txt 生成）
# 这些类只出现在未使用的可选 API 签名/编译期注解中，运行时不会被加载：
#   · Memoized：AutoValue 的编译期注解（com.google.auto.value 为 compileOnly 依赖）
#   · CalculatorProfileProto / GraphTemplateProto：MediaPipe framework 可选 proto，
#     未随 tasks AAR 分发；对应 GraphProfiler/Graph 方法在本项目调用路径中不可达。
# 若未来启用 GraphProfiler/模板图加载，需要补相应 protobuf 依赖而不是删除本规则。
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# --- Room 生成类（AppDatabase_Impl / Dao_Impl / 反射访问的 Entity 字段） ------
# room-runtime 自带 consumer 规则，这里是二次保险（防 AGP 版本差异导致规则丢失）
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class * implements androidx.room.RoomDatabase$Callback { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
# 查询返回的 POJO 字段名与 SQL 列名映射依赖反射（本项目未用 @Keep，故按注解保留）
-keep @androidx.room.Embedded class * { *; }
-keep @androidx.room.Relation class * { *; }

# --- WorkManager：Worker 由 WorkerFactory 按类名反射实例化（含 Worker 子类） --
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.work.Worker { <init>(...); }
-keep class * extends androidx.work.CoroutineWorker { <init>(...); }

# --- Compose：运行时快照/组合器在部分路径依赖保留的成员（AAR 已带 consumer 规则，
#     此处保守兜底，避免 shrinker 删掉 runtime 的可反射访问成员） --------------
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

# --- AndroidX 生命周期/ViewModel（P1-8 引入 ViewModel store 反射） -----------
# lifecycle 通过 ClassLoader/反射创建 AndroidViewModel，类名不能混淆
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

# --- 应用自身（保留入口与 Application 组件语义；内部实现仍会被 R8 优化） ----
# MainActivity 由 Framework 按 manifest 类名实例化，必须保留类名
-keep class com.zhibox.memory.MainActivity { <init>(); }
# 原生库加载入口（System.loadLibrary 的 so 名由 Java 常量引用，类名保留便于诊断）
-keep class com.zhibox.memory.core.embedding.** { <init>(...); }

# B4-4（审查修复）：release R8 会裁剪未被引用的 BuildConfig 常量；保留构建可追溯字段，
# 使 release APK 内仍可用 dexdump/apkanalyzer 读到 git sha 与构建时间（交付归档审计用）。
-keep class com.zhibox.memory.BuildConfig {
    public static final java.lang.String BUILD_GIT_SHA;
    public static final java.lang.String BUILD_TIME;
}
