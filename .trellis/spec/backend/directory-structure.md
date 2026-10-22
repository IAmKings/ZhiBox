# Backend Directory Structure

> Core module layout, purity boundaries, and Gradle dependency hygiene for data/domain code.

---

## Core Modules

```
:core:model        # pure Kotlin domain models + Result type. NO dependencies.
:core:common       # dispatchers, Clock, logging ports, small utils. Pure Kotlin.
:core:database     # Room/SQLDelight: schema, entities, DAOs, data sources
:core:network      # Retrofit/Ktor: APIs, DTOs, interceptors
:core:data         # repositories: aggregate database + network, map to domain
:core:domain       # (optional) use cases when business logic outgrows repositories
```

TODO(spec): prune modules the project does not use; record the final module list here.

---

## Purity Rules

1. **`:core:model` and `:core:common` are JVM-pure** — no `android.*`, no Compose, no
   framework imports. This keeps them unit-testable and (future) KMP-shareable.
2. **`android.*` appears only at the edges**: `:core:database` (Room runtime),
   `:core:network` (only if using Android-specific transports), `:app` (wiring).
3. **Dependency direction is one-way**:

```
:feature:* / :app  ->  :core:data  ->  :core:database / :core:network
                                 ->  :core:model
        all  ->  :core:common
```

   A cycle between core modules is a design bug; fix the boundary, not the import.

4. **`implementation` by default.** `api` only for types that genuinely appear in the
   module's public API (e.g. `:core:data` exposes `:core:model` types).
5. **Package by capability, then feature** inside core modules:
   `com.example.app.core.data/home/HomeRepository.kt`.

---

## File Naming

| Concern                    | Convention                       |
| -------------------------- | -------------------------------- |
| Repository interface       | `HomeRepository` (in `:core:data`) |
| Remote data source         | `HomeRemoteDataSource`           |
| Local data source          | `HomeLocalDataSource`            |
| DTO                        | `HomeDto` (in `:core:network`)   |
| DB entity                  | `HomeEntity` (in `:core:database`) |
| Domain model               | `HomeItem` (in `:core:model`)    |
| Mapper                     | `HomeMappers.kt` (top-level fns) |

---

## Anti-Patterns

- **Domain in the database module** — business rules inside DAOs or entities.
- **`Context` in repositories** — Android dependencies in data logic; inject what is
  needed as a pure abstraction from `:core:common`.
- **Convenience re-export layering** — `:core:data` exposing DTO types "temporarily".
- **Utils dumping ground** — `:core:common` growing a `Util` of unrelated statics;
  split by capability (`time/`, `log/`, `result/`).
