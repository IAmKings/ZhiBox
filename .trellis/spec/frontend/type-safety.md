# Frontend Type Safety

> UI state modeling rules: sealed hierarchies, immutable collections, typed identifiers.

---

## Rules

1. **Sealed over boolean-zoo.** Screen states with more than two mutually exclusive
   conditions are `sealed interface` hierarchies, not `isLoading`/`isError` flags — see
   [state-management.md](./state-management.md).
2. **IDs are value classes**, never bare strings/ints across layer boundaries:

```kotlin
@JvmInline
value class HomeItemId(val value: Long)

data class HomeItem(
    val id: HomeItemId,
    val title: String,
    val price: BigDecimal,
    val isPinned: Boolean,
)
```

   TODO(spec): define the project's core identifier value classes.

3. **Immutable collections in state**: `ImmutableList`/`ImmutableMap` from
   `kotlinx.collections.immutable` for anything inside `UiState`. Plain `List` makes the
   class unstable and kills skipping (see
   [../big-question/compose-recomposition.md](../big-question/compose-recomposition.md)):

```kotlin
data class HomeUiItem(
    val id: HomeItemId,
    val title: String,
    val subtitle: String?,        // real absence only — never "" as null
    val priceText: String,        // pre-formatted in the mapper
    val isPinned: Boolean,
)

data class HomeLoadedState(
    val items: ImmutableList<HomeUiItem>,      // stable -> skippable
    val totalText: String,
)
```

4. **No domain leakage.** Domain entities (DB/DTO types) never appear in `UiState` or
   composable parameters. The ViewModel maps them via `*UiMapper.kt`; this keeps DB
   schema changes from cascading into UI and previews.
5. **No nullable-strings for structured data.** `val dateText: String?` invites
   formatting at call sites; model as `val timestamp: Instant?` and format once in the
   mapper with the shared date formatters (`../shared/timestamp.md`).
6. **Exhaustive `when`.** UI `when` over a sealed type uses `when (state) { ... }`
   without `else`; the compiler must break when a new state is added.
7. **Text that can be a resource or dynamic value** uses a small `UiText` wrapper —
   not raw `String` plumbing of both:

```kotlin
sealed interface UiText {
    data class Res(@StringRes val id: Int) : UiText
    data class Dynamic(val value: String) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Res -> stringResource(id)
        is Dynamic -> value
    }
}
```

---

## Fake Providers for Previews and Tests

One `Fake` per UI model file keeps previews and tests consistent:

```kotlin
object HomeUiItemFakes {
    val featured = HomeUiItem(
        id = HomeItemId(1),
        title = "Summer Collection",
        subtitle = null,
        priceText = "¥199",
        isPinned = true,
    )
    val standard = featured.copy(id = HomeItemId(2), isPinned = false, subtitle = "In stock")
}
```

- Pre-format display strings (`priceText`, `dateText`) in the ViewModel/mapper so
  composables stay dumb and previews stay trivial.
- Keep UI models free of framework types (`Context`, resources) — they must construct
  in unit tests without Android.

---

## Anti-Patterns

- **`List<Any>` or `Any` parameters** — defeats the type system and Compose stability.
- **Formatting in composables** — `SimpleDateFormat` inside a composable recreates per
  recomposition and hides locale rules.
- **Reusing DTOs as UI models** — "just this once" coupling that makes server renames
  crash the UI.
- **`Map<String, Any>` state bags** — a sealed class costs less than debugging it.
