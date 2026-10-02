"""Summarize exact benchmark timings and attach separate, manually authored image reviews."""
import collections
import argparse
import csv
import html
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--max-output-tokens", type=int, default=128)
args = parser.parse_args()
RESULTS = ROOT / f"tools/vlm-feasibility/artifacts/results/model-comparison-{args.max_output_tokens}"
rows = []
for path in sorted(RESULTS.glob("*/host.json")):
    host = json.loads(path.read_text())
    report_path = path.parent / "report.json"
    report = json.loads(report_path.read_text()) if report_path.exists() else {}
    row = {**report, **host, "directory": path.parent.name}
    hwm = [report.get(phase, {}).get("VmHWMKiB") for phase in
           ("memoryBefore", "memoryLoaded", "memoryAfterGeneration", "memoryAfterUnload")]
    row["peakRssKiB"] = max(value for value in hwm if value is not None) if any(value is not None for value in hwm) else None
    if report.get("stage") != "finished":
        row["observedPeakRssKiB"] = row["peakRssKiB"]
        row["peakRssKiB"] = None  # Last persisted stage cannot establish the peak of a killed process.
    if not host.get("runnerSuccess") and report.get("stage") != "finished":
        row["oom"] = None  # A killed process cannot reliably persist its own OOM status.
    rows.append(row)
review_path = RESULTS / "manual-reviews.json"
reviews = json.loads(review_path.read_text()) if review_path.exists() else {}
# Exact repeated outputs can share a manual assessment only for the same clip and frame configuration.
# A differing byte, recording hash or sampling provenance requires a new manual comparison.
for row in rows:
    if row["runId"] in reviews or not row.get("success"):
        continue
    reference = next((item for item in rows if item["runId"] in reviews
                      and item.get("rawOutput") == row.get("rawOutput")
                      and item.get("sourceSha256") == row.get("sourceSha256")
                      and item.get("frameCount") == row.get("frameCount")
                      and item.get("maxLongEdge") == row.get("maxLongEdge")
                      and item.get("frameSampling", {}).get("frames") == row.get("frameSampling", {}).get("frames")), None)
    if reference is not None:
        reviews[row["runId"]] = {**reviews[reference["runId"]], "identical_output_review_reference": reference["runId"]}
if review_path.exists():
    review_path.write_text(json.dumps(reviews, indent=2), encoding="utf-8")
groups = collections.defaultdict(list)
for row in rows:
    if not row["warmup"] and not row.get("interruptedByUser"):
        groups[(row["model"], row["frameCount"], row["maxLongEdge"])].append(row)

