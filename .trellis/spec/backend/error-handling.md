# Error Handling

> Failures cross layers as values (`Result` / sealed errors), not exceptions. Nothing
> is swallowed silently.

---

## The Model

```kotlin
// :core:common
sealed interface AppError {
    data object Network : AppError
    data object NotFound : AppError
    data object Auth : AppError
    data class Conflict(val detail: String) : AppError
    data class Unexpected(val cause: Throwable) : AppError
}

class AppException(val error: AppError) : Exception(error.toString())
```

**Layer contract:**

| Layer            | May throw                    | Must return                  |
| ---------------- | ---------------------------- | ---------------------------- |
| Data sources     | yes (IO exceptions etc.)     | —                            |
| Repositories     | no (except truly fatal)      | `Result<T>` / typed error    |
| Use cases        | no                           | `Result<T>`                  |
| ViewModels       | no                           | maps error → `UiState.Error` |

---

## Full Repository Pattern

Failures are converted exactly once, in the repository — a ViewModel must never need
`try/catch` around repository calls:

```kotlin
class OfflineFirstHomeRepository @Inject constructor(
    private val local: HomeLocalDataSource,
    private val remote: HomeRemoteDataSource,
    private val errorMapper: NetworkErrorMapper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HomeRepository {

    override suspend fun getItem(id: HomeItemId): Result<HomeItem> =
        withContext(ioDispatcher) {
            runCatching { remote.fetchItem(id.value) }
                .onSuccess { local.upsert(it.toEntity()) }        // cache-first write
                .map { it.toDomain() }
                .recoverCatching { e ->
                    local.findById(id.value)?.toDomain()          // offline fallback
                        ?: throw errorMapper.toAppException(e)    // nothing cached
                }
        }
}
```

---

## Rules

1. **Repositories catch and wrap.** Data source exceptions are converted to `AppError`
   exactly once, in the repository — see the mapping table in
   [network.md](./network.md). A ViewModel must never need `try/catch` around
   repository calls.
2. **Never swallow.** `catch (e: Exception) {}` and `runCatching { }.getOrNull()`
   without handling lose data and time. Minimum: log with context, then wrap or
   rethrow.
3. **Catch narrow, rethrow fatal.** `catch (e: IOException)` — not `catch (e:
   Exception)` that also eats `CancellationException`. Always rethrow
   `CancellationException`:

```kotlin
try {
    remote.fetch()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Result.failure(AppException(AppError.Network))
}
```

4. **User-facing messages are typed, not strings, in the data layer.** UI renders
   `AppError` via a message catalog (resources); repositories never hard-code sentences.
5. **`require`/`check` for programmer errors** (invariants), `Result` for expected
   runtime failures. Do not use exceptions for control flow.
6. **Multi-write consistency**: wrap related DAO writes in a Room transaction and let a
   failure roll back the whole unit — silent partial writes are P1:

```kotlin
@Transaction
suspend fun transferOwnership(itemId: Long, newOwner: Long) {
    itemDao.updateOwner(itemId, newOwner)
    historyDao.insert(HistoryEntity(itemId, newOwner))
    // failure anywhere rolls back both writes
}
```

7. **Log at the boundary where the error is handled**, once, with context — not at
   every level of the stack (see [logging.md](./logging.md)).

---

## UI Rendering

```kotlin
when (val error = state.error) {
    AppError.Network -> EmptyState(stringResource(R.string.error_offline), retry = onRetry)
    AppError.Auth    -> ReAuthPrompt()
    is AppError.Conflict -> InlineMessage(error.detail)
    else             -> ErrorState(retry = onRetry)
}
```

- Every screen defines what its error states look like (full-screen, inline, toast) and
  that decision is previewed — see [../frontend/components.md](../frontend/components.md).

---

## Testing Anchor

Every repository method ships its failure branches as tests — success + each
`AppError` branch (see [../shared/testing.md](../shared/testing.md)). The mapping
table above is itself a test fixture: one test per row.

---

## Anti-Patterns

- **`!!` and `!!`-adjacent optimism** — force-unwraps that turn recoverable states into
  crashes (also see [../shared/kotlin.md](../shared/kotlin.md)).
- **Error → `null` → "empty"** — an offline failure rendered as "no data".
- **Sentinel values** — `-1` or `""` meaning failure; use types.
- **Crash-only thinking** — treating every failure as unrecoverable instead of
  designing the recovery path.
