# Codex Implementation Brief - Android Phase 2
## Local Qwen3-VL Review, VLM Annotation, and Editable Participant Annotation

**Project:** Ecological Capture - Android  
**Baseline:** Completed Phase 1 local capture/review application  
**Target:** Phase 2 local VLM-assisted review and editable participant annotation  
**Implementation agent:** Codex  
**Platform:** Android only  
**Primary test device:** Pixel 9 / Android 16  
**Wearable:** Ray-Ban Meta via Meta Wearables DAT 0.9.0  
**Target VLM:** Qwen3-VL-4B-Instruct, Q4 language quantization  
**Initial runtime:** upstream llama.cpp + libmtmd, Android ARM64, in-process JNI for production integration

---

# 1. Objective

Extend the existing Phase 1 application with a fully local, optional VLM-assisted annotation workflow.

```text
Ray-Ban Meta
  -> DAT 0.9
  -> compressed HEVC
  -> local MP4
  -> ClipRecord
  -> Review Queue
  -> Clip Review
       |
       +-> participant may write/edit own description
       |
       +-> participant may choose "Generate AI description"
              -> deterministic frame sampling from saved MP4
              -> local Qwen3-VL-4B-Instruct inference
              -> immutable VLM result + provenance
              -> participant may:
                   Use as starting point
                   Edit and save amendment
                   Ignore
       |
       -> Approve / Review Later / Delete remain independent
```

Phase 2 must remain entirely local.

Do not introduce upload, REDCap, FileSender, backend services, remote VLM calls, enrolment UI, consent UI, participant-profile editing, or problem/solution structured annotation.

---

# 2. Protected Phase 1 Baseline

Treat this existing path as protected functionality:

```text
DAT 0.9 compressed HEVC stream
  -> HevcMp4Recorder
  -> RecordingResult.Completed
  -> ClipRepository.addCompletedRecording
  -> Room
  -> ReviewQueueViewModel
  -> ClipReviewViewModel
  -> Media3 ExoPlayer
```

Existing behaviors that must not regress:

- app-private video storage;
- stable ClipRecord persistence;
- legacy clip reconciliation;
- accessible Review Queue;
- local HEVC playback;
- Review Later;
- Approve;
- Delete with confirmation and tombstone behavior;
- TalkBack semantics;
- truthful recording feedback;
- no upload;
- no environmental audio.

Do not modify the DAT frame path to support the VLM.

The VLM operates only on an already-finalized local MP4.

---

# 3. Explicit Phase 2 Scope

Implement:

- participant-authored free-text annotation;
- editing with revision history rather than destructive overwrite;
- a VLM abstraction;
- Qwen3-VL-4B-Instruct local inference;
- deterministic multi-frame sampling from saved MP4;
- VLM model/runtime/prompt/frame provenance;
- VLM review state;
- participant amendment of VLM output;
- accessible progress/error/review UI;
- persistence across restart;
- model lifecycle and memory handling;
- Phase 1 regression tests.

Do not implement:

```text
REDCap
study enrolment
participant profile editing
participant profile display
consent
FileSender
eResearch Storage integration
backend/API
WorkManager upload
cloud inference
custom speech-to-text
environmental audio
problem/solution structured fields
privacy blurring
face recognition
identity inference
GPS
gallery export
share intents
```

System keyboard voice typing may work automatically through the Android IME, but do not add a custom microphone or speech-recognition subsystem.

---

# 4. Participant Profile Rule

The study participant profile is provisioned externally and fixed for that participant.

Phase 2 must not add enrolment or demographic/clinical profile UI.

Most importantly:

```text
DO NOT pass participant demographic or clinical information to Qwen3-VL.
```

The VLM must be blinded to diagnosis, time since functional deficit, age, sex/gender, clinical severity, identity, and other participant-profile metadata.

This preserves a clean scientific distinction between what the VLM inferred from the ecological visual scene and what is known about the participant from study metadata.

---

# 5. Exact Model Bundle

Use the official Qwen GGUF repository:

```text
Qwen/Qwen3-VL-4B-Instruct-GGUF
```

Language model:

```text
Qwen3VL-4B-Instruct-Q4_K_M.gguf
approximate size: 2.5 GB
SHA-256:
66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a
```

Multimodal projector:

```text
mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf
approximate size: 454 MB
SHA-256:
30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d
```

Important:

```text
"Q4" refers to the language model.
```

