import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from pull_snapshot import extract_fixed


class FixedArchiveExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.event_id = "00000000-0000-0000-0000-000000000001"
        self.snapshot_id = "00000000-0000-0000-0000-000000000002"
        self.prefix = f"files/engineering-exports/{self.snapshot_id}"
        self.bundle = f"{self.prefix}/event_{self.event_id}_snapshot_{self.snapshot_id}"
        self.archive = self.root / "fixture.tar"

    def prepare(self, extra=None):
        with tarfile.open(self.archive, "w") as tar:
            for name in [f"{self.bundle}/video.mp4", f"{self.bundle}/event.json",
                         f"{self.bundle}/manifest.json", f"{self.prefix}/snapshot-record.json"]:
                member = tarfile.TarInfo(name)
                member.size = 7
                tar.addfile(member, io.BytesIO(b"fixture"))
            if extra:
                tar.addfile(extra, io.BytesIO(b""))

    def extract(self):
        extract_fixed(self.archive, self.root, self.event_id, self.snapshot_id)

    def test_exact_regular_files_only(self):
        self.prepare()
        self.extract()
        self.assertEqual({"video.mp4", "event.json", "manifest.json"}, {p.name for p in (self.root / "bundle").iterdir()})
        self.assertEqual(b"fixture", (self.root / "snapshot-record.json").read_bytes())

    def test_traversal_rejected_before_extraction(self):
        self.prepare(tarfile.TarInfo("../outside"))
        with self.assertRaises(ValueError): self.extract()
        self.assertFalse((self.root / "bundle").exists())

    def test_duplicate_expected_file_rejected(self):
        self.prepare(tarfile.TarInfo(f"{self.bundle}/event.json"))
        with self.assertRaises(ValueError): self.extract()
        self.assertFalse((self.root / "bundle").exists())

    def test_symlink_rejected_before_extraction(self):
        link = tarfile.TarInfo(f"{self.prefix}/link")
        link.type = tarfile.SYMTYPE
        link.linkname = "/private"
        self.prepare(link)
        with self.assertRaises(ValueError): self.extract()
        self.assertFalse((self.root / "bundle").exists())


if __name__ == "__main__":
    unittest.main()
