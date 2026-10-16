import io
import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
from email.utils import formatdate

from ecocapture_gateway.errors import GatewayError
from ecocapture_gateway.filesender_adapter import API, FileSenderClient


class FileSenderClockTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.ini = Path(self.temporary.name) / "dummy.ini"
        self.ini.write_text("[system]\nbase_url=" + API + "\n[user]\nusername=fake@example.invalid\napikey=dummy-secret\n")
        self.ini.chmod(0o600)
        self.epoch = 1704067200

    def response(self, url, date=True, age=None):
        response = io.BytesIO(b"[]")
        response.status = 200
        response.geturl = lambda: url
        response.headers = {}
        if date:
            response.headers["Date"] = formatdate(self.epoch, usegmt=True)
        if age is not None:
            response.headers["Age"] = age
        return response

    def client(self, enabled=True):
        return FileSenderClient(self.ini, live_authorized=True, use_server_time=enabled)

    def test_default_uses_host_time_without_extra_request(self):
        client = self.client(False)
        with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - 83), \
                patch.object(client.opener, "open", side_effect=lambda request, **kw: self.response(request.full_url)) as opened:
            client.call("get", "/transfer/@me")
        self.assertEqual(1, opened.call_count)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(opened.call_args.args[0].full_url).query)
        self.assertEqual([str(self.epoch - 83)], query["timestamp"])

    def test_auth_check_never_opens_the_gateway_store_or_mutates_transfers(self):
        from ecocapture_gateway import forward_cli
        output = io.StringIO()
        arguments = ["forward_cli", "--config", "unused.json", "auth-check",
                     "--filesender-config", str(self.ini), "--authorize-synthetic-filesender",
                     "--credential-policy", "accepted-engineering-exception", "--use-filesender-time"]
        with patch("sys.argv", arguments), patch.object(forward_cli, "load_store") as load, \
                patch.object(forward_cli, "FileSenderClient") as factory, contextlib.redirect_stdout(output):
            factory.return_value.call.return_value = []
            forward_cli.main()
        load.assert_not_called()
        self.assertTrue(factory.call_args.kwargs["use_server_time"])
        factory.return_value.call.assert_called_once_with("get", "/transfer/@me")
        result = json.loads(output.getvalue())
        self.assertEqual("SIGNED_READ_SUCCEEDED", result["status"])
        self.assertEqual(0, result["transfer_mutations"])
        self.assertFalse(result["host_clock_changed"])

    def test_corrected_clock_is_local_monotonic_and_authenticated(self):
        client = self.client()
        observed = []
        def respond(request, **kw):
            observed.append(request)
            return self.response(request.full_url)
        with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - 83) as wall, \
                patch("ecocapture_gateway.filesender_adapter.time.monotonic", return_value=100) as mono, \
                patch.object(client.opener, "open", side_effect=respond):
            client.call("get", "/transfer/@me")
            mono.return_value = 110
            wall.return_value = self.epoch + 10000  # no process-wide clock override
            client.call("get", "/transfer/21")
            self.assertEqual(self.epoch + 10000, __import__("time").time())
        self.assertEqual(3, len(observed))
        self.assertEqual(API + "/info", observed[0].full_url)
        self.assertEqual("no-cache, no-store", observed[0].get_header("Cache-control"))
        for request, expected in zip(observed[1:], (self.epoch, self.epoch + 10)):
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query)
            self.assertEqual([str(expected)], query["timestamp"])
            self.assertIn("signature", query)

    def test_stale_sample_refreshes_before_next_signed_request(self):
        client = self.client()
        with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - 83), \
                patch("ecocapture_gateway.filesender_adapter.time.monotonic", return_value=0) as mono, \
                patch.object(client.opener, "open", side_effect=lambda request, **kw: self.response(request.full_url)) as opened:
            client.call("get", "/transfer/@me")
            mono.return_value = 61
            self.epoch += 61
            client.call("get", "/transfer/@me")
        self.assertEqual([API + "/info", API + "/info"],
                         [call.args[0].full_url for call in (opened.call_args_list[0], opened.call_args_list[2])])

    def test_stale_date_on_refresh_is_rejected_without_signing(self):
        client = self.client()
        with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - 83), \
                patch("ecocapture_gateway.filesender_adapter.time.monotonic", return_value=0) as mono, \
                patch.object(client.opener, "open", side_effect=lambda request, **kw: self.response(request.full_url)) as opened:
            client.call("get", "/transfer/@me")
            mono.return_value = 61
            with self.assertRaises(GatewayError) as error:
                client.call("get", "/transfer/@me")
        self.assertEqual("FILESENDER_CLOCK_SAMPLE_UNSTABLE", error.exception.code)
        self.assertEqual(3, opened.call_count)

    def test_bad_samples_never_fall_back_or_reach_transfer_endpoint(self):
        for case, code in (("missing", "FILESENDER_CLOCK_DATE_INVALID"),
                           ("cached", "FILESENDER_CLOCK_CACHED_RESPONSE"),
                           ("offset", "FILESENDER_CLOCK_OFFSET_TOO_LARGE"),
                           ("slow", "FILESENDER_CLOCK_SAMPLE_TOO_SLOW"),
                           ("redirect", "FILESENDER_REDIRECT_REJECTED")):
            with self.subTest(case=case):
                client = self.client()
                response = self.response(API + "/info", date=case != "missing", age="60" if case == "cached" else None)
                if case == "redirect": response.geturl = lambda: "https://other.invalid/"
                with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - (1000 if case == "offset" else 83)), \
                        patch("ecocapture_gateway.filesender_adapter.time.monotonic",
                              side_effect=[0, 0, 6] if case == "slow" else None, return_value=0), \
                        patch.object(client.opener, "open", return_value=response) as opened:
                    with self.assertRaises(GatewayError) as error:
                        client.call("post", "/transfer", payload={"synthetic": True})
                self.assertEqual(code, error.exception.code)
                self.assertEqual(1, opened.call_count)
                self.assertTrue(response.closed)
                self.assertIsNone(client._clock_sample)

    def test_clock_http_error_closes_response_and_redacts(self):
        client = self.client()
        body = io.BytesIO(b"PRIVATE")
        failure = urllib.error.HTTPError("https://invalid/?secret=PRIVATE", 500, "PRIVATE", {}, body)
        with patch.object(client.opener, "open", side_effect=failure):
            with self.assertRaises(GatewayError) as error:
                client.now()
        self.assertEqual("FILESENDER_CLOCK_SYNC_FAILED", error.exception.code)
        self.assertNotIn("PRIVATE", str(error.exception))
        self.assertTrue(body.closed)

    def test_signed_failure_does_not_retry_with_another_timestamp(self):
        client = self.client()
        failure = urllib.error.HTTPError("https://invalid/?signature=PRIVATE", 500, "PRIVATE", {}, io.BytesIO())
        with patch("ecocapture_gateway.filesender_adapter.time.time", return_value=self.epoch - 83), \
                patch("ecocapture_gateway.filesender_adapter.time.monotonic", return_value=0), \
                patch.object(client.opener, "open", side_effect=[self.response(API + "/info"), failure]) as opened:
            with self.assertRaises(GatewayError) as error:
                client.call("post", "/transfer", payload={"synthetic": True})
        self.assertEqual("FILESENDER_REMOTE_UNAVAILABLE", error.exception.code)
        self.assertEqual(2, opened.call_count)
