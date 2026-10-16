"""Checkpoint 5 operator actions. No automatic upload or credential provisioning."""
import argparse
import json
import os
from pathlib import Path
import sys
import uuid

from .app import verify_snapshot
from .config import Config
from .contract import creation
from .errors import GatewayError, require
from .filesender_adapter import FileSenderClient
from .forwarding import Forwarder, SYNTHETIC_POLICY
from .store import Store
from .synthetic_fixture import files
from .archive_encryptor import measure


def load_store(config_file):
    raw = json.loads(config_file.read_text(encoding="utf-8"))
    raw["storage"] = Path(raw["storage"]); raw["allowed_hosts"] = tuple(raw["allowed_hosts"])
    return Store(Config(**raw))


def synthetic(store):
    owner = str(uuid.uuid4()); event = str(uuid.uuid4()); snapshot = str(uuid.uuid4())
    # Never expose/use this bearer externally: this is an explicit local operator fixture.
    store.issue(owner, SYNTHETIC_POLICY, eligible=True)
    content = files(event, snapshot)
    import hashlib
    body = {"schema": "ecocapture.gateway.transfer.v1", "snapshot_id": snapshot, "event_id": event,
        "objects": [{"name": name, "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                    for name, data in content.items()]}
    body, fingerprint = creation(body, snapshot, store.config)
    transfer = store.create(owner, body, fingerprint)["gateway_transfer_id"]
    for name, data in content.items():
        for offset in range(0, len(data), store.config.max_chunk_bytes):
            block = data[offset:offset+store.config.max_chunk_bytes]
            temporary = store.begin_chunk(owner, transfer, name)
            try:
                with temporary.open("xb") as stream: stream.write(block); stream.flush(); os.fsync(stream.fileno())
                store.commit_chunk(owner, transfer, name, offset, temporary, len(block), hashlib.sha256(block).hexdigest())
            finally: temporary.unlink(missing_ok=True)
    store.complete(owner, transfer, verify_snapshot)
    return transfer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("prepare-synthetic", "prepare", "send", "status", "discard", "reconcile", "auth-check", "retry-creation"):
        command = sub.add_parser(action)
        if action not in ("prepare-synthetic", "auth-check"): command.add_argument("--transfer-id", required=True)
        if action in ("prepare-synthetic", "prepare"):
            command.add_argument("--age-tool", type=Path, required=True)
            command.add_argument("--age-recipient", required=True)
            command.add_argument("--key-version", required=True)
        if action in ("prepare-synthetic", "prepare"): command.add_argument("--evidence-out", type=Path, required=True)
        if action in ("send", "reconcile", "auth-check", "retry-creation"):
            command.add_argument("--filesender-config", type=Path, required=True)
            command.add_argument("--authorize-synthetic-filesender", action="store_true")
            command.add_argument("--credential-policy", choices=("rotated", "accepted-engineering-exception"), required=True)
            command.add_argument("--use-filesender-time", action="store_true",
                                 help="Use bounded verified-HTTPS server time for this worker only")
        if action == "retry-creation":
            command.add_argument("--accept-possible-orphan", action="store_true")
            command.add_argument("--expected-ciphertext-sha256", required=True)
        if action == "reconcile":
            choice = command.add_mutually_exclusive_group(required=True)
            choice.add_argument("--adopt-remote-transfer", type=int)
            choice.add_argument("--confirm-remote-available", action="store_true")
    args = parser.parse_args(); os.umask(0o077)
    if args.action == "auth-check":
        client = authorized_client(args)
        transfers = client.call("get", "/transfer/@me")
        require(type(transfers) is list and all(type(item) is dict for item in transfers),
                502, "OWNER_LIST_FORMAT_UNEXPECTED")
        print(json.dumps({"status": "SIGNED_READ_SUCCEEDED", "transfer_mutations": 0,
                          "host_clock_changed": False, "uses_filesender_time": args.use_filesender_time}))
        return
    store = load_store(args.config); forwarder = Forwarder(store)
    if args.action == "prepare-synthetic":
        require(not args.evidence_out.exists() and not args.evidence_out.is_symlink(), 409, "EVIDENCE_OUTPUT_ALREADY_EXISTS")
        # Reserve the evidence file before allocating a new synthetic transfer.
        with args.evidence_out.open("x", encoding="utf-8") as output:
            json.dump({"status": "SYNTHETIC_CREATION_PENDING", "participant_data": False}, output)
        transfer = synthetic(store)
        args.evidence_out.write_text(json.dumps({"status": "SYNTHETIC_SNAPSHOT_VERIFIED", "gateway_transfer_id": transfer,
                                                "participant_data": False}) + "\n", encoding="utf-8")
    else: transfer = args.transfer_id
    if args.action in ("prepare", "prepare-synthetic"):
        if args.action == "prepare":
            require(not args.evidence_out.exists() and not args.evidence_out.is_symlink(), 409, "EVIDENCE_OUTPUT_ALREADY_EXISTS")
            with args.evidence_out.open("x", encoding="utf-8") as output:
                json.dump({"status": "PREPARATION_PENDING", "gateway_transfer_id": transfer, "participant_data": False}, output)
        result = forwarder.prepare(transfer, args.age_tool, args.age_recipient, args.key_version)
        with store.connect() as db:
            row = db.execute("SELECT * FROM forwarding_jobs WHERE transfer_id=?", (transfer,)).fetchone()
        evidence = {**result, "files": json.loads(row["objects_json"]), "policy": json.loads(row["policy"]),
                    "participant_data": False}
        args.evidence_out.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    elif args.action == "status": result = forwarder.info(transfer)
    elif args.action == "discard": result = forwarder.discard(transfer)
    else:
        # Only this explicit path reads a separately approved external credential file.
        client = authorized_client(args)
        if args.action == "send": result = forwarder.send(transfer, client)
        elif args.action == "retry-creation":
            result = forwarder.authorize_creation_retry(transfer, client, args.expected_ciphertext_sha256,
                                                        args.accept_possible_orphan)
        else: result = forwarder.reconcile(transfer, client, args.adopt_remote_transfer, args.confirm_remote_available)
    print(json.dumps(result, indent=2))


def authorized_client(args):
    require(args.authorize_synthetic_filesender, 403, "EXPLICIT_SYNTHETIC_TRANSFER_AUTHORIZATION_REQUIRED")
    return FileSenderClient(args.filesender_config, live_authorized=True,
                            use_server_time=args.use_filesender_time)


if __name__ == "__main__":
    try: main()
    except GatewayError as error:
        print(json.dumps({"status": "CHECKPOINT5_ACTION_FAILED", "code": error.code})); raise SystemExit(1)
    except Exception:
        print('{"status":"CHECKPOINT5_ACTION_FAILED","code":"LOCAL_OR_CONFIGURATION_FAILURE"}'); raise SystemExit(1)
