import importlib.util
from pathlib import Path
import socket
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("gatewayctl", Path(__file__).resolve().parents[1] / "deployment/gb10/gatewayctl.py")
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class GatewayControlTest(unittest.TestCase):
    def test_unit_scopes_shutdown_and_keeps_private_paths(self):
        text = control.unit_text("/usr/bin/apptainer")
        self.assertIn("KillMode=mixed", text)
        self.assertIn("TimeoutStopSec=20", text)
        self.assertIn("/home/rchia/server/ecocapture-gateway-state serve", text)
        self.assertIn("Restart=on-failure", text)
        self.assertNotIn("tailscale", text)
        self.assertNotIn("password", text)
        with self.assertRaises(control.ControlFailure): control.unit_text("/tmp/a\nExecStart=evil")
        self.assertIn("gateway-gb10-checkpoint5.sif", control.unit_text("/usr/bin/apptainer", "/home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif"))
        with self.assertRaises(control.ControlFailure): control.unit_text("/usr/bin/apptainer", "/other/unreviewed.sif")

    def test_install_updates_only_own_unit_and_rejects_foreign_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "gateway.service"
            text = control.unit_text("/usr/bin/apptainer")
            control.save_unit(path, text)
            control.save_unit(path, text)
            self.assertEqual(text, path.read_text())
            path.write_text("unrelated service")
            with self.assertRaises(control.ControlFailure): control.save_unit(path, text)
            self.assertEqual("unrelated service", path.read_text())

    def test_busy_port_does_not_terminate_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); listener.listen()
            port = listener.getsockname()[1]
            with self.assertRaises(control.ControlFailure): control.port_check(port)
            self.assertEqual(port, listener.getsockname()[1])