The official Qwen GGUF repository provides the multimodal projector in F16 and Q8_0 rather than Q4. Use the Q8_0 mmproj as the Phase 2 default.

Approximate model bundle size on disk:

```text
2.5 GB language model
+ 454 MB vision projector
------------------------
~2.95 GB
```

Do not bundle these files into the APK.

Do not silently substitute another Qwen quant, another mmproj, or a community quant.

Any model change requires explicit documentation and a new model identifier.

---

# 6. Runtime Choice

Use upstream `ggml-org/llama.cpp` and its modern multimodal layer:

```text
libmtmd
```

Do not use Ollama inside Android.

Do not run a local HTTP server inside the application.

Production integration should be:

```text
Kotlin
  -> JNI
  -> llama.cpp / libllama
  -> libmtmd
  -> Qwen3-VL GGUF + mmproj
```

For the initial hardware feasibility spike only, Codex may build and push `llama-mtmd-cli` to the phone with ADB. That CLI is a test harness only.

`libmtmd` is experimental and changes quickly. Therefore:

1. select a llama.cpp commit that successfully runs Qwen3-VL;
2. record the exact commit SHA;
3. pin it;
4. do not track `master` after validation.

Create:

```text
documentation/VLM_RUNTIME.md
```

Record:

- llama.cpp commit SHA;
- Android NDK version;
- CMake version;
- ABI;
- build flags;
- Qwen model hashes;
- mmproj hash;
- context size;
- thread count;
- frame sampling configuration;
- known runtime limitations.

---

# 7. Model Provisioning

The model is too large to package into the APK.

For Phase 2 engineering and study-device preparation, pre-provision the model files onto each research phone.

Preferred app-owned model directory:

```text
<app-specific files>/models/qwen3vl/
```

Required files:

```text
Qwen3VL-4B-Instruct-Q4_K_M.gguf
mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf
```

Implement a `VlmModelManager` with at least:

```text
NOT_INSTALLED
VERIFYING
READY
HASH_MISMATCH
INCOMPLETE
ERROR
```

Before loading:

- both files exist;
- both are non-zero;
- expected filenames match;
- SHA-256 hashes match;
- app has read access.

If validation fails, disable `Generate AI description` while leaving capture, playback, participant annotation, Approve, Review Later, and Delete operational.

No runtime internet model download is part of Phase 2.

---

# 8. VLM Architecture

Add a clean abstraction so review UI does not depend directly on llama.cpp.

Suggested layout:

```text
vlm/
  VlmEngine.kt
  VlmRequest.kt
  VlmResult.kt
  VlmModelInfo.kt
  VlmModelManager.kt
  VlmFrameSampler.kt
  FrameSamplingConfig.kt
  VlmPrompt.kt
  FakeVlmEngine.kt
  Qwen3VlEngine.kt

vlm/native/
  NativeQwen3VlBridge.kt
```

Interface:

```kotlin
interface VlmEngine {
    suspend fun load(): VlmLoadResult
    suspend fun generate(request: VlmRequest): VlmResult
    suspend fun unload()
    fun modelInfo(): VlmModelInfo
}
```

Do not place llama.cpp calls directly inside `ClipReviewViewModel`.

---

# 9. VLM Lifecycle

Do not load Qwen3-VL when the app starts.

Initial lifecycle:

```text
User opens clip
  -> no model load by default
  -> if optional automatic-preparation preference is enabled, prepare once while foregrounded
     and capture/finalization are idle; do not display output or change the participant draft

User presses GENERATE AI DESCRIPTION
  -> validate model files
  -> sample frames
  -> load model
  -> run inference
  -> persist result
  -> unload model
```

Automatic preparation is off by default and stored locally. Generation remains optional;
viewing, using and saving output remain explicit participant actions. Persist an automatic-attempt
marker before starting so failed/cancelled attempts do not silently retry on reopen. Never
automatically regenerate a rejected suggestion. Manual generation/regeneration remains available
inside the description flow. Announce only preparation/readiness, preserve focus, and leave
playback, participant editing and clip decisions operational. Cancel automatic work when disabled,
when capture/finalization becomes busy, or when leaving foreground review. The current fake-engine
implementation and checks are recorded in `PHASE2_AUTOMATIC_PREPARATION_REPORT.md`.

The first implementation should prioritize:

```text
memory safety > inference speed
```

The application must remain usable if model loading fails, inference is cancelled, Android kills the process, native inference fails, or the device becomes thermally constrained.

Never delete or modify the MP4 because of a VLM failure.

---

