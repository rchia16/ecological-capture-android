"""Mock-only two-process runtime. Host Tailscale owns TLS; no Tailscale secrets in SIF."""
import json
import errno
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time


class RuntimeFailure(Exception):
    def __init__(self, code):
        self.code = code


def assert_port_free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as error:
            code = "PORT_" + str(port) + ("_IN_USE" if error.errno == errno.EADDRINUSE else "_BIND_FAILED")
            raise RuntimeFailure(code) from None


def safe_failure_code(error):
    if isinstance(error, RuntimeFailure):
        return error.code
    if isinstance(error, FileNotFoundError):
        return "REQUIRED_FILE_OR_EXECUTABLE_MISSING"
    if isinstance(error, PermissionError):
        return "RUNTIME_PERMISSION_DENIED"
    if isinstance(error, json.JSONDecodeError):
        return "CONFIG_JSON_INVALID"
    known = {"ONE_FUNNEL_HOST_REQUIRED", "INVALID_FUNNEL_HOST", "PRIVATE_STATE_BIND_REQUIRED",
             "ACTION_REQUIRED", "INVALID_ACTION"}
    if isinstance(error, ValueError) and str(error) in known:
        return str(error)
    return "RUNTIME_STARTUP_FAILED"


def load_config(path):
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    host = raw.get("allowed_hosts")
    if not isinstance(host, list) or len(host) != 1 or not isinstance(host[0], str):
        raise ValueError("ONE_FUNNEL_HOST_REQUIRED")
    domain = host[0]
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.ts\.net", domain):
        raise ValueError("INVALID_FUNNEL_HOST")
    if raw.get("storage") != "/gateway-state/private":
        raise ValueError("PRIVATE_STATE_BIND_REQUIRED")
    return domain


def nginx_config(domain, temporary):
    # domain is validated before interpolation; temporary is our internally created path.
    return f'''worker_processes 1;
pid {temporary}/nginx.pid;
error_log /dev/null;
events {{ worker_connections 128; }}
http {{
    access_log off;
    client_body_temp_path {temporary}/body;
    proxy_temp_path {temporary}/proxy;
    fastcgi_temp_path {temporary}/fastcgi;
    uwsgi_temp_path {temporary}/uwsgi;
    scgi_temp_path {temporary}/scgi;
    server {{
        listen 127.0.0.1:18080;
        server_name {domain};
        server_tokens off;
        client_max_body_size 4m;
        client_body_timeout 30s;
        send_timeout 30s;
        if ($host != {domain}) {{ return 444; }}
        location /v1/ {{
            proxy_pass http://127.0.0.1:8000;
            proxy_http_version 1.1;
            proxy_set_header Host {domain};
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header Connection "";
            proxy_request_buffering off;
            proxy_buffering off;
            proxy_connect_timeout 5s;
            proxy_send_timeout 30s;
            proxy_read_timeout 120s;
        }}
        location / {{ return 404; }}
    }}
}}
'''


def main():
    if len(sys.argv) < 2:
        raise ValueError("ACTION_REQUIRED")
    action, *arguments = sys.argv[1:]
    config = "/gateway-config/gateway.json"
    domain = load_config(config)
    if action in {"issue-synthetic", "revoke", "cleanup"}:
        os.execv(sys.executable, [sys.executable, "-B", "-m", "ecocapture_gateway.cli",
                                "--config", config, action, *arguments])
    if action != "serve" or arguments:
        raise ValueError("INVALID_ACTION")
    assert_port_free(8000)
    assert_port_free(18080)
    os.umask(0o077)
    children = []
    stopped = False
    def stop(signum, frame):
        nonlocal stopped
        stopped = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with tempfile.TemporaryDirectory(prefix="ecocapture-runtime-") as tmp:
        configuration = Path(tmp) / "nginx.conf"
        configuration.write_text(nginx_config(domain, tmp), encoding="utf-8")
        try:
            check = subprocess.run(["nginx", "-e", "/dev/null", "-p", tmp + "/", "-t", "-c", str(configuration)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            if check.returncode:
                denied = b"Permission denied" in check.stderr or b"Read-only file system" in check.stderr
                raise RuntimeFailure("NGINX_PATH_PERMISSION_FAILED" if denied else "NGINX_CONFIG_CHECK_FAILED")
            children.append(subprocess.Popen([sys.executable, "-B", "-m", "ecocapture_gateway.cli",
                "--config", config, "serve", "--port", "8000"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            children.append(subprocess.Popen(["nginx", "-e", "/dev/null", "-p", tmp + "/", "-c", str(configuration), "-g", "daemon off;"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            print('{"status":"LOOPBACK_PROCESSES_STARTED","funnel_configuration_changed":false}', flush=True)
            while not stopped:
                for index, child in enumerate(children):
                    if child.poll() is not None:
                        raise RuntimeFailure("GATEWAY_PROCESS_EXITED" if index == 0 else "NGINX_PROCESS_EXITED")
                time.sleep(0.25)
        finally:
            for child in children:
                if child.poll() is None:
                    child.terminate()
            for child in children:
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"status": "GB10_RUNTIME_FAILED", "code": safe_failure_code(error)}), file=sys.stderr)
        raise SystemExit(1)
