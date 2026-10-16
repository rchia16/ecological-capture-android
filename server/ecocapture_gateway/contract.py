import hashlib
import json
import re
import uuid

from .errors import require

OBJECTS = ("video.mp4", "event.json", "manifest.json")


def canonical_uuid(value):
    try:
        require(type(value) is str and str(uuid.UUID(value)) == value)
    except (ValueError, AttributeError):
        require(False)
    return value


def sha256(value):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None)
    return value


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def parse_json(body):
    try:
        return json.loads(body, object_pairs_hook=no_duplicates,
                          parse_constant=lambda _: require(False))
    except (ValueError, UnicodeError, RecursionError):
        require(False)


def creation(body, key, config):
    require(type(body) is dict and set(body) == {"schema", "snapshot_id", "event_id", "objects"})
    require(body["schema"] == "ecocapture.gateway.transfer.v1")
    canonical_uuid(body["snapshot_id"])
    canonical_uuid(body["event_id"])
    require(key == body["snapshot_id"], code="INVALID_IDEMPOTENCY_KEY")
    require(type(body["objects"]) is list and len(body["objects"]) == 3)
    require(all(type(o) is dict and set(o) == {"name", "size_bytes", "sha256"} for o in body["objects"]))
    require({o["name"] for o in body["objects"] if type(o["name"]) is str} == set(OBJECTS))
    limits = {"video.mp4": config.max_video_bytes, "event.json": config.max_event_bytes,
              "manifest.json": config.max_manifest_bytes}
    for o in body["objects"]:
        require(type(o["size_bytes"]) is int and 0 < o["size_bytes"] <= limits[o["name"]],
                413, "OBJECT_LIMIT")
        sha256(o["sha256"])
    canonical = dict(body, objects=sorted(body["objects"], key=lambda o: o["name"]))
    fingerprint = hashlib.sha256(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return canonical, fingerprint
