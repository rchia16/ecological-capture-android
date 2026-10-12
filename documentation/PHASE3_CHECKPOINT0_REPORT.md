# Phase 3 Checkpoint 0 - Baseline Release Gate and Protected Data Audit

Status: PARTIAL
Date: 2026-10-12 (Australia/Sydney)
Baseline commit: `208c55a` (2026-10-09); working tree clean before this audit.

## Scope implemented

Read the entire Phase 3 plan, Phase 1 completion report, Phase 2 Checkpoint 8/9, description-flow and background-preparation reports, and VLM runtime documentation. Inspected the current implementation and newer automatic-preparation report/history. Completed host build/unit regressions and a read-only device preflight. Added this report and the decision log. No application, schema, model, native runtime or capture code changed. No transfer functionality was added.

### Current baseline and protected paths

| Area | Actual code and behavior |
| --- | --- |
| Identity/build | `app/build.gradle.kts`: application ID `com.rchia.ecocapture.phase0`, version code 1 / `0.1.0-phase0`, minimum API 31, target/compile API 36, ARM64. Release has no signing configuration and is not a replacement for the signed debug installation. Benchmark package suffix is opt-in and must not be used for a research-app upgrade. |
| Capture | `Phase0ViewModel.kt`, `capture/HevcMp4Recorder.kt`, `data/CompletedRecordingPersistence.kt`: DAT 0.9.0 compressed HEVC is finalized locally, then inserted into Room. Failed insertion retains MP4 for reconciliation. |
| Room | `data/local/EcologicalCaptureDatabase.kt`: version 2, `clips`, `annotations`, `vlm_runs`, explicit `MIGRATION_1_2`; no destructive fallback. No export or transfer tables. |
| Review decisions | `data/local/ClipDao.kt`, `data/RoomClipRepository.kt`: Review Later stores DEFERRED/UNDECIDED; Approve stores REVIEWED/APPROVED locally. Decisions do not require descriptions or AI. Approved clips leave the pending queue; no approved-recordings browser yet. |
| Description/history | `data/AnnotationRepository.kt`, `ui/review/DescriptionEditor.kt`, `VlmReviewWorkflow.kt`: revisions retain superseding links and amendment parent run; explicit Save creates participant text. Use alone does not save. |
| VLM provenance | `data/local/VlmRunEntity.kt`, `data/VlmRunRepository.kt`: exact raw output/description, model and projector hashes, runtime commit, prompt version, frame/config JSON, duration, generation/presentation times and disposition; first presentation can remain null. No dedicated approved-at column exists in clips: do not later invent historical approval times from a general updated timestamp. |
| Local runtime | `vlm/Qwen3VlEngine.kt`, `VlmPrompt.kt`: participant engine is local Qwen, with the selected three-frame policy and 384-token limit. No model/prompt change made. |
| Capture priority | `vlm/VlmExecutionGate.kt`: one engine cycle, reserve capture, cancel active inference, wait for cleanup, release after capture/finalization. |
| Durable preparation | `vlm/background/BackgroundAiPreparation.kt`, `AiPreparationWorker.kt`: unique per-clip local AI work, clip ID input, charging/battery constraints, idempotent run IDs, eligibility recheck, foreground/background gating, bounded attempt and cleanup. These requests are not uploads. |
| Deletion | `ClipDao.markDeletedAndEraseText`, `RoomClipRepository.deleteClip`, review cancellation and startup reconciliation: tombstone/text purge transaction precedes file removal; failed database operation retains media, incomplete file removal can retry; late saves/decisions cannot revive deleted clips. |
| Screen flow | `MainActivity.kt`: Home -> Review Recordings -> clip -> full-screen Description -> explicit suggestion review/Use/Reject -> Save; Review Recordings -> Settings -> AI descriptions. Unsaved-edit confirmation and pinned controls are present. Human accessibility acceptance is still needed. |
| Network boundary | No ecological event exporter, gateway client, transfer worker, FileSender adapter or upload action found in app sources. Existing INTERNET permission and Meta DAT integration mean this is not a claim that all application traffic is absent. |
| Safe runner | `tools/run-room-tests.ps1`: replacement installs of target and test APKs, direct instrumentation and installed-package check; no uninstall, clear-data or connected-test task. Tests inspected use isolated databases/cache fixtures; native/media/system opt-ins require explicit arguments. |

### Differences from earlier report evidence

`2c5d610` includes Checkpoint 8/9 hardening, later automatic batch preparation, AI Settings, unsaved draft confirmation and an isolated model benchmark. `VLM_RUNTIME.md` explicitly says batch preparation/settings follow-ups were built and installed without new tests. Earlier descriptions of simulated inference or automatic preparation only when opening a clip are historical. Current source uses the participant Qwen engine and observes eligible saved clips for durable batch scheduling. Historical 62-pass device and 11-scenario recovery results are not counted as new results here.