# 10. Frame Sampling Contract

Input source:

```text
finalized app-private MP4
```

Use deterministic temporal sampling.

Initial candidate:

```text
5 frames at:
10%
30%
50%
70%
90%
of playable duration
```

Persist for every sampled frame:

```text
frame index
requested timestamp ms
actual timestamp if available
source width/height
inference width/height
```

Initial preprocessing:

```text
preserve aspect ratio
no upscaling
max long edge = 1024 px
```

Keep frame count and maximum dimension configurable in one place.

If the feasibility checkpoint shows memory pressure, test:

```text
3 frames at 15/50/85%
```

and/or:

```text
max long edge = 768 px
```

Do not silently change the sampling policy.

Use Android media APIs to extract frames from the saved MP4. Do not re-encode the video. Release Bitmaps promptly after native ingestion.

---

# 11. Participant Annotation Data Model

Do not add one mutable `annotationText` field to ClipRecord.

Add a provenance-preserving entity, for example:

```kotlin
@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey val annotationId: String,
    val clipId: String,
    val source: String,
    val text: String,
    val createdAtEpochMs: Long,
    val parentVlmRunId: String?,
    val supersedesAnnotationId: String?,
    val isCurrent: Boolean,
)
```

Source enum:

```kotlin
enum class AnnotationSource {
    PARTICIPANT,
    PARTICIPANT_AMENDMENT,
}
```

When a participant edits their own annotation:

```text
old row remains
old row isCurrent = false
new row supersedesAnnotationId = old ID
new row isCurrent = true
```

Do not destroy prior revisions.

---

# 12. VLM Provenance Data Model

Store VLM output separately from participant annotations.

Recommended:

```kotlin
@Entity(tableName = "vlm_runs")
data class VlmRunEntity(
    @PrimaryKey val vlmRunId: String,
    val clipId: String,

    val modelId: String,
    val modelQuant: String,
    val languageModelSha256: String,
    val mmprojSha256: String,

    val runtimeName: String,
    val runtimeCommit: String,
    val promptVersion: String,

    val generatedAtEpochMs: Long,
    val firstPresentedAtEpochMs: Long?,
    val inferenceDurationMs: Long,

    val frameSamplingJson: String,
    val generationConfigJson: String,

    val rawOutput: String,
    val description: String,
    val disposition: String,
)
```

Disposition:

```kotlin
enum class VlmDisposition {
    NOT_PRESENTED,
    PRESENTED,
    USED_AS_STARTING_POINT,
    AMENDED,
    IGNORED,
}
```

The original VLM text is immutable.

Never overwrite it with participant edits.

---

# 13. VLM Exposure Timestamp

Participant-generated information can be anchored by AI output.

Therefore store:

```text
VlmRunEntity.firstPresentedAtEpochMs
AnnotationEntity.createdAtEpochMs
```

This permits later analysis of whether participant text was authored before or after VLM exposure.

Do not mark the VLM as presented when generation completes.

Set `firstPresentedAtEpochMs` only when VLM text is actually rendered to the participant-facing review UI.

---

# 14. Prompt Contract

Create a versioned prompt.

Current ID (supersedes v1 for new runs; preserve historical provenance):

```text
ecological_scene_description_v2
```

Prompt goals:

- Act as an optional coarse ecological scene-description assistant, not an OCR or ground-truth annotation system.
- Describe only visually supported environmental context, spatial layout, obstacles/hazards, landmarks, entrances/exits, route options, relevant objects and visible actions/changes.
- Do not guess small/blurry text, dates, prices, street numbers, bus/platform numbers, names, fine/partially visible signage or exact distances.
- Include text/numbers only when clearly legible; otherwise explicitly state that the detail is unclear. Unclear objects and spatial details must also remain uncertain.
- Do not infer identity, diagnosis/disability, demographics, intentions, emotional state, the participant's actual problem or whether a solution worked. Distinguish a visually apparent obstacle from inference about participant experience.
- Preserve raw output, uncertainty wording and actual prompt/model/runtime/frame/generation provenance. Never relabel or silently correct output as ground truth.
- Participant edits remain separate `PARTICIPANT_AMENDMENT` records, including unchanged endorsements; prior revisions and original VLM output are retained.
- No demographic or clinical participant-profile information is passed to the prompt. Generation remains optional and independent of Approve, Review Later and Delete.

The exact user-supplied system and frame prompts are defined in `vlm/VlmPrompt.kt`. Use distinct system/user messages; do not substitute an instruction requesting transcription or exact scene measurements.

