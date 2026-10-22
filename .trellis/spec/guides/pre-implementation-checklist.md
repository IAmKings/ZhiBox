# Pre-Implementation Checklist

> Run this before writing code for any non-trivial change. Two minutes here beats two
> hours of rework.

---

## 1. Search First

- [ ] Searched for existing implementation: `rg "<keyword>" --type kotlin`
- [ ] Checked `:core:ui` for a reusable component
- [ ] Checked `:core:common` for an existing utility
- [ ] Checked `.trellis/spec/` for an existing rule about this

## 2. Place It Correctly

- [ ] Decided which module owns this code
      ([../frontend/directory-structure.md](../frontend/directory-structure.md))
- [ ] Confirmed the dependency direction stays one-way
- [ ] Screen-local vs shared component decided deliberately

## 3. Model It Correctly

- [ ] Types named for each boundary: DTO / entity / domain / UI
- [ ] Identifiers are value classes, not raw strings
- [ ] State modeled as sealed variants, not boolean flags
- [ ] Time units named (`millis`/`seconds`) — see
      [../shared/timestamp.md](../shared/timestamp.md)

## 4. Think About Failure

- [ ] Listed how each step can fail (network, DB, parsing, empty)
- [ ] Decided the error → `AppError` mapping and UI rendering
- [ ] Loading and empty states designed (not an afterthought)

## 5. Think About Lifecycle

- [ ] State survives rotation and process death
      (`rememberSaveable`/`SavedStateHandle`)
- [ ] Coroutines owned by a scope that cancels
      ([../backend/concurrency.md](../backend/concurrency.md))
- [ ] Collection is lifecycle-aware

## 6. Think About Tests

- [ ] Named the tests you will write **before** implementing
- [ ] Error branches included, not just the happy path
- [ ] Fakes identified (repository, clock, dispatchers)

## 7. If It Touches the Schema or API

- [ ] Version bump + migration planned
      ([../backend/database.md](../backend/database.md))
- [ ] Field units and nullability verified against the real payload
- [ ] Consumers list grepped (who reads this field?)

---

## Output

Write the answers to 1–7 as task notes (or a short design note in the task folder)
before implementing. If any box cannot be checked, resolve it first — that is the bug
you have not written yet.