metrics = ["modelLoadMs", "frameExtractionMs", "visionEncodeMs", "promptEvaluationMs", "textGenerationMs", "totalElapsedMs"]
table = ["| Model | Frames / edge | Successful measured runs | Load | Extraction | Vision | Prompt | Generation | Total | Tokens | Peak RSS | Token-limit stops |",
         "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
summary = []
for (model, frames, edge), group in sorted(groups.items()):
    successful = [row for row in group if row.get("runnerSuccess") and row.get("success")]
    entry = {"model": model, "frames": frames, "maxLongEdge": edge, "measuredAttempts": len(group), "successful": len(successful)}
    values = []
    for metric in metrics:
        samples = [row[metric] / 1000 for row in successful if metric in row]
        entry[metric] = {"count": len(samples), "minimumSeconds": min(samples), "medianSeconds": statistics.median(samples),
                         "maximumSeconds": max(samples)} if samples else None
        values.append(f"{min(samples):.2f} / {statistics.median(samples):.2f} / {max(samples):.2f}" if samples else "Unavailable")
    tokens = [row["generatedTokens"] for row in successful]
    peak = [row["peakRssKiB"] / 1048576 for row in successful if row["peakRssKiB"] is not None]
    stops = sum(row.get("stopReason") == "token_limit" for row in successful)
    entry.update(generatedTokens=tokens, peakRssGiB=peak, tokenLimitStops=stops)
    summary.append(entry)
    token_range = f"{min(tokens)}-{max(tokens)}" if tokens else "Unavailable"
    memory = f"{max(peak):.2f} GiB" if peak else "Unavailable"
    table.append(f"| {model} | {frames} / {edge}px | {len(successful)}/{len(group)} | " + " | ".join(values)
                 + f" | {token_range} | {memory} | {stops} |")

(RESULTS / "timing-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
(RESULTS / "comparison-table.md").write_text("Timing cells: min / median / max seconds. Warm-ups and user-interrupted runs excluded.\n\n" + "\n".join(table) + "\n", encoding="utf-8")
csv_fields = ["runId", "model", "recording", "frameCount", "maxLongEdge", "maxOutputTokens", "warmup", "repetition", "runnerSuccess",
              *metrics, "verificationMs", "rgbIngestionMs", "overallElapsedMs", "hostElapsedMs", "generatedTokens",
              "stopReason", "peakRssKiB", "observedPeakRssKiB", "thermalBefore", "thermalAfter", "thermalAfterUnload", "success",
              "crash", "oom", "processCrash", "timeout", "interruptedByUser", "completionStatus", "generationStatus", "errorType", "error"]
with (RESULTS / "runs.csv").open("w", encoding="utf-8", newline="") as target:
    writer = csv.DictWriter(target, fieldnames=csv_fields, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)

sections = []
for row in rows:
    review = reviews.get(row["runId"])
    directory = row["directory"]
    reference = next((item for item in rows if item["warmup"] and item["model"] == row["model"]
                      and item["recording"] == row["recording"] and item["frameCount"] == row["frameCount"]
                      and item["maxLongEdge"] == row["maxLongEdge"]), None)
    pictures = "" if reference is None else "".join(
        f'<img loading="lazy" src="{html.escape(reference["directory"])}/frame-{index}.png" alt="Sample {index + 1}">'
        for index in range(row["frameCount"]))
    assessment = "<p>Manual image comparison pending.</p>" if review is None else "<dl>" + "".join(
        f"<dt>{html.escape(key.replace('_', ' ').capitalize())}</dt><dd>{html.escape(str(value))}</dd>"
        for key, value in review.items()) + "</dl>"
    status = "Interrupted when the batch was stopped; no completed output" if row.get("interruptedByUser") else ("Warm-up" if row["warmup"] else "Measured")
    sections.append(f'<section id="{html.escape(row["runId"])}"><h2>{html.escape(row["runId"])}</h2>'
                    f'<p>{status}; <a href="{html.escape(directory)}/report.json">Full provenance and timings</a></p>'
                    f'<div class="frames">{pictures}</div><h3>Complete unedited output</h3>'
                    f'<pre>{html.escape(row.get("rawOutput", "No successful output returned."))}</pre>{assessment}</section>')
page = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' \
       '<title>Qwen 4B versus 2B: ecological scene benchmark</title><style>body{font:18px system-ui;line-height:1.5;margin:24px;background:#171717;color:white}' \
       'a{color:#9cf}.frames{display:flex;gap:12px;overflow:auto}img{max-height:420px;max-width:85vw}pre{white-space:pre-wrap;font:inherit;max-width:90ch}' \
       'section{border-top:1px solid #777;padding:24px 0}dt{font-weight:bold}dd{margin-bottom:12px}</style>' \
       f'<h1>Qwen 4B / 2B benchmark ({args.max_output_tokens}-token cap)</h1><p>{len(rows)} run records exported; {sum(bool(row.get("runnerSuccess")) for row in rows)} completed; {sum(bool(row.get("interruptedByUser")) for row in rows)} user-interrupted. {len(reviews)} manual reviews recorded. '
page += 'Outputs are AI suggestions, not ground truth. Warm-ups are excluded from timing summaries. '
page += '<a href="comparison-table.md">Comparison table</a> &middot; <a href="runs.csv">All run measurements</a></p>' + "".join(sections) + '</html>'
(RESULTS / "index.html").write_text(page, encoding="utf-8")
print(f"Exported {len(rows)} runs; {len(reviews)} manual reviews. {RESULTS / 'index.html'}")
