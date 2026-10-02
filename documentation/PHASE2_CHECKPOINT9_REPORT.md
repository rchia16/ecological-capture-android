# Phase 2 Checkpoint 9: scientific provenance and regression

Date: 2026-10-02. **Automated validation passed. Phase 2 completion remains pending the physical glasses and TalkBack checks below.** No upload, FileSender, REDCap, enrolment, participant-profile, consent, or structured problem/solution work has started.

## Scientific state matrix

`Checkpoint9ProvenanceTest` uses a disposable Room database, disposable media and an explicitly simulated engine. It validates all seven combinations through close/reopen, without editing participant records:

| State | Expected participant records | Expected VLM record |
| --- | --- | --- |
| Neither | None | None |
| Participant only | Independent description | None |
| VLM only | None | Raw output/provenance, unpresented |
| Participant then VLM | Original description retained | Raw output/provenance, unpresented |
| VLM then participant | Independent description saved after presentation | First presentation retained |
| VLM + amendment | Amendment and subsequent revision linked to the run | Raw output unchanged |
| Participant + VLM + amendment | Original description, amendment and subsequent revision retained | Raw output unchanged |

The matrix checks that Use alone creates no annotation; Save creates a separate amendment; later revision keeps its parent run and supersedes the preceding participant record; only one annotation is current; uncertainty, raw output and provenance survive reopen; clip decisions and source bytes remain unchanged. Other workflow tests cover ignored suggestions, regeneration, stale edits, failure, cancellation and presentation callbacks.

### Timestamp interpretation

- `generatedAtEpochMs` records completed output generation, not participant exposure.
- `firstPresentedAtEpochMs` is null until the visible-output callback. The first recorded presentation is retained across later presentation calls.
- `annotations.createdAtEpochMs` records the participant **save event** for each revision.
- Comparisons can classify saved records before/after recorded presentation. A null presentation timestamp means no recorded exposure; it is not a zero timestamp.

These timestamps do not prove when the participant first typed the words, that they read or understood the suggestion, that the suggestion influenced their account, or that the model described their actual problem. A draft can be typed before presentation and saved afterwards. Equal timestamps or a changed phone clock require conservative interpretation. No clinical/demographic/profile data enter generation.

## Fixed runtime and model provenance

| Setting | Value |
| --- | --- |
| Model | Qwen3-VL-4B-Instruct, Q4_K_M |
| Language-model file | Qwen3VL-4B-Instruct-Q4_K_M.gguf, 2,497,281,664 bytes |
| Language SHA-256 | `66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a` |
| Projector | mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf, 453,974,304 bytes |
| Projector SHA-256 | `30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d` |
| Runtime commit | `0c1e57098bba43ac29e6e3b677cdceebdd22334f` |
| Prompt version | `ecological_scene_description_v2` |
| Requested frames | 3 at 15%, 50%, 85%; independent chronological images |
| Maximum long edge | 1024 pixels, aspect ratio retained, no upscaling |
| Context / batch | 8192 / 512 |
| CPU threads | 4 |
| Output / sampling | 384-token limit, greedy, temperature 0, no random seed |

New real runs persist both model hashes, commit, prompt version and exact prompts, formatted prompt hash, requested and available actual frame times, frame dimensions, extraction duration, generation configuration and native timings/stop reason. Raw output and description remain identical unedited strings. Participant edits are separate PARTICIPANT_AMENDMENT rows. Engineering/simulated fixtures remain identifiable through their own provenance.

The release APK contains the ARM64 native library and no GGUF files. Model verification and inference are local. Code inspection found no VLM/annotation upload path; this is not a packet-level audit of the Meta Wearables SDK or all application network traffic.

## Performance distribution

Historical Checkpoint 6 measurements, Pixel 9 / Android 16 / CPU native Release. No additional full-length generation benchmark was run for Checkpoint 9. Each of three saved workspace clips was run once at each of 1, 3 and 5 frames; caches and temperature were uncontrolled.

