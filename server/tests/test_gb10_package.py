import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "server/deployment/gb10" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class Gb10PackageTest(unittest.TestCase):
    def test_occupied_loopback_port_fails_without_stopping_existing_listener(self):
        import socket
        runtime = module("container_runtime")
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0)); listener.listen()
            port = listener.getsockname()[1]
            with self.assertRaises(runtime.RuntimeFailure) as error:
                runtime.assert_port_free(port)
            self.assertEqual(f"PORT_{port}_IN_USE", error.exception.code)
            self.assertEqual(port, listener.getsockname()[1])

    def test_valid_hostname_and_proxy_boundary(self):
        runtime = module("container_runtime")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(json.dumps({"allowed_hosts": ["gb10.tailabc.ts.net"], "storage": "/gateway-state/private"}))
            self.assertEqual("gb10.tailabc.ts.net", runtime.load_config(path))
        text = runtime.nginx_config("gb10.tailabc.ts.net", "/tmp/runtime-test")
        self.assertIn("listen 127.0.0.1:18080", text)
        self.assertIn("proxy_set_header X-Forwarded-Proto https", text)
        self.assertIn("proxy_request_buffering off", text)
        self.assertIn("if ($host != gb10.tailabc.ts.net)", text)
        for module_name in ("fastcgi", "uwsgi", "scgi"):
            self.assertIn(f"{module_name}_temp_path /tmp/runtime-test/{module_name};", text)

    def test_runtime_failure_diagnostics_do_not_echo_exception_contents(self):
        runtime = module("container_runtime")
        self.assertEqual("RUNTIME_STARTUP_FAILED", runtime.safe_failure_code(ValueError("private secret text")))
        self.assertEqual("REQUIRED_FILE_OR_EXECUTABLE_MISSING", runtime.safe_failure_code(FileNotFoundError("private/path")))
        self.assertEqual("PRIVATE_STATE_BIND_REQUIRED", runtime.safe_failure_code(ValueError("PRIVATE_STATE_BIND_REQUIRED")))
        self.assertEqual("NGINX_PROCESS_EXITED", runtime.safe_failure_code(runtime.RuntimeFailure("NGINX_PROCESS_EXITED")))

    def test_config_rejects_injection_and_unbound_storage(self):
        runtime = module("container_runtime")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            for host, storage in (("gb10.ts.net; include /etc/passwd;", "/gateway-state/private"),
                                  ("https://gb10.tailabc.ts.net", "/gateway-state/private"),
                                  ("gb10.tailabc.ts.net", "/home/rchia")):
                path.write_text(json.dumps({"allowed_hosts": [host], "storage": storage}))
                with self.assertRaises(ValueError): runtime.load_config(path)

    def test_source_bundle_is_checked_and_excludes_runtime_secrets(self):
        package = module("package_local")
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "gateway.tar.gz"
            result = package.build(ROOT, output)
            self.assertEqual(0, result["remote_actions"])
            with tarfile.open(output) as archive:
                names = archive.getnames()
                self.assertIn("server/deployment/gb10/gateway.def", names)
                definition = archive.extractfile("server/deployment/gb10/gateway.def").read().decode()
                for line in definition.split("%files\n", 1)[1].split("\n%", 1)[0].splitlines():
                    if line.strip():
                        source = line.split()[0]
                        self.assertTrue(source in names or any(n.startswith(source + "/") for n in names), source)
                self.assertFalse(any(x in name for name in names for x in (".venv", ".state", ".ini", ".pem", ".key", "filesender.py")))
                import hashlib
                for line in archive.extractfile("SHA256SUMS").read().decode().splitlines():
                    digest, name = line.split("  ", 1)
                    self.assertEqual(digest, hashlib.sha256(archive.extractfile(name).read()).hexdigest())
                    self.assertNotIn(b"\r\n", archive.extractfile(name).read())
            with self.assertRaises(FileExistsError): package.build(ROOT, output)
