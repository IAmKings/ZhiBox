# Concurrency

> Structured coroutines, injected dispatchers, Flow discipline. No fire-and-forget.

---

## Dispatchers

1. **Dispatchers are injected** via qualifiers (`@IoDispatcher`, `@DefaultDispatcher`)
   — production code never references `Dispatchers.*` directly, so tests can control
   scheduling (see [dependency-injection.md](./dependency-injection.md)).
2. **Choose by workload**: `IO` for disk/network, `Default` for CPU, `Main` only inside
   presentation (ViewModel/Compose). Repositories switch explicitly with
   `withContext(ioDispatcher)` around blocking/IO work.
3. **No `Dispatchers.Main.immediate` assumptions in domain code.**

---

## Structured Concurrency

1. **Every coroutine has an owner**: `viewModelScope` for UI, an injected
   application-scoped `CoroutineScope` for app-level work (sync, prefetch). `GlobalScope`
   is banned in production code.
2. **No `runBlocking`** on the main path; in tests use `runTest`.
3. **`launch` fire-and-forget needs a reason written next to it** — and a scope that
   will cancel it. Prefer returning a `Deferred`/`Flow` when the result matters.
4. **`async` requires either await or cancellation handling** — orphaned `async` leaks
   work.
5. **Cancellation is cooperative**: long loops check `ensureActive()`/`yield()`;
   blocking calls inside coroutines are wrapped and interruptible where possible.

---

## Flow Rules

1. **Cold flows do work on collection.** Build with `flow { }` /
   `channelFlow { }` and switch context with `flowOn(ioDispatcher)` — not
   `withContext` inside the builder body.
2. **Terminal collection happens in the right lifecycle** — composition uses
   `collectAsStateWithLifecycle()`, background collection uses `repeatOnLifecycle`
   (see [../frontend/state-management.md](../frontend/state-management.md)).
3. **State holders expose `StateFlow`, one-shot channels expose `SharedFlow`/`Channel`
   with replay 0** (see the UI-state pattern in
   [../frontend/state-management.md](../frontend/state-management.md)).
4. **`catch` before terminal operators**; `retryWhen` with backoff for transient
   network flows:

```kotlin
repository.observeHome()
    .catch { emit(emptyList()) }          // explicit degradation, logged
    .flowOn(ioDispatcher)
```

5. **Combine, don't chain-launch.** Parallel work is `coroutineScope { val a = async
   {}; ... }`, sequential dependencies are plain awaits — not nested launches:

```kotlin
// parallel independent fetches — structured, both cancel together
suspend fun loadDashboard(): Dashboard = coroutineScope {
    val home = async(ioDispatcher) { remote.fetchHome() }
    val profile = async(ioDispatcher) { remote.fetchProfile() }
    Dashboard(home.await().toDomain(), profile.await().toDomain())
}
```

6. **`stateIn`/`shareIn` need explicit scope + `started` policy** (`WhileSubscribed(5_000)`
   for UI-bound state), and the shared flow's upstream is cancel-safe.

---

## Timers and Delays

| Need                        | Use                                        |
| --------------------------- | ------------------------------------------ |
| Debounce user input         | `.debounce(300)` on the flow               |
| Polling                     | `flow { while(true) { emit(fetch()); delay(d) } }.flowOn(...)` |
| One-shot delay in UI        | `LaunchedEffect { delay(...) }`            |
| Test time                   | `runTest` + virtual time                   |

`Thread.sleep`/`Handler.postDelayed` in coroutine code is banned.

---

## Anti-Patterns

- **Coroutine leaks** — launching in `init` on a scope nobody cancels (see
  [../big-question/coroutine-scope-leaks.md](../big-question/coroutine-scope-leaks.md)).
- **`withContext(Dispatchers.IO)` hardcoded** — untestable and wrong in KMP.
- **Blocking the main thread** — Room without suspend, JSON on `Dispatchers.Main`.
- **Exception-eating flows** — `.catch { }` with an empty body; degradation must be
  explicit and observable.