| Metric | Selected 3-frame configuration (n=3): min / median / max | All frame configurations (n=9): min / median / max |
| --- | --- | --- |
| Load + hash verification | 4.438 / 5.293 / 5.456 seconds | 3.632 / 5.008 / 8.411 seconds |
| Inference | 600.333 / 736.122 / 788.482 seconds | 426.687 / 736.122 / 1298.944 seconds |
| Peak RSS | 4.344 / 4.421 / 4.428 GiB | See Checkpoint 6 per-run table |

Inference includes RGB ingestion, tokenization, vision encoding, prefill and decoding; it excludes model load/hash verification and frame extraction. Peak RSS includes loading. Selected three-frame latency is approximately 10.0–13.1 minutes, median 12.3 minutes. This is a small descriptive sample, not a production latency guarantee or a background battery benchmark.

Thermal status changed from NONE to LIGHT on the first run and remained LIGHT for subsequent measurements. Physical throttling and energy use were not measured. No OOM/process death occurred in the nine completed inference comparisons.

Runtime/persistence failure rate: **0/9 observed**, selected configuration **0/3**. One five-frame output reached the token limit and ended mid-sentence. These counts do not measure semantic accuracy: recorded outputs include unsupported fine-text and intention claims. Generation remains optional coarse scene context, never OCR, ground truth, participant identity/diagnosis, personal problem or solution-success evidence.

## Automated regression and recovery

Debug/release unit regression: **52 tests per variant passed**. Debug, release and instrumentation APKs compile. Final ordinary Pixel 9 suite: **62 passed, 5 opt-in tests skipped (JUnit OK, 67 tests)**, including the seven-state matrix. All **11** isolated system-recovery scenarios passed separately. Actual native cancellation passed with **123 ms cancel/join**, no result returned and successful unload/reload. Initial test timeouts and an inconsistent approved/deferred fixture were investigated; earlier failed attempts are not counted as passes. Details and limits are in [Checkpoint 8 report](PHASE2_CHECKPOINT8_REPORT.md).

Checkpoint 8's disposable WorkManager protocol covers model missing/hash mismatch, actual invalid-video extraction, injected native/OOM failures, success and relaunch, background execution, force-stop, process kill, notification cancellation and critical-memory retry. Instrumentation must stay alive during host-driven background/process events because finishing instrumentation kills its process. Actual JNI cancellation uses the existing opt-in native cancellation test. Physical OOM is not deliberately induced.

The pre/post snapshot comparison passed: all **10 active clips, 13 participant annotation records and 16 VLM runs** were identical. Replacement installs and direct instrumentation were used; no uninstall/clear-data flow was used. Recovery fixtures use separate databases and media. The app is reopened and ready for hands-on checks. Raw engineering artifacts and snapshots remain local and ignored.

## Required hands-on checks

Use disposable recordings for destructive actions. Complete after automated phone checks finish:

1. Confirm DAT registration/registered state, glasses discovery and camera start. Verify the compressed stream preview and expected recording dimensions.
2. Record, stop and finalize a new clip. Confirm its saved MP4 appears in Review Recordings; play, pause and replay it.
3. Check Review Later and Approve on disposable clips. Check Delete on another disposable clip with participant text and a suggestion; reopen and confirm it stays deleted.
4. With TalkBack, complete Home → Review Recordings → clip → playback → Description → AI preparation → suggestion review → Use → edit → Save → decision → Back. Also check Reject/regeneration and that generation is optional.
5. Confirm generated output is read only when explicitly reviewing it; the complete text is not automatically announced. Check progress, cancellation and error announcements, large text and pinned controls.
6. In Settings → AI descriptions, check all three switch labels/states, notification permission behavior, charging-only dependency and persistence. Confirm Back returns to Review Recordings.
7. Edit a description and choose Back or Android Back. Keep editing must retain the draft; Discard changes must preserve the previously saved description. Back with unchanged text should leave directly.
8. Confirm the three previously filled descriptions and existing approval/deferred decisions remain intact.

Record participant/device results here before marking the physical and accessibility completion criteria passed. The automated tests do not establish glasses connectivity, real capture quality, TalkBack usability or ecological description accuracy.
