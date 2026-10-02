# Qwen3-VL Android runtime

## Checkpoint 8 recovery hardening

Deletion now atomically purges annotation/VLM text and commits a tombstone before removing files. Startup resumes incomplete removals; deleted clips cannot be restored by later decision updates. Background cancellation survives review exit and reports cleanup in progress. Critical memory pressure unloads and defers background inference; UI hiding alone does not cancel it. Missing persisted results are not reported as usable successes.

Debug/release builds and 52 unit tests per variant pass. The final Pixel 9 suite passed 62 tests with five opt-in skips; eleven isolated system-recovery checks and actual native cancellation passed separately. Participant records were unchanged in pre/post comparison. See [Checkpoint 8 report](PHASE2_CHECKPOINT8_REPORT.md) for recovery limits and [Checkpoint 9 report](PHASE2_CHECKPOINT9_REPORT.md) for the seven-state provenance matrix, performance distribution and remaining physical/TalkBack checks. Phase 2 completion awaits those hands-on checks.

## Optional background preparation

### Automatic batch preparation update (2026-10-02)

Enabling automatic preparation now queues every active saved clip without a current participant description or any existing VLM run, rather than requiring each clip to be opened. New eligible clips are observed through Room and queued while the preference remains enabled. Unique per-clip requests prevent duplicates; the execution gate still permits one model at a time. Settings and Review Recordings show the pending/preparing count. Explicitly enabling the switch again can retry finished failed/cancelled requests; ordinary database updates do not silently retry those requests. Workers recheck eligibility before loading so a description saved while waiting is respected.

Automatic batch requests are durable even when background preparation is off. In that mode inference requires the app to be visible; leaving the app cancels/unloads the attempt and defers it until reopening. Optional background preparation and notification permission remain separate. Charging constraints apply to requests submitted with background preparation enabled; changing preferences affects future requests. Android scheduling/backoff can delay starting or restarting an attempt. Turning automatic preparation off cancels automatic requests; turning background preparation off cancels manual background requests and restricts automatic execution to the visible app.

This update compiled in debug/release and was installed with replacement install. No new tests were added or run for this update; the recovery/provenance test results below describe the preceding checkpoint build. Batch execution, foreground pause/resume and charging behavior require the manual checks. Raw output/provenance and participant amendments remain separate; batch generation never writes a participant description or changes a decision.

The three preparation preferences now live in **Review Recordings > Settings > AI descriptions**. Settings uses labeled, whole-row switches, a scrollable content area and a fixed Back button. Charging-only remains visible but disabled when background preparation is off. Existing preferences and permission/cancellation behavior are preserved; changes save immediately.

Participant review supports optional WorkManager foreground-worker preparation with a Cancel notification and brief readiness notification. Review Recordings contains background preparation (off by default) and charging-only (on by default) preferences. Background work can continue after leaving review and with the screen off; charging and battery constraints can defer it. Foreground generation remains available when background preparation is off.

Complete foreground/background engine cycles share one execution gate. Recording reserves it, cancels inference and waits for native cleanup before arming capture; interrupted background work restarts later. Durable request IDs deduplicate committed VLM results, and cancelled partial text is never saved. No background output is automatically presented, accepted or saved as a participant description. Android 16 scheduling quotas can delay work. See [background preparation report](PHASE2_BACKGROUND_PREPARATION_REPORT.md) for implementation limits and manual checks. The foreground-only lifecycle description below records the initial Checkpoint 7 behavior, superseded by this optional mode.

## Checkpoint 7 participant review

Participant review now explicitly selects `QWEN_PARTICIPANT` in debug and release builds. This uses the Checkpoint 6 real engine and selected defaults below; the factory's simulation option remains for isolated workflow checks. No model is loaded just by constructing the review ViewModel. Manual Generate in Add/Edit Description and the optional default-off automatic preparation preference both use real inference. Previously saved fake runs remain labelled as simulations rather than real observations.

The review screen stays awake during foreground generation and cancels when it closes, capture becomes busy, or the app leaves the foreground. Engine load/generate/unload cycles are serialized across cancellation and subsequent requests. Model verification failures have readable messages; reviewing and writing remain available. Full restart/error/deletion recovery is Checkpoint 8 work.

