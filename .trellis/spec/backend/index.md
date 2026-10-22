# Backend Development Guidelines Index

> **Tech Stack**: Kotlin coroutines/Flow + Room (or SQLDelight) + Retrofit/Ktor + Hilt
>
> **Scope**: non-UI layers — `:core:data`, `:core:domain`, `:core:network`,
> `:core:database`, `:core:common`. ("Backend" = everything below the ViewModel.)

## Related Guidelines

| Guideline                  | Location     | When to Read                 |
| -------------------------- | ------------ | ---------------------------- |
| **Shared Code Standards**  | `../shared/` | Always — applies to all code |
| **UI Contracts**           | `../frontend/state-management.md` | When exposing flows to ViewModels |

---

## Documentation Files

| File                                              | Description                                    | When to Read                       |
| ------------------------------------------------- | ---------------------------------------------- | ---------------------------------- |
| [directory-structure.md](./directory-structure.md) | Core module layout, JVM purity, api/implementation | Creating core modules         |
| [data-layer.md](./data-layer.md)                   | Repository pattern, data sources, mapping      | Implementing any data flow         |
| [database.md](./database.md)                       | Room/SQLDelight schema, migrations, tests      | Database work                      |
| [network.md](./network.md)                         | Retrofit/Ktor, serialization, error mapping    | API integration                    |
| [dependency-injection.md](./dependency-injection.md) | Hilt modules, scoping, test doubles         | Wiring anything                    |
| [error-handling.md](./error-handling.md)           | Result types, exception taxonomy               | Handling failures                  |
| [concurrency.md](./concurrency.md)                 | Dispatchers, structured concurrency, Flow      | Any async code                     |
| [logging.md](./logging.md)                         | Timber/Kermit conventions, PII rules           | Adding logs                        |
| [quality.md](./quality.md)                         | Boundaries checks, detekt, CI gates            | Before committing                  |

---

## Quick Navigation

| Task                              | File                                       |
| --------------------------------- | ------------------------------------------ |
| New repository / data source      | [data-layer.md](./data-layer.md)           |
| Domain model vs DTO vs entity     | [data-layer.md](./data-layer.md)           |
| Schema change / migration         | [database.md](./database.md), [../guides/](../guides/index.md) |
| New endpoint integration          | [network.md](./network.md)                 |
| Provide a dependency              | [dependency-injection.md](./dependency-injection.md) |
| Choose a dispatcher               | [concurrency.md](./concurrency.md)         |
| Model a failure                   | [error-handling.md](./error-handling.md)   |

---

## Core Rules Summary

| Rule                                                            | Reference                                      |
| --------------------------------------------------------------- | ---------------------------------------------- |
| **Repositories are the single source of truth** for UI data      | [data-layer.md](./data-layer.md)              |
| **DTOs/entities never escape the data layer** — map to domain    | [data-layer.md](./data-layer.md)              |
| **Inject dispatchers and Clock; never call them statically**     | [concurrency.md](./concurrency.md)            |
| **No `GlobalScope`, no `runBlocking` in production code**        | [concurrency.md](./concurrency.md)            |
| **Failures cross layers as `Result`, not exceptions**            | [error-handling.md](./error-handling.md)      |
| **Never swallow exceptions silently**                            | [error-handling.md](./error-handling.md)      |
| **Export Room schema and write migration tests**                 | [database.md](./database.md)                  |
| **`android.*` banned in `:core:model`/`:core:common`**           | [directory-structure.md](./directory-structure.md) |
| **No PII in logs**                                               | [logging.md](./logging.md)                    |

---

**Language**: All documentation must be written in **English**.
