import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
import uuid
import os
import subprocess
from unittest.mock import patch

from ecocapture_gateway.synthetic_fixture import files
from ecocapture_firebase.receiver import Receiver, InvalidPackage


class FakeCloud:
    def __init__(self, row, archive):
        self.row, self.archive = row, archive
        self.receipts = []; self.fail_receipt = False; self.downloads = 0
    def pending(self, study): return [self.row]
    def download(self, row, path): self.downloads += 1; path.write_bytes(self.archive)
    def receipt(self, body):
        if self.fail_receipt: raise OSError("offline")
        self.receipts.append(body)


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        event, snapshot, owner = (str(uuid.uuid4()) for _ in range(3))
        source = files(event, snapshot)
        science = json.loads(source["event.json"]); science["study_participant_code"] = "P014"
        source["event.json"] = json.dumps(science).encode()
        manifest = json.loads(source["manifest.json"])
        manifest["files"][1].update(size_bytes=len(source["event.json"]), sha256=hashlib.sha256(source["event.json"]).hexdigest())
        source["manifest.json"] = json.dumps(manifest).encode()
        self.source = source
        archive = self.archive(source)
        self.row = {"study_id":"study", "participant_code":"P014", "snapshot_id":snapshot, "event_id":event,
                    "owner_uid":owner, "recorded_at_epoch_ms":1000, "status":"AWAITING_GB10", "generation":"7",
                    "object_path":f"uploads/{owner}/{snapshot}/approved-event.tar.age",
                    "size_bytes":len(archive), "ciphertext_sha256":hashlib.sha256(archive).hexdigest(),
                    "manifest_sha256":hashlib.sha256(source["manifest.json"]).hexdigest()}
        self.cloud = FakeCloud(self.row,archive)
        self.root = Path(self.temp.name) / "received"
        self.receiver = self.open()
        self.addCleanup(lambda: self.receiver.close())

    def open(self):
        # Unit tests isolate decryption; real age interoperability is a separate integration check.
        return Receiver(self.root,"study","unused","unused",self.cloud,decrypt=shutil.copyfile)

    def archive(self, source):
        output=io.BytesIO()
        with tarfile.open(fileobj=output,mode="w",format=tarfile.USTAR_FORMAT) as archive:
            for name,data in source.items():
                member=tarfile.TarInfo(name);member.size=len(data);archive.addfile(member,io.BytesIO(data))
        return output.getvalue()

    def test_receives_verifies_and_skips_duplicate_download(self):
        self.receiver.once();self.receiver.once()
        self.assertEqual(1,self.cloud.downloads)
        receipt=self.cloud.receipts[-1]
        self.assertEqual("VERIFIED_GB10",receipt["status"])
        destination=self.root/receipt["gb10_path"]
        self.assertEqual(set(self.source),{p.name for p in destination.iterdir()})
        self.assertEqual(self.source["video.mp4"],(destination/"video.mp4").read_bytes())

    def test_acknowledgement_survives_restart(self):
        self.cloud.fail_receipt=True
        with self.assertRaises(OSError):self.receiver.once()
        self.receiver.close();self.receiver=self.open();self.cloud.fail_receipt=False
        self.receiver.once()
        self.assertEqual(1,self.cloud.downloads)
        self.assertEqual("VERIFIED_GB10",self.cloud.receipts[-1]["status"])

    def test_recovers_publication_before_ledger_commit(self):
        self.receiver.once();self.receiver.db.execute("DELETE FROM receipts");self.receiver.db.commit()
        self.receiver.once();self.assertEqual(1,self.cloud.downloads)

    def test_corruption_is_not_published(self):
        self.cloud.archive+=b"changed"
        self.receiver.once()
        self.assertEqual("NEEDS_ATTENTION",self.cloud.receipts[-1]["status"])
        self.assertEqual([],list((self.root/".staging").iterdir()))

    def test_archive_traversal_and_extra_members_rejected(self):
        for name in ("../escape", "extra.txt"):
            bad=self.archive({**self.source,name:b"bad"})
            self.cloud.archive=bad;self.row.update(size_bytes=len(bad),ciphertext_sha256=hashlib.sha256(bad).hexdigest())
            self.receiver.once()
            self.assertEqual("NEEDS_ATTENTION",self.cloud.receipts[-1]["status"])

    def test_wrong_participant_rejected(self):
        self.row["participant_code"]="P015"
        self.receiver.once();self.assertEqual("NEEDS_ATTENTION",self.cloud.receipts[-1]["status"])

    def test_low_disk_keeps_package_pending(self):
        with patch("ecocapture_firebase.receiver.shutil.disk_usage",return_value=shutil._ntuple_diskusage(1000,900,100)):
            with self.assertRaises(OSError):self.receiver.once()
        self.assertEqual([],self.cloud.receipts);self.assertEqual(0,self.cloud.downloads)

    @unittest.skipUnless(os.environ.get("ECO_CRYPTO_INTEROP"), "Build the Go interop harness to enable real age round trips")
    def test_actual_go_encryption_decrypts_on_receiver_with_each_key(self):
        source=Path(self.temp.name)/"source";source.mkdir()
        for name,data in self.source.items():(source/name).write_bytes(data)
        encrypted=Path(self.temp.name)/"encrypted"
        subprocess.run([os.environ["ECO_CRYPTO_INTEROP"],str(source),str(encrypted)],check=True,capture_output=True,timeout=30)
        age=Path(__file__).resolve().parents[2]/"tools/phase3-protocol/artifacts/age.exe"
        if not age.is_file():age=Path(shutil.which("age") or "/not-installed/age")
        self.assertTrue(age.is_file(),"Install age v1.3.2 to run interoperability checks")
        self.cloud.archive=(encrypted/"approved-event.tar.age").read_bytes()
        self.row.update(size_bytes=len(self.cloud.archive),ciphertext_sha256=hashlib.sha256(self.cloud.archive).hexdigest())
        for key in ("gb10.agekey","recovery.agekey"):
            receiver=Receiver(Path(self.temp.name)/key.replace('.agekey',''),"study",age,encrypted/key,self.cloud)
            try:
                receiver.once()
                self.assertEqual("VERIFIED_GB10",self.cloud.receipts[-1]["status"])
                self.assertEqual(self.source["video.mp4"],(receiver.root/self.cloud.receipts[-1]["gb10_path"]/"video.mp4").read_bytes())
            finally:receiver.close()


if __name__ == "__main__":unittest.main()
