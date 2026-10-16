"""Offline, independent Checkpoint 2 schema/linkage and exact-byte verifier (stdlib only).

Schemas use a restricted JSON Schema vocabulary, checked below without fetching resources.
This verifies integrity against caller-trusted hashes, not identity or authenticity.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

SCHEMAS = Path(__file__).resolve().parent / "schemas"


def require(condition):
    if not condition:
        raise ValueError("SNAPSHOT_VERIFICATION_FAILED")


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def load_json(path):
    require(path.stat().st_size <= 16 * 1024 * 1024)
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("INVALID_JSON")))


def validate(value, schema):
    # Fail closed when a schema gains a keyword this deliberately small validator does not support.
    require(set(schema) <= {"$schema", "$id", "type", "properties", "required", "additionalProperties",
                            "items", "minItems", "maxItems", "pattern", "minimum", "enum", "const", "anyOf"})
    if "anyOf" in schema:
        for choice in schema["anyOf"]:
            try:
                validate(value, choice)
                break
            except ValueError:
                continue
        else:
            raise ValueError("INVALID_SCHEMA_VALUE")
    if "type" in schema:
        kinds = schema["type"]
        if isinstance(kinds, str):
            kinds = [kinds]
        types = {"null": type(None), "string": str, "integer": int, "boolean": bool, "object": dict, "array": list}
        require(any(type(value) is types[kind] for kind in kinds))
    if "const" in schema:
        require(type(value) is type(schema["const"]) and value == schema["const"])
    if "enum" in schema:
        require(value in schema["enum"])
    if isinstance(value, dict):
        require(set(schema.get("required", [])) <= set(value))
        if schema.get("additionalProperties") is False:
            require(set(value) <= set(schema["properties"]))
        for key, child in schema.get("properties", {}).items():
            if key in value:
                validate(value[key], child)
    if isinstance(value, list):
        require(schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", sys.maxsize))
        for entry in value:
            validate(entry, schema["items"])
    if isinstance(value, str) and "pattern" in schema:
        require(re.fullmatch(schema["pattern"], value) is not None)
    if type(value) is int and "minimum" in schema:
        require(value >= schema["minimum"])


def digest(path):
    h = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while block := stream.read(128 * 1024):
            size += len(block)
            h.update(block)
    return {"size_bytes": size, "sha256": h.hexdigest()}


def verify(directory, manifest_sha256, event_sha256=None, video_sha256=None):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink())
    require({p.name for p in directory.iterdir()} == {"video.mp4", "event.json", "manifest.json"})
    files = {name: directory / name for name in ("video.mp4", "event.json", "manifest.json")}
    require(all(path.is_file() and not path.is_symlink() for path in files.values()))
    measured = {name: digest(path) for name, path in files.items()}
    for name, expected in (("manifest.json", manifest_sha256), ("event.json", event_sha256), ("video.mp4", video_sha256)):
        if expected is not None:
            require(re.fullmatch("[0-9a-fA-F]{64}", expected) is not None)
            require(measured[name]["sha256"] == expected.lower())
    manifest = load_json(files["manifest.json"])
    event = load_json(files["event.json"])
    validate(manifest, load_json(SCHEMAS / "ecocapture.manifest.v1.json"))
    validate(event, load_json(SCHEMAS / "ecocapture.event.v1.json"))
    require([f["path"] for f in manifest["files"]] == ["video.mp4", "event.json"])
    for entry in manifest["files"]:
        require({k: entry[k] for k in ("size_bytes", "sha256")} == measured[entry["path"]])
    require(manifest["event_id"] == event["event_id"] == event["clip_id"])
    require(manifest["snapshot_id"] == event["snapshot_id"])
    require(event["previous_snapshot_id"] != event["snapshot_id"])
    require(manifest["created_at_epoch_ms"] == event["review"]["snapshot_created_at_epoch_ms"] == event["export"]["exported_at_epoch_ms"])
    annotations = event["participant_annotations"]["revisions"]
    runs = event["vlm_runs"]
    require(len({r["vlm_run_id"] for r in runs}) == len(runs))
    require(len({a["annotation_id"] for a in annotations}) == len(annotations))
    require(annotations == sorted(annotations, key=lambda a: (a["created_at_epoch_ms"], a["annotation_id"])))
    require(runs == sorted(runs, key=lambda r: (r["generated_at_epoch_ms"], r["vlm_run_id"])))
    current = [a["annotation_id"] for a in annotations if a["is_current"]]
    require(len(current) == (1 if annotations else 0))
    require(event["participant_annotations"]["current_annotation_id"] == (current[0] if current else None))
    by_id = {a["annotation_id"]: a for a in annotations}
    for a in annotations:
        require((a["source"] == "PARTICIPANT_AMENDMENT") == (a["parent_vlm_run_id"] is not None))
        require(a["parent_vlm_run_id"] is None or a["parent_vlm_run_id"] in {r["vlm_run_id"] for r in runs})
        seen = set()
        cursor = a
        while cursor is not None:
            require(cursor["annotation_id"] not in seen)
            seen.add(cursor["annotation_id"])
            parent = cursor["supersedes_annotation_id"]
            require(parent is None or parent in by_id)
            cursor = by_id[parent] if parent is not None else None
    return {"status": "SNAPSHOT_SHA256_SCHEMA_VERIFIED", "files": measured}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--manifest-sha256", required=True, help="Digest from the trusted local record, not calculated from received files")
    parser.add_argument("--event-sha256")
    parser.add_argument("--video-sha256")
    args = parser.parse_args()
    try:
        result = verify(args.directory, args.manifest_sha256, args.event_sha256, args.video_sha256)
    except (ValueError, OSError, KeyError, TypeError, RecursionError):
        print('{"status":"SNAPSHOT_VERIFICATION_FAILED"}')
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
