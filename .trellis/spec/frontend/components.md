# Compose Components

> Naming, API design, and testing conventions for composables.

---

## Naming and Layering

| Kind                     | Suffix / Pattern        | Description                                    |
| ------------------------ | ----------------------- | ---------------------------------------------- |
| Route-level screen       | `*Screen`               | Owns a ViewModel; collects state               |
| Stateless content        | `*Content`              | Pure function of state + callbacks             |
| Reusable card/list entry | `*Card` / `*Item`       | Presents one item; emits callbacks             |
| Shared widget            | neutral noun            | `AppButton`, `EmptyState`, `SectionHeader`     |
| Slot API wrapper         | descriptive noun        | `SettingsGroup(content: @Composable () -> Unit)` |

**Standard screen shape:**

```kotlin
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenDetail: (DetailId) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenDetail = onOpenDetail,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenDetail: (DetailId) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when (state) {
            is HomeUiState.Loading -> HomeShimmerGrid()
            is HomeUiState.Error -> ErrorState(message = state.message, retry = onRefresh)
            is HomeUiState.Loaded -> HomeItems(
                items = state.items,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}
```

---

## Reusable Component Example

A `*Card` is a pure presenter: values and lambdas only, one root that consumes
`modifier`:

```kotlin
@Composable
fun HomeCard(
    item: HomeUiItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(AppTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(item.cover, contentDescription = null, Modifier.size(48.dp))
            Spacer(Modifier.width(AppTheme.spacing.s))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(item.priceText, style = MaterialTheme.typography.labelLarge)
        }
    }
}
```

**Rules:**

1. **Screens are thin.** `*Screen` only wires the ViewModel and navigation callbacks;
   all rendering logic lives in the stateless `*Content`.
2. **Stateless content is private but previewed.** Mark it `private` in the same file,
   and give it the `@Preview`s.
3. **Never pass a ViewModel below the screen.** Deeper composables receive values and
   lambdas only — this keeps previews and tests trivial.
4. **Parameter order**: data/state first, callbacks next, `modifier: Modifier =
   Modifier` last.
5. **Every composable accepts a `modifier`** and applies it to its root layout — no
   exceptions for "internal" components.
6. **Slot over boolean.** Prefer `content: @Composable () -> Unit` over widget-type
   enums; add booleans only for binary rendering switches (max 1–2 per API):

```kotlin
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,   // slot for custom illustration
) { /* themed, dark-mode-safe rendering */ }
```

7. **Lambda stability.** Pass method references (`viewModel::refresh`) or `remember`ed
   lambdas into hot paths; see [../big-question/compose-recomposition.md](../big-question/compose-recomposition.md).

---

## Previews

Every reusable composable and every `*Content` ships previews. **The states you skip
are the states that ship broken** — loading/error/empty each get their own:

```kotlin
@Preview(name = "Loading", showBackground = true)
@Composable
private fun HomeContentLoadingPreview() {
    AppTheme { HomeContent(state = HomeUiState.Loading, onRefresh = {}, onOpenDetail = {}) }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun HomeContentErrorPreview() {
    AppTheme {
        HomeContent(state = HomeUiState.Error(UiText.Res(R.string.error_offline)), ...)
    }
}

@Preview(name = "Loaded", showBackground = true)
@Preview(name = "Loaded dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun HomeContentLoadedPreview() {
    AppTheme { HomeContent(state = HomeUiState.Fake.loaded, ...) }
}
```

**Rules:**

- Previews wrap content in the app theme; never preview raw Material defaults.
- Share fake state via a `val Fake` companion or fixture object instead of inline
  literals duplicated across previews.

---

## Anti-Patterns

- **Business logic in composables** — computing discounts, formatting persistence data,
  calling repositories. Composables render; ViewModels decide.
- **`Modifier` swallowed** — a composable that ignores its `modifier` parameter.
- **Preview-free "reusable" component** — if it is not worth previewing, it is not
  reusable yet.
- **State read in the wrong scope** — reading `state.foo` outside the `if (visible)`
  block that needs it; hoist reads to the narrowest composable that uses them.
- **Flag explosion** — `HomeCard(isCompact, isWide, showBadge, ...)`; split into two
  components or use slots (see the reuse guide
  [../guides/code-reuse-thinking-guide.md](../guides/code-reuse-thinking-guide.md)).
