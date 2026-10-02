# Phase 2 Checkpoint 0 report

Date: 2026-10-01. Status: **passed; stopped for user review**.

The exact official Qwen3-VL-4B-Instruct Q4_K_M language model and Q8_0 projector ran locally on the Pixel 9 through upstream `llama-mtmd-cli`/libmtmd, outside the production application. Both files passed SHA-256 verification on the workstation and again after transfer to the phone. The verified llama.cpp SHA is pinned in the harness.

The implementation plan and Phase 1 completion report were reviewed before work began. No clarification was needed for Checkpoint 0. Phase 1's outstanding physical glasses and complete TalkBack acceptance tests remain outstanding; this spike does not claim to complete them.

## Device results

| Image inputs | Wall time | Observed memory high-water mark | Exit | OOM/native crash observed | Thermal status before/after |
| ---: | ---: | ---: | ---: | --- | --- |
| 1 | 71 s | 4.07 GiB | 0 | No | 0 / 0 |
| 3 | 183 s | 4.12 GiB | 0 | No | 0 / 0 |
| 5 | 380 s | 3.89 GiB | 0 | No | 0 / 0 |

All runs generated a complete plain-text paragraph and exited. A final process check found no remaining `llama-mtmd-cli`. Model/projector initialization completed by 3.915/2.290/2.382 seconds respectively. Five-input generation evaluation took 37.380 seconds at 3.10 tokens/s; native load/prompt counters overlap multimodal processing and are described carefully in the runtime record.

Manual engineering verification compared the generated text with the upstream newspaper image: the newspaper name, headline and page layout were recognized. All runs incorrectly read July 21 as July 16; the 1/5-input runs incorrectly read 10 cents as 15 cents. These tests establish functioning local vision/text inference, with visible accuracy limitations.

The upstream 640 by 480 fixture was repeated for the 3/5-input tests. Separate encoding events confirm all inputs were consumed. Actual saved HEVC clips, temporal coverage, 1024-pixel input memory, TalkBack, production lifecycle and JNI memory behavior were not tested in this checkpoint.

Skin temperature increased from 28.62 to 34.27°C in the one-input run, 35.22 to 37.26°C in the three-input run, and 36.61 to 38.30°C in the five-input run. Runs were sequential without controlled cooling. No reported thermal-status escalation occurred; CPU-frequency throttling was not measured. Latency and factual text-reading errors need further characterization on actual clips at Checkpoint 6.

## Files added

| File | Purpose |
| --- | --- |
| `tools/vlm-feasibility/.gitignore` | Exclude large models, toolchains, source checkout, binaries and raw results from Git. |
| `tools/vlm-feasibility/README.md` | Reproduction commands and engineering-harness scope. |
| `tools/vlm-feasibility/prepare.ps1` | Official downloads, exact model/NDK checksum verification, local NDK extraction, detached runtime checkout. |
| `tools/vlm-feasibility/build.ps1` | Fixed-commit CPU-only Android ARM64 CLI build. |
| `tools/vlm-feasibility/device.ps1` | Model provisioning/hash verification, explicit device selection, run and result collection. |
| `tools/vlm-feasibility/run-device.sh` | Local inference with 1/3/5 inputs, RSS/high-water sampling, thermal/memory snapshots and exit/wall-time recording. |
| `tools/vlm-feasibility/summarize.py` | Numeric measurements from collected results, without printing generated text. |
| `documentation/VLM_RUNTIME.md` | Exact hashes/sizes, pinned runtime, toolchain/build flags, settings, measurements and limitations. |
| `documentation/PHASE2_CHECKPOINT0_REPORT.md` | This checkpoint report. |

No existing production source, app build configuration, dependency version, database/schema, package identity or participant-facing UI was modified. No APK was installed or application data cleared. Existing untracked user files were left untouched. Model provisioning for this spike used `/data/local/tmp/ecocapture-qwen3vl-spike/`; production app-owned model storage belongs to Checkpoint 4.

## Commands and outcomes

Workstation commands below were run from the project root in PowerShell. Official downloads required execution with network access; downloaded files remained in the workspace's ignored `artifacts/` directory. The model URLs used the fixed official Hugging Face revision `1cd86afb9a95c410a6038ab3b40d8b578c892266`.