The existing full-screen description editor retains pinned Use suggestion, Reject suggestion and Back controls. Use copies a draft; Save creates a separate participant amendment referencing the immutable VLM run. Reject does not replace the current description and permits explicit regeneration. Only short status messages are live regions; generated text is read on demand, never automatically spoken. See [Checkpoint 7 report](PHASE2_CHECKPOINT7_REPORT.md) for the required manual TalkBack sequence.

## Checkpoint 6 real inference defaults

Real saved-video inference is implemented behind `VlmRuntimeMode.QWEN_ENGINEERING` (debug only). Participant review still uses SIMULATED until Checkpoint 7 approval. Construction and app startup do not load the model. Raw generated descriptions are optional ecological context suggestions; participant amendments remain separate records, and demographics/clinical profiles never enter the prompt.

Selected provisional policy: **3 chronological frames at 15/50/85% of playable duration, maximum long edge 1024, no upscaling**, exposed as `QwenPhase2Defaults.sampling`. The general Checkpoint 5 sampler still defaults to five frames for standalone sampling. Both policies persist their exact requested times and dimensions; actual decoded timestamps remain null.

Generation uses the exact `ecological_scene_description_v2` system/user prompt, ChatML roles, independent image markers, CPU only, greedy sampling, maximum 384 output tokens, context 8192, batch/ubatch 512 and four language/projector threads. Seed is null for greedy sampling. Both app variants now build native CMake Release (`-O3 -DNDEBUG`); Checkpoint 4 build settings below describe that earlier checkpoint. Official model hashes and pinned runtime commit are unchanged.

All nine Pixel 9 runs (three saved clips, each with 1/3/5 frames) completed, persisted raw output/provenance and reopened the engineering database successfully. No OOM or process death was observed. Three frames took 600.3–788.5 seconds of inference; five took 1038.6–1298.9 seconds. All configurations used roughly 4.3–4.4 GiB peak RSS. Three frames retain temporal context with substantially less latency than five; this is a provisional choice based on short workspace clips, not a navigation accuracy evaluation. Five frames produced one repetitive, token-limited output. The selected three-frame runs ended normally.

Prompt compliance is imperfect: observed output includes inferred intention and fine-text claims needing manual validation. Raw output is preserved unchanged; a successful runtime/persistence test does not certify accuracy. In-flight vision encoding must finish before cancellation cleanup; CPU language evaluation supports an abort callback. Explicit cancellation and subsequent reload passed on-device. Participant lifecycle integration is Checkpoint 7 work.

See [Checkpoint 6 report](PHASE2_CHECKPOINT6_REPORT.md) for measurements, verification and manual checks, and [native README](../vlm-native/README.md) for engineering commands.

## Checkpoint 5 saved-video sampling

`VlmFrameSampler` extracts five deterministic requested times at 10/30/50/70/90% of playable MP4 duration. `FrameSamplingConfig` holds the explicit policy, max long edge 1024, no upscaling. Android decoding runs on IO, uses OPTION_CLOSEST, retains aspect ratio and applies container rotation once. Actual decoded timestamps are unavailable and recorded as null. Source/inference dimensions, rotation, policy and requested timestamps are available as JSON for Checkpoint 6 persistence. Successful batches own their Bitmaps and must be closed; failed/cancelled batches recycle partial output and release the retriever. No VLM inference or participant UI hookup was added.

Three actual glasses clips were sampled into a local engineering gallery; synthetic decoder tests also cover a 60-second clip, short/portrait/rotated inputs and failure/resource cleanup. See [Checkpoint 5 report](PHASE2_CHECKPOINT5_REPORT.md) for results and manual inspection.

## Checkpoint 4 JNI configuration

The app builds `libecocapture_qwen3vl.so` from the same pinned llama.cpp/libmtmd commit using NDK r28c and CMake 3.29.2. CMake rejects a dirty or different source checkout. ARM64 CPU only; all GPU backends remain disabled. Debug uses CMake Debug; release uses RelWithDebInfo. Static libc++, no CLI/server targets, 16 KiB ELF segment alignment verified. No GGUF files are packaged in the APK.

