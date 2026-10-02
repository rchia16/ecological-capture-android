# Phase 2 Checkpoint 6: real Qwen3-VL engineering inference

Implementation and device comparison completed on 2026-10-02. Participant review stays on simulation until Checkpoint 7 approval.

## Implementation

`Qwen3VlEngine` now connects saved MP4 sampling, chronological RGB image ingestion, separate fixed system/user prompt messages, local CPU generation, and `VlmResult.Success`. `VlmEngineFactory` exposes SIMULATED and debug-only QWEN_ENGINEERING modes. `ClipReviewViewModel` uses the factory's simulated default. Construction does not load models, hash files or perform inference.

Native JNI converts software RGBA Bitmaps into owned RGB copies, respects row stride, and immediately releases Java pixels after each successful ingestion. It applies the ChatML formatter to distinct roles, inserts media markers before the exact user prompt, tokenizes with libmtmd, encodes image chunks, evaluates their Qwen3-VL embeddings/M-RoPE/deepstack inputs through upstream helpers, then samples greedily. Input frames are independent images in chronological order, not temporally merged video chunks. Context capacity is checked before evaluation; incomplete native operations are cleared in finally.

The model gets no participant demographics, clinical profiles, annotation history, clip ID, personal problem or outcome. The engine rejects a substituted prompt. The fixed prompt remains `ecological_scene_description_v2`. Generated output is a coarse visual-context suggestion and is not treated as OCR or ground truth.

Raw output and description are the identical unedited decoded text. Native returns UTF-8 byte arrays instead of modified UTF-8 JNI strings. Successful provenance records model ID, quantization, both file hashes, upstream commit, prompt version, exact system/user instructions, formatted prompt SHA-256, requested frame times, nullable actual times, dimensions, extraction duration, load/hash duration, CPU context/batch/thread settings, greedy sampler, output limit and native phase timings/token counts/stop reason. Greedy sampling has no random seed, so seed is null.

Generation uses a bounded cancellation watcher while JNI blocks. CPU language evaluation has an abort callback; cancellation is checked between image encoding calls and decoded chunks/tokens. An in-flight vision encoding call must finish before cleanup. Partial/cancelled work is not returned for persistence. Cleanup and unload are non-cancellable. OS process termination and upstream fatal assertions cannot universally be converted into typed Kotlin failures.

## Native build

Pinned llama.cpp/libmtmd commit and official model hashes remain unchanged. ARM64 CPU only, NDK r28c, CMake 3.29.2, static libc++, no GPU backend and 16 KiB ELF alignment. Checkpoint 6 aligns both app build variants with native CMake Release (`-O3 -DNDEBUG`), matching the original CLI feasibility build. The earlier Checkpoint 4 debug load-only observations remain historical. Native build type is included in new inference metrics.

## Engineering tests and artifacts

- `QwenGenerationConfigTest`: bounded generation parameters.
- `Qwen3VlEngineTest`: successful-load guard, exact uncertainty/Unicode preservation, source bytes and complete provenance, rejection of changed prompts, failure/blank-output handling, frame cleanup, cancellation propagation and default simulation.
- `Checkpoint6InferenceTest`: opt-in real generation on a named finalized glasses recording, source hashes before/after, memory/thermal measurements, actual `VlmRunRepository` persistence and exact equality after database reopen. Results go into `checkpoint6-engineering.db`, separate from the participant database. Nothing is marked presented.
- `Checkpoint6CancellationTest`: opt-in native generation cancellation, no successful result, unload and reload.
- Debug-only non-exported `VlmEngineeringActivity`: foreground CPU scheduling and screen-on conditions for comparison; no participant generation controls or capture actions.
- `run-checkpoint6-comparison.ps1`: replacement install option, direct instrumentation, raw UTF-8 export and thermal-service snapshots.
- `summarize-checkpoint6.py`: local comparison table and unmodified output text.

Artifacts are local and ignored under `tools/vlm-feasibility/artifacts/results/checkpoint6`. No model files enter the APK. No model output or recording data are published.

## Preliminary diagnostic run (not the final comparison)

Before native Release alignment and the foreground harness, one 1024-pixel frame completed and persisted unchanged. Load + verification: 8.118 s; extraction: 0.500 s; inference: 445.672 s; total: 456.012 s. Native phase timings: vision 34.065 s, prefill 150.529 s, generation 260.096 s; 1,038 prompt tokens and 243 generated tokens, stop EOG. Peak RSS: 4,465,400 KiB (4.26 GiB); RSS after unload: 132,384 KiB. Thermal status stayed 0. File: `first-run-background.json` and matching raw text file.

The output retained uncertainty about small screen text and did not give exact dates/prices/numbers or a personal problem/outcome. It nevertheless described movement and interaction from one still and claimed a monitor logo. Such claims require checking against the images; prompt adherence is not guaranteed. Output was retained without silent correction.

## Final comparison and selected defaults

