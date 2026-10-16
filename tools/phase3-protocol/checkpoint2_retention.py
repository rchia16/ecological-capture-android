"""Read-only on-device SHA inventory; no participant file/DB contents copied to the host.

Run with app quiescent. Compare stopped-app database hashes separately across replacement install.
After instrumentation WAL/checkpoint representation may change; compare media/models and use Room tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "com.rchia.ecocapture.phase0"


def inventory(adb, serial):
    # Fixed directories only; SHA hashes are computed on the phone. No credentials/profile directories.
    command = "run-as " + PACKAGE + " sh -c 'find databases files/recordings files/models -type f -exec sha256sum {} \\;'"
    result = subprocess.run([adb, "-s", serial, "shell", command], check=True, capture_output=True, text=True)
    entries = {}
    for line in result.stdout.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+(.+)", line)
        if not match:
            raise ValueError("INVENTORY_FAILED")
        sha, path = match.groups()
        if path.startswith("databases/") and Path(path).name not in {
            "ecological_capture.db", "ecological_capture.db-wal", "ecological_capture.db-shm"
        }:
            continue
        category = "database" if path.startswith("databases/") else "models" if path.startswith("files/models/") else "recordings"
        # Keep filenames and capture timestamps out of the host inventory and console.
        entries[hashlib.sha256(path.encode()).hexdigest()] = {"category": category, "sha256": sha}
    if not entries or not any(v["category"] == "database" for v in entries.values()):
        raise ValueError("INVENTORY_INCOMPLETE")
    return entries


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default="D:/Ray/Android/sdk/platform-tools/adb.exe")
    p.add_argument("--serial", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--compare", type=Path)
    p.add_argument("--include-database", action="store_true")
    args = p.parse_args()
    data = inventory(args.adb, args.serial)
    summary = {"counts": {c: sum(v["category"] == c for v in data.values()) for c in ("database", "recordings", "models")}}
    if args.compare:
        before = json.loads(args.compare.read_text())
        categories = {"database", "recordings", "models"} if args.include_database else {"recordings", "models"}
        before = {k: v for k, v in before.items() if v["category"] in categories}
        after = {k: v for k, v in data.items() if v["category"] in categories}
        summary["identical"] = before == after
        summary["compared_categories"] = sorted(categories)
        if before != after:
            print(json.dumps(summary))
            return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(data, output, indent=2)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
