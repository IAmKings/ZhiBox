# Timestamp Handling

> One time representation per layer, UTC everywhere, injected Clock. Timestamp bugs are
> silent and painful — see [../big-question/](../big-question/index.md).

---

## Representation by Layer

| Layer              | Type                                  | Example                          |
| ------------------ | ------------------------------------- | -------------------------------- |
| Database           | `Long` epoch **milliseconds**, UTC    | `updatedAt: Long`                |
| Network (DTO)      | `Long` millis (or ISO string if API dictates — normalize immediately) | |
| Domain models      | `kotlinx.time.Instant` (or `java.time.Instant`) | `updatedAt: Instant`    |
| UI                 | pre-formatted `String` from the mapper| `dateText: String`               |

TODO(spec): confirm kotlinx-datetime vs java.time for this project.

---

## Full Flow Example

```kotlin
// 1. Network DTO — unit documented at the field, verified against the real payload
@Serializable
data class HomeDto(
    val id: Long,
    val title: String? = null,
    /** Epoch millis per API-231 (seconds were v1; see big-question). */
    val updatedAt: Long? = null,
)

// 2. Mapping — conversion happens exactly once, here
fun HomeDto.toEntity(clock: Clock): HomeEntity = HomeEntity(
    id = id,
    title = title.orEmpty(),
    updatedAt = updatedAt ?: clock.now().toEpochMilliseconds(),
)

fun HomeEntity.toDomain(): HomeItem = HomeItem(
    id = HomeItemId(id),
    title = title,
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
)

// 3. UI formatting — once, in the ViewModel/mapper, never in a composable
fun Instant.toDateText(locale: Locale): String =
    DateTimeFormatters.dateMedium.withLocale(locale).format(this)
```

---

## Rules

1. **Epoch milliseconds at persistence.** Seconds/millis mismatches are the classic
   silent bug — one unit, stated in the entity/DTO field docs:
   `/** Epoch millis, UTC */`.
2. **Never call `System.currentTimeMillis()` / `Date()` / `Clock.systemDefaultZone()`
   inline in business code.** Inject `Clock` (see
   [../backend/dependency-injection.md](../backend/dependency-injection.md)); tests pin
   time:

```kotlin
val fixed = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
```

3. **Conversions happen once**, in mappers: entity `Long` ↔ domain `Instant`. UI never
   converts timestamps.
4. **Format with the shared formatters** (a `DateFormat` facility in `:core:common`),
   with explicit `Locale` — never `SimpleDateFormat` without locale, never formatting
   inside composables.
5. **`ZonedDateTime` only at the presentation edge** when the user's calendar matters
   (day boundaries, countdowns); domain comparisons stay UTC `Instant`.
6. **Duration math uses `Instant`/`Duration` arithmetic**, not epoch `Long` subtraction
   sprinkled in features:

```kotlin
// staleness check — Duration, not raw subtraction
val isStale = (Clock.System.now() - item.updatedAt) > Duration.days(7)
```

7. **Server field units are verified, documented, and mapped immediately** — a field
   named `timestamp` is not evidence of anything (see the semantic-change guide
   [../guides/cross-layer-thinking-guide.md](../guides/cross-layer-thinking-guide.md)).

---

## Pinned-Time Test Example

```kotlin
@Test
fun `entity missing updatedAt falls back to injected clock`() {
    val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    val entity = dto.copy(updatedAt = null).toEntity(clock)

    assertEquals(1_704_067_200_000L, entity.updatedAt)   // exact epoch millis
}
```

---

## Checklist for Any Time-Related Change

- [ ] Unit named and documented (`millis` vs `seconds`) at the boundary
- [ ] Conversion covered by a test with a known instant
- [ ] No new `System.currentTimeMillis()` outside DI-provided `Clock`
- [ ] Display formats checked in both 12/24-hour locales
- [ ] DST-sensitive logic (day boundaries) tested with a zone-crossing case

---

## Anti-Patterns

- **Mixing seconds and millis** in one table/column family — the classic P1.
- **Timezone local storage** — storing local time without zone info; UTC only.
- **`Date()` as a clock** — hidden `Date()` calls make tests time-dependent flakes.
- **String timestamps parsed ad hoc** at five call sites with five formats.