Recommended output:

```text
Scene:
<1-3 sentences about the environment and spatial layout>
Relevant spatial features:
<navigation-relevant features>
Visible actions:
<visually observable changes/actions>
Clearly legible text:
<clearly readable relevant text only, or "No clearly legible text relevant to the scene.">
Uncertain or unclear details:
<important details that cannot be determined confidently>
```

Do not require JSON output in Phase 2.

Use low-variance generation.

Initial generation settings:

```text
temperature = 0.0 / greedy where supported
max output tokens = 384 for the v2 five-section format
context size initially 8192
```

Persist actual generation settings with each VLM run.

Prompt-contract tests cover unclear small text, conservative dates/prices/numbers, prohibited participant-problem inference, coarse spatial content and versioned uncertainty instructions. Real Room tests must retain uncertainty verbatim after reopen and preserve output/provenance through participant amendments. Synthetic fixtures do not establish actual model compliance; evaluate generated outputs separately.

---

# 15. Participant Annotation UX

Add a section to `ClipReviewScreen` before the AI section:

```text
YOUR DESCRIPTION

No description added.

[ ADD DESCRIPTION ]
```

When present:

```text
YOUR DESCRIPTION

"I could not determine where the crossing point was."

[ EDIT DESCRIPTION ]
```

Participant annotation is optional.

The current participant-facing workflow supersedes the initial inline AI controls below:
Add/Edit Description opens a full-screen description editor. Generation and regeneration belong
inside this flow, with a separate full-screen suggestion-review step. Offer Use Suggestion,
and Reject Suggestion, plus Back to Description. Use Suggestion copies into the editable draft;
explain that it can be edited before saving. The redundant Edit Suggestion action is omitted.
Only Save Description
creates a separate `PARTICIPANT_AMENDMENT`. Afterwards Clip Review displays only the current
participant description. Rejection preserves the draft and offers Generate Another Suggestion
in the editor, including after restart. Generation, failure and cancellation must not replace
the draft. Preserve every original AI run, exposure timestamp, disposition and provenance.
Cancelling an unsaved edit leaves the suggestion pending. Suggestion acceptance never approves
the clip. No AI section is displayed on Clip Review; keep its 60/40 video/description split and
two-column bottom actions. See `PHASE2_DESCRIPTION_FLOW_REPORT.md` for the current UI and checks.

Do not gate Approve, Review Later, Delete, or VLM generation on participant annotation.

---

# 16. AI Description UX

Below participant annotation:

```text
AI DESCRIPTION

No AI description generated.

[ GENERATE AI DESCRIPTION ]
```

During inference:

```text
Generating AI description...
This may take some time.

[ CANCEL ]
```

After success:

```text
AI DESCRIPTION

<model text>

AI-generated. May be incomplete or incorrect.

[ USE AS STARTING POINT ]
[ IGNORE ]
```

Do not label model output as correct, verified, or ground truth.

Do not automatically speak the full model output. TalkBack should read it when focused; a separate read-aloud action may be added later if needed.

---

# 17. Editable VLM Amendment

If participant selects `USE AS STARTING POINT`, copy the VLM description into an editable text field.

On Save:

```text
create AnnotationEntity
source = PARTICIPANT_AMENDMENT
parentVlmRunId = VLM run/output reference
text = participant-edited copy
```

The original VLM output remains unchanged.

If the participant saves the text unchanged, still store an explicit participant amendment/endorsement record rather than mutating the VLM row.

---

# 18. Valid Phase 2 States

All are valid:

```text
participant annotation only
VLM only
participant annotation + VLM
VLM + participant amendment
participant annotation + VLM + amendment
neither annotation nor VLM
```

A clip may be APPROVED with no annotations.

A clip may remain DEFERRED with a VLM result.

No annotation state changes approval automatically.

---

# 19. Concurrency and Deletion Rules

Only one VLM inference may run for a given clip at a time.

Prefer only one Qwen inference process-wide in the first implementation.

Use a single inference mutex/semaphore.

If participant leaves the review screen:

- cancel frame extraction if safe;
- request native cancellation if supported;
- otherwise permit native work to finish without updating a destroyed UI;
- persist a valid successful result if it finishes safely.

Delete must not race with inference.

If Delete is requested while inference is active:

```text
cancel/finish inference
release native references
release Bitmaps
then execute existing clip deletion path
```

---

# 20. Native Integration Requirements

