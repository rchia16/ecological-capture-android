"""Verified publication and durable acknowledgements, with no cloud SDK dependency."""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tarfile

from ecocapture_gateway.archive_encryptor import measure, NAMES, AGE_VERSION
from ecocapture_gateway.manifest_validator import load_json, verify

MAX_BYTES = 256 * 1024 * 1024


class InvalidPackage(ValueError):
    pass


def checked(condition):
    if not condition:
        raise InvalidPackage("PACKAGE_INVALID")


def safe_id(value, uuid=False):
    import uuid as uuids
    checked(type(value) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value))
    if uuid:
        checked(str(uuids.UUID(value)) == value)
    return value


def validate_package(row, study):
    checked(row["study_id"] == study and row["status"] == "AWAITING_GB10")
    for name in ("snapshot_id", "event_id", "owner_uid"):
        safe_id(row[name], True)
    safe_id(row["participant_code"])
    for name in ("ciphertext_sha256", "manifest_sha256"):
        checked(type(row[name]) is str and re.fullmatch(r"[0-9a-f]{64}", row[name]))
    checked(type(row["size_bytes"]) is int and 0 < row["size_bytes"] <= MAX_BYTES)
    checked(type(row["recorded_at_epoch_ms"]) is int and 0 <= row["recorded_at_epoch_ms"] <= 253402300799000)
    checked(row["object_path"] == f"uploads/{row['owner_uid']}/{row['snapshot_id']}/approved-event.tar.age")
    checked(str(row["generation"]).isdigit())


def private_directory(path):
    path = Path(path).absolute()
    for item in (path, *path.parents):
        checked(not item.is_symlink())
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    checked(path.is_dir())
    if os.name != "nt":
        checked(path.stat().st_uid == os.getuid() and path.stat().st_mode & 0o077 == 0)
    return path


