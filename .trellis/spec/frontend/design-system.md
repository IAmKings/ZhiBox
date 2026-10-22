# Design System

> Material 3 theming with semantic tokens. UI code consumes tokens, never raw values.

---

## Structure (`:core:designsystem`)

```
:core:designsystem/
├── theme/
│   ├── Theme.kt          # AppTheme composable (light/dark/dynamic)
│   ├── Color.kt          # palette + semantic scheme
│   ├── Type.kt           # Typography scale
│   └── Shape.kt          # corner shapes
├── tokens/
│   └── Spacing.kt        # spacing scale object
└── components/           # themed primitives (AppButton, AppCard, ...)
```

TODO(spec): record the project's brand palette, font family (e.g. custom CJK font),
and whether dynamic color is enabled.

---

## Theme Skeleton

```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,        // opt-in per project decision
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> if (darkTheme) dynamicDarkColorScheme(context)
                        else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalAppSpacing provides AppSpacing,
        LocalAppExtendedColors provides AppExtendedColors(
            success = if (darkTheme) SuccessDark else SuccessLight,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
```

Extended tokens ride a CompositionLocal so feature code stays token-based:

```kotlin
val LocalAppExtendedColors = staticCompositionLocalOf { AppExtendedColors() }

// consumption in feature code — a token, not a literal
val successColor = AppTheme.extendedColors.success
```

---

## Rules

1. **Semantic over raw.** Composables use semantic tokens
   (`MaterialTheme.colorScheme.surface`, `AppTheme.spacing.m`) — never literal
   `Color(0xFF...)` or `8.dp` scattered in feature code. A raw hex in `:feature:*` is a
   review blocker.
2. **Extend Material, do not replace it.** Custom tokens are provided through
   CompositionLocal with light **and** dark values; missing dark variants are a bug.
3. **Dark theme is part of definition-of-done** for every screen — preview both:
   `@Preview(uiMode = UI_MODE_NIGHT_YES)`.
4. **Typography only from `MaterialTheme.typography`** plus custom styles defined in
   `Type.kt`:

```kotlin
// Type.kt — the scale is the whole truth; feature code never sets fontSize
val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily(R.font.app_display),   // TODO(spec): project font
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    // ... map remaining Material roles
)
```

5. **Spacing is a fixed scale** (e.g. 4/8/12/16/24/32):

```kotlin
@Immutable
data class AppSpacing(val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 16.dp,
                      val l: Dp = 24.dp, val xl: Dp = 32.dp)
```

   Free-form dp values need a comment naming the design spec they implement.
6. **Touch targets ≥ 48dp**; icons get `contentDescription` (or `null` when decorative
   and paired with a text label).
7. **User-facing strings live in `strings.xml`** and are read via `stringResource`.
   Hard-coded UI strings are a review blocker. TODO(spec): note the project's product
   language(s) and whether spec text differs (e.g. English spec, Chinese product copy).
8. **Icons**: one source set (Material Icons or custom), registered centrally; mixing
   two icon families needs a design decision recorded here.

---

## Token Checklist for New Screens

- [ ] All colors come from `colorScheme` / extended tokens
- [ ] All text styles from `typography`
- [ ] All spacing from the scale
- [ ] Light + dark previews compile and look right
- [ ] No hard-coded strings
- [ ] Loading / empty / error states use shared design-system components
      (`EmptyState`, `ErrorState`, `LoadingIndicator`) instead of ad-hoc layouts

---

## Anti-Patterns

- **Copy-paste theme** — `Color(0xFF6750A4)` transplanted from Material defaults into a
  feature file.
- **Half-done dark mode** — text becomes invisible at night; caught by preview discipline.
- **Local one-off styles** — a `TextStyle(fontSize = 13.sp, lineHeight = 18.sp)` defined
  inline; promote to `Type.kt` if used twice.
- **String building in composables** — concatenating user-visible fragments instead of
  using placeholder resources (`stringResource(R.string.x, arg)`).
