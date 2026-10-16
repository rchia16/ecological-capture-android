"""Explicit synthetic-only archive/encrypt/forward jobs. No autonomous external sending."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import time

from .app import verify_snapshot
from .archive_encryptor import deterministic_tar, age_encrypt, measure, validate_recipient
from .contract import canonical_uuid
from .errors import GatewayError, require
from .filesender_adapter import identifier

SYNTHETIC_POLICY = "synthetic-checkpoint5-v1"
RECIPIENT = "raymond.chia@uts.edu.au"
NAME = "approved-event.tar.age"
MAX_ARTIFACT = 300 * 1024 * 1024


def receipt(db, transfer):
    row = db.execute("SELECT * FROM forwarding_jobs WHERE transfer_id=?", (transfer["id"],)).fetchone()
    require(row is not None and row["completion_ack"] is not None, 409, "REMOTE_COMPLETION_NOT_ACKNOWLEDGED")
    return {"schema": "ecocapture.gateway.filesender-receipt.v1", "gateway_transfer_id": transfer["id"],
        "snapshot_id": transfer["snapshot_id"], "status": "FILESENDER_COMPLETE", "mock": False,
        "filesender_transfer_id": row["remote_id"], "expires_at_epoch_ms": row["expiry_ms"],
        "manifest_sha256": transfer["manifest_hash"], "archive_sha256": row["archive_sha"],
        "encryption": "age-v1.3.2", "recipient_key_version": json.loads(row["policy"])["age_key_version"],
        "ciphertext_sha256": row["cipher_sha"], "ciphertext_size_bytes": row["cipher_size"],
        "plaintext_staging_removed": bool(row["plaintext_removed"]), "researcher_verified": False,
        "archived": False}


class Forwarder:
    def __init__(self, store):
        self.store = store
        self.root = store.root / "forwarding"
        self.root.mkdir(exist_ok=True, mode=0o700)
        require(not self.root.is_symlink(), 500, "UNSAFE_FORWARDING_ROOT")
        self.locks = store.root / "forward-locks"
        self.locks.mkdir(exist_ok=True, mode=0o700)
        require(not self.locks.is_symlink(), 500, "UNSAFE_FORWARDING_LOCKS")

    def directory(self, transfer_id):
        canonical_uuid(transfer_id)
        path = self.root / transfer_id
        require(not path.is_symlink() and path.resolve() == path.absolute(), 500, "UNSAFE_FORWARDING_PATH")
        return path

    @contextmanager
    def lock(self, transfer_id):
        canonical_uuid(transfer_id)
        path = self.locks / (transfer_id + ".lock")
        require(not path.is_symlink(), 500, "UNSAFE_FORWARDING_LOCK")
        with path.open("a+b") as stream:
            if path.stat().st_size == 0: stream.write(b"0"); stream.flush()
            try:
                if os.name == "nt":
                    import msvcrt
                    stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise GatewayError(409, "FORWARDING_JOB_BUSY") from None
            try: yield
            finally:
                if os.name == "nt":
                    stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)

    def row(self, db, transfer_id):
        canonical_uuid(transfer_id)
        row = db.execute("SELECT * FROM forwarding_jobs WHERE transfer_id=?", (transfer_id,)).fetchone()
        require(row is not None, 404, "FORWARDING_JOB_NOT_FOUND")
        return row

    def update(self, transfer_id, **values):
        allowed = {"state", "archive_sha", "archive_size", "cipher_sha", "cipher_size", "remote_id", "file_id",
                   "next_offset", "completion_ack", "expiry_ms", "artifact_reserved", "plaintext_removed", "error_code"}
        require(set(values) <= allowed, 500, "INVALID_FORWARDING_UPDATE")
        values["updated"] = self.store.clock()
        with self.store.transaction() as db:
            self.row(db, transfer_id)
            db.execute("UPDATE forwarding_jobs SET " + ",".join(key + "=?" for key in values) + " WHERE transfer_id=?",
                       [*values.values(), transfer_id])

    def info(self, transfer_id):
        with self.store.connect() as db:
            row = self.row(db, transfer_id)
            return {"status": row["state"], "gateway_transfer_id": transfer_id,
                "archive_sha256": row["archive_sha"], "archive_size_bytes": row["archive_size"],
                "ciphertext_sha256": row["cipher_sha"], "ciphertext_size_bytes": row["cipher_size"],
                "filesender_transfer_id": row["remote_id"], "uploaded_bytes": row["next_offset"],
                "expires_at_epoch_ms": row["expiry_ms"],
                "encryption": "age-v1.3.2", "recipient_key_version": json.loads(row["policy"])["age_key_version"],
                "error_code": row["error_code"], "plaintext_staging_removed": bool(row["plaintext_removed"]),
                "researcher_verified": False, "archived": False}

    def prepare(self, transfer_id, age_tool, recipient, key_version, days=5):
        validate_recipient(recipient)
        require(type(key_version) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key_version) is not None)
        require(days == 5, 400, "SYNTHETIC_EXPIRY_MUST_BE_FIVE_DAYS")
        with self.lock(transfer_id):
            with self.store.transaction() as db:
                transfer = db.execute("SELECT * FROM transfers WHERE id=?", (transfer_id,)).fetchone()
                require(transfer is not None, 404, "NOT_FOUND")
                installation = self.store.installation(db, transfer["owner"])
                require(installation["policy"] == SYNTHETIC_POLICY and installation["study_code"] is None,
                        403, "ONLY_CHECKPOINT5_SYNTHETIC_INSTALLATIONS")
                existing = db.execute("SELECT * FROM forwarding_jobs WHERE transfer_id=?", (transfer_id,)).fetchone()
                policy = {"recipient": RECIPIENT, "age_recipient": recipient, "age_key_version": key_version,
                          "days": days, "synthetic_only": True}
                if existing:
                    frozen = json.loads(existing["policy"])
                    require(all(frozen[k] == v for k, v in policy.items()), 409, "FROZEN_FORWARDING_POLICY_CHANGED")
                    require(existing["state"] not in ("CANCELLED",), 409, "FORWARDING_CANCELLED")
                    if existing["cipher_sha"] is not None:
                        require(measure(self.directory(transfer_id) / NAME) ==
                            {"size_bytes": existing["cipher_size"], "sha256": existing["cipher_sha"]}, 409, "CIPHERTEXT_CHANGED")
                        return self.info(transfer_id)
                else:
                    require(transfer["state"] == "MOCK_FORWARDED", 409, "VERIFIED_SNAPSHOT_REQUIRED")
                    rows = db.execute("SELECT * FROM objects WHERE transfer_id=?", (transfer_id,)).fetchall()
                    expected = {r["name"]: {"sha256": r["sha"], "size_bytes": r["size"]} for r in rows}
                    from .synthetic_fixture import files
                    import hashlib
                    golden = files(transfer["event_id"], transfer["snapshot_id"])
                    require(expected == {name: {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                                          for name, data in golden.items()}, 403, "ONLY_EXACT_SYNTHETIC_FIXTURE")
                    reservation = 3 * sum(v["size_bytes"] for v in expected.values()) + 1024 * 1024
                    reserved = db.execute("SELECT t.owner,SUM(o.size) AS size FROM transfers t JOIN objects o ON o.transfer_id=t.id WHERE t.reserved=1 GROUP BY t.owner").fetchall()
                    other = db.execute("SELECT t.owner,f.artifact_reserved AS size FROM forwarding_jobs f JOIN transfers t ON t.id=f.transfer_id").fetchall()
                    require(reservation + sum(r["size"] for r in reserved) + sum(r["size"] for r in other) <= self.store.config.max_total_bytes and
                        reservation + sum(r["size"] for r in reserved if r["owner"] == transfer["owner"]) + sum(r["size"] for r in other if r["owner"] == transfer["owner"]) <= self.store.config.max_installation_bytes,
                        413, "FORWARDING_STORAGE_QUOTA")
                    require(shutil.disk_usage(self.store.root).free >= self.store.config.min_free_bytes + reservation + sum(r["size"] for r in reserved) + sum(r["size"] for r in other),
                            503, "FORWARDING_DISK_CAPACITY")
                    policy["expires_at"] = round(self.store.clock()) + days * 86400
                    db.execute("INSERT INTO forwarding_jobs(transfer_id,state,policy,objects_json,artifact_reserved,updated) VALUES(?,'PREPARING',?,?,?,?)",
                        (transfer_id, json.dumps(policy, sort_keys=True), json.dumps(expected, sort_keys=True), reservation, self.store.clock()))
                    db.execute("UPDATE transfers SET state='FORWARDING_PENDING',updated=? WHERE id=?", (self.store.clock(), transfer_id))
            directory = self.directory(transfer_id); directory.mkdir(exist_ok=True, mode=0o700)
            with self.store.connect() as db:
                row = self.row(db, transfer_id)
                expected = json.loads(row["objects_json"])
            # Uncommitted preparation is safe to rebuild only before any external creation.
            require(row["remote_id"] is None, 409, "REMOTE_ARTIFACT_IMMUTABLE")
            for name in ("approved-event.tar", NAME, "archive.part", "cipher.part"):
                path = directory / name
                require(not path.is_symlink(), 500, "UNSAFE_ARTIFACT")
                path.unlink(missing_ok=True)
            source = self.store.directory(transfer_id)
            with self.store.connect() as db:
                objects = {r["name"]: r for r in db.execute("SELECT * FROM objects WHERE transfer_id=?", (transfer_id,))}
            verify_snapshot(source, objects)
            archive = deterministic_tar(source, directory / "archive.part", expected)
            require(archive["size_bytes"] <= MAX_ARTIFACT, 413, "ARCHIVE_LIMIT")
            (directory / "archive.part").replace(directory / "approved-event.tar")
            self.update(transfer_id, state="ENCRYPTING", archive_sha=archive["sha256"], archive_size=archive["size_bytes"])
            cipher = age_encrypt(age_tool, directory / "approved-event.tar", directory / "cipher.part", recipient, MAX_ARTIFACT)
            require(archive["size_bytes"] + cipher["size_bytes"] <= row["artifact_reserved"], 413, "ARTIFACT_RESERVATION_EXCEEDED")
            (directory / "cipher.part").replace(directory / NAME)
            self.update(transfer_id, state="READY_TO_SEND", cipher_sha=cipher["sha256"], cipher_size=cipher["size_bytes"],
                        artifact_reserved=archive["size_bytes"] + cipher["size_bytes"], error_code=None)
            return self.info(transfer_id)

    def remote(self, client, row):
        remote = client.call("get", f"/transfer/{row['remote_id']}")
        require(type(remote) is dict and identifier(remote.get("id")) == row["remote_id"], 502, "REMOTE_TRANSFER_MISMATCH")
        require(remote.get("subject") == "EcoCapture encrypted synthetic " + row["transfer_id"], 502, "REMOTE_SUBJECT_MISMATCH")
        files = remote.get("files")
        require(type(files) is list and len(files) == 1 and files[0].get("name") == NAME and
                int(files[0].get("size", -1)) == row["cipher_size"], 502, "REMOTE_FILE_MISMATCH")
        recipients = remote.get("recipients")
        require(type(recipients) is list and len(recipients) == 1 and recipients[0].get("email") == RECIPIENT,
                502, "REMOTE_RECIPIENT_MISMATCH")
        return remote

    def cleanup_plaintext(self, transfer_id):
        with self.store.connect() as db: row = self.row(db, transfer_id)
        require(row["completion_ack"] is not None, 409, "REMOTE_COMPLETION_NOT_ACKNOWLEDGED")
        archive = self.directory(transfer_id) / "approved-event.tar"
        require(not archive.is_symlink(), 500, "UNSAFE_ARTIFACT")
        archive.unlink(missing_ok=True)
        self.store.purge(transfer_id)
        with self.store.transaction() as db:
            db.execute("UPDATE transfers SET reserved=0 WHERE id=?", (transfer_id,))
            db.execute("DELETE FROM chunks WHERE transfer_id=?", (transfer_id,))
        self.update(transfer_id, plaintext_removed=1, artifact_reserved=row["cipher_size"])

    def send(self, transfer_id, client):
        with self.lock(transfer_id):
            try:
                return self._send(transfer_id, client)
            except GatewayError as error:
                self.update(transfer_id, error_code=error.code)
                raise
            except Exception:
                self.update(transfer_id, error_code="FORWARDING_OPERATION_FAILED")
                raise GatewayError(503, "FORWARDING_OPERATION_FAILED") from None

    def _send(self, transfer_id, client):
        with self.store.connect() as db:
            row = dict(self.row(db, transfer_id))
            transfer = db.execute("SELECT * FROM transfers WHERE id=?", (transfer_id,)).fetchone()
            installation = self.store.installation(db, transfer["owner"])
            require(installation["policy"] == SYNTHETIC_POLICY, 403, "ONLY_CHECKPOINT5_SYNTHETIC_INSTALLATIONS")
        if row["state"] == "FILESENDER_COMPLETE":
            with self.store.transaction() as db:
                db.execute("UPDATE transfers SET state='FILESENDER_COMPLETE' WHERE id=?", (transfer_id,))
            self.cleanup_plaintext(transfer_id)
            return self.info(transfer_id)
        if row["state"] == "LOCAL_PURGED":
            return self.info(transfer_id)
        require(row["cipher_sha"] is not None and row["state"] != "CANCELLED", 409, "ENCRYPTED_ARTIFACT_NOT_READY")
        path = self.directory(transfer_id) / NAME
        require(not path.is_symlink() and measure(path) == {"size_bytes": row["cipher_size"], "sha256": row["cipher_sha"]}, 409, "CIPHERTEXT_CHANGED")
        policy = json.loads(row["policy"])
        remote_now = getattr(client, "now", self.store.clock)()
        require(policy["expires_at"] > remote_now, 409, "REQUESTED_EXPIRY_ELAPSED")
        info = client.call("get", "/info")
        size = info.get("upload_chunk_size") if type(info) is dict else None
        require(type(size) is int and 0 < size <= 64 * 1024 * 1024, 502, "REMOTE_CHUNK_LIMIT_INVALID")
        if row["remote_id"] is None:
            require(row["state"] == "READY_TO_SEND", 409, "CREATION_OUTCOME_REQUIRES_RECONCILIATION")
            self.update(transfer_id, state="REMOTE_CREATION_PENDING")
            remote = client.call("post", "/transfer", payload={"from": client.username if "@" in client.username else RECIPIENT,
                "files": [{"name": NAME, "size": row["cipher_size"]}], "recipients": [RECIPIENT],
                "subject": "EcoCapture encrypted synthetic " + transfer_id,
                "message": "Synthetic engineering archive only; encrypted with age. Verify hashes after decryption. NOT ARCHIVED.",
                "expires": policy["expires_at"], "aup_checked": 1, "options": {"get_a_link": 0}})
            require(type(remote) is dict, 502, "REMOTE_CREATION_RESPONSE_INVALID")
            remote_id = identifier(remote.get("id"))
            # Persist identifier before checking anything else; a failed response is never re-created.
            self.update(transfer_id, remote_id=remote_id, state="UPLOADING")
            row["remote_id"] = remote_id; row["state"] = "UPLOADING"
        remote = self.remote(client, row)
        file_id = identifier(remote["files"][0].get("id"))
        require(row["file_id"] is None or row["file_id"] == file_id, 502, "REMOTE_FILE_ID_CHANGED")
        self.update(transfer_id, file_id=file_id)
        if (remote.get("status") == "available" or row["completion_ack"] is not None) and row["next_offset"] == row["cipher_size"]:
            require(remote.get("status", "available") == "available", 502, "REMOTE_NOT_AVAILABLE")
            acknowledged = row["completion_ack"] or round(self.store.clock() * 1000)
        else:
            require(row["state"] != "TRANSFER_COMPLETION_PENDING", 409, "FINALIZATION_OUTCOME_REQUIRES_RECONCILIATION")
            # Owner-authenticated PUTs avoid persistence of remote puid/roundtrip tokens.
            with path.open("rb") as stream:
                stream.seek(row["next_offset"])
                offset = row["next_offset"]
                while block := stream.read(size):
                    client.call("put", f"/file/{file_id}/chunk/{offset}", raw=block)
                    offset += len(block)
                    self.update(transfer_id, state="UPLOADING", next_offset=offset)
            require(measure(path) == {"size_bytes": row["cipher_size"], "sha256": row["cipher_sha"]}, 409, "CIPHERTEXT_CHANGED")
            self.update(transfer_id, state="FILE_COMPLETION_PENDING")
            client.call("put", f"/file/{file_id}", payload={"complete": True})
            self.update(transfer_id, state="TRANSFER_COMPLETION_PENDING")
            ack = client.call("put", f"/transfer/{row['remote_id']}", payload={"complete": True})
            require(type(ack) is dict and identifier(ack.get("id")) == row["remote_id"], 502, "FINALIZATION_NOT_ACKNOWLEDGED")
            acknowledged = round(self.store.clock() * 1000)
            self.update(transfer_id, completion_ack=acknowledged)
            row["next_offset"] = row["cipher_size"]
            remote = self.remote(client, row)
            require(remote.get("status", "available") == "available", 502, "REMOTE_NOT_AVAILABLE")
        expiry = remote.get("expires")
        expiry = expiry.get("raw") if type(expiry) is dict else expiry
        require(type(expiry) is int and expiry > self.store.clock(), 502, "REMOTE_EXPIRY_INVALID")
        with self.store.transaction() as db:
            db.execute("UPDATE forwarding_jobs SET state='FILESENDER_COMPLETE',completion_ack=?,expiry_ms=?,error_code=NULL,updated=? WHERE transfer_id=?",
                       (acknowledged, expiry * 1000, self.store.clock(), transfer_id))
            db.execute("UPDATE transfers SET state='FILESENDER_COMPLETE',updated=? WHERE id=?", (self.store.clock(), transfer_id))
        self.cleanup_plaintext(transfer_id)
        return self.info(transfer_id)

    def authorize_creation_retry(self, transfer_id, client, expected_cipher_sha, accept_possible_orphan=False):
        require(accept_possible_orphan is True, 403, "POSSIBLE_ORPHAN_ACCEPTANCE_REQUIRED")
        with self.lock(transfer_id):
            with self.store.connect() as db:
                row = dict(self.row(db, transfer_id))
                transfer = db.execute("SELECT * FROM transfers WHERE id=?", (transfer_id,)).fetchone()
                installation = self.store.installation(db, transfer["owner"])
            require(installation["policy"] == SYNTHETIC_POLICY and installation["study_code"] is None,
                    403, "ONLY_CHECKPOINT5_SYNTHETIC_INSTALLATIONS")
            require(row["state"] == "REMOTE_CREATION_PENDING" and row["remote_id"] is None and
                    row["file_id"] is None and row["next_offset"] == 0 and row["completion_ack"] is None,
                    409, "CREATION_RETRY_NOT_ALLOWED")
            require(type(expected_cipher_sha) is str and re.fullmatch(r"[0-9a-f]{64}", expected_cipher_sha) and
                    expected_cipher_sha == row["cipher_sha"], 409, "RECOVERY_CIPHERTEXT_MISMATCH")
            path = self.directory(transfer_id) / NAME
            require(not path.is_symlink() and measure(path) ==
                    {"size_bytes": row["cipher_size"], "sha256": expected_cipher_sha}, 409, "CIPHERTEXT_CHANGED")
            now = getattr(client, "now", self.store.clock)()
            require(json.loads(row["policy"])["expires_at"] > now, 409, "REQUESTED_EXPIRY_ELAPSED")
            listed = client.call("get", "/transfer/@me")
            require(type(listed) is list and all(type(item) is dict and type(item.get("subject")) is str for item in listed),
                    502, "OWNER_LIST_FORMAT_UNEXPECTED")
            require(not any(item["subject"] == "EcoCapture encrypted synthetic " + transfer_id for item in listed),
                    409, "EXISTING_TRANSFER_REQUIRES_ADOPTION")
            # Available-only listing is NOT proof of remote absence. Explicit
            # acceptance records that uncertainty, once per synthetic job.
            with self.store.transaction() as db:
                db.execute("""CREATE TABLE IF NOT EXISTS forwarding_creation_recovery(
                    transfer_id TEXT PRIMARY KEY, prior_state TEXT NOT NULL, prior_error TEXT,
                    cipher_sha TEXT NOT NULL, accepted_at REAL NOT NULL,
                    remote_absence_verified INTEGER NOT NULL CHECK(remote_absence_verified=0))""")
                require(db.execute("SELECT 1 FROM forwarding_creation_recovery WHERE transfer_id=?",
                                   (transfer_id,)).fetchone() is None, 409, "CREATION_RECOVERY_ALREADY_USED")
                current = self.row(db, transfer_id)
                require(current["state"] == row["state"] and current["remote_id"] is None and
                        current["next_offset"] == 0, 409, "CREATION_RETRY_NOT_ALLOWED")
                self.store.installation(db, transfer["owner"])  # recheck revocation after HTTP
                db.execute("INSERT INTO forwarding_creation_recovery VALUES(?,?,?,?,?,0)",
                           (transfer_id, row["state"], row["error_code"], expected_cipher_sha, now))
                db.execute("UPDATE forwarding_jobs SET state='READY_TO_SEND',error_code=NULL,updated=? WHERE transfer_id=?",
                           (self.store.clock(), transfer_id))
            return {"status": "CREATION_RETRY_AUTHORIZED", "gateway_transfer_id": transfer_id,
                    "ciphertext_sha256": expected_cipher_sha, "possible_orphaned_remote_transfer": True,
                    "remote_absence_verified": False, "transfer_mutations": 0, "next": "explicit_send"}

    def reconcile(self, transfer_id, client, remote_id=None, confirm_available=False):
        with self.lock(transfer_id):
            with self.store.connect() as db: row = dict(self.row(db, transfer_id))
            if remote_id is not None:
                require(row["remote_id"] is None and row["state"] == "REMOTE_CREATION_PENDING",
                        409, "REMOTE_ADOPTION_NOT_ALLOWED")
                row["remote_id"] = identifier(remote_id)
                remote = self.remote(client, row)
                require(remote.get("status") != "available", 409, "UNEXPECTED_ALREADY_AVAILABLE_TRANSFER")
                self.update(transfer_id, remote_id=row["remote_id"], file_id=identifier(remote["files"][0]["id"]),
                            state="UPLOADING", error_code=None)
            elif confirm_available:
                require(row["remote_id"] is not None and row["state"] == "TRANSFER_COMPLETION_PENDING" and
                        row["next_offset"] == row["cipher_size"], 409, "FINALIZATION_CONFIRMATION_NOT_ALLOWED")
                self.remote(client, row)  # Verify owner/identity/recipient/bytes before operator confirmation.
                self.update(transfer_id, completion_ack=round(self.store.clock() * 1000), error_code=None)
            else:
                require(False, 400, "RECONCILIATION_ACTION_REQUIRED")
            return self.info(transfer_id)

    def discard(self, transfer_id):
        with self.lock(transfer_id):
            with self.store.connect() as db: row = self.row(db, transfer_id)
            require(row["state"] in ("PREPARING", "ENCRYPTING", "READY_TO_SEND", "FILESENDER_COMPLETE", "LOCAL_PURGED", "CANCELLED"),
                    409, "REMOTE_OUTCOME_REQUIRES_RECONCILIATION")
            directory = self.directory(transfer_id)
            if directory.exists():
                require(all(p.name in (NAME, "approved-event.tar", "archive.part", "cipher.part") and
                            p.is_file() and not p.is_symlink() and p.resolve() == p.absolute() for p in directory.iterdir()),
                        500, "UNSAFE_ARTIFACT")
                shutil.rmtree(directory)
            self.store.purge(transfer_id)
            with self.store.transaction() as db:
                db.execute("UPDATE transfers SET reserved=0 WHERE id=?", (transfer_id,))
                db.execute("DELETE FROM chunks WHERE transfer_id=?", (transfer_id,))
                if row["completion_ack"] is None:
                    db.execute("UPDATE transfers SET state='CANCELLED' WHERE id=?", (transfer_id,))
            self.update(transfer_id, state="LOCAL_PURGED" if row["completion_ack"] else "CANCELLED",
                        artifact_reserved=0, plaintext_removed=1)
            return {"status": "LOCAL_ARTIFACTS_REMOVED", "remote_deletion_claimed": False, "archived": False}