Use an isolated Android native module or equivalent.

Suggested:

```text
vlm-native/
  build.gradle.kts
  src/main/cpp/
    CMakeLists.txt
    qwen3vl_jni.cpp
```

Initial ABI:

```text
arm64-v8a
```

Initial backend:

```text
CPU
```

Do not add Vulkan in the first implementation.

Expose only minimal JNI operations:

```text
nativeLoadModel(...)
nativeGenerate(...)
nativeCancel(...)
nativeUnload()
nativeGetRuntimeInfo()
```

Never expose raw native pointers to Compose or ViewModels.

---

# 21. Native Error Contract

Convert native failures into typed Kotlin failures.

Examples:

```text
ModelMissing
HashMismatch
ModelLoadFailed
MmprojLoadFailed
FrameExtractionFailed
OutOfMemory
Cancelled
NativeFailure(code)
Unknown(message)
```

Never intentionally crash the app on llama.cpp errors.

Participant-facing UI must not display raw native traces.

Do not log participant annotation text or VLM output by default.

---

# 22. Engineering Metrics

For each local inference collect local engineering metrics where practical:

```text
model load duration
frame extraction duration
vision encode duration if available
prompt evaluation duration
generation duration
total inference duration
output token count
process memory/RSS
number of frames
frame dimensions
context size
thread count
thermal status before/after if available
```

Do not upload these metrics.

Verbose performance logging should be debug-build only.

---

# 23. Phase 2 Invariants

### INV-P2-01
VLM inference never consumes the live DAT stream.

### INV-P2-02
VLM failure never deletes or modifies the source MP4.

### INV-P2-03
VLM generation is optional.

### INV-P2-04
Participant annotation is optional.

### INV-P2-05
VLM output is never treated as ground truth.

### INV-P2-06
Participant amendment never overwrites original VLM output.

### INV-P2-07
Editing participant annotation preserves prior revisions.

### INV-P2-08
Approval remains independent from annotation and VLM state.

### INV-P2-09
Participant demographic/clinical profile data are never passed to the VLM.

### INV-P2-10
No Phase 2 action sends data off the phone.

### INV-P2-11
Missing/corrupt model files do not block Phase 1 functionality.

### INV-P2-12
`firstPresentedAt` records actual VLM exposure, not generation completion.

### INV-P2-13
Model, projector, prompt, runtime and sampling provenance are recoverable for every VLM run.

---

# 24. Codex Working Rules

1. Inspect the repository and Phase 1 completion report before modifying code.
2. Do not clear application data.
3. Do not rename the application package.
4. Preserve existing Room data with explicit migrations.
5. Implement one checkpoint at a time.
6. Build and test after every checkpoint.
7. Stop after every checkpoint and wait for user approval.
8. At each checkpoint report files added/modified, commands, results, device tests, manual verification, performance observations and known issues.
9. Never claim hardware/VLM behavior was verified unless actually run.
10. Do not silently substitute another model/runtime.
11. Do not upgrade DAT or unrelated dependencies as part of VLM work.
12. Pin llama.cpp after feasibility succeeds.
13. Do not add upload/network infrastructure.
14. Do not add participant-profile UI.
15. Keep native VLM code isolated from the capture path.

---

# 25. Implementation Checkpoints

Implement exactly one checkpoint at a time.

## CHECKPOINT 0 - Qwen3-VL Android feasibility spike

### Purpose

Prove the exact Qwen model bundle can run on the Pixel 9 before altering participant-facing VLM code.

### Perform

Outside the production app:

1. obtain the official model files;
2. verify both SHA-256 hashes;
3. select a llama.cpp commit with Qwen3-VL + libmtmd support;
4. build `llama-mtmd-cli` for Android ARM64;
5. push CLI + model + mmproj + test images to the device;
6. run local inference via ADB.

Start with one image, then 3 images, then 5 images if stable.

### Record

- llama.cpp commit;
- NDK/CMake versions;
- build flags;
- model load time;
- inference duration;
- process memory/RSS;
- 1/3/5 image success;
- thermal behavior;
- any OOM/native crash;
- output sanity.

### Exit criteria

```text
[ ] exact Q4_K_M model loads
[ ] exact Q8_0 mmproj loads
[ ] one-image inference succeeds
[ ] no OOM/crash
[ ] process/model exits cleanly
```

3/5 image success is characterized but not mandatory if memory requires fewer frames.

### STOP

Do not modify participant-facing VLM UI yet. Report results and wait for approval.