One existing Home explanatory string says there is no annotation/VLM in this build, despite the current implementation. This is a pre-existing wording inconsistency; it has not been changed by this audit.

## Files added/modified

- `documentation/PHASE3_CHECKPOINT0_REPORT.md` (added).
- `documentation/PHASE3_DECISIONS.md` (added).

## Commands executed

Repository inspection used `rg --files`, targeted `rg -n` searches and `Get-Content` on the plan/reports/source/runner. No FileSender INI file was read. Git required a per-command safe-directory option because the sandbox account differs from the repository owner; global Git configuration was not changed.

```powershell
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android status --short
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android log -6 --format='%h %ad %s' --date=iso
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android show --stat 2c5d610

$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase3Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase3Gradle :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain
```

Initial sandbox build failed during generated catalog compilation. Repeating with `--stacktrace` identified `AccessDeniedException` for the existing Gradle distribution's `gradle-stdlib-java-extensions-8.14.1.jar`. The same offline command with approved host access passed in 34 seconds (134 tasks, 17 executed). Initial failures are not counted as test failures because tests had not run.

```powershell
adb devices -l
adb -s 49280DLAQ0020M shell getprop ro.build.version.release
adb -s 49280DLAQ0020M shell getprop ro.build.version.sdk
adb -s 49280DLAQ0020M shell pm path com.rchia.ecocapture.phase0
adb -s 49280DLAQ0020M shell am get-current-user
adb -s 49280DLAQ0020M shell pm list users
adb -s 49280DLAQ0020M shell dumpsys user
adb -s 49280DLAQ0020M shell run-as com.rchia.ecocapture.phase0 ls -l databases
adb -s 49280DLAQ0020M shell run-as com.rchia.ecocapture.phase0 ls -l files/models/qwen3vl
adb -s 49280DLAQ0020M shell run-as com.rchia.ecocapture.phase0 ls files/recordings
```

Storage checks failed with `run-as: couldn't stat /data/user/0/com.rchia.ecocapture.phase0: No such file or directory`, including a host-access retry. `dumpsys user` explained the unavailable credential-encrypted storage: user 0 was `RUNNING_LOCKED`. The package remained installed. `cmd user is-user-unlocked 0` was unsupported. No attempt was made to bypass the device lock.

Additional checks:

```powershell
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height -show_entries format=duration -of json app/build/generated/checkpoint5-fixtures/checkpoint5/short.mp4
& 'D:/Ray/Android/sdk/build-tools/36.1.0/apksigner.bat' verify --print-certs app/build/outputs/apk/debug/app-debug.apk
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android diff --check
```

Runner parsed with the PowerShell language parser: zero errors. Summed JUnit XML under `app/build/test-results/testDebugUnitTest` and `testReleaseUnitTest` for counts below. Final `git diff --check` passed; separate checks of the two untracked reports found zero trailing-whitespace errors. Final status contained only these two new documentation files.

## Automated tests

| Check | Result |
| --- | --- |
| Debug JVM | 52 passed, 0 failures, 0 errors, 0 skipped |
| Release JVM | 52 passed, 0 failures, 0 errors, 0 skipped |
| Debug APK | Built successfully |
| Release APK | Built successfully; unsigned release is not for installation over the data-bearing debug app |
| Instrumentation APK | Built successfully |
| Room/device regression | Not run: locked storage prevented a pre-install retention baseline |
| Safe-runner syntax/static review | Passed; replacement install/direct instrumentation path |
| Synthetic HEVC probe | Passed: short fixture HEVC, 320 x 180, 1.000 second |

Build success includes tasks that were up to date; no clean rebuild was requested. JVM tests actually executed. No new tests were added for this documentation-only audit. Unit tests do not establish real glasses, playback, native inference, TalkBack or retained-data acceptance.

## Physical-device tests

Read-only preflight only: Pixel 9 `49280DLAQ0020M`, Android 16/API 36, user 0, installed debuggable target version code 1 / `0.1.0-phase0`. No replacement install, instrumentation, capture, playback, native inference or accessibility walkthrough was performed. Accessibility setting read 0 and font scale 1.0 during the locked preflight; these do not establish accessibility acceptance.

## Gateway and AARNet tests

None. No gateway or FileSender calls, external transfers, infrastructure provisioning or credentials were introduced. Vendor/API feasibility is Checkpoint 1 work.

## Data-safety / retention check

No phone data were cleared, uninstalled, copied or deleted. Existing recordings were not opened or used as fixtures. Installed-package presence is verified; record/media/model preservation has not yet been measured. Historical inventories (10 active clips, 13 annotations, 16 runs) are not current inventory results.