Pixel 9, Android 16; native Release; official 4B Q4_K_M language model and Q8_0 projector. Three valid saved MP4s, all nine 1/3/5-frame combinations. One frame uses 50%; three use 15/50/85%; five use 10/30/50/70/90%. All use maximum long edge 1024, aspect ratio retained and no upscaling. All runs passed generation, source hash preservation, raw output/provenance persistence and database reopen checks. No OOM or process death was observed.

| Recording suffix | Frames | Load + hashes (s) | Extraction (s) | Inference (s) | Peak RSS (GiB) | Stop |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 160008_634 | 1 | 3.63 | 0.16 | 479.8 | 4.40 | EOG |
| 160008_634 | 3 | 5.46 | 0.89 | 600.3 | 4.34 | EOG |
| 160008_634 | 5 | 8.41 | 1.47 | 1218.0 | 4.45 | EOG |
| 160118_201 | 1 | 5.24 | 0.63 | 469.3 | 4.43 | EOG |
| 160118_201 | 3 | 5.29 | 0.94 | 788.5 | 4.42 | EOG |
| 160118_201 | 5 | 5.01 | 1.92 | 1298.9 | 4.28 | 384-token limit |
| 183137_611 | 1 | 4.89 | 0.46 | 426.7 | 4.43 | EOG |
| 183137_611 | 3 | 4.44 | 0.81 | 736.1 | 4.43 | EOG |
| 183137_611 | 5 | 4.87 | 1.41 | 1038.6 | 4.42 | EOG |

Thermal status started at 0 (NONE), rose to 1 (LIGHT) on the first run and remained 1 for later measurements. Before/after thermal-service snapshots are retained alongside JSON. These are individual sequential runs with uncontrolled file caches and thermal conditions, not repeatable cold-load timing estimates. Peak RSS includes model loading; inference includes ingestion, vision, prefill and decoding but excludes load/extraction. Exact phase timings, tokens, wall time and memory after unload are in each JSON.

Selected provisional Phase 2 default: **3 frames at 15/50/85%, 1024 maximum edge, 384 output tokens, greedy CPU generation**. All configurations were stable in this sample; three frames provide temporal observations, while five roughly doubled latency in two clips and introduced one repetitive truncated output. One frame cannot establish actions across time. Three frames still take 10–13 minutes here. The clips are short indoor workspace recordings; broader scene coverage and participant suitability remain unproven. `QwenPhase2Defaults` defines the engine policy without changing the general five-frame sampler contract.

## Observed description limitations

Coarse descriptions of desks, monitors, phones, containers and their relative positions remain present. Two three-frame outputs explicitly decline to read small screen text. Uncertainty is retained verbatim in the reopened database. However:

- The first three-frame output says the hand is "likely initiating or managing a recording", an inferred intention despite the system instruction.
- The first clip's outputs include fine-text claims (including a box number and monitor text/logo) needing visual verification. They must not be accepted as OCR results or ground truth.
- Single-frame outputs describe movement/interactions that a still alone cannot establish.
- The second five-frame output repeats uncertainty about illegible text until the token limit and ends mid-sentence. Stop reason and raw output are retained.
- A shifting camera perspective is sometimes attributed to a person holding a phone; the glasses viewpoint does not establish that causal account.

No silent correction or deletion of these claims was performed. The fixed supplied v2 prompt remains unchanged. Runtime success and persisted equality do not mean semantic accuracy or full prompt compliance. The model remains an optional coarse scene-description assistant.

## Verification

Debug/release builds and 52 JVM tests per variant pass. Ordinary device suite: 54 passed, four opt-in tests skipped (58 reported by JUnit). Nine opt-in real inference cases passed. Explicit native generation cancellation passed: cancel/join 332 ms in this run, no successful result returned, unload/reload successful. This does not bound cancellation latency during a larger in-flight vision encoding call. Native lifecycle regression passed partial-failure cleanup, two load/unload cycles and HEVC playback after unload (14.551 s). Tests use replacement installs and direct instrumentation; participant app data was retained.

The comparison records live in a separate engineering database and remain unpresented. Participant description revisions and review decisions were not changed. Local artifacts remain ignored; models remain outside APKs.

## What to check before approving Checkpoint 7

1. Open the [comparison gallery](../tools/vlm-feasibility/artifacts/results/checkpoint6/index.html). Read the three-frame descriptions first, then compare with one and five frames. JSON links show full provenance and measurements; raw text is unmodified.
2. Play the three matching recordings in the app. Check coarse layout, relative object positions and visible actions against the video. Use the [Checkpoint 5 frame gallery](../tools/vlm-feasibility/artifacts/results/checkpoint5/index.html) as additional reference; its five requested times differ from the three-frame policy.
3. Pay particular attention to the observed intention and fine-text claims above. Confirm whether these descriptions are useful enough as editable suggestions given their inaccuracies and 10–13 minute latency.
4. Check the existing participant flow: recording/playback, Edit description, use/reject/regenerate simulation, separate saved description, Approve, Review Later and Delete. Participant screens still use simulation; this checkpoint adds real inference to the engineering harness only.

Stop here for approval. Checkpoint 7 will connect the real runtime to participant review and lifecycle handling.
