import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import shutil
import unittest
from unittest.mock import patch
import uuid

from starlette.testclient import TestClient
from ecocapture_gateway.app import create_app
from ecocapture_gateway.config import Config
from ecocapture_gateway.store import Store
from ecocapture_gateway.errors import GatewayError
from ecocapture_gateway.forwarding import Forwarder, SYNTHETIC_POLICY, NAME, RECIPIENT
from ecocapture_gateway.synthetic_fixture import files
from ecocapture_gateway.archive_encryptor import measure, deterministic_tar
from ecocapture_gateway.filesender_adapter import FileSenderClient, signed_request

REPO = Path(__file__).resolve().parents[2]
AGE = REPO / "tools/phase3-protocol/artifacts/age.exe"
KEYGEN = AGE.with_name("age-keygen.exe")
if not AGE.is_file(): AGE = Path(shutil.which("age") or "/not-installed/age")
if not KEYGEN.is_file(): KEYGEN = Path(shutil.which("age-keygen") or "/not-installed/age-keygen")


class FakeFileSender:
    username = RECIPIENT
    def __init__(self):
        self.created = 0; self.chunks = []; self.data = bytearray(); self.subject = None
        self.available = False; self.file_size = 0; self.fail_create = False; self.fail_chunk = False
        self.fail_finalize = False; self.fail_owner_get = False; self.omit_status = False
        self.finalized = 0

    def call(self, method, path, parameters=None, payload=None, raw=None):
        if path == "/info": return {"upload_chunk_size": 65536}
        if method == "post":
            self.created += 1; self.subject = payload["subject"]; self.file_size = payload["files"][0]["size"]
            if self.fail_create: raise GatewayError(503, "FILESENDER_NETWORK_OR_TLS_FAILURE")
            return {"id": 19}
        if method == "get":
            if self.fail_owner_get: self.fail_owner_get = False; raise GatewayError(503, "FILESENDER_REMOTE_UNAVAILABLE")
            row = {"id": 19, "subject": self.subject,
                   "files": [{"id": 21, "name": NAME, "size": self.file_size}],
                   "recipients": [{"email": RECIPIENT}], "expires": {"raw": 2000000000}}
            if not self.omit_status: row["status"] = "available" if self.available else "uploading"
            return row
        if "/chunk/" in path:
            offset = int(path.rsplit("/", 1)[1]); self.chunks.append(offset)
            self.data[offset:offset+len(raw)] = raw
            if self.fail_chunk: self.fail_chunk = False; raise GatewayError(503, "FILESENDER_RATE_LIMIT")
            return {"id": 21}
        if path == "/file/21": return True
        self.available = True; self.finalized += 1
        if self.fail_finalize: self.fail_finalize = False; raise GatewayError(503, "FILESENDER_NETWORK_OR_TLS_FAILURE")
        return {"id": 19}


class ForwardingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = Store(Config(self.root / "state", allowed_hosts=("gateway.test",)))
        self.installation = str(uuid.uuid4())
        self.token = self.store.issue(self.installation, SYNTHETIC_POLICY, eligible=True)
        self.event_id = str(uuid.uuid4()); self.snapshot_id = str(uuid.uuid4())
        self.files = files(self.event_id, self.snapshot_id)
        self.client = TestClient(create_app(self.store.config, self.store), base_url="https://gateway.test")
        self.addCleanup(self.client.close)
        self.headers = {"Authorization": "Bearer " + self.token, "Idempotency-Key": self.snapshot_id}
        self.forwarder = Forwarder(self.store)
        self.recipient = "age1k4d08sjgx3twvq2fffmyedkqxav8ttqtlu58zllz3dv0rlfk7pqs7w3u8d"

    def snapshot(self):
        body = {"schema": "ecocapture.gateway.transfer.v1", "snapshot_id": self.snapshot_id,
            "event_id": self.event_id, "objects": [{"name": name, "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                for name, data in self.files.items()]}
        response = self.client.post("/v1/transfers", json=body, headers=self.headers)
        self.assertEqual(201, response.status_code)
        transfer = response.json()["gateway_transfer_id"]
        for name, data in self.files.items():
            response = self.client.put(f"/v1/transfers/{transfer}/objects/{name}/chunks/0", content=data,
                headers={**self.headers, "Content-Type": "application/octet-stream", "X-Chunk-SHA256": hashlib.sha256(data).hexdigest()})
            self.assertEqual(200, response.status_code)
        self.assertEqual("MOCK_FORWARDED", self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers).json()["status"])
        return transfer

    def prepare(self, transfer):
        # State-machine tests use a fake encryptor; separate test uses the real pinned age.
        def encrypt(executable, source, destination, recipient, max_bytes):
            destination.write_bytes(b"age-encryption.org/v1\n" + source.read_bytes())
            return measure(destination)
        with patch("ecocapture_gateway.forwarding.age_encrypt", side_effect=encrypt):
            return self.forwarder.prepare(transfer, "not-executed", self.recipient, "checkpoint1")

    def test_roundtrip_mock_remote_receipt_and_source_staging_cleanup(self):
        transfer = self.snapshot(); before = self.prepare(transfer)
        self.assertEqual("READY_TO_SEND", before["status"])
        cipher = self.forwarder.directory(transfer) / NAME
        expected = cipher.read_bytes()
        remote = FakeFileSender()
        result = self.forwarder.send(transfer, remote)
        self.assertEqual("FILESENDER_COMPLETE", result["status"])
        self.assertEqual(expected, bytes(remote.data))
        self.assertFalse(self.store.directory(transfer).exists())
        self.assertFalse((cipher.parent / "approved-event.tar").exists())
        self.assertTrue(cipher.exists())
        self.assertEqual(1, remote.created)
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.finalized)
        receipt = self.client.get(f"/v1/transfers/{transfer}/receipt", headers=self.headers).json()
        self.assertFalse(receipt["archived"]); self.assertFalse(receipt["researcher_verified"])
        self.assertFalse(receipt["mock"]); self.assertTrue(receipt["plaintext_staging_removed"])
        self.assertEqual(19, receipt["filesender_transfer_id"])
        self.assertEqual(receipt, self.client.post(f"/v1/transfers/{transfer}/complete", headers=self.headers).json())

    def test_creation_timeout_never_recreates_remote_and_adoption_is_verified(self):
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_create = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.created)
        remote.subject = "wrong subject"
        with self.assertRaises(GatewayError): self.forwarder.reconcile(transfer, remote, remote_id=19)
        remote.subject = "EcoCapture encrypted synthetic " + transfer
        self.forwarder.reconcile(transfer, remote, remote_id=19)
        remote.fail_create = False
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.created)

    def test_creation_recovery_requires_acceptance_and_exact_ciphertext(self):
        from unittest.mock import Mock
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_create = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        recovery = Mock()
        before = self.forwarder.info(transfer)
        with self.assertRaises(GatewayError) as error:
            self.forwarder.authorize_creation_retry(transfer, recovery, before["ciphertext_sha256"])
        self.assertEqual("POSSIBLE_ORPHAN_ACCEPTANCE_REQUIRED", error.exception.code)
        with self.assertRaises(GatewayError) as error:
            self.forwarder.authorize_creation_retry(transfer, recovery, "0" * 64, True)
        self.assertEqual("RECOVERY_CIPHERTEXT_MISMATCH", error.exception.code)
        recovery.call.assert_not_called()
        self.assertEqual(before, self.forwarder.info(transfer))

    def test_creation_recovery_is_audited_once_without_remote_mutation(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_create = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        before = self.forwarder.info(transfer)
        recovery = SimpleNamespace(now=self.store.clock, call=Mock(return_value=[]))
        result = self.forwarder.authorize_creation_retry(transfer, recovery, before["ciphertext_sha256"], True)
        self.assertEqual("CREATION_RETRY_AUTHORIZED", result["status"])
        self.assertFalse(result["remote_absence_verified"])
        recovery.call.assert_called_once_with("get", "/transfer/@me")
        self.assertEqual(1, remote.created)
        self.assertEqual("READY_TO_SEND", self.forwarder.info(transfer)["status"])
        self.assertEqual(before["ciphertext_sha256"], self.forwarder.info(transfer)["ciphertext_sha256"])
        self.forwarder = Forwarder(Store(self.store.config))
        with self.store.connect() as db:
            audit = db.execute("SELECT * FROM forwarding_creation_recovery WHERE transfer_id=?", (transfer,)).fetchone()
            self.assertEqual("REMOTE_CREATION_PENDING", audit["prior_state"])
            self.assertEqual(0, audit["remote_absence_verified"])
            self.assertEqual(before["ciphertext_sha256"], audit["cipher_sha"])
        # A second ambiguous attempt never permits an endless creation loop.
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        with self.assertRaises(GatewayError) as error:
            self.forwarder.authorize_creation_retry(transfer, recovery, before["ciphertext_sha256"], True)
        self.assertEqual("CREATION_RECOVERY_ALREADY_USED", error.exception.code)
        self.assertEqual("REMOTE_CREATION_PENDING", self.forwarder.info(transfer)["status"])

    def test_creation_recovery_rejects_existing_available_transfer(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_create = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        before = self.forwarder.info(transfer)
        recovery = SimpleNamespace(now=self.store.clock, call=Mock(return_value=[
            {"id": 19, "subject": "EcoCapture encrypted synthetic " + transfer}]))
        with self.assertRaises(GatewayError) as error:
            self.forwarder.authorize_creation_retry(transfer, recovery, before["ciphertext_sha256"], True)
        self.assertEqual("EXISTING_TRANSFER_REQUIRES_ADOPTION", error.exception.code)
        self.assertEqual(before, self.forwarder.info(transfer))

    def test_chunk_error_and_restart_resume_same_remote_and_frozen_ciphertext(self):
        transfer = self.snapshot(); self.prepare(transfer)
        before = self.forwarder.info(transfer)["ciphertext_sha256"]
        remote = FakeFileSender(); remote.fail_chunk = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.forwarder = Forwarder(Store(self.store.config))
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.created)
        self.assertEqual([0, 0], remote.chunks[:2])
        self.assertEqual(before, self.forwarder.info(transfer)["ciphertext_sha256"])

    def test_ambiguous_finalization_uses_available_state_without_duplicate_notification(self):
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_finalize = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.finalized)

    def test_missing_status_after_lost_finalize_requires_operator_reconciliation(self):
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.fail_finalize = True; remote.omit_status = True
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.finalized)
        self.forwarder.reconcile(transfer, remote, confirm_available=True)
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.finalized)

    def test_frozen_policy_artifact_change_and_concurrent_worker_rejected(self):
        transfer = self.snapshot(); self.prepare(transfer)
        with self.assertRaises(GatewayError): self.forwarder.prepare(transfer, "unused", self.recipient, "new-key")
        with self.forwarder.lock(transfer):
            with self.assertRaises(GatewayError): self.forwarder.send(transfer, FakeFileSender())
        (self.forwarder.directory(transfer) / NAME).write_bytes(b"changed")
        remote = FakeFileSender()
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.assertEqual(0, remote.created)

    def test_real_participant_like_content_and_other_policy_rejected(self):
        # Structurally valid scientific content outside the exact fixture must fail too.
        event = json.loads(self.files["event.json"]); event["capture"]["created_by_app_version"] = "actual-app"
        self.files["event.json"] = json.dumps(event, separators=(",", ":")).encode()
        manifest = json.loads(self.files["manifest.json"])
        manifest["files"][1]["size_bytes"] = len(self.files["event.json"])
        manifest["files"][1]["sha256"] = hashlib.sha256(self.files["event.json"]).hexdigest()
        self.files["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
        transfer = self.snapshot()
        with self.assertRaises(GatewayError): self.prepare(transfer)
        with self.store.transaction() as db:
            db.execute("UPDATE installations SET policy='synthetic-checkpoint4-v1' WHERE id=?", (self.installation,))
        with self.assertRaises(GatewayError): self.prepare(transfer)

    def test_cancel_blocks_forwarding_and_discards_only_safe_local_artifacts(self):
        transfer = self.snapshot(); self.prepare(transfer)
        self.assertEqual(409, self.client.post(f"/v1/transfers/{transfer}/cancel", headers=self.headers).status_code)
        result = self.forwarder.discard(transfer)
        self.assertFalse(result["remote_deletion_claimed"])
        remote = FakeFileSender()
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.assertEqual(0, remote.created)

    def test_revocation_before_send_and_pending_receipt_fail_closed(self):
        transfer = self.snapshot(); self.prepare(transfer)
        self.assertEqual(409, self.client.get(f"/v1/transfers/{transfer}/receipt", headers=self.headers).status_code)
        self.store.revoke(self.installation)
        remote = FakeFileSender()
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.assertEqual(0, remote.created)

    def test_preparation_disk_and_artifact_quotas_reject_without_new_job(self):
        transfer = self.snapshot()
        with patch("ecocapture_gateway.forwarding.shutil.disk_usage", return_value=type("Usage", (), {"free": 0})()):
            with self.assertRaises(GatewayError) as error: self.prepare(transfer)
            self.assertEqual("FORWARDING_DISK_CAPACITY", error.exception.code)
        from dataclasses import replace
        limited = Forwarder(Store(replace(self.store.config, max_total_bytes=500000)))
        with self.assertRaises(GatewayError) as error:
            limited.prepare(transfer, "unused", self.recipient, "checkpoint1")
        self.assertEqual("FORWARDING_STORAGE_QUOTA", error.exception.code)
        with self.store.connect() as db: self.assertEqual(0, db.execute("SELECT COUNT(*) FROM forwarding_jobs").fetchone()[0])

    def test_expiry_validation_uses_worker_clock_without_extending_policy(self):
        transfer = self.snapshot(); self.prepare(transfer)
        with self.store.connect() as db:
            policy = json.loads(db.execute("SELECT policy FROM forwarding_jobs WHERE transfer_id=?", (transfer,)).fetchone()[0])
        remote = FakeFileSender()
        remote.now = lambda: policy["expires_at"] + 1
        with self.assertRaises(GatewayError) as error:
            self.forwarder.send(transfer, remote)
        self.assertEqual("REQUESTED_EXPIRY_ELAPSED", error.exception.code)
        self.assertEqual(0, remote.created)
        with self.store.connect() as db:
            self.assertEqual(policy, json.loads(db.execute("SELECT policy FROM forwarding_jobs WHERE transfer_id=?", (transfer,)).fetchone()[0]))

    def test_public_info_unsigned_but_owner_requests_remain_signed(self):
        import io
        import urllib.parse
        ini = self.root / "synthetic-filesender.ini"
        ini.write_text("[system]\nbase_url = https://filesender.aarnet.edu.au/rest.php\n[user]\nusername = synthetic@example.invalid\napikey = dummy-secret-marker\n")
        ini.chmod(0o600)
        client = FileSenderClient(ini, live_authorized=True)
        requests = []
        def respond(request, timeout):
            requests.append(request)
            response = io.BytesIO(b'{"upload_chunk_size":65536}')
            response.status = 200
            response.geturl = lambda: request.full_url
            return response
        with patch.object(client.opener, "open", side_effect=respond):
            self.assertEqual(65536, client.call("get", "/info")["upload_chunk_size"])
            client.call("get", "/transfer/21")
            with self.assertRaises(GatewayError):
                client.call("get", "/info", parameters={"unexpected": "argument"})
        self.assertEqual(2, len(requests))
        self.assertEqual("https://filesender.aarnet.edu.au/rest.php/info", requests[0].full_url)
        owner_query = urllib.parse.parse_qs(urllib.parse.urlsplit(requests[1].full_url).query)
        self.assertEqual([client.username], owner_query["remote_user"])
        self.assertIn("signature", owner_query)
        self.assertIn("timestamp", owner_query)

    def test_remote_errors_redacted_and_credentials_are_never_persisted(self):
        ini = self.root / "synthetic-filesender.ini"
        ini.write_text("[system]\nbase_url = https://filesender.aarnet.edu.au/rest.php\n[user]\nusername = synthetic@example.invalid\napikey = dummy-secret-marker\n")
        ini.chmod(0o600)
        client = FileSenderClient(ini, live_authorized=True)
        import urllib.error
        import io
        failure = urllib.error.HTTPError("https://example.invalid/?secret=dummy-secret-marker", 500,
                                          "secret scientific text", {}, io.BytesIO(b"private response"))
        with patch.object(client.opener, "open", side_effect=failure):
            with self.assertRaises(GatewayError) as error: client.call("get", "/info")
        self.assertEqual("FILESENDER_REMOTE_UNAVAILABLE", str(error.exception))
        self.assertTrue(failure.fp is None or failure.fp.closed)
        self.assertNotIn("dummy-secret", str(error.exception))

    def test_researcher_verifier_matches_source_and_rejects_wrong_identity(self):
        if not AGE.is_file() or not KEYGEN.is_file(): self.skipTest("Pinned local age tools unavailable")
        from ecocapture_gateway.verify_researcher import verify_received
        identity = self.root / "ephemeral-researcher-key.txt"
        wrong = self.root / "ephemeral-wrong-key.txt"
        generated = subprocess.run([str(KEYGEN), "-o", str(identity)], capture_output=True, check=True)
        subprocess.run([str(KEYGEN), "-o", str(wrong)], capture_output=True, check=True)
        import re
        self.recipient = re.search(rb"age1[0-9a-z]+", generated.stderr).group().decode()
        transfer = self.snapshot()
        result = self.forwarder.prepare(transfer, AGE, self.recipient, "disposable-test")
        expected = self.root / "expected.json"
        expected.write_text(json.dumps({**result, "participant_data": False, "files": {
            name: {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in self.files.items()}}))
        ciphertext = self.forwarder.directory(transfer) / NAME
        checked = verify_received(ciphertext, expected, AGE, identity, self.root / "checked")
        self.assertTrue(checked["researcher_verified"]); self.assertFalse(checked["archived"])
        with self.assertRaises(GatewayError) as error:
            verify_received(ciphertext, expected, AGE, wrong, self.root / "wrong-check")
        self.assertEqual("RESEARCHER_DECRYPTION_FAILED", error.exception.code)
        self.assertFalse((self.root / "wrong-check/decrypted.part").exists())

    def test_new_metadata_table_migration_preserves_all_existing_rows(self):
        transfer = self.snapshot()
        tables = ("installations", "transfers", "objects", "chunks", "mock_intents", "rates")
        def inventory():
            with self.store.connect() as db:
                return {table: [tuple(r) for r in db.execute("SELECT * FROM " + table + " ORDER BY rowid")] for table in tables}
        before = inventory()
        with self.store.transaction() as db: db.execute("DROP TABLE forwarding_jobs")
        Store(self.store.config)
        self.assertEqual(before, inventory())
        with self.store.connect() as db: self.assertEqual(0, db.execute("SELECT COUNT(*) FROM forwarding_jobs").fetchone()[0])

    def test_persisted_finalize_ack_allows_metadata_retry_without_email_replay(self):
        transfer = self.snapshot(); self.prepare(transfer)
        remote = FakeFileSender(); remote.omit_status = True
        original = remote.call
        def fail_verification_once(method, path, **kwargs):
            result = original(method, path, **kwargs)
            if method == "put" and path == "/transfer/19": remote.fail_owner_get = True
            return result
        remote.call = fail_verification_once
        with self.assertRaises(GatewayError): self.forwarder.send(transfer, remote)
        self.forwarder.send(transfer, remote)
        self.assertEqual(1, remote.created); self.assertEqual(1, remote.finalized)

    def test_adapter_credentials_need_explicit_authorization_before_file_read(self):
        with self.assertRaises(GatewayError) as error: FileSenderClient(Path("not-read.ini"))
        self.assertEqual("LIVE_CREDENTIAL_USE_NOT_AUTHORIZED", error.exception.code)

    def test_deterministic_three_member_archive_and_actual_age_researcher_decrypt(self):
        if not AGE.is_file() or not KEYGEN.is_file(): self.skipTest("Pinned local age tools unavailable")
        identity = self.root / "disposable-test-key.txt"
        generated = subprocess.run([str(KEYGEN), "-o", str(identity)], capture_output=True, check=True)
        import re
        self.recipient = re.search(rb"age1[0-9a-z]+", generated.stderr).group().decode()
        transfer = self.snapshot()
        result = self.forwarder.prepare(transfer, AGE, self.recipient, "disposable-test-key")
        source = self.store.directory(transfer)
        expected = {name: {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in self.files.items()}
        repeated = deterministic_tar(source, self.root / "repeated.tar", expected)
        self.assertEqual(result["archive_sha256"], repeated["sha256"])
        decrypted = self.root / "decrypted.tar"
        subprocess.run([str(AGE), "--decrypt", "-i", str(identity), "-o", str(decrypted), str(self.forwarder.directory(transfer) / NAME)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual(result["archive_sha256"], measure(decrypted)["sha256"])
        import tarfile
        with tarfile.open(decrypted) as archive:
            self.assertEqual(["event.json", "manifest.json", "video.mp4"], archive.getnames())
            for name, data in self.files.items(): self.assertEqual(data, archive.extractfile(name).read())

    def test_signing_matches_hardened_reference_and_has_fixed_origin(self):
        outgoing = signed_request("synthetic@example.invalid", "dummy-api-secret", "put", "/file/21/chunk/0", raw=b"cipher")
        self.assertTrue(outgoing.full_url.startswith("https://filesender.aarnet.edu.au/rest.php/file/21/chunk/0?"))
        self.assertEqual(b"cipher", outgoing.data)
        self.assertEqual("PUT", outgoing.method)
        with self.assertRaises(GatewayError): signed_request("u", "s", "get", "//attacker.invalid")
        # Cross-check the already reviewed Checkpoint 1 implementation, never tools/filesender.py.
        if not (REPO / "tools/phase3-protocol/checkpoint1_send_test.py").is_file(): return
        import sys
        sys.path.insert(0, str(REPO / "tools/phase3-protocol"))
        try:
            from checkpoint1_send_test import request
            with patch("time.time", return_value=1800000000):
                reference = request("synthetic@example.invalid", "dummy-api-secret", "put", "/file/21/chunk/0", raw=b"cipher")
                outgoing = signed_request("synthetic@example.invalid", "dummy-api-secret", "put", "/file/21/chunk/0", raw=b"cipher")
            self.assertEqual(reference.full_url, outgoing.full_url)
            self.assertEqual(reference.data, outgoing.data)
        finally: sys.path.pop(0)

    def test_checkpoint5_public_uploader_creates_exact_fixture_idempotently(self):
        import importlib.util
        import sys
        directory = REPO / "server/deployment/gb10"
        sys.path.insert(0, str(directory))
        try:
            spec = importlib.util.spec_from_file_location("checkpoint5_probe", directory / "checkpoint5_probe.py")
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        finally: sys.path.pop(0)
        def request(method, path, token=None, body=None, headers=None):
            headers = dict(headers or {})
            if token: headers["Authorization"] = "Bearer " + token
            response = self.client.request(method, path, content=body, headers=headers)
            return response.status_code, response.json()
        value = {"installation_id": self.installation, "token": self.token}
        first = module.upload(request, value)
        repeated = module.upload(request, value)
        self.assertEqual(first, repeated)
        self.prepare(first["gateway_transfer_id"])
        self.assertEqual("READY_TO_SEND", self.forwarder.info(first["gateway_transfer_id"])["status"])
