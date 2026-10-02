# Qwen3-VL 4B versus 2B benchmark

## Status

**Partial benchmark stopped for user review on 2026-10-02 because the full batch was taking too long.** There are 26 successfully completed runs (18 measured, eight warm-ups) and one user-interrupted run. No runtime crash or OOM was observed in the completed runs. All 26 returned outputs have separate manual image-comparison notes.

The complete measured comparison covers the first clip only: three repetitions of each model/configuration after warm-up. The second clip has one-frame warm-ups for both models; its first measured 4B run was interrupted. The third clip has not been run. These results do not fulfill the originally planned three-clip comparison and must remain preliminary.

At the user's request, the benchmark cap is now **384 output tokens**, configurable with `--max-output-tokens`. No 384-token measurements have been run. They will be saved in a separate `model-comparison-384` folder; existing `model-comparison-128` artifacts retain their original settings and outputs. Production already uses a 384-token cap and is unchanged. Phase 2 integration remains paused.

## Protocol

Completed runs used the same pinned llama.cpp/libmtmd runtime (`0c1e57098bba43ac29e6e3b677cdceebdd22334f`), ARM64 CPU Release build, four threads, context size 8192 and batch size 512 on the connected Pixel 9 running Android 16. Generation was greedy (temperature 0), capped at **128 output tokens**. The system and user prompts were copied directly from `VlmPrompt.definition`, without edits; the version remains `ecological_scene_description_v2`.

| Model | Language model | Projector |
| --- | --- | --- |
| A | Qwen3-VL-4B-Instruct Q4_K_M | Current official 4B Q8_0 mmproj |
| B | Qwen3-VL-2B-Instruct Q4_K_M | mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf |

The official file sizes, SHA-256 hashes and pinned model repository revisions are recorded in the local manifest. Files are verified before loading. The production model selection is unchanged.

The clips are the exact three glasses recordings used in Checkpoint 6, verified by SHA-256:

- `clip_20261001_160008_634.mp4`
- `clip_20261001_160118_201.mp4`
- `clip_20261001_183137_611.mp4`

| Configuration | Sampling times | Maximum long edge |
| --- | --- | --- |
| One frame | 50% | 768 px |
| Three frames | 15%, 50%, 85% | 768 px |
| Three frames | 15%, 50%, 85% | 1024 px |

Aspect ratio is preserved and frames are not upscaled. The original schedule planned one warm-up followed by three measured repetitions for each clip/model/configuration: 18 warm-ups and 54 measured runs. Model order alternates between blocks and repetitions. Each run starts a fresh process, loads the model, generates once and unloads it. A fixed 30-second rest separates runs; this does not guarantee that thermal conditions return to baseline.

The benchmark uses a separate application package and private storage. The production app is temporarily force-stopped to prevent competing preparation jobs; its preferences and pending requests are preserved. No participant recordings, descriptions, annotations or review decisions are written by the benchmark.

## Timing definitions

- **Model load:** native language model, context and projector loading. SHA verification is recorded separately and excluded.
- **Frame extraction:** the app's `MediaMetadataRetriever` sampler, including scaling and sampled-frame creation.
- **Vision encode:** native `mtmd_encode_chunk` calls.
- **Prompt evaluation:** text and image embedding evaluation in the language model, separately from vision encoding.
- **Text generation:** native greedy sampling and token decoding.
- **Total:** native load start through generation return, including extraction, RGB ingestion and orchestration. It excludes process startup, file verification, review-frame PNG export, source rehash and unload.
- **Peak process RSS:** `/proc/self/status` VmHWM, including model loading and the benchmark process. Runs use fresh processes to reset this high-water mark. Sampled bitmaps remain allocated until review PNG export; this small additional allocation is identical for both models.
- **Thermal:** Android PowerManager status before loading and after generation, plus raw thermal-service snapshots. NONE means Android reported no thermal throttling state; it does not establish identical device temperatures.

Every run preserves the complete returned output, exact prompts, model and frame provenance, phase timings, token count, stop reason, memory readings and failure evidence. Token-limit stops are reported separately from runtime failures. Crashes and OOMs must be interpreted from the instrumentation log, persisted stage and process exit information; unavailable timing values are not represented as zero.

## Artifacts

Local recording and model artifacts remain ignored by Git.

- [Complete outputs, sampled frames and manual reviews](../tools/vlm-feasibility/artifacts/results/model-comparison-128/index.html)
- [Timing comparison table](../tools/vlm-feasibility/artifacts/results/model-comparison-128/comparison-table.md)
- [Per-run measurements](../tools/vlm-feasibility/artifacts/results/model-comparison-128/runs.csv)
- [Model and recording manifest](../tools/vlm-feasibility/artifacts/results/model-comparison-128/manifest.json)

## Partial timing comparison

The following medians are from **three measured repetitions per model/configuration on clip `160008_634` only**, with warm-ups and the interrupted run excluded. Times are seconds. Peak RSS is the largest process VmHWM observed among the three measured runs. The linked timing table retains minimum/median/maximum values and CSV contains individual runs.

