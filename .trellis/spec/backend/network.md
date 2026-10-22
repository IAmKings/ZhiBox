# Network

> Retrofit (Android) or Ktor client (KMP) + kotlinx.serialization. DTOs stay at the edge.

TODO(spec): pick Retrofit or Ktor client for this project and delete the other section.

---

## Retrofit Setup

```kotlin
interface HomeApi {
    @GET("v1/home")
    suspend fun fetchHome(): List<HomeDto>
}
```

1. **Suspend functions only** — no `Call`/`Observable` returns.
2. **One API interface per service area** (`HomeApi`, `ProfileApi`), provided by Hilt.
3. **Auth/headers via `Interceptor`** registered centrally; endpoints never attach
   tokens themselves:

```kotlin
class AuthInterceptor @Inject constructor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.current() ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${token.value}")
                .build(),
        )
    }
}
```

4. **Timeouts explicit** (connect/read/write) in the OkHttp builder; defaults are not a
   decision.

## Ktor Client Setup

1. **One shared `HttpClient`**, `ContentNegotiation` + `kotlinx.serialization` installed
   in `:core:network`; engines injected per platform.
2. **`HttpTimeout` explicit**; install `HttpRequestRetry` with idempotency awareness.

---

## Shared Json Instance

```kotlin
val AppJson = Json {
    ignoreUnknownKeys = true      // servers evolve; new fields must not crash the app
    coerceInputValues = true      // null into non-null defaults, unknown enum -> default
    explicitNulls = false
    encodeDefaults = true
}
```

---

## Serialization Rules (both stacks)

1. **`kotlinx.serialization` is the single JSON stack.** No Moshi/GSON mixing.
2. **DTOs are nullable-friendly**: `val title: String? = null`, unknown enum values map
   to `UNKNOWN` via `@SerialName` + a custom serializer or `fallback` enum entry.
3. **DTO → entity/domain mapping happens in `:core:network` / `:core:data`**; DTO types
   never appear above the data layer.
4. **R8**: keep rules for `@Serializable` classes ship in `consumer-rules.pro` of the
   module that declares them — see
   [../big-question/proguard-r8.md](../big-question/proguard-r8.md).
5. **Every endpoint integration ships a unit test** using a recorded JSON fixture
   (MockWebServer / Ktor MockEngine) asserting DTO parsing **including unknown-field
   tolerance**:

```kotlin
@Test
fun `parses payload with unknown fields and enum values`() {
    val payload = """[{"id":1,"title":"ok","mysteryField":true}]"""
    val dtos = AppJson.decodeFromString<List<HomeDto>>(payload)
    assertEquals(1, dtos.size)
    assertEquals(HomeStatus.UNKNOWN, dtos.first().status)   // unknown wire value
}
```

---

## Error Mapping

| HTTP / transport         | Mapped to (domain)          | UI sees            |
| ------------------------ | --------------------------- | ------------------ |
| 401 / 403                | `AppError.Auth`             | re-auth flow       |
| 404                      | `AppError.NotFound`         | empty state        |
| 409 / 422                | `AppError.Conflict(message)`| inline message     |
| 5xx                      | `AppError.Server`           | retry affordance   |
| IOException / timeout    | `AppError.Network`          | offline state      |
| SerializationException   | `AppError.Unexpected` + log | generic error      |

- Mapping lives in one place (`NetworkErrorMapper.kt`), not per endpoint:

```kotlin
class NetworkErrorMapper @Inject constructor() {
    fun toAppException(error: Throwable): Exception = when (error) {
        is CancellationException -> error            // never swallowed, rethrown as-is
        is HttpException -> when (error.code()) {
            401, 403 -> AppException(AppError.Auth)
            404 -> AppException(AppError.NotFound)
            409, 422 -> AppException(AppError.Conflict(parseDetail(error)))
            in 500..599 -> AppException(AppError.Server)
            else -> AppException(AppError.Unexpected(error))
        }
        is IOException -> AppException(AppError.Network)
        is SerializationException -> AppException(AppError.Unexpected(error))
        else -> AppException(AppError.Unexpected(error))
    }
}
```

---

## Rules

1. **No business logic in interceptors** beyond auth/headers/logging.
2. **No `@Volatile` caches inside API interfaces** — caching belongs to the repository
   or an explicit cache layer.
3. **API versioning in the path** (`v1/`), surfaced in the interface so upgrades are a
   diff, not a hunt.
4. **Retries respect idempotency** — blind retry on POST can double-submit; retry only
   idempotent verbs or attach idempotency keys.

---

## Anti-Patterns

- **`Response.success` assumed** — unwrapping bodies without checking codes.
- **Domain types in `@GET` return positions** — couples the wire format to the app.
- **Base URL picked from `BuildConfig` deep in mappers** — the client is configured in
  DI, once.
