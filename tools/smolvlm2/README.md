# SmolVLM2 local engine

Active model: [HuggingFaceTB/SmolVLM2-500M-Video-Instruct](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct), Apache 2.0. Published GGUFs are pinned to `ggml-org/SmolVLM2-500M-Video-Instruct-GGUF` revision `ccd7aae53bcb1997355c2f094959e72b3642ce17`. The Q8_0 language model and Q8_0 projector total **545,593,888 bytes**. File sizes and SHA256 values are in [model-manifest.json](model-manifest.json), and the app checks the same policy before loading.

The existing llama.cpp runtime remains pinned to `0c1e57098bba43ac29e6e3b677cdceebdd22334f`. It already includes SmolVLM2/Idefics3 support. Android still uses four CPU threads, an 8192-token context, greedy sampling and a 384-token output cap. Three sampled images are supplied together, in chronological order, using the model's own conversation format. SmolVLM uses `<|im_start|>` as BOS and `<end_of_utterance>` between turns; it must not be formatted as Qwen ChatML.

## Prepare and build

```powershell
# Prepare runtime dependencies if this is a fresh workstation:
powershell -NoProfile -ExecutionPolicy Bypass -File tools/vlm-feasibility/prepare.ps1 -RuntimeOnly
python tools/smolvlm2/prepare.py
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest -PgatewayTransfer=true
```

Model files remain outside the APK, under the ignored local artifacts directory. `prepare.py --verify-only` rechecks the downloaded bundle without using the network. The new engine supplies truthful model ID, quantisation, source hashes, model revision, runtime commit and formatter provenance. Existing Qwen descriptions retain their original provenance; they are not relabelled or regenerated automatically. The historical Qwen A/B benchmark has its original model hashes pinned separately.

## Device update and provisioning

Use a replacement install (`adb -s SERIAL install -r ...`) with the same application identity/signing key. Never uninstall or clear storage. Inventory the stopped app's database, recordings and old model files before and after replacing the APK. Then provision the new models:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/provision-smolvlm2-models.ps1 -Serial SERIAL
```

The script validates all local files first, stops the app to avoid concurrent inference, stages only the two pinned public files, independently hashes staged and app-owned copies and atomically publishes each file. New files live in `files/models/smolvlm2`; old `files/models/qwen3vl` files are preserved. If either file is missing or corrupt, the app presents the existing model-unavailable feedback; participant descriptions remain editable.

## Focused device tests

Install the generated test APK with `install -r`. The opt-in native test generates three synthetic bitmaps and checks actual load, multimodal generation, the model-specific prompt, nonempty output and unload; it never opens participant recordings or Room:

```powershell
adb -s SERIAL shell am instrument -w -e smolVlm2Native true -e class com.rchia.ecocapture.phase0.vlm.SmolVlm2NativeTest com.rchia.ecocapture.phase0.test/androidx.test.runner.AndroidJUnitRunner
```

The test saves aggregate timings and native metrics in app cache `smolvlm2-synthetic-report.json`. `SmolVlm2EngineTest` additionally exercises the existing sampled-video path with a disposable packaged fixture, checks new provenance and preserves input bytes. These checks establish compatibility, not description quality on study recordings. Evaluate quality, latency, peak memory and heat on approved disposable clips before treating the model change as accepted for participant use.

## Validation and installed update, 20 October 2026

- Debug app and instrumentation APKs built successfully with direct GB10 transfer enabled. Release Kotlin compiled successfully. Debug and release each passed 73 JVM tests.
- Both downloaded model files matched the immutable revision's published byte sizes and SHA256 values. The manifest matched the Android verification policy.
- The connected Pixel 9 received a replacement app install and the new model bundle. A stopped-app SHA inventory confirmed all three original database files, 24 recording files and two Qwen model files were unchanged after installation. Original files were also checked after device testing; the only model additions were the two SmolVLM2 files.
- Real SmolVLM2 inference on three generated 512×512 bitmaps passed, including load/unload and SmolVLM conversation-template assertions. The test used a 48-token cap: load/verification 866 ms, vision encoding 22,584 ms, prefill 5,556 ms, generation 5,360 ms, overall 34,420 ms. Generation stopped at the test token limit. This is a synthetic compatibility measurement, not a comparative benchmark or the app's full 384-token description latency.
- Six sampled-video engine/provenance/error-path tests and one isolated Room migration test passed. Participant descriptions and historical Qwen provenance were not rewritten.
- Installed debug APK copy: `app/build/outputs/apk/debug/ecological-capture-smolvlm2-gateway-debug.apk`, SHA256 `a3c1e4276d1d9190adcd99581de067f787ef8414b5d8d081e19c8161571a7d14`.

Next manual acceptance: generate a description from a disposable glasses recording and check spatial detail, uncertainty, changes between frames, TalkBack presentation and phone responsiveness. Real study-content evaluation has not been run by these automated checks.
