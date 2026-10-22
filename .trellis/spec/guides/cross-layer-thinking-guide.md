# Cross-Layer Thinking Guide

> Features that span layers fail at the boundaries. Walk every boundary explicitly.

---

## The Boundaries

```
UI (Compose)  ⇄  ViewModel (UiState)  ⇄  Repository  ⇄  Data Sources
      UI models       domain models        entity / DTO
```

Each arrow converts a type. Each conversion can drift in units, nullability, casing,
or meaning. A feature is done when **every** arrow has been consciously walked.

---

## The Walkthrough

For a feature crossing all layers, answer for each boundary:

### 1. UI ⇄ ViewModel

- [ ] One `UiState` models the whole screen (loading/error/empty included)
- [ ] Events are lambdas up, state is a Flow down
- [ ] No domain type appears in UI parameters
- [ ] Display strings are pre-formatted in the mapper

### 2. ViewModel ⇄ Repository

- [ ] Repository returns `Result`/typed errors, not throws
- [ ] UI state derived from domain models via `*UiMapper`
- [ ] One-shot vs reactive (suspend vs Flow) chosen deliberately per operation

### 3. Repository ⇄ Data Sources

- [ ] DTO → entity → domain mapping written and tested
- [ ] Unknown enum values / null fields have explicit handling
- [ ] Timestamps normalized to epoch millis at the edge
      ([../shared/timestamp.md](../shared/timestamp.md))
- [ ] Writes that belong together are in one transaction

### 4. Persistence ⇄ Versioning

- [ ] Schema change carries version bump + migration
      ([../backend/database.md](../backend/database.md))
- [ ] Old stored data still renders (migration test exists)
- [ ] API evolution tolerated (`ignoreUnknownKeys`, unknown enum case)

---

## Semantic Changes (the sneaky ones)

Changing **what a value means** (not just its type) requires a consumer audit:

```bash
rg "<field_or_value>" --type kotlin -c
```

- [ ] Every reader of the field checked and updated
- [ ] Old stored values still interpreted correctly (or migrated)
- [ ] Unit/meaning documented at the field
- [ ] Tests assert both old and new interpretation where applicable

Examples: seconds → millis, `"active"` → `Active` enum with new members, `0 = off` →
`0 = unset`.

---

## Red Flags

- A type imported across more than two layers (UI touching a DTO)
- A `TODO` about "figure out the units later"
- The same parsing/formatting logic in two layers
- A feature that renders fine on your device but has no loading/error test
