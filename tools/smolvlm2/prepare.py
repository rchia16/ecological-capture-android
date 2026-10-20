"""Download only the pinned public SmolVLM2 bundle; never touches device data."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("model-manifest.json")
DESTINATION = ROOT / "tools/vlm-feasibility/artifacts/smolvlm2"


def digest(path):
    with path.open("rb") as source: return hashlib.file_digest(source, "sha256").hexdigest()


def prepare(verify_only=False):
    policy = json.loads(MANIFEST.read_text())
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for item in policy["files"]:
        path = DESTINATION / item["filename"]
        if path.is_file() and path.stat().st_size == item["bytes"] and digest(path) == item["sha256"]:
            print("VERIFIED", item["filename"]); continue
        if verify_only: raise ValueError("MODEL_NOT_PREPARED")
        temporary = path.with_suffix(".gguf.download")
        url = f"https://huggingface.co/{policy['repository']}/resolve/{policy['revision']}/{item['filename']}"
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as output:
            size = 0
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > item["bytes"]: raise ValueError("MODEL_SIZE_MISMATCH")
                output.write(block)
        if temporary.stat().st_size != item["bytes"] or digest(temporary) != item["sha256"]:
            raise ValueError("MODEL_HASH_MISMATCH")
        temporary.replace(path)
        print("VERIFIED", item["filename"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--verify-only", action="store_true")
    prepare(parser.parse_args().verify_only)
