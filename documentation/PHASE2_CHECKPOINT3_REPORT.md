# Phase 2 Checkpoint 3 — Fake VLM review workflow

The current full-screen description/regeneration flow supersedes the inline UI described here.
See [PHASE2_DESCRIPTION_FLOW_REPORT.md](PHASE2_DESCRIPTION_FLOW_REPORT.md) for its behavior, latest test results and manual checks.

Date: 2026-10-01. Implementation and automated checks complete; manual acceptance pending.

## Implemented

Clip Review offers optional **GENERATE AI DESCRIPTION**, progress, **CANCEL AI GENERATION**, and a pending **AI SUGGESTION** with **ACCEPT DESCRIPTION**, **EDIT SUGGESTION**, and **REJECT SUGGESTION**. Once accepted, edited and saved, or rejected, the entire AI section disappears and stays hidden after reopening or restart. Accepted/edited text appears only under **YOUR DESCRIPTION**; rejection keeps the existing participant description, or the normal empty state. Cancelling an unsaved edit leaves the suggestion pending. The section explicitly says **Simulated workflow test. This does not analyze the recording.** The fake engine waits three seconds and returns the same clearly simulated ecological-description fixture. It does not decode video, extract frames, load Qwen or perform inference.

The controller has `IDLE`, `PREPARING`, `RUNNING`, `SUCCESS`, `ERROR` and `CANCELLED` states. Generation is started only by an explicit action, with one active generation per controller. The engine is unloaded in a non-cancellable cleanup block after success, failure or cancellation. Leaving the review screen requests cancellation. Delete waits for generation cancellation and engine cleanup before invoking the existing clip-deletion path. Clip decisions remain independent of AI state and participant description.

The user-approved layout remains: video gets 60% of the space below the recording header and above the pinned actions; the participant/AI description pane gets 40% and scrolls independently. The existing five bottom actions remain in two columns. AI controls are in the scrollable AI section below the participant description. Progress/error announcements are polite live regions; full generated text is not automatically spoken. New actions have minimum 56 dp touch targets.

## Persistence and provenance

- Each completed generation inserts a new VLM run with raw output, description, fake model/runtime identity, prompt version `ecological_scene_description_v2`, simulated sampling/configuration and timings. Historical runs are retained. The latest run is restored when reopening the clip.
- Generation completion and history loading do not set exposure. The text UI calls the exposure handler only when the output intersects its clipped viewport, the activity is resumed, and neither the participant editor nor Delete dialog obscures it. `firstPresentedAtEpochMs` is written once, with a guarded database update. This records visible presentation, not proof that a participant read or understood the text.
- Exposure and disposition are the only mutable VLM fields. There is no output/provenance update API. Presentation cannot reset `IGNORED`, `USED_AS_STARTING_POINT` or `AMENDED` disposition.
- Edit Suggestion opens an editable copy linked to the original run. Opening records `USED_AS_STARTING_POINT`; cancelling creates no participant record and leaves the suggestion pending. Saving, even unchanged, inserts a separate `PARTICIPANT_AMENDMENT` and records `AMENDED` atomically. Accept Description directly saves an unchanged amendment. Both paths hide the AI section and retain all original output/provenance and prior revisions. Existing description revisions remain; expected-current-ID checks prevent stale amendment drafts overwriting newer participant revisions. Suggestion acceptance does not approve the clip.
- Reject Suggestion records `IGNORED` and permanently hides the AI section and regeneration controls for that clip, while retaining original output/provenance/exposure. It does not create an annotation or change approval. Any existing participant description remains. After any completed resolution, users edit only the current participant description.
- No clinical or demographic profile fields enter the engine request. Participant text remains separate from VLM inputs and is not logged.

The existing Room version 2 schema is reused; no migration or dependency change is needed.

## Files

Paths under `app/src/main/java/com/rchia/ecocapture/phase0/`:

- Added `ui/review/VlmReviewWorkflow.kt`, `AiDescriptionSection.kt`.
- Modified `ui/review/ClipReviewViewModel.kt`, `ClipReviewScreen.kt`, `DescriptionEditor.kt`, `DescriptionEditorDialog.kt`.
- Modified `data/VlmRunRepository.kt`, `data/AnnotationRepository.kt`, `data/local/VlmRunDao.kt` for guarded exposure/disposition updates and transactional amendments.

