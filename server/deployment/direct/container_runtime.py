"""Separate public/private listeners; Tailscale owns TLS and researcher identity."""
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time

CONFIG = "/direct-config/direct.json"


def nginx_config(domain, temporary):
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*\.ts\.net", domain):
        raise ValueError("INVALID_TAILSCALE_HOST")
    common = f'''proxy_http_version 1.1;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For 127.0.0.1;
        proxy_set_header Connection "";
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_connect_timeout 5s;
        proxy_read_timeout 120s;
        proxy_send_timeout 30s;'''
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
    server_tokens off;
    client_max_body_size 4m;
    client_body_timeout 30s;
    send_timeout 30s;
    server {{
        listen 127.0.0.1:18082;
        server_name {domain};
        if ($host != {domain}) {{ return 444; }}
        {common}
        proxy_set_header Host {domain};
        proxy_set_header Tailscale-User-Login "";
        proxy_set_header Tailscale-User-Name "";
        proxy_set_header Tailscale-User-Profile-Pic "";
        location /v1/ {{ proxy_pass http://127.0.0.1:18080; }}
        location /v2/ {{ proxy_pass http://127.0.0.1:8002; }}
        location / {{ return 404; }}
    }}
    server {{
        listen 127.0.0.1:18083;
        server_name {domain};
        if ($host != {domain}) {{ return 444; }}
        {common}
        proxy_set_header Host {domain}:9443;
        proxy_set_header Tailscale-User-Login $http_tailscale_user_login;
        location / {{ proxy_pass http://127.0.0.1:8003; }}
    }}
}}
'''


def main():
    os.umask(0o077)
    from ecocapture_direct.config import DirectConfig
    config = DirectConfig.load(CONFIG)
    if config.gateway.storage != Path("/direct-state/private") or len(config.gateway.allowed_hosts) != 1:
        raise ValueError("PRIVATE_BINDS_REQUIRED")
    if config.identity != Path("/direct-secrets/gb10.agekey"):
        raise ValueError("PRIVATE_KEY_BIND_REQUIRED")
    arguments = sys.argv[1:]
    if arguments and arguments[0] != "serve":
        os.execv(sys.executable, [sys.executable, "-B", "-m", "ecocapture_direct.cli", "--config", CONFIG, *arguments])
    if arguments != ["serve"]: raise ValueError("ACTION_REQUIRED")
    for port in (8002, 8003, 18082, 18083):
        with socket.socket() as probe: probe.bind(("127.0.0.1", port))
    children = []
    stopped = False
    def stop(signum, frame):
        nonlocal stopped
        stopped = True
    signal.signal(signal.SIGTERM, stop); signal.signal(signal.SIGINT, stop)
    with tempfile.TemporaryDirectory(prefix="ecocapture-direct-") as tmp:
        nginx = Path(tmp) / "nginx.conf"
        nginx.write_text(nginx_config(config.gateway.allowed_hosts[0], tmp), encoding="utf-8")
        base = ["nginx", "-e", "/dev/null", "-p", tmp + "/", "-c", str(nginx)]
        subprocess.run([*base, "-t"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for action in ("public", "researcher", "verify"):
                children.append(subprocess.Popen([sys.executable, "-B", "-m", "ecocapture_direct.cli", "--config", CONFIG, action],
                                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            children.append(subprocess.Popen([*base, "-g", "daemon off;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            print('{"status":"DIRECT_LOOPBACK_STARTED","routes_changed":false}', flush=True)
            while not stopped:
                if any(child.poll() is not None for child in children): raise RuntimeError("CHILD_EXITED")
                time.sleep(0.25)
        finally:
            for child in children:
                if child.poll() is None: child.terminate()
            for child in children:
                try: child.wait(timeout=5)
                except subprocess.TimeoutExpired: child.kill(); child.wait()


if __name__ == "__main__":
    try: main()
    except Exception:
        print('{"status":"DIRECT_RUNTIME_FAILED"}', file=sys.stderr)
        raise SystemExit(1)
