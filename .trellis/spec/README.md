# Kotlin + Compose Development Guidelines (Android / KMP)

Universal development guidelines for Gradle multi-module Android/KMP projects built with
Jetpack Compose. Extracted from production Kotlin codebases; adapt before committing to
your project.

## Structure

### [Frontend](./frontend/index.md)

UI layer (`:app`, `:feature:*`, `:core:ui`, `:core:designsystem`):

- [Directory Structure](./frontend/directory-structure.md)
- [Components](./frontend/components.md)
- [State Management](./frontend/state-management.md)
- [Navigation](./frontend/navigation.md)
- [Design System](./frontend/design-system.md)
- [Type Safety](./frontend/type-safety.md)
- [Quality](./frontend/quality.md)

### [Backend](./backend/index.md)

Data and domain layers (`:core:data`, `:core:domain`, `:core:network`, `:core:database`):

- [Directory Structure](./backend/directory-structure.md)
- [Data Layer](./backend/data-layer.md)
- [Database](./backend/database.md)
- [Network](./backend/network.md)
- [Dependency Injection](./backend/dependency-injection.md)
- [Error Handling](./backend/error-handling.md)
- [Concurrency](./backend/concurrency.md)
- [Logging](./backend/logging.md)
- [Quality](./backend/quality.md)

### [Shared](./shared/index.md)

Cross-cutting concerns:

- [Kotlin Conventions](./shared/kotlin.md)
- [Code Quality](./shared/code-quality.md)
- [Git Conventions](./shared/git-conventions.md)
- [Gradle](./shared/gradle.md)
- [Testing](./shared/testing.md)
- [Timestamp Handling](./shared/timestamp.md)

### [Guides](./guides/index.md)

Development thinking guides:

- [Pre-Implementation Checklist](./guides/pre-implementation-checklist.md)
- [Cross-Layer Thinking Guide](./guides/cross-layer-thinking-guide.md)
- [Code Reuse Thinking Guide](./guides/code-reuse-thinking-guide.md)
- [Bug Root Cause Thinking Guide](./guides/bug-root-cause-thinking-guide.md)

### [Big Questions / Pitfalls](./big-question/index.md)

Common issues and solutions:

- [Compose Recomposition](./big-question/compose-recomposition.md)
- [Lifecycle-Aware Collection](./big-question/collectasstate-lifecycle.md)
- [Room Schema Migrations](./big-question/room-schema-migrations.md)
- [Coroutine Scope Leaks](./big-question/coroutine-scope-leaks.md)
- [Configuration Change](./big-question/configuration-change.md)
- [R8 / Minified Release](./big-question/proguard-r8.md)

## Tech Stack Assumptions

- **Language**: Kotlin 2.x, JVM target per project
- **UI**: Jetpack Compose + Material 3, single-Activity architecture
- **Async**: Kotlin coroutines + Flow (structured concurrency everywhere)
- **Data**: Room (Android) or SQLDelight (KMP); Retrofit or Ktor client
- **DI**: Hilt by default (swap for Koin in KMP-only modules)
- **Build**: Gradle KTS + version catalogs + convention plugins

Where a rule depends on a project decision (e.g. Room vs SQLDelight), the file marks it
with `TODO(spec):` — resolve these after installing the template.

## Usage

These guidelines can be used as:

1. **New Project Template** — install via `trellis init --registry`, then prune to fit
2. **Reference Documentation** — consult specific guides when implementing features
3. **Code Review Checklist** — verify implementations against established patterns
4. **Onboarding Material** — help new developers and agents understand conventions
