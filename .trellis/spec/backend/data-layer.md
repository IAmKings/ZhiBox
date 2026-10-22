# Data Layer

> Repository pattern: single source of truth, explicit mapping, no layer leakage.

---

## Architecture

```
ViewModel ──> Repository ──> LocalDataSource (Room/SQLDelight)
                        └──> RemoteDataSource (Retrofit/Ktor)
```

- **The repository owns the data contract.** UI and domain code see
  `HomeRepository`; nothing else sees DAOs or API services.
- **The local database is the single source of truth.** Remote responses are written
  to the DB; UI observes the DB. "Fetch-then-observe", never "fetch-and-return".

---

## Repository Skeleton

```kotlin
interface HomeRepository {
    fun observeHome(): Flow<List<HomeItem>>       // reactive UI data
    suspend fun refresh(): Result<Unit>           // one-shot sync
    suspend fun getItem(id: HomeItemId): Result<HomeItem>
}

class OfflineFirstHomeRepository @Inject constructor(
    private val local: HomeLocalDataSource,
    private val remote: HomeRemoteDataSource,
    private val errorMapper: NetworkErrorMapper,   // maps IO/HTTP -> AppError
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HomeRepository {

    override fun observeHome(): Flow<List<HomeItem>> =
        local.observeAll()
            .map { entities -> entities.map(HomeEntity::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun refresh(): Result<Unit> = withContext(ioDispatcher) {
        runCatching { remote.fetchHome() }
            .mapCatching { dtos -> local.upsertAll(dtos.map(HomeDto::toEntity)) }
            .recoverCatching { error -> throw errorMapper.toAppException(error) }
    }
}
```

---

## Data Source Examples

Data sources are thin, typed wrappers over their transport — no policy here:

```kotlin
// HomeLocalDataSource.kt
class HomeLocalDataSource @Inject constructor(private val dao: HomeDao) {
    fun observeAll(): Flow<List<HomeEntity>> = dao.observeAll()
    suspend fun upsertAll(entities: List<HomeEntity>) = dao.upsertAll(entities)
    suspend fun findById(id: Long): HomeEntity? = dao.findById(id)
}

// HomeRemoteDataSource.kt
class HomeRemoteDataSource @Inject constructor(private val api: HomeApi) {
    suspend fun fetchHome(): List<HomeDto> = api.fetchHome()
}
```

---

## Rules

1. **Map at every boundary.** DTO → entity at the network edge, entity → domain at the
   repository edge. Domain models never come from DAOs or API responses directly.
2. **`Result` crossings.** Suspending repository methods return `Result<T>` (or a
   domain-specific sealed result); they do not throw into ViewModels — see
   [error-handling.md](./error-handling.md).
3. **Flows for reactive data** (`observe*`), suspend functions for one-shots
   (`refresh`, `save`). A repository API mixing callbacks or Rx is rejected.
4. **Flow composition uses `catch`/`retryWhen` deliberately** — an unclosed flow that
   throws tears down UI collection. Handle upstream errors where the repository knows
   the recovery strategy:

```kotlin
override fun observeHome(): Flow<List<HomeItem>> =
    local.observeAll()
        .catch { e -> logger.w(TAG, "home observe failed", e); emit(emptyList()) }
        .map { entities -> entities.map(HomeEntity::toDomain) }
        .flowOn(ioDispatcher)
```

5. **No UI/Android types in repositories** — no `Context`, no Compose, no `Toast`.
   User-facing wording for errors is produced as a typed error, rendered by UI.
6. **One repository per aggregate** (`HomeRepository`), not per table. If two
   repositories need the same join, the higher-level one may depend on the lower —
   keep the graph acyclic.
7. **Pagination**: expose `PagingData` via the Paging library only when lists are
   large; otherwise explicit `page`/`cursor` parameters in suspend functions.
   TODO(spec): record the project's pagination approach if any.

---

## Mapping Conventions

```kotlin
// HomeMappers.kt — top-level extension functions, colocated per type pair
fun HomeDto.toEntity(): HomeEntity = HomeEntity(
    id = id,
    title = title.orEmpty(),
    updatedAt = updatedAt,          // epoch millis, see ../shared/timestamp.md
)

fun HomeEntity.toDomain(): HomeItem = HomeItem(
    id = HomeItemId(id),
    title = title,
)
```

- Mappers are pure, tested, and boring — no network calls, no `Date()` inside.
- Unknown enum values map to an explicit `UNKNOWN` case, never to crash-or-null:

```kotlin
enum class HomeStatus { ACTIVE, ARCHIVED, UNKNOWN;

    companion object {
        fun fromWire(raw: String?): HomeStatus =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
```

---

## Anti-Patterns

- **Pass-through repository** — every method forwards to a DAO with zero added value;
  merge the layers instead.
- **Network-first UI** — showing only fetched data with no DB fallback; offline state
  becomes a crash-prone afterthought.
- **Business logic in DAOs** — SQL should not decide pricing.
- **Leaking `Response<T>`/`Call<T>`** above the data layer.

## Scenario: Candidate eligibility and committed-restore warnings

### 1. Scope / Trigger

- Use this contract when a retrieval API adds source/document eligibility, or when a restore performs derived work after its database transaction commits.

### 2. Signatures

- Scoped retrieval: `search(query, topK, sourceIds: List<String>)`.
- Restore result: `Result<ImportOutcome>`, where `ImportOutcome(restoredCount, warning)` distinguishes committed data from derived-index health.

### 3. Contracts

- Eligibility is applied in every retrieval channel before candidate limits and fusion `topK`.
- An empty `sourceIds` list preserves the unscoped ranking path.
- Validation, snapshot, and database replacement failures return `Result.failure`.
- Work that fails after the replacement transaction commits returns success with a typed warning; it must not report that the restore rolled back.

### 4. Validation & Error Matrix

- Invalid archive or failed pre-import snapshot -> failure, live database unchanged.
- Database transaction failure -> failure, transaction rolled back.
- Derived FTS rebuild failure after commit -> success plus `SAG_FTS_REBUILD_FAILED`.
- Coroutine cancellation -> rethrow cancellation; never convert it to a warning.

### 5. Good/Base/Bad Cases

- Good: a low-ranked in-scope chunk remains eligible even when global top results belong to another source.
- Base: an empty source scope produces the existing global result order.
- Bad: filter fused hits after `take(topK)`, or throw a total import failure after restored rows are committed.

### 6. Tests Required

- Unit-test matrix ranking with more than `topK` higher-scored out-of-scope rows.
- Room-test scoped FTS with the in-scope row inserted after out-of-scope rows and `limit = 1`.
- Force derived-index rebuilding to fail and assert restored rows plus the typed warning.
- Assert pre-commit failures do not change the live database.

### 7. Wrong vs Correct

```kotlin
// Wrong: eligibility is too late and can starve the requested scope.
val hits = fuse(vector, keyword).take(topK).filter { it.sourceId in sourceIds }

// Correct: each channel is scoped first; fusion only sees eligible candidates.
val hits = fuse(vector.filterEligible(sourceIds), keyword.filterEligible(sourceIds)).take(topK)
```
