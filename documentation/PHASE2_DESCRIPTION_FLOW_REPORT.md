# Phase 2 description flow refinement

Date: 2026-10-01. Supersedes the inline suggestion UI in the Checkpoint 3 report. Real inference remains deferred to later checkpoints.

## Current behavior

- Clip Review displays the video and current participant description, with the existing 60/40 split and two-column bottom actions. There is no AI section on Clip Review.
- Add/Edit Description opens a full-screen editor with a labelled multiline draft, optional Generate AI Suggestion, and Save Description / Cancel at the bottom. Opening the screen focuses its heading rather than immediately opening the keyboard.
- Generate opens a separate full-screen suggestion-review step. The unsaved description is preserved during generation, failure, cancellation and rejection. Progress/error messages are announced briefly; generated text is not automatically spoken.
- Use Suggestion copies the suggestion into the editable draft and returns to the description screen. It does not save automatically. The redundant Edit Suggestion action has been removed. Save Description explicitly commits a separate `PARTICIPANT_AMENDMENT`, preserving original AI output and participant revisions. Cancel discards the unsaved draft.
- Reject Suggestion returns to the editor without changing the draft and offers Generate Another Suggestion. This is available again after reopening a rejected clip. Every regenerated result is a new immutable output/provenance row; the rejected run remains retained with its disposition/exposure timestamp.
- Back from suggestion review returns to the editor and cancels active generation. Back or Cancel from editing returns to Clip Review without saving. A pending suggestion can be reopened with Review AI Suggestion. Saved amendments are presented only as the current description on Clip Review.
- Accepted/edited-and-saved suggestions remain resolved; regeneration is offered after rejection, not automatically after acceptance. Approval, Review Later and Delete remain separate from description/suggestion actions.

The runtime is still FakeVlmEngine with a three-second delay and clearly labelled simulated output. Repeated fake generations intentionally return identical fixture text. There is no frame extraction, Qwen loading or real inference.

## Accessibility implementation

Each full-screen step has an accessibility pane title and heading. Heading/field focus requests follow explicit navigation; generation completion does not force focus onto the output. Returning to Clip Review requests keyboard focus on Add/Edit Description. TalkBack accessibility-focus behavior is not established by this keyboard-focus request and needs the physical check below. New controls retain minimum 56 dp touch targets and wrapping labels. Editor content scrolls above bottom actions, with safe-area and keyboard insets. The video player is released when the editor replaces Clip Review and recreated on return.

Suggestion-review actions are pinned below the scrolling text, separated by a divider. The first
row contains a full-width Use Suggestion; the second contains Reject Suggestion / Back to
Description. A brief explanation says the suggestion can be edited before saving.
Buttons share equal row heights and grow for wrapping labels. Reading order remains
heading, suggestion, then actions; reaching the end of the suggestion is not required to act.
While generating or retrying, Cancel/Generate and Back also stay in the bottom action area.

Exposure is recorded only by visible output in the resumed suggestion-review screen. Opening the editor, generating, restoring history or displaying Clip Review does not establish exposure. Original raw text, uncertainty wording, model/runtime/prompt provenance and historical results are kept separately from participant amendments. Participant drafts and demographic/clinical data are not passed to the model.

## Changes and checks

Added `DescriptionEditorScreen.kt`; removed the obsolete `DescriptionEditorDialog.kt`. Modified ClipReviewScreen, AiDescriptionSection, DescriptionEditor and VlmReviewWorkflow. Updated workflow tests and the implementation plan to match the approved flow. The Room schema and dependencies are unchanged.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
& ./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
```

Build passed; **38 JVM tests per debug/release variant** and **32 device tests** passed. Instrumentation reported `OK (32 tests)` in 6.157 seconds. After renaming suggestion callbacks to match their labels, the final debug APK was rebuilt successfully and installed with `adb install -r`, preserving app data. `git diff --check` passed with normal line-ending notices.

The 15 workflow/Room tests now cover regeneration preserving an unsaved draft and all prior runs, failed regeneration retaining draft/history, explicit Save after copying, and preservation of the draft's original revision reference even when another description is saved concurrently. All existing capture/recovery/persistence, editor and prompt tests passed. These tests drive controller/visibility callbacks; they do not operate the Compose screens or validate TalkBack. Device tests use disposable databases and synthetic media fixtures, not participant recordings.

## What to check on the phone

1. Add/Edit Description should open a full screen. Enter a multiline draft, then Generate. Reject the suggestion: the draft must remain, and Generate Another Suggestion must be available.
2. Regenerate. Use Suggestion must copy into the field without saving. Cancel should return to Clip Review with the previously saved description unchanged.
3. Use Suggestion, then focus the description field, change the text and Save. Clip Review should show only the saved participant description. Reopen after force-stop without clearing data and verify persistence.
4. Reject another suggestion, leave the editor and reopen it. Verify regeneration remains available. Cancel generation and return from suggestion review using Back; neither action should change the draft.
5. With TalkBack, verify screen-title announcements, reading order, output focus, Use/Edit/Reject labels, and return orientation at Add/Edit Description. At large font sizes, check scrolling, wrapped labels and Save/Cancel with the keyboard open and closed.
6. Verify video playback works after returning from editing, the 60/40 layout and bottom actions remain usable, and Approve / Review Later / Delete still work on disposable recordings independently of annotation. Verify physical capture as part of the Phase 1 regression.

Saved descriptions persist; unsaved drafts are not promised to survive process death. Keyboard focus, TalkBack and actual viewport exposure require manual acceptance. Checkpoint 4 has not started.

### Pinned-action layout follow-up

The pinned two-row suggestion actions were built successfully with `:app:assembleDebug --offline
--console=plain` and installed on the Pixel 9 with `adb install -r`. No additional tests were run
for this layout-only follow-up. Check that all four actions stay visible while scrolling a long
suggestion, and verify wrapping labels, TalkBack traversal and touch targets at a large font size.

The subsequent simplification removed Edit Suggestion, retained Use/Reject/Back and added
"You can edit this suggestion before saving." The debug build passed and was installed with
`adb install -r`; no additional tests were run for this UI simplification. Check that Use copies
into the editable field, Cancel preserves the saved description, and only Save commits changes.
