# Compose Recomposition

> **Severity**: P2 — jank, wasted work, "why is this rebuilding everything" bugs.
>
> Symptom: scrolling stutters or a whole screen recomposes when one element changes.

---

## Cause

Compose skips recomposition only when inputs are **stable and equal**. Instability
comes from:

1. `List`/`Map`/`data class` fields of unstable types inside state
2. Lambdas created in composition capturing unstable values
3. `var` public properties or untyped (`Any`) parameters
4. `Context`, `Application`, and other framework types as parameters

---

## Diagnosis

1. Enable compiler metrics
   ([../frontend/quality.md](../frontend/quality.md)) and read the report: look for
   `unstable` on your `UiState` and hoisted lambdas marked restartable-and-not-skippable.
2. Layout Inspector → Composition Counts, or recomposition highlighter during a scroll.

---

## Fix Patterns

### Make state stable

```kotlin
// before: unstable List<HomeItem> — every emission skips nothing
data class HomeUiState(val items: List<HomeItem>)

// after: ImmutableList → skippable
data class HomeUiState(val items: ImmutableList<HomeItem>)
```

### Stabilize lambdas in hot paths

```kotlin
// before: new lambda every recomposition
LazyColumn { items(items) { HomeCard(it, onClick = { onOpen(it.id) }) } }

// after: stable reference or remembered closure
val openDetail = remember(onOpen) { { item: HomeItem -> onOpen(item.id) } }
```

Method references (`viewModel::refresh`) are already stable — prefer them.

### Narrow the reads

Read `state.header` inside the header composable, not at the screen root, so header
changes do not recompose the list:

```kotlin
Header(state.header)                  // reads only what it needs
ItemsList(state.items, onOpen)
```

### Use keys and content types in lists

```kotlin
items(items, key = { it.id }, contentType = { it.type })
```

Index keys turn item removal into full-range recomposition plus state mix-ups.

### derivedStateOf for scroll math

```kotlin
val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
```

---

## Prevention

- `ImmutableList` for every collection in `UiState` (see
  [../frontend/type-safety.md](../frontend/type-safety.md))
- Stability config file for third-party unstable types
  (TODO(spec): add the project's stability config path)
- Compose metrics reviewed once per milestone — unstable classes fixed, not annotated
  away with `@Stable` lies

---

## Anti-Patterns

- **`@Stable` as a mute button** — annotating an actually-mutable class hides the
  problem until it produces wrong-UI bugs.
- **Whole-state hoisting** — passing `state: HomeUiState` deep into leaf rows, forcing
  every row to recompose on any field change.