Tests: added `VlmReviewWorkflowTest.kt` under Android data tests and `VlmReviewStateTest.kt` under JVM review tests. Prior immutable-output assertions now allow the explicitly mutable `AMENDED` disposition while still comparing every output/provenance field.

## Commands and results

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
& ./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
```

Final build passed. **38 JVM tests passed per debug/release variant** and **29 device instrumentation tests passed** on Pixel 9 / Android 16. The final device run reported `OK (29 tests)` in 3.629 seconds. `git diff --check` passed with normal LF/CRLF notices.

Twelve workflow/Room tests cover explicit generation/no startup loading; raw output and simulated provenance; null exposure before the visibility callback; first presentation and idempotence; unchanged/edited amendments and parent/revision preservation; cancel amendment; rejection and database reopen; cancellation/unload/duplicate generation/retry; injected generation/load failures; clip selection cancellation; stale amendment rejection; direct acceptance persistence and resolved-state hiding after reload; rejection preserving existing participant text; blocked regeneration after resolution; and unchanged source bytes and decision states. Two new JVM tests cover phase classification and preservation of amendment references while editing. All existing tests passed.

Tests use separate disposable databases and synthetic byte files, not production clips. Direct instrumentation uses replacement installs and confirms the target app remains installed. The final APK is installed on the Pixel 9 without uninstalling or clearing app data.

## What to check on the phone

1. Open a disposable recording with no previously resolved AI suggestion. Confirm the 60/40 layout and pinned two-column actions remain usable. Scroll the description pane down to **AI SUGGESTION** and verify the simulation notice.
2. Generate. Confirm progress appears and a simulated five-section description follows after about three seconds, with **AI-generated. May be incomplete or incorrect.** No full-text read-aloud should start automatically.
3. Choose **ACCEPT DESCRIPTION**. Verify the AI section disappears and the accepted text appears only under **YOUR DESCRIPTION**. On another disposable recording, generate and **EDIT SUGGESTION**, change and save the text, and verify the same behavior. Use **EDIT DESCRIPTION** for later revisions. On a pending suggestion, cancel an edit and confirm it remains pending without changing the saved participant description.
4. On another disposable recording, **REJECT SUGGESTION**. Verify the entire AI section disappears, while any existing participant description stays unchanged. Force-stop without clearing data, relaunch and reopen accepted, edited and rejected clips: none should show an AI section or regeneration controls.
5. On a clip without a saved VLM result, Generate and promptly **CANCEL AI GENERATION**. Check the cancellation message and that retry works. Also leave the review screen during generation and check playback/review still work on return.
6. With TalkBack, exercise Generate, output focus, Accept Description, Edit Suggestion, Save, Cancel and Reject Suggestion. Verify readable labels, predictable keyboard/focus and scroll access at a large font size. Check Approve and Review Later with neither description nor AI output; use disposable clips for Delete and capture/playback regression.
7. Optional Database Inspector check in `ecological_capture.db`: inspect `vlm_runs` and `annotations`. Generation alone must leave `firstPresentedAtEpochMs` null until output is visibly presented; reopening must not replace its first timestamp. Amendments must be separate rows with `source = PARTICIPANT_AMENDMENT` and `parentVlmRunId`; original VLM output/provenance and prior participant revisions must remain. For an off-screen exposure check, first save a long participant description, generate at the bottom of the pane, and inspect the null timestamp before scrolling up to the output. Also check that output under an open editor is not marked presented until the editor closes and the output is visible.

## Limits and stop

The automated tests drive the visibility callback explicitly; they verify database/controller behavior, not actual Compose viewport geometry, TalkBack or human exposure. Those physical checks remain pending above. Injected fake load/generation failures are covered automatically; no participant-facing failure injection controls were added. Real process-death UI behavior and physical glasses capture remain manual checks.

No real model or frame extraction ran. Fake delay and instrumentation timings are not Qwen performance measurements. Native integration/model verification belongs to Checkpoint 4; deletion cleanup of annotation/VLM rows remains scheduled for the later cleanup checkpoint. If generation completes and its transaction commits before cancellation, its valid persisted result remains available on return.

Checkpoint 4 has not started. Stop here for manual review and approval.
