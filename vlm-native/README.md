# Local SmolVLM2 native component

The active model is now **SmolVLM2-500M-Video-Instruct, Q8_0**, with three chronological frames at 15%, 50% and 85% (maximum long edge 1024). See [current setup and validation](../tools/smolvlm2/README.md). Historical Qwen measurements below do not describe SmolVLM2 performance.

The Android library is `ecocapture_vlm`; Kotlin uses `SmolVlm2Engine`, `NativeVlmBridge` and `SMOLVLM2_PARTICIPANT` / `SMOLVLM2_ENGINEERING`. The pinned llama.cpp commit already supports SmolVLM2. Generation applies the verified model's conversation template, rather than forcing ChatML. Old persisted model IDs and descriptions are retained.

The original Checkpoint 6 added RGB frame ingestion and local greedy generation to the verified model lifecycle. Checkpoint 7 enabled it in participant review. Isolated workflow checks can still select simulation.

## Build

Run `tools/vlm-feasibility/prepare.ps1 -RuntimeOnly` to prepare Android NDK r28c and the pinned llama.cpp checkout, then `python tools/smolvlm2/prepare.py` for the exact current model files. With already downloaded runtime artifacts, use `-SkipDownloads`. CMake rejects a modified checkout or a different commit from `LLAMA_CPP_COMMIT`.

Install CMake 3.29.2 and Ninja and make them available to Android Gradle Plugin. This workspace uses `C:/Strawberry/c/bin`. Android Gradle Plugin uses the NDK at `tools/vlm-feasibility/artifacts/android-ndk-r28c`. Build `:app:assembleDebug` or `:app:assembleRelease`; both now use native CMake Release optimization. The component supports ARM64 CPU only and uses static libc++. Source and model downloads are ignored artifacts; the APK contains the JNI library, not GGUF files.

## Device verification

Build `:app:test :app:assembleDebug :app:assembleDebugAndroidTest`, then run:

```powershell
./tools/run-room-tests.ps1 -Serial <serial>
./tools/run-checkpoint4-tests.ps1 -Serial <serial> -DeviceRecording <existing-finalized-clip-filename.mp4>
```

Alternatively provide `-PlaybackFixture <valid-local-HEVC-mp4>`. The runner rejects text-damaged MP4 exports. The device-recording option copies the named recording into cache and reads that copy; it does not alter the original or its database record. Both runners use replacement installs and direct instrumentation, preserving app data.

`tools/provision-smolvlm2-models.ps1` stages verified local weights under `/data/local/tmp/ecocapture-smolvlm2` and copies them into app-owned `files/models/smolvlm2`, checking SHA-256 before and after copying and publishing each verified temporary file. It stops the app before provisioning. Runtime inspection checks both complete files again before every load. Existing Qwen files remain untouched.

The heavy test is explicitly opt-in. It checks runtime provenance, failed partial loads, repeated load/unload and a Media3 HEVC rendered frame after unload. Memory/timing reports are written into ignored `tools/vlm-feasibility/artifacts/results/checkpoint4`.

Cancellation requests can interrupt native loading at upstream progress callbacks. During generation, a cancellation watcher requests native cancellation while JNI blocks; CPU language evaluation has an abort callback. Cancellation is checked between vision encoding calls/chunks/tokens. The current vision encoding call must finish before cleanup. Partial work is discarded, generation memory is cleared and unload is non-cancellable. Checkpoint 7 cancels on leaving participant review or the foreground and serializes complete engine cycles; full recovery validation belongs to Checkpoint 8.

## Real inference engineering comparison

Prepare synthetic test fixtures with `tools/prepare-frame-sampler-fixtures.ps1` before building the test APK. Install the app/test with replacement installs and keep the official verified model bundle provisioned. Then run:

```powershell
./tools/run-checkpoint6-comparison.ps1 -Serial <serial> -DeviceRecording <clip_filename.mp4> -FrameCount 1 -Install
./tools/run-checkpoint6-comparison.ps1 -Serial <serial> -DeviceRecording <clip_filename.mp4> -FrameCount 3
./tools/run-checkpoint6-comparison.ps1 -Serial <serial> -DeviceRecording <clip_filename.mp4> -FrameCount 5
python ./tools/summarize-checkpoint6.py
```

The non-exported debug activity provides foreground scheduling and keeps the screen on. Reports retain exact raw text, provenance, measured phase durations, memory and thermal observations in ignored local artifacts. `checkpoint6-engineering.db` is separate from participant data. No recording description, decision or annotation is changed. Source bytes are hashed before/after each comparison. Checkpoint 6 report documents final measurements and selected defaults.