`VlmModelManager` explicitly discovers `Context.filesDir/models/qwen3vl`, checks the official byte lengths and streams SHA-256 for both files. It exposes NOT_INSTALLED, VERIFYING, READY, HASH_MISMATCH, INCOMPLETE and ERROR. Verified file metadata is checked again before native loading. The engineering provisioning script publishes verified app-owned temporary files by rename; do not replace files during native use.

`NativeQwen3VlBridge` lazily loads the JNI library on an explicit call, validates runtime provenance and serializes process-wide ownership. Native model, language context and projector are private RAII resources, freed in reverse order on unload or partial failure. Native settings: context 8192; batch/ubatch 512; generation/batch/projector threads 4; model MMAP; zero GPU layers; no K/Q/V or operation offload; projector warmup disabled. Returned failures distinguish verification, language model, context, projector, allocation, cancellation, linkage and runtime mismatch.

Native cancellation uses progress callbacks; coroutine cancellation cleans up after the blocking JNI call returns. Unload cleanup cannot be cancelled. This checkpoint does not validate generation cancellation, frame encoding, or lifecycle integration. OS process termination and upstream fatal assertions cannot be converted into Kotlin failure values.

Participant UI remains on FakeVlmEngine. No real model is loaded at startup or from the automatic preparation toggle. Model availability does not affect Approve, Review Later or Delete. Prompt version remains `ecological_scene_description_v2`, with immutable raw output/provenance and separate participant amendments in the existing workflow.

Build and device-run instructions: [native README](../vlm-native/README.md). Results and manual verification: [Checkpoint 4 report](PHASE2_CHECKPOINT4_REPORT.md).

Status: Checkpoint 0 established standalone inference on the Pixel 9. Checkpoint 4 adds an isolated JNI model lifecycle component; participant screens still use FakeVlmEngine. The configuration and measurements below are historical Checkpoint 0 results unless explicitly labelled otherwise. See [Checkpoint 4 report](PHASE2_CHECKPOINT4_REPORT.md) for in-process verification.

## Runtime and build configuration

