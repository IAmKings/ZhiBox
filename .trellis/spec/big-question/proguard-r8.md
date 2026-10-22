# R8 / Minified Release

> **Severity**: P0 — debug works, minified release crashes (`ClassNotFoundException`,
> missing field, JSON errors, reflection failures).
>
> Symptom: crash reports only from release builds; stack traces are mangled.

---

## Cause

R8 strips or renames things that are only referenced reflectively:

1. `kotlinx.serialization` classes renamed → `SerializationException` / missing fields
2. Enums / sealed classes looked up reflectively
3. Retrofit interface generics erased
4. `-keep` rules placed in the app module for a library that owns its types (wrong
   place; consumers miss them)

---

## Fix Patterns

### Keep rules belong in the owning module

Serialization classes ship `consumer-rules.pro` in the module that declares them:

```proguard
# core-network/consumer-rules.pro
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class <pkg>.** {
    *** Companion;
}
-keepclasseswithmembers class <pkg>.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

With R8 full mode + kotlinx.serialization, `@Serializable` classes are generally kept
by the serializer's own rules — the failures come from *non-annotated* DTOs or custom
serializers. Audit those first.

### Smoke-test the real artifact

- A release-build install + login + first-run smoke test **before** tagging a release.
- Enable R8 mapping upload to the crash reporter so traces de-obfuscate
  (TODO(spec): record the mapping-upload setup).
- `./gradlew :app:assembleRelease` on CI for every merge to the release branch —
  R8-only failures must never be discovered by hand a day before release.

### Keep it debuggable

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

Debuggable builds bypass R8 entirely — never validate release behavior on them.

---

## Prevention

- Every module that uses reflection/serialization declares its `consumer-rules.pro`
  (see [../shared/gradle.md](../shared/gradle.md))
- Release smoke test on a real device in the release checklist
- Crash reporter verifies mapping upload in CI; missing mapping = failed release
- New reflection usage (Room entities, serialization, Hilt metas) re-audited against
  shrink rules in the same PR

---

## Related

- [../backend/network.md](../backend/network.md) — serialization rules that R8
  interacts with
- [room-schema-migrations.md](./room-schema-migrations.md) — the other "works in
  debug, dies in prod" classic

## Scenario: Kotlin metadata compatibility gate

### 1. Scope / Trigger

- Re-evaluate this contract whenever Kotlin, AGP, or an explicit R8 override changes.

### 2. Signatures

- Build gate: `./gradlew :app:assembleRelease`.
- Compatibility input: Kotlin compiler version -> minimum R8 version from Android's official table.

### 3. Contracts

- The effective R8 version must meet the Kotlin metadata minimum; AGP version alone is not proof when the bundled shrinker is older.
- Keep an explicit override in one root build location only when required, and pin its artifacts in dependency verification metadata.
- CI captures release output and fails on Kotlin metadata parse/version warnings.

### 4. Validation & Error Matrix

- Release task fails -> CI failure.
- Kotlin metadata parse/version warning -> CI failure even if Gradle exits zero.
- New unverified R8 artifact -> dependency-verification failure until its trusted SHA-256 is reviewed and recorded.

### 5. Good/Base/Bad Cases

- Good: the release artifact is minified with a compatible, verified R8 and the log contains no metadata warning.
- Base: no override when AGP already bundles a compatible version.
- Bad: suppress the warning, validate only debug builds, or infer R8 compatibility from the AGP number.

### 6. Tests Required

- Print or inspect the effective R8 version.
- Run the full minified release build locally and in CI.
- Search the captured log for known Kotlin metadata incompatibility messages.
- Preserve a real ARM64 install/launch smoke test in the release checklist.

### 7. Wrong vs Correct

```text
Wrong: assembleDebug passes, so release is assumed safe.
Correct: assembleRelease runs R8, its log is checked, and the ARM64 artifact gets a device smoke test before release.
```
