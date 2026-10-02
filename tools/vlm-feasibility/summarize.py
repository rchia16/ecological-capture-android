"""Summarize collected engineering measurements without printing generated text."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parent / "artifacts" / "results"
rows = []
for directory in sorted(root.rglob("*-images")):
    count = int(directory.name.split("-")[0])
    request_file = directory / "request.json"
    request = json.loads(request_file.read_text(encoding="utf-8")) if request_file.exists() else {}
    prefix = request.get("engineeringRunId") or (f"run-{count}-v2" if request else f"run-{count}")
    summary = directory / f"{prefix}-summary.txt"
    if not summary.exists():
        continue
    measurements = {}
    for line in summary.read_text(encoding="utf-8").splitlines():
        key, value = line.split("=", 1)
        measurements[key] = int(value)
    rss = []
    for line in (directory / f"{prefix}-rss.txt").read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) == 3:
            rss.append([int(field) for field in fields])
    runtime = (directory / f"{prefix}-runtime.txt").read_text(encoding="utf-8")
    ready = re.search(r"(\d+)\.(\d+)\.(\d+)\.(\d+) I main: loading model", runtime)
    initialization_seconds = None
    if ready:
        minutes, seconds, milliseconds, microseconds = map(int, ready.groups())
        initialization_seconds = minutes * 60 + seconds + milliseconds / 1000 + microseconds / 1_000_000
    thermal = {}
    skin = {}
    for phase in ("before", "after"):
        text = (directory / f"{prefix}-thermal-{phase}.txt").read_text()
        status = re.search(r"^Thermal Status: (\d+)", text, re.MULTILINE)
        thermal[phase] = int(status.group(1)) if status else None
        # Use fresh HAL readings; cached event readings can remain unchanged.
        current = text.split("Current temperatures from HAL:", 1)[-1]
        sensor = re.search(r"Temperature\{mValue=([\d.]+), mType=3, mName=VIRTUAL-SKIN,", current)
        skin[phase] = float(sensor.group(1)) if sensor else None
    perf = {}
    for label in ("load", "prompt eval", "eval", "total"):
        match = re.search(rf"llama_perf_context_print:\s+{label} time =\s*([\d.]+) ms", runtime)
        perf[f"{label.replace(' ', '_')}_ms"] = float(match.group(1)) if match else None
    rows.append({
        "images": count,
        "prompt_version": request.get("promptVersion", "checkpoint0_engineering_prompt"),
        "engineering_run_id": request.get("engineeringRunId"),
        **measurements,
        "initialization_ready_seconds": initialization_seconds,
        "vision_encode_ms": [int(value) for value in re.findall(r"mtmd batch encoding done in (\d+) ms", runtime)],
        "peak_rss_kib": max((sample[1] for sample in rss), default=None),
        "observed_hwm_kib": max((sample[2] for sample in rss), default=None),
        "thermal_status": thermal,
        "virtual_skin_celsius": skin,
        "native_perf": perf,
    })
print(json.dumps(rows, indent=2))
