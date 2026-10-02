"""Export the Kotlin prompt verbatim for the standalone CLI; keep one prompt source."""
import argparse
import hashlib
import json
import re
import textwrap
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--output-directory", type=Path, required=True)
parser.add_argument("--images", type=int, choices=(1, 3, 5), required=True)
parser.add_argument("--run-id", default=None)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
source = root / "app/src/main/java/com/rchia/ecocapture/phase0/vlm/VlmPrompt.kt"
code = source.read_text(encoding="utf-8")
version = re.search(r'const val VERSION = "([^"]+)"', code).group(1)
prompts = {}
for field, filename in (("systemPrompt", "system-prompt.txt"), ("userPrompt", "user-prompt.txt")):
    match = re.search(rf'val {field} = """\n(.*?)\n    """\.trimIndent\(\)', code, re.DOTALL)
    if not match:
        raise ValueError(f"Cannot export Kotlin prompt: {field}")
    prompts[filename] = textwrap.dedent(match.group(1))
args.output_directory.mkdir(parents=True, exist_ok=True)
for filename, prompt in prompts.items():
    (args.output_directory / filename).write_bytes(prompt.encode("utf-8"))
manifest = {
    "engineeringRunId": args.run_id,
    "promptVersion": version,
    "prompts": prompts,
    "promptSha256": {name: hashlib.sha256(text.encode("utf-8")).hexdigest() for name, text in prompts.items()},
    "modelId": "Qwen/Qwen3-VL-4B-Instruct-GGUF",
    "modelQuant": "Q4_K_M",
    "languageModelSha256": "66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a",
    "mmprojSha256": "30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d",
    "runtimeCommit": "0c1e57098bba43ac29e6e3b677cdceebdd22334f",
    "generationConfig": {"temperature": 0, "maxOutputTokens": 384, "contextSize": 8192, "threads": 4, "seed": 0},
    "frames": {"count": args.images, "width": 640, "height": 480, "source": "repeated upstream newspaper fixture; not a participant video"},
}
(args.output_directory / "request.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print(f"Exported {version}")
