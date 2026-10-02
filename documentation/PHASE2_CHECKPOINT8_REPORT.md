# Checkpoint 8 — Restart, cancellation, error and deletion recovery

Implementation/validation date: 2026-10-02. **Automated recovery validation passed on Pixel 9 (`49280DLAQ0020M`).** Device checks initially waited for USB reconnection, then completed through replacement installs and direct instrumentation. Physical participant checks remain listed below. User approval authorized proceeding to Checkpoint 9.

## Changes

- Deletion commits the DELETED tombstone and erases all participant annotation history and VLM runs in one Room transaction before removing media. A database failure leaves media intact. A file-removal failure leaves a hidden tombstone that startup reconciliation retries. The selected review screen offers Retry Delete while files remain.
- The tombstone preserves basic clip metadata for reconciliation, without participant or VLM text. Retrying retains the original deletion timestamp. Later approval/review updates cannot restore deleted clips. Late VLM saves and amendments are rejected.
- Review deletion waits for inference cancellation and cleanup, then finishes deletion without screen disposal interrupting it. A successful deletion clears the clip's ready notification.
- Background submission and cancellation are serialized so cancellation cannot precede an unfinished submission. Cancellation has an application-owned scope, survives review exit, and reports Cancelling until engine cleanup finishes. WorkManager success is only shown as a usable suggestion when its persisted run exists.
- Background workers check low-memory state before loading. Critical memory callbacks cancel the current attempt, unload, and retry later; moving the UI into the background does not itself trigger this cancellation. Generation failure and out-of-memory errors never persist partial output as successful descriptions.
- Existing request IDs remain idempotent across retries. Completed raw output, uncertainty and model provenance remain unchanged; preparation creates no participant amendment or presentation event. Recording retains priority over inference.

No schema version change, prompt change, model change, or demographic/clinical input was introduced.

## Validation completed

Final command:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
& 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat' :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:testReleaseUnitTest --offline --console=plain
```

- Debug, release and instrumentation APK compilation passed.
- Debug: 52 JVM tests, zero failures/errors.
- Release: 52 JVM tests, zero failures/errors.
- Updated deletion unit tests verify tombstone-first ordering, recoverable media-removal failure and media preservation on database failure.
- Host recovery script parses successfully. The disposable recovery worker is absent from compiled release classes.
- `git diff --check` passed (line-ending conversion warnings only).

These results establish compilation and JVM behavior, not Android/Room or native recovery success.

## Device validation completed

Final ordinary device suite: **62 passed, 5 opt-in tests skipped (JUnit reports OK, 67 tests)**, including seven Checkpoint 8 tests and the seven-state Checkpoint 9 matrix. All eleven host scenarios below passed. Background work completed with one engine invocation; force-stop, abrupt process kill and critical-memory interruption each restarted with two invocations and persisted exactly one result. Active-worker notification cancellation used the real WorkManager Cancel pending intent and saved no result.

Actual Qwen/JNI cancellation also passed: **123 ms cancel/join in this run**, no successful result returned, unload/reload successful (14.503 seconds for the complete test). This does not bound cancellation during a larger vision encoding call. Physical memory exhaustion and actual native decode faults were not induced; their error paths use typed failures.

Before/after logical comparison confirmed all **10 active clips, 13 annotation records and 16 VLM runs** remained exactly equal, including existing descriptions, provenance and review decisions. Local snapshots and results remain in ignored `tools/vlm-feasibility/artifacts/results/checkpoint8`. The app remained installed and was reopened after checks.

Initial validation issues: an approved fixture accidentally retained DEFERRED review state and was corrected to REVIEWED; a test wait was bounded and lifecycle diagnostics added after a timeout. One later workflow timeout did not recur in the clean final suite; these waits are not phone latency guarantees. Finishing instrumentation kills its process, so the host protocol was corrected to hold instrumentation alive during intended background/system events. Stale disposable jobs were cleaned before final regression. A PowerShell summary serialization issue was corrected and its final artifact flattened to eleven results. These earlier attempts are not counted as successful final validations.

Seven isolated Room/workflow tests in `Checkpoint8RecoveryTest` cover exact reopen and idempotency, text purge and partial deletion recovery, tombstone protection, database failure, typed engine failures, foreground cancellation, and recording priority with cleanup.

The opt-in `Checkpoint8SystemRecoveryTest`, debug-only `Checkpoint8RecoveryWorker`, and `tools/run-checkpoint8-recovery.ps1` prepare eleven disposable WorkManager scenarios:

1. Missing model in an isolated directory.
2. Hash mismatch in tiny disposable files.
3. Actual frame extraction from an invalid disposable MP4.
4. Injected native failure.
5. Injected out-of-memory failure.
6. Completed output followed by force-stop/relaunch.
7. Active background preparation after Home.
8. Active preparation interrupted by force-stop and reopened.
9. Active preparation interrupted by abrupt process kill and reopened.
10. WorkManager notification Cancel pending intent.
11. Critical-memory callback and retry.

The harness uses separate UUID-named databases, cache media and marker files. It does not use participant recordings or official model files as fixtures. Native failure/OOM are injected typed failures, not induced physical memory exhaustion or actual JNI faults. Actual cancellation was separately rerun with the official models using the existing Checkpoint 6 opt-in test; its media fixture was disposable.

Reproduction: snapshot participant database state; replacement-install APKs through `tools/run-room-tests.ps1`; run ordinary instrumentation and `tools/run-checkpoint8-recovery.ps1`; run the native cancellation opt-in; compare snapshots with `tools/check-checkpoint-database-retention.py`; reopen the app. Never use connected-test uninstall/clear-data flows.

## What the participant needs to check after installation

1. Start background preparation on a disposable clip, leave review, switch apps, and confirm the preparation notification remains available. Cancel it; confirm no partial suggestion becomes a description and editing/decisions still work.
2. Allow a suggestion to finish, close and reopen the app, and confirm output remains available without automatically replacing the participant description or changing the review decision.
3. Begin recording while preparation runs. Confirm cleanup finishes before recording starts and the new recording captures/plays normally.
4. Use a disposable recording with a suggestion and edited description to check Delete. Reopen the app and confirm it stays absent. Do not delete valued research recordings for this check.
5. Confirm the three previously filled descriptions and existing Approve/Review Later decisions remain intact. Check TalkBack announces waiting/cancelling/error states and Retry Delete where applicable.

Checkpoint 9's automated provenance/regression work followed the user's approval. Physical glasses and TalkBack acceptance remain required before Phase 2 completion.
