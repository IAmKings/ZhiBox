# Design — Code Quality Audit Bug Fixes

## Scope and Boundary Map

The defects cross four existing boundaries:

1. Settings UI → `SettingsViewModel` → `KnowledgeBackup` → Room/filesystem.
2. SAG/chat caller → `SearchService` → Room candidates → RRF ranking.
3. Settings UI → `SharedModelStore` → SAF permission + SharedPreferences.
4. Gradle/manifest/CI → release artifact and platform privacy behavior.

The fix stays inside these boundaries. It does not introduce a new repository layer, backup format, database schema, service, or runtime dependency.

## D1 — Persistent Snapshot and Restore Flow

- Store snapshots in `filesDir/backup_snapshots` with the existing `auto-backup-<timestamp>.zip` naming and retention count.
- `KnowledgeBackup` exposes only the minimum read surface needed by Settings: newest snapshot metadata/file, preview from file, and import from file. Existing archive validation remains the trust boundary.
- Settings refreshes whether a restorable snapshot exists. The restore action first builds the existing `ImportPreview`, then reuses the current confirmation dialog and import path.
- The ViewModel retains the selected source as either the existing content URI or the internal snapshot file; both converge on the same confirmation and result mapping.
- Snapshot files are app-private and are covered by the explicit Android backup exclusions.

Failure contract:

- Snapshot write failure deletes the partial file and aborts before database replacement.
- Invalid/missing latest snapshots are not offered; a file disappearing between preview and restore is an ordinary import failure and does not touch the database.

## D2 — Source Eligibility Before Ranking

- Resolve eligible chunk IDs for requested source IDs through one Room join over `chunk` and `document`, restricted to ready documents.
- Pass the optional eligible-ID set into the shared channel-ranking path.
- Matrix vector ranking scores/sorts only eligible rows; streaming ranking skips ineligible rows before its bounded top-K buffer.
- Add a source-scoped FTS DAO query that joins FTS rows to chunks/documents and applies source eligibility before the existing hit limit. Unscoped calls retain the current query.
- RRF receives already-scoped channel lists, so `topK` is applied only after eligibility. `buildHits` remains evidence hydration, not policy filtering.
- `searchChannels` remains unscoped and unchanged because no caller supplies a source scope.

This fixes the mechanism once in `rankChannels`; increasing over-fetch or filtering after fusion is explicitly rejected because neither guarantees an in-scope result.

## D3 — Import Outcome After Commit

- Replace the public import success payload `Int` with a small typed value containing `restoredCount` and an optional closed warning enum.
- All validation, snapshot, and database replacement failures remain `Result.failure`.
- After commit, SAG FTS rebuild failure becomes `Result.success` with `SAG_FTS_REBUILD_FAILED`; cancellation still propagates.
- `SettingsViewModel` maps normal success and success-with-warning to distinct resource-backed UI messages. It never shows the generic import-failed state for committed data.
- Existing import test helpers return the same outcome type so tests exercise the public contract.

## D4 — Android Backup Privacy Rules

- Add a legacy `full-backup-content` XML and Android 12+ `data-extraction-rules` XML.
- Exclude root/files, databases, shared preferences, external app files, and device-protected equivalents from cloud backup and device transfer.
- Keep `android:allowBackup="false"`; add both manifest references because Android 12+ device-to-device behavior is not fully described by that flag alone.
- Validate the merged manifest instead of only checking source XML.

## D5 — Export Consistency

- Expand the active-work precondition to count both ingest/re-embedding jobs and extraction jobs using their existing active status constants.
- Keep ZIP output paged, but perform all export reads inside a single Room read transaction so counts, closure membership, and emitted rows observe one database snapshot.
- Keep the active-work check outside the transaction for a clear user-facing refusal. The transaction is still the correctness mechanism if a worker changes state immediately after the check.
- Do not buffer all database rows or vector blobs in memory.

Trade-off: a long export holds a read transaction longer. WAL permits normal readers and preserves snapshot consistency; this is preferable to a silently corrupt archive. The existing pagination and active-work refusal limit duration/contention.

## D6 — Portable CI Coverage

