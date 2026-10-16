# Phase 3 Checkpoint 2 - Immutable Local Event Snapshot + Manifest

Status: PARTIAL — implementation and automated checks passed; three disposable-clip manual inspections pending.
Date: 2026-10-13 (Australia/Sydney).

Continuation: investigator said “Looks good” and explicitly approved Checkpoint 3 implementation. Its Room 3 migration and local simulation are reported in `PHASE3_CHECKPOINT3_REPORT.md`. The earlier specific three-state human inspections remain in the running checklist until evidence is supplied.

## Scope implemented

Investigator explicitly instructed continuation and deferred Checkpoint 1 backup recovery. `PHASE3_OUTSTANDING_CHECKS.md` is the running tab of unfinished work. Earlier Checkpoint 1 stop/pending statements are superseded only for this authorized local checkpoint; the checkpoint remains PARTIAL and participant uploads remain unavailable.

Implemented `ApprovedEventSnapshotBuilder`, `EventSnapshotSerializer`, `EventManifest` and a transactional read/compare Room adapter. Each approved event produces a three-file bundle with byte-identical `video.mp4`, exact saved scientific `event.json` and SHA-256 `manifest.json`. Hashes/lengths are persisted in a local engineering record outside the bundle; its whole job is atomically published. Room is still version 2. The planned Room snapshot/job migration is Checkpoint 3, so engineering file persistence is used here rather than prematurely implementing that migration.

All existing annotation revisions/current selection and every VLM output/provenance column are exported. Empty/participant-only/VLM-only/both/amendment states are valid. Null first presentation remains null. There is no separate stored approval timestamp: exported `approved_at_epoch_ms` is null and the original update time keeps its original meaning. Source paths/sidecars are excluded; codec/start time/profile fields are not fabricated. The debug chooser provisions no subject code; exported study code is null.

Room rows are read consistently, then compared inside the publication transaction. Edit/deletion/output-disposition changes abort stale exports. Streamed source hashes are checked against the copy and again before publication. Android validates MP4 boxes, video track and a bounded readable sample; this does not prove every frame decodes. Normal failure/cancellation cleans only owned staging, preserving original media/data. Debug preparation uses the existing capture gate and defers/cancels for capture. Stale canonical UUID staging at least 24 hours old can be cleaned explicitly; completed jobs remain immutable.

Added shell-DUMP-permission-protected debug inspection, JSON schemas, independent stdlib Python schema/hash/linkage verification, fixed-name USB extraction, and read-only on-device retention hashing. No new network calls/permissions, Send controls, transfer worker, credentials, crypto, remote AI or gateway were added. Existing INTERNET permission for DAT predates this checkpoint.

## Files added/modified

- `app/src/main/java/com/rchia/ecocapture/phase0/export/`: builder, serializer, manifest, Room adapter.
- `app/src/debug/java/com/rchia/ecocapture/phase0/export/SnapshotInspectionActivity.kt` and debug manifest.
- `app/src/test/.../export/ApprovedEventSnapshotTest.kt`: 13 new JVM tests.
- `app/src/androidTest/.../export/RoomEventSnapshotTest.kt`: four isolated Android tests.
- `documentation/schemas/`: event and manifest JSON schemas.
- `tools/phase3-protocol/verify_snapshot.py`, `pull_snapshot.py`, `checkpoint2_retention.py` and two Python test files.
- `PHASE3_EVENT_SCHEMA.md`, this report, `PHASE3_OUTSTANDING_CHECKS.md`, decision log and continuation notes in previous reports.

Pre-existing uncommitted Checkpoint 1 changes and sender files were preserved. No app ID/version, production manifest, Room entities/migration, capture, local Qwen, prompt, model or annotation/review code was changed.

## Commands executed

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
& 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat' :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain

python -B -m unittest discover -s tools/phase3-protocol -p 'test_*snapshot.py' -v

& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am force-stop com.rchia.ecocapture.phase0
python -B tools/phase3-protocol/checkpoint2_retention.py --serial 49280DLAQ0020M --output tools/phase3-protocol/artifacts/checkpoint2-retention-before.json

& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M install -r app/build/outputs/apk/debug/app-debug.apk
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python -B tools/phase3-protocol/checkpoint2_retention.py --serial 49280DLAQ0020M --output tools/phase3-protocol/artifacts/checkpoint2-retention-after-install.json --compare tools/phase3-protocol/artifacts/checkpoint2-retention-before.json --include-database

& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am instrument -w -r com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner | Set-Content -LiteralPath tools/phase3-protocol/artifacts/checkpoint2-instrumentation.txt -Encoding UTF8
python -B tools/phase3-protocol/checkpoint2_retention.py --serial 49280DLAQ0020M --output tools/phase3-protocol/artifacts/checkpoint2-retention-after-tests.json --compare tools/phase3-protocol/artifacts/checkpoint2-retention-before.json --include-database

