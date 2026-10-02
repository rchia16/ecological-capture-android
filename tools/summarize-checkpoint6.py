"""Build a local engineering comparison from exact persisted reports; never rewrite model output."""
import html
import json
from pathlib import Path

results = Path(__file__).parent / "vlm-feasibility/artifacts/results/checkpoint6"
reports = []
for path in sorted(results.glob("clip_*-*frames-*px.json")):
    if path.stat().st_size:
        report = json.loads(path.read_text(encoding="utf-8-sig"))
        report["reportFile"] = path.name
        reports.append(report)

rows, sections = [], []
for report in reports:
    config = report.get("generationConfig", {})
    metrics = config.get("nativeMetrics", {})
    sampling = report.get("frameSampling", {})
    values = [report["sourceFilename"], str(report["frameCount"]), str(report["maxLongEdge"]),
              str(report.get("success", False)), f'{report.get("loadAndVerificationMs", 0) / 1000:.1f}',
              f'{sampling.get("extractionDurationMs", 0) / 1000:.2f}',
              f'{report.get("inferenceDurationMs", 0) / 1000:.1f}',
              f'{report.get("memoryAfterGeneration", {}).get("VmHWMKiB", 0) / 1048576:.2f}',
              f'{report.get("thermalBefore", "?")} / {report.get("thermalAfterGeneration", "?")}',
              str(metrics.get("stopReason", "unknown"))]
    rows.append("<tr>" + "".join(f"<td>{html.escape(value)}</td>" for value in values) + "</tr>")
    heading = f'{report["sourceFilename"]}: {report["frameCount"]} frames, {report["maxLongEdge"]} px'
    sections.append(f'<section><h2>{html.escape(heading)}</h2><p><a href="{html.escape(report["reportFile"])}">Full provenance and measurements</a></p>'
                    f'<pre>{html.escape(report.get("rawOutput", "No successful output recorded."))}</pre></section>')

headers = ["Recording", "Frames", "Max edge", "Runtime / persistence pass", "Load + hashes (s)", "Extraction (s)",
           "Inference (s)", "Peak RSS (GiB)", "Thermal before / after", "Stop reason"]
page = f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Checkpoint 6 real inference comparison</title>
<style>body{{font:18px system-ui;background:#151515;color:#fff;margin:24px;line-height:1.5}}a{{color:#9cf}}table{{border-collapse:collapse}}td,th{{padding:10px;border:1px solid #666;text-align:left}}.table{{overflow:auto}}pre{{white-space:pre-wrap;font:inherit;max-width:90ch}}section{{margin:40px 0}}</style>
<h1>Checkpoint 6: real Qwen3-VL comparison</h1>
<p>Engineering results only. Descriptions are unmodified AI outputs and may be inaccurate or incomplete. Check coarse layout, obstacles, landmarks, routes and visible actions against playback. Text and numbers need separate scrutiny; generated claims are not ground truth.</p>
<p>The pass column records runtime and persistence checks. Description accuracy requires review against the supplied frames.</p>
<p>Measurements are individual runs with uncontrolled file caches and thermal conditions. Peak RSS includes loading. Inference includes RGB ingestion, vision encoding, prefill and generation. Participant recordings and their review descriptions were not changed.</p>
<div class="table"><table><caption>Recorded comparison runs</caption><thead><tr>{''.join(f'<th scope="col">{header}</th>' for header in headers)}</tr></thead><tbody>{''.join(rows)}</tbody></table></div>
{''.join(sections)}</html>'''
(results / "index.html").write_text(page, encoding="utf-8")
print(results / "index.html")