If the exact Qwen bundle cannot run reliably, stop and report rather than substituting another model.

---

## CHECKPOINT 1 - Room migration and annotation/provenance foundation

### Implement

Add:

```text
AnnotationEntity
VlmRunEntity
AnnotationDao
VlmRunDao
annotation repository/use cases
Room migration currentVersion -> nextVersion
VlmEngine
FakeVlmEngine
```

No participant-facing UI change yet.

### Automated tests

Test:

- migration preserves existing clips;
- approved state survives;
- deferred state survives;
- tombstones survive;
- participant annotation insert;
- annotation revision;
- VLM run insert;
- original VLM output remains immutable through amendment workflow;
- query current annotation;
- query VLM history.

### Commands

```bash
./gradlew test
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
```

Run Room migration instrumentation where available. On a research phone, build the APKs then use `tools/run-room-tests.ps1 -Serial <serial>` for replacement installs and direct instrumentation. The current Gradle connected-test runner is configured to uninstall the app after testing; do not use `connectedDebugAndroidTest` on a device whose app-private data must be retained.

### Manual checkpoint

Upgrade without clearing app data. Verify old recordings, decisions, playback and capture still work.

### STOP

Wait for approval.

---

## CHECKPOINT 2 - Participant annotation only

### Implement

Add to Clip Review:

```text
YOUR DESCRIPTION
[ ADD DESCRIPTION ]
```

Support Add, Edit, Save and Cancel.

Editing creates a new revision.

### Accessibility

Verify TalkBack labels, text-field focus, large touch targets, Save/Cancel semantics, and predictable keyboard behavior.

### Automated tests

```text
no annotation
add annotation
edit annotation
cancel edit
revision history
restart/reload current annotation
```

### Manual checkpoint

1. open existing clip;
2. add description;
3. save;
4. Back;
5. kill/relaunch;
6. verify persistence;
7. edit;
8. verify current text changes;
9. verify old revision remains in DB/debug inspection.

### STOP

Wait for approval.

---

## CHECKPOINT 3 - Fake VLM review workflow

### Implement

Integrate `FakeVlmEngine` with deterministic output after a short delay.

Add UI states:

```text
IDLE
PREPARING
RUNNING
SUCCESS
ERROR
CANCELLED
```

After success show:

```text
AI-generated. May be incomplete or incorrect.
[ USE AS STARTING POINT ]
[ IGNORE ]
```

Set `firstPresentedAtEpochMs` only when generated text is actually displayed.

`USE AS STARTING POINT` opens an editable copy and saves `PARTICIPANT_AMENDMENT` without changing the VLM record.

### Tests

- generate;
- display;
- firstPresentedAt;
- use as starting point;
- unchanged save;
- edited save;
- ignore;
- cancellation;
- fake VLM failure;
- VLM state does not alter approval.

### Manual checkpoint

Exercise all fake-VLM paths with TalkBack.

### STOP

Wait for approval.

---

## CHECKPOINT 4 - Model manager and native llama.cpp module

### Implement

Add:

```text
VlmModelManager
NativeQwen3VlBridge
native build/CMake
pinned llama.cpp source/commit
documentation/VLM_RUNTIME.md
```

Do not connect real inference to participant-facing UI yet.

Implement model path discovery, hash verification, native load, runtime info, unload and typed failures.

Initial ABI: `arm64-v8a`.

Initial backend: CPU.

Do not add Vulkan.

### Device test

```text
inspect -> READY
load
unload
load again
unload again
```

Measure memory before/after.

### Exit criteria

```text
[ ] correct hashes detected
[ ] corrupted file detected
[ ] missing file handled
[ ] model load succeeds
[ ] mmproj load succeeds
[ ] unload does not crash
[ ] repeated load/unload works
[ ] Phase 1 playback still works after unload
```

### STOP

Wait for approval.

---

## CHECKPOINT 5 - Saved-video frame sampler

### Implement

Add:

```text
VlmFrameSampler
FrameSample
FrameSamplingConfig
```

Input: `ClipRecord.videoFile`.

Initial output: 5 deterministic frames at 10/30/50/70/90%, max long edge 1024, no upscaling.

No inference yet.

### Tests

- normal 30-60 s clip;
- short clip;
- missing MP4;
- corrupt MP4;
- zero/unknown duration;
- portrait source;
- timestamp ordering;
- deterministic sampling;
- Bitmap cleanup.

### Manual checkpoint