class Receiver:
    def __init__(self, root, study, age_tool, identity, cloud, decrypt=None):
        self.root = private_directory(root)
        self.staging = private_directory(self.root / ".staging")
        self.study = safe_id(study)
        self.age_tool, self.identity, self.cloud = Path(age_tool), Path(identity), cloud
        if decrypt is None:
            if not self.age_tool.is_file() or not self.identity.is_file():
                raise OSError("RECEIVER_KEYS_OR_TOOL_UNAVAILABLE")
            if os.name != "nt":
                checked(self.identity.stat().st_uid == os.getuid() and self.identity.stat().st_mode & 0o077 == 0)
        self.decrypt = decrypt or self.age_decrypt
        self.db = sqlite3.connect(self.root / ".receipts.sqlite3")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS receipts (snapshot TEXT PRIMARY KEY, cipher TEXT NOT NULL, receipt TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
        self.db.commit()

    def close(self):
        self.db.close()

    @contextmanager
    def exclusive(self):
        # A second receiver must not clean another receiver's staging directory.
        lock = (self.root / ".receiver.lock").open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                lock.seek(0); lock.write(b"0"); lock.flush(); lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            yield
        finally:
            lock.close()

    def age_decrypt(self, source, output):
        version = subprocess.run([str(self.age_tool), "--version"], capture_output=True, check=True, timeout=10)
        checked(version.stdout.decode().strip() == AGE_VERSION)
        checked(self.identity.is_file() and not self.identity.is_symlink())
        result = subprocess.run([str(self.age_tool), "--decrypt", "-i", str(self.identity),
                                 "-o", str(output), str(source)], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=180)
        checked(result.returncode == 0 and output.stat().st_size <= MAX_BYTES)

    def check_files(self, directory, row):
        verify(directory, row["manifest_sha256"])
        event = load_json(directory / "event.json")
        checked(event["event_id"] == row["event_id"] and event["snapshot_id"] == row["snapshot_id"])
        checked(event["study_participant_code"] == row["participant_code"])
        checked(event["capture"]["created_at_epoch_ms"] == row["recorded_at_epoch_ms"])
        checked(event["review"]["approval_state"] == "APPROVED")

    def acknowledge(self):
        # Retry all local receipts before querying cloud pending work.
        for snapshot, encoded in self.db.execute("SELECT snapshot, receipt FROM receipts WHERE acknowledged=0").fetchall():
            self.cloud.receipt(json.loads(encoded))
            self.db.execute("UPDATE receipts SET acknowledged=1 WHERE snapshot=?", (snapshot,))
            self.db.commit()

    def receive(self, row):
        validate_package(row, self.study)
        snapshot = row["snapshot_id"]
        previous = self.db.execute("SELECT cipher,receipt FROM receipts WHERE snapshot=?", (snapshot,)).fetchone()
        if previous:
            checked(previous[0] == row["ciphertext_sha256"])
            self.cloud.receipt(json.loads(previous[1]))
            return
        day = datetime.fromtimestamp(row["recorded_at_epoch_ms"] / 1000, timezone.utc).strftime("%Y-%m-%d")
        relative = f"{self.study}/{row['participant_code']}/{day}/{row['event_id']}/{snapshot}"
        destination = self.root / relative
        parent = private_directory(destination.parent)
        checked(not destination.is_symlink())
        if destination.exists():
            # Crash after atomic publication, before local ledger commit: validate and recover.
            self.check_files(destination, row)
        else:
            if shutil.disk_usage(self.root).free < 3 * MAX_BYTES + 64 * 1024 * 1024:
                raise OSError("INSUFFICIENT_STORAGE")
            stage = self.staging / snapshot
            checked(not stage.is_symlink())
            if stage.exists():
                shutil.rmtree(stage)
            stage.mkdir(mode=0o700)
            try:
                cipher = stage / "cipher.age"
                self.cloud.download(row, cipher)
                checked(measure(cipher) == {"size_bytes": row["size_bytes"], "sha256": row["ciphertext_sha256"]})
                archive_path = stage / "decrypted.tar"
                self.decrypt(cipher, archive_path)
                files = stage / "files"; files.mkdir(mode=0o700)
                # Never extract paths, links, devices, sparse files or compressed input.
                with tarfile.open(archive_path, "r:") as archive:
                    total = 0; seen = []
                    for member in archive:
                        checked(member.name in NAMES and member.name not in seen and member.isfile() and
                                not member.issparse() and not member.pax_headers)
                        total += member.size
                        checked(0 < member.size and total <= MAX_BYTES)
                        seen.append(member.name)
                        with archive.extractfile(member) as source, (files / member.name).open("xb") as output:
                            shutil.copyfileobj(source, output, 128 * 1024)
                            output.flush(); os.fsync(output.fileno())
                    checked(set(seen) == set(NAMES))
                self.check_files(files, row)
                os.rename(files, destination)
                if os.name != "nt":
                    handle = os.open(parent, os.O_DIRECTORY)
                    try: os.fsync(handle)
                    finally: os.close(handle)
            finally:
                if stage.exists():
                    shutil.rmtree(stage)
        receipt = {"snapshot_id": snapshot, "ciphertext_sha256": row["ciphertext_sha256"],
                   "status": "VERIFIED_GB10", "gb10_path": relative, "error_code": None}
        self.db.execute("INSERT INTO receipts(snapshot,cipher,receipt) VALUES (?,?,?)",
                        (snapshot, row["ciphertext_sha256"], json.dumps(receipt)))
        self.db.commit()
        self.acknowledge()

    def once(self):
        with self.exclusive():
            self.acknowledge()
            for row in self.cloud.pending(self.study):
                try:
                    self.receive(row)
                except (InvalidPackage, ValueError, KeyError, tarfile.TarError):
                    self.cloud.receipt({"snapshot_id": row["snapshot_id"],
                        "ciphertext_sha256": row["ciphertext_sha256"], "status": "NEEDS_ATTENTION",
                        "gb10_path": None, "error_code": "RECEIVE_FAILED"})
