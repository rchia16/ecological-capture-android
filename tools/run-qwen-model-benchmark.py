"""Isolated, resumable Pixel benchmark. No production model or annotation writes."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PRODUCTION = "com.rchia.ecocapture.phase0"
PACKAGE = PRODUCTION + ".benchmark"
CLIPS = ["clip_20261001_160008_634.mp4", "clip_20261001_160118_201.mp4", "clip_20261001_183137_611.mp4"]
MODELS = {
    "A": {"repository": "Qwen/Qwen3-VL-4B-Instruct-GGUF", "revision": "1cd86afb9a95c410a6038ab3b40d8b578c892266",
          "files": [("Qwen3VL-4B-Instruct-Q4_K_M.gguf", 2497281664, "66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a"),
                    ("mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf", 453974304, "30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d")]},
    "B": {"repository": "Qwen/Qwen3-VL-2B-Instruct-GGUF", "revision": "52d6c8ffea26cc873ac5ad116f8631268d7eb503",
          "files": [("Qwen3VL-2B-Instruct-Q4_K_M.gguf", 1107409952, "089d75c52f4b7ffc56ba998ffc50aae89fcafc755f9e7208aacca281dca6c2ae"),
                    ("mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf", 445053216, "f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82")]},
}

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="D:/Ray/Android/sdk/platform-tools/adb.exe")
parser.add_argument("--aapt", default="D:/Ray/Android/sdk/build-tools/35.0.0/aapt.exe")
parser.add_argument("--prepare", action="store_true")
parser.add_argument("--run", action="store_true")
parser.add_argument("--limit", type=int)
parser.add_argument("--cooldown", type=int, default=30)
parser.add_argument("--max-output-tokens", type=int, default=384, choices=range(1, 1025), metavar="1..1024")
args = parser.parse_args()
RESULTS = ROOT / f"tools/vlm-feasibility/artifacts/results/model-comparison-{args.max_output_tokens}"
RESULTS.mkdir(parents=True, exist_ok=True)


def adb(*arguments, check=True, timeout=120):
    return subprocess.run([args.adb, "-s", args.serial, *map(str, arguments)], capture_output=True,
                          timeout=timeout, check=check)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2), encoding="utf-8")
    temporary.replace(path)


def export(remote, local, optional=False):
    output = adb("exec-out", "run-as", PACKAGE, "cat", remote, check=False)
    if output.returncode:
        if optional:
            return False
        raise RuntimeError(f"Could not export {remote}")
    local.write_bytes(output.stdout)
    return True


def prepare():
    # Validate both APK identities before any install; ordinary debug builds use the production ID.
    apks = [(ROOT / "app/build/outputs/apk/debug/app-debug.apk", PACKAGE),
            (ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", PACKAGE + ".test")]
    for apk, expected_package in apks:
        badging = subprocess.run([args.aapt, "dump", "badging", str(apk)], capture_output=True, check=True)
        match = re.search(rb"^package: name='([^']+)'", badging.stdout, re.MULTILINE)
        assert match and match.group(1).decode() == expected_package, "Build isolated APKs with -PvlmBenchmark=true before preparing"
    # Force stop holds production scheduling without changing its preferences or pending requests.
    adb("shell", "am", "force-stop", PRODUCTION)
    adb("shell", f"run-as {PRODUCTION} tar -cf - databases > /data/local/tmp/qwen-benchmark-before.tar")
    adb("pull", "/data/local/tmp/qwen-benchmark-before.tar", RESULTS / "production-before.tar")
    for apk, _ in apks:
        adb("install", "-r", apk)
    assert adb("shell", "pm", "path", PACKAGE).stdout.strip(), "Isolated APK was not installed"
    assert adb("shell", "pm", "path", PRODUCTION).stdout.strip(), "Production app must remain installed"
    for key, model in MODELS.items():
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", f"files/benchmark/models/{key}")
        for filename, size, expected in model["files"]:
            folder = ROOT / "tools/vlm-feasibility/artifacts"
            if key == "B":
                folder /= "models-2b"
            path = folder / filename
            assert path.stat().st_size == size and sha(path) == expected, f"Verification failed: {filename}"
            print(f"Verified and copying {filename}", flush=True)
            remote = "/data/local/tmp/" + filename
            adb("push", path, remote, timeout=300)
            adb("shell", "run-as", PACKAGE, "cp", remote, f"files/benchmark/models/{key}/{filename}", timeout=180)
    source_dir = RESULTS / "sources"
    source_dir.mkdir(exist_ok=True)
    manifest = []
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/benchmark/recordings")
    for name in CLIPS:
        source = source_dir / name
        data = adb("exec-out", "run-as", PRODUCTION, "cat", "files/recordings/" + name).stdout
        source.write_bytes(data)
        old = ROOT / f"tools/vlm-feasibility/artifacts/results/checkpoint6/{name[:-4]}-3frames-1024px.json"
        expected = json.loads(old.read_text(encoding="utf-8-sig"))["sourceSha256"]
        assert sha(source) == expected, f"Clip changed since Checkpoint 6: {name}"
        remote = "/data/local/tmp/qwen-benchmark-source.mp4"
        adb("push", source, remote)
        adb("shell", "run-as", PACKAGE, "cp", remote, "files/benchmark/recordings/" + name)
        manifest.append({"name": name, "sha256": expected, "bytes": source.stat().st_size})
    write_json(RESULTS / "manifest.json", {"models": MODELS, "clips": manifest,
        "promptVersion": "ecological_scene_description_v2", "maxOutputTokens": args.max_output_tokens,
        "contextSize": 8192, "threads": 4, "temperature": 0, "backend": "CPU",
        "warmupsPerClipModelConfig": 1, "measuredRepetitionsPerClipModelConfig": 3,
        "freshProcessPerRun": True, "productionForceStoppedForIsolation": True,
        "totalTimingExcludes": "verification, process startup, artifact PNG export, source rehash and unload"})


def schedule():
    plan = []
    for clip_index, name in enumerate(CLIPS):
        for config_index, (frames, edge) in enumerate(((1, 768), (3, 768), (3, 1024))):
            for repetition in range(4):
                # Counterbalance model order between blocks/repetitions.
                order = ("A", "B") if (clip_index + config_index + repetition) % 2 == 0 else ("B", "A")
                for model in order:
                    run_id = f"{name[:-4]}-{model}-{frames}f-{edge}px-r{repetition}"
                    plan.append({"runId": run_id, "recording": name, "model": model, "frameCount": frames,
                                 "maxLongEdge": edge, "maxOutputTokens": args.max_output_tokens,
                                 "repetition": repetition, "warmup": repetition == 0})
    return plan


def run_one(index, total, item):
    directory = RESULTS / item["runId"]
    directory.mkdir(exist_ok=True)
    if (directory / "host.json").exists():
        return False
    print(f"[{index}/{total}] {item['runId']} {'warm-up' if item['warmup'] else 'measured'}", flush=True)
    # Fresh process makes VmHWM meaningful for this particular run; never clears either app's data.
    adb("shell", "am", "force-stop", PACKAGE)
    (directory / "thermal-before.txt").write_bytes(adb("shell", "dumpsys", "thermalservice").stdout)
    command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-e", "class",
               "com.rchia.ecocapture.phase0.vlm.QwenModelBenchmarkTest", "-e", "modelBenchmark", "true"]
    for key, value in item.items():
        command += ["-e", key, str(value).lower() if isinstance(value, bool) else str(value)]
    command.append(PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    started = time.monotonic()
    deadline = started + 1800
    timed_out = False
    with (directory / "instrumentation.txt").open("wb") as stream:
        process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
        last_update = started
        while process.poll() is None:
            if time.monotonic() >= deadline:
                timed_out = True
                adb("shell", "am", "force-stop", PACKAGE)
                process.wait(timeout=30)
                break
            time.sleep(5)
            if time.monotonic() - last_update >= 60:
                stage = "running"
                report = adb("exec-out", "run-as", PACKAGE, "cat", f"files/benchmark/results/{item['runId']}/report.json", check=False)
                try:
                    stage = json.loads(report.stdout)["stage"]
                except (ValueError, KeyError):
                    pass
                print(f"  {int(time.monotonic() - started)}s: {stage}", flush=True)
                last_update = time.monotonic()
    log = (directory / "instrumentation.txt").read_text(encoding="utf-8", errors="replace")
    export(f"files/benchmark/results/{item['runId']}/report.json", directory / "report.json", optional=True)
    export(f"files/benchmark/results/{item['runId']}/output.txt", directory / "output.txt", optional=True)
    if item["warmup"]:
        for frame in range(item["frameCount"]):
            export(f"files/benchmark/results/{item['runId']}/frame-{frame}.png", directory / f"frame-{frame}.png", optional=True)
    (directory / "thermal-after.txt").write_bytes(adb("shell", "dumpsys", "thermalservice").stdout)
    (directory / "exit-info.txt").write_bytes(adb("shell", "dumpsys", "activity", "exit-info", PACKAGE).stdout)
    host = {**item, "runnerSuccess": "OK (1 test)" in log, "hostElapsedMs": round((time.monotonic() - started) * 1000),
            "timeout": timed_out, "processCrash": "Process crashed" in log or "INSTRUMENTATION_FAILED" in log,
            "adbExitCode": process.returncode}
    write_json(directory / "host.json", host)
    subprocess.run([sys.executable, str(ROOT / "tools/summarize-qwen-model-benchmark.py"),
                    "--max-output-tokens", str(args.max_output_tokens)], check=True,
                   stdout=subprocess.DEVNULL)
    status = "PASS" if host["runnerSuccess"] else "FAIL (retained for review)"
    print(f"  {status}, elapsed {host['hostElapsedMs']/1000:.1f}s", flush=True)
    return True


if args.prepare:
    prepare()
if args.run:
    assert (RESULTS / "manifest.json").exists(), "Prepare the isolated models and sources first"
    manifest = json.loads((RESULTS / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["maxOutputTokens"] == args.max_output_tokens, "Token-cap provenance mismatch"
    if "serial" in manifest:
        assert manifest["serial"] == args.serial, "Prepared benchmark belongs to a different device"
    plan = schedule()
    write_json(RESULTS / "schedule.json", plan)
    completed = 0
    for index, item in enumerate(plan, 1):
        if run_one(index, len(plan), item):
            completed += 1
            if args.limit and completed >= args.limit:
                break
            # Cancellable host rest between runs; not part of elapsed inference time.
            for _ in range(args.cooldown):
                time.sleep(1)
    print("Benchmark invocation finished. Review outputs before making a model decision.", flush=True)