git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android diff --check
```

Used replacement installs/direct instrumentation, the retention-safe procedure from `tools/run-room-tests.ps1`; no connected-test uninstall task, uninstall or clear-data operation. Final source build: successful in 12 seconds, 134 actionable tasks, 27 executed/107 up to date. Tests ran freshly after source correction; instrumentation APK reused compiled test code on the final build.

## Automated tests

| Validation | Final result |
| --- | --- |
| Debug JVM regression | 65 passed, 0 failures/errors/skips |
| Release JVM regression | 65 passed, 0 failures/errors/skips |
| New JVM snapshot tests | 13 per variant, included above |
| Independent verifier/extraction Python tests | 14 passed, 0 failures/errors/skips, 0.097 seconds |
| Pixel 9 ordinary instrumentation | 66 passed, 6 opt-in assumption skips, 0 failures; JUnit `OK (72 tests)`, 19.015 seconds |
| New Room/MP4 snapshot tests | All four passed, included above |
| Real USB helper invocation | Wholly synthetic Kotlin fixture staged in a new app-private job, copied with `pull_snapshot.py`, schema/hash verified; exact synthetic device job removed after resolving its path |
| Replacement-install protected-file inventory | Database + recordings + models identical |
| Post-instrumentation protected-file inventory | Database + recordings + models identical |

JVM tests cover all five annotation states, exact output/provenance/text, deterministic serialization, clock-reversal lineage, null timestamps, immutable published versions, corrupt/missing media, duplicate IDs, concurrent annotation edit/deletion, source-byte mutation including immediately before publication, cancellation and stale-staging cleanup. Cross-language synthetic fixtures were generated by Kotlin and parsed/schema/hash-verified by Python, including accented text/emoji. Python rejects same-length damage, truncation, missing/extra files, directory substitution, bad linkage/current pointer, unknown profile fields and archive links/traversal/duplicates.

Android tests use the existing generated HEVC test-pattern asset and UUID-named disposable databases/cache files. They cover all five scientific states (with multi-revision amendment), exact source/Room preservation, edits/tombstones before publication, corrupt source and persistence through database reopen. They do not use valued clips or run real Qwen generation.

## Issues corrected during validation

- Initial sandboxed Gradle invocation failed generating catalog classes; the same offline build succeeded with approved access to the existing Gradle cache.
- Generated host fixture paths exceeded Windows copy API limits. Shortened only ignored fixture paths.
- Independent test code initially used Windows locale decoding for UTF-8 exports. Explicit UTF-8 corrected the text round-trip assertion.
- First device suite had three new-test failures because Android's app-directory aliases were rejected by overly strict absolute/canonical equality. Trusted roots now resolve aliases while rejecting leaf symlinks and out-of-root source media. Final full suite passed. Initial failures are not counted as passes.

## Data safety / retention

Pixel 9 serial `49280DLAQ0020M` reported RUNNING_UNLOCKED. SHA-256 was computed on the phone; only hashes and hashed path identities were retained in ignored local inventories. No participant video or database contents were copied to the workspace. All three existing main database files, 24 recording-directory files and two local model files had identical SHA-256 values before/after replacement installation and again after the full device suite. These counts are files, not inferred counts of clip/annotation rows. Android isolated fixture databases/media were cleaned normally.

No FileSender request or external transfer occurred. No private encryption identity or API configuration was read. Nothing was marked remotely received or archived. Engineering copies of explicitly selected disposable clips require manual cleanup after inspection; integration with normal Delete and production retention remains future checkpoint work.

The debug chooser was launched successfully through `adb shell am start` without selecting any existing recording. It is ready for investigator selection. A separate actual USB helper check used only the Kotlin-generated structural MP4 fixture with synthetic text; it does not establish real MP4 playback or human acceptance. That newly staged synthetic job was removed after its app-private absolute path was checked. Host synthetic results remain ignored.

## Known limitations / recommended next checkpoint

The three real disposable-clip inspections have not been performed; Checkpoint 2 remains PARTIAL until those pass. The debug chooser and USB copy tool are engineering inspection, not participant Send. Backup recovery and institutional gates remain in the running checklist. Checkpoint 3 is transfer-state records, explicit Room migration and offline/fake upload UI, and requires separate continuation approval.

## Your manual checks

Ready on the installed debug build:

1. Open the debug chooser using the command in `PHASE3_EVENT_SCHEMA.md`. Choose only approved disposable clips representing no annotation, participant-only annotation and a saved VLM amendment/revision. Expect a local snapshot-ready result; busy capture/AI should defer preparation.
2. Copy each explicitly selected snapshot using `pull_snapshot.py` with its displayed event/snapshot UUIDs and a new ignored output folder. Expect `SNAPSHOT_SHA256_SCHEMA_VERIFIED`. Inspect each `bundle/event.json` locally and replay its `video.mp4`; descriptions, amendment history and original VLM wording should match the app. Report PASS/FAIL for each state without pasting scientific text.
3. Confirm the normal app still plays existing recordings and retains descriptions and Approve/Review Later decisions. Automated protected-file comparison already passed; do not repeat hashes manually.
4. Keep disposable inspection exports local and discard them after acceptance. Deferred Checkpoint 1 recovery/crypto/rotation/notification and later release checks remain in `PHASE3_OUTSTANDING_CHECKS.md`; they need not be performed now.

STOP. Do not begin Checkpoint 3 without explicit investigator approval.
