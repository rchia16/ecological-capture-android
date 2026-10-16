"""Copy ONE explicitly selected disposable local snapshot over USB and verify it offline.

No FileSender, credentials or automatic enumeration. Output directory must be new.
Only use disposable engineering recordings; the extracted video/text remain local.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import uuid

from verify_snapshot import digest, load_json, require, verify


def extract_fixed(archive, output, event_id, snapshot_id):
    prefix = f"files/engineering-exports/{snapshot_id}"
    bundle_prefix = f"{prefix}/event_{event_id}_snapshot_{snapshot_id}"
    expected = {f"{bundle_prefix}/{name}": output / "bundle" / name
                for name in ("video.mp4", "event.json", "manifest.json")}
    expected[f"{prefix}/snapshot-record.json"] = output / "snapshot-record.json"
    directories = {"files", "files/engineering-exports", prefix, bundle_prefix}
    seen = set()
    with tarfile.open(archive, "r:") as tar:
        # Reject unknown names, links and duplicate entries before writing any extracted files.
        members = tar.getmembers()
        require(len(members) <= 8)
        for member in members:
            name = member.name.rstrip("/")
            require(name not in seen)
            seen.add(name)
            require((member.isdir() and name in directories) or (member.isfile() and name in expected))
            if member.isfile():
                require(0 <= member.size <= (8 * 1024**3 if name.endswith("video.mp4") else 16 * 1024**2))
        require(set(expected) <= seen)
        (output / "bundle").mkdir()
        for member in members:
            if member.isfile():
                with tar.extractfile(member) as source, expected[member.name].open("xb") as destination:
                    shutil.copyfileobj(source, destination, length=128 * 1024)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="D:/Ray/Android/sdk/platform-tools/adb.exe")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--snapshot-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for value in (args.event_id, args.snapshot_id):
        require(str(uuid.UUID(value)) == value)
    args.output.mkdir(parents=True, exist_ok=False)
    archive = args.output / "snapshot.tar"
    # UUID validation makes the only variable in this remote shell command non-executable.
    command = f"run-as com.rchia.ecocapture.phase0 tar -cf - files/engineering-exports/{args.snapshot_id}"
    with archive.open("xb") as output:
        process = subprocess.run([args.adb, "-s", args.serial, "exec-out", command], stdout=output, stderr=subprocess.PIPE)
    require(process.returncode == 0)
    extract_fixed(archive, args.output, args.event_id, args.snapshot_id)
    record = load_json(args.output / "snapshot-record.json")
    require(record["schema"] == "ecocapture.local-snapshot.v1" and record["state"] == "LOCAL_ONLY")
    require(record["snapshot_id"] == args.snapshot_id and record["clip_id"] == args.event_id)
    require([entry["path"] for entry in record["files"]] == ["video.mp4", "event.json", "manifest.json"])
    expected = {entry["path"]: entry for entry in record["files"]}
    for name, entry in expected.items():
        require(digest(args.output / "bundle" / name) == {k: entry[k] for k in ("size_bytes", "sha256")})
    result = verify(args.output / "bundle", expected["manifest.json"]["sha256"],
                    expected["event.json"]["sha256"], expected["video.mp4"]["sha256"])
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, KeyError, TypeError, tarfile.TarError, RecursionError):
        print('{"status":"SNAPSHOT_COPY_OR_VERIFICATION_FAILED"}')
        raise SystemExit(1)
