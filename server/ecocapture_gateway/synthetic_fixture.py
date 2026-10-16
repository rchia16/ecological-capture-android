"""The only event payload eligible for the Checkpoint 5 engineering forwarder."""
import json


def files(event_id, snapshot_id):
    video = b"\x00\x00\x00\x10ftypisom0000" + b"\x00\x00\x00\x09moovx"
    video += (131080).to_bytes(4, "big") + b"mdat" + b"x" * 131072
    event = {"schema": "ecocapture.event.v1", "event_id": event_id, "clip_id": event_id,
        "snapshot_id": snapshot_id, "previous_snapshot_id": None, "study_participant_code": None,
        "capture": {"created_at_epoch_ms": 1000, "duration_ms": 1000, "width": 320, "height": 180,
                    "sample_count": 2, "created_by_app_version": "synthetic-checkpoint5"},
        "review": {"review_state": "REVIEWED", "approval_state": "APPROVED", "approved_at_epoch_ms": None,
                   "updated_at_epoch_ms": 2000, "snapshot_created_at_epoch_ms": 3000},
        "participant_annotations": {"current_annotation_id": None, "revisions": []}, "vlm_runs": [],
        "export": {"schema_version": 1, "app_version": "synthetic-checkpoint5", "exported_at_epoch_ms": 3000}}
    science = json.dumps(event, separators=(",", ":")).encode()
    import hashlib
    manifest = {"schema": "ecocapture.manifest.v1", "event_id": event_id, "snapshot_id": snapshot_id,
        "created_at_epoch_ms": 3000, "files": [
            {"path": "video.mp4", "size_bytes": len(video), "sha256": hashlib.sha256(video).hexdigest()},
            {"path": "event.json", "size_bytes": len(science), "sha256": hashlib.sha256(science).hexdigest()}],
        "hash_algorithm": "SHA-256", "manifest_version": 1}
    return {"video.mp4": video, "event.json": science,
            "manifest.json": json.dumps(manifest, separators=(",", ":")).encode()}
