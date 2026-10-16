"""Explicit one-shot synthetic FileSender test. No automatic retries.

Accepts the two fixed plaintext fixtures or the one exact reviewed age artifact.
"""
import argparse
import hashlib
import hmac
import json
from pathlib import Path
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from checkpoint1_auth_probe import API, MAX_RESPONSE_BYTES, NoRedirect, ProbeFailure, load_credentials
from checkpoint1_probe import NAMES, digest


EXPECTED = {
    NAMES[0]: {"size_bytes": 71, "sha256": "104b5784640475d8e6b1748d10f5f7ca567795bee6134fff054fdfb9176475f6"},
    NAMES[1]: {"size_bytes": 12582929, "sha256": "2035167438838a59533e30d07fdda4ecd0ea8a3f14fa41fa255761761fa6ad76"},
}
RECIPIENT = "raymond.chia@uts.edu.au"
ENCRYPTED_EXPECTED = {"synthetic-files.tar.age": {
    "size_bytes": 12598472,
    "sha256": "4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889",
}}


def request(username, secret, method, path, parameters=None, payload=None, raw=None):
    arguments = dict(parameters or {})
    arguments.update(remote_user=username, timestamp=str(round(time.time())))
    body = json.dumps(payload, separators=(",", ":")).encode("ascii") if payload is not None else raw
    canonical = f"{method}&{API.removeprefix('https://')}{path}?" + "&".join(
        f"{key}={arguments[key]}" for key in sorted(arguments))
    signed = canonical.encode("ascii") + (b"&" + body if body is not None else b"")
    arguments["signature"] = hmac.new(secret.encode("ascii"), signed, hashlib.sha1).hexdigest()
    query = "&".join(urllib.parse.quote(key) + "=" + urllib.parse.quote(arguments[key]) for key in sorted(arguments))
    return urllib.request.Request(API + path + "?" + query, data=body, method=method.upper(),
        headers={"Accept": "application/json", "Content-Type": "application/octet-stream" if raw is not None else "application/json"})


class Client:
    def __init__(self, config):
        self.username, self.secret = load_credentials(config)
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def call(self, method, path, parameters=None, payload=None, raw=None):
        outgoing = request(self.username, self.secret, method, path, parameters, payload, raw)
        try:
            with self.opener.open(outgoing, timeout=120) as response:
                if response.geturl() != outgoing.full_url:
                    raise ProbeFailure("REDIRECT_REJECTED")
                if response.status != (201 if method == "post" else 200):
                    raise ProbeFailure("UNEXPECTED_HTTP_STATUS")
                body = response.read(MAX_RESPONSE_BYTES + 1)
                if len(body) > MAX_RESPONSE_BYTES:
                    raise ProbeFailure("RESPONSE_TOO_LARGE")
            result = json.loads(body)
            if result is False or result is None:
                raise ProbeFailure("REMOTE_OPERATION_NOT_ACKNOWLEDGED")
            return result
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise ProbeFailure(f"REMOTE_HTTP_{status}") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ProbeFailure("NETWORK_OR_TLS_FAILURE") from None
        except (ValueError, UnicodeError):
            raise ProbeFailure("INVALID_JSON_RESPONSE") from None


def save(directory, evidence):
    temporary = directory / "receipt.tmp"
    temporary.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    temporary.replace(directory / "receipt.json")


def identifier(value):
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]*", str(value)):
        raise ProbeFailure("INVALID_REMOTE_IDENTIFIER")
    return int(value)


