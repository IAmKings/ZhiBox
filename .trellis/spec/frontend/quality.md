# Frontend Quality

> Recomposition hygiene, tooling, and the UI-side checklist before committing.

---

## Recomposition Hygiene

1. **Stable parameters.** State classes use immutable collections and value classes;
   lambdas are `remember`ed or method references in hot paths.
2. **Stable `LazyList` keys.** Every `items(...)` call passes a stable unique `key = {
   it.id }`; index keys silently break animations and state retention:

```kotlin
LazyColumn {
    items(
        items = state.items,
        key = { it.id.value },            // stable: survives reorder/insert/delete
        contentType = { it.contentType }, // enables view recycling across types
    ) { item -> HomeCard(item, onClick = { onOpen(item.id) }) }
}
```
3. **Narrow state reads.** Read `state.foo` inside the smallest composable that uses
   it; hoist whole-object reads only when the object is truly small and stable.
4. **`derivedStateOf` for computed booleans** read during scroll (e.g.
   `listState.firstVisibleItemIndex > 0`) — not a `remember` recalculated every frame.
5. **Defer reads** with lambdas (`contentAlignment: (Int) -> Alignment`, offset-based
   graphicsLayer) only after measurements show a need; do not pre-optimize.
6. **Run Compose compiler metrics** at least once per milestone and fix the reported
   unstable `UiState` classes:

```kotlin
// gradle.properties (enable during audits, not permanently noisy builds)
kotlin.options.freeCompilerArgs +=
    listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
        layout.buildDirectory.get().dir("compose_reports").asFile.absolutePath)
```

TODO(spec): adopt the project's chosen metrics setup (Compose compiler metrics or the
Compose Compiler Gradle plugin stability config file).

---

## Tooling Baseline

| Concern            | Expectation                                                  |
| ------------------ | ------------------------------------------------------------ |
| Lint               | Android Lint + Compose lint checks pass on CI                |
| Style              | ktlint/spotless clean (see [../shared/code-quality.md](../shared/code-quality.md)) |
| UI tests           | Critical flows have Compose UI tests (`createComposeRule`)   |
| Screenshots        | Screens render golden-identical in CI (Roborazzi/Paparazzi — pick one, TODO(spec)) |
| Accessibility      | Touch targets ≥ 48dp, contentDescription, contrast ≥ 4.5:1   |

---

## UI Code Review Checklist

- [ ] `*Screen` is thin; rendering lives in stateless `*Content`
- [ ] `collectAsStateWithLifecycle()` used
- [ ] No domain entities in UI state
- [ ] Previews exist for loading / loaded / error / empty
- [ ] Dark theme preview passes
- [ ] No hard-coded colors, dp outside scale, or string literals
- [ ] `LazyList` items have stable keys
- [ ] Back / process-death behavior verified (`rememberSaveable` / `SavedStateHandle`)
- [ ] New reusable widgets landed in `:core:ui` or stayed screen-local deliberately

---

## Anti-Patterns

- **Testing theater** — one `setContent { Text("hi") }` test claimed as UI coverage.
- **Debug overlays left in** — borders, recomposition counters, log spam committed.
- **`Modifier.fillMaxSize()` on everything** by reflex; give layouts intended
  constraints.
- **Preview-only styling** — visuals that exist in `@Preview` but diverge at runtime
  because they bypass the design system.