- Upstream: [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp).
- Verified and pinned commit: `0c1e57098bba43ac29e6e3b677cdceebdd22334f`. Preparation checks out this SHA with detached HEAD; the build rejects any other SHA. Production JNI integration will use this verified source baseline, subject to its own later tests.
- Runtime: `libllama` + `libmtmd`, through the standalone `llama-mtmd-cli` engineering harness.
- Android NDK: r28c, `28.2.13676358`.
- CMake: `3.29.2`; Ninja: `1.12.0`.
- ABI: `arm64-v8a`; Android API: `31`; C++ runtime: static.
- Release build, static libraries, CPU backend.
- `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, `GGML_CCACHE=OFF`, `GGML_VULKAN=OFF`, `GGML_CUDA=OFF`, `GGML_METAL=OFF`. Generic ARM64 CPU baseline; no Pixel-specific architecture flags or KleidiAI acceleration.
- `LLAMA_OPENSSL=OFF`, `LLAMA_SUBPROCESS=OFF`, `MTMD_VIDEO=OFF`.
- Test/example/server/unified-app targets disabled; tools enabled; target `llama-mtmd-cli`.
- Complete executable build flags: [build.ps1](../tools/vlm-feasibility/build.ps1).
- Cross-compilation follows the [upstream Android instructions](https://github.com/ggml-org/llama.cpp/blob/0c1e57098bba43ac29e6e3b677cdceebdd22334f/docs/android.md).

## Exact model bundle

Source: [official Qwen GGUF repository](https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/tree/1cd86afb9a95c410a6038ab3b40d8b578c892266), revision `1cd86afb9a95c410a6038ab3b40d8b578c892266`.

| File | Bytes | Expected SHA-256 |
| --- | ---: | --- |
| `Qwen3VL-4B-Instruct-Q4_K_M.gguf` | 2,497,281,664 | `66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a` |
| `mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf` | 453,974,304 | `30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d` |

Total: 2,951,255,968 bytes. These files remain outside the APK. Checkpoint 4 verifies app-owned files under `files/models/qwen3vl`; engineering provisioning is explicit.

## Historical Checkpoint 0 feasibility configuration

- Pixel 9, serial `49280DLAQ0020M`, Android 16, build `CP1A.260505.005`. Reported RAM: 11,848,700 KiB.
- Context: 8192 tokens; CPU generation/batch threads: 4.
- GPU layers: 0; projector offload disabled; explicit model warmup disabled. File caches were not flushed; these measurements do not establish cold-storage loading times.
- Temperature: 0 (greedy); seed: 0; maximum output: 192 tokens.
- Prompt: the historical, unversioned engineering user prompt below, with no participant/profile data. The harness now uses v2; do not relabel these earlier measurements as v2 results.
- Input: upstream newspaper image, 640 by 480, repeated 1/3/5 times in successive runs. No saved-video sampling in Checkpoint 0.
- Later initial sampling candidate remains 5 frames at 10/30/50/70/90%, maximum long edge 1024, aspect ratio preserved, no upscaling. No production default has been selected by feasibility results yet.

Historical engineering prompt, retained for provenance:

```text
Describe only visually observable information in these images in one concise plain-text paragraph of approximately 50 to 120 words. Prioritize spatial layout, obstacles, landmarks, visible signage or text, route options and visible actions when present. State uncertainty when something cannot be determined. Do not infer diagnosis, disability, demographics, identity or intention. Do not claim a visible condition is the participant's actual problem. These are engineering test images.
```

## Measurements

One run at each input count, in order 1, 3, 5, without a controlled cooling interval. All exits were 0; no OOM/native crash was observed. `pidof llama-mtmd-cli` found no process after completion.

| Inputs | Startup ready (s) | End-to-end wall time (s) | Sampled maximum RSS (KiB) | Observed VmHWM (KiB / GiB) | Thermal status before/after | HAL skin temperature before/after (°C) |
| ---: | ---: | ---: | ---: | --- | --- | --- |
| 1 | 3.915 | 71 | 4,266,600 | 4,266,600 / 4.07 | 0 / 0 | 28.62 / 34.27 |
| 3 | 2.290 | 183 | 4,323,012 | 4,323,132 / 4.12 | 0 / 0 | 35.22 / 37.26 |
| 5 | 2.382 | 380 | 3,994,636 | 4,079,448 / 3.89 | 0 / 0 | 36.61 / 38.30 |

Startup ready is elapsed CLI time at the log immediately after the context constructor: language model, language context, vision projector and chat template have initialized. This includes startup overhead and is not an isolated file-read timer. End-to-end wall time includes loading, preprocessing, vision encoding, prefill, generation and teardown, with one-second precision.

Vision encoding totals were 10.450 s (1 input), 36.998 s (3 inputs), and 93.787 s (5 inputs). The five-input log confirms five separate 300-token image batches, context 8192, CPU model mapping 2375.91 MiB, F16 KV cache 1152 MiB, and language compute buffer 321.75 MiB.

Native performance counters were exposed only for the five-input run by raising engineering verbosity from the default 3 to 4:

- Raw upstream `load time`: 340,824.86 ms.
- Raw upstream `prompt eval time`: 338,974.94 ms / 1606 tokens, 4.74 tokens/s.
- Native generation evaluation: 37,380.24 ms / 116 evaluation runs, 3.10 tokens/s.
- Native total: 378,260.25 ms / 1722 prompt + evaluation tokens.

The upstream load counter overlaps prompt processing in this CLI and is **not** the physical model load duration; initialization was already complete at 2.382 s. Native prompt timing spans multimodal work, so do not add vision encoding to it as if those were disjoint durations. Native phase counters for the 1/3-input runs were suppressed by logging verbosity and are unavailable; their wall/startup/vision timings are recorded above.

Raw results: `tools/vlm-feasibility/artifacts/results/{1,3,5}-images/` (ignored engineering artifacts). Use [summarize.py](../tools/vlm-feasibility/summarize.py) to inspect numeric summaries. The CLI binary SHA-256 for these runs is `2c2051308e624114e19e6ebe33ff2821bbba6df2b21286af338c5340632e46d6`.

## Known limitations

- This establishes standalone CLI feasibility on one phone, with one run per configuration. In-process JNI behavior, repeated native load/unload, app coexistence, cancellation and production memory safety remain to be tested in their assigned checkpoints.
- Repeating a 640 by 480 fixture characterizes resource scaling, not temporal coverage, scene annotation usefulness, or memory use with 1024-pixel frames. Final production frame defaults remain undecided.
- All outputs recognized the newspaper, headline and layout, but all misread the date as July 16 instead of July 21; the 1/5-input outputs also misread 10 cents as 15 cents. This is output sanity, not accuracy acceptance.
- Latency is substantial: 71/183/380 seconds. Runs had different thermal/cache conditions; slower later runs cannot be attributed to a particular cause from these measurements. No reported thermal-status escalation occurred, but CPU-frequency throttling was not measured.
- RSS/`VmHWM` were sampled once per second and may miss a final short-lived peak. HAL temperatures are before/after snapshots, not a continuously measured temperature maximum. Memory after exit is system-wide, not a native leak test.
- Upstream warns about a GGUF control-token type (corrected internally), the absent GPU (expected for this CPU build), and recommended image-token minimums for grounding tasks. No grounding/token-policy change was made.
- Initial multi-image invocations repeated `--image`; a generic deprecation warning appeared at verbosity 4. Source inspection confirms the handler appends each file, and separate image-encoding logs confirm all 3/5 inputs were processed. The saved harness now uses the recommended comma-separated paths; this equivalent argument spelling has been inspected and syntax-checked, without repeating the long benchmark.

Full checkpoint commands, file inventory, verification and approval boundary: [PHASE2_CHECKPOINT0_REPORT.md](PHASE2_CHECKPOINT0_REPORT.md).

## Prompt v2 update requested after Checkpoint 0

The VLM is an optional **coarse ecological scene-description assistant**, not OCR or ground truth. The exact system/frame prompts supplied by the user are in `app/src/main/java/com/rchia/ecocapture/phase0/vlm/VlmPrompt.kt`, with version `ecological_scene_description_v2`. They prioritize environmental/spatial context, obstacles/hazards, landmarks, entrances/exits, route options, objects and visible actions. Unclear text, numbers, objects or spatial details must remain explicitly uncertain. Identity, demographics, diagnosis/disability, intentions, emotions, participant problems and solution success must not be inferred.

The new five-section plain-text format uses a maximum output allowance of 384 tokens, replacing 192 for new v2 harness runs. Temperature 0, context 8192, four CPU threads, model/projector hashes and pinned runtime are unchanged. Model generation is still optional; no participant-facing generation UI or production native engine has been enabled.

### Output preservation and tests

There was no app prompt or VLM storage layer at Checkpoint 0. To exercise the requested persisted-uncertainty/amendment tests, this scoped update adds `VlmRunEntity`/`VlmRunDao`, `AnnotationEntity`/`AnnotationDao`, `AnnotationSource`, and `AnnotationRepository.saveAmendment`. `EcologicalCaptureDatabase` moves from version 1 to 2 through an explicit migration adding only the two new tables. Existing clip columns and state are preserved. This is a subset of Checkpoint 1's foundation, not completion of that checkpoint; `VlmEngine`, `FakeVlmEngine` and the full participant annotation workflow remain outstanding.

VLM output/provenance is insert-only through the DAO, with ABORT on duplicate run IDs, so an amendment cannot replace an existing run. `rawOutput` is retained verbatim, including uncertainty and whitespace. Participant endorsements/edits create separate `PARTICIPANT_AMENDMENT` rows with an explicit `parentVlmRunId`; revisions supersede the current amendment without deleting prior text. These operations never update clip decisions or mark a generated result presented. No demographic/clinical profile data is accepted by the prompt constants or sent to the engineering model request.

`VlmPromptTest` adds five JVM contract tests: no forced transcription of unclear small text, legibility rules for dates/prices/numbers, prohibited participant-problem/solution inference, required coarse spatial/action content, and explicit v2 uncertainty rules. `VlmOutputPersistenceTest` adds four real Room tests: uncertain output/provenance survives close/reopen verbatim; unchanged and edited amendments remain separate with history; duplicate output replacement fails and approval remains independent; version 1 clip fields plus approved/deferred/deleted states survive migration.

Validation commands (Android Studio Java and the previously cached Gradle 8.14.1):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$phase2Gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $phase2Gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest --offline --console=plain
& $phase2Gradle :app:connectedDebugAndroidTest --offline --console=plain
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 1
python ./tools/vlm-feasibility/summarize.py
```

