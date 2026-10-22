# Gradle

> Kotlin DSL, version catalogs, convention plugins. The build is code; review it like
> code.

---

## Structure

```
gradle/
├── libs.versions.toml        # single source of truth for versions
└── wrapper/
build-logic/                  # convention plugins (composite build)
├── convention-android-app
├── convention-android-library
├── convention-kotlin-jvm
└── convention-compose
```

1. **All versions live in `libs.versions.toml`** — no version literals inside module
   build files. Bumping a library is a one-line diff:

```toml
[versions]
kotlin = "2.0.21"
compose-bom = "2024.10.01"
room = "2.6.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
room = { id = "androidx.room", version.ref = "room" }
```
2. **Module build files declare what, not which-version**:

```kotlin
plugins {
    alias(libs.plugins.app.library.compose)
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.androidx.hilt.navigation.compose)
}
```

3. **Convention plugins carry shared config** (compile SDK, Kotlin options, compose
   flags, test defaults). A setting duplicated across three modules moves into
   `build-logic/`.
4. **Type-safe project accessors** (`projects.core.model`) over string notation.
5. **Kotlin DSL only** — no Groovy build scripts.

---

## Rules

1. **Dependency changes go through the catalog** and state the reason in the commit
   body (what for, why this version).
2. **`implementation` by default; `api` audited** (see
   [../backend/directory-structure.md](../backend/directory-structure.md)).
3. **No build logic in `settings.gradle.kts`** beyond repository and include
   declarations.
4. **Build variants** (if any) are declared explicitly per module; debug-only
   dependencies use the `debugImplementation` configuration.
5. **CI runs the same commands locally run**: `./gradlew build` (assemble + checks) and
   `./gradlew spotlessCheck detekt test` — no CI-only magic targets.
6. **Configuration cache and build cache stay enabled**; a change that breaks them is
   fixed, not worked around.

---

## Version Bumps

- Minor/patch bumps: batch monthly, one PR per area (compose, kotlin, libraries).
- Kotlin / compose-compiler bumps are atomic with any required AGP bump — never split
  across PRs.
- New major versions get a spike note in the PR description (migration cost observed).

---

## Anti-Patterns

- **Snapshot builds** committed to the catalog.
- **Copy-pasted block of 30 lines of config** across modules instead of a convention
  plugin.
- **`mavenLocal()`/`lastUpdated()` hacks** in CI.
- **R8 rules sprinkled per-module without `consumer-rules.pro`** — consumers break
  (see [../big-question/proguard-r8.md](../big-question/proguard-r8.md)).