Use at least three actual glasses-generated HEVC clips. Inspect sampled frames in a debug-only engineering view or test output.

Confirm timeline coverage, orientation, aspect ratio and no accidental crop/stretch.

### STOP

Wait for approval.

---

## CHECKPOINT 6 - Real Qwen3-VL inference

### Implement

Replace fake generation behind a debug/runtime switch with `Qwen3VlEngine`.

```text
saved MP4
 -> frame sampler
 -> native frame ingestion
 -> ecological_scene_description_v2
 -> Qwen3-VL generation
 -> VlmResult
 -> VlmRunEntity
```

Persist model ID, quant, hashes, runtime commit, prompt version, frame sampling, generation parameters and duration.

Do not pass ParticipantProfile information.

### Real-device comparison

Use at least three valid clips.

Where feasible run:

```text
1 frame
3 frames
5 frames
```

Record load time, extraction time, inference duration, output, memory and thermal observations.

Choose the Phase 2 default based on:

```text
stability first
useful scene coverage second
latency third
```

Do not choose a configuration that causes OOM or frequent process death.

Document final defaults in `VLM_RUNTIME.md`.

### STOP

Wait for approval.

---

## CHECKPOINT 7 - Real VLM review + editable amendment

### Implement

Enable the real engine in participant-facing review.

During inference:

```text
Generating AI description...
This may take some time.
[ CANCEL ]
```

Success:

```text
<model text>
AI-generated. May be incomplete or incorrect.
[ USE AS STARTING POINT ]
[ IGNORE ]
```

Preserve original output. Participant amendment remains separate.

### TalkBack checkpoint

1. focus Generate;
2. start inference;
3. navigate progress state;
4. read AI description;
5. choose Use as Starting Point;
6. edit;
7. save;
8. verify original and amended records remain distinguishable.

Do not automatically TTS the full model output.

### STOP

Wait for approval.

---

## CHECKPOINT 8 - Restart, cancellation, error and deletion recovery

### Test/harden

```text
generate -> success -> kill/relaunch
generate -> cancel
generate -> leave review screen
generate -> app background
generate -> force stop
model missing
model hash mismatch
frame extraction failure
native inference error
low-memory/process restart
delete after VLM result exists
```

Recommended deletion policy:

```text
when participant deletes the clip:
    media is deleted
    participant-facing annotation data for that clip is no longer retained as an active research event
```

If Phase 1 tombstones remain for reconciliation safety, do not retain long participant/VLM text inside the tombstone.

### Required outcome

No case may corrupt the clip DB, resurrect deleted media, report failed inference as success, convert VLM output into participant annotation automatically, or lose Phase 1 decision state accidentally.

### STOP

Wait for approval.

---

## CHECKPOINT 9 - Phase 2 scientific provenance and regression pass

### Validate all states

```text
neither annotation nor VLM
participant annotation only
VLM only
participant annotation then VLM
VLM then participant annotation
VLM + participant amendment
participant annotation + VLM + amendment
```

Verify timestamps permit determination of whether participant text was authored before or after VLM presentation.

### Phase 1 physical regression

Re-run:

```text
DAT registration
glasses discovery
camera start
compressed stream
record
stop
saved MP4
review queue
play/pause/replay
Review Later
Approve
Delete
```

### Accessibility regression

With TalkBack:

```text
Home
 -> Review Recordings
 -> Clip
 -> playback
 -> participant description
 -> AI generation
 -> amendment
 -> decision
 -> Back
```

### Performance report

Provide:

- chosen frame count;
- chosen max frame dimension;
- model/hash;
- mmproj/hash;
- llama.cpp commit;
- context size;
- thread count;
- model-load duration distribution;
- inference-duration distribution;
- observed memory;
- thermal issues;
- failure rate.

### STOP

Produce Phase 2 completion report.

Do not begin upload, FileSender, REDCap, enrolment, participant-profile UI, consent, or structured problem/solution fields.

---

# 26. Phase 2 Completion Criteria

