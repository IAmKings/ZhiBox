# Thinking Flows for Kotlin + Compose Projects

> **Purpose**: systematic thinking guides that catch issues before they become bugs.
>
> **Core Philosophy**: 30 minutes of thinking saves 3 hours of debugging.

---

## Why Thinking Flows?

Most bugs and tech debt come from "didn't think of that", not from lack of skill:

- Didn't think about what happens at layer boundaries → cross-layer bugs
- Didn't think about code patterns repeating → duplicated code everywhere
- Didn't think about edge cases → runtime errors
- Didn't think about state restoration → lost data on rotation or process death

These guides help you **ask the right questions before coding**.

---

## Available Guides

| Guide                                                                  | Purpose                                | When to Use                                  |
| ---------------------------------------------------------------------- | -------------------------------------- | -------------------------------------------- |
| [Pre-Implementation Checklist](./pre-implementation-checklist.md)      | verify readiness before coding         | before starting any feature                  |
| [Cross-Layer Thinking](./cross-layer-thinking-guide.md)                | think through data flow across layers  | features spanning 3+ layers                  |
| [Code Reuse Thinking](./code-reuse-thinking-guide.md)                  | find and reduce duplication            | repeated patterns, new helpers               |
| [Bug Root Cause Analysis](./bug-root-cause-thinking-guide.md)          | learn from bugs permanently            | after fixing any non-trivial bug             |

---

## Quick Reference: When to Use Which Guide

### Before Writing Code

Use [Pre-Implementation Checklist](./pre-implementation-checklist.md) when:

- [ ] About to add a constant, type, or composable
- [ ] About to implement new logic
- [ ] Feels like similar code already exists

### Cross-Layer Work

Use [Cross-Layer Thinking](./cross-layer-thinking-guide.md) when:

- [ ] Feature touches UI → ViewModel → repository → data source
- [ ] A type crosses a boundary (DTO/entity/domain/UI)
- [ ] Multiple consumers need the same data
- [ ] Schema or API fields change meaning

### Code Organization

Use [Code Reuse Thinking](./code-reuse-thinking-guide.md) when:

- [ ] You see the same pattern three times
- [ ] You are creating a new helper (search first!)
- [ ] You are adding a field in multiple places

### After Fixing Bugs

Use [Bug Root Cause Analysis](./bug-root-cause-thinking-guide.md) when:

- [ ] The fix took > 30 minutes
- [ ] The bug involved wrong assumptions about library behavior
- [ ] A similar bug happened before

---

## The Pre-Modification Rule (CRITICAL)

> **Before changing ANY value, ALWAYS search first!**

```bash
# Search for the value you're about to change
rg "value_to_change" --type kotlin

# Check how many files define or read it
rg "CONFIG_NAME" --type kotlin -c
```

This single habit prevents most "forgot to update X" bugs.

---

## Layer Map (Android + Compose)

```
UI Layer (Composables)
        |
        v
Presentation (ViewModel, UiState)
        |
        v
Data (Repositories)
        |
        v
Data Sources (Room/SQLDelight, Retrofit/Ktor)
```

Each boundary converts types (DTO ↔ entity ↔ domain ↔ UI) and each conversion is a
place where units, nullability, or meaning can silently drift.

---

## Core Principles

1. **Search Before Write** — existing patterns before new ones
2. **Think Before Code** — 5 minutes of checklist saves 50 minutes of debugging
3. **Document Assumptions** — make implicit assumptions explicit
4. **Verify All Layers** — changes usually need updates in multiple places
5. **Learn From Bugs** — feed lessons back into these guides and
   [../big-question/](../big-question/index.md)
