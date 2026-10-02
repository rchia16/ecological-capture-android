# Phase 2 Checkpoint 7: real participant suggestion review

Implemented 2026-10-02. Stop for participant-facing and TalkBack review before Checkpoint 8.

## Changes

- `ClipReviewViewModel` selects `QWEN_PARTICIPANT`, available in debug and release. The same saved-video engine, official model bundle, fixed `ecological_scene_description_v2` prompt and complete provenance from Checkpoint 6 are used. Defaults: three frames at 15/50/85%, maximum long edge 1024, greedy generation, maximum 384 output tokens. Construction does not load the model.
- Generate in Add/Edit Description and optional automatic preparation now analyze saved frames locally. Automatic preparation remains off by default and does not automatically present or accept text. Existing simulated runs are retained and explicitly identified when reviewed.
- Preparation/generation status, a cancellation control, readiness, and the incomplete/incorrect notice are available. Generation may take about 10 to 13 minutes on the measured Pixel 9. The display stays awake during foreground generation; leaving review or the foreground cancels it. Capture remains protected from concurrent inference.
- Complete engine cycles are serialized so a cancelled run finishes cleanup before another load/generate cycle. Queued cancellation exits the progress state. Cancellation may wait for an in-flight vision encoding call.
- Missing or unverifiable models produce readable errors while writing and recording decisions remain available. The existing installed model bundle is used; no model downloads or APK bundling were added.

## Description and accessibility behavior

The accepted full-screen editor and pinned footer remain. Use suggestion copies text into the editable draft. The participant can change the description field and must choose Save description to persist it. That creates a separate `PARTICIPANT_AMENDMENT` with a parent VLM run reference; the original raw output, model/prompt/frame/runtime provenance and uncertainty wording are never overwritten. No description is saved automatically by generation or Use.

Reject suggestion leaves the existing description/draft intact and offers Generate another suggestion. Resolved suggestions disappear from participant-facing AI review; their raw records remain in the background. Approve, Review Later and Delete remain independent of accepting or generating a suggestion. No demographic, clinical profile, participant description, intended problem or outcome is supplied to Qwen.

Only short status/error/readiness messages use polite live regions. The full model output has no live region and is not automatically sent to text-to-speech. Pane titles, headings, focus restoration and minimum 56 dp controls are retained. Human TalkBack usability verification is still required; compilation alone does not establish screen-reader usability or description accuracy.

## Build status and scope

Debug and release builds completed. The debug update is installed by replacement install, retaining app data and app-owned models. No new automated tests were added or run for this checkpoint. Checkpoint 6's real inference and persistence measurements remain the runtime evidence; this checkpoint's complete participant-facing use/edit/save and TalkBack flow is for manual review below.

Full force-stop/restart, error injection, low-memory and deletion recovery validation belongs to Checkpoint 8. Model descriptions can still make unsupported intention or fine-text claims observed in Checkpoint 6. Review them as optional coarse context, not as OCR or verified annotations.

## What to check now

1. Enable TalkBack. Open Review Recordings and a recording with no suggestion, then Add/Edit Description. Focus Generate AI suggestion and activate it. For an existing simulated suggestion, review and reject it first to enable Generate another suggestion.
2. Navigate the progress message and Cancel control. Confirm the pinned footer remains reachable, the screen stays awake, and the full model text is not automatically spoken. Allow a generation to finish with the app open; it may take about 10 to 13 minutes.
3. Read the output with TalkBack. Check the incomplete/incorrect notice. Compare coarse layout and visible actions with playback; challenge small text, numbers and inferred intentions.
4. Choose Use suggestion. Edit in Your description, choose Save description, and reopen review. Confirm only the saved description is shown, with the edits retained.
5. On another suggestion, choose Reject suggestion. Confirm the prior description remains and Generate another suggestion is available inside the editor. Check that writing, Approve and Review Later are usable without accepting AI text.
6. For research record inspection, use Android Studio Database Inspector on the debug app: `vlm_runs.rawOutput` and provenance retain the original; `annotations` contains a separate `PARTICIPANT_AMENDMENT`, edited text and matching `parentVlmRunId`. Do not modify records in the inspector.

Approve this checkpoint after these checks. Checkpoint 8 is recovery and deletion hardening.
