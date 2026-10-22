# Frontend Directory Structure

> Gradle module layout for Compose UI code and the dependency rules between modules.

---

## Module Graph

```
:app                     # MainActivity, NavHost, DI wiring, app-level config
├── :feature:home        # one module per feature; self-contained
├── :feature:detail
└── :feature:settings

:core:ui                 # shared reusable composables (no feature logic)
:core:designsystem       # theme, colors, typography, shapes, spacing tokens
:core:model              # pure Kotlin domain models (JVM-pure)
:core:data               # repositories (aggregates data sources)
:core:database           # Room/SQLDelight implementation
:core:network            # Retrofit/Ktor implementation
:core:common             # dispatchers, Clock, Result types, logging
```

TODO(spec): rename/prune modules to match this project.

---

## Dependency Rules (enforced, not advisory)

| Module            | May depend on                                    | Must NOT depend on                        |
| ----------------- | ------------------------------------------------ | ----------------------------------------- |
| `:feature:*`      | `:core:ui`, `:core:designsystem`, `:core:model`, `:core:common`, DI | other `:feature:*` |
| `:core:ui`        | `:core:designsystem`, `:core:model`              | `:feature:*`, `:core:data`                |
| `:core:designsystem` | nothing internal (Compose + Material 3 only)  | everything internal                       |
| `:core:data`      | `:core:model`, `:core:database`, `:core:network`, `:core:common` | `:feature:*`, Compose    |
| `:core:model`     | nothing (pure Kotlin)                            | anything                                  |
| `:app`            | everything (composition root)                    | —                                         |

**Hard rules:**

1. **Features never depend on other features.** Shared code moves down into `:core:ui`
   or `:core:common`. If two features need the same code, that code is core code.
2. **`android.*` imports are forbidden outside UI modules.** `:core:model` and
   `:core:common` stay JVM-pure; `:core:data` may use Android only for platform glue.
3. **The composition root is `:app`.** Feature modules receive dependencies via DI and
   never reference each other's classes.
4. **Use `api` sparingly.** Depend with `implementation(...)`; `api(...)` only when the
   type genuinely appears in the module's public surface.
5. **Declare module boundaries with a dependency check** (custom Gradle check or
   Konsist test) so violations fail CI. See [../backend/quality.md](../backend/quality.md).

---

## Feature Module Internal Layout

```
:feature:home/src/main/kotlin/<pkg>/feature/home/
├── HomeScreen.kt            # route-level composable: collects ViewModel state
├── HomeViewModel.kt         # StateFlow<HomeUiState> + event handlers
├── HomeUiState.kt           # immutable UI model
├── HomeUiMapper.kt          # domain model -> UI model mapping
├── components/              # screen-specific composables
│   ├── HomeHeader.kt
│   └── HomeCard.kt
└── navigation/              # route definitions + graph extension
    └── HomeNavigation.kt
```

**Rules:**

- One screen per file section: keep `*Screen`, its ViewModel, and its `UiState` findable
  by filename prefix — never co-locate two features in one package.
- Screen-specific composables live in `components/`; promoted to `:core:ui` only when a
  second feature reuses them (see [../guides/code-reuse-thinking-guide.md](../guides/code-reuse-thinking-guide.md)).
- Navigation code lives in `navigation/` and exposes a single
  `fun NavGraphBuilder.homeGraph(...)` extension used by `:app`.

---

## Package Naming

| Element                  | Convention                       | Example                              |
| ------------------------ | -------------------------------- | ------------------------------------ |
| Feature package          | `<pkg>.feature.<name>`           | `com.example.app.feature.home`       |
| Core package             | `<pkg>.core.<capability>`        | `com.example.app.core.data`          |
| Composable file          | PascalCase matching the symbol   | `HomeCard.kt`                        |
| ViewModel file           | `<Screen>ViewModel.kt`           | `HomeViewModel.kt`                   |

---

## Anti-Patterns

- **God feature module** — one `:feature:all` with every screen; split by user journey.
- **Upward dependency** — `:core:*` referencing `:feature:*` classes.
- **Hidden coupling** — feature A importing feature B's ViewModel "just for this type";
  move the shared type to `:core:model` instead.
- **Android imports in domain code** — `Context` inside `:core:data` business logic.
