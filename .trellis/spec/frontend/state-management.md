# State Management

> Unidirectional data flow with ViewModel + StateFlow. No reactive-framework soup.

---

## The Pattern

```
UiState (immutable)          events (user intents)
    ^                                |
    |                                v
ViewModel ── StateFlow ──> Screen ──> callbacks ──> ViewModel ──> Repository
```

1. **One `UiState` per screen**, exposed as `StateFlow`.
2. **One-shot events** use a channel with replay 0, collected in a `LaunchedEffect`.

```kotlin
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val items: ImmutableList<HomeItem>) : HomeUiState
    data class Error(val message: UiText) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()   // viewModelScope cancels with the ViewModel — safe
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            when (val result = repository.loadHome()) {
                is AppResult.Success -> _uiState.value = HomeUiState.Loaded(
                    result.data.map(HomeItem::toUiItem).toImmutableList(),
                )
                is AppResult.Failure -> _uiState.value = HomeUiState.Error(result.error.toUiText())
            }
        }
    }

    fun togglePin(id: HomeItemId) {
        // optimistic local update against current state, no extra load
        val current = _uiState.value as? HomeUiState.Loaded ?: return
        _uiState.value = current.copy(items = current.items.togglePinned(id))
    }
}
```

---

## UI Mapping at the Boundary

Domain entities never reach `UiState` — the mapper lives next to the ViewModel:

```kotlin
// HomeUiMapper.kt — display strings are pre-formatted, previews stay trivial
fun HomeItem.toUiItem(): HomeUiItem = HomeUiItem(
    id = id,                       // HomeItemId value class passes through
    title = title,
    subtitle = description?.takeIf { it.isNotBlank() },
    priceText = priceFormatter.format(price, locale),   // once, here
    isPinned = isPinned,
)

fun AppError.toUiText(): UiText = when (this) {
    AppError.Network -> UiText.Res(R.string.error_offline)
    AppError.Auth    -> UiText.Res(R.string.error_reauth)
    is AppError.Conflict -> UiText.Dynamic(detail)
    else -> UiText.Res(R.string.error_generic)
}
```

---

## One-Shot Events

```kotlin
sealed interface HomeEvent {
    data class OpenDetail(val id: DetailId) : HomeEvent
    data object ShowPinLimit : HomeEvent
}

// in the ViewModel
private val _events = Channel<HomeEvent>(Channel.BUFFERED)
val events = _events.receiveAsFlow()

// in HomeContent's host
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is HomeEvent.OpenDetail -> onOpenDetail(event.id)
            HomeEvent.ShowPinLimit -> snackbarHost.showSnackbar(...)
        }
    }
}
```

Never model one-shot events as `StateFlow<Boolean>` flags — they re-fire on rotation.

---

## Rules

1. **`UiState` is immutable and complete.** A screen must render from `UiState` alone —
   no supplementary `MutableState` on the side. Loading/error/empty are explicit states,
   not booleans scattered around.
2. **Collect with `collectAsStateWithLifecycle()`** in composition, and
   `repeatOnLifecycle` for non-compose collection. Plain `collectAsState()` and
   `launchWhenStarted` are banned — see
   [../big-question/collectasstate-lifecycle.md](../big-question/collectasstate-lifecycle.md).
3. **UI models only.** Domain entities map into UI models at the ViewModel boundary
   (`HomeUiMapper.kt`). Domain types leaking into `UiState` is a review blocker.
4. **Use `kotlinx.collections.immutable`** (`ImmutableList`) in state classes so Compose
   treats them as stable; a plain `List<...>` in `UiState` defeats skipping.
5. **Events up, state down.** Child composables receive lambdas; only the screen (or
   ViewModel) mutates state. Grandchild-to-grandchild callbacks are a smell — hoist.
6. **State restoration**: user-typed input goes through `rememberSaveable`; data that
   must survive process death lives in `SavedStateHandle` — see
   [../big-question/configuration-change.md](../big-question/configuration-change.md).
7. **Local optimistic updates** (toggle pin, like) update `_uiState` directly and let
   the repository reconcile — never re-fetch the whole list for a single toggle.

---

## Side Effects in Composition

| Need                              | Use                                | Do not use            |
| --------------------------------- | ---------------------------------- | --------------------- |
| Run once when entering comp       | `LaunchedEffect(Unit)`             | side effect in body   |
| Run on key change                 | `LaunchedEffect(key)`              | `remember {}` w/ side |
| Timer / periodic work             | `LaunchedEffect` + `delay` loop    | `Handler`/`Timer`     |
| Cleanup (listeners, receivers)    | `DisposableEffect`                 | manual null juggling  |
| Derive frequent computed value    | `derivedStateOf`                   | `remember` + manual   |
| Callback-scope launches (onClick) | `rememberCoroutineScope`           | `GlobalScope`         |

---

## Testing Anchor

Every public ViewModel event maps to one Turbine test — this is the contract the UI
renders against (full example in
[../shared/testing.md](../shared/testing.md)).

---

## Anti-Patterns

- **ViewModel per composable** — one ViewModel per screen/destination, shared down.
- **State duplication** — the same truth in `UiState` and in a `remember { mutableStateOf }`.
- **Boolean-zoo states** — `isLoading`, `isError`, `isEmpty` triads modeling what a
  sealed class expresses precisely.
- **Direct repository calls from composables** — UI talks to ViewModels, ViewModels
  talk to repositories.
