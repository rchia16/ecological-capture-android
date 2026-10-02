# Checkpoint 0: Android Qwen3-VL feasibility harness

This engineering harness builds upstream `llama-mtmd-cli` outside the production application. Artifacts, models, toolchain archives and raw engineering results are ignored by Git. Nothing here installs an APK, clears app data, reads participant recordings, or changes the capture/review path.

Run from the repository root in PowerShell:

```powershell
& ./tools/vlm-feasibility/prepare.ps1
& ./tools/vlm-feasibility/build.ps1 -Ninja (Get-Command ninja).Source
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Provision -Images 1
```

Only proceed to the following after checking the preceding run for a successful output, clean exit, memory use and thermal state:

```powershell
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 3
& ./tools/vlm-feasibility/device.ps1 -Serial 49280DLAQ0020M -Images 5
```

`prepare.ps1` verifies the exact language/projector hashes and Google's published NDK archive SHA-1 checksum, extracts NDK r28c locally, and checks out the recorded runtime commit. Network access is needed on the engineering workstation to download the official sources. Inference on the phone is local, without a server or network backend.

Device files are staged in `/data/local/tmp/ecocapture-qwen3vl-spike/`, separately from production app storage. `device.ps1 -Provision` verifies both GGUF files again on the phone before inference. Provisioning is a one-time step; later tests reuse the verified files.

Each run uses the upstream `tools/mtmd/test-1.jpeg` newspaper fixture (640 by 480), repeated for multiple inputs. This checks image ingestion, resource scaling and visible-content sanity; it does not measure temporal scene coverage or quality on glasses recordings. The original fixture is smaller than the initial maximum long edge of 1024 pixels and is not upscaled.

The device script samples `/proc/<pid>/status` once per second, including `VmHWM`, and records thermal/memory state before and after. Sampling can miss a final short-lived memory peak. Runtime logs provide finer load/vision/prompt/generation timings where upstream exposes them. Wall time in the shell summary has one-second precision. New results use `artifacts/results/v2/<unique-run-id>/<count>-images/`, preserving prior runs. Historical Checkpoint 0 results remain in `artifacts/results/<count>-images/`.

`export-prompts.py` exports the exact system/user prompts from the single source `VlmPrompt.kt`, identified as `ecological_scene_description_v2`. A per-run `request.json` retains both prompts, their hashes, model/projector hashes, runtime commit, frame fixture information and requested generation settings. Raw stdout is retained without text filtering or uncertainty removal. Prompt messages use separate system/user roles. The v2 output allowance is 384 tokens to accommodate five plain-text sections; the original 192-token benchmark settings and results remain historical.

Summarize collected results without printing generated text:

```powershell
python ./tools/vlm-feasibility/summarize.py
```

The harness enables engineering log verbosity 4 to expose native performance counters. It uses comma-separated image paths, as recommended by the pinned CLI. These settings apply only to this standalone engineering harness.

The runtime commit in the scripts is pinned after successful Checkpoint 0 device inference. See [VLM_RUNTIME.md](../../documentation/VLM_RUNTIME.md) for measured results and limitations, and [PHASE2_CHECKPOINT0_REPORT.md](../../documentation/PHASE2_CHECKPOINT0_REPORT.md) for the checkpoint report. Production JNI integration belongs to later checkpoints.
