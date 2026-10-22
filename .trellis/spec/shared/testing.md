# Testing

> Test the failure paths first. Fakes over mocks for state, mocks over fakes for
> protocols.

TODO(spec): screenshot tooling (Roborazzi vs Paparazzi) and library versions.

---

## Pyramid

| Level            | What lives here                                   | Where it runs        |
| ---------------- | ------------------------------------------------- | -------------------- |
| Unit (JVM)       | Repositories, mappers, ViewModels, DAO logic      | `test` source set    |
| DB (JVM/instr.)  | Room round-trips, migrations (MigrationTestHelper)| androidTest / JVM    |
| UI (Compose)     | Stateless screen rendering + interaction via `createComposeRule` | JVM Robolectric |
| Device integration | Room migration, Keystore, native runtime, real Activity recreation | androidTest |
| Screenshot       | Golden images per state (loading/loaded/error)    | CI                   |
| Manual           | Performance, real network, R8 build               | before release       |

**Unit tests are the default.** Anything testable without instrumentation must not
become an instrumented test.

- Portable Compose tests live in `src/test`, use Robolectric native graphics, and
  run in the default `testDebugUnitTest` CI gate.
- Instrumentation tests that perform evaluation, performance, network, or OEM-sensitive
  Activity recreation are `@LargeTest` and opt in; the default device suite excludes them.
- Every default instrumentation test has a runner timeout, and the Gradle device task has a
  host timeout, so one blocked Android API cannot hold `connectedDebugAndroidTest` indefinitely.

---

## ViewModel Test Example

```kotlin
class HomeViewModelTest {

    private val repository = FakeHomeRepository()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh emits Loading then Loaded when repository succeeds`() = runTest {
        repository.items = listOf(HomeItemFakes.featured)
        val viewModel = HomeViewModel(repository)

        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val loaded = awaitItem() as HomeUiState.Loaded
            assertEquals(1, loaded.items.size)
            assertEquals("¥199", loaded.items.first().priceText)   // formatted in mapper
        }
    }

    @Test
    fun `refresh emits Error when repository fails`() = runTest {
        repository.error = AppError.Network
        val viewModel = HomeViewModel(repository)
        viewModel.refresh()

        viewModel.uiState.test {
            assertEquals(HomeUiState.Error(UiText.Res(R.string.error_offline)), awaitItem())
        }
    }
}
```

The `FakeHomeRepository` is shared state, not behavior mocks:

```kotlin
class FakeHomeRepository : HomeRepository {
    var items: List<HomeItem> = emptyList()
    var error: AppError? = null

    override suspend fun loadHome(): AppResult<List<HomeItem>> =
        error?.let { AppResult.Failure(it) } ?: AppResult.Success(items)

    override fun observeHome(): Flow<List<HomeItem>> =
        MutableStateFlow(items)
}
```

---

## Repository Test Example (failure branches)

```kotlin
@Test
fun `getItem falls back to cache when network fails`() = runTest {
    val remote = FakeHomeRemoteDataSource(throwOnFetch = true)
    val local = HomeLocalDataSource(inMemoryDb.dao)         // real Room, in-memory
    local.upsert(HomeEntity(id = 1, title = "cached", ...))

    val result = OfflineFirstHomeRepository(local, remote, errorMapper, testIo).getItem(HomeItemId(1))

    assertEquals("cached", result.getOrThrow().title)
}
```

---

## Naming and Structure

- Backtick sentences: `subject` + condition + outcome.
- **given / when / then** with comments for anything non-trivial.
- One behavior per test; a test asserting three outcomes is three tests.

---

## Fakes vs Mocks

| Dependency type            | Prefer   | Example                              |
| -------------------------- | -------- | ------------------------------------ |
| Repository / data source   | Fake     | `FakeHomeRepository` with in-memory state |
| Clock / dispatchers        | Fake     | `TestDispatcher`, fixed `Clock`      |
| One-method interface       | Fake/lambda |                                  |
| HTTP boundary              | Mock server | MockWebServer / Ktor MockEngine  |

- Fakes live in a shared `test-fixtures` source set so modules reuse them.
- `mockk` relaxed mocks hide behavior bugs; use `answer`-free strict mocks sparingly.

---

## Coroutines Testing Rules

1. `runTest` everywhere; inject `StandardTestDispatcher` via the DI qualifiers (see
   [../backend/concurrency.md](../backend/concurrency.md)).
2. `Turbine` for Flow assertions (`uiState.test { ... }`).
3. Virtual time for debounce/delay logic — no `Thread.sleep`, ever.
4. Test cancellation explicitly where it matters (leaving a screen cancels work).

---

## What Must Be Tested

- [ ] Every repository method: success branch + each `AppError` branch
- [ ] Mappers: DTO/entity/domain round-trip + unknown enum value tolerance
- [ ] ViewModels: each public event's state transition
- [ ] Schema migrations (see [../backend/database.md](../backend/database.md))
- [ ] Navigation: start destination + one path per deep link
- [ ] Regression tests for every `big-question` bug fixed
      ([../big-question/index.md](../big-question/index.md))

---

## Anti-Patterns

- **Coverage theater** — trivial getters green while error branches are red.
- **Instrumented-only tests for pure logic** — slow CI for no reason.
- **Tests asserting implementation details** — internal call counts; assert observable
  state.
- **Flaky tests tolerated** — quarantine same-day, fix or delete within the sprint.
