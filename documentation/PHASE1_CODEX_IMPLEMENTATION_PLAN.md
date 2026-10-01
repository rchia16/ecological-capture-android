# Codex Implementation Brief - Android Phase 1
## Local Clip Persistence, Accessible Review Queue, Playback, and Participant Decisions

**Project:** Ecological Capture - Android  
**Current baseline:** Phase 0 capture prototype  
**Target:** Phase 1 local review workflow  
**Implementation agent:** Codex  
**Platform:** Android only  
**Language/UI:** Kotlin + Jetpack Compose  
**Wearable:** Ray-Ban Meta / supported Meta AI glasses via Meta Wearables DAT 0.9.0

---

# 1. Objective

Extend the existing Android Phase 0 capture prototype into a local-first review application without destabilising the working Meta DAT capture path.

Target workflow:

```text
Ray-Ban Meta
  -> Meta Wearables DAT 0.9
  -> compressed HEVC frames
  -> HevcMp4Recorder
  -> durable local MP4
  -> ClipRecord persisted in Room
  -> accessible Review Recordings queue
  -> local video playback
  -> participant chooses:
       Review later / Defer
       Approve
       Delete
```

This phase must preserve the existing Phase 0 recording implementation as much as possible.

Do **not** add in this phase:

- backend upload;
- consent/PIS;
- participant dictation;
- speech transcription;
- VLM inference;
- embeddings;
- privacy blurring;
- cloud services;
- environmental audio;
- gallery import/export;
- continuous/background capture.

Those remain later phases.

---

# 2. Current Repository Assumptions

Treat the current repository as authoritative.

Current package:

```text
com.rchia.ecocapture.phase0
```

Current recording location:

```text
/data/user/0/com.rchia.ecocapture.phase0/files/recordings/
```

Current per-recording files:

```text
clip_YYYYMMDD_HHMMSS_SSS.mp4
clip_YYYYMMDD_HHMMSS_SSS.json
```

Current capture pipeline:

```text
Stream.videoStream
  -> compressed HEVC VideoFrame
  -> HevcMp4Recorder
  -> MediaMuxer
  -> video-only MP4
```

Important current classes:

```text
app/src/main/java/com/rchia/ecocapture/phase0/
  MainActivity.kt
  Phase0ViewModel.kt
  capture/
    HevcNalParser.kt
    HevcMp4Recorder.kt
  feedback/
    FeedbackController.kt
  ui/
    Phase0Screen.kt
```

Do not replace the existing compressed-HEVC passthrough with raw I420 decoding, Bitmap conversion, MediaCodec re-encoding, CameraX, gallery capture, or phone-camera recording.

---

# 3. Governing Phase 1 Architecture

Add a local application-state layer around the completed MP4.

```text
EXISTING CAPTURE LAYER
Meta DAT
  -> HevcMp4Recorder
  -> RecordingResult.Completed

NEW LOCAL DOMAIN LAYER
ClipRepository
  -> Room database

NEW REVIEW LAYER
ReviewQueueViewModel
  -> ReviewQueueScreen
  -> ClipReviewScreen
  -> Local video playback
  -> participant decision
```

The MP4 remains the source media.

Room becomes the source of truth for workflow state.

The JSON sidecar remains capture provenance/export metadata. Do not use it as the primary workflow database.

---

# 4. Phase 1 Domain Model

Create a persistent `ClipRecord`.

Recommended Room entity:

```kotlin
@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey
    val clipId: String,

    val videoPath: String,
    val metadataPath: String?,

    val createdAtEpochMs: Long,
    val durationMs: Long,

    val width: Int,
    val height: Int,
    val sampleCount: Long,

    val reviewState: String,
    val approvalState: String,

    val createdByAppVersion: String?,
    val updatedAtEpochMs: Long,
)
```

Domain enums:

```kotlin
enum class ReviewState {
    UNREVIEWED,
    REVIEWED,
    DEFERRED,
}

enum class ApprovalState {
    UNDECIDED,
    APPROVED,
    WITHHELD,
    DELETED,
}
```

Recommended domain model:

```kotlin
data class ClipRecord(
    val clipId: String,
    val videoFile: File,
    val metadataFile: File?,
    val createdAt: Instant,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampleCount: Long,
    val reviewState: ReviewState,
    val approvalState: ApprovalState,
)
```