- New regressions for this task live in `src/test` and use Robolectric/in-memory Room where Android APIs are required.
- Move only existing tests that are demonstrably independent of native model libraries, GPU/NPU, device performance, real Keystore, full UI instrumentation, and OEM behavior. Do not duplicate the same test in both source sets.
- Default CI continues to compile all `androidTest` sources and runs `testDebugUnitTest`.
- `docs/RELEASE.md` remains the ARM device gate for hardware-dependent tests and gains an explicit command/evidence checklist if the current one is incomplete.

## D7 — R8/Kotlin Compatibility and Release Gate

- Inspect the effective R8 dependency first. Kotlin 2.4 requires R8 9.1.29 or newer according to the Android compatibility table.
- If AGP's bundled shrinker is older, override only the build-time R8 artifact using the official Android build configuration pattern; keep the version in the version catalog or one root build location.
- Regenerate strict Gradle dependency verification metadata only for newly resolved build artifacts.
- Add `:app:assembleRelease` to the existing CI Gradle invocation so minification runs on every push/PR.
- Capture release output in validation and fail the task if the metadata parse warning remains. Do not hide it with warning suppression.

## D8 — Atomic SAF Directory Replacement

For `old != new`:

1. Acquire the new persisted read/write permission.
2. Persist the new URI synchronously with `commit()` so failure is observable.
3. If persistence fails, release the newly acquired permission and keep the old preference/permission.
4. Only after persistence succeeds, best-effort release the old permission.

For `old == new`, retain or reacquire the same permission and do not release it. The public Boolean contract remains unchanged.

## D9 — Documentation Contract

- README and manifest comments list all explicit network paths: model download, webpage import, and configured OpenAI-compatible LLM requests.
- Offline claims are narrowed to local search, local-file import, embedding, graph, and other on-device paths.
- Add a stable docs-consistency assertion only if it can check identifiers/phrases without encoding prose formatting.

## Test Design

- Backup snapshot: persistent location, retention, latest selection, failed write safety, preview/restore round-trip.
- Import outcome: committed data plus forced derived-index failure maps to success-with-warning; pre-commit failure remains failure.
- Search: scoped result below global top-K remains visible; matrix and streaming eligibility; unscoped results unchanged; scoped FTS path.
- Export: active extraction refuses export; a controlled concurrent write cannot create mismatched counts/archive rows.
- SAF: new grant failure preserves old state; successful replacement releases old only after durable preference; same URI is not released.
- Manifest: merged manifest references both rule resources; XML rules exclude every private domain.
- Build: unit, lint, androidTest compile, debug assemble, release assemble, docs consistency.

## Compatibility, Rollback, and Data Safety

- Backup v1/v2/v3 parsing and archive contents remain compatible.
- Database schema/version remains unchanged.
- Existing snapshots in `cacheDir` are not migrated because they were never user-addressable or durable; only new snapshots are guaranteed recoverable.
- Rolling back the app leaves the live database unchanged; new private snapshot files become unused but harmless.
- The R8 override is isolated and can be reverted independently if release validation exposes an upstream incompatibility.

## Expected Files

- `app/src/main/java/com/zhibox/memory/core/backup/KnowledgeBackup.kt`
- `app/src/main/java/com/zhibox/memory/core/search/SearchService.kt`
- `app/src/main/java/com/zhibox/memory/core/model/SharedModelStore.kt`
- `app/src/main/java/com/zhibox/memory/data/db/Daos.kt`
- `app/src/main/java/com/zhibox/memory/ui/SettingsViewModel.kt`
- `app/src/main/java/com/zhibox/memory/ui/SettingsScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/AndroidManifest.xml`
- Relevant `app/src/test/**` tests and selected portable moves from `app/src/androidTest/**`
- `gradle/libs.versions.toml` and/or root build configuration, verification metadata if required
- `.github/workflows/ci.yml`, `README.md`, `docs/RELEASE.md`, and possibly `scripts/check_docs_consistency.sh`

## Explicitly Not Doing

- No paid ARM runner, Firebase setup, credentials, signing, or device provisioning.
- No production x86_64 ABI, ranking redesign, snapshot history UI, scheduled backup, schema migration, or backup format bump.
- No broad repository/ViewModel architecture refactor.

## Refactor Proof

Each change closes a demonstrated boundary failure: persistent restore reaches the existing import flow; source eligibility reaches both retrieval channels before truncation; post-commit warnings cross the data/UI boundary as a value; export reads share one snapshot; SAF permission order preserves the old resource. No new abstraction is introduced unless it is required to make one of these boundaries testable.
