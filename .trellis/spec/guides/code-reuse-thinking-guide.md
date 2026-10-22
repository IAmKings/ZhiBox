# Code Reuse Thinking Guide

> Duplication is cheaper to prevent than to extract. Search before you write; promote
> on the third occurrence.

---

## The Rules

1. **Search before creating anything.** New helper, component, constant:

```bash
rg "formatDuration|DurationText|formatDurationText" --type kotlin
```

   If something close exists, extend it or learn why it does not fit — do not write a
   sibling.

2. **The Rule of Three.** Two similar blocks: tolerate with a comment. Three:
   extract. Extraction goes to the **lowest module both users already depend on** —
   `:core:ui` for composables, `:core:common` for logic — never sideways between
   features.

3. **Extract patterns, not coincidences.** Two code blocks that look alike but serve
   different domains should often stay separate. Same shape + same domain = extract;
   same shape + different domain = maybe later.

4. **One concept, one definition.** A display format, a business threshold, an enum of
   categories exists in exactly one place. Grep the current spelling before adding a
   second definition.

---

## Placement Decision Tree

```
Is it UI?
 ├─ used by 1 screen          → feature module, components/
 ├─ used by 2+ features       → :core:ui
 └─ pure styling primitives   → :core:designsystem
Is it logic?
 ├─ single feature            → feature ViewModel / mapper
 ├─ 2+ features or domain rule→ :core:domain / :core:data
 └─ generic (time, text, result) → :core:common
Is it a constant?
 └─ crosses module boundaries → :core:model / :core:common
```

**Never** let feature A depend on feature B "to reuse something" — promote it down
instead (see [../frontend/directory-structure.md](../frontend/directory-structure.md)).

---

## When NOT to Reuse

- The two usages evolve on different schedules (design iteration vs stable backend
  contract).
- Merging them requires boolean/enum flags that make the shared version harder to read
  than the copies.
- The "shared" logic is actually coupled to lifecycle or platform details that differ.

Deliberate duplication with a comment naming the sibling is a valid outcome:

```kotlin
// Intentionally similar to ProfileHeader; the layouts diverge on purpose
// (home uses compact metrics). Revisit after the home redesign.
```

---

## Extraction Checklist

- [ ] Searched for existing implementations (and aliases)
- [ ] Named it for what it does, not where it came from
- [ ] Placed per the decision tree; dependency direction stays one-way
- [ ] Moved its tests with it (fakes/fixtures included)
- [ ] Deleted the originals in the same PR — no deprecation shims left behind
- [ ] Updated [../frontend/index.md](../frontend/index.md) or module docs if the
      surface is notable

---

## Anti-Patterns

- **Shared component with 12 boolean parameters** — a flag explosion is worse than two
  clean components.
- **`Utils.kt` grab-bag** — promote to named capabilities (`time/`, `text/`).
- **Copy with tiny silent divergence** — the worst outcome: two behaviors that look
  like one.
