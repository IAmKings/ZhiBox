# Coroutine Scope Leaks

> **Severity**: P1 — work that outlives its owner (leak, duplicate requests) or dies
> with it (silent no-op features).
>
> Symptom: a request fires twice after navigating back and forth; or a background sync
> never runs; or `JobCancellationException` in the wild with no user impact.

---

## Cause

1. **Wrong owner**: work launched on a scope that dies early (composition, ViewModel
   cleared) or lives forever (`GlobalScope`).
2. **Orphaned `async`** without await/cancel.
3. **`init {}` launches** that keep running after the object should stop.
4. **Catch without rethrowing `CancellationException`** — cancellation treated as an
   error, breaking structured shutdown.

---

## Fix Patterns

### Pick the scope by lifetime

| Work lifetime                        | Owner                                      |
| ------------------------------------ | ------------------------------------------ |
| While the screen is visible          | ViewModel, collected lifecycle-aware       |
| While the ViewModel lives            | `viewModelScope.launch`                    |
| App-level (sync, prefetch)           | injected application `CoroutineScope` (Hilt `@ApplicationScope`) |
| Never: "runs even if app closes"     | `WorkManager`, not coroutines              |

```kotlin
@Provides @Singleton
fun appScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + io)
```

A supervisor scope so one failed child does not cancel siblings — and every feature
launching on it is still cancelable by the process, by design.

### Rethrow cancellation

```kotlin
try { remote.fetch() }
catch (e: CancellationException) { throw e }   // always
catch (e: IOException) { Result.failure(...) }
```

(See [../backend/error-handling.md](../backend/error-handling.md) rule 3.)

### Debounced/user-initiated work in UI

`rememberCoroutineScope` for callback-scope launches tied to composition lifetime —
never `GlobalScope.launch { }` in a composable.

---

## Prevention

- Architecture test: no `GlobalScope` in production source
  ([../backend/quality.md](../backend/quality.md))
- Every injected scope documents its `SupervisorJob` and dispatcher
- Tests assert cancellation: leaving a screen cancels its work
  ([../shared/testing.md](../shared/testing.md))

---

## Related

- [collectasstate-lifecycle.md](./collectasstate-lifecycle.md) — UI-side collection
  lifetime
- [../backend/concurrency.md](../backend/concurrency.md) — the rules this pitfall
  violates
