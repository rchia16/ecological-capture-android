"""Mock-only tests of signing, secret isolation and safe network failures."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.parse

import checkpoint1_auth_probe as auth


class AuthProbeTest(unittest.TestCase):
    def response(self, request, payload):
        value = Mock()
        value.status = 200
        value.geturl.return_value = request.full_url
        value.read.return_value = json.dumps(payload).encode()
        value.__enter__ = Mock(return_value=value)
        value.__exit__ = Mock(return_value=False)
        return value

    def test_reference_signature_compatibility(self):
        # Independent literal reference input includes reserved query characters.
        import hashlib
        import hmac
        username, key = "synthetic+user@example.invalid", "fake-key"
        request = auth.signed_request(username, key, 1700000000)
        expected = hmac.new(key.encode(), b"get&filesender.aarnet.edu.au/rest.php/transfer/@me?remote_user=synthetic+user@example.invalid&timestamp=1700000000", hashlib.sha1).hexdigest()
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query)
        self.assertEqual([expected], query["signature"])
        self.assertEqual([username], query["remote_user"])

    def test_success_emits_no_account_or_transfer_contents(self):
        def response(request, **kwargs):
            self.assertEqual(30, kwargs["timeout"])
            self.assertEqual("GET", request.get_method())
            payload = {"upload_chunk_size": 12582912} if request.full_url.endswith("/info") else [{"token": "fake-sensitive-token", "email": "synthetic@example.invalid"}]
            return self.response(request, payload)
        opener = Mock()
        opener.open.side_effect = response
        with patch.object(auth, "load_credentials", return_value=("fake-user", "fake-key")):
            result = auth.probe(Path("unused"), opener)
        self.assertEqual("SIGNED_READ_SUCCEEDED", result["status"])
        self.assertEqual(2, opener.open.call_count)
        self.assertNotIn("fake-", json.dumps(result))

    def test_http_error_is_redacted_and_not_retried(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.invalid/?signature=fake-secret", 403, "fake-secret", {}, None)
        with self.assertRaisesRegex(auth.ProbeFailure, "^AUTHENTICATION_REJECTED$"):
            auth.fetch_json(opener, auth.signed_request("fake-user", "fake-key", 1))
        self.assertEqual(1, opener.open.call_count)

    def test_network_error_is_redacted(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.URLError("fake-secret-signature")
        with self.assertRaisesRegex(auth.ProbeFailure, "^NETWORK_OR_TLS_FAILURE$"):
            auth.fetch_json(opener, auth.signed_request("fake-user", "fake-key", 1))

    def test_redirect_rejected(self):
        with self.assertRaisesRegex(auth.ProbeFailure, "^REDIRECT_REJECTED$"):
            auth.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://example.invalid")

    def test_oversized_response_rejected(self):
        opener = Mock()
        request = auth.signed_request("fake-user", "fake-key", 1)
        response = self.response(request, {})
        response.read.return_value = b"X" * (auth.MAX_RESPONSE_BYTES + 1)
        opener.open.return_value = response
        with self.assertRaisesRegex(auth.ProbeFailure, "^RESPONSE_TOO_LARGE$"):
            auth.fetch_json(opener, request)

    def test_repository_credential_rejected_before_read(self):
        path = Path(auth.__file__).resolve().parent / "not-read.ini"
        with self.assertRaisesRegex(auth.ProbeFailure, "^CREDENTIAL_MUST_BE_OUTSIDE_REPOSITORY$"):
            auth.load_credentials(path)

    def test_external_config_literal_percent_and_fixed_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.ini"
            path.write_text("[system]\nbase_url = " + auth.API + "\n[user]\nusername = fake-user\napikey = fake%key\n")
            self.assertEqual(("fake-user", "fake%key"), auth.load_credentials(path))
            path.write_text(path.read_text().replace(auth.API, "https://example.invalid/rest.php"))
            with self.assertRaisesRegex(auth.ProbeFailure, "^UNAPPROVED_API_HOST$"):
                auth.load_credentials(path)

    def test_cli_unexpected_failure_has_no_trace_or_secret(self):
        output = io.StringIO()
        with patch("sys.argv", ["probe", "--config", "unused"]), patch.object(auth, "probe", side_effect=RuntimeError("fake-secret")), contextlib.redirect_stdout(output):
            self.assertEqual(1, auth.main())
        self.assertNotIn("fake-secret", output.getvalue())
        self.assertEqual("UNEXPECTED_PROBE_FAILURE", json.loads(output.getvalue())["safe_error_code"])


if __name__ == "__main__":
    unittest.main()
