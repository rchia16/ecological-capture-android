"""Actual loopback TLS + upload/replay/restart/revocation test; no public service or CA-store change."""
from dataclasses import asdict, replace
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import time

from ecocapture_gateway.probe import probe
from test_gateway import GatewayTest


def run():
    fixture = GatewayTest()
    fixture.setUp()
    process = None
    try:
        root = fixture.root
        openssl = os.environ.get("ECOCAPTURE_TEST_OPENSSL", "C:/Program Files/Git/usr/bin/openssl.exe" if os.name == "nt" else "openssl")
        def openssl_run(*args):
            subprocess.run([openssl, *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        openssl_run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=EcoCapture disposable test CA",
                    "-keyout", str(root / "ca.key"), "-out", str(root / "ca.pem"),
                    "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        openssl_run("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost", "-keyout", str(root / "server.key"), "-out", str(root / "server.csr"))
        extension = root / "extensions.txt"
        extension.write_text("subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n", encoding="ascii")
        openssl_run("x509", "-req", "-in", str(root / "server.csr"), "-CA", str(root / "ca.pem"), "-CAkey", str(root / "ca.key"),
                    "-CAcreateserial", "-days", "2", "-extfile", str(extension), "-out", str(root / "server.pem"))
        config = replace(fixture.config, allowed_hosts=("localhost", "127.0.0.1"))
        data = asdict(config); data["storage"] = str(config.storage)
        configuration = root / "config.json"; configuration.write_text(json.dumps(data), encoding="utf-8")
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0)); port = reserved.getsockname()[1]
        url = f"https://localhost:{port}/v1/health"
        def start():
            child = subprocess.Popen([sys.executable, "-B", "-m", "ecocapture_gateway.cli", "--config", str(configuration), "serve",
                                      "--port", str(port), "--cert", str(root / "server.pem"), "--key", str(root / "server.key")],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if probe(url, root / "ca.pem")["status"] == "HTTPS_MOCK_GATEWAY_REACHABLE":
                    return child
                if child.poll() is not None: raise RuntimeError("TLS_SERVER_FAILED")
                time.sleep(0.05)
            child.terminate(); child.wait(timeout=5)
            raise RuntimeError("TLS_SERVER_START_TIMEOUT")
        def request(method, path, data=None, extra=None, token=None):
            connection = http.client.HTTPSConnection("localhost", port, context=ssl.create_default_context(cafile=str(root / "ca.pem")), timeout=5)
            try:
                headers = {"Authorization": "Bearer " + (token or fixture.token_a), **(extra or {})}
                connection.request(method, path, body=data, headers=headers)
                response = connection.getresponse()
                return response.status, json.loads(response.read(65536))
            finally: connection.close()
        process = start()
        assert probe(url, root / "ca.pem")["status"] == "HTTPS_MOCK_GATEWAY_REACHABLE"
        assert probe(url)["status"] == "TLS_VERIFICATION_FAILED"
        status, created = request("POST", "/v1/transfers", json.dumps(fixture.body).encode(),
                                  {"Content-Type": "application/json", "Idempotency-Key": fixture.snapshot})
        assert status == 201
        transfer = created["gateway_transfer_id"]
        def upload(name, offset, data):
            return request("PUT", f"/v1/transfers/{transfer}/objects/{name}/chunks/{offset}", data,
                           {"Content-Type": "application/octet-stream", "X-Chunk-SHA256": fixture.digest(data)})
        assert upload("video.mp4", 0, fixture.video[:64])[0] == 200
        assert upload("video.mp4", 0, fixture.video[:64])[1]["replayed"]
        process.terminate(); process.wait(timeout=5); process = None
        assert probe(url, root / "ca.pem")["retryable"]
        process = start()
        status, resumed = request("GET", f"/v1/transfers/{transfer}")
        assert status == 200 and next(o for o in resumed["objects"] if o["name"] == "video.mp4")["accepted_bytes"] == 64
        for name, data in fixture.files.items():
            for offset in range(64 if name == "video.mp4" else 0, len(data), 64):
                assert upload(name, offset, data[offset:offset + 64])[0] == 200
        status, receipt = request("POST", f"/v1/transfers/{transfer}/complete")
        assert status == 200 and receipt["status"] == "MOCK_FORWARDED" and receipt["mock"] and not receipt["archived"]
        fixture.store.revoke(fixture.a)
        assert request("GET", f"/v1/transfers/{transfer}")[0] == 403
        assert request("GET", f"/v1/transfers/{transfer}", token=fixture.token_b)[0] == 404
        print(json.dumps({"status": "LOCAL_TLS_GATEWAY_VERIFIED", "checks": ["trusted_tls", "untrusted_ca_rejected", "chunk_replay",
                          "actual_server_restart_resume", "mock_complete", "independent_revocation", "owner_isolation", "offline_retryable"],
                          "public_deployment": False, "filesender_calls": 0}))
    finally:
        if process is not None: process.terminate(); process.wait(timeout=5)
        fixture.doCleanups()


if __name__ == "__main__":
    run()
