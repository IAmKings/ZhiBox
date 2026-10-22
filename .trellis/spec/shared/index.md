# Shared Standards Index

> Cross-cutting rules that apply to **every** module — UI and data alike.

## Documentation Files

| File                                    | Description                                 | When to Read                       |
| --------------------------------------- | ------------------------------------------- | ---------------------------------- |
| [kotlin.md](./kotlin.md)                | Language conventions and idioms             | Writing any Kotlin                 |
| [code-quality.md](./code-quality.md)    | Naming, file organization, comments         | Always                             |
| [git-conventions.md](./git-conventions.md) | Branches, commits, PR checklist          | Before every commit                |
| [gradle.md](./gradle.md)                | Version catalogs, convention plugins, KTS   | Touching build files               |
| [testing.md](./testing.md)              | Test pyramid, naming, fakes vs mocks        | Writing tests                      |
| [timestamp.md](./timestamp.md)          | Time representation rules                   | Persisting or exchanging time      |

---

## Non-Negotiables (all modules)

1. **English spec text; follow the project's rule for user-facing strings** —
   TODO(spec): state it (e.g. "product copy in Chinese, code identifiers in English").
2. **Formatting is mechanical** — ktlint/spotless; never hand-adjusted style.
3. **No warnings without a written `@Suppress("...", reason)`** — silent suppressions
   rot.
4. **Search before you write** — new helper, constant, or type: grep first (see
   [../guides/code-reuse-thinking-guide.md](../guides/code-reuse-thinking-guide.md)).
5. **Every PR compiles the whole graph** and passes the module's own tests before
   merge.
