# Backend Quality

> Layer-boundary checks, static analysis, and CI gates for non-UI code.

---

## Static Analysis Baseline

| Tool                | Role                                             |
| ------------------- | ------------------------------------------------ |
| ktlint / spotless   | Formatting + style, enforced on CI               |
| detekt              | Complexity, smells (coroutines rules enabled)    |
| Android Lint        | Platform issues; treat errors as failures        |
| Konsist (or similar)| Architecture tests for module/package boundaries |

TODO(spec): record chosen tool versions and where the config lives (root
`detekt.yml`, `config/`, etc.).

---

## Architecture Tests

Boundary rules are code, not folklore. Example Konsist checks:

```kotlin
@Test
fun `core model is android-free`() {
    SourceSet("core:model")
        .files.ShouldNotImport("android.", "androidx.")
}

@Test
fun `features do not import features`() {
    SourceSet("feature")
        .files.shouldNotImport("..feature.")
}
```

Minimum set for this template:

- [ ] `:core:model` / `:core:common` import no `android.*` / `androidx.*`
- [ ] No `:feature:*` imports another `:feature:*`
- [ ] No production code references `Dispatchers.` directly (use injected qualifiers)
- [ ] No production code references `GlobalScope`
- [ ] DTO types do not appear outside `:core:network` / `:core:data`

---

## Unit Test Gates

| Layer           | Required coverage                                           |
| --------------- | ----------------------------------------------------------- |
| Repositories    | success + each `AppError` branch per public method          |
| Mappers         | round-trip + unknown-value tolerance tests                  |
| DAOs            | in-memory DB round-trips for non-trivial queries            |
| ViewModels      | state transitions per user event (Turbine on `uiState`)     |
| Error mapping   | network/DB failure → `AppError` mapping table               |

Run with `./gradlew :core:data:testDebugUnitTest` etc.; CI fails on any red module.

---

## CI Checklist (data/backend side)

- [ ] `spotlessCheck` / `detekt` pass
- [ ] Architecture tests pass
- [ ] Unit tests pass on JVM module matrix (per-module `test`)
- [ ] `lint` has no new errors
- [ ] Schema/`.sqm` changes ship with migration tests
      ([database.md](./database.md))

---

## Anti-Patterns

- **`todo()` stubs merged to main** — track in the issue, not the code.
- **Suppressed warnings without justification** — `@Suppress` requires a comment
  naming the reason and the review that accepted it.
- **Coverage theater** — testing getters while error branches stay red; test the
  failure paths first.