Do not implement annotation, VLM, upload, or server state yet.

---

# 5. Clip Identity

Every completed recording must have a stable `clipId`.

Preferred:

```text
UUID.randomUUID().toString()
```

UUIDv7 is acceptable only if already available without an unnecessary dependency.

Do not derive identity solely from the filename.

Do not rename existing Phase 0 files unless necessary.

---

# 6. Recording Completion Contract

Refactor only as much as required so successful finalisation returns structured metadata.

Target:

```kotlin
sealed interface RecordingResult {
    data class Completed(
        val clipId: String,
        val videoFile: File,
        val metadataFile: File?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sampleCount: Long,
    ) : RecordingResult

    data object NoRecording : RecordingResult
    data class Failed(val reason: String? = null) : RecordingResult
}
```

If a result type already exists, adapt it minimally.

On `Completed`:

```text
1. MP4 already finalised successfully
2. .partial already promoted to .mp4
3. file exists
4. file size > 0
5. create ClipRecord
6. expose to review queue
```

Do not create a DB row when recording never starts, finalisation fails, MP4 is zero bytes, or `.partial` was not promoted.

---

# 7. Room Persistence

Suggested packages:

```text
data/local/
  EcologicalCaptureDatabase.kt
  ClipDao.kt
  ClipEntity.kt
  ClipEntityMapper.kt

data/
  ClipRepository.kt
  RoomClipRepository.kt

domain/
  ClipRecord.kt
  ReviewState.kt
  ApprovalState.kt
```

Recommended DAO responsibilities:

```kotlin
@Dao
interface ClipDao {
    fun observeActiveClips(): Flow<List<ClipEntity>>
    fun observeReviewQueue(): Flow<List<ClipEntity>>
    fun observeClip(clipId: String): Flow<ClipEntity?>
    suspend fun insert(clip: ClipEntity)
    suspend fun updateReviewState(clipId: String, reviewState: String, updatedAt: Long)
    suspend fun updateApprovalState(clipId: String, approvalState: String, updatedAt: Long)
}
```

The default review queue should include `UNREVIEWED` and `DEFERRED`, excluding deleted clips.

Start at database version 1.

Do **not** use `fallbackToDestructiveMigration()`.

---

# 8. Existing Clip Reconciliation

Phase 1 may be installed over Phase 0 with existing MP4 files.

At startup:

```text
files/recordings/
  -> find *.mp4
  -> for each MP4:
       find matching .json if present
       check whether DB already contains it
       if absent:
           parse sidecar metadata if available
           otherwise create only minimal safe metadata
           insert ClipRecord
```

Rules:

- never duplicate an existing row;
- never delete media during reconciliation;
- ignore `.partial`;
- imported Phase 0 clips start `UNREVIEWED / UNDECIDED`;
- preserve zero/unknown legacy metadata rather than inventing values;
- reconciliation must be idempotent.

---

# 9. Review Queue UI

Add a top-level:

```text
REVIEW RECORDINGS
```

Navigation:

```text
Phase0Screen / Home
  -> ReviewQueueScreen
       -> ClipReviewScreen
```

Do not redesign the entire app yet.

Create:

```text
ui/review/
  ReviewQueueScreen.kt
  ReviewQueueViewModel.kt
  ReviewQueueUiState.kt
```

Each queue item should expose capture time, duration, and review state.

Example:

```text
Recording from 2:15 PM
34 seconds
Not reviewed
```

TalkBack example:

```text
Recording from 2:15 PM. Duration 34 seconds. Not reviewed. Double tap to review.
```

Do not require thumbnails.

Home should also expose the pending count:

```text
Review recordings
3 recordings waiting
```

---

# 10. Local Video Playback

Use AndroidX Media3 ExoPlayer.

Do not upload, copy to Gallery, or manually decode every frame.

Suggested:

```text
ui/review/player/
  LocalClipPlayer.kt
```

Controls:

```text
Play
Pause
Replay
Current time / duration
Back
```

Do not autoplay.

The player must release when leaving the review screen and pause when the app backgrounds.

---

# 11. Clip Review Screen

Create:

```text
ClipReviewScreen.kt
ClipReviewViewModel.kt
ClipReviewUiState.kt
```

Recommended structure:

```text
Recorded today at approximately 2:15 PM
Duration: 34 seconds

[ video player ]

[ PLAY / PAUSE ]
[ REPLAY ]

[ APPROVE ]
[ REVIEW LATER ]
[ DELETE ]

[ BACK ]
```

No critical action may require visual inspection.

Opening or playing a clip does not automatically mark it reviewed.

---

# 12. State Rules

Back without a decision:

```text
reviewState = UNREVIEWED
approvalState = UNDECIDED
```

Review Later:

```text
reviewState = DEFERRED
approvalState = UNDECIDED
```

Approve:

```text
reviewState = REVIEWED
approvalState = APPROVED
```

Phase 1 approval is **local state only**. It must not upload or transmit anything.

Feedback:

```text
Recording approved. It is still saved on this phone.
```

---

# 13. Delete Semantics

Delete requires confirmation:

```text
Delete this recording?
This removes it from this phone. This cannot be undone.

[CANCEL]
[DELETE RECORDING]
```

On confirmed delete:

```text
1. stop/release player
2. acquire per-clip mutation lock
3. delete MP4
4. delete sidecar JSON if present
5. verify deletion
6. persist tombstone or remove row according to repository policy
7. refresh queue
8. spoken + haptic confirmation
```

If MP4 deletion fails:

- do not report success;
- do not remove DB state as if deletion succeeded;
- surface an accessible error.

Do not retain a hidden media copy.

---

# 14. Accessibility Requirements

Accessibility is release-blocking.

Required:

- logical TalkBack focus order;
- large touch targets;
- semantic labels;
- action verbs;
- no icon-only critical controls;
- no color-only state;
- scalable text;
- predictable Back behavior;
- destructive confirmation;
- playback state announcement;
- review decision feedback;
- no autoplay.

Reuse the existing `FeedbackController`; do not create a second TTS subsystem.

Suggested new feedback events:

```text
PLAYBACK_STARTED
PLAYBACK_PAUSED
CLIP_APPROVED
CLIP_DEFERRED
CLIP_DELETED
DELETE_FAILED
REVIEW_ERROR
```

---

# 15. Capture Integration Invariant

After `RecordingResult.Completed`:

```text
HevcMp4Recorder
  -> Phase0ViewModel
  -> ClipRepository.addCompletedRecording(...)
  -> Room insert
  -> existing saved feedback
```

If Room insertion fails after a valid MP4 exists:

```text
DO NOT DELETE THE MP4
```

Log/report the DB error and allow startup reconciliation to recover the clip later.

This is a critical invariant.

Do not force review immediately after capture.

---

# 16. Missing/Corrupt File Handling

If Room references a missing file:

```text
Recording file unavailable.
```

Do not crash.

If playback fails:

```text
Recording could not be played. The file has not been deleted.
```

Allow Back and Delete. Never auto-approve an unplayable clip.

---

# 17. Recommended Source Layout

```text
app/src/main/java/com/rchia/ecocapture/phase0/
  MainActivity.kt
  Phase0ViewModel.kt

  capture/
    HevcNalParser.kt
    HevcMp4Recorder.kt

  data/
    ClipRepository.kt
    RoomClipRepository.kt
    local/
      EcologicalCaptureDatabase.kt
      ClipDao.kt
      ClipEntity.kt
      ClipEntityMapper.kt

  domain/
    ClipRecord.kt
    ReviewState.kt
    ApprovalState.kt

  feedback/
    FeedbackController.kt

  ui/
    Phase0Screen.kt
    review/
      ReviewQueueScreen.kt
      ReviewQueueViewModel.kt
      ReviewQueueUiState.kt
      ClipReviewScreen.kt
      ClipReviewViewModel.kt
      ClipReviewUiState.kt
      player/
        LocalClipPlayer.kt
```

Do not move stable Phase 0 files for aesthetic restructuring.

---

# 18. Expected New Dependencies

- Room
- Room KTX
- KSP if needed
- Navigation Compose if needed
- Media3 ExoPlayer
- Media3 player UI/Compose integration

Pin explicit versions.

Do not upgrade Meta DAT or unrelated libraries without a specific need.

---

# 19. Phase 1 Invariants

