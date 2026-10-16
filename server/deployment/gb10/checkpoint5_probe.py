"""Provision/upload/retire the one exact Checkpoint 5 public-HTTPS synthetic fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid

from hosted_probe import BASE, HttpsRequests, ProbeFailure, check, failure_code, protected_write, chunk, create


def profile(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    check(value.get("mode") == "CHECKPOINT5_SYNTHETIC_ONLY" and value.get("hostname") == BASE, "WRONG_CHECKPOINT5_PROFILE")
    check(str(uuid.UUID(value["installation_id"])) == value["installation_id"], "INVALID_INSTALLATION")
    import re
    check(re.fullmatch(r"[A-Za-z0-9_-]{43}", value["token"]) is not None, "INVALID_TOKEN_FORMAT")
    return value


def admin(path, action):
    from ecocapture_gateway.forward_cli import load_store
    from ecocapture_gateway.forwarding import SYNTHETIC_POLICY
    check(path.absolute() == Path("/gateway-state/checkpoint5-credentials.json"), "PRIVATE_CREDENTIAL_PATH_REQUIRED")
    store = load_store(Path("/gateway-config/gateway.json"))
    check(store.config.allowed_hosts == (BASE,) and store.root == Path("/gateway-state/private"), "WRONG_HOST_CONFIGURATION")
    if action == "provision":
        check(not path.exists(), "CHECKPOINT5_ALREADY_PROVISIONED")
        installation = str(uuid.uuid4())
        token = store.issue(installation, SYNTHETIC_POLICY, eligible=True)
        try:
            protected_write(path, {"mode": "CHECKPOINT5_SYNTHETIC_ONLY", "hostname": BASE,
                                   "installation_id": installation, "token": token})
        except BaseException: store.revoke(installation); raise
        return {"status": "CHECKPOINT5_SYNTHETIC_CREDENTIAL_WRITTEN", "tokens_printed": False}
    value = profile(path)
    with store.connect() as db:
        row = db.execute("SELECT * FROM installations WHERE id=?", (value["installation_id"],)).fetchone()
    check(row is not None and row["policy"] == SYNTHETIC_POLICY and
          row["token_hash"] == hashlib.sha256(value["token"].encode()).hexdigest(), "WRONG_ADMIN_SCOPE")
    store.revoke(value["installation_id"])
    return {"status": "CHECKPOINT5_SYNTHETIC_CREDENTIAL_REVOKED"}


def upload(request, value):
    from ecocapture_gateway.synthetic_fixture import files
    event = str(uuid.uuid5(uuid.UUID(value["installation_id"]), "checkpoint5-event"))
    snapshot = str(uuid.uuid5(uuid.UUID(value["installation_id"]), "checkpoint5-snapshot"))
    content = files(event, snapshot)
    body = {"schema": "ecocapture.gateway.transfer.v1", "event_id": event, "snapshot_id": snapshot,
            "objects": [{"name": name, "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                        for name, data in content.items()]}
    status, health = request("GET", "/v1/health")
    check(status == 200 and health == {"status": "ok", "transport": "mock"}, "MOCK_GATEWAY_HEALTH_REQUIRED")
    row = create(request, value["token"], body)
    transfer = row["gateway_transfer_id"]
    if row["state"] == "RECEIVING":
        offsets = {o["name"]: o["accepted_bytes"] for o in row["objects"]}
        size = min(row["max_chunk_bytes"], 65536)
        for name, data in content.items():
            for offset in range(offsets[name], len(data), size):
                chunk(request, value["token"], transfer, name, offset, data[offset:offset+size])
        status, result = request("POST", f"/v1/transfers/{transfer}/complete", value["token"])
        check(status == 200 and result.get("mock") is True, "MOCK_SNAPSHOT_VERIFICATION_FAILED")
    else:
        check(row["state"] in ("MOCK_FORWARDED", "FORWARDING_PENDING", "FILESENDER_COMPLETE"), "UNEXPECTED_SNAPSHOT_STATE")
    return {"status": "CHECKPOINT5_SYNTHETIC_SNAPSHOT_UPLOADED", "gateway_transfer_id": transfer,
            "snapshot_id": snapshot, "files": body["objects"], "filesender_calls": 0}


def main():
    # Client path needs only stdlib; production API dependencies are imported only by admin actions.
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("provision", "upload", "retire"))
    parser.add_argument("--credentials", type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    result = upload(HttpsRequests(), profile(args.credentials)) if args.action == "upload" else admin(args.credentials, args.action)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try: main()
    except ProbeFailure as error:
        print(json.dumps({"status": "CHECKPOINT5_PROBE_FAILED", "code": str(error)})); raise SystemExit(1)
    except Exception as error:
        print(json.dumps({"status": "CHECKPOINT5_PROBE_FAILED", "code": failure_code(error)})); raise SystemExit(1)
