# Instrumentation Test Hangs (P1)

## Symptom

`connectedDebugAndroidTest` advances through most tests, then remains on one Compose UI or
Activity recreation test for tens of minutes. UTP may keep waiting because its host timeout is
far longer than a useful CI or release gate.

## Root Cause

The suite mixed three workloads with different reliability contracts:

- stateless Compose rendering/interaction that does not require a device;
- OEM-sensitive lifecycle operations such as real Activity recreation;
- opt-in evaluation, performance, and network runs.

On MIUI/Android 14, no-Activity Compose hosts and real recreation can block below JUnit, so a
JUnit timeout alone may not interrupt the system call. A second instrumentation runner using the
same device can create the same apparent stall.

## Fix

1. Put portable Compose tests in `src/test` and run them with Robolectric native graphics.
2. Mark evaluation, performance, network, and OEM-sensitive lifecycle tests `@LargeTest`.
3. Exclude `@LargeTest` from the default instrumentation runner and set finite per-test and host-task timeouts.
4. Before diagnosing a stall, inspect `dumpsys activity processes` for another active runner.

## Prevention

- New Compose tests start in Robolectric unless they exercise a device-only API.
- `connectedDebugAndroidTest` remains a bounded stable-regression gate; `@LargeTest` is explicit.
- A fixture used by many tests must use a terminal job state unless the test specifically verifies
  active-job behavior, so new export guards do not invalidate unrelated round-trip cases.

## Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

If the device suite stalls, identify the current method and competing runner before editing code:

```bash
adb shell dumpsys activity processes | rg -A8 -B2 'Active instrumentation|mInstrumenting'
```
