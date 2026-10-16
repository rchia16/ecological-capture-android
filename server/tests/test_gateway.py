from dataclasses import replace
import hashlib
import json
import logging
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import uuid

from starlette.testclient import TestClient
from ecocapture_gateway.app import create_app
from ecocapture_gateway.config import Config
from ecocapture_gateway.store import Store
from ecocapture_gateway.cli import RedactedLog


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = Config(self.root / "state", allowed_hosts=("gateway.test",), max_chunk_bytes=64,
                             requests_per_minute=2000)
        self.store = Store(self.config)
        self.a = str(uuid.uuid4())
        self.b = str(uuid.uuid4())
        self.token_a = self.store.issue(self.a, "synthetic", eligible=True)
        self.token_b = self.store.issue(self.b, "synthetic", eligible=True)
        self.client = TestClient(create_app(self.config, self.store), base_url="https://gateway.test")
        self.addCleanup(self.client.close)
        self.snapshot = str(uuid.uuid4())
        self.event_id = str(uuid.uuid4())
        # Entirely synthetic deterministic data; structural MP4 box fixture, not a playable recording.
        self.video = b"\x00\x00\x00\x10ftypisom0000" + b"\x00\x00\x00\x09moovx" + b"\x00\x00\x00\x48mdat" + b"x" * 64
        self.event = {
            "schema": "ecocapture.event.v1", "event_id": self.event_id, "clip_id": self.event_id,
            "snapshot_id": self.snapshot, "previous_snapshot_id": None, "study_participant_code": None,
            "capture": {"created_at_epoch_ms": 1000, "duration_ms": 1000, "width": 320, "height": 180,
                        "sample_count": 2, "created_by_app_version": "synthetic"},
            "review": {"review_state": "REVIEWED", "approval_state": "APPROVED", "approved_at_epoch_ms": None,
                       "updated_at_epoch_ms": 2000, "snapshot_created_at_epoch_ms": 3000},
            "participant_annotations": {"current_annotation_id": None, "revisions": []}, "vlm_runs": [],
            "export": {"schema_version": 1, "app_version": "synthetic", "exported_at_epoch_ms": 3000}}
        self.prepare()

    @staticmethod
    def digest(data):
        return hashlib.sha256(data).hexdigest()

    def prepare(self):
        event = json.dumps(self.event, separators=(",", ":")).encode()
        manifest = {"schema": "ecocapture.manifest.v1", "event_id": self.event_id, "snapshot_id": self.snapshot,
                    "created_at_epoch_ms": 3000, "files": [
                        {"path": "video.mp4", "size_bytes": len(self.video), "sha256": self.digest(self.video)},
                        {"path": "event.json", "size_bytes": len(event), "sha256": self.digest(event)}],
                    "hash_algorithm": "SHA-256", "manifest_version": 1}
        self.files = {"video.mp4": self.video, "event.json": event,
                      "manifest.json": json.dumps(manifest, separators=(",", ":")).encode()}
        self.body = {"schema": "ecocapture.gateway.transfer.v1", "snapshot_id": self.snapshot,
                     "event_id": self.event_id, "objects": [
                         {"name": name, "size_bytes": len(data), "sha256": self.digest(data)}
                         for name, data in self.files.items()]}

    def headers(self, token=None):
        return {"Authorization": "Bearer " + (token or self.token_a), "Idempotency-Key": self.snapshot}

    def create(self, token=None):
        response = self.client.post("/v1/transfers", json=self.body, headers=self.headers(token))
        self.assertEqual(201, response.status_code, response.text)
        return response.json()["gateway_transfer_id"]

    def chunk(self, transfer_id, name, offset, data, token=None, sha=None):
        return self.client.put(f"/v1/transfers/{transfer_id}/objects/{name}/chunks/{offset}", content=data,
                               headers={**self.headers(token), "Content-Type": "application/octet-stream",
                                        "X-Chunk-SHA256": sha or self.digest(data)})

    def upload(self, transfer_id):
        for name, data in self.files.items():
            for offset in range(0, len(data), 64):
                response = self.chunk(transfer_id, name, offset, data[offset:offset + 64])
                self.assertEqual(200, response.status_code, response.text)

    def test_https_and_host_required_forwarded_header_cannot_bypass(self):
        plain = TestClient(create_app(self.config, self.store), base_url="http://gateway.test")
        self.addCleanup(plain.close)
        self.assertEqual(400, plain.get("/v1/health", headers={"X-Forwarded-Proto": "https"}).status_code)
        self.assertEqual(400, self.client.get("/v1/health", headers={"Host": "attacker.test"}).status_code)
        self.assertEqual(200, self.client.get("/v1/health").status_code)
        self.assertEqual(400, self.client.get("/v1/health?token=synthetic-marker").status_code)

    def test_missing_invalid_and_disabled_auth(self):
        self.assertEqual(401, self.client.post("/v1/transfers", json=self.body).status_code)
        self.assertEqual(401, self.client.post("/v1/transfers", json=self.body, headers=self.headers("invalid")).status_code)
        disabled = self.store.issue(str(uuid.uuid4()), "synthetic", eligible=False)
        self.assertEqual(403, self.client.post("/v1/transfers", json=self.body, headers=self.headers(disabled)).status_code)

    def test_revocation_independent_and_token_is_hashed(self):
        transfer = self.create()
        self.store.revoke(self.a)
        self.assertEqual(403, self.client.get(f"/v1/transfers/{transfer}", headers=self.headers()).status_code)
        self.assertEqual(200, self.client.get("/v1/health").status_code)
        other_body = dict(self.body, snapshot_id=str(uuid.uuid4()))
        response = self.client.post("/v1/transfers", json=other_body,
                                    headers={**self.headers(self.token_b), "Idempotency-Key": other_body["snapshot_id"]})
        self.assertEqual(201, response.status_code)
        with self.store.connect() as db:
            row = db.execute("SELECT token_hash FROM installations WHERE id=?", (self.a,)).fetchone()
        self.assertEqual(self.digest(self.token_a.encode()), row[0])
        self.assertNotIn(self.token_a.encode(), self.store.database.read_bytes())

    def test_device_b_cannot_read_write_complete_cancel_or_receive_a(self):
        transfer = self.create()
        path = f"/v1/transfers/{transfer}"
        for method, suffix in [("GET", ""), ("GET", "/receipt"), ("POST", "/complete"), ("POST", "/cancel")]:
            self.assertEqual(404, self.client.request(method, path + suffix, headers=self.headers(self.token_b)).status_code)
        self.assertEqual(404, self.chunk(transfer, "video.mp4", 0, b"x", self.token_b).status_code)

    def test_create_idempotency_and_conflict(self):
        first = self.create()
        self.assertEqual(first, self.create())
        conflict = dict(self.body, event_id=str(uuid.uuid4()))
        self.assertEqual(409, self.client.post("/v1/transfers", json=conflict, headers=self.headers()).status_code)
        self.assertEqual(409, self.client.post("/v1/transfers", json=self.body, headers=self.headers(self.token_b)).status_code)

    def test_wrong_names_extra_fields_duplicate_keys_and_boolean_size(self):
        malicious = json.loads(json.dumps(self.body))
        malicious["objects"][0]["name"] = "../outside"
        self.assertEqual(400, self.client.post("/v1/transfers", json=malicious, headers=self.headers()).status_code)
        self.assertEqual(400, self.client.post("/v1/transfers", json=dict(self.body, destination="private-marker"), headers=self.headers()).status_code)
        malicious = json.loads(json.dumps(self.body)); malicious["objects"][0]["size_bytes"] = True
        self.assertEqual(413, self.client.post("/v1/transfers", json=malicious, headers=self.headers()).status_code)
        duplicate = b'{"schema":1,"schema":2}'
        self.assertEqual(400, self.client.post("/v1/transfers", content=duplicate,
                                             headers={**self.headers(), "Content-Type": "application/json"}).status_code)

    def test_chunk_replay_conflict_and_hash_mismatch_do_not_corrupt(self):
        transfer = self.create()
        data = self.video[:64]
        self.assertEqual(200, self.chunk(transfer, "video.mp4", 0, data).status_code)
        replay = self.chunk(transfer, "video.mp4", 0, data)
        self.assertTrue(replay.json()["replayed"])
        conflict = self.chunk(transfer, "video.mp4", 0, b"different")
        self.assertEqual(409, conflict.status_code)
        self.assertEqual(64, conflict.json()["error"]["accepted_bytes"])
        self.assertEqual(400, self.chunk(transfer, "video.mp4", 64, b"x", sha="0" * 64).status_code)
        self.assertEqual(data, self.store.path(transfer, "video.mp4").read_bytes())

    def test_oversized_chunk_rejected_before_mutation(self):
        transfer = self.create()
        self.assertEqual(413, self.chunk(transfer, "video.mp4", 0, b"x" * 65).status_code)
        self.assertFalse(self.store.path(transfer, "video.mp4").exists())
        self.assertEqual(400, self.chunk(transfer, "unknown", 0, b"x").status_code)

    def test_restart_uses_authoritative_offset_and_discards_uncommitted_tail(self):
        transfer = self.create()
        self.assertEqual(200, self.chunk(transfer, "video.mp4", 0, self.video[:64]).status_code)
        with self.store.path(transfer, "video.mp4").open("ab") as file:
            file.write(b"UNCOMMITTED")
        restarted = Store(self.config)
        new_client = TestClient(create_app(self.config, restarted), base_url="https://gateway.test")
        self.addCleanup(new_client.close)
        status = new_client.get(f"/v1/transfers/{transfer}", headers=self.headers()).json()
        self.assertEqual(64, next(o for o in status["objects"] if o["name"] == "video.mp4")["accepted_bytes"])
        self.client = new_client
        self.assertEqual(200, self.chunk(transfer, "video.mp4", 64, self.video[64:]).status_code)
        self.assertEqual(self.video, restarted.path(transfer, "video.mp4").read_bytes())

    def test_truncated_committed_file_is_not_silently_accepted(self):
        transfer = self.create()
        self.chunk(transfer, "video.mp4", 0, self.video[:64])
        self.store.path(transfer, "video.mp4").write_bytes(b"broken")
        self.assertEqual(409, self.chunk(transfer, "video.mp4", 64, self.video[64:]).status_code)

    def test_complete_verifies_all_files_once_and_cannot_claim_archive(self):
        transfer = self.create()
        self.assertEqual(409, self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers()).status_code)
        self.upload(transfer)
        with patch("socket.create_connection", side_effect=AssertionError("No outbound calls")):
            response = self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers())
            repeat = self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers())
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(response.json(), repeat.json())
        self.assertEqual("MOCK_FORWARDED", response.json()["status"])
        self.assertTrue(response.json()["mock"])
        self.assertFalse(response.json()["archived"])
        self.assertNotIn("filesender_transfer_id", response.json())
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM mock_intents").fetchone()[0])

    def test_complete_rehashes_disk_corruption(self):
        transfer = self.create(); self.upload(transfer)
        file = self.store.path(transfer, "video.mp4")
        damaged = bytearray(file.read_bytes()); damaged[-1] ^= 1; file.write_bytes(damaged)
        self.assertEqual(409, self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers()).status_code)

    def test_schema_and_participant_linkage_verified_even_with_correct_hashes(self):
        self.event["study_participant_code"] = "IMPERSONATED"
        self.prepare(); transfer = self.create(); self.upload(transfer)
        self.assertEqual(403, self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers()).status_code)
        self.snapshot = str(uuid.uuid4()); self.event["snapshot_id"] = self.snapshot
        self.event["study_participant_code"] = None; self.event["diagnosis"] = "forbidden-fixture-field"
        self.prepare(); transfer = self.create(); self.upload(transfer)
        self.assertEqual(409, self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers()).status_code)

    def test_cancel_discards_local_payload_and_receipt_without_remote_claim(self):
        transfer = self.create(); self.upload(transfer)
        response = self.client.post(f"/v1/transfers/{transfer}/cancel", headers=self.headers())
        self.assertEqual(200, response.status_code)
        self.assertFalse(response.json()["remote_deletion_claimed"])
        self.assertFalse(self.store.directory(transfer).exists())
        self.assertEqual(409, self.chunk(transfer, "video.mp4", 0, b"x").status_code)

    def test_storage_active_record_and_disk_limits(self):
        limited = replace(self.config, max_total_bytes=1)
        # Store quotas are authoritative; use a store carrying the limited config.
        limited_store = Store(limited)
        client = TestClient(create_app(limited, limited_store), base_url="https://gateway.test")
        self.addCleanup(client.close)
        self.assertEqual(413, client.post("/v1/transfers", json=self.body, headers=self.headers()).status_code)
        self.create()
        for config in (replace(self.config, max_installation_active=1), replace(self.config, max_records=1)):
            client = TestClient(create_app(config, Store(config)), base_url="https://gateway.test")
            self.addCleanup(client.close)
            self.body["snapshot_id"] = str(uuid.uuid4()); self.snapshot = self.body["snapshot_id"]
            self.assertIn(client.post("/v1/transfers", json=self.body, headers=self.headers()).status_code, (429, 503))
        with patch("ecocapture_gateway.store.shutil.disk_usage", return_value=type("Usage", (), {"free": 0})()):
            self.assertEqual(503, self.client.post("/v1/transfers", json=self.body, headers=self.headers()).status_code)

    def test_stream_limit_without_content_length_and_duplicate_auth(self):
        transfer = self.create()
        response = self.client.put(f"/v1/transfers/{transfer}/objects/video.mp4/chunks/0",
            content=iter([b"x" * 32, b"y" * 33]), headers={**self.headers(),
                "Content-Type": "application/octet-stream", "X-Chunk-SHA256": self.digest(b"x" * 32 + b"y" * 33)})
        self.assertEqual(413, response.status_code)
        self.assertEqual(0, self.store.status(self.a, transfer)["objects"][2]["accepted_bytes"])
        self.assertEqual([], list(self.store.incoming.iterdir()))
        response = self.client.get(f"/v1/transfers/{transfer}", headers=[
            ("Authorization", "Bearer " + self.token_a), ("Authorization", "Bearer " + self.token_b)])
        self.assertEqual(400, response.status_code)

    def test_revocation_during_upload_prevents_commit(self):
        transfer = self.create()
        original = self.store.commit_chunk
        def revoke_then_commit(*args):
            self.store.revoke(self.a)
            return original(*args)
        with patch.object(self.store, "commit_chunk", side_effect=revoke_then_commit):
            self.assertEqual(403, self.chunk(transfer, "video.mp4", 0, self.video[:64]).status_code)
        self.assertFalse(self.store.path(transfer, "video.mp4").exists())
        self.assertEqual([], list(self.store.incoming.iterdir()))

    def test_small_chunks_and_chunk_record_capacity(self):
        transfer = self.create()
        self.assertEqual(400, self.chunk(transfer, "video.mp4", 0, self.video[:1]).status_code)
        self.assertEqual(200, self.chunk(transfer, "video.mp4", 0, self.video[:64]).status_code)
        limited = replace(self.config, max_chunk_records=1)
        client = TestClient(create_app(limited, Store(limited)), base_url="https://gateway.test")
        self.addCleanup(client.close)
        response = client.put(f"/v1/transfers/{transfer}/objects/video.mp4/chunks/64",
            content=self.video[64:], headers={**self.headers(), "Content-Type": "application/octet-stream",
                "X-Chunk-SHA256": self.digest(self.video[64:])})
        self.assertEqual(503, response.status_code)
        self.assertEqual(self.video[:64], self.store.path(transfer, "video.mp4").read_bytes())

    def test_failed_purge_keeps_reservation_until_retry(self):
        transfer = self.create()
        self.chunk(transfer, "video.mp4", 0, self.video[:64])
        with patch.object(self.store, "purge", side_effect=OSError("synthetic failure")):
            self.assertEqual(503, self.client.post(f"/v1/transfers/{transfer}/cancel", headers=self.headers()).status_code)
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT reserved FROM transfers WHERE id=?", (transfer,)).fetchone()[0])
        self.assertEqual(200, self.client.post(f"/v1/transfers/{transfer}/cancel", headers=self.headers()).status_code)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT reserved FROM transfers WHERE id=?", (transfer,)).fetchone()[0])

    def test_rate_limit(self):
        config = replace(self.config, requests_per_minute=1)
        client = TestClient(create_app(config, Store(config)), base_url="https://gateway.test")
        self.addCleanup(client.close)
        transfer = self.create()
        self.assertEqual(429, client.get(f"/v1/transfers/{transfer}", headers=self.headers()).status_code)

    def test_cleanup_is_bounded_and_never_purges_completed_receipts(self):
        transfer = self.create(); self.upload(transfer)
        self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers())
        self.snapshot = str(uuid.uuid4()); self.body["snapshot_id"] = self.snapshot
        incomplete = self.create()
        old = Store(self.config, clock=lambda: self.store.clock() + 2 * self.config.staging_ttl_seconds)
        self.assertEqual(1, old.cleanup()["purged_jobs"])
        self.assertTrue(self.store.directory(transfer).exists())
        self.assertEqual("PURGED", self.client.get(f"/v1/transfers/{incomplete}", headers=self.headers()).json()["state"])

    def test_exception_and_transport_logs_do_not_echo_secrets_or_text(self):
        secret_marker = "private-token-and-participant-text-marker"
        with patch.object(self.store, "status", side_effect=RuntimeError(secret_marker)):
            transfer = self.create()
            with self.assertLogs("probe", level="ERROR") as logs:
                logger = logging.getLogger("probe"); filter = RedactedLog(); logger.addFilter(filter)
                try: logger.exception(secret_marker)
                finally: logger.removeFilter(filter)
            response = self.client.get(f"/v1/transfers/{transfer}", headers=self.headers())
        self.assertEqual(503, response.status_code)
        self.assertNotIn(secret_marker, response.text + str(logs.output))
        self.assertNotIn(self.token_a, response.text)

    def test_packaged_schemas_match_android_contract(self):
        repo = Path(__file__).resolve().parents[2]
        for schema in (repo / "documentation/schemas").glob("*.json"):
            self.assertEqual(schema.read_bytes(), (repo / "server/ecocapture_gateway/schemas" / schema.name).read_bytes())

    def test_symlink_object_rejected(self):
        transfer = self.create()
        directory = self.store.directory(transfer); directory.mkdir()
        outside = self.root / "outside"; outside.write_bytes(b"unchanged")
        try:
            self.store.path(transfer, "video.mp4").symlink_to(outside)
        except OSError:
            self.skipTest("Windows symbolic-link privilege unavailable")
        self.assertEqual(500, self.chunk(transfer, "video.mp4", 0, b"x").status_code)
        self.assertEqual(b"unchanged", outside.read_bytes())


if __name__ == "__main__":
    unittest.main()
