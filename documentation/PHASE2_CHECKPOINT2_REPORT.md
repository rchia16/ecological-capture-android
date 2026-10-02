# Phase 2 Checkpoint 2 — Participant annotation

Date: 2026-10-01. Implementation and automated checks complete; manual acceptance pending.

## Changes

Clip Review now shows **YOUR DESCRIPTION**, the saved current text (or **No description added.**), and **ADD DESCRIPTION** / **EDIT DESCRIPTION**. The editor provides a labelled multiline field, **SAVE DESCRIPTION**, and **CANCEL**. Blank/whitespace-only drafts cannot be saved. Cancelling or dismissing the editor discards the draft without writing to Room. Saved text is retained exactly, including whitespace and uncertainty wording.

Saving uses the existing annotation repository. Adds create `PARTICIPANT` records; edits create new revisions with the original revision reference. Existing amendment provenance is preserved by that repository when revising an amendment. Prior text is retained. A stale save cannot overwrite another revision; the draft remains visible with an error and can be cancelled/reopened to load the latest revision. Writes run in ViewModel scope and a session guard prevents a completed write from updating another selected clip's editor.

Participant annotation does not change approval/review states or gate Approve, Review Later or Delete. Following participant review, actions occupy two columns pinned to the bottom within the screen's safe area: Add/Edit Description beside Approve, Review Later beside Delete, and Back in the left column of the final row. Below the recording metadata, the remaining space above the actions is split 60% for video and 40% for the description; the description scrolls independently. Buttons retain a minimum 56 dp height, with wrapping labels and equal heights within each row. The existing player fits the video within its allocated area. The description heading has heading semantics; the field has a visible accessible label, receives focus on opening, and saving/error text uses polite live regions. Full TalkBack and keyboard acceptance remains a physical manual check.

### Files

- Added `ui/review/DescriptionEditor.kt` and `DescriptionEditorDialog.kt` under the existing main package.
- Modified `ui/review/ClipReviewViewModel.kt` and `ClipReviewScreen.kt`.
- Added `app/src/test/java/com/rchia/ecocapture/phase0/ui/review/DescriptionDraftTest.kt`.
- Added `app/src/androidTest/java/com/rchia/ecocapture/phase0/data/DescriptionEditorTest.kt`.

This checkpoint uses the approved Room version 2 schema and v2 prompt foundation. No model generation UI is introduced at this checkpoint.

## Automated checks

Using Android Studio Java and the existing cached Gradle distribution:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
& ./tools/run-room-tests.ps1 -Serial 49280DLAQ0020M
```

- Debug and instrumentation APKs built successfully.
- **36 JVM tests passed in each debug/release variant**, zero failures/errors. Three new tests cover blank drafts, verbatim text and fixed revision references; all 33 previous tests also passed.
- **17 instrumentation tests passed** on Pixel 9 / Android 16, including five new editor/Room integration tests and all 12 previous persistence tests.
- New integration coverage: no description; cancel add; add/save; edit/save and revision history; exact text after database close/reopen and a new editor instance; cancel edit; blank save; duplicate/stale save rejection with draft retention; switching clips discards the unsaved draft and loads the selected description. Clip decision fields remain unchanged by annotation writes.
- `git diff --check` passed (Git emitted normal LF/CRLF conversion notices).

Device testing used replacement installs and direct instrumentation through the previously added safe runner. It did not uninstall or clear the target app. The runner confirmed the target remained installed afterward. Tests use disposable databases and synthetic media paths, never production clips. No claim is made that automated tests exercised the Compose UI, real process death, glasses capture or TalkBack.

## What to check on the phone

1. Open an existing recording. Verify playback, Pause and Replay, then scroll to **YOUR DESCRIPTION**. A clip without text should show **No description added.**
2. Choose **ADD DESCRIPTION**, enter a few lines (including an uncertain observation), and **SAVE DESCRIPTION**. Verify the exact text appears and the action becomes **EDIT DESCRIPTION**.
3. Go Back, force-stop the app without clearing data, relaunch and reopen that recording. Verify the saved text remains.
4. Edit and save. Verify the displayed current text changes. In Android Studio Database Inspector, inspect `ecological_capture.db` → `annotations`: the prior row should retain its text with `isCurrent = 0`; the new row should have `isCurrent = 1` and `supersedesAnnotationId` pointing to the prior ID. Both participant-authored rows should have `source = PARTICIPANT` and null `parentVlmRunId`. Automated tests already verify this revision contract in an isolated database.
5. Edit again, change the text, then **CANCEL**. Verify the saved text is unchanged. Also try cancelling a new description and entering only spaces: no annotation should be created and Save should remain disabled.
6. With TalkBack enabled, check the heading, field label/focus, Add/Edit and Save/Cancel announcements. Check that the keyboard appears predictably, multiline entry works, and dismissing the editor does not save. Verify the 60% video / 40% description split below the metadata, and that scrolling a long description keeps the video and two-column bottom actions in place. At a large font size, check wrapped button labels and that editor controls remain reachable with the keyboard both open and closed.
7. On disposable test recordings, verify Review Later and Approve both work without a description, and do not erase a saved description. Verify Delete confirmation/cancel and actual deletion on a disposable clip. Capture and review one new glasses recording as a Phase 1 regression check.

## Limits and next checkpoint

Only saved descriptions are persisted; unsaved drafts are not promised to survive process death or selecting another recording. Database reopen testing is not proof of Android process-death UI behavior. Physical accessibility and capture/playback regression checks remain pending above. Annotation/VLM deletion cleanup remains scheduled for the later cleanup checkpoint.

Checkpoint 3 (fake VLM UI flow) has not started. Stop here for manual review and approval.