1. A valid Phase 0 recording is never deleted because Room insertion failed.
2. Opening/playing a clip does not equal approval.
3. A clip can remain `UNREVIEWED / UNDECIDED` indefinitely.
4. Review Later does not alter the MP4.
5. Approved does not upload anything.
6. Delete success is not announced before media deletion succeeds.
7. Review code does not modify the working DAT frame path.
8. No new cloud dependency.
9. No critical review action requires vision.
10. Existing Phase 0 MP4s remain compatible.

---

# 20. Explicit Non-Goals

Do not implement:

```text
PIS
electronic consent
server/API
authentication
background upload
WorkManager upload
dictation
speech-to-text
VLM
keyframe extraction for AI
embeddings
semantic search
privacy blur
GPS
clip tags
environmental audio
researcher dashboard
gallery export
share intent
```

Do not create unfinished UI for them.

---

# 21. Codex Working Rules

1. Inspect the repository before changing it.
2. Treat the working capture pipeline as protected.
3. Make small logical patches.
4. Build after every checkpoint.
5. Run tests after every checkpoint that adds testable logic.
6. Stop if build fails or existing capture no longer compiles.
7. After each checkpoint report:
   - files changed;
   - what was implemented;
   - test commands and outcomes;
   - manual verification steps;
   - expected result;
   - known issues;
   - whether it is safe to continue.
8. Do not claim physical-glasses verification unless actually tested.
9. Do not silently fix unrelated code.
10. Do not continue to the next checkpoint until the user approves.

---

# 22. Implementation Checkpoints

## CHECKPOINT 1 - Room foundation only

Implement:

- Room dependencies
- ClipEntity
- ClipDao
- EcologicalCaptureDatabase
- domain models/enums
- ClipRepository
- RoomClipRepository
- no UI changes

Automated verification:

```bash
./gradlew test
./gradlew assembleDebug
```

Tests:

- insert/read
- review-state update
- approval-state update

Manual checkpoint:

- app installs
- Phase 0 screen unchanged
- DAT registration unchanged
- glasses connection unchanged
- existing Phase 0 recording still works

**STOP and wait for approval.**

---

## CHECKPOINT 2 - Persist newly captured clips

Connect successful recording completion to ClipRepository.

New clip state:

```text
UNREVIEWED / UNDECIDED
```

If DB insert fails, preserve the MP4.

Automated tests:

- recording result -> entity mapping
- DB failure does not trigger media deletion

Manual checkpoint:

1. connect glasses
2. record 10-20 s
3. stop
4. confirm normal save feedback
5. kill/relaunch app
6. inspect Room using App Inspection/debug helper

Expected:

- MP4 remains
- exactly one DB row
- `UNREVIEWED / UNDECIDED`

**STOP and wait for approval.**

---

## CHECKPOINT 3 - Legacy Phase 0 reconciliation

Implement idempotent startup scan of:

```text
files/recordings/*.mp4
```

Use sidecar JSON if present. Ignore `.partial`.

Automated fixtures:

- MP4 + JSON
- MP4 only
- existing DB row
- partial only
- duplicate scan

Manual checkpoint:

1. use device containing old Phase 0 media
2. upgrade without clearing app data
3. launch
4. inspect DB/debug summary

Expected:

- old MP4 represented exactly once
- media unchanged
- `UNREVIEWED / UNDECIDED`

**STOP and wait for approval.**

---

## CHECKPOINT 4 - Accessible review queue

Add `REVIEW RECORDINGS` and ReviewQueueScreen.

Show:

- capture time
- duration
- review state
- pending count on Home

No video player yet.

Manual TalkBack checkpoint:

1. focus Review recordings
2. confirm waiting count
3. open queue
4. navigate every clip
5. confirm time/duration/state understandable without vision
6. Back

Expected:

- no state change merely from opening queue

**STOP and wait for approval.**

---

## CHECKPOINT 5 - Local HEVC playback

Add Media3/ExoPlayer.

Controls:

```text
Play
Pause
Replay
Back
```

No autoplay.

Tests:

- valid HEVC file
- missing file
- corrupt file
- repeated open/close
- background/foreground

Manual checkpoint for at least 3 clips:

1. open
2. play
3. pause
4. replay
5. Back
6. reopen
7. verify TalkBack controls
8. return Home and record another clip

Expected:

- MP4 plays
- no player leak/crash
- capture still works

**STOP and wait for approval.**

---

## CHECKPOINT 6 - Review Later

