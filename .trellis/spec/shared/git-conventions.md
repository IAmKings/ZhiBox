# Git Conventions

> Commits, branches, and the merge checklist. Small, reviewable, revertable.

---

## Commits

Conventional Commits, lowercase, imperative:

```
feat(home): add pinned items section
fix(database): migrate schema v2->v3 without data loss
refactor(data): extract HomeMappers to core:model
chore(deps): bump compose-bom to 2024.09.00
docs(spec): record pagination decision
```

| Type       | Meaning                                    |
| ---------- | ------------------------------------------ |
| `feat`     | user-visible capability                    |
| `fix`      | bug fix                                    |
| `refactor` | behavior-neutral change                    |
| `perf`     | measurable performance change              |
| `test`     | tests only                                 |
| `chore`    | build, deps, tooling                       |
| `docs`     | documentation (specs included)             |

- **One logical change per commit.** A fix that touches schema, mapper, and UI may be
  one commit if inseparable — say so in the body.
- **Schema changes always appear with their migration in the same commit** — a
  bisect through history must never hit a version mismatch (see
  [../backend/database.md](../backend/database.md)).

---

## Branches

`<type>/<topic>` — `feat/pinned-items`, `fix/migration-v3`. Branch from and target the
default branch; rebase onto it before merge; squash-merge is the default
(TODO(spec): confirm merge strategy for this repo).

---

## PR Checklist

- [ ] CI green (lint, detekt, unit, lint for touched modules)
- [ ] Description states **what changed and why**, with screenshots for UI changes
      (light + dark)
- [ ] New module/dependency declared in the version catalog
      ([gradle.md](./gradle.md))
- [ ] Schema/`.sqm` files and migration tests included when DB changed
- [ ] Specs updated when a rule or decision changed — code that contradicts
      `.trellis/spec/` is a bug in one of the two
- [ ] Risk noted: what breaks if this is wrong, and how it was verified

---

## Repo Hygiene

- `local.properties`, `build/`, `.idea/`, `*.jks` (unless a release keystore policy
  exists — TODO(spec)) are gitignored; secrets never enter history.
- Generated code directories are excluded from review noise via PR settings, not
  `.gitignore` games.
- History stays linear enough to bisect; force-push only on your own branch.

---

## Anti-Patterns

- **"wip" / "fix stuff" commits** on shared branches.
- **PR with 40 files and no description** — split it or document it.
- **Secrets committed "temporarily"** — rotate and scrub; history is forever.
