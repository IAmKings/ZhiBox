# Kotlin Conventions

> Language-level idioms for readable, stable, review-friendly Kotlin.

---

## Rules

1. **`val` by default.** `var` needs a reason; mutable module-level state needs a
   review.
2. **No non-null assertions (`!!`).** Use `?.`, `requireNotNull` with a message, or
   restructure — `!!` is a crash in disguise (see
   [../frontend/type-safety.md](../frontend/type-safety.md)).
3. **Data classes for value types**, sealed hierarchies for closed sets of variants.
   Prefer `sealed interface` over `sealed class` unless sharing constructor state.
4. **Value classes for domain identifiers** (`HomeItemId`) and units (`Dp`, `Millis`) —
   see [../frontend/type-safety.md](../frontend/type-safety.md).
5. **Scope functions with intent**:

| Function  | Use for                                   | Avoid                      |
| --------- | ----------------------------------------- | -------------------------- |
| `let`     | null-scoped transformation                | nested `let.let.let`       |
| `apply`   | configuring one object                    | returning computed values  |
| `also`    | side observation (log, assert)            | chained mutation           |
| `run`     | computed block with a result              | anonymous-ish scoping      |
| `with`    | grouped reads on one receiver             | receiver confusion         |

   More than two nested scope functions: extract a named function instead.
6. **Expressions over statements** when clarity holds: `if`/`when` as expressions,
   single-expression functions with inferred types for trivial bodies only.
7. **Explicit types on public API** — inferred types are fine inside bodies; public
   functions declare their return type.
8. **Extension functions live with their receiver's module**, top-level, not in `Util`
   classes. Cross-module extensions require a shared home (`:core:common`).
9. **Companion constants**: `private const val` — never `val` with runtime-computed
   constants that could be `const`.
10. **`internal` for module-internal surface**; default to least visibility and open
    nothing without a reason.

---

## Nullability

- Nullable types model real absence; empty strings / `-1` / sentinel objects do not.
- Late-init: `lateinit var` only for framework-injected lifecycle fields; otherwise
  `by lazy { }` or constructor values.
- Platform types (Java interop) are normalized at the boundary into explicit nullable
  or non-null Kotlin types immediately.

---

## Collections

- Read-only interfaces (`List`, `Map`) in APIs; mutable constructors at creation sites.
- `kotlinx.collections.immutable` types in Compose state (see
  [../frontend/state-management.md](../frontend/state-management.md)).
- Sequence for large lazy pipelines; plain collection ops for typical sizes — measure
  before "optimizing".

---

## Anti-Patterns

- **`when` with `else -> {}`** over a sealed type — hides new variants from the
  compiler.
- **`typealias` soup** — aliases that rename instead of clarify (`typealias Data =
  Any`).
- **Utility classes with `object Util`** — top-level functions are Kotlin's version of
  statics; split by capability.
- **Delegation gymnastics** — `by` interfaces where a plain field is clearer.
