# Phase 2 Checkpoint 4: verified model manager and isolated JNI lifecycle

Implemented on 2026-10-01. Stop here for participant regression checks and approval before Checkpoint 5.

## Changes

- `VlmModelManager.kt`: explicit app-owned model discovery, both official byte sizes and streamed SHA-256 checks; observable installation/verification states; typed failures; cancellable hashing and pre-load file metadata checks.
- `NativeQwen3VlBridge.kt` and `qwen3vl_jni.cpp`: lazy library loading, runtime provenance, serialized ownership, CPU model/context/projector load, partial failure cleanup, cancellation requests and idempotent unload. No native pointers leave JNI. Unload cleanup is non-cancellable.
- `VlmEngine.kt`: added typed verification, context, linkage, provenance and cancellation failures.
- `vlm-native/CMakeLists.txt`, `LLAMA_CPP_COMMIT`, native README, `app/build.gradle.kts`, `.gitignore`: ARM64-only library, exact clean upstream commit enforcement, NDK r28c/CMake 3.29.2, static libc++, GPU backends disabled, 16 KiB page alignment and ignored build artifacts.
- `VlmModelManagerTest.kt`, opt-in `Checkpoint4NativeTest.kt`, `provision-qwen-models.ps1`, `run-checkpoint4-tests.ps1`: verification, actual native lifecycle/memory measurement and post-unload HEVC playback checks; replacement installs preserve app data.
- `VLM_RUNTIME.md`: current native configuration and separation from historical CLI results.

The exact 2,951,255,968-byte official bundle is provisioned under app-private `files/models/qwen3vl`, outside the APK. Runtime commit: `0c1e57098bba43ac29e6e3b677cdceebdd22334f`. Participant screens still use FakeVlmEngine; generation and frame sampling are later checkpoints. Optional generation stays independent of recording decisions. Prompt version and existing raw-output/amendment separation are unchanged.

## Build and verification

Successful offline Gradle run: `:app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`. Debug native configuration is Debug; release is RelWithDebInfo. All 46 JVM tests passed per variant, including eight new verification tests: missing, incomplete/zero-size, truncated, same-size corruption, correct hash, file metadata change, wrong filename and invalid directory handling.

The debug APK contains `lib/arm64-v8a/libecocapture_qwen3vl.so` (14,375,488 bytes after stripping), with no GGUF files. ELF LOAD segments have alignment `0x4000`. Existing Phase 1 capture/playback code is unchanged.

Device: Pixel 9, Android 16, serial `49280DLAQ0020M`. Direct heavy instrumentation completed `OK (1 test)` in 36.749 seconds. Checks passed: official bundle READY, expected runtime, missing language/projector native failures and cleanup, both successful load/unload cycles, rejection of a duplicate load, idempotent unload, and Media3 HEVC first frame after unload.

Final ordinary device regression: 36 tests passed, with the heavy native test skipped (`OK (37 tests)`, 7.092 seconds). The separate opt-in run above passed the heavy test. Replacement installs left the app installed; all seven original recording MP4s and sidecars remained with their original sizes. `git diff --check` passed. Physical capture and TalkBack still require manual checking.

## Final native memory/timing measurements

| Stage | RSS (KiB) | PSS (KiB) | Native allocated bytes |
| --- | ---: | ---: | ---: |
| Before verification | 194,424 | 71,347 | 6,577,536 |
| After partial failure cleanup | 237,376 | 113,649 | 9,203,312 |
| First load | 3,444,564 | 3,338,509 | 2,294,252,464 |
| First unload | 217,396 | 111,121 | 9,204,768 |
| Second load | 3,529,828 | 3,419,854 | 2,294,273,168 |
| Second unload | 220,332 | 110,490 | 9,196,800 |
| After playback | 224,576 | 106,640 | 9,929,744 |

Initial hash verification took 3.669 seconds. Verification plus load took 19.950 seconds for cycle 1 and 6.481 seconds for cycle 2. Complete cycles including measurements/unload took 20.674 and 7.061 seconds. Observed process high-water RSS: 3,922,128 KiB (3.74 GiB). These are debug load-only measurements, with uncontrolled file caches and scheduling; they are not inference benchmarks. Unload releases model allocations, while some allocator/library memory remains resident. Two cycles do not establish long-term leak freedom.

Reports: ignored `tools/vlm-feasibility/artifacts/results/checkpoint4/instrumentation.txt` and `memory-and-timing.txt`; also app-private `files/checkpoint4-native-report.txt`.

## Playback fixture correction

The initial test run passed native loading but failed MediaExtractor on the local `clips/clip_439.mp4` fixture. Its bytes begin with a UTF-16 BOM and text-expanded MP4 header, indicating an earlier damaged binary export. The runner now rejects that header. The successful run copied finalized glasses recording `clip_20261001_180119_450.mp4` (104,621 bytes) into an isolated app-cache file and confirmed `video/hevc` plus a rendered frame. The source recording and its record were untouched. No app uninstall or data clearing was performed.

## What to check manually before approval

1. Open Review Recordings. Confirm your existing recordings, descriptions and review states remain present.
2. Open at least three clips. Play, pause, replay and return to the queue; confirm video and controls work normally after this native test.
3. Capture a new glasses recording, then play it from Review Recordings.
4. On a disposable recording, exercise description editing, suggestion use/rejection/regeneration and automatic-preparation preference. AI output should still clearly be simulated at this checkpoint.
5. On disposable recordings, check Approve and Review Later. Use Delete only on a recording you intend to remove. Decisions must remain available independently of AI preparation.
6. With TalkBack and your preferred text size, check queue navigation, description editing, pinned suggestion actions and bottom recording controls. Automated controller tests do not establish screen-reader usability.

No new participant-facing native controls are expected. Model status/load verification was performed by the engineering test. Physical glasses capture and TalkBack checks remain for the user. Native generation, saved-video frame sampling and real participant lifecycle integration have not started.