def send(client, fixtures, directory, recipient, days, encrypted=False):
    if recipient != RECIPIENT or days != 5:
        raise ProbeFailure("UNAPPROVED_TEST_DESTINATION_OR_EXPIRY")
    expected_files = ENCRYPTED_EXPECTED if encrypted else EXPECTED
    names = tuple(expected_files)
    for name, expected in expected_files.items():
        source = fixtures / name
        if source.is_symlink() or not source.is_file() or digest(source) != expected:
            raise ProbeFailure("SYNTHETIC_SOURCE_MISMATCH")
    # Existing job directories are refused: never blindly create a second transfer.
    try:
        directory.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        raise ProbeFailure("EXISTING_JOB_REQUIRES_RECONCILIATION") from None
    run_id = str(uuid.uuid4())
    expires = round(time.time()) + days * 86400
    evidence = {"schema": "ecocapture.checkpoint1.transfer.v1", "test_id": run_id,
                "status": "PREPARING", "recipient": recipient, "requested_expiry_at_epoch_ms": expires * 1000,
                "files": expected_files, "participant_data": False, "archived": False,
                "transport_encrypted": encrypted}
    save(directory, evidence)
    try:
        info = client.call("get", "/info")
        chunk_size = info.get("upload_chunk_size") if isinstance(info, dict) else None
        if type(chunk_size) is not int or not 0 < chunk_size <= 64 * 1024 * 1024:
            raise ProbeFailure("INVALID_CHUNK_CONFIGURATION")
        evidence.update(status="CREATION_REQUESTED", upload_chunk_size=chunk_size)
        save(directory, evidence)  # Mark intent before POST; a lost response is not retried.
        transfer = client.call("post", "/transfer", payload={
            "from": client.username if "@" in client.username else recipient,
            "files": [{"name": name, "size": expected_files[name]["size_bytes"]} for name in names],
            "recipients": [recipient], "subject": f"EcoCapture synthetic test {run_id}",
            "message": "Harmless engineering files only. No participant data. Please verify SHA-256 hashes.",
            "expires": expires, "aup_checked": 1, "options": {"get_a_link": 0}})
        if not isinstance(transfer, dict):
            raise ProbeFailure("INVALID_TRANSFER_RESPONSE")
        transfer_id = identifier(transfer.get("id"))
        evidence.update(status="UPLOADING", filesender_transfer_id=transfer_id)
        save(directory, evidence)  # Persist ID immediately; never store remote keys/tokens.
        remote_files = transfer.get("files")
        if not isinstance(remote_files, list) or len(remote_files) != len(names):
            raise ProbeFailure("UNEXPECTED_REMOTE_FILES")
        remote_by_name = {file.get("name"): file for file in remote_files if isinstance(file, dict)}
        if set(remote_by_name) != set(names):
            raise ProbeFailure("UNEXPECTED_REMOTE_FILES")
        for name in names:
            file = remote_by_name[name]
            if int(file.get("size", -1)) != expected_files[name]["size_bytes"]:
                raise ProbeFailure("REMOTE_FILE_SIZE_MISMATCH")
            file_id = identifier(file.get("id"))
            parameters = {"key": file["puid"], "roundtriptoken": transfer["roundtriptoken"]}
            with (fixtures / name).open("rb") as stream:
                offset = 0
                while block := stream.read(chunk_size):
                    client.call("put", f"/file/{file_id}/chunk/{offset}", parameters, raw=block)
                    offset += len(block)
            if digest(fixtures / name) != expected_files[name]:
                raise ProbeFailure("SYNTHETIC_SOURCE_CHANGED")
            client.call("put", f"/file/{file_id}", parameters, payload={"complete": True})
        evidence["status"] = "FINALIZATION_REQUESTED"
        save(directory, evidence)
        acknowledgment = client.call("put", f"/transfer/{transfer_id}", {"key": remote_by_name[names[0]]["puid"]}, payload={"complete": True})
        if not isinstance(acknowledgment, dict) or identifier(acknowledgment.get("id")) != transfer_id:
            raise ProbeFailure("FINALIZATION_NOT_ACKNOWLEDGED")
        evidence["completion_acknowledged_at_epoch_ms"] = round(time.time() * 1000)
        save(directory, evidence)
        remote = client.call("get", f"/transfer/{transfer_id}")
        # Actual AARNet owner responses omit status. Acknowledged completion plus
        # matching owner metadata establishes API completion; download hashes
        # are a separate check. A present non-available status still fails.
        if not isinstance(remote, dict) or identifier(remote.get("id")) != transfer_id or remote.get("status", "available") != "available":
            raise ProbeFailure("FINALIZATION_NOT_VERIFIED")
        confirmed_files = remote.get("files")
        if not isinstance(confirmed_files, list) or len(confirmed_files) != len(names) or {
            file.get("name"): int(file.get("size", -1)) for file in confirmed_files if isinstance(file, dict)
        } != {name: expected_files[name]["size_bytes"] for name in names}:
            raise ProbeFailure("FINALIZATION_FILES_MISMATCH")
        expiry = remote.get("expires")
        expiry = expiry.get("raw") if isinstance(expiry, dict) else expiry
        if type(expiry) is not int or expiry <= round(time.time()):
            raise ProbeFailure("INVALID_ACKNOWLEDGED_EXPIRY")
        evidence.update(status="FILESENDER_COMPLETE", filesender_expiry_at_epoch_ms=expiry * 1000,
                        completed_at_epoch_ms=round(time.time() * 1000), researcher_verified=False)
        save(directory, evidence)
        return evidence
    except Exception:
        evidence["status"] = "NEEDS_RECONCILIATION"
        save(directory, evidence)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--job", required=True, type=Path)
    parser.add_argument("--recipient", required=True)
    parser.add_argument("--days", required=True, type=int)
    parser.add_argument("--encrypted", action="store_true", help="Send only the exact reviewed synthetic age ciphertext")
    args = parser.parse_args()
    try:
        result = send(Client(args.config), args.fixtures, args.job, args.recipient, args.days, args.encrypted)
    except ProbeFailure as error:
        print(json.dumps({"status": "FAILED", "safe_error_code": str(error)}))
        return 1
    except Exception:
        print(json.dumps({"status": "FAILED", "safe_error_code": "UNEXPECTED_TEST_FAILURE"}))
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
