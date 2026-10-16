import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from starlette.testclient import TestClient
from ecocapture_gateway.app import create_app
from ecocapture_gateway.config import Config
from ecocapture_gateway.store import Store


spec = importlib.util.spec_from_file_location("hosted_probe", Path(__file__).resolve().parents[1] / "deployment/gb10/hosted_probe.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class HostedProbeTest(unittest.TestCase):
    def test_pause_real_store_restart_resume_revocation_and_purge(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Config(Path(tmp) / "gateway", allowed_hosts=(probe.BASE,))
            store = Store(config)
            import uuid
            value = {"mode": "SYNTHETIC_ONLY", "hostname": probe.BASE}
            for name in ("a", "b"):
                installation = str(uuid.uuid4())
                value[name] = {"installation_id": installation,
                               "token": store.issue(installation, probe.POLICY, eligible=True)}
            client = TestClient(create_app(config, store), base_url="https://" + probe.BASE)
            def request(method, path, token=None, body=None, headers=None):
                h = dict(headers or {})
                if token: h["Authorization"] = "Bearer " + token
                if method == "PUT" and body is not None and len(body) > config.max_chunk_bytes:
                    self.assertEqual(config.max_chunk_bytes + 1, len(body))
                    self.assertNotIn("Content-Length", h)  # Library supplies the true length.
                result = client.request(method, path, content=body, headers=h)
                return result.status_code, result.json()
            progress = Path(tmp) / "progress.json"
            try:
                self.assertEqual("HOSTED_UPLOAD_PAUSED", probe.initial(request, value, progress)["status"])
                with self.assertRaises(probe.ProbeFailure): probe.initial(request, value, progress)
                client.close()
                store = Store(config)
                client = TestClient(create_app(config, store), base_url="https://" + probe.BASE)
                self.assertEqual("HOSTED_RESUME_AND_MOCK_RECEIPT_VERIFIED", probe.resume(request, value, progress)["status"])
                saved = json.loads(progress.read_text())
                self.assertFalse(store.directory(saved["a_transfer"]).exists())
                with self.assertRaises(probe.ProbeFailure): probe.revoked(request, value, progress)
                store.revoke(value["a"]["installation_id"])
                self.assertEqual("HOSTED_INDEPENDENT_REVOCATION_VERIFIED", probe.revoked(request, value, progress)["status"])
                with store.connect() as db:
                    self.assertEqual(1, db.execute("SELECT COUNT(*) FROM mock_intents").fetchone()[0])
                    self.assertEqual(0, db.execute("SELECT SUM(reserved) FROM transfers").fetchone()[0])
            finally:
                client.close()

    def test_credentials_profile_and_exclusive_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "credentials.json"
            probe.protected_write(path, {"mode": "REAL", "hostname": probe.BASE})
            with self.assertRaises(probe.ProbeFailure): probe.credentials(path)
            with self.assertRaises(FileExistsError): probe.protected_write(path, {})

    def test_diagnostics_classify_without_exposing_private_values(self):
        import socket
        import http.client
        for error, expected in ((FileNotFoundError("private credential path"), "LOCAL_FILE_MISSING"),
                                (PermissionError("private path"), "LOCAL_FILE_PERMISSION_DENIED"),
                                (socket.gaierror("private hostname"), "DNS_LOOKUP_FAILED"),
                                (TimeoutError("private request"), "NETWORK_TIMEOUT"),
                                (http.client.RemoteDisconnected("private response"), "SERVER_DISCONNECTED"),
                                (KeyError("secret token"), "PROFILE_OR_RESPONSE_INVALID")):
            self.assertEqual(expected, probe.failure_code(error))
