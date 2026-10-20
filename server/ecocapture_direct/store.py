"""Independent operational ledger; no FileSender or cloud forwarding."""
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import secrets
import shutil

from ecocapture_gateway.archive_encryptor import measure
from ecocapture_gateway.contract import canonical_uuid, sha256
from ecocapture_gateway.errors import require
from ecocapture_gateway.store import Store
from .config import identifier

MAX_BYTES = 256 * 1024 * 1024
FIELDS = {"snapshot_id", "event_id", "recorded_at_epoch_ms", "size_bytes", "ciphertext_sha256", "manifest_sha256", "key_versions"}


def encode(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


class DirectStore(Store):
    def __init__(self, config):
        self.direct = config
        super().__init__(config.gateway)
        self.ciphers = self.root / "ciphers"
        self.ciphers.mkdir(exist_ok=True, mode=0o700)
        require(not self.ciphers.is_symlink(), 500, "UNSAFE_STORAGE")
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS direct_enrolments(
                    code_hash TEXT PRIMARY KEY, participant TEXT NOT NULL, expires REAL NOT NULL,
                    installation TEXT, proof_hash TEXT, enabled INTEGER NOT NULL DEFAULT 1);
                CREATE TABLE IF NOT EXISTS direct_packages(
                    snapshot TEXT PRIMARY KEY, event TEXT NOT NULL, owner TEXT NOT NULL,
                    metadata TEXT NOT NULL, state TEXT NOT NULL, accepted INTEGER NOT NULL DEFAULT 0,
                    registered INTEGER NOT NULL, received INTEGER, relative_path TEXT, error TEXT,
                    archived INTEGER NOT NULL DEFAULT 0, archive_label TEXT);
                CREATE TABLE IF NOT EXISTS direct_chunks(
                    snapshot TEXT NOT NULL, offset INTEGER NOT NULL, size INTEGER NOT NULL, sha TEXT NOT NULL,
                    PRIMARY KEY(snapshot,offset));
                CREATE TABLE IF NOT EXISTS direct_event_notes(
                    event TEXT PRIMARY KEY, tags TEXT NOT NULL DEFAULT '[]', notes TEXT NOT NULL DEFAULT '',
                    revision INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS direct_reviews(
                    snapshot TEXT PRIMARY KEY, state TEXT NOT NULL DEFAULT 'TO_REVIEW', revision INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS direct_audit(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, changed INTEGER NOT NULL,
                    kind TEXT NOT NULL, target TEXT NOT NULL, revision INTEGER NOT NULL, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS direct_researchers(login TEXT PRIMARY KEY, enabled INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS direct_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS direct_event_participants(event TEXT PRIMARY KEY,participant TEXT NOT NULL);
            """)
        with self.transaction() as db:
            db.execute("INSERT OR IGNORE INTO direct_settings VALUES('study',?)", (config.study_id,))
            require(db.execute("SELECT value FROM direct_settings WHERE key='study'").fetchone()[0] == config.study_id, 409, "STUDY_STORAGE_MISMATCH")

    def cipher(self, snapshot):
        canonical_uuid(snapshot)
        path = self.ciphers / (snapshot + ".age")
        require(not path.is_symlink(), 500, "UNSAFE_STORAGE")
        return path

    def profile(self, owner):
        with self.connect() as db:
            installation = self.installation(db, owner)
            require(installation["policy"] == "direct-gb10-v1", 403, "DIRECT_INSTALLATION_REQUIRED")
            return {"study_id": self.direct.study_id, "participant_code": installation["study_code"],
                    "recipients": self.direct.recipients, "key_versions": self.direct.key_versions}

    def issue_code(self, participant):
        identifier(participant)
        code = secrets.token_urlsafe(32)
        with self.transaction() as db:
            require(db.execute("SELECT COUNT(*) FROM direct_enrolments").fetchone()[0] < self.config.max_records, 503, "RECORD_CAPACITY")
            db.execute("INSERT INTO direct_enrolments(code_hash,participant,expires) VALUES (?,?,?)",
                       (hashlib.sha256(code.encode()).hexdigest(), participant, self.clock() + 86400))
        return code

    def enrol(self, body):
        # The device persists its random token before QR exchange. Only hashes are retained here.
        require(type(body) is dict and set(body) == {"code", "installation_id", "token"})
        canonical_uuid(body["installation_id"])
        for name in ("code", "token"):
            require(type(body[name]) is str and re.fullmatch(r"[A-Za-z0-9_-]{43}", body[name]) is not None)
        code_hash = hashlib.sha256(body["code"].encode()).hexdigest()
        proof = hashlib.sha256(body["token"].encode()).hexdigest()
        with self.transaction() as db:
            code = db.execute("SELECT * FROM direct_enrolments WHERE code_hash=?", (code_hash,)).fetchone()
            require(code and code["enabled"] and code["expires"] > self.clock(), 403, "ENROLMENT_UNAVAILABLE")
            if code["installation"] is not None:
                require(code["installation"] == body["installation_id"] and code["proof_hash"] == proof, 403, "ENROLMENT_USED")
                self.installation(db, body["installation_id"])
            else:
                require(db.execute("SELECT 1 FROM installations WHERE id=? OR token_hash=?", (body["installation_id"], proof)).fetchone() is None,
                        409, "INSTALLATION_EXISTS")
                db.execute("INSERT INTO installations VALUES(?,?,1,0,?,'direct-gb10-v1')", (body["installation_id"], proof, code["participant"]))
                db.execute("UPDATE direct_enrolments SET installation=?,proof_hash=? WHERE code_hash=?", (body["installation_id"], proof, code_hash))
        return self.profile(body["installation_id"])

    def owned_package(self, db, owner, snapshot):
        canonical_uuid(snapshot)
        self.installation(db, owner)
        row = db.execute("SELECT * FROM direct_packages WHERE snapshot=? AND owner=?", (snapshot, owner)).fetchone()
        require(row is not None, 404, "NOT_FOUND")
        return row

    def describe(self, row):
        return {**json.loads(row["metadata"]), "status": row["state"], "accepted_bytes": row["accepted"],
                "max_chunk_bytes": self.config.max_chunk_bytes, "received_at": row["received"],
                "gb10_path": row["relative_path"], "error_code": row["error"], "archived": bool(row["archived"])}

    def register(self, owner, body):
        require(type(body) is dict and set(body) == FIELDS)
        for field in ("snapshot_id", "event_id"): canonical_uuid(body[field])
        for field in ("ciphertext_sha256", "manifest_sha256"): sha256(body[field])
        require(type(body["size_bytes"]) is int and 0 < body["size_bytes"] <= MAX_BYTES, 413, "PACKAGE_LIMIT")
        require(type(body["recorded_at_epoch_ms"]) is int and 0 <= body["recorded_at_epoch_ms"] <= 253402300799000)
        require(body["key_versions"] == list(self.direct.key_versions), 409, "KEY_POLICY_CHANGED")
        profile = self.profile(owner)
        metadata = encode({**body, "study_id": profile["study_id"], "participant_code": profile["participant_code"]})
        snapshot = body["snapshot_id"]
        with self.transaction() as db:
            self.installation(db, owner)
            old = db.execute("SELECT * FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone()
            if old:
                require(old["owner"] == owner and old["metadata"] == metadata, 409, "SNAPSHOT_CONFLICT")
                return self.describe(old)
            linked = db.execute("SELECT participant FROM direct_event_participants WHERE event=?", (body["event_id"],)).fetchone()
            require(linked is None or linked[0] == profile["participant_code"], 409, "EVENT_PARTICIPANT_CONFLICT")
            rows = db.execute("SELECT owner,metadata,state,accepted FROM direct_packages").fetchall()
            require(len(rows) < self.config.max_records, 503, "RECORD_CAPACITY")
            active = [row for row in rows if row["state"] in ("RECEIVING", "AWAITING_GB10")]
            require(len(active) < self.config.max_active and sum(row["owner"] == owner for row in active) < self.config.max_installation_active,
                    429, "ACTIVE_JOB_LIMIT")
            # Reserve ciphertext, decryption staging and final plaintext; completed files stay quota-counted.
            reserved = [(row["owner"], 4 * json.loads(row["metadata"])["size_bytes"]) for row in rows if row["state"] != "CANCELLED"]
            required = 4 * body["size_bytes"]
            require(sum(size for _, size in reserved) + required <= self.config.max_total_bytes and
                    sum(size for uid, size in reserved if uid == owner) + required <= self.config.max_installation_bytes, 413, "STORAGE_QUOTA")
            outstanding = sum(max(0, 4 * json.loads(row["metadata"])["size_bytes"] - row["accepted"]) for row in rows if row["state"] != "CANCELLED")
            require(shutil.disk_usage(self.root).free >= required + outstanding + self.config.min_free_bytes, 503, "DISK_CAPACITY")
            db.execute("INSERT INTO direct_packages(snapshot,event,owner,metadata,state,registered) VALUES(?,?,?,?,'RECEIVING',?)",
                       (snapshot, body["event_id"], owner, metadata, int(self.clock() * 1000)))
            db.execute("INSERT OR IGNORE INTO direct_event_notes(event) VALUES(?)", (body["event_id"],))
            db.execute("INSERT OR IGNORE INTO direct_event_participants VALUES(?,?)", (body["event_id"], profile["participant_code"]))
            db.execute("INSERT INTO direct_reviews(snapshot) VALUES(?)", (snapshot,))
            return self.describe(self.owned_package(db, owner, snapshot))

    def status(self, owner, snapshot):
        with self.connect() as db: return self.describe(self.owned_package(db, owner, snapshot))

    def chunk(self, owner, snapshot, offset, source, expected_sha):
        sha256(expected_sha)
        measured = measure(source)
        require(0 < measured["size_bytes"] <= self.config.max_chunk_bytes, 413, "BODY_LIMIT")
        require(measured["sha256"] == expected_sha, 400, "CHUNK_HASH_MISMATCH")
        with self.transaction() as db:
            row = self.owned_package(db, owner, snapshot)
            require(row["state"] == "RECEIVING", 409, "NOT_RECEIVING")
            size = measured["size_bytes"]; total = json.loads(row["metadata"])["size_bytes"]
            require(type(offset) is int and offset >= 0 and offset + size <= total, 409, "OFFSET_CONFLICT", accepted_bytes=row["accepted"])
            prior = db.execute("SELECT * FROM direct_chunks WHERE snapshot=? AND offset=?", (snapshot, offset)).fetchone()
            if prior:
                require(prior["size"] == size and prior["sha"] == expected_sha, 409, "OFFSET_CONFLICT", accepted_bytes=row["accepted"])
                return {"accepted_bytes": row["accepted"], "replayed": True}
            require(offset == row["accepted"], 409, "OFFSET_CONFLICT", accepted_bytes=row["accepted"])
            require(db.execute("SELECT COUNT(*) FROM direct_chunks").fetchone()[0] < self.config.max_chunk_records, 503, "RECORD_CAPACITY")
            path = self.cipher(snapshot)
            require(path.exists() or offset == 0, 409, "CIPHERTEXT_TRUNCATED")
            with path.open("r+b" if path.exists() else "xb") as output:
                require(output.seek(0, 2) >= offset, 409, "CIPHERTEXT_TRUNCATED")
                output.truncate(offset); output.seek(offset)
                with source.open("rb") as stream: shutil.copyfileobj(stream, output, 128 * 1024)
                output.flush(); os.fsync(output.fileno())
            if os.name != "nt":
                directory_fd = os.open(self.ciphers, os.O_DIRECTORY)
                try: os.fsync(directory_fd)
                finally: os.close(directory_fd)
            db.execute("INSERT INTO direct_chunks VALUES(?,?,?,?)", (snapshot, offset, size, expected_sha))
            db.execute("UPDATE direct_packages SET accepted=? WHERE snapshot=?", (offset + size, snapshot))
            return {"accepted_bytes": offset + size, "replayed": False}

    def complete(self, owner, snapshot):
        with self.transaction() as db:
            row = self.owned_package(db, owner, snapshot)
            if row["state"] in ("AWAITING_GB10", "VERIFIED_GB10", "NEEDS_ATTENTION"): return self.describe(row)
            require(row["state"] == "RECEIVING", 409, "NOT_RECEIVING")
            metadata = json.loads(row["metadata"])
            require(row["accepted"] == metadata["size_bytes"], 409, "UPLOAD_INCOMPLETE")
            require(measure(self.cipher(snapshot)) == {"size_bytes": metadata["size_bytes"], "sha256": metadata["ciphertext_sha256"]},
                    409, "CIPHERTEXT_HASH_MISMATCH")
            db.execute("UPDATE direct_packages SET state='AWAITING_GB10' WHERE snapshot=?", (snapshot,))
            return self.describe(self.owned_package(db, owner, snapshot))

    def cancel(self, owner, snapshot):
        with self.transaction() as db:
            row = self.owned_package(db, owner, snapshot)
            require(row["state"] in ("RECEIVING", "CANCELLED"), 409, "ALREADY_ACCEPTED")
            self.cipher(snapshot).unlink(missing_ok=True)
            db.execute("UPDATE direct_packages SET state='CANCELLED' WHERE snapshot=?", (snapshot,))
            return {"status": "CANCELLED", "archived": False}

    def pending(self):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM direct_packages WHERE state='AWAITING_GB10' ORDER BY registered,snapshot").fetchall()
            return [{**self.describe(row), "owner_uid": row["owner"], "generation": "1",
                     "object_path": f"uploads/{row['owner']}/{row['snapshot']}/approved-event.tar.age"} for row in rows]

    def receive_receipt(self, receipt):
        snapshot = canonical_uuid(receipt["snapshot_id"])
        with self.transaction() as db:
            row = db.execute("SELECT * FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone()
            require(row and row["state"] in ("AWAITING_GB10", "NEEDS_ATTENTION", "VERIFIED_GB10"), 409, "NOT_READY")
            metadata = json.loads(row["metadata"])
            require(metadata["ciphertext_sha256"] == receipt["ciphertext_sha256"], 409, "RECEIPT_CONFLICT")
            state = receipt["status"]
            require(state in ("VERIFIED_GB10", "NEEDS_ATTENTION"))
            day = datetime.fromtimestamp(metadata["recorded_at_epoch_ms"] / 1000, timezone.utc).strftime("%Y-%m-%d")
            relative = f"{self.direct.study_id}/{metadata['participant_code']}/{day}/{metadata['event_id']}/{snapshot}"
            require(receipt["gb10_path"] == (relative if state == "VERIFIED_GB10" else None), 409, "RECEIPT_CONFLICT")
            require(receipt["error_code"] == (None if state == "VERIFIED_GB10" else "RECEIVE_FAILED"))
            if row["state"] == "VERIFIED_GB10":
                require(state == "VERIFIED_GB10", 409, "RECEIPT_CONFLICT")
                return self.describe(row)
            db.execute("UPDATE direct_packages SET state=?,received=?,relative_path=?,error=? WHERE snapshot=?",
                       (state, int(self.clock() * 1000) if state == "VERIFIED_GB10" else None,
                        receipt["gb10_path"], receipt["error_code"], snapshot))
            return self.describe(db.execute("SELECT * FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone())

    def researcher(self, login):
        require(type(login) is str and len(login) <= 254 and re.fullmatch(r"[^\s,@]+@[^\s,@]+", login), 403, "RESEARCHER_REQUIRED")
        with self.connect() as db:
            row = db.execute("SELECT enabled FROM direct_researchers WHERE login=?", (login.lower(),)).fetchone()
            require(row and row[0], 403, "RESEARCHER_REQUIRED")
        return login.lower()

    def grant(self, login, enabled=True):
        require(type(login) is str and re.fullmatch(r"[^\s,@]+@[^\s,@]+", login))
        with self.transaction() as db:
            db.execute("INSERT OR REPLACE INTO direct_researchers VALUES(?,?)", (login.lower(), int(enabled)))

    def audit(self, db, actor, kind, target, revision, value):
        require(db.execute("SELECT COUNT(*) FROM direct_audit").fetchone()[0] < self.config.max_records * 10, 503, "AUDIT_CAPACITY")
        db.execute("INSERT INTO direct_audit(actor,changed,kind,target,revision,value) VALUES(?,?,?,?,?,?)",
                   (actor, int(self.clock() * 1000), kind, target, revision, encode(value)))

    def notes(self, actor, event, body):
        canonical_uuid(event)
        require(type(body) is dict and set(body) == {"revision", "tags", "notes"})
        require(type(body["revision"]) is int and body["revision"] >= 0)
        require(type(body["tags"]) is list and len(body["tags"]) <= 32 and
                all(type(tag) is str and 0 < len(tag.strip()) <= 64 and not any(ord(c) < 32 for c in tag) for tag in body["tags"]))
        require(type(body["notes"]) is str and len(body["notes"]) <= 8000)
        tags = sorted(set(tag.strip() for tag in body["tags"])); revision = body["revision"] + 1
        with self.transaction() as db:
            require(db.execute("SELECT 1 FROM direct_researchers WHERE login=? AND enabled=1", (actor,)).fetchone(), 403, "RESEARCHER_REQUIRED")
            row = db.execute("SELECT * FROM direct_event_notes WHERE event=?", (event,)).fetchone()
            require(row is not None, 404, "NOT_FOUND")
            require(row["revision"] == body["revision"], 409, "EDIT_CONFLICT")
            db.execute("UPDATE direct_event_notes SET tags=?,notes=?,revision=? WHERE event=?", (encode(tags), body["notes"], revision, event))
            value = {"tags": tags, "notes": body["notes"], "revision": revision}
            self.audit(db, actor, "EVENT_NOTES", event, revision, value)
            return value

    def review(self, actor, snapshot, body):
        canonical_uuid(snapshot)
        require(type(body) is dict and set(body) == {"revision", "review_status"})
        require(type(body["revision"]) is int and body["revision"] >= 0 and body["review_status"] in ("TO_REVIEW", "IN_REVIEW", "REVIEWED"))
        with self.transaction() as db:
            require(db.execute("SELECT 1 FROM direct_researchers WHERE login=? AND enabled=1", (actor,)).fetchone(), 403, "RESEARCHER_REQUIRED")
            row = db.execute("SELECT r.*,p.state AS receive_state FROM direct_reviews r JOIN direct_packages p ON p.snapshot=r.snapshot WHERE r.snapshot=?", (snapshot,)).fetchone()
            require(row is not None and row["receive_state"] == "VERIFIED_GB10", 409, "VERIFIED_SNAPSHOT_REQUIRED")
            require(row["revision"] == body["revision"], 409, "EDIT_CONFLICT")
            revision = body["revision"] + 1
            db.execute("UPDATE direct_reviews SET state=?,revision=? WHERE snapshot=?", (body["review_status"], revision, snapshot))
            value = {"review_status": body["review_status"], "revision": revision}
            self.audit(db, actor, "SNAPSHOT_REVIEW", snapshot, revision, value)
            return value

    def catalogue(self, filters):
        allowed = {"participant", "status", "review", "tag", "from", "to", "versions", "after"}
        require(set(filters) <= allowed and all(type(v) is str and len(v) <= 128 for v in filters.values()))
        require(filters.get("versions", "latest") in ("latest", "all"))
        after = filters.get("after", "")
        if after: canonical_uuid(after)
        for field in ("from", "to"):
            if filters.get(field):
                try: datetime.strptime(filters[field], "%Y-%m-%d")
                except ValueError: require(False)
        with self.connect() as db:
            rows = db.execute("""SELECT p.*,n.tags,n.notes,n.revision AS notes_revision,
                r.state AS review_status,r.revision AS review_revision,
                ROW_NUMBER() OVER(PARTITION BY p.event ORDER BY p.registered DESC,p.snapshot DESC) AS version_rank
                FROM direct_packages p JOIN direct_event_notes n ON n.event=p.event
                JOIN direct_reviews r ON r.snapshot=p.snapshot WHERE p.state!='CANCELLED' ORDER BY p.snapshot""").fetchall()
            output = []
            for row in rows:
                item = self.describe(row)
                day = datetime.fromtimestamp(item["recorded_at_epoch_ms"] / 1000, timezone.utc).strftime("%Y-%m-%d")
                tags = json.loads(row["tags"])
                if (filters.get("versions", "latest") == "latest" and row["version_rank"] != 1 or
                    after and row["snapshot"] <= after or
                    filters.get("participant", "").lower() not in item["participant_code"].lower() or
                    filters.get("status") and item["status"] != filters["status"] or
                    filters.get("review") and row["review_status"] != filters["review"] or
                    filters.get("tag") and filters["tag"] not in tags or
                    filters.get("from") and day < filters["from"] or filters.get("to") and day > filters["to"]): continue
                output.append({**item, "registered_at": row["registered"], "tags": tags, "notes": row["notes"],
                               "notes_revision": row["notes_revision"], "review_status": row["review_status"],
                               "review_revision": row["review_revision"], "archive_label": row["archive_label"]})
                if len(output) == 101: break
        return {"items": output[:100], "next": output[99]["snapshot_id"] if len(output) > 100 else None}

    def retry_verification(self, snapshot):
        canonical_uuid(snapshot)
        with self.transaction() as db:
            require(db.execute("SELECT 1 FROM direct_packages WHERE snapshot=? AND state='NEEDS_ATTENTION'", (snapshot,)).fetchone(), 409, "ATTENTION_STATE_REQUIRED")
            db.execute("UPDATE direct_packages SET state='AWAITING_GB10',error=NULL WHERE snapshot=?", (snapshot,))

    def record_archive(self, snapshot, destination, label):
        from pathlib import Path
        from ecocapture_gateway.manifest_validator import verify
        canonical_uuid(snapshot); identifier(label)
        destination = Path(destination).absolute()
        require(not destination.resolve().is_relative_to(self.root), 400, "INDEPENDENT_DESTINATION_REQUIRED")
        with self.connect() as db:
            row = db.execute("SELECT * FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone()
            require(row and row["state"] == "VERIFIED_GB10", 409, "VERIFIED_SNAPSHOT_REQUIRED")
            data = self.describe(row)
        source = self.root / "received" / data["gb10_path"]
        verified = verify(destination, data["manifest_sha256"])
        require(all(not os.path.samefile(source / name, destination / name) for name in ("video.mp4", "event.json", "manifest.json")),
                400, "INDEPENDENT_COPY_REQUIRED")
        with self.transaction() as db:
            row = db.execute("SELECT archived,archive_label FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone()
            if row["archived"]:
                require(row["archive_label"] == label, 409, "ARCHIVE_CONFLICT")
                return
            self.audit(db, "operator", "ARCHIVE_VERIFIED", snapshot, 1, {"destination_label": label, "files": verified["files"]})
            db.execute("UPDATE direct_packages SET archived=1,archive_label=? WHERE snapshot=?", (label, snapshot))
