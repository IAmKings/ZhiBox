# Lifecycle-Aware Collection (`collectAsState` pitfalls)

> **Severity**: P1 — leaks, duplicate work, crashes from events after the UI is gone.
>
> Symptom: collection continues in background; a screen keeps firing network calls; or
> a crash when a collected event fires with a dead lifecycle.

---

## Cause

`collectAsState()` and `launchWhenStarted` keep collecting while the UI is **not
resumed**. Compose `collectAsState` stops only when the composable leaves composition —
a screen behind another (stopped activity instance in multi-window, or kept
composition) keeps flowing.

---

## Fix Patterns

### In composition — always the lifecycle-aware variant

```kotlin
// before
val state by viewModel.uiState.collectAsState()

// after
val state by viewModel.uiState.collectAsStateWithLifecycle()
```

Requires `androidx.lifecycle:lifecycle-runtime-compose`. It is in the template's
standard imports — a plain `collectAsState()` on a ViewModel flow is a review blocker.

### Outside composition — repeatOnLifecycle

```kotlin
lifecycleScope.launch {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.events.collect { /* ... */ }
    }
}
```

`launchWhenStarted` is deprecated for a reason: the block suspends but the coroutine
keeps consuming upstream. `repeatOnLifecycle` cancels properly.

### One-shot events

Collect in a `LaunchedEffect(Unit)` inside content that is actually on screen, with a
replay-0 channel upstream
([../frontend/state-management.md](../frontend/state-management.md)). A `StateFlow`
event flag re-fires on every configuration change — the "toast shows again on rotate"
classic.

---

## Prevention

- Lint/architecture test: no `collectAsState(` without `WithLifecycle` on ViewModel
  flows ([../backend/quality.md](../backend/quality.md))
- ViewModels never expose hot flows that "fire and forget" work on collection — work
  starts on explicit events, state reflects progress
- Screen-level tests cover: background → foreground → background, and back navigation

---

## Related

- [coroutine-scope-leaks.md](./coroutine-scope-leaks.md) — the non-UI half of the same
  disease
- [configuration-change.md](./configuration-change.md) — what survives the restart
  these bugs cause
