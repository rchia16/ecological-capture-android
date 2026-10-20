import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "functions"))
from contract import Rejected, registration, same_registration, valid_enrolment, public_row


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.install = {"uid": "11111111-1111-4111-8111-111111111111", "study_id": "study", "participant_code": "P014"}
        self.study = {"key_versions": ["gb10-v1", "recovery-v1"]}
        self.body = {"snapshot_id": "22222222-2222-4222-8222-222222222222", "event_id": "33333333-3333-4333-8333-333333333333",
                     "recorded_at_epoch_ms": 1792368000000, "size_bytes": 100, "ciphertext_sha256": "a" * 64,
                     "manifest_sha256": "b" * 64, "key_versions": self.study["key_versions"]}

    def test_routing_is_server_owned_and_idempotent(self):
        row = registration(self.body, self.install, self.study)
        self.assertEqual("P014", row["participant_code"])
        self.assertIn(self.install["uid"], row["object_path"])
        same_registration({**row, "status": "VERIFIED_GB10"}, row)
        changed = {**row, "ciphertext_sha256": "c" * 64}
        with self.assertRaises(Rejected): same_registration(row, changed)

    def test_rejects_untrusted_metadata_sizes_and_policy(self):
        for key, value in [("participant_code", "P015"), ("size_bytes", True), ("size_bytes", 268435457),
                           ("event_id", "../outside"), ("ciphertext_sha256", "Z" * 64), ("key_versions", ["old", "key"])]:
            with self.subTest(key=key, value=value), self.assertRaises(Rejected):
                registration({**self.body, key: value}, self.install, self.study)

    def test_single_installation_enrolment_and_revocation(self):
        row = {"enabled": True, "expires_at": 2000, "installation_id": None}
        valid_enrolment(row, "a", 1000)
        valid_enrolment({**row, "installation_id": "a"}, "a", 1000)
        for invalid in [{**row, "installation_id": "b"}, {**row, "enabled": False}, {**row, "expires_at": 999}]:
            with self.assertRaises(Rejected): valid_enrolment(invalid, "a", 1000)

    def test_researcher_response_does_not_expose_storage_credentials_or_owner(self):
        row = registration(self.body, self.install, self.study)
        exposed = public_row({**row, "download_url": "secret", "generation": "1"})
        self.assertNotIn("owner_uid", exposed); self.assertNotIn("object_path", exposed)
        self.assertNotIn("download_url", exposed)


if __name__ == "__main__": unittest.main()
