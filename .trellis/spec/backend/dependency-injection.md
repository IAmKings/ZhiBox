# Dependency Injection

> Hilt on Android; Koin acceptable in KMP-shared modules. Modules per layer, test
> doubles by binding.

TODO(spec): confirm Hilt vs Koin for this project.

---

## Layout

```
:app           AppModule      — app-wide singletons (DB, Json, Clock, logging)
:core:database DatabaseModule — AppDatabase + DAOs
:core:network  NetworkModule  — HttpClient/Retrofit, OkHttp, Json, APIs
:core:data     DataModule     — @Binds repository interfaces to implementations
```

---

## Rules

1. **Interfaces bound to implementations, not `@Provides`-constructed** where the type
   is project code:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindHomeRepository(impl: OfflineFirstHomeRepository): HomeRepository
}
```

2. **Scope deliberately.** `@Singleton` only for genuinely shared expensive objects
   (DB, HTTP client, Json). Everything else is unscoped — accidental singletons are
   memory leaks with extra steps.
3. **Qualifiers for ambiguous types**:

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Provides fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
```

   Inject `@IoDispatcher CoroutineDispatcher` instead of touching `Dispatchers.IO`
   inside classes (see [concurrency.md](./concurrency.md)).
4. **`Clock` is injected** (`@Provides fun clock(): Clock = Clock.systemUTC()`), never
   `Clock.systemDefaultZone()` inline — tests freeze it (see
   [../shared/timestamp.md](../shared/timestamp.md)).
5. **No service locator.** `ObjectGraph`-style globals and `lateinit var` holders are
   banned; everything is constructor-injected.
6. **Assisted injection for runtime parameters** (e.g. per-item workers) instead of
   passing factories of half-built objects.
7. **ViewModels get `@HiltViewModel` + `@Inject constructor`** and receive only
   interfaces they actually use — no "inject the graph and fish".

---

## Test Doubles

- Bind fakes in a `TestModule` (`FakeHomeRepository`) — fakes with in-memory state beat
  mocks for repository behavior tests.
- ViewModels are tested by constructing with fakes directly (no Hilt needed).
- UI tests swap bindings via `@HiltAndroidTest` + `ReplacementModule` only when the
  component boundary requires it.

---

## Anti-Patterns

- **`@Provides` factories that `new` project classes** — prefer `@Binds`; construction
  belongs to the constructor.
- **Injecting the Activity/Application into domain code** — Android context propagating
  upward is the classic purity leak (see [directory-structure.md](./directory-structure.md)).
- **Module per tiny class** — modules group by layer/capability, not per type.
- **Optional dependencies with `lateinit`** — make the dependency required or model the
  option explicitly.
