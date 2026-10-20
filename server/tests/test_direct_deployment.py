import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "deployment/direct"


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


class DirectDeploymentTests(unittest.TestCase):
    def test_public_and_private_proxies_are_separate_and_loopback_only(self):
        configuration = module("container_runtime").nginx_config("example.tail123.ts.net", "/tmp/generated")
        public, private = configuration.split("listen 127.0.0.1:18083;")
        self.assertIn("listen 127.0.0.1:18082;", public)
        self.assertIn('proxy_set_header Tailscale-User-Login "";', public)
        self.assertNotIn("8003", public)
        self.assertIn("proxy_pass http://127.0.0.1:18080", public)
        self.assertIn("proxy_pass http://127.0.0.1:8002", public)
        self.assertIn("proxy_pass http://127.0.0.1:8003", private)
        self.assertIn("Tailscale-User-Login $http_tailscale_user_login", private)
        self.assertNotIn("listen 0.0.0.0", configuration)
        with self.assertRaises(ValueError): module("container_runtime").nginx_config("bad;configuration", "/tmp/generated")

    def test_source_bundle_excludes_credentials_and_build_outputs(self):
        entries = list(module("package_source").sources())
        names = {name for name, _ in entries}
        self.assertIn("server/ecocapture_direct/web/index.html", names)
        self.assertIn("server/deployment/direct/direct.def", names)
        self.assertFalse(any(".local." in name or "agekey" in name or "node_modules" in name or "__pycache__" in name for name in names))
        self.assertTrue(all(not name.startswith(("firebase/", "app/", "crypto/", "tools/")) for name in names))
