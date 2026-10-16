import hashlib
import hmac
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import urllib.parse

from checkpoint1_auth_probe import ProbeFailure
from checkpoint1_probe import NAMES, prepare
import checkpoint1_send_test as sender


class SyntheticSendTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixtures = self.root / "fixtures"
        prepare(self.fixtures)
        self.job = self.root / "job"
        self.remote = {"id": 123, "roundtriptoken": "fake-roundtrip-secret", "status": "available",
                       "expires": {"raw": round(time.time()) + 5 * 86400},
                       "files": [{"id": index + 1, "name": name, "size": sender.EXPECTED[name]["size_bytes"],
                                  "puid": "fake-file-secret"} for index, name in enumerate(NAMES)]}
        self.client = Mock(username="synthetic@example.invalid")
        self.client.call.side_effect = self.respond

    def respond(self, method, path, *args, **kwargs):
        if path == "/info":
            return {"upload_chunk_size": 12582912}
        if path in ("/transfer", "/transfer/123"):
            return self.remote
        return True

    def run_send(self):
        return sender.send(self.client, self.fixtures, self.job, sender.RECIPIENT, 5)

    def test_exact_chunks_finalization_and_safe_receipt(self):
        result = self.run_send()
        chunks = [call for call in self.client.call.call_args_list if "/chunk/" in call.args[1]]
        self.assertEqual([71, 12582912, 17], [len(call.kwargs["raw"]) for call in chunks])
        self.assertEqual("FILESENDER_COMPLETE", result["status"])
        self.assertFalse(result["researcher_verified"])
        self.assertFalse(result["archived"])
        self.assertNotIn("fake-", (self.job / "receipt.json").read_text())

    def test_lost_create_response_not_retried(self):
        def response(method, path, *args, **kwargs):
            if method == "post":
                raise ProbeFailure("NETWORK_OR_TLS_FAILURE")
            return self.respond(method, path, *args, **kwargs)
        self.client.call.side_effect = response
        with self.assertRaises(ProbeFailure):
            self.run_send()
        self.assertEqual(1, sum(call.args[0] == "post" for call in self.client.call.call_args_list))
        self.assertEqual("NEEDS_RECONCILIATION", json.loads((self.job / "receipt.json").read_text())["status"])

    def test_upload_failure_preserves_id_and_sources(self):
        def response(method, path, *args, **kwargs):
            if "/chunk/" in path:
                raise ProbeFailure("NETWORK_OR_TLS_FAILURE")
            return self.respond(method, path, *args, **kwargs)
        self.client.call.side_effect = response
        with self.assertRaises(ProbeFailure):
            self.run_send()
        result = json.loads((self.job / "receipt.json").read_text())
        self.assertEqual(123, result["filesender_transfer_id"])
        self.assertTrue(all((self.fixtures / name).is_file() for name in NAMES))
        self.assertFalse(any(call.args[0] == "delete" for call in self.client.call.call_args_list))

    def test_existing_job_prevents_second_create(self):
        self.run_send()
        self.client.reset_mock()
        with self.assertRaisesRegex(ProbeFailure, "EXISTING_JOB_REQUIRES_RECONCILIATION"):
            self.run_send()
        self.client.call.assert_not_called()

    def test_stalled_remote_not_reported_complete(self):
        self.remote["status"] = "uploading"
        with self.assertRaisesRegex(ProbeFailure, "FINALIZATION_NOT_VERIFIED"):
            self.run_send()
        self.assertEqual("NEEDS_RECONCILIATION", json.loads((self.job / "receipt.json").read_text())["status"])

    def test_actual_aarnet_missing_status_uses_completion_acknowledgment(self):
        del self.remote["status"]
        result = self.run_send()
        self.assertEqual("FILESENDER_COMPLETE", result["status"])
        self.assertIn("completion_acknowledged_at_epoch_ms", result)

    def test_wrong_completion_acknowledgment_rejected(self):
        def response(method, path, *args, **kwargs):
            if method == "put" and path == "/transfer/123":
                return {"id": 999}
            return self.respond(method, path, *args, **kwargs)
        self.client.call.side_effect = response
        with self.assertRaisesRegex(ProbeFailure, "FINALIZATION_NOT_ACKNOWLEDGED"):
            self.run_send()

    def test_changed_source_rejected_before_network(self):
        (self.fixtures / NAMES[0]).write_bytes(b"not the approved synthetic fixture")
        with self.assertRaisesRegex(ProbeFailure, "SYNTHETIC_SOURCE_MISMATCH"):
            self.run_send()
        self.client.call.assert_not_called()

    def test_unapproved_recipient_rejected(self):
        with self.assertRaisesRegex(ProbeFailure, "UNAPPROVED_TEST_DESTINATION_OR_EXPIRY"):
            sender.send(self.client, self.fixtures, self.job, "other@example.invalid", 5)
        self.client.call.assert_not_called()

    def test_encrypted_profile_sends_one_fixed_artifact(self):
        name = "synthetic-files.tar.age"
        data = b"mock ciphertext for isolated protocol test"
        (self.fixtures / name).write_bytes(data)
        expected = {name: {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}}
        self.remote["files"] = [{"id": 1, "name": name, "size": len(data), "puid": "fake-file-secret"}]
        with patch.object(sender, "ENCRYPTED_EXPECTED", expected):
            result = sender.send(self.client, self.fixtures, self.job, sender.RECIPIENT, 5, encrypted=True)
        self.assertTrue(result["transport_encrypted"])
        self.assertEqual({name}, set(result["files"]))
        creation = next(call for call in self.client.call.call_args_list if call.args[0] == "post")
        self.assertEqual([{"name": name, "size": len(data)}], creation.kwargs["payload"]["files"])
        chunks = [call for call in self.client.call.call_args_list if "/chunk/" in call.args[1]]
        self.assertEqual([data], [call.kwargs["raw"] for call in chunks])

    def test_unreviewed_ciphertext_rejected_before_network(self):
        (self.fixtures / "synthetic-files.tar.age").write_bytes(b"not the reviewed artifact")
        with self.assertRaisesRegex(ProbeFailure, "SYNTHETIC_SOURCE_MISMATCH"):
            sender.send(self.client, self.fixtures, self.job, sender.RECIPIENT, 5, encrypted=True)
        self.client.call.assert_not_called()

    def test_signing_includes_exact_body_and_binary(self):
        for payload, raw, body in [(None, b"\x00\xff", b"\x00\xff"), ({"complete": True}, None, b'{"complete":true}')]:
            with patch.object(sender.time, "time", return_value=1700000000):
                outgoing = sender.request("fake-user", "fake-key", "put", "/file/1", payload=payload, raw=raw)
            signed = b"put&filesender.aarnet.edu.au/rest.php/file/1?remote_user=fake-user&timestamp=1700000000&" + body
            expected = hmac.new(b"fake-key", signed, hashlib.sha1).hexdigest()
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(outgoing.full_url).query)
            self.assertEqual([expected], query["signature"])
            self.assertEqual(body, outgoing.data)


if __name__ == "__main__":
    unittest.main()
