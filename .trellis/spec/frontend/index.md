# Frontend Development Guidelines Index

> **Tech Stack**: Jetpack Compose + Material 3 + Navigation Compose + ViewModel/StateFlow
>
> **Scope**: UI-layer modules — `:app`, `:feature:*`, `:core:ui`, `:core:designsystem`

## Related Guidelines

| Guideline                  | Location     | When to Read                 |
| -------------------------- | ------------ | ---------------------------- |
| **Shared Code Standards**  | `../shared/` | Always — applies to all code |
| **Data Layer Contracts**   | `../backend/data-layer.md` | When wiring UI to repositories |

---

## Documentation Files

| File                                              | Description                                   | When to Read                       |
| ------------------------------------------------- | --------------------------------------------- | ---------------------------------- |
| [directory-structure.md](./directory-structure.md) | Gradle module layout and dependency rules     | Creating a module or feature       |
| [components.md](./components.md)                   | Composable naming, API design, previews       | Writing any composable             |
| [state-management.md](./state-management.md)       | UiState, StateFlow, unidirectional data flow  | Building screens                   |
| [navigation.md](./navigation.md)                   | Type-safe routes, single-Activity NavHost     | Adding destinations                |
| [design-system.md](./design-system.md)             | Material 3 theming, tokens, resources         | Styling, colors, typography        |
| [type-safety.md](./type-safety.md)                 | Sealed UI models, value-class IDs             | Modeling UI state                  |
| [quality.md](./quality.md)                         | Recomposition hygiene, lint, metrics          | Before committing UI code          |

---

## Core Rules Summary

| Rule                                                            | Reference                                      |
| --------------------------------------------------------------- | ---------------------------------------------- |
| **Screens are thin**: collect state, delegate to stateless content | [components.md](./components.md)             |
| **One `UiState` per screen**, exposed as `StateFlow`            | [state-management.md](./state-management.md)   |
| **Events flow up, state flows down** (UDF)                      | [state-management.md](./state-management.md)   |
| **Collect with `collectAsStateWithLifecycle()`**                | [state-management.md](./state-management.md)   |
| **No domain entities in UI state** — map to UI models           | [type-safety.md](./type-safety.md)             |
| **No hard-coded colors or dp outside the design system**        | [design-system.md](./design-system.md)         |
| **Features never depend on each other**                         | [directory-structure.md](./directory-structure.md) |
| **User-facing strings via `stringResource`**, never literals    | [design-system.md](./design-system.md)         |
| **Modifier comes last** with `modifier: Modifier = Modifier`    | [components.md](./components.md)               |
| **Every reusable composable ships a `@Preview`**                | [components.md](./components.md)               |
| **Give `LazyList` items stable `key`s**                         | [quality.md](./quality.md)                     |

---

## Reference Files

| Concern            | Typical Location                     |
| ------------------ | ------------------------------------ |
| NavHost + routes   | `:app` or `:core:navigation`         |
| Theme tokens       | `:core:designsystem`                 |
| Shared UI widgets  | `:core:ui`                           |
| Screen + ViewModel | `:feature:{name}`                    |
| MainActivity       | `:app`                               |

---

**Language**: All documentation must be written in **English**.
