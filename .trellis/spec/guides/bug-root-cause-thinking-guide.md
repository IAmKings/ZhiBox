# Bug Root Cause Thinking Guide

> A bug fix that does not produce a lesson will be paid for again. After any non-trivial
> fix, run this loop.

---

## The Loop

### 1. Reproduce and Pin

- [ ] Minimal reproduction captured (test, steps, input)
- [ ] Failing assertion or observable stated before the fix

### 2. Find the Real Cause (5 Whys)

Keep asking until the answer is a **system or assumption**, not a person:

```text
The list was empty on cold start.
Why? The cache returned null.
Why? The DB had not finished seeding.
Why? The repository did not await seeding.
Why? Nothing models "seeding" as a state.      ← root cause: missing state, not "a bug"
```

- Root causes live in categories: missing state, wrong unit
  ([../shared/timestamp.md](../shared/timestamp.md)), lifecycle misunderstanding
  ([../big-question/](../big-question/index.md)), boundary drift
  ([./cross-layer-thinking-guide.md](./cross-layer-thinking-guide.md)), unchecked
  assumption about a library.

### 3. Fix at the Right Level

- Fix the mechanism, not the instance. One `!!` removed is local; banning `!!` via
  lint/architecture test is systemic
  ([../backend/quality.md](../backend/quality.md)).
- If the fix changes a rule, update the spec file in the same PR.

### 4. Lock It In

- [ ] Regression test written **first fails, then passes**
- [ ] Spec updated if the rule was missing
      (which file? ← write it into the PR)
- [ ] Recurring-class lesson promoted to `../big-question/` with severity
- [ ] `no-trellis`-skipped breadcrumbs are irrelevant here — the test is the memory

### 5. Blameless Note (for the team/spec)

```markdown
**Bug**: <one line>
**Cost**: <time / user impact>
**Root cause**: <system-level statement>
**Lock-in**: <test + spec change>
**Detection**: how could CI/tests have caught it earlier?
```

---

## When a Bug Becomes a Big Question

Promote to [../big-question/](../big-question/index.md) when **any** of:

- Fix took > 30 minutes
- The failure was silent (no crash — wrong behavior only)
- A library behaved against expectation
- It could recur in any sibling feature

---

## Anti-Patterns

- **"Fixed it, moving on"** — no test, no lesson, guaranteed sequel.
- **Whack-a-mole fixes** — patching symptoms at each call site instead of the boundary.
- **Hero blame** — the root cause is a person; the fix is a process, not a memo.
