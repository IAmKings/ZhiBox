# Logging

> Structured, leveled, PII-free logging. Logs are for diagnosing, not for storytelling.

TODO(spec): choose the logging stack — Timber (Android-only) or Kermit (KMP) — and the
crash-reporting integration (e.g. Crashlytics/Sentry).

---

## Rules

1. **One logging facade** (`:core:common/log`), injected or object-based consistently;
   no `Log.d`/`println` scattered directly in features.
2. **Levels mean things**:

| Level   | Use                                                |
| ------- | -------------------------------------------------- |
| `ERROR` | Handled failure worth surfacing; includes context   |
| `WARN`  | Degraded behavior, fallback taken                   |
| `INFO`  | Lifecycle milestones (app start, sync completed)    |
| `DEBUG` | Development detail; stripped or gated in release    |
| `VERBOSE` | Not used by default                              |

3. **No PII / secrets in logs — hard rule.** Auth tokens, full request/response bodies,
   emails, precise location are excluded. Log identifiers and shapes, not payloads:

```kotlin
logger.d(TAG, "home refresh failed: code=%d, ids=%d", code, itemCount)
```

4. **Log at the handling boundary** — where the error is caught and wrapped (see
   [error-handling.md](./error-handling.md)) — not at every layer it passes through.
5. **Tags are stable constants** (`private const val TAG = "HomeRepo"`), not inline
   strings; class-name reflection in hot paths is avoided.
6. **Lazy/parameterized formatting** over string interpolation for debug logs so gated
   logs cost nothing in release.
7. **Crash reports get breadcrumbs, not dumps**: wire the facade to the crash reporter
   so the last N logs attach automatically; never log the full DB contents.

---

## What a Good Log Line Looks Like

```kotlin
// Context: what happened, with which inputs, at which boundary
logger.w(TAG, "migration fallback used: from=%d, to=%d", oldVersion, newVersion)
logger.i(TAG, "home sync complete: inserted=%d, skipped=%d, tookMs=%d", n, s, ms)
```

---

## Anti-Patterns

- **Log-and-continue swallowing** — logging an exception and returning `null` anyway
  hides the failure from the error model (see
  [error-handling.md](./error-handling.md)).
- **Token in a header log** — instant security incident.
- **Logging loops** — per-item logs inside batch operations; log totals instead.
- **Debug spam shipped** — ungated `logger.d` in hot paths (startup, scroll).