```powershell
git ls-remote https://github.com/ggml-org/llama.cpp.git HEAD
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git tools/vlm-feasibility/artifacts/llama.cpp
curl.exe --fail --silent --show-error -L https://dl.google.com/android/repository/repository2-1.xml -o tools/vlm-feasibility/artifacts/android-repository.xml
curl.exe --fail --silent --show-error -L 'https://huggingface.co/api/models/Qwen/Qwen3-VL-4B-Instruct-GGUF?blobs=true' -o tools/vlm-feasibility/artifacts/model-repository.json
curl.exe --fail --silent --show-error -L --retry 3 --connect-timeout 30 -o tools/vlm-feasibility/artifacts/android-ndk-r28c-windows.zip https://dl.google.com/android/repository/android-ndk-r28c-windows.zip
curl.exe --fail --silent --show-error -L --retry 3 --connect-timeout 30 -o tools/vlm-feasibility/artifacts/Qwen3VL-4B-Instruct-Q4_K_M.gguf https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/1cd86afb9a95c410a6038ab3b40d8b578c892266/Qwen3VL-4B-Instruct-Q4_K_M.gguf
curl.exe --fail --silent --show-error -L --retry 3 --connect-timeout 30 -o tools/vlm-feasibility/artifacts/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/1cd86afb9a95c410a6038ab3b40d8b578c892266/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf
& ./tools/vlm-feasibility/prepare.ps1 -SkipDownloads
& ./tools/vlm-feasibility/build.ps1 -Ninja (Get-Command ninja).Source
```

Outcomes: all official downloads completed; exact model hashes and Google's NDK archive checksum passed; runtime checkout detached at the verified SHA; all 188 native build steps completed successfully. The first native build attempt encountered a permission failure from the host compiler cache; disabling `GGML_CCACHE` resolved it. A scoped Git trust exception allows CMake to record the actual commit under the sandbox without changing global Git configuration. An initial attempt to redirect build stderr also encountered PowerShell's native-stderr/Stop behavior; the successful build ran directly.

Device preparation and actual tests:

```powershell
$phase2Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe'
& $phase2Adb -s 49280DLAQ0020M devices -l
& $phase2Adb -s 49280DLAQ0020M shell 'mkdir -p /data/local/tmp/ecocapture-qwen3vl-spike'
& $phase2Adb -s 49280DLAQ0020M push tools/vlm-feasibility/artifacts/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf /data/local/tmp/ecocapture-qwen3vl-spike/
& $phase2Adb -s 49280DLAQ0020M push tools/vlm-feasibility/artifacts/Qwen3VL-4B-Instruct-Q4_K_M.gguf /data/local/tmp/ecocapture-qwen3vl-spike/
& $phase2Adb -s 49280DLAQ0020M push tools/vlm-feasibility/artifacts/llama.cpp/tools/mtmd/test-1.jpeg /data/local/tmp/ecocapture-qwen3vl-spike/
& $phase2Adb -s 49280DLAQ0020M shell 'cd /data/local/tmp/ecocapture-qwen3vl-spike && sha256sum *.gguf'
& $phase2Adb -s 49280DLAQ0020M push tools/vlm-feasibility/artifacts/build-android/bin/llama-mtmd-cli /data/local/tmp/ecocapture-qwen3vl-spike/
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 1
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 3
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 5
python ./tools/vlm-feasibility/summarize.py
& $phase2Adb -s 49280DLAQ0020M shell 'pidof llama-mtmd-cli || true'
```

Each `device.ps1` invocation pushes an LF-normalized shell script, runs it, then pulls the eight primary result files. Results are preserved under `tools/vlm-feasibility/artifacts/results/{1,3,5}-images/`. The final checked-in harness uses verbosity 4 and comma-separated image paths. Measured runs 1/3 used default verbosity 3; run 5 used verbosity 4. All three measured runs used repeated image flags, which the pinned handler appends; the final comma-separated spelling resolves the upstream deprecation warning without changing the input list. That spelling was inspected and syntax-checked, not benchmarked again.

PowerShell scripts were parsed with `System.Management.Automation.Language.Parser`; the final shell script was checked with Android `sh -n`. The summary script was executed against actual results. These checks passed. Gradle/app tests and physical Phase 1 regression were not run because this checkpoint builds and tests the standalone native CLI; no app implementation changed.

## Exit criteria and approval boundary

- [x] Exact Q4_K_M language model loads.
- [x] Exact Q8_0 projector loads.
- [x] One-image inference succeeds.
- [x] No OOM/native crash observed.
- [x] Process/model exits cleanly.
- [x] Three/five-image resource use and output sanity characterized.
- [x] Verified runtime SHA pinned in the isolated harness.

Checkpoint 0 is complete. Per Section 24 and the Checkpoint 0 STOP instruction in [the implementation plan](PHASE2_QWEN3VL_CODEX_IMPLEMENTATION_PLAN.md), wait for user approval before Checkpoint 1's Room migration and annotation/provenance foundation. No alternative model, quantization or projector was substituted.