```text
[ ] exact Qwen3-VL-4B-Instruct Q4_K_M runs locally on target phone
[ ] exact Q8_0 mmproj is verified and used
[ ] llama.cpp commit is pinned
[ ] model files are not bundled in APK
[ ] hash verification works
[ ] participant annotation can be added and edited
[ ] participant annotation revision history is preserved
[ ] VLM uses only saved MP4 frames
[ ] VLM receives no demographic/clinical profile data
[ ] deterministic frame sampling is recorded
[ ] VLM output includes model/runtime/prompt provenance
[ ] first VLM presentation timestamp is stored
[ ] participant can ignore VLM output
[ ] participant can use VLM as starting point
[ ] amendment is separate from original VLM text
[ ] approval is independent from annotation/VLM
[ ] VLM failure leaves source MP4 untouched
[ ] missing model leaves Phase 1 usable
[ ] restart preserves annotations/VLM results
[ ] deletion does not leave annotation/VLM text as an active event
[ ] TalkBack completes the annotation/VLM workflow
[ ] Phase 1 capture/review regression passes
[ ] no data leave the phone
```

---

# 27. Expected End-of-Phase UX

```text
RECORDING
Today, 1:34 PM
Duration: 42 seconds

[ VIDEO ]
[ PLAY ] [ PAUSE ] [ REPLAY ]

YOUR DESCRIPTION
No description added.
[ ADD DESCRIPTION ]

AI DESCRIPTION
No AI description generated.
[ GENERATE AI DESCRIPTION ]

[ APPROVE ]
[ REVIEW LATER ]
[ DELETE ]
```

After VLM generation:

```text
AI DESCRIPTION

"The recording shows ..."

AI-generated. May be incomplete or incorrect.

[ USE AS STARTING POINT ]
[ IGNORE ]
```

The original AI output always remains stored separately from any participant amendment.

---

# 28. Recommended Source Layout

```text
app/src/main/java/com/rchia/ecocapture/phase0/

  data/
    AnnotationRepository.kt
    VlmRunRepository.kt

    local/
      AnnotationEntity.kt
      AnnotationDao.kt
      VlmRunEntity.kt
      VlmRunDao.kt

  domain/
    AnnotationRecord.kt
    AnnotationSource.kt
    VlmDisposition.kt

  vlm/
    VlmEngine.kt
    VlmRequest.kt
    VlmResult.kt
    VlmModelInfo.kt
    VlmModelManager.kt
    VlmFrameSampler.kt
    FrameSamplingConfig.kt
    VlmPrompt.kt
    FakeVlmEngine.kt
    Qwen3VlEngine.kt

    native/
      NativeQwen3VlBridge.kt

  ui/review/
    ClipReviewViewModel.kt
    ClipReviewUiState.kt
    ClipReviewScreen.kt

vlm-native/ or equivalent isolated Android library module
  src/main/cpp/
    CMakeLists.txt
    qwen3vl_jni.cpp

documentation/
  VLM_RUNTIME.md
  PHASE2_COMPLETION_REPORT.md
```

Do not move existing Phase 1 classes solely to match this layout.

---

# 29. Required Phase 2 Completion Report

Create:

```text
documentation/PHASE2_COMPLETION_REPORT.md
```

Include:

- architecture: Kotlin -> JNI -> llama.cpp -> libmtmd;
- exact model filenames, hashes and sizes;
- pinned llama.cpp commit;
- NDK/CMake/ABI/build flags;
- final frame sampling policy;
- prompt version and generation parameters;
- Room migration and schema;
- exact tests/commands/outcomes;
- load time, inference time, memory and thermal observations;
- TalkBack checks;
- Phase 1 physical regression;
- known limitations;
- deferred work.

Deferred work must explicitly include:

```text
structured problem/solution annotation
participant-profile provisioning
REDCap linkage
consent
FileSender
eResearch Storage transfer
upload
privacy preprocessing
```

---

# 30. Copy/Paste Instruction for Codex

> Implement Android Phase 2 according to this document. The exact target model is the official `Qwen/Qwen3-VL-4B-Instruct-GGUF` language model `Qwen3VL-4B-Instruct-Q4_K_M.gguf` with `mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf`. Treat the existing Phase 1 DAT 0.9 capture, Room persistence, accessible review, playback, approval, defer and delete implementation as protected functionality. Begin with Checkpoint 0 and prove the exact Qwen model can run on the Pixel 9 before changing participant-facing VLM code. Use upstream llama.cpp/libmtmd, pin the first verified working commit, and ultimately integrate it in-process through an isolated JNI layer rather than Ollama or a local HTTP server. Implement one checkpoint at a time, build/test, stop, and report results before continuing. Participant annotation and VLM generation are optional and independent of approval. Preserve original VLM output and participant revision provenance. Never pass demographic or clinical participant-profile information into the VLM. Do not add REDCap, enrolment, consent, FileSender, upload, backend services, cloud inference, structured problem/solution fields, or participant-profile UI in this phase.