Add `REVIEW LATER`.

Transition:

```text
DEFERRED / UNDECIDED
```

Manual checkpoint:

1. open unreviewed clip
2. choose Review Later
3. kill app
4. relaunch
5. reopen queue

Expected:

- clip remains
- deferred state persists
- MP4 unchanged

**STOP and wait for approval.**

---

## CHECKPOINT 7 - Approve

Add `APPROVE`.

Transition:

```text
REVIEWED / APPROVED
```

Feedback:

```text
Recording approved. It is still saved on this phone.
```

Manual checkpoint:

1. review clip
2. approve
3. confirm it leaves default pending queue
4. kill/relaunch
5. inspect persisted state

Expected:

- MP4 remains
- no upload/network transfer
- approval persists

**STOP and wait for approval.**

---

## CHECKPOINT 8 - Delete

Add accessible confirmation.

On confirm:

- release player
- delete MP4
- delete JSON
- verify
- update/remove DB state
- do not report success on partial failure

Manual checkpoint:

1. create disposable clip
2. choose Delete
3. Cancel and verify it remains
4. choose Delete again
5. confirm
6. verify file gone using ADB
7. restart app
8. verify reconciliation does not restore it

ADB:

```bash
adb shell run-as com.rchia.ecocapture.phase0 \
  find files/recordings -maxdepth 1 -type f
```

**STOP and wait for approval.**

---

## CHECKPOINT 9 - Recovery and regression pass

Full flow A:

```text
capture
  -> save
  -> restart
  -> queue
  -> play
  -> defer
  -> restart
  -> play
  -> approve
```

Full flow B:

```text
capture
  -> save
  -> review
  -> delete
  -> restart
```

Physical Phase 0 regression:

- DAT registration
- glasses discovery
- camera start
- compressed stream
- FPS/resolution
- recording-start feedback
- recording-stop feedback
- valid MP4
- repeated recordings

TalkBack regression:

```text
Home
  -> Review recordings
  -> select clip
  -> Play
  -> Pause
  -> Review Later / Approve / Delete
  -> Back
```

**STOP and produce Phase 1 completion report. Do not begin consent, upload, dictation, or VLM.**

---

# 23. Phase 1 Completion Criteria

```text
[ ] Existing Phase 0 capture still works on physical glasses
[ ] Every valid completed recording gets a ClipRecord
[ ] Existing legacy Phase 0 MP4s are reconciled
[ ] Room state survives process death
[ ] Review queue works with TalkBack
[ ] Local HEVC playback works
[ ] Playback never implies approval
[ ] Review Later persists
[ ] Approval persists
[ ] Approval performs no network operation
[ ] Delete requires confirmation
[ ] Confirmed deletion removes MP4
[ ] Deleted clips do not reappear after reconciliation
[ ] Missing/corrupt media does not crash app
[ ] Player releases correctly
[ ] No cloud/backend/VLM/dictation features were introduced
```

---

# 24. Required Codex Completion Report

At completion provide:

## Files added
List every new source file.

## Files modified
List each modified existing file and why.

## Architecture summary

```text
HevcMp4Recorder
  -> RecordingResult.Completed
  -> ClipRepository
  -> Room
  -> ReviewQueueViewModel
  -> ClipReviewViewModel
  -> Media3
```

## Tests run
Provide exact Gradle commands and outcomes.

## Manual tests still required
Distinguish automated/emulator verification from physical-glasses verification.

## Known limitations
Do not describe deliberate later-phase omissions as bugs.

## Recommended next phase

```text
accessible PIS / consent
  -> versioned consent record
  -> local approval policy
  -> reliable background upload
```

Dictation and local VLM remain later unless the roadmap is deliberately changed.

---

# 25. Copy/Paste Instruction for Codex

> Implement Android Phase 1 according to this document. Treat the existing Phase 0 Meta DAT 0.9 compressed-HEVC capture pipeline as protected functionality and make the minimum changes required around successful local MP4 finalisation. Implement one checkpoint at a time. After each checkpoint, build and run the required tests, then stop and report the files changed, test results, manual verification steps, and any issues. Do not continue to the next checkpoint until I approve it. Do not add backend, upload, PIS/consent, dictation, VLM, privacy processing, gallery import/export, or environmental audio. Preserve all valid Phase 0 recordings even if the new database layer fails.
