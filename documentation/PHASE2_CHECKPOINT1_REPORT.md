# Phase 2 Checkpoint 1: annotation and VLM provenance foundation

Date: 2026-10-01. Implementation and automated checks complete; manual acceptance/data recovery pending. Stopped before Checkpoint 2.

## Implemented behavior

Checkpoint 1 completes the persistence foundation begun by the approved prompt-v2 update. Room remains version 2, with the explicit 1 → 2 migration adding `annotations` and `vlm_runs`. The prior clip table, decisions and tombstones are unchanged. No further schema change was needed for this checkpoint.

Participant descriptions can be added, revised, observed and queried with history. A revision leaves prior text intact, marks the prior row noncurrent, and points to it with `supersedesAnnotationId`. Saving against a stale current ID fails instead of silently replacing a newer edit. Amendments retain `PARTICIPANT_AMENDMENT` and their `parentVlmRunId` through later revisions; even unchanged endorsements remain separate records. Empty text and deleted/missing clips are rejected. Annotation operations do not change clip decisions.

`VlmEngine` exposes load/generate/unload/model information independently of Room, ViewModels and the UI. Its request contains a saved MP4 path and versioned system/user prompt, with no participant clinical/profile fields. Typed load/generation failures are represented separately from successful output. Kotlin coroutine cancellation propagates, so cancelled generation cannot be returned as success.

`FakeVlmEngine` generates deterministic, explicitly simulated output after a configurable delay. It does not decode the MP4 or load Qwen. Model ID `fake/ecological-scene-v1`, runtime `FakeVlmEngine`, quantization `NONE`, blank GGUF hashes, and simulated configuration with zero sampled frames distinguish its provenance from real inference. A lifecycle mutex serializes operations on each fake instance; native process-wide scheduling remains work for the real engine. Fake loading is explicit, repeated load/unload works, and cancellation releases its lifecycle lock.

`VlmRunRepository` saves immutable output/provenance snapshots and queries history. It assigns a new run ID, preserves raw text/uncertainty verbatim, and sets `NOT_PRESENTED` with a null first-presentation timestamp. Persisting output never creates a participant annotation or changes approval. The DAO rejects duplicate run IDs rather than replacing their output.

The current prompt stays `ecological_scene_description_v2` with the exact user-supplied system/frame wording. No participant-facing UI, DAT capture path, playback controls, package identity, dependencies, network/download infrastructure, or real native inference integration was changed by this checkpoint.

## Files added in this checkpoint

Paths are relative to `app/src/main/java/com/rchia/ecocapture/phase0/` unless stated otherwise:

- `vlm/VlmEngine.kt`, `VlmRequest.kt`, `VlmResult.kt`, `VlmModelInfo.kt`, `FakeVlmEngine.kt`.
- `data/VlmRunRepository.kt`.
- `domain/VlmDisposition.kt`.
- `app/src/test/java/com/rchia/ecocapture/phase0/vlm/FakeVlmEngineTest.kt`.
- `tools/run-room-tests.ps1`.
- `documentation/PHASE2_CHECKPOINT1_REPORT.md`.

Modified: `data/AnnotationRepository.kt`, `data/local/AnnotationDao.kt`, `vlm/VlmPrompt.kt` (typed prompt definition only), `app/src/androidTest/java/com/rchia/ecocapture/phase0/data/VlmOutputPersistenceTest.kt`, the Phase 2 implementation plan, and `documentation/VLM_RUNTIME.md`.

Previously approved files reused: both entities/DAOs, `AnnotationSource`, the explicit Room migration, and the v2 prompt/contract tests.

## Commands and results

The repository has no runnable Gradle wrapper script/JAR. The existing Gradle 8.14.1 distribution and Android Studio Java were used:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest --offline --console=plain
```

Build successful. Debug and instrumentation APKs built. All 33 JVM tests passed in **each** debug/release variant: 23 Phase 1 tests, 5 v2 prompt tests, and 5 fake-engine tests. All 12 instrumentation tests passed on Pixel 9 / Android 16: 3 existing Phase 1 persistence tests and 9 annotation/VLM foundation tests.

Coverage includes migration preserving every legacy clip field and approved/deferred/deleted states; participant insert/revision/reopen/history/current-flow query; competing stale revisions; invalid revision rollback; amendment parent/revision preservation; unchanged endorsements; immutable original VLM output; multiple persisted VLM results and provenance; null exposure timestamp; missing/deleted clip guards; fake load/unload/reload, typed failures, deterministic output, cancellation and source-file preservation. Fake tests use synthetic byte fixtures and do not establish real MP4 decoding or model behavior.

The initial unqualified `test assembleDebug assembleDebugAndroidTest` invocation selected a pre-existing Android plugin on the root project, which has no root `src/main/AndroidManifest.xml`; it failed. Module-qualified tasks resolved that task selection issue. A duplicate typed-prompt property introduced during editing was removed before the successful build. The existing vibrator deprecation warning remains.

### Device-test cleanup incident and correction

The connected-test run used Gradle's UTP installer. Its log, `app/build/outputs/androidTest-results/connected/debug/Pixel 9 - 16/utp.0.log`, confirms `uninstall_after_test: true` and explicitly records uninstalling both the target and test package. The target package was absent after testing. This can remove app-private recordings and database contents, so the upgrade-with-data-retention requirement was **not** met by that test run. No claim is made that old participant media or decisions survived. The user was notified and asked about data/backups. One MP4 and its JSON sidecar exist in the workstation's `clips/` directory; they were left untouched and have not been restored or assumed to be a complete backup.

The debug APK was reinstalled. Future research-phone tests use the added runner:

```powershell
& ./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
```

It performs `adb install -r` for the app/test APKs, runs `am instrument -w com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner`, and verifies that the target package remains installed. It does not uninstall either package or clear app data.

All 12 tests passed again through direct instrumentation. A second run verified that an app-private sentinel file created solely for this retention check survived replacement installation and testing. The sentinel was removed afterward. This proves the new runner's retention path; it does not recover data removed by prior cleanup. The test methods themselves delete only their disposable test databases.

The implementation plan now specifies this direct runner for research phones and warns against the current Gradle connected-test cleanup. `git diff --check` and the runner's PowerShell syntax check passed.

## Manual acceptance still required

After data recovery status is resolved, verify old recordings and decisions against an upgrade made with `adb install -r`. Open the queue, play/pause/replay existing HEVC clips, exercise Review Later and Approve on disposable test clips, and capture a new glasses recording. Confirm the tombstones and remaining media are preserved. Physical glasses capture and TalkBack were not operated by Codex in this checkpoint. No production clip was deliberately deleted through the app UI.

## Performance and known limitations

No real VLM inference ran in this checkpoint. Fake delay is simulated latency, not a Qwen performance measurement. Native timings and v2 model-compliance failures remain recorded separately in `VLM_RUNTIME.md`; stronger prompting still does not guarantee conservative fine-detail output.

Process-wide real-inference scheduling, actual frame extraction, native cancellation, UI exposure/disposition transitions, and annotation/VLM cleanup on clip deletion belong to later checkpoints. The new persistence APIs are not yet invoked by participant-facing UI. Room close/reopen establishes disk persistence, not Android process-death behavior.

Checkpoint 2's participant annotation UI has not begun. Wait for user review and resolve the device-data incident/manual acceptance before continuing.
