# Code Quality Audit Bug Fixes

## Goal

Remove the correctness, recoverability, privacy, concurrency, release, and documentation defects found by the current repository audit without changing the product's local-first behavior or adding unrelated features.

## Background and Confirmed Facts

- The current JVM suite passes, Android instrumentation sources compile, and debug lint has no errors, but instrumentation tests are not executed in CI.
- The app packages only `arm64-v8a`; the current Ubuntu x86_64 CI runner cannot execute the test APK because native MediaPipe/LiteRT libraries have no x86_64 variant (`.github/workflows/ci.yml:10-14`, `app/build.gradle.kts:77-80`).
- GitHub and Firebase provide ARM test infrastructure, but enabling it changes CI cost/account configuration. Firebase Test Lab's ARM virtual devices are compatible with native ARM applications and have usage quotas or per-minute charges.
- The owner selected the no-cost CI boundary: portable regressions run under Robolectric/JVM on every change; hardware/native cases remain an explicit pre-release ARM device gate.
- Kotlin is `2.4.21` while release R8 emits repeated Kotlin metadata parse warnings. Android's official compatibility table requires R8 `9.1.29` or newer for Kotlin 2.4; the effective shrinker version must be verified and aligned.
- No Room schema change is required by this task.

## Requirements

### R1 — Recoverable pre-import snapshot (P0)

- Move automatic pre-import snapshots from purgeable `cacheDir` storage to an app-private persistent directory under `filesDir` (`KnowledgeBackup.kt:1482-1496`).
- Keep the existing bounded retention policy and never replace the live database if snapshot creation fails.
- Expose the newest valid snapshot in Settings and route restore through the existing preview/confirmation import flow; do not add a second restore implementation.
- Keep snapshots private to the app and exclude them from Android system/cloud backup.

### R2 — Correct source-scoped search ranking (P1)

- Apply `sourceIds` eligibility before per-channel candidate truncation and RRF `topK`, not after fused results are already truncated (`SearchService.kt:135-148`).
- Preserve unscoped search behavior, ranking determinism, streaming/matrix safety limits, and existing `SearchResult` counters with clearly documented scoped semantics.
- Add a regression case where more than `topK` higher-ranked out-of-scope candidates cannot hide an in-scope result.

### R3 — Truthful backup import outcome (P1)

- A successfully committed database replacement must not be reported as a failed import merely because derived SAG FTS rebuilding failed (`KnowledgeBackup.kt:1410-1418`, `SettingsViewModel.kt:589+`).
- Return a typed import outcome containing the restored row count plus a recoverable warning when derived index rebuilding fails.
- Preserve cancellation propagation and hard failure behavior before the database commit.
- Render success-with-warning distinctly in Settings and retain the existing retry/index-maintenance guidance.

### R4 — Explicit Android backup exclusion (P1, privacy)

- Add Android 12+ `dataExtractionRules` and legacy `fullBackupContent` resources and reference both from the application manifest.
- Exclude the database, preferences, files (including automatic snapshots), external app data, and device-protected equivalents from cloud backup and device transfer; cache remains ineligible under the platform backup model.
- Verify the merged manifest and lint output.

### R5 — Consistent export under background extraction (P1)

- Prevent backup export and pre-import snapshot export from observing a mixture of SAG extraction states (`KnowledgeBackup.kt:237+`, `KnowledgeBackup.kt:1590-1595`).
- Extend the active-work guard to extraction jobs and execute the multi-table export reads against one consistent Room read transaction where supported by the existing paging implementation.
- Preserve streaming/paged ZIP writing and bounded memory; do not materialize the full database in memory.
- Add concurrency/guard regression coverage.

### R6 — Execute meaningful tests in CI (P1)

- Move database/backup/search/store cases that do not require device-native libraries into the existing Robolectric JVM suite, so the default CI job executes them.
- Keep hardware/model/UI/performance cases in `androidTest` and continue compiling the complete source set on every push/PR.
- Do not provision a paid/cloud ARM lane in this task; document and preserve the executable pre-release ARM device command and evidence checklist.

### R7 — Release shrinker compatibility and smoke gate (P1)

- Align the effective R8 version with Kotlin 2.4's official minimum without downgrading Kotlin or changing application dependencies unnecessarily.
- Keep the version in one build-configuration location and preserve dependency verification.
- Make `assembleRelease` part of CI and fail if Kotlin metadata parse warnings recur.
- Record a minimal release artifact smoke procedure; real-device install/launch remains a device acceptance step unless ARM execution is approved under R6.

### R8 — Atomic shared model directory switch (P2)

- Acquire and persist permission for the new SAF tree before releasing the old tree permission (`SharedModelStore.kt:51-72`).
- If new permission acquisition or preference persistence fails, the previous directory and permission must remain usable.
- Release the previous permission only after the new URI is durable, and do not release when selecting the same URI.
- Add regression coverage for acquisition failure, successful replacement, and same-URI selection.

### R9 — Network/privacy documentation accuracy (P2)

- Update the manifest comment and README permission description: `INTERNET` is also used by explicit web import and configured OpenAI-compatible LLM requests, not only model downloads (`AndroidManifest.xml:4-9`, `README.md:33`).
- Keep the local-first/offline behavior claim precise: search, local document import, embedding, and graph operations remain offline; network features are explicit user actions.
- Extend the existing documentation consistency check if a stable assertion can prevent this wording from drifting again.

## Acceptance Criteria

- [ ] Importing a backup creates a persistent, bounded pre-import snapshot; Settings can preview and restore the newest snapshot after cache eviction.
- [ ] A failed snapshot creation leaves the live database unchanged.
- [ ] Source-scoped search returns eligible hits even when all global top-K candidates belong to other sources; unscoped ranking is unchanged.
- [ ] A committed import with failed SAG FTS rebuild reports restored data plus a warning, never a false total failure.
- [ ] The merged release/debug manifests reference both backup-exclusion rule files and no private app data is eligible for system backup or transfer.
- [ ] Export refuses active ingest/re-embedding/extraction work and its multi-table view is transactionally consistent without unbounded buffering.
- [ ] Default CI executes all portable regression tests, compiles remaining instrumentation tests, runs lint, and builds the minified release artifact.
- [ ] Release build logs contain no Kotlin metadata parse warning and dependency verification remains strict.
- [ ] Failed shared-directory replacement preserves the old URI and permission; successful replacement releases only the old permission.
- [ ] README and manifest comments accurately enumerate network-enabled features and documentation checks pass.
- [ ] `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, and `:app:assembleRelease` pass.

## Constraints

- Preserve current user data, backup format compatibility, cancellation behavior, and `arm64-v8a` release scope.
- Reuse existing Settings import preview/confirmation UI, Room transaction APIs, Robolectric setup, and string-resource conventions.
- No new runtime dependency, database migration, model route, or backup format version unless implementation evidence proves it unavoidable.
- Product code changes require an approved final planning summary under the Trellis workflow.

## Out of Scope

- New backup destinations, scheduled backups, snapshot browsing/history management, or user-configurable retention.
- Adding x86_64 production ABI/native libraries solely to make emulator CI possible.
- Reworking the search ranking algorithm beyond making source eligibility correct.
- Changing model families, Kotlin language level, or application architecture.
- Provisioning cloud credentials, billing, signing keys, or physical devices without explicit owner approval.

## Key Decisions

- Portable database, backup, search, ViewModel, and storage regressions run in the existing Robolectric/JVM lane.
- Hardware/native model, performance, full UI, and OEM-specific SAF behavior remain a documented ARM device release gate.
- No cloud credentials, billing setup, third-party CI service, or production x86_64 ABI is added.
