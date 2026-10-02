# Optional automatic AI suggestion preparation

Date: 2026-10-01. A refinement of Checkpoint 3; real Qwen integration has not started.

## Behavior

- **Review Recordings** contains **Prepare AI suggestions automatically** above the clip list, with a labelled switch and explanatory text. It scrolls with the queue for enlarged text. Default is off. The preference is stored locally and survives restart; enabling it applies when an eligible clip is opened. The description editor contains only its description/suggestion flow.
- When enabled, a foreground/resumed clip review prepares a suggestion only after clip history is loaded, when no VLM run exists, clip decisions are not being saved, and recording/finalization are idle. No model work starts at application startup or just because a recording finishes.
- The app records an automatic-attempt marker before starting. Each clip gets at most one automatic attempt across reopen/restart, including failure or cancellation. Attempts are local clip IDs, not clinical/profile data, and never enter a VLM request. Existing, accepted or rejected suggestions are not automatically regenerated.
- Preparation does not display generated text, open the suggestion-review screen, shift focus, insert a draft, create an annotation or change approval/review state. A polite preparation/readiness status and Cancel AI Preparation are available while normal review/editing continues.
- Participants explicitly open Add/Edit Description and Review AI Suggestion to see output. The exposure timestamp remains null until text is actually visible in the resumed suggestion-review screen. Use/Edit copies into the draft; Save explicitly commits a separate participant amendment.
- Manual generation remains available when automatic preparation is disabled or has failed/cancelled. Generate Another Suggestion remains an explicit action after rejection. No repeated automatic attempts occur after rejection.
- Disabling the preference cancels current automatic work. Leaving the review screen, moving automatic work out of foreground review, or recording/finalization becoming busy requests cancellation. Delete still waits for engine cancellation/cleanup. If a valid generation has already committed before cancellation, its output remains stored.

The runtime is still the labelled FakeVlmEngine with a three-second delay. Native model validation, resource management and real coexistence with playback/capture require the later native checkpoints. The existing 60/40 review layout, pinned two-column actions and full-screen description flow are preserved.

## Implementation

Added `data/AiSuggestionPreferences.kt` (SharedPreferences, serialized writes, persisted attempt IDs) and `ui/review/AiPreparationStatus.kt`. Updated VlmReviewWorkflow, ClipReviewViewModel, ClipReviewScreen and DescriptionEditorScreen. MainActivity observes only distinct capture-busy changes and passes them to review; no recorder/DAT frame path was modified. No dependency or Room schema change.

Settings writes run on IO with safe participant-facing errors. Automatic eligibility is rechecked after claiming an attempt. The screen gates start/cancellation using lifecycle and capture state. The preference switch is a labelled toggleable row with minimum 56 dp height. Readiness is a brief live region, never a full-output announcement or exposure callback.

## Automated results

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
& ./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
```

Final build passed. **38 JVM tests passed per debug/release variant**, zero failures/errors. **36 device instrumentation tests passed** on Pixel 9 / Android 16 (`OK (36 tests)`, 7.138 seconds). Four added tests verify off-by-default and preference persistence, automatic output retaining null exposure/draft/decision states, failure with no retry after recreation plus manual retry, and cancellation/rejection with no automatic regeneration. All previous tests passed. Tests use disposable database, preference and media fixtures; production data are not modified. Replacement installs preserve the target app; the runner confirmed it remains installed.

The first build found duplicate capture-state declarations/imports introduced during editing; these were removed before the successful build. `git diff --check` passed with normal LF/CRLF notices.

## What to check on the phone

1. Open Review Recordings. Verify the preference is initially off. Open a clip and confirm the toggle is absent from Add/Edit Description and manual Generate still works. Cancel the draft.
2. Enable the preference on Review Recordings, then open an ungenerated disposable clip. Confirm preparation starts without opening suggestion review or changing typed text. Keep writing or return to Clip Review; when ready, only a brief readiness message should appear. No generated text should be spoken automatically.
3. Explicitly choose Review AI Suggestion in the editor. Verify Use/Edit, Save/Cancel and pinned review actions still work. Original output should remain unpresented in Database Inspector until you actually display it.
4. Open another ungenerated clip with the preference enabled. Cancel preparation promptly, then reopen it. There should be no automatic retry; manual Generate should still work. Reject a generated suggestion and confirm only explicit Generate Another Suggestion starts another run.
5. Force-stop without clearing data and relaunch. Confirm the preference persists. Turn it off, open another new clip, and verify no automatic preparation. Turning it off during automatic preparation should cancel that work.
6. With TalkBack and enlarged text, verify switch label/state, preparation/readiness announcements, stable focus while typing and accessible Cancel. Check playback and Approve / Review Later / Delete on disposable clips, plus physical capture. Confirm preparation does not run alongside recording/finalization.

The tests verify controller/preferences/persistence; they do not exercise Compose lifecycle/capture gating, TalkBack or actual native resource use. Those are physical/manual acceptance checks. Persisted attempt IDs will need inclusion in the later clip-deletion cleanup. Unsuccessful automatic attempts intentionally require explicit retry. Checkpoint 4 remains unstarted.

Location follow-up: added `AutomaticPreparationSetting.kt`, moved the toggle to the queue and
removed preference controls from the description editor. `:app:assembleDebug` passed and the
updated APK was installed with `adb install -r`. No additional tests were run for this UI move.
Check the queue toggle's label/state with TalkBack, its position above recordings, and persistence
after restart. Existing preference values are retained.
