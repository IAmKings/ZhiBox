# Code Quality

> Naming, file organization, comments, and the everyday hygiene bar.

---

## Naming

| Element            | Convention                  | Example                    |
| ------------------ | --------------------------- | -------------------------- |
| Classes/objects    | PascalCase noun             | `OfflineFirstHomeRepository` |
| Functions          | lowerCamelCase verb phrase  | `loadHomeItems()`          |
| Booleans           | `is/has/should` prefix      | `isPinned`, `hasChanges`   |
| Constants          | SCREAMING_SNAKE `const val` | `MAX_RETRY_COUNT`          |
| Compose states     | noun state names            | `HomeUiState`              |
| Flows              | `observe*` / `*Flow`        | `observeHome()`            |
| Tests              | `` `subject` action `result` `` | see [testing.md](./testing.md) |

---

## File Organization

1. **One primary declaration per file**, named after it. Co-locate small private
   helpers and mappers with their primary type.
2. **Order inside a file**: constants → public API → internal/private → companion.
3. **New files under 300 lines** are a guideline, not a target — split when a second
   concern appears, not by line counter.
4. **Imports**: no wildcards (ktlint enforces), keep ordering automatic.

---

## Comments

- **Explain why, not what.** Code shows what; comments carry constraints, links to
  issues, and non-obvious decisions:

```kotlin
// Server sends epoch seconds for this field despite the API doc (see API-231).
```

- **TODO format**: `// TODO(name): what and why — tracked in ISSUE-123`. Bare `TODO()`
  in committed code fails review.
- **KDoc on public API** of core modules: purpose, params, failure behavior — one
  sentence each, honestly maintained.
- **No commented-out code.** Delete it; git remembers.

---

## Dead Code and Duplication

- Unused symbols are deleted in the PR that makes them unused.
- Three occurrences of the same pattern triggers the reuse guide
  ([../guides/code-reuse-thinking-guide.md](../guides/code-reuse-thinking-guide.md)):
  search, extract, or consciously duplicate with a comment.

---

## Pre-Commit Hygiene

- [ ] `spotlessApply` / format run
- [ ] No debug logging, print statements, or leftover experiments
- [ ] New warnings on the touched files are fixed or explicitly suppressed with reason
- [ ] Public API changes update KDoc
- [ ] Strings/resources updated on both UI modes if user-facing

---

## Anti-Patterns

- **Mega-files** — `HomeScreen.kt` containing ViewModels, mappers, and six components;
  split by concern per the module layout.
- **Shadow names** — `data`, `result`, `temp` reused three meanings in one function.
- **Copy-paste drift** — two similar blocks "temporarily" diverging in behavior.
