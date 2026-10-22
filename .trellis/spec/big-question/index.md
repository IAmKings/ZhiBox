# Kotlin + Compose Common Pitfalls

> Documented pitfalls from building Android/KMP apps with Compose, Room, and
> coroutines. Each entry: symptom, cause, fix, prevention.

## Severity Levels

| Level | Description                                   |
| ----- | --------------------------------------------- |
| P0    | App crashes or fails to start                 |
| P1    | Feature completely broken, data loss possible |
| P2    | Degraded experience, workaround exists        |

---

## By Category

### Compose Runtime

| Document                                                          | Severity | Summary                                        |
| ----------------------------------------------------------------- | -------- | ---------------------------------------------- |
| [compose-recomposition.md](./compose-recomposition.md)             | P2       | Unstable parameters cause recomposition storms |
| [collectasstate-lifecycle.md](./collectasstate-lifecycle.md)       | P1       | Collection continues after UI leaves, leaks and crashes |
| [configuration-change.md](./configuration-change.md)              | P1       | State lost on rotation or process death        |

### Coroutines

| Document                                                          | Severity | Summary                                        |
| ----------------------------------------------------------------- | -------- | ---------------------------------------------- |
| [coroutine-scope-leaks.md](./coroutine-scope-leaks.md)             | P1       | Work continues (or dies) with the wrong scope  |

### Persistence

| Document                                                          | Severity | Summary                                        |
| ----------------------------------------------------------------- | -------- | ---------------------------------------------- |
| [room-schema-migrations.md](./room-schema-migrations.md)           | P0       | Version mismatch crash or destructive wipe     |

### Release Build

| Document                                                          | Severity | Summary                                        |
| ----------------------------------------------------------------- | -------- | ---------------------------------------------- |
| [proguard-r8.md](./proguard-r8.md)                                | P0       | Minified release crashes on serialization/reflection |

### Test Infrastructure

| Document                                                          | Severity | Summary                                        |
| ----------------------------------------------------------------- | -------- | ---------------------------------------------- |
| [instrumentation-test-hangs.md](./instrumentation-test-hangs.md)   | P1       | Portable/OEM-sensitive UI tests can stall the full device suite |

---

## Quick Debugging Checklist

### Crash on startup (P0)

1. Fresh install works, upgrade crashes → [room-schema-migrations.md](./room-schema-migrations.md)
2. Debug works, release crashes → [proguard-r8.md](./proguard-r8.md)

### State weirdness (P1)

1. Screen freezes or stale after returning → [collectasstate-lifecycle.md](./collectasstate-lifecycle.md)
2. Data resets on rotation / task switch → [configuration-change.md](./configuration-change.md)
3. Work stops or never stops → [coroutine-scope-leaks.md](./coroutine-scope-leaks.md)

### Performance (P2)

1. Janky scroll, full-screen recompositions → [compose-recomposition.md](./compose-recomposition.md)

### Device test never finishes (P1)

1. Progress stops on a Compose or Activity test → [instrumentation-test-hangs.md](./instrumentation-test-hangs.md)

---

## Technology Stack Coverage

Pitfalls documented for: **Kotlin 2.x**, **Jetpack Compose (Material 3)**, **Room /
SQLDelight**, **Retrofit / Ktor**, **kotlinx.serialization**, **Hilt**, **coroutines +
Flow**. Most apply to neighbor stacks (Koin DI, Moshi, Rx) with small renames.

## Contributing

Every fix that costs > 30 minutes or behaves silently-wrong earns an entry here, plus a
regression test in the project that hit it (see
[../guides/bug-root-cause-thinking-guide.md](../guides/bug-root-cause-thinking-guide.md)).