Results: builds passed; all 28 JVM tests passed (23 existing + 5 new); all 7 Pixel 9 instrumentation tests passed (3 existing + 4 new). The second connected run rechecked the final fixture type-warning fix and passed. Test methods use separate disposable databases. **Correction:** the earlier statement that participant application data was retained was not established. Checkpoint 1 later identified that Gradle's connected-test cleanup uninstalls the target package, potentially removing its private recordings/database. Future device tests must use the direct runner described below. Physical capture, playback and TalkBack were not manually exercised in this update.

Initial validation issues were resolved: restricted Gradle-cache execution failed before compilation, then the existing cache worked with sandbox escalation; two prompt assertions originally assumed semicolons where the supplied prompt used full stops; the pinned multimodal CLI does not expose `--system-prompt-file`, so the harness reads the file into its supported `--system-prompt` argument instead. The failed CLI run's logs were retained separately.

### Real v2 smoke check and remaining model limitation

Run ID: `run-1-v2-1454d3f2e2db409c808262305c7d5ca3`, one upstream newspaper fixture. Exit 0, 146 seconds, observed VmHWM 4,276,660 KiB (4.08 GiB). Thermal status changed 0 → 1; HAL skin temperature changed 30.67 → 38.01°C. This run overlapped device instrumentation and is not a controlled benchmark or directly comparable to the earlier timings.

