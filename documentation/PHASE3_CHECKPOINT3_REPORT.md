# Phase 3 Checkpoint 3 - Transfer State, Room Migration and Offline Upload UI

Status: PARTIAL — implementation and automated validation passed; new-screen physical/accessibility acceptance pending.
Date: 2026-10-13 (Australia/Sydney).

## Scope implemented

Investigator explicitly accepted continuation from Checkpoint 2 and instructed the next checkpoint. Earlier unfinished inspections and Checkpoint 1 recovery/governance are retained in `PHASE3_OUTSTANDING_CHECKS.md`; “Looks good” is not inferred as evidence of each three-state physical inspection.

Added persisted export/job records, explicit Room 2 -> 3 migration, local simulation repository/runtime, debug disposable-clip provisioning and accessible Approved recordings UI. Migration creates empty tables; local Approve remains distinct from explicit Send, and existing approved clips are not queued. App/package version and signing identity are unchanged. The prior 1 -> 2 migration remains usable and its test fixtures now use the complete migration chain.

The UI shows the Wi-Fi-only default for future live sending and explicitly labels simulation/progress/failure/cancellation/completion. No network request, gateway credential, participant identity/profile, FileSender API secret, upload worker, recipient routing, actual receipt or archival state is implemented. Release provisioning stays disabled. See `PHASE3_TRANSFER_STATE.md` for the state/consistency contract.

Same-source Send taps reuse the existing job. Explicit Retry checks frozen bytes and reuses its job/snapshot; changed Room state requires a new explicit linked snapshot. Snapshot insertion and job state share a DB transaction after file publication. Interrupted jobs become retryable without auto-resume. Delete cancels/hides jobs, purges local scientific export copies and keeps the established tombstone behaviour. Capture can interrupt bounded preparation/hash work without losing the source.

## Files added/modified

- `data/local/ExportSnapshotEntity.kt`, `TransferJobEntity.kt`, their DAOs and `ApprovedExportSource.kt`.
- `EcologicalCaptureDatabase.kt`: Room 3, explicit migration and DAO accessors.
- `ClipDao.kt`, `RoomClipRepository.kt`: cancelled transfer records and safe export purge during local deletion.
- `export/ExportSnapshotRepository.kt`, `LocalSnapshotFiles.kt`: version/hash records and constrained job cleanup.
- `transfer/`: state, eligibility, fake transport, repository and application-owned runtime.
- `ui/transfer/`: view model and Approved recordings screen; Home/Main navigation.
- Debug-only `TransferSimulationActivity.kt` and protected manifest entry.
- `Checkpoint3TransferTest.kt`, opt-in `Checkpoint3RetentionTest.kt`; existing fake DAO/migration test plumbing.
- This report, `PHASE3_TRANSFER_STATE.md`, current decision/checklist and Checkpoint 2 schema continuation notes.

Pre-existing checkpoint changes were preserved. DAT, local Qwen/native model/prompt/frame policy, stored annotation/VLM fields and production manifest permissions remain unchanged.

## Commands executed

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
& 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat' :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain

& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am force-stop com.rchia.ecocapture.phase0
python -B tools/phase3-protocol/checkpoint2_retention.py --serial 49280DLAQ0020M --output tools/phase3-protocol/artifacts/checkpoint3-files-before.json

# Install only the new test APK first, then hash existing rows using Android SQLite read-only APIs.
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am instrument -w -r -e class com.rchia.ecocapture.phase0.export.Checkpoint3RetentionTest -e retentionPhase before com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner

& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M install -r app/build/outputs/apk/debug/app-debug.apk
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am instrument -w -r -e class com.rchia.ecocapture.phase0.export.Checkpoint3RetentionTest -e retentionPhase after com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am instrument -w -r com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner
# Repeat the after-phase audit after regression; no participant row/text leaves the phone.
python -B tools/phase3-protocol/checkpoint2_retention.py --serial 49280DLAQ0020M --output tools/phase3-protocol/artifacts/checkpoint3-files-after-tests.json --compare tools/phase3-protocol/artifacts/checkpoint3-files-before.json
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android diff --check
```

Used replacement installs/direct instrumentation rather than connected-test uninstall/clear-data flows. The baseline was taken before upgrading the target app, using a class-specific audit in the new test APK against the still-installed Checkpoint 2 app. Host artifacts contain only non-content counts/hashes and test summaries. Final source build succeeded in 34 seconds: 134 actionable tasks, 31 executed/103 up to date.

## Final automated validation

| Check | Result |
| --- | --- |
| Offline debug/release/instrumentation build | PASS |
| Debug JVM regression | 65 passed, 0 failures/errors/skips |
| Release JVM regression | 65 passed, 0 failures/errors/skips |
| Final Pixel 9 ordinary suite | 76 passed, 7 opt-in assumption skips, 0 failures; JUnit `OK (83 tests)`, 21.272 seconds |
| New migration/simulation tests | All 10 passed, included above |
| Protected-row opt-in audit | Before upgrade, after upgrade, after initial suite and after final suite all passed; one test per invocation |
| Main data retention | 13 clips including tombstones, 13 annotations, 20 VLM runs; exact logical digests unchanged |
| Main new tables | Empty after upgrade and regression; no legacy autoqueue |
| Protected file retention | All 24 recording-directory files and 2 models have identical SHA-256 before/after |
| Release debug-provisioning exclusion | PASS: activity absent from merged release manifest |
| Whitespace checks | PASS, ordinary LF/CRLF notices only |

The final source includes buffer-level integrity-hash cancellation and accurate cancelled-job deletion timestamps. The final suite adds an actual coroutine cancellation during simulated progress, retaining the completed snapshot/original and persisting FAILED_RETRYABLE. The earlier 75-pass/7-skip suite was also successful but is superseded by the final result. DB physical hashes are not compared across a schema migration; typed, ordered logical row hashes are used instead.

Tests exercise unprovisioned/approval-only zero queue, missing/unapproved/deleted/corrupt rejection before job creation, two concurrent Send calls creating one job, fake failure and same-job retry, explicit post-edit linked versions retaining old JSON bytes, deletion/tombstone/export cleanup, preparation cancellation and persisted interruption/reopen. A disposable migration fixture reconstructs exact v2 entity tables by dropping only the new tables from a seeded isolated database and then applies 2 -> 3; every old row/provenance/decision/null is compared. Actual valued installation retention is checked separately by the opt-in on-device audit.

## Data safety / limits

Only disposable test-pattern HEVC assets, isolated UUID-named databases/cache directories and injected fake transport were used for new functional tests. No real participant clip was exported/sent/deleted; no original DB contents were copied to the host, and no credential/private key was read. DAT/local AI policy stayed unchanged. No real gateway/AARNet tests were run or claimed.

Pixel 9 serial `49280DLAQ0020M`, Android 16/API 36, was unlocked. The final debug simulation chooser launched successfully without selecting/provisioning any existing clip. It is ready for investigator selection. No real TalkBack, glasses capture or Send-screen interaction is inferred from that launch.

Room/file publication has an explicit crash window handled as a persisted interrupted job, not an atomic cross-filesystem claim. UI displays only the latest job per clip; historical linked records remain in Room. Source-change labels describe scientific Room changes; raw MP4 integrity is checked independently. Wi-Fi default is wording/policy intent only; actual network constraints await the upload worker. Full process-kill/reboot/system/capture/withdrawal coverage belongs to later checkpoints; this checkpoint's interrupted-attempt tests inject cancellation and reopen a disposable DB. TalkBack/glasses/large-font usability have not been operated by Codex.

## Follow-up: setup access and disabled-AI feedback (2026-10-14)

Investigator requested the disposable setup screen again and explicitly confirmed that failure notifications should stop when preparation is disabled. Reopened the chooser, added a debug-only `DISPOSABLE CLIP SETUP` button under Home -> Approved recordings, and reinstalled the corrected app using replacement installation. Checkpoint 4 did not begin.

Code inspection found no dedicated Android AI-failure notification: failed WorkManager state was replayed into Review's spoken/live-region error even when its preparation setting was off. Workflow observation now combines current automatic/background preferences with progress. Historical failures for the disabled mode are ignored, and only a matching scheduler failure is cleared when toggled off. Explicit foreground manual-generation errors and unrelated loading/review errors remain visible; saved output/provenance and null exposure timestamps are preserved. Disabled queued attempts return a successful skipped result instead of recording disablement as a failure; stale ready notifications are also gated. The isolated debug recovery worker explicitly bypasses participant preferences as before.

Eight new feedback-policy tests cover disabled/re-enabled modes, clearing a matching visible error, preserving foreground/database errors and retaining saved output/null exposure. Final offline build passed in 21 seconds (134 tasks: 31 executed, 103 up to date). Debug/release unit results: **73 passed per variant**, zero failures/errors/skips. Phone regression: **76 passed, seven opt-in assumption skips**, `OK (83 tests)`, 23.731 seconds. Fresh before/after on-device typed row audits passed; all 24 recording-directory files and two model files retained exact hashes. The audit uses a new named baseline and permits pre-existing simulation tables, so investigator testing history is not presumed empty or cleared. No app uninstall/data clear, source-content copy or external upload occurred.

Two initial compile issues (constructor trailing-lambda compatibility and duplicate setup declarations) were corrected before successful final validation. Test summaries/retention hashes are ignored under `tools/phase3-protocol/artifacts/ai-feedback-20261014-*`. Protected data and the original Checkpoint 3 audit baseline were preserved. The chooser was reopened after testing without selecting or enabling a recording.

Manual follow-up remains: check the new setup button and, with automatic/background preparation off, revisit previously failed clips with TalkBack. Expect no stale failure announcement from those disabled modes; saved descriptions/suggestions remain available. Explicitly requested foreground generation remains available and its genuine errors remain visible. This is not evidence that human TalkBack acceptance has passed.

## Recommended next checkpoint (unchanged)

Checkpoint 4: HTTPS gateway, scoped installation authentication, resumable harmless input and mock forwarding. Hosting/domain/operator/institutional gates remain unresolved. This report does not authorize provisioning infrastructure or participant credentials.

## Your manual checks

Ready after the installed debug build:

1. Approve a disposable clip and open Home -> Approved recordings. Expect no automatic attempt and sending disabled until explicitly enabled for simulation.
2. Open Disposable clip setup from Approved recordings (ADB fallback remains in `PHASE3_TRANSFER_STATE.md`), select only that disposable clip, then explicitly press Send. Expect clearly labelled local simulation progress/completion, with the recording intact.
3. Use Review recording to save an edit, return and check “New local changes since last send”. Editing starts nothing; Send updated recording creates a new version.
4. On another disposable clip, enable Fail next simulation; expect recoverable failure, intact media and successful Retry. Check Cancel during progress retains the video. Delete only a disposable clip through Review and verify it stays absent from Approved recordings after reopening.
5. With TalkBack and enlarged text, navigate Send/status/Retry/Cancel/Review/Back; expect understandable labels, short announcements and reachable wrapped controls. Confirm existing valued recordings/descriptions/decisions still appear normally.
6. Turn automatic/background AI preparation off and revisit previously failed clips. Expect no stale failure announcements for those disabled modes; saved descriptions/suggestions remain. Report PASS/FAIL for this follow-up.

Report PASS/FAIL/NOT TESTED. Deferred backup-key recovery and earlier explicit inspections remain in `PHASE3_OUTSTANDING_CHECKS.md`; automated retained-row/file hashes do not need repeating manually.

STOP. Do not begin Checkpoint 4 without explicit investigator approval.
