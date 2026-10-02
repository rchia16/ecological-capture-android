# Phase 2 Checkpoint 5: saved-video frame sampler

Implemented 2026-10-01. Stop for inspection and approval before real inference in Checkpoint 6.

## Implementation

- `vlm/FrameSamplingConfig.kt`: one immutable configuration for percentages and maximum dimension. Defaults: 10/30/50/70/90%, long edge at most 1024, no upscaling. Alternate counts require explicit percentages; invalid, duplicate or unordered percentages are rejected. Very short durations may produce identical requested millisecond timestamps.
- `vlm/VlmFrameSampler.kt`: accepts `ClipRecord.videoFile` or a saved File, checks readable nonempty MP4 input, obtains video duration/dimensions/rotation from Android metadata, and decodes on Dispatchers.IO. It never substitutes sidecar duration for invalid video duration. Null/zero/negative duration and invalid geometry return typed failures. No live DAT frames, re-encoding or model loading.
- `vlm/FrameSample.kt`: frame index, requested timestamp, nullable actual timestamp, encoded source dimensions, container rotation and inference dimensions. `SampledFrames.provenanceJson()` records the complete versioned policy and extraction duration for later run persistence. This checkpoint does not create VLM runs.
- Scaling uses bounded `MediaMetadataRetriever.getScaledFrameAtTime` with OPTION_CLOSEST and ARGB_8888 requested. The decoder preserves aspect ratio and applies container rotation. Square decode bounds avoid a swapped-axis scaling error on rotated video. Returned geometry is checked for dimension limit, no upscaling and display aspect ratio. Orientation is not applied twice.
- Caller owns successful frames and must close the batch, normally with `.use { ... }`. Retriever release is unconditional. Partial frames are recycled after failure, allocation failure or cancellation, including cancellation while a blocking decode returns. Cancellation cannot interrupt Android's current blocking frame call; it is checked before and after each call. No native ingestion occurs yet.

Android returns a nearby frame when an exact frame is unavailable and does not expose the decoded frame's timestamp in this API. `actualTimestampMs` remains null rather than being fabricated from the request. Requested timestamps are deterministic and ordered; actual frames may repeat in short, stationary or sparse recordings. See [Android MediaMetadataRetriever reference](https://developer.android.com/reference/android/media/MediaMetadataRetriever#getScaledFrameAtTime(long,int,int,int,android.media.MediaMetadataRetriever.BitmapParams)).

Participant UI remains on FakeVlmEngine. Existing prompt version `ecological_scene_description_v2`, optional preparation, immutable raw outputs, participant amendments and recording decisions retain their existing behavior. Phase 1 capture and playback code are unchanged.

## Tests and engineering tools

- `FrameSamplingConfigTest.kt`: four JVM tests for 30/60-second timestamps, very short clips, deterministic ordering, explicit alternate configuration, copied input and invalid policies/durations.
- `VlmFrameSamplerTest.kt`: twelve device tests covering real 60-second HEVC decoding, pixel-identical repeat sampling, source-byte preservation, short video, portrait downscaling, container rotation with exact pixel-orientation comparison, missing/empty/corrupt MP4, zero/unknown/negative duration, successful/idempotent Bitmap recycling, partial failure, propagated cancellation, cancellation of a job during a blocking decode, allocation failure and invalid returned geometry.
- `Checkpoint5InspectionTest.kt`: opt-in engineering export of five sampled JPEGs from each of at least three actual saved glasses clips. It checks each source SHA-256 before/after, writes only cache artifacts and emits the complete sampling manifest.
- `tools/prepare-frame-sampler-fixtures.ps1`: generates disposable synthetic HEVC test assets under ignored `app/build/generated/checkpoint5-fixtures`; FFmpeg 7.1 with libx265 was used. These are packaged only in the test APK. The production path never encodes video.
- `tools/run-checkpoint5-inspection.ps1`: replacement installs and direct instrumentation, binary-safe adb pulls, and a local HTML inspection gallery. No uninstall, data clearing or source recording edits.

Builds: `:app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` passed. All 50 JVM tests passed in each variant (46 existing plus four new). Ordinary device regression: 48 passed, two opt-in engineering tests skipped (`OK (50 tests)`, 14.814 seconds). The separate three-clip inspection run passed (`OK (1 test)`, 4.555 seconds). `git diff --check` passed. APK inspection confirmed zero fixture assets in the app APK and all four synthetic videos in the test APK.

## Three actual glasses recordings

Pixel 9 / Android 16, serial `49280DLAQ0020M`. Five samples per clip; encoded source 720 × 1280, output 576 × 1024, rotation 0. Source SHA-256 unchanged for all three. The duration comes from the playable MP4, which can differ from the recording sidecar's capture-duration value.

| Recording | Playable duration (ms) | Requested timestamps (ms) |
| --- | ---: | --- |
| `clip_20261001_160008_634.mp4` | 7,722 | 772, 2316, 3861, 5405, 6949 |
| `clip_20261001_160118_201.mp4` | 8,151 | 815, 2445, 4075, 5705, 7335 |
| `clip_20261001_183137_611.mp4` | 3,580 | 358, 1074, 1790, 2506, 3222 |

These actual clips are short; the 60-second case is a synthetic decoder fixture. Representative exported frames were visually inspected and appear upright with the expected portrait shape. User inspection of all timeline frames against playback remains pending; this is not an ecological-description quality evaluation.

Final five-frame extraction times were 1.024, 1.595 and 1.473 seconds respectively. These are one debug run per clip, without a controlled cache/thermal protocol; no model was loaded. Deterministic requests do not imply a universal guarantee of bit-identical pixels across device decoder implementations. Pixel-identical repeated extraction was verified with the synthetic 60-second fixture on this phone.

## Reproduce

With native prerequisites already installed:

```powershell
./tools/prepare-frame-sampler-fixtures.ps1
# Use the project's Gradle executable:
gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline
./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
./tools/run-checkpoint5-inspection.ps1 -Serial 49280DLAQ0020M -DeviceRecordings clip_20261001_160008_634.mp4,clip_20261001_160118_201.mp4,clip_20261001_183137_611.mp4
```

Run fixture preparation again after `clean`, before building the test APK. You can pass `-Ffmpeg` with a tool path. Replace recording filenames with actual finalized glasses clips on the target device.

## What to check before approval

1. Open [the frame inspection gallery](../tools/vlm-feasibility/artifacts/results/checkpoint5/index.html) in a browser. It is a local engineering artifact, not a participant screen.
2. For each of the three recordings, compare its five images with video playback. Confirm early/middle/late coverage, upright orientation, original aspect ratio and no crop/stretch. Times displayed are requests, not confirmed frame timestamps. Some samples may show the same decoded frame.
3. In the app, play/pause/replay the same recordings, then capture and play a new glasses recording. Confirm existing descriptions and recording decisions remain intact.
4. Confirm AI suggestion behavior remains simulated; real generation begins only in the next checkpoint.

Artifacts live under ignored `tools/vlm-feasibility/artifacts/results/checkpoint5`: `index.html`, fifteen JPEGs, `manifest.json`, and the inspection instrumentation log. They contain local recording images and are not published or committed.
