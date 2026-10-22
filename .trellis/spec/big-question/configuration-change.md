# Configuration Change & Process Death

> **Severity**: P1 — user input or scroll position vanishes on rotation, backgrounding,
> or process death.
>
> Symptom: "it works on my emulator" (no rotation) breaks on device (auto-rotate +
> memory pressure).

---

## Cause

1. State held in `remember { }` (dies with composition) instead of
   `rememberSaveable { }` (survives recreation).
2. ViewModel state not in `SavedStateHandle` for the death path — `viewModelScope`
   state survives rotation but **not** process death.
3. Positions/selection stored outside the state system (global singletons, companion
   caches).

---

## What Survives What

| Storage                                | Rotation | Process death |
| -------------------------------------- | -------- | ------------- |
| `remember { }`                         | ✗        | ✗             |
| `rememberSaveable { }`                 | ✓        | ✓ (Bundle-size limits apply) |
| ViewModel fields                       | ✓        | ✗             |
| `SavedStateHandle`                     | ✓        | ✓             |
| Room / DataStore                       | ✓        | ✓             |

**Rule of thumb**: transient UI trivia → `rememberSaveable`; re-derivable from ID →
`SavedStateHandle` + repository; durable data → storage.

---

## Fix Patterns

### Restore by ID, not by object

The destination's ViewModel reads its argument from `SavedStateHandle` and re-fetches
— this is deep-link- and death-proof:

```kotlin
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DetailRepository,
) : ViewModel() {
    private val id = DetailId(savedStateHandle.get<Long>("id")!!)
    // observeDetail(id) ...
}
```

### Save user input

```kotlin
var draft by rememberSaveable { mutableStateOf("") }
```

Large or structured input → ViewModel + `SavedStateHandle`, not ever-growing
`rememberSaveable` Bundles (`TransactionTooLargeException` is the next pitfall).

### Scroll and list state

`rememberLazyListState()` is saveable by default — do not wrap it in plain `remember`,
and do not store "current item" manually.

---

## Prevention

- Every screen test run includes a configuration-change pass (rotation +
  `ProcessLifecycleOwner` simulation); CI screenshot tests pin light/dark and
  orientation pairs
- Review question for each new screen: "what happens on rotate and on process death?"
  — answered in the PR description
- Navigation args are IDs (see
  [../frontend/navigation.md](../frontend/navigation.md)), so restoration is always
  possible by re-fetch

---

## Related

- [collectasstate-lifecycle.md](./collectasstate-lifecycle.md)
- [../frontend/state-management.md](../frontend/state-management.md) — restoration rules
  (rule 6)
