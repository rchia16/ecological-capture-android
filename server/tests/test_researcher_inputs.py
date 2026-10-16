import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ecocapture_gateway.errors import GatewayError
from ecocapture_gateway.verify_researcher import verify_received


class ResearcherInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inputs = [self.root / name for name in ("cipher.age", "expected.json", "age.exe", "identity.txt", "out")]
        for path in self.inputs[:4]: path.write_bytes(b"dummy")
        self.expected = {"participant_data": False,
                         "ciphertext_size_bytes": 5, "ciphertext_sha256": hashlib.sha256(b"dummy").hexdigest(),
                         "archive_size_bytes": 1, "archive_sha256": "0" * 64,
                         "files": {name: {"size_bytes": 1, "sha256": "0" * 64}
                                   for name in ("event.json", "manifest.json", "video.mp4")}}
        self.inputs[1].write_text(json.dumps(self.expected))

    def test_missing_inputs_are_distinguished_before_age_runs(self):
        for index, code in enumerate(("DOWNLOADED_CIPHERTEXT_UNAVAILABLE", "EXPECTED_FILE_UNAVAILABLE",
                                      "AGE_EXECUTABLE_UNAVAILABLE", "RESEARCHER_IDENTITY_UNAVAILABLE")):
            with self.subTest(code=code), patch("ecocapture_gateway.verify_researcher.subprocess.run") as run:
                inputs = self.inputs.copy(); inputs[index] = self.root / "missing"
                with self.assertRaises(GatewayError) as error: verify_received(*inputs)
                self.assertEqual(code, error.exception.code)
                run.assert_not_called()
                self.assertFalse(self.inputs[4].exists())

    def test_malformed_or_partial_expectations_fail_before_decrypt(self):
        for data, code in (("PRIVATE not json", "EXPECTED_FILE_INVALID_JSON"),
                           ("[]", "TRUSTED_SYNTHETIC_EXPECTATIONS_REQUIRED"),
                           (json.dumps({"participant_data": False}), "TRUSTED_SYNTHETIC_EXPECTATIONS_REQUIRED"),
                           (json.dumps({**self.expected, "archive_sha256": None}), "EXPECTED_FILE_SCHEMA_INVALID")):
            with self.subTest(code=code), patch("ecocapture_gateway.verify_researcher.subprocess.run") as run:
                self.inputs[1].write_text(data)
                with self.assertRaises(GatewayError) as error: verify_received(*self.inputs)
                self.assertEqual(code, error.exception.code)
                self.assertNotIn("PRIVATE", str(error.exception))
                run.assert_not_called()

    def test_launch_failure_reports_safe_code_without_reading_identity(self):
        with patch("ecocapture_gateway.verify_researcher.subprocess.run", side_effect=OSError("PRIVATE")), \
                patch.object(Path, "read_text", autospec=True, return_value=json.dumps(self.expected)) as read:
            with self.assertRaises(GatewayError) as error: verify_received(*self.inputs)
        self.assertEqual("AGE_EXECUTION_FAILED", error.exception.code)
        self.assertNotIn("PRIVATE", str(error.exception))
        read.assert_called_once_with(self.inputs[1], encoding="utf-8-sig")
