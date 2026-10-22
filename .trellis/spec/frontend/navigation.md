# Navigation

> Single-Activity, Navigation Compose, typed routes. One route definition site.

---

## Setup

- One `MainActivity`, one `NavHost` in `:app` (or `:core:navigation` for big apps).
- Each feature module exposes `fun NavGraphBuilder.<feature>Graph(...)`; `:app` composes
  the graphs. Features never navigate to each other's internals — they receive
  destinations as lambdas from `:app`.

```kotlin
// :feature:home/navigation/HomeNavigation.kt
@Serializable
data object HomeRoute

fun NavGraphBuilder.homeGraph(
    onOpenDetail: (DetailId) -> Unit,
) {
    composable<HomeRoute> {
        HomeScreen(onOpenDetail = onOpenDetail)
    }
}

// :app
NavHost(navController, startDestination = HomeRoute) {
    homeGraph(onOpenDetail = { id -> navController.navigate(DetailRoute(id)) })
    detailGraph(onBack = { navController.popBackStack() })
}
```

TODO(spec): choose type-safe kotlinx-serialization routes (Compose Navigation 2.8+,
shown above) or classic string routes; then delete the other half of this file.

---

## Rules

1. **Routes are data classes / objects**, declared in the feature's `navigation/`
   package. Route parameters are typed (`DetailId`, not `String`) — parse once at the
   boundary, fail fast on malformed input.
2. **Only `:app` holds a `NavController`.** Screens receive navigation callbacks as
   lambdas (`onOpenDetail`, `onBack`); they never see the controller.
3. **Navigate with route objects, not back stack class magic**: `navController.navigate
   (DetailRoute(id))`. For "back to X" behavior use `popUpTo(X) { inclusive = ... }`
   with `launchSingleTop` where ids repeat.
4. **Pass IDs, not objects.** Deep-linked destinations reconstruct state from their ID;
   shipping a whole object through navigation breaks deep links and process death.
5. **Deep links** declare `deepLinks = listOf(navDeepLink<DetailRoute>(basePath =
   "app://detail"))` and reuse the same typed route.
6. **Bottom bar / drawer destinations** keep their own `NavController`-free state in
   `:app`; screens inside tabs stay back-stack agnostic.
7. **Arguments survive process death** via `SavedStateHandle` in the destination's
   ViewModel — never via static singletons or companion caches — see
   [../big-question/configuration-change.md](../big-question/configuration-change.md).

---

## Testing

- Navigation gets a smoke test: start destination renders, tapping the entry point
  lands on the target route (`ComposeTestRule` + `TestNavHostController`).
- Every deep link path has a test asserting the correct screen and argument parsing.

---

## Anti-Patterns

- **NavController in a feature composable** — couples the feature to app wiring and
  makes previews impossible.
- **String concatenation routes** — `"detail/$id"` built by hand in five places; one
  typo silently misroutes.
- **Navigation in the ViewModel** — ViewModels emit events; UI decides navigation.
- **State through navigation extras** — complex objects stuffed into route arguments
  instead of re-loading by ID.
