"""Synthetic-only hosted gateway acceptance. Tokens stay in external files, never output."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import ssl
import socket
import sys
import uuid

BASE = "promaxgb10-4e2b.tail06a565.ts.net"
POLICY = "synthetic-checkpoint4-hosted-v1"


class ProbeFailure(Exception):
    pass


def failure_code(error):
    if isinstance(error, ssl.SSLCertVerificationError): return "TLS_VERIFICATION_FAILED"
    if isinstance(error, socket.gaierror): return "DNS_LOOKUP_FAILED"
    if isinstance(error, TimeoutError): return "NETWORK_TIMEOUT"
    if isinstance(error, ConnectionRefusedError): return "CONNECTION_REFUSED"
    if isinstance(error, http.client.RemoteDisconnected): return "SERVER_DISCONNECTED"
    if isinstance(error, ConnectionResetError): return "CONNECTION_RESET"
    if isinstance(error, FileNotFoundError): return "LOCAL_FILE_MISSING"
    if isinstance(error, PermissionError): return "LOCAL_FILE_PERMISSION_DENIED"
    if isinstance(error, json.JSONDecodeError): return "LOCAL_JSON_INVALID"
    if isinstance(error, ssl.SSLError): return "TLS_CONNECTION_FAILED"
    if isinstance(error, OSError): return "IO_FAILED"
    if isinstance(error, (KeyError, TypeError, ValueError)): return "PROFILE_OR_RESPONSE_INVALID"
    return "IO_OR_CONFIGURATION_FAILED"


def check(condition, code):
    if not condition:
        raise ProbeFailure(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def protected_write(path, value):
    check(not path.is_symlink(), "UNSAFE_OUTPUT")
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(value, output, indent=2)
        output.flush(); os.fsync(output.fileno())


def credentials(path):
    check(not path.is_symlink(), "UNSAFE_CREDENTIAL_FILE")
    value = json.loads(path.read_text(encoding="utf-8"))
    check(value.get("mode") == "SYNTHETIC_ONLY" and value.get("hostname") == BASE, "WRONG_CREDENTIAL_PROFILE")
    for name in ("a", "b"):
        item = value[name]
        check(str(uuid.UUID(item["installation_id"])) == item["installation_id"], "INVALID_INSTALLATION")
        import re
        check(re.fullmatch(r"[A-Za-z0-9_-]{43}", item["token"]) is not None, "INVALID_TOKEN_FORMAT")
    return value


def admin(config_file, credential_file, action):
    from ecocapture_gateway.config import Config
    from ecocapture_gateway.store import Store
    raw = json.loads(config_file.read_text(encoding="utf-8"))
    check(raw.get("storage") == "/gateway-state/private" and raw.get("allowed_hosts") == [BASE], "WRONG_HOST_CONFIGURATION")
    check(credential_file.absolute().parent == Path("/gateway-state"), "CREDENTIAL_OUTPUT_MUST_BE_PRIVATE_STATE")
    raw["storage"] = Path(raw["storage"]); raw["allowed_hosts"] = tuple(raw["allowed_hosts"])
    store = Store(Config(**raw))
    if action == "provision":
        check(not credential_file.exists(), "CREDENTIAL_FILE_ALREADY_EXISTS")
        value = {"mode": "SYNTHETIC_ONLY", "hostname": BASE}
        issued = []
        try:
            for name in ("a", "b"):
                installation = str(uuid.uuid4())
                token = store.issue(installation, POLICY, eligible=True)
                issued.append(installation)
                value[name] = {"installation_id": installation, "token": token}
            protected_write(credential_file, value)
        except BaseException:
            for installation in issued: store.revoke(installation)
            raise
        return {"status": "SYNTHETIC_A_B_CREDENTIALS_WRITTEN", "tokens_printed": False}
    value = credentials(credential_file)
    # Only act on this explicitly provisioned synthetic profile, never arbitrary installations.
    for name in ("a", "b"):
        with store.connect() as db:
            row = db.execute("SELECT * FROM installations WHERE id=?", (value[name]["installation_id"],)).fetchone()
        check(row is not None and row["policy"] == POLICY and row["token_hash"] == digest(value[name]["token"].encode()), "WRONG_ADMIN_SCOPE")
    for name in (("a",) if action == "revoke-a" else ("a", "b")):
        store.revoke(value[name]["installation_id"])
    return {"status": "SYNTHETIC_A_REVOKED" if action == "revoke-a" else "SYNTHETIC_A_B_REVOKED"}


class HttpsRequests:
    def __call__(self, method, path, token=None, body=None, headers=None):
        headers = dict(headers or {})
        if token: headers["Authorization"] = "Bearer " + token
        operation = "HEALTH" if path == "/v1/health" else "TRANSFER_CREATE" if path == "/v1/transfers" else "CHUNK" if "/chunks/" in path else "TRANSFER_CONTROL"
        connection = http.client.HTTPSConnection(BASE, context=ssl.create_default_context(), timeout=30)
        try:
            try:
                connection.request(method, path, body=body, headers=headers)
            except BrokenPipeError:
                # A proxy can send an early 413 while closing the oversized request body.
                # Read that response if available; never count a disconnect alone as PASS.
                pass
            response = connection.getresponse()
            data = response.read(32769)
            check(len(data) <= 32768, "RESPONSE_LIMIT")
            try: value = json.loads(data)
            except (ValueError, UnicodeError): value = {}
            return response.status, value
        except ProbeFailure:
            raise
        except Exception as error:
            raise ProbeFailure(failure_code(error) + "_" + operation) from None
        finally:
            connection.close()


def fixture(installation, name):
    event_id = str(uuid.uuid5(uuid.UUID(installation), "synthetic-hosted-event-" + name))
    snapshot = str(uuid.uuid5(uuid.UUID(installation), "synthetic-hosted-snapshot-" + name))
    # Structural MP4-shaped synthetic bytes; not playable footage and no real recording.
    video = b"\x00\x00\x00\x10ftypisom0000" + b"\x00\x00\x00\x09moovx"
    video += (131080).to_bytes(4, "big") + b"mdat" + b"x" * 131072
    event = {"schema": "ecocapture.event.v1", "event_id": event_id, "clip_id": event_id,
        "snapshot_id": snapshot, "previous_snapshot_id": None, "study_participant_code": None,
        "capture": {"created_at_epoch_ms": 1000, "duration_ms": 1000, "width": 320, "height": 180,
                    "sample_count": 2, "created_by_app_version": "synthetic-hosted-probe"},
        "review": {"review_state": "REVIEWED", "approval_state": "APPROVED", "approved_at_epoch_ms": None,
                   "updated_at_epoch_ms": 2000, "snapshot_created_at_epoch_ms": 3000},
        "participant_annotations": {"current_annotation_id": None, "revisions": []}, "vlm_runs": [],
        "export": {"schema_version": 1, "app_version": "synthetic-hosted-probe", "exported_at_epoch_ms": 3000}}
    science = json.dumps(event, separators=(",", ":")).encode()
    manifest = {"schema": "ecocapture.manifest.v1", "event_id": event_id, "snapshot_id": snapshot,
        "created_at_epoch_ms": 3000, "files": [
            {"path": "video.mp4", "size_bytes": len(video), "sha256": digest(video)},
            {"path": "event.json", "size_bytes": len(science), "sha256": digest(science)}],
        "hash_algorithm": "SHA-256", "manifest_version": 1}
    files = {"video.mp4": video, "event.json": science, "manifest.json": json.dumps(manifest, separators=(",", ":")).encode()}
    body = {"schema": "ecocapture.gateway.transfer.v1", "snapshot_id": snapshot, "event_id": event_id,
            "objects": [{"name": key, "size_bytes": len(data), "sha256": digest(data)} for key, data in files.items()]}
    return body, files


def create(request, token, body):
    status, result = request("POST", "/v1/transfers", token, json.dumps(body).encode(),
                             {"Content-Type": "application/json", "Idempotency-Key": body["snapshot_id"]})
    check(status == 201, "CREATE_FAILED_HTTP_" + str(status))
    return result


def chunk(request, token, transfer, name, offset, data, expected=200, sha=None):
    status, result = request("PUT", f"/v1/transfers/{transfer}/objects/{name}/chunks/{offset}", token,
        data, {"Content-Type": "application/octet-stream", "X-Chunk-SHA256": sha or digest(data)})
    check(status == expected, "CHUNK_UNEXPECTED_HTTP_" + str(status))
    return result


def initial(request, value, progress):
    check(not progress.exists(), "PROGRESS_ALREADY_EXISTS_USE_RESUME")
    a, b = value["a"], value["b"]
    status, health = request("GET", "/v1/health")
    check(status == 200 and health == {"status": "ok", "transport": "mock"}, "HTTPS_HEALTH_FAILED")
    body, files = fixture(a["installation_id"], "a")
    first = create(request, a["token"], body)
    transfer = first["gateway_transfer_id"]
    check(create(request, a["token"], body)["gateway_transfer_id"] == transfer, "DUPLICATE_CREATE")
    check(request("GET", f"/v1/transfers/{transfer}")[0] == 401, "UNAUTHENTICATED_NOT_REJECTED")
    for method, suffix in (("GET", ""), ("POST", "/complete"), ("POST", "/cancel"), ("GET", "/receipt")):
        check(request(method, f"/v1/transfers/{transfer}" + suffix, b["token"])[0] == 404, "OWNER_ISOLATION_FAILED")
    size = min(first["max_chunk_bytes"], 65536)
    check(size >= 65536, "HOST_CHUNK_LIMIT_TOO_SMALL")
    chunk(request, b["token"], transfer, "video.mp4", 0, files["video.mp4"][:size], expected=404)
    accepted = chunk(request, a["token"], transfer, "video.mp4", 0, files["video.mp4"][:size])
    check(accepted["accepted_bytes"] == size, "INITIAL_OFFSET_WRONG")
    replay = chunk(request, a["token"], transfer, "video.mp4", 0, files["video.mp4"][:size])
    check(replay["replayed"] and replay["accepted_bytes"] == size, "CHUNK_REPLAY_FAILED")
    changed = b"z" + files["video.mp4"][1:size]
    conflict = chunk(request, a["token"], transfer, "video.mp4", 0, changed, expected=409)
    check(conflict.get("error", {}).get("accepted_bytes") == size, "CONFLICT_OFFSET_WRONG")
    chunk(request, a["token"], transfer, "video.mp4", size, files["video.mp4"][size:2*size], expected=400, sha="0" * 64)
    check(request("POST", f"/v1/transfers/{transfer}/complete", a["token"])[0] == 409, "INCOMPLETE_ACCEPTED")
    oversized = b"x" * (first["max_chunk_bytes"] + 1)
    status, _ = request("PUT", f"/v1/transfers/{transfer}/objects/video.mp4/chunks/{size}", a["token"],
        oversized, {"Content-Type": "application/octet-stream", "X-Chunk-SHA256": digest(oversized)})
    check(status == 413, "DECLARED_BODY_LIMIT_FAILED")
    invalid = json.loads(json.dumps(body))
    invalid["objects"][0]["name"] = "../video.mp4"
    status, _ = request("POST", "/v1/transfers", a["token"], json.dumps(invalid).encode(),
        {"Content-Type": "application/json", "Idempotency-Key": body["snapshot_id"]})
    check(status == 400, "PATH_TRAVERSAL_NOT_REJECTED")
    body_b, _ = fixture(b["installation_id"], "b")
    transfer_b = create(request, b["token"], body_b)["gateway_transfer_id"]
    protected_write(progress, {"hostname": BASE, "a_transfer": transfer, "b_transfer": transfer_b,
                               "paused_bytes": size})
    return {"status": "HOSTED_UPLOAD_PAUSED", "accepted_bytes": size,
            "checks": ["verified_https", "idempotent_create", "unauthenticated_rejected", "owner_isolation",
                       "chunk_replay", "conflict_offset", "bad_chunk_hash", "incomplete_rejected",
                       "oversized_complete_body", "path_traversal_rejected"],
            "next": "restart_gateway_then_resume", "filesender_calls": 0}


def resume(request, value, progress):
    a = value["a"]
    saved = json.loads(progress.read_text())
    transfer = saved["a_transfer"]
    status, row = request("GET", f"/v1/transfers/{transfer}", a["token"])
    check(status == 200 and row["state"] == "RECEIVING", "RESUME_STATE_FAILED")
    offsets = {o["name"]: o["accepted_bytes"] for o in row["objects"]}
    check(offsets["video.mp4"] == saved["paused_bytes"], "PERSISTED_OFFSET_FAILED")
    body, files = fixture(a["installation_id"], "a")
    for name, data in files.items():
        for offset in range(offsets[name], len(data), 65536):
            chunk(request, a["token"], transfer, name, offset, data[offset:offset+65536])
    status, receipt = request("POST", f"/v1/transfers/{transfer}/complete", a["token"])
    check(status == 200 and receipt.get("status") == "MOCK_FORWARDED" and receipt.get("mock") is True
          and receipt.get("archived") is False and receipt.get("manifest_sha256") == digest(files["manifest.json"]), "VERIFIED_RECEIPT_FAILED")
    check(request("POST", f"/v1/transfers/{transfer}/complete", a["token"]) == (200, receipt), "DUPLICATE_COMPLETE")
    check(request("GET", f"/v1/transfers/{transfer}/receipt", a["token"]) == (200, receipt), "RECEIPT_FAILED")
    # Prove complete rejects an incorrect whole-object expected hash, with all offsets full.
    invalid_body, invalid_files = fixture(a["installation_id"], "bad-whole-hash")
    for item in invalid_body["objects"]:
        if item["name"] == "video.mp4": item["sha256"] = "0" * 64
    invalid_transfer = create(request, a["token"], invalid_body)["gateway_transfer_id"]
    for name, data in invalid_files.items():
        for offset in range(0, len(data), 65536):
            chunk(request, a["token"], invalid_transfer, name, offset, data[offset:offset+65536])
    check(request("POST", f"/v1/transfers/{invalid_transfer}/complete", a["token"])[0] == 409, "WHOLE_HASH_MISMATCH_ACCEPTED")
    check(request("POST", f"/v1/transfers/{invalid_transfer}/cancel", a["token"])[0] == 200, "BAD_HASH_FIXTURE_CANCEL_FAILED")
    # Cancel our synthetic completed job to discard its gateway payload before A is revoked.
    status, cancelled = request("POST", f"/v1/transfers/{transfer}/cancel", a["token"])
    check(status == 200 and cancelled.get("remote_deletion_claimed") is False, "SYNTHETIC_CANCEL_FAILED")
    return {"status": "HOSTED_RESUME_AND_MOCK_RECEIPT_VERIFIED", "checks": ["persisted_offset", "resumed_upload",
            "manifest_hash", "mock_not_archived", "duplicate_complete", "receipt_readback",
            "whole_hash_mismatch_rejected", "local_cancel"], "filesender_calls": 0}


def revoked(request, value, progress):
    saved = json.loads(progress.read_text())
    check(request("GET", f"/v1/transfers/{saved['a_transfer']}", value["a"]["token"])[0] == 403, "A_REVOCATION_FAILED")
    check(request("GET", f"/v1/transfers/{saved['b_transfer']}", value["b"]["token"])[0] == 200, "B_INDEPENDENCE_FAILED")
    status, result = request("POST", f"/v1/transfers/{saved['b_transfer']}/cancel", value["b"]["token"])
    check(status == 200 and result.get("remote_deletion_claimed") is False, "B_CANCEL_FAILED")
    return {"status": "HOSTED_INDEPENDENT_REVOCATION_VERIFIED", "filesender_calls": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("provision", "pause", "resume", "revoke-a", "revoked", "retire"))
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path("/gateway-config/gateway.json"))
    parser.add_argument("--progress", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    if args.action in {"provision", "revoke-a", "retire"}:
        result = admin(args.config, args.credentials, args.action)
    else:
        progress = args.progress or args.credentials.with_name("hosted-probe-progress.json")
        value = credentials(args.credentials)
        result = {"pause": initial, "resume": resume, "revoked": revoked}[args.action](HttpsRequests(), value, progress)
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print(json.dumps({"status": "HOSTED_PROBE_FAILED", "code": str(error)})); raise SystemExit(1)
    except Exception as error:
        print(json.dumps({"status": "HOSTED_PROBE_FAILED", "code": failure_code(error)})); raise SystemExit(1)
