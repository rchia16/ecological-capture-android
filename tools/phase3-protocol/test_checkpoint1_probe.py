"""Corrupt/missing/extra download rejection for the independent fixture verifier."""
import json
from pathlib import Path
import tempfile
import unittest

from checkpoint1_probe import NAMES, digest, verify


class DownloadVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.downloads = root / "downloads"
        self.downloads.mkdir()
        for name in NAMES:
            (self.downloads / name).write_bytes(b"synthetic test bytes\x00\xff\n")
        self.expected = root / "expected.json"
        self.expected.write_text(json.dumps({"schema": "ecocapture.checkpoint1.synthetic.v1",
            "files": {name: digest(self.downloads / name) for name in NAMES}}), encoding="utf-8")

    def test_correct_download_passes(self):
        self.assertEqual("SHA256_VERIFIED", verify(self.expected, self.downloads)["status"])

    def test_changed_byte_fails_even_with_same_length(self):
        path = self.downloads / NAMES[0]
        data = path.read_bytes()
        path.write_bytes(b"X" + data[1:])
        with self.assertRaisesRegex(ValueError, "hash_mismatch"):
            verify(self.expected, self.downloads)

    def test_truncated_file_fails(self):
        (self.downloads / NAMES[0]).write_bytes(b"synthetic")
        with self.assertRaisesRegex(ValueError, "hash_mismatch"):
            verify(self.expected, self.downloads)

    def test_missing_file_fails(self):
        (self.downloads / NAMES[0]).unlink()
        with self.assertRaisesRegex(ValueError, "unexpected_download_files"):
            verify(self.expected, self.downloads)

    def test_extra_file_fails(self):
        (self.downloads / "unexpected.txt").write_bytes(b"synthetic")
        with self.assertRaisesRegex(ValueError, "unexpected_download_files"):
            verify(self.expected, self.downloads)

    def test_directory_in_place_of_file_fails(self):
        path = self.downloads / NAMES[0]
        path.unlink()
        path.mkdir()
        with self.assertRaisesRegex(ValueError, "hash_mismatch"):
            verify(self.expected, self.downloads)

    def test_unexpected_manifest_path_fails(self):
        data = json.loads(self.expected.read_text())
        data["files"]["../unexpected"] = data["files"].pop(NAMES[0])
        self.expected.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "invalid_expected_fixture"):
            verify(self.expected, self.downloads)


if __name__ == "__main__":
    unittest.main()
