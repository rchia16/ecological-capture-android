import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import uuid
import zipfile
from unittest.mock import patch

from starlette.testclient import TestClient
from ecocapture_gateway.archive_encryptor import measure
from ecocapture_gateway.config import Config
from ecocapture_gateway.errors import GatewayError
from ecocapture_gateway.synthetic_fixture import files
from ecocapture_direct.config import DirectConfig
from ecocapture_direct.store import DirectStore
from ecocapture_direct.app import public_app, researcher_app
from ecocapture_direct.receiver import receiver

REPO = Path(__file__).resolve().parents[2]
AGE = Path(shutil.which("age") or REPO / "tools/phase3-protocol/artifacts/age.exe")
KEYGEN = Path(shutil.which("age-keygen") or AGE.with_name("age-keygen.exe"))


class DirectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.keys.cleanup)
        cls.public_keys = []
        for name in ("gb10", "recovery"):
            identity = Path(cls.keys.name) / (name + ".agekey")
            subprocess.run([str(KEYGEN), "-o", str(identity)], check=True, capture_output=True)
            public = subprocess.run([str(KEYGEN), "-y", str(identity)], check=True, capture_output=True)
            cls.public_keys.append(public.stdout.decode().strip())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.config = DirectConfig(Config(Path(self.temp.name) / "store", ("gateway.test",), max_chunk_bytes=65536),
            "study", tuple(self.public_keys), ("gb10-v1", "recovery-v1"), AGE, Path(self.keys.name) / "gb10.agekey")
        self.store = DirectStore(self.config)
        self.owner = str(uuid.uuid4()); self.token = secrets.token_urlsafe(32)
        self.code = self.store.issue_code("P014")
        self.enrolment = {"code": self.code, "installation_id": self.owner, "token": self.token}
        self.public = TestClient(public_app(self.store), base_url="https://gateway.test", raise_server_exceptions=False)
        self.addCleanup(self.public.close)
        enrolled = self.public.post("/v2/enrol", json=self.enrolment)
        self.assertEqual(200, enrolled.status_code, enrolled.text)
        self.headers = {"Authorization": "Bearer " + self.token}
        self.login = "researcher@example.test"; self.store.grant(self.login)
        self.private = TestClient(researcher_app(self.store), base_url="https://gateway.test:9443", client=("127.0.0.1", 50000),
                                  headers={"Tailscale-User-Login": self.login}, raise_server_exceptions=False)
        self.addCleanup(self.private.close)
        self.event, self.snapshot = str(uuid.uuid4()), str(uuid.uuid4())
        self.source = files(self.event, self.snapshot)
        event = json.loads(self.source["event.json"]); event["study_participant_code"] = "P014"
        self.source["event.json"] = json.dumps(event).encode()
        manifest = json.loads(self.source["manifest.json"])
        manifest["files"][1].update(size_bytes=len(self.source["event.json"]), sha256=hashlib.sha256(self.source["event.json"]).hexdigest())
        self.source["manifest.json"] = json.dumps(manifest).encode()
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, data in self.source.items():
                member = tarfile.TarInfo(name); member.size = len(data); archive.addfile(member, io.BytesIO(data))
        self.cipher = output.getvalue()
        self.body = {"snapshot_id": self.snapshot, "event_id": self.event, "recorded_at_epoch_ms": 1000,
            "size_bytes": len(self.cipher), "ciphertext_sha256": hashlib.sha256(self.cipher).hexdigest(),
            "manifest_sha256": hashlib.sha256(self.source["manifest.json"]).hexdigest(), "key_versions": ["gb10-v1", "recovery-v1"]}

    def register(self):
        result = self.public.post("/v2/packages", json=self.body, headers=self.headers)
        self.assertEqual(200, result.status_code, result.text)
        return result.json()

    def chunk(self, offset, data):
        return self.public.put(f"/v2/packages/{self.snapshot}/chunks/{offset}", content=data,
            headers={**self.headers, "Content-Type": "application/octet-stream", "X-Chunk-SHA256": hashlib.sha256(data).hexdigest()})

    def uploaded(self):
        self.register()
        for offset in range(0, len(self.cipher), 65536):
            response = self.chunk(offset, self.cipher[offset:offset + 65536])
            self.assertEqual(200, response.status_code, response.text)
        response = self.public.post(f"/v2/packages/{self.snapshot}/complete", headers=self.headers)
        self.assertEqual(200, response.status_code, response.text)

    def verified(self):
        self.uploaded()
        verifier = receiver(self.store, decrypt=shutil.copyfile)
        try: verifier.once()
        finally: verifier.close()
        result = self.public.get(f"/v2/packages/{self.snapshot}", headers=self.headers)
        self.assertEqual("VERIFIED_GB10", result.json()["status"], result.text)
        return result.json()

    def test_enrolment_is_retryable_only_with_same_device_proof_and_hashes_only(self):
        self.assertEqual(200, self.public.post("/v2/enrol", json=self.enrolment).status_code)
        self.assertEqual(403, self.public.post("/v2/enrol", json={**self.enrolment, "token": secrets.token_urlsafe(32)}).status_code)
        self.assertEqual(403, self.public.post("/v2/enrol", json={**self.enrolment, "installation_id": str(uuid.uuid4())}).status_code)
        with self.store.connect() as db:
            content = str([tuple(row) for row in db.execute("SELECT * FROM installations")])
        self.assertNotIn(self.token, content)

    def test_resumes_after_restart_replays_and_rejects_offset_conflicts(self):
        self.register(); first = self.cipher[:65536]
        self.assertEqual(200, self.chunk(0, first).status_code)
        self.assertTrue(self.chunk(0, first).json()["replayed"])
        self.assertEqual(409, self.chunk(1, first).status_code)
        reopened = DirectStore(self.config)
        self.assertEqual(65536, reopened.status(self.owner, self.snapshot)["accepted_bytes"])
        # Simulate bytes written before their transaction committed; the next chunk truncates that tail.
        with reopened.cipher(self.snapshot).open("ab") as stream: stream.write(b"uncommitted")
        for offset in range(65536, len(self.cipher), 65536): self.assertEqual(200, self.chunk(offset, self.cipher[offset:offset + 65536]).status_code)
        self.assertEqual(self.body["ciphertext_sha256"], measure(reopened.cipher(self.snapshot))["sha256"])

    def test_owner_isolation_revocation_and_registration_conflicts(self):
        original = self.register()
        self.assertEqual(original, self.register())
        bad = self.public.post("/v2/packages", json={**self.body, "ciphertext_sha256": "a" * 64}, headers=self.headers)
        self.assertEqual(409, bad.status_code)
        other, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
        self.store.enrol({"code": self.store.issue_code("P015"), "installation_id": other, "token": token})
        self.assertEqual(404, self.public.get(f"/v2/packages/{self.snapshot}", headers={"Authorization": "Bearer " + token}).status_code)
        self.store.revoke(self.owner)
        self.assertEqual(403, self.public.get("/v2/profile", headers=self.headers).status_code)

    def test_complete_does_not_claim_archival_and_is_idempotent(self):
        self.uploaded()
        result = self.public.post(f"/v2/packages/{self.snapshot}/complete", headers=self.headers)
        self.assertEqual("AWAITING_GB10", result.json()["status"])
        self.assertFalse(result.json()["archived"])
        self.assertEqual(409, self.public.post(f"/v2/packages/{self.snapshot}/cancel", headers=self.headers).status_code)

    def test_bad_hash_fails_without_verified_publication(self):
        self.body["ciphertext_sha256"] = "0" * 64
        self.register()
        for offset in range(0, len(self.cipher), 65536): self.chunk(offset, self.cipher[offset:offset + 65536])
        self.assertEqual(409, self.public.post(f"/v2/packages/{self.snapshot}/complete", headers=self.headers).status_code)
        self.assertEqual([], self.store.pending())

    def test_disk_and_total_reservations_reject_before_creation(self):
        from dataclasses import replace
        constrained = DirectStore(replace(self.config, gateway=replace(self.config.gateway, max_total_bytes=1)))
        with self.assertRaises(GatewayError) as error: constrained.register(self.owner, self.body)
        self.assertEqual("STORAGE_QUOTA", error.exception.code)
        with patch("ecocapture_direct.store.shutil.disk_usage", return_value=shutil._ntuple_diskusage(100, 99, 1)):
            result = self.public.post("/v2/packages", json=self.body, headers=self.headers)
        self.assertEqual(503, result.status_code)
        self.assertEqual([], self.store.catalogue({})["items"])

    def test_archive_requires_independent_verified_copy_and_never_deletes(self):
        received = self.verified()
        directory = self.store.root / "received" / received["gb10_path"]
        destination = Path(self.temp.name) / "approved-share-copy"
        shutil.copytree(directory, destination)
        with self.assertRaises(GatewayError): self.store.record_archive(self.snapshot, directory, "UTS-share")
        (destination / "video.mp4").write_bytes(b"corrupt copy")
        with self.assertRaises(Exception): self.store.record_archive(self.snapshot, destination, "UTS-share")
        self.assertFalse(self.store.status(self.owner, self.snapshot)["archived"])
        shutil.copyfile(directory / "video.mp4", destination / "video.mp4")
        self.store.record_archive(self.snapshot, destination, "UTS-share")
        self.store.record_archive(self.snapshot, destination, "UTS-share")
        self.assertTrue(self.store.status(self.owner, self.snapshot)["archived"])
        self.assertTrue(directory.is_dir()); self.assertTrue(self.store.cipher(self.snapshot).is_file())

    def test_versions_share_notes_but_have_independent_reviews(self):
        self.verified()
        original = self.snapshot
        self.store.notes(self.login, self.event, {"revision": 0, "tags": ["Outdoor"], "notes": "Event note"})
        self.store.review(self.login, original, {"revision": 0, "review_status": "REVIEWED"})
        self.body = {**self.body, "snapshot_id": str(uuid.uuid4())}
        self.store.register(self.owner, self.body)
        rows = self.store.catalogue({"versions": "all"})["items"]
        self.assertEqual(2, len(rows))
        self.assertTrue(all(row["notes"] == "Event note" for row in rows))
        self.assertEqual({"TO_REVIEW", "REVIEWED"}, {row["review_status"] for row in rows})
        self.assertEqual(1, len(self.store.catalogue({})["items"]))

    def test_receipt_survives_restart_and_revoked_researchers_cannot_edit(self):
        self.verified()
        restarted = DirectStore(self.config)
        verifier = receiver(restarted, decrypt=shutil.copyfile)
        try: verifier.once()
        finally: verifier.close()
        self.assertEqual("VERIFIED_GB10", restarted.status(self.owner, self.snapshot)["status"])
        restarted.grant(self.login, False)
        self.assertEqual(403, self.private.get("/research/api/catalogue").status_code)
        with self.assertRaises(GatewayError):
            restarted.notes(self.login, self.event, {"revision": 0, "tags": [], "notes": "Denied"})

    def test_public_requests_cannot_access_catalogue_even_with_forged_identity(self):
        self.verified()
        for path in ("/", "/research/api/catalogue", f"/research/api/packages/{self.snapshot}/download"):
            response = self.public.get(path, headers={**self.headers, "Tailscale-User-Login": self.login})
            self.assertIn(response.status_code, (401, 404))
            self.assertNotIn("video.mp4", response.text)
        self.assertEqual(403, self.private.get("/research/api/me", headers={"Tailscale-User-Login": "unknown@example.test"}).status_code)
        remote = TestClient(researcher_app(self.store), base_url="https://gateway.test:9443", client=("100.64.0.2", 123), headers={"Tailscale-User-Login": self.login})
        try: self.assertEqual(403, remote.get("/").status_code)
        finally: remote.close()

    def test_notes_conflicts_audit_and_original_bytes_unchanged(self):
        received = self.verified()
        directory = self.store.root / "received" / received["gb10_path"]
        before = {name: measure(directory / name) for name in self.source}
        body = {"tags": ["Navigation", "Outdoor"], "notes": "Shared researcher note", "revision": 0}
        path = f"/research/api/events/{self.event}/notes"
        self.assertEqual(403, self.private.put(path, json=body).status_code)
        headers = {"Origin": "https://gateway.test:9443"}
        saved = self.private.put(path, json=body, headers=headers)
        self.assertEqual(200, saved.status_code, saved.text)
        self.assertEqual(409, self.private.put(path, json=body, headers=headers).status_code)
        catalogue = self.private.get("/research/api/catalogue?tag=Outdoor").json()["items"]
        self.assertEqual("Shared researcher note", catalogue[0]["notes"])
        self.assertEqual(1, len(self.private.get(f"/research/api/audit/{self.event}").json()["items"]))
        self.assertEqual(before, {name: measure(directory / name) for name in self.source})

    def test_review_and_zip_are_private_and_preserve_exact_files(self):
        self.verified()
        result = self.private.put(f"/research/api/packages/{self.snapshot}/review", json={"revision": 0, "review_status": "REVIEWED"},
                                  headers={"Origin": "https://gateway.test:9443"})
        self.assertEqual(200, result.status_code, result.text)
        listing = self.private.get("/research/api/catalogue?review=REVIEWED")
        self.assertEqual(1, len(listing.json()["items"]))
        downloaded = self.private.get(f"/research/api/packages/{self.snapshot}/download")
        self.assertEqual(200, downloaded.status_code, downloaded.text[:100] if downloaded.status_code != 200 else "")
        with zipfile.ZipFile(io.BytesIO(downloaded.content)) as archive:
            self.assertEqual(set(self.source), set(archive.namelist()))
            for name, data in self.source.items(): self.assertEqual(data, archive.read(name))
        self.assertEqual("no-store", downloaded.headers["cache-control"])

    def test_cancel_removes_only_incomplete_cipher_and_blocks_resume(self):
        self.register(); self.chunk(0, self.cipher[:65536])
        self.assertEqual(200, self.public.post(f"/v2/packages/{self.snapshot}/cancel", headers=self.headers).status_code)
        self.assertFalse(self.store.cipher(self.snapshot).exists())
        self.assertEqual(409, self.chunk(65536, self.cipher[65536:131072]).status_code)

    @unittest.skipUnless(os.environ.get("ECO_CRYPTO_INTEROP"), "Build synthetic Go interop harness")
    def test_actual_phone_encryption_to_direct_receiving_and_download(self):
        source = Path(self.temp.name) / "source"; source.mkdir()
        for name, data in self.source.items(): (source / name).write_bytes(data)
        encrypted = Path(self.temp.name) / "encrypted"
        subprocess.run([os.environ["ECO_CRYPTO_INTEROP"], str(source), str(encrypted)], check=True, capture_output=True)
        self.cipher = (encrypted / "approved-event.tar.age").read_bytes()
        self.body.update(size_bytes=len(self.cipher), ciphertext_sha256=hashlib.sha256(self.cipher).hexdigest())
        self.uploaded()
        # The test archive uses disposable keys generated by the phone-code harness.
        from dataclasses import replace
        self.store.direct = replace(self.config, identity=encrypted / "gb10.agekey")
        verifier = receiver(self.store)
        try: verifier.once(); verifier.once()
        finally: verifier.close()
        result = self.public.get(f"/v2/packages/{self.snapshot}", headers=self.headers)
        self.assertEqual("VERIFIED_GB10", result.json()["status"], result.text)
        self.assertFalse(result.json()["archived"])


if __name__ == "__main__": unittest.main()
