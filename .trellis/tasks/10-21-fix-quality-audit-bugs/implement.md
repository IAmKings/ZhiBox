# Implementation Plan — Code Quality Audit Bug Fixes

## Ordered Checklist

1. Establish regression tests before production edits.
   - Add source-scoped ranking cases covering global-top-K starvation and unscoped parity.
   - Add backup import outcome and persistent snapshot tests.
   - Add active-extraction export guard/consistency coverage.
   - Add SAF permission replacement-order tests.
2. Fix backup correctness and recovery.
   - Move snapshots to app-private persistent storage and expose newest-snapshot lookup.
   - Wrap export reads in one Room transaction and extend active-work guards.
   - Return typed import success/warning outcomes while preserving cancellation and pre-commit failures.
3. Wire recovery through Settings.
   - Add snapshot availability/state and restore action.
   - Reuse import preview, confirmation, and execution paths for URI and internal-file sources.
   - Add resource-backed success-with-warning text.
4. Fix source-scoped retrieval at the candidate boundary.
   - Add the minimal DAO queries for ready chunks/FTS rows by source.
   - Apply eligibility in matrix, streaming, keyword, and fusion paths before truncation.
   - Remove redundant post-fusion filtering.
5. Fix atomic shared-model-directory replacement.
   - Acquire, durably persist, then release; roll back the new grant if persistence fails.
   - Preserve same-URI permission.
6. Close Android backup privacy configuration.
   - Add legacy and Android 12+ exclusion XML.
   - Reference both from the manifest and verify merged output.
7. Strengthen the free CI lane.
   - Move selected portable instrumentation tests to Robolectric without duplication.
   - Keep native/hardware tests compiled and document the ARM release command/evidence gate.
   - Add release assembly to CI.
8. Align the release shrinker.
   - Determine the effective R8 version.
   - Apply the smallest official build-time override needed for Kotlin 2.4 compatibility.
   - Update strict dependency verification metadata only if resolution changes.
9. Correct network/privacy documentation and add a stable consistency assertion where practical.
10. Run focused tests after each boundary change, then run the complete validation matrix.
11. Run `trellis-check`, update applicable Trellis specs with durable lessons, and review the final diff for unrelated changes.

## Validation Commands

```bash
bash ./scripts/check_docs_consistency.sh
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest
./gradlew --no-daemon --stacktrace :app:lintDebug
./gradlew --no-daemon --stacktrace :app:compileDebugAndroidTestKotlin
./gradlew --no-daemon --stacktrace :app:assembleDebug :app:assembleRelease
```

Additional checks:

- Inspect the merged debug and release manifests for `fullBackupContent` and `dataExtractionRules`.
- Inspect release output for Kotlin metadata/R8 parse warnings; zero occurrences required.
- Confirm no duplicate portable test remains in both `test` and `androidTest`.
- Confirm git status contains only task changes plus the user's pre-existing untracked project tooling files.

## Risky Files and Rollback Points

- `KnowledgeBackup.kt`: highest data-integrity risk; keep test checkpoints for snapshot, import commit, and export transaction independently revertible.
- `SearchService.kt` / `Daos.kt`: ranking regression risk; compare unscoped output before/after and avoid schema changes.
- `SharedModelStore.kt`: permission-loss risk; failure-path test must pass before UI wiring.
- Build/R8 configuration and verification metadata: release-only risk; isolate from application dependency changes and require both debug/release builds.
- CI workflow: syntax/configuration can only be fully proven by GitHub; validate YAML locally where tooling exists and keep Gradle commands locally reproducible.

## Follow-up Gate Before Implementation

- PRD has no open product/scope/risk decisions.
- Design maps every defect to its owning boundary and regression test.
- No sub-agents are used; `implement.jsonl` and `check.jsonl` remain intentionally empty for this inline workflow.
- Start implementation only after the user approves the final planning summary.
