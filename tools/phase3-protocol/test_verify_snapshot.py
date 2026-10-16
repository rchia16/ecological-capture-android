"""Independent verifier rejection cases using only generated Kotlin synthetic fixtures."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from verify_snapshot import digest, verify


class SnapshotVerifierTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2] / "app/build/c2"
        records = list(root.glob("*/*/snapshot-record.json"))
        self.assertTrue(records, "Run module-qualified JVM tests to prepare synthetic cross-language fixtures")
        selected = max(records, key=lambda p: p.stat().st_mtime_ns)
        self.record = json.loads(selected.read_text(encoding="utf-8"))
        bundle = next(selected.parent.glob("event_*"))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / "bundle"
        shutil.copytree(bundle, self.bundle)
        self.expected = {e["path"]: e["sha256"] for e in self.record["files"]}

    def check(self):
        return verify(self.bundle, self.expected["manifest.json"], self.expected["event.json"], self.expected["video.mp4"])

    def edit_event_and_update_trusted_hashes(self, transform):
        event_path = self.bundle / "event.json"
        event = json.loads(event_path.read_text(encoding="utf-8"))
        transform(event)
        event_path.write_text(json.dumps(event), encoding="utf-8")
        self.expected["event.json"] = digest(event_path)["sha256"]
        manifest_path = self.bundle / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][1].update(digest(event_path))
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        self.expected["manifest.json"] = digest(manifest_path)["sha256"]

    def test_correct_kotlin_bytes(self):
        self.assertEqual("SNAPSHOT_SHA256_SCHEMA_VERIFIED", self.check()["status"])

    def test_same_size_video_corruption(self):
        video = self.bundle / "video.mp4"
        data = bytearray(video.read_bytes())
        data[-1] ^= 1
        video.write_bytes(data)
        with self.assertRaises(ValueError): self.check()

    def test_truncation(self):
        video = self.bundle / "video.mp4"
        video.write_bytes(video.read_bytes()[:-1])
        with self.assertRaises(ValueError): self.check()

    def test_missing_and_extra_files(self):
        (self.bundle / "extra.txt").write_text("synthetic")
        with self.assertRaises(ValueError): self.check()
        (self.bundle / "extra.txt").unlink()
        (self.bundle / "event.json").unlink()
        with self.assertRaises(ValueError): self.check()

    def test_directory_substitution(self):
        path = self.bundle / "video.mp4"
        path.unlink()
        path.mkdir()
        with self.assertRaises(ValueError): self.check()

    def test_linkage_mismatch_even_with_valid_hashes(self):
        self.edit_event_and_update_trusted_hashes(lambda e: e.update(clip_id="ffffffff-ffff-ffff-ffff-ffffffffffff"))
        with self.assertRaises(ValueError): self.check()

    def test_unknown_profile_field_even_with_valid_hashes(self):
        self.edit_event_and_update_trusted_hashes(lambda e: e.update(diagnosis="forbidden-profile-fixture"))
        with self.assertRaises(ValueError): self.check()

    def test_absent_exposure_is_null_not_zero(self):
        event = json.loads((self.bundle / "event.json").read_text(encoding="utf-8"))
        for run in event["vlm_runs"]:
            self.assertIsNone(run["first_presented_at_epoch_ms"])
        self.assertIsNone(event["review"]["approved_at_epoch_ms"])

    def test_broken_current_pointer_even_with_valid_hashes(self):
        self.edit_event_and_update_trusted_hashes(lambda e: e["participant_annotations"].update(current_annotation_id="ffffffff-ffff-ffff-ffff-ffffffffffff"))
        with self.assertRaises(ValueError): self.check()

    def test_original_strings_roundtrip(self):
        event = json.loads((self.bundle / "event.json").read_text(encoding="utf-8"))
        if event["vlm_runs"]:
            self.assertEqual('Exact uncertain output\n"café" 😀', event["vlm_runs"][0]["raw_output"])
            self.assertEqual('{"frame_times":[1,2,3]}', event["vlm_runs"][0]["frame_sampling_json"])
        self.assertEqual("SNAPSHOT_SHA256_SCHEMA_VERIFIED", self.check()["status"])


if __name__ == "__main__":
    unittest.main()