All five requested sections appeared, and the date was correctly transcribed as July 21. **The model still incorrectly reported “15 CENTS” instead of 10 cents, misread the byline, and asserted an indoor setting not established by the image.** Its uncertainty section did not qualify those incorrect details. This smoke check therefore does **not** pass fine-detail/uncertainty compliance; passing contract tests does not prove that Qwen follows the instructions. Coarse ecological accuracy still needs evaluation on saved glasses clips. The raw description was not corrected or sanitized to disguise these errors.

The fixture is a newspaper rather than an ecological sequence and cannot establish movement across frames. It also cannot justify relying on claimed text legibility. Keep fine details and inferred context untrusted; participant review and separately stored amendments remain necessary.

New engineering runs have unique result directories, exact exported prompts, prompt hashes, version, model/projector hashes, runtime commit and frame/generation configuration in `request.json`. Raw stdout/stderr remain separate and unfiltered. This run's files are under `tools/vlm-feasibility/artifacts/results/v2/run-1-v2-1454d3f2e2db409c808262305c7d5ca3/1-images/`. Historical Checkpoint 0 outputs remain untouched.

## Checkpoint 1 foundation update

The remaining engine/repository foundation has now been implemented and tested: `VlmEngine`, typed request/result/model information, `FakeVlmEngine`, participant add/revise/history/current-query operations, and VLM result persistence/history. No participant-facing annotation or VLM UI has been enabled. See [PHASE2_CHECKPOINT1_REPORT.md](PHASE2_CHECKPOINT1_REPORT.md).

Device-test retention incident: Gradle UTP's installer log explicitly specifies `uninstall_after_test: true` for the target APK and records uninstalling `com.rchia.ecocapture.phase0`. After the connected tests the package was absent. Existing app-private media/database preservation cannot be claimed, and potential recovery depends on backups. The debug APK was reinstalled; reinstalling does not recover removed data.

Future research-phone instrumentation uses `tools/run-room-tests.ps1`: `adb install -r` for both APKs, then direct `am instrument -w`, with no uninstall or app-data clear. All 12 tests passed using this runner, and a temporary app-private sentinel survived a subsequent replacement-install/test cycle. The sentinel created for this check was then removed. This establishes retention for the replacement-install path, not recovery of prior data. Pending participant-device/manual acceptance checks remain explicitly pending.