| Model | Frames / edge | Load | Extraction | Vision | Prompt evaluation | Generation | Total | Peak RSS |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4B | 1 / 768 | 2.96 | 0.42 | 23.37 | 127.00 | 236.99 | 401.76 | 4.23 GiB |
| 2B | 1 / 768 | 1.08 | 0.26 | 22.56 | 47.32 | 107.35 | 179.24 | 2.78 GiB |
| 4B | 3 / 768 | 1.43 | 0.92 | 67.37 | 263.74 | 156.16 | 495.13 | 4.27 GiB |
| 2B | 3 / 768 | 1.33 | 0.94 | 72.59 | 92.89 | 116.61 | 285.53 | 2.72 GiB |
| 4B | 3 / 1024 | 2.26 | 1.04 | 151.43 | 394.39 | 198.89 | 768.97 | 4.25 GiB |
| 2B | 3 / 1024 | 0.99 | 0.72 | 120.01 | 134.29 | 154.76 | 416.47 | 2.82 GiB |

Medians of separate phases need not sum to the median total. All completed runs generated **128 tokens and stopped at the token limit**. Android thermal states were NONE or LIGHT; recent runs remained LIGHT. Total latency varied substantially even with identical outputs and reported thermal states. File caches and device temperatures were not controlled.

## Manual image comparison

All repeated outputs were byte-identical within their clip/model/frame configuration. A manual assessment is reused only after verifying identical raw output, recording hash, frame count, edge size and sampled-frame provenance; the gallery explicitly links that assessment to the reviewed reference run. The paired warm-up PNGs were also verified byte-identical between models. Repetitions therefore add timing observations, not independent scene-quality evidence.

| Clip / configuration | 4B | 2B |
| --- | --- | --- |
| First clip, 1 frame / 768 | Correct object inventory; little relative layout; unsupported hand movement from a still. Truncated during the visible monitor-logo sentence. | Repetitive object inventory; unsupported holding and standing claims. Stops at the actions heading. |
| First clip, 3 frames / 768 | Useful desk objects, clutter and hand/camera changes. Unsupported seated posture and computer interaction; limited wider furniture layout. | Adds caliper and wire container, but infers sitting and omits the main change to a wider chair/desk view. |
| First clip, 3 frames / 1024 | Adds relative container position but interprets an ambiguous small package mark as the exact number `81`. Omits the wider view and overstates absence of windows. | Basic desk inventory, with unsupported seated posture and monitor position; no clear semantic improvement over 768 pixels. |
| Second clip, 1 frame / 768, warm-ups only | Recognizes blinds/window and caliper; calls the desktop a navigable area and infers movement from a still. | Recognizes headphones and wire container; omits prominent caliper and second phone, and claims keyboard interaction without clearly visible contact. |

Both models provide some useful coarse scene information, but every returned output is incomplete and lacks the requested uncertainty section. Some 4B outputs also contain garbled apostrophe characters, retained exactly as returned. No dates, prices, bus/platform numbers, participant identity, diagnosis, personal problem or solution-success claims were found in these returned fragments. The lack of fine-text guesses in truncated 2B fragments does not establish reliable fine-text handling.

**The partial data do not justify replacing the production model.** 2B is faster and uses less RSS in the measured clip, but its unsupported posture/handling claims and limited spatial/action coverage require substantial participant editing. 4B has useful temporal coverage in one configuration and still makes unsupported claims, including an inappropriate exact-number interpretation. Neither output quality nor three-clip robustness has been established.

The gallery records useful spatial information, important omissions, unsupported details, inappropriate fine-text guesses and usefulness as a participant-editable starting point for each completed output. These comparisons are qualitative judgments, not ground-truth annotations or participant outcome claims.

The three clips show a narrow set of indoor workspace scenes. This comparison cannot establish performance in street navigation, public transport, low light or other environments. A single still cannot establish a temporal action. The 128-token limit may truncate requested sections, including uncertainty; that limitation must inform the final recommendation.

## Production preservation and review

The production database before/after comparison passed: all **13 clips (including tombstones), 13 annotations and 17 VLM records** are identical. The complete schema and Room user version are also identical. The VlmEngine interface, prompt version, participant annotation workflow and production model selection are unchanged.

The running benchmark process was stopped and the isolated Android package force-stopped. The interrupted run's stage report and exit/thermal evidence were retained; unavailable generation timings and output are not fabricated. Production scheduling preferences are unchanged. After completing the retention comparison, the normal app was reopened so its existing preparation settings can resume.

The 384-token isolated benchmark APKs compiled successfully, and the updated isolated test APK was installed. The normal debug APK build outputs were then restored without installing a replacement production APK. Python scripts parse successfully. No new inference batch was started.

### What to check

1. Open the results gallery and compare each first-clip configuration with its actual sampled frames.
2. Check the 4B `81` claim, both models' posture/interaction claims, missing wider-view details and truncation.
3. Decide whether the descriptions are useful enough to edit. Treat the second-clip warm-ups as examples only, without drawing three-clip performance conclusions.

The higher cap gives descriptions more space; it does **not** reduce vision/prompt evaluation time and may increase generation time. The 384-token benchmark is configured but has not been started.

Stop for user review after reporting the completed comparison. Do not replace the production model or continue Phase 2 integration automatically.