Manifest has `allowBackup=false`. Replacement installation is an upgrade procedure, not a disaster-recovery backup. An approved recovery strategy remains pending. The existing host retention comparer extracts database snapshots; it was inspected but not run, to avoid copying participant content during this checkpoint inventory.

### Safe upgrade procedure to complete after unlock

1. Unlock the phone once after boot; establish a current on-device inventory and consistent baseline including clip decisions/tombstones, annotation revisions, VLM provenance, MP4 files and model files. Record counts/digests without exporting participant text. If the app is processing work, account for legitimate concurrent changes before comparing retention.
2. Confirm installed signing certificate against the replacement APK and unchanged package. Built debug certificate SHA-256: `e1c76d42d563e211860d61f1ede5c8fe1a4ff29944cdd95141eeebe23bcdfe10`. Installed certificate comparison has not been executed. A signing mismatch must stop installation; never resolve it by uninstalling.
3. Use `adb install -r` only, or the inspected `tools/run-room-tests.ps1 -Serial 49280DLAQ0020M` after preflight. Do not use `connectedDebugAndroidTest`, `pm clear`, uninstall, or change the research package/signing identity. Do not install the unsigned release APK.
4. Compare baseline and post-test records/files/models, including tombstones; verify the package is still installed. Reopen and check existing descriptions/decisions with the investigator. Do not claim retention solely because installation succeeds.
5. Agree and validate an approved recovery-copy process separately before research rollout. Avoid raw sensitive snapshots in Git, ordinary logs or unapproved backups.

### Disposable fixtures

Existing generated `checkpoint5/{normal60,short,portrait,rotated}.mp4` fixtures are synthetic HEVC test patterns generated by `tools/prepare-frame-sampler-fixtures.ps1`. Sizes: 597552, 15029, 16666 and 15029 bytes respectively. The short fixture was independently probed. These can support synthetic decoder/engineering checks and contain no recorded participant footage. Future network metadata must also be entirely synthetic.

No actual glasses clip is designated disposable yet. The presence of `clips/clip_439.mp4` or historical phone clips does not establish permission to use them for transfer tests. Investigator identification of a harmless glasses fixture remains required.

## Manual acceptance steps

On the unlocked current build, use confirmed disposable clips and record outcomes:

1. Glasses registration/discovery -> camera -> record -> stop/finalize -> queue -> play/pause/replay; repeat after returning from review.
2. Add/edit/save participant descriptions; check unsaved Back/Keep editing/Discard behavior. Optional local Qwen generation -> explicit review -> Use/edit/Save; also Reject/regenerate/cancel. Original output and participant revisions remain separate.
3. Review Later/Approve without annotation or AI. Delete only a disposable recording; reopen and verify tombstone, text purge and absence without resurrection.
4. Settings -> AI descriptions: automatic/background/charging-only labels/states, persistence, notification denial, batch eligibility, foreground pause/resume, cancellation and recording priority. No description or decision changes automatically.
5. TalkBack traverses Home/queue/playback/editor/suggestion/settings/actions/errors/back; output is not fully announced automatically. Large text leaves all pinned controls reachable.
6. Verify existing valued descriptions, decisions, recordings and model files after a certificate-matched replacement install.

Human results and disposable-clip identification were requested during this audit; no results have been received at report creation.

## Known failures and limitations

- Locked phone prevents current clip/annotation/VLM inventory, safe-upgrade retention comparison and Room execution in this session.
- Physical glasses and TalkBack/large-text acceptance remain outstanding; automation cannot certify those human results.
- Existing later batch/settings changes have current JVM/build coverage but no new device/manual acceptance here.
- Backup/recovery, disposable glasses fixture and external governance decisions remain unconfirmed.
- Existing manifest contains Meta DAT client metadata; this audit neither changed it nor equates it with a FileSender credential. No full secret-history or SDK packet audit was performed.

## Exit criteria

- [x] Protected code paths documented from current source.
- [ ] App data backup/retention strategy confirmed and measured after upgrade.
- [ ] Physical glasses acceptance recorded.
- [ ] TalkBack acceptance recorded.
- [x] Synthetic non-participant HEVC fixtures identified.
- [ ] Disposable glasses recording selected by investigator.
- [x] No Phase 3 network path added.

## Recommended next checkpoint

Finish Checkpoint 0 after device unlock and investigator acceptance results. Resolve the pending decisions in `PHASE3_DECISIONS.md`; approve the baseline explicitly before authorizing Checkpoint 1 harmless-file protocol/encryption feasibility work.

STOP. Do not continue without explicit approval. Checkpoint 0 is PARTIAL; no claim is made that its release gate has passed.
