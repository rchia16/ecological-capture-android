from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import time
import uuid

from .contract import OBJECTS, canonical_uuid
from .errors import GatewayError, require
from .mock_transport import MockFileSender


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


class Store:
    def __init__(self, config, clock=time.time):
        self.config = config
        self.clock = clock
        configured = Path(config.storage).absolute()
        require(not configured.is_symlink(), 500, "UNSAFE_STORAGE")
        self.root = configured.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.payload = self.root / "payload"
        self.payload.mkdir(exist_ok=True, mode=0o700)
        require(not self.payload.is_symlink(), 500, "UNSAFE_STORAGE")
        self.incoming = self.root / "incoming"
        self.incoming.mkdir(exist_ok=True, mode=0o700)
        require(not self.incoming.is_symlink(), 500, "UNSAFE_STORAGE")
        self.database = self.root / "gateway.sqlite3"
        require(not self.database.is_symlink(), 500, "UNSAFE_STORAGE")
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS installations(
                    id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, eligible INTEGER NOT NULL,
                    revoked INTEGER NOT NULL, study_code TEXT, policy TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS transfers(
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, snapshot_id TEXT UNIQUE NOT NULL,
                    event_id TEXT NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL,
                    created REAL NOT NULL, updated REAL NOT NULL, reserved INTEGER NOT NULL, manifest_hash TEXT);
                CREATE TABLE IF NOT EXISTS objects(
                    transfer_id TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL,
                    sha TEXT NOT NULL, accepted INTEGER NOT NULL, PRIMARY KEY(transfer_id,name));
                CREATE TABLE IF NOT EXISTS chunks(
                    transfer_id TEXT NOT NULL, name TEXT NOT NULL, offset INTEGER NOT NULL,
                    size INTEGER NOT NULL, sha TEXT NOT NULL, PRIMARY KEY(transfer_id,name,offset));
                CREATE TABLE IF NOT EXISTS mock_intents(
                    transfer_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, manifest_sha TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS rates(owner TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS forwarding_jobs(
                    transfer_id TEXT PRIMARY KEY, state TEXT NOT NULL, policy TEXT NOT NULL,
                    objects_json TEXT NOT NULL, archive_sha TEXT, archive_size INTEGER,
                    cipher_sha TEXT, cipher_size INTEGER, remote_id INTEGER, file_id INTEGER,
                    next_offset INTEGER NOT NULL DEFAULT 0, completion_ack INTEGER, expiry_ms INTEGER,
                    artifact_reserved INTEGER NOT NULL DEFAULT 0, plaintext_removed INTEGER NOT NULL DEFAULT 0,
                    error_code TEXT, updated REAL NOT NULL);
            """)
        if os.name != "nt":
            self.database.chmod(0o600)
        self.mock = MockFileSender()

    def connect(self):
        require(not self.database.is_symlink(), 500, "UNSAFE_STORAGE")
        db = sqlite3.connect(self.database, timeout=2, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA synchronous=FULL")
        return db

    @contextmanager
    def transaction(self):
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def issue(self, installation_id, policy, study_code=None, eligible=False):
        canonical_uuid(installation_id)
        require(type(policy) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", policy) is not None)
        require(study_code is None or type(study_code) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", study_code) is not None)
        token = secrets.token_urlsafe(32)
        with self.transaction() as db:
            require(db.execute("SELECT 1 FROM installations WHERE id=?", (installation_id,)).fetchone() is None, 409, "INSTALLATION_EXISTS")
            db.execute("INSERT INTO installations VALUES (?, ?, ?, 0, ?, ?)",
                       (installation_id, hashlib.sha256(token.encode()).hexdigest(), int(eligible), study_code, policy))
        return token  # Admin writes to a protected file; never log or return from an HTTP route.

    def revoke(self, installation_id):
        canonical_uuid(installation_id)
        with self.transaction() as db:
            db.execute("UPDATE installations SET revoked=1 WHERE id=?", (installation_id,))

    def authenticate(self, authorization):
        require(type(authorization) is str and re.fullmatch(r"Bearer [A-Za-z0-9_-]{43}", authorization) is not None,
                401, "UNAUTHENTICATED")
        digest = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with self.connect() as db:
            row = db.execute("SELECT * FROM installations WHERE token_hash=?", (digest,)).fetchone()
        require(row is not None, 401, "UNAUTHENTICATED")
        require(row["eligible"] and not row["revoked"], 403, "INSTALLATION_DISABLED")
        return row["id"]

    def installation(self, db, owner):
        row = db.execute("SELECT * FROM installations WHERE id=?", (owner,)).fetchone()
        require(row is not None and row["eligible"] and not row["revoked"], 403, "INSTALLATION_DISABLED")
        return row

    def rate(self, owner):
        window = int(self.clock() // 60)
        with self.transaction() as db:
            self.installation(db, owner)
            row = db.execute("SELECT * FROM rates WHERE owner=?", (owner,)).fetchone()
            count = row["count"] if row is not None and row["window"] == window else 0
            require(count < self.config.requests_per_minute, 429, "RATE_LIMIT")
            db.execute("INSERT OR REPLACE INTO rates VALUES (?, ?, ?)", (owner, window, count + 1))

    def directory(self, transfer_id):
        canonical_uuid(transfer_id)
        directory = self.payload / transfer_id
        require(not directory.is_symlink() and directory.resolve() == directory.absolute(), 500, "UNSAFE_STORAGE")
        return directory

    def path(self, transfer_id, name):
        require(name in OBJECTS)
        path = self.directory(transfer_id) / name
        require(not path.is_symlink(), 500, "UNSAFE_STORAGE")
        return path

    def owned(self, db, owner, transfer_id):
        canonical_uuid(transfer_id)
        self.installation(db, owner)
        row = db.execute("SELECT * FROM transfers WHERE id=? AND owner=?", (transfer_id, owner)).fetchone()
        require(row is not None, 404, "NOT_FOUND")
        return row

    def create(self, owner, body, fingerprint):
        with self.transaction() as db:
            self.installation(db, owner)
            prior = db.execute("SELECT * FROM transfers WHERE snapshot_id=?", (body["snapshot_id"],)).fetchone()
            if prior is not None:
                require(prior["owner"] == owner and prior["fingerprint"] == fingerprint, 409, "IDEMPOTENCY_CONFLICT")
                return self.describe(db, prior)
            cfg = self.config
            rows = db.execute("SELECT owner,state,COUNT(*) AS n FROM transfers GROUP BY owner,state").fetchall()
            require(sum(r["n"] for r in rows) < cfg.max_records, 503, "RECORD_CAPACITY")
            active = [r for r in rows if r["state"] == "RECEIVING"]
            require(sum(r["n"] for r in active) < cfg.max_active and
                    sum(r["n"] for r in active if r["owner"] == owner) < cfg.max_installation_active, 429, "ACTIVE_JOB_LIMIT")
            reserved = db.execute("""SELECT t.owner,SUM(o.size) AS size,SUM(o.size-o.accepted) AS remaining
                FROM objects o JOIN transfers t ON t.id=o.transfer_id WHERE t.reserved=1 GROUP BY t.owner""").fetchall()
            size = sum(o["size_bytes"] for o in body["objects"])
            artifacts = db.execute("SELECT t.owner,f.artifact_reserved AS size FROM forwarding_jobs f JOIN transfers t ON t.id=f.transfer_id").fetchall()
            require(size + sum(r["size"] for r in reserved) + sum(r["size"] for r in artifacts) <= cfg.max_total_bytes and
                    size + sum(r["size"] for r in reserved if r["owner"] == owner) + sum(r["size"] for r in artifacts if r["owner"] == owner) <= cfg.max_installation_bytes,
                    413, "STORAGE_QUOTA")
            require(shutil.disk_usage(self.root).free >= cfg.min_free_bytes + size + sum(r["remaining"] for r in reserved) + sum(r["size"] for r in artifacts), 503, "DISK_CAPACITY")
            transfer_id = str(uuid.uuid4())
            now = self.clock()
            db.execute("INSERT INTO transfers VALUES (?, ?, ?, ?, ?, 'RECEIVING', ?, ?, 1, NULL)",
                       (transfer_id, owner, body["snapshot_id"], body["event_id"], fingerprint, now, now))
            db.executemany("INSERT INTO objects VALUES (?, ?, ?, ?, 0)",
                           [(transfer_id, o["name"], o["size_bytes"], o["sha256"]) for o in body["objects"]])
            return self.describe(db, self.owned(db, owner, transfer_id))

    def describe(self, db, transfer):
        return {"gateway_transfer_id": transfer["id"], "snapshot_id": transfer["snapshot_id"],
                "state": transfer["state"], "max_chunk_bytes": self.config.max_chunk_bytes,
                "objects": [{"name": r["name"], "size_bytes": r["size"], "accepted_bytes": r["accepted"]}
                            for r in db.execute("SELECT * FROM objects WHERE transfer_id=? ORDER BY name", (transfer["id"],))]}

    def status(self, owner, transfer_id):
        with self.transaction() as db:
            return self.describe(db, self.owned(db, owner, transfer_id))

    def begin_chunk(self, owner, transfer_id, name):
        with self.transaction() as db:
            transfer = self.owned(db, owner, transfer_id)
            require(transfer["state"] == "RECEIVING", 409, "TRANSFER_NOT_RECEIVING")
            require(name in OBJECTS)
            require(not self.incoming.is_symlink() and self.incoming.resolve() == self.incoming.absolute(), 500, "UNSAFE_STORAGE")
            directory = self.directory(transfer_id)
            directory.mkdir(exist_ok=True, mode=0o700)
            require(shutil.disk_usage(self.root).free >= self.config.min_free_bytes + self.config.max_chunk_bytes, 503, "DISK_CAPACITY")
            temporary = self.incoming / (str(uuid.uuid4()) + ".part")
        return temporary

    def commit_chunk(self, owner, transfer_id, name, offset, temporary, size, sha):
        with self.transaction() as db:
            transfer = self.owned(db, owner, transfer_id)
            require(transfer["state"] == "RECEIVING", 409, "TRANSFER_NOT_RECEIVING")
            row = db.execute("SELECT * FROM objects WHERE transfer_id=? AND name=?", (transfer_id, name)).fetchone()
            require(row is not None)
            accepted = row["accepted"]
            path = self.path(transfer_id, name)
            actual = path.stat().st_size if path.exists() else 0
            require(actual >= accepted, 409, "STORAGE_INCONSISTENT", accepted_bytes=accepted)
            if actual > accepted:
                with path.open("r+b") as file:
                    file.truncate(accepted); file.flush(); os.fsync(file.fileno())
            if offset < accepted:
                prior = db.execute("SELECT * FROM chunks WHERE transfer_id=? AND name=? AND offset=?", (transfer_id, name, offset)).fetchone()
                require(prior is not None and prior["size"] == size and prior["sha"] == sha, 409, "OFFSET_CONFLICT", accepted_bytes=accepted)
                return {"accepted_bytes": accepted, "replayed": True}
            require(offset == accepted and 0 < size <= row["size"] - accepted, 409, "OFFSET_CONFLICT", accepted_bytes=accepted)
            require(size >= min(64 * 1024, self.config.max_chunk_bytes) or size == row["size"] - accepted,
                    400, "CHUNK_SIZE")
            require(db.execute("SELECT COUNT(*) FROM chunks").fetchone()[0] < self.config.max_chunk_records,
                    503, "CHUNK_RECORD_CAPACITY")
            with path.open("ab") as output, temporary.open("rb") as source:
                shutil.copyfileobj(source, output, length=128 * 1024)
                output.flush(); os.fsync(output.fileno())
            db.execute("INSERT INTO chunks VALUES (?, ?, ?, ?, ?)", (transfer_id, name, offset, size, sha))
            db.execute("UPDATE objects SET accepted=? WHERE transfer_id=? AND name=?", (accepted + size, transfer_id, name))
            db.execute("UPDATE transfers SET updated=? WHERE id=?", (self.clock(), transfer_id))
            return {"accepted_bytes": accepted + size, "replayed": False}

    def complete(self, owner, transfer_id, verify):
        with self.transaction() as db:
            transfer = self.owned(db, owner, transfer_id)
            if transfer["state"] == "MOCK_FORWARDED":
                return self.receipt_row(transfer)
            if transfer["state"] == "FILESENDER_COMPLETE":
                from .forwarding import receipt
                return receipt(db, transfer)
            require(transfer["state"] == "RECEIVING", 409, "TRANSFER_NOT_RECEIVING")
            rows = db.execute("SELECT * FROM objects WHERE transfer_id=?", (transfer_id,)).fetchall()
            require(all(r["accepted"] == r["size"] for r in rows), 409, "OBJECTS_INCOMPLETE")
            expected = {r["name"]: r for r in rows}
            try:
                result, event = verify(self.directory(transfer_id), expected)
            except (ValueError, OSError, KeyError, TypeError, RecursionError):
                raise GatewayError(409, "SNAPSHOT_VERIFICATION_FAILED") from None
            require(event["snapshot_id"] == transfer["snapshot_id"] and event["event_id"] == transfer["event_id"], 409, "SNAPSHOT_LINKAGE_FAILED")
            require(event["study_participant_code"] == self.installation(db, owner)["study_code"], 403, "PARTICIPANT_LINKAGE_FAILED")
            manifest_sha = expected["manifest.json"]["sha"]
            self.mock.record(db, transfer, manifest_sha)
            db.execute("UPDATE transfers SET state='MOCK_FORWARDED',manifest_hash=?,updated=? WHERE id=?", (manifest_sha, self.clock(), transfer_id))
            return self.receipt_row(self.owned(db, owner, transfer_id))

    @staticmethod
    def receipt_row(transfer):
        return {"schema": "ecocapture.gateway.mock-receipt.v1", "gateway_transfer_id": transfer["id"],
                "snapshot_id": transfer["snapshot_id"], "status": "MOCK_FORWARDED", "mock": True,
                "manifest_sha256": transfer["manifest_hash"], "archived": False}

    def receipt(self, owner, transfer_id):
        with self.transaction() as db:
            transfer = self.owned(db, owner, transfer_id)
            if transfer["state"] == "FILESENDER_COMPLETE":
                from .forwarding import receipt
                return receipt(db, transfer)
            require(transfer["state"] == "MOCK_FORWARDED", 409, "NO_RECEIPT")
            return self.receipt_row(transfer)

    def purge(self, transfer_id):
        directory = self.directory(transfer_id)
        if directory.exists():
            require(all(p.is_file() and not p.is_symlink() and p.resolve() == p.absolute() for p in directory.iterdir()), 500, "UNSAFE_STORAGE")
            shutil.rmtree(directory)

    def cancel(self, owner, transfer_id):
        with self.transaction() as db:
            self.owned(db, owner, transfer_id)
            require(db.execute("SELECT 1 FROM forwarding_jobs WHERE transfer_id=?", (transfer_id,)).fetchone() is None,
                    409, "FORWARDING_CANCEL_REQUIRES_OPERATOR")
            db.execute("UPDATE transfers SET state='CANCELLED',updated=? WHERE id=?", (self.clock(), transfer_id))
        self.purge(transfer_id)
        with self.transaction() as db:
            db.execute("UPDATE transfers SET reserved=0 WHERE id=?", (transfer_id,))
            db.execute("DELETE FROM chunks WHERE transfer_id=?", (transfer_id,))
        return {"state": "CANCELLED", "remote_deletion_claimed": False}

    def cleanup(self):
        cutoff = self.clock() - self.config.staging_ttl_seconds
        with self.transaction() as db:
            ids = [r["id"] for r in db.execute("SELECT id FROM transfers WHERE updated<? AND state IN ('RECEIVING','CANCELLED') AND id NOT IN (SELECT transfer_id FROM forwarding_jobs)", (cutoff,))]
            for transfer_id in ids:
                self.purge(transfer_id)
                db.execute("UPDATE transfers SET state='PURGED',reserved=0 WHERE id=?", (transfer_id,))
                db.execute("DELETE FROM chunks WHERE transfer_id=?", (transfer_id,))
        # Orphan request bodies/partial chunks are bounded and only purged after the staging window.
        known = set()
        with self.connect() as db:
            known.update(r["id"] for r in db.execute("SELECT id FROM transfers"))
        for directory in self.payload.iterdir():
            require(not directory.is_symlink(), 500, "UNSAFE_STORAGE")
            if directory.name not in known and directory.stat().st_mtime < cutoff:
                self.purge(directory.name)
        require(not self.incoming.is_symlink() and self.incoming.resolve() == self.incoming.absolute(), 500, "UNSAFE_STORAGE")
        for part in self.incoming.glob("*.part"):
            require(not part.is_symlink(), 500, "UNSAFE_STORAGE")
            if part.stat().st_mtime < cutoff:
                part.unlink()
        return {"purged_jobs": len(ids)}
