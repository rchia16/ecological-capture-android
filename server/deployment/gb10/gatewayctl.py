"""Manage only the GB10 mock gateway using rchia's systemd user service."""
import argparse
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys

UNIT = "ecocapture-gateway.service"
MARKER = "# Managed by EcoCapture gatewayctl v1"
PROJECT = Path("/home/rchia/projects/ecological-gateway")
CONFIG = Path("/home/rchia/server/ecocapture-gateway-config")
STATE = Path("/home/rchia/server/ecocapture-gateway-state")
HOST = "promaxgb10-4e2b.tail06a565.ts.net"


class ControlFailure(Exception):
    pass


def unit_text(apptainer, image=None):
    path = PurePosixPath(apptainer)
    if not path.is_absolute() or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(path)):
        raise ControlFailure("INVALID_APPTAINER_PATH")
    image = PurePosixPath(image) if image is not None else PurePosixPath(PROJECT.as_posix()) / "gateway-gb10-fixed.sif"
    if image.parent != PurePosixPath(PROJECT.as_posix()) or image.suffix != ".sif" or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(image)):
        raise ControlFailure("IMAGE_MUST_BE_SIF_IN_GATEWAY_PROJECT")
    return f'''{MARKER}
[Unit]
Description=EcoCapture GB10 mock gateway (Apptainer)
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
WorkingDirectory={PROJECT.as_posix()}
Environment=PATH={path.parent}:/usr/local/bin:/usr/bin:/bin
ExecStart=/bin/sh {PROJECT.as_posix()}/server/deployment/gb10/run.sh {image} {CONFIG.as_posix()} {STATE.as_posix()} serve
Restart=on-failure
RestartSec=5
KillMode=mixed
TimeoutStopSec=20
UMask=0077
StandardOutput=journal
StandardError=journal
SyslogIdentifier=ecocapture-gateway

[Install]
WantedBy=default.target
'''


def save_unit(path, text):
    if path.is_symlink(): raise ControlFailure("UNSAFE_UNIT_PATH")
    if path.exists() and not path.read_text(encoding="utf-8").startswith(MARKER + "\n"):
        raise ControlFailure("EXISTING_UNIT_NOT_MANAGED")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".new")
    with temporary.open("x", encoding="utf-8", newline="\n") as output:
        output.write(text)
    try:
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def systemctl(*arguments, checked=True):
    result = subprocess.run(["systemctl", "--user", *arguments], capture_output=True, text=True)
    if checked and result.returncode:
        raise ControlFailure("SYSTEMCTL_FAILED_CHECK_USER_SESSION")
    return result


def port_check(port):
    with socket.socket() as connection:
        try: connection.bind(("127.0.0.1", port))
        except OSError: raise ControlFailure(f"PORT_{port}_BUSY_OR_UNAVAILABLE") from None


def health():
    connection = http.client.HTTPConnection("127.0.0.1", 18080, timeout=3)
    try:
        connection.request("GET", "/v1/health", headers={"Host": HOST})
        response = connection.getresponse()
        data = response.read(4097)
        return response.status == 200 and len(data) <= 4096 and json.loads(data) == {"status": "ok", "transport": "mock"}
    except (OSError, ValueError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("install", "start", "stop", "restart", "status", "enable", "disable", "logs"))
    parser.add_argument("--image", type=Path, help="Install only: select an explicitly built SIF in the gateway project")
    args = parser.parse_args()
    if sys.platform != "linux": raise ControlFailure("RUN_CONTROLLER_ON_GB10_LINUX")
    if os.getuid() == 0: raise ControlFailure("RUN_AS_RCHIA_NOT_ROOT")
    unit = Path.home() / ".config/systemd/user" / UNIT
    if args.image is not None and args.action != "install": raise ControlFailure("IMAGE_SELECTION_IS_INSTALL_ONLY")
    if args.action == "install":
        executable = shutil.which("apptainer")
        if not executable: raise ControlFailure("APPTAINER_NOT_FOUND")
        image = args.image or PROJECT / "gateway-gb10-fixed.sif"
        for path in (image, PROJECT / "server/deployment/gb10/run.sh", CONFIG / "gateway.json"):
            if not path.is_file() or path.is_symlink(): raise ControlFailure("REQUIRED_GATEWAY_FILE_MISSING_OR_UNSAFE")
        if STATE.is_symlink() or not STATE.is_dir() or STATE.stat().st_uid != os.getuid() or STATE.stat().st_mode & 0o077:
            raise ControlFailure("STATE_REQUIRES_PRIVATE_OWNER_PERMISSIONS")
        save_unit(unit, unit_text(executable, image.as_posix()))
        systemctl("daemon-reload")
        print('{"status":"USER_SERVICE_INSTALLED","started":false,"funnel_changed":false}')
        return
    if not unit.is_file() or unit.is_symlink() or not unit.read_text().startswith(MARKER + "\n"):
        raise ControlFailure("INSTALL_MANAGED_USER_SERVICE_FIRST")
    if args.action == "logs":
        # Only our unit: existing launcher emits redacted fixed status messages.
        raise SystemExit(subprocess.call(["journalctl", "--user", "-u", UNIT, "-n", "30", "--no-pager"]))
    if args.action == "status":
        result = systemctl("show", UNIT, "--property=ActiveState,SubState,MainPID", checked=False)
        fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        print(json.dumps({"status": "GATEWAY_STATUS", "active": fields.get("ActiveState", "unknown"),
                          "substate": fields.get("SubState", "unknown"), "main_pid": fields.get("MainPID", "0"),
                          "mock_health_ok": health()}))
        return
    if args.action in {"start", "restart"}:
        active = systemctl("is-active", UNIT, checked=False).returncode == 0
        if not active:
            port_check(8000); port_check(18080)
    systemctl(args.action, UNIT)
    print(json.dumps({"status": "GATEWAY_" + args.action.upper() + "_REQUESTED", "funnel_changed": False}))


if __name__ == "__main__":
    try:
        main()
    except ControlFailure as error:
        print(json.dumps({"status": "GATEWAY_CONTROL_FAILED", "code": str(error)})); raise SystemExit(1)
    except Exception:
        print('{"status":"GATEWAY_CONTROL_FAILED","code":"LOCAL_OPERATION_FAILED"}'); raise SystemExit(1)
