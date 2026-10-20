"""Add the direct routes while preserving existing services. Default is read-only."""
import argparse
import json
from pathlib import Path
import subprocess
import urllib.request

HOST = "promaxgb10-4e2b.tail06a565.ts.net"
PUBLIC = HOST + ":443"
PRIVATE = HOST + ":9443"


def status():
    return json.loads(subprocess.run(["tailscale", "serve", "status", "--json"], check=True, capture_output=True).stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true"); parser.add_argument("--backup", type=Path)
    args = parser.parse_args()
    before = status()
    assert before["Web"][PUBLIC] == {"Handlers": {"/": {"Proxy": "http://127.0.0.1:18080"}}}
    assert PRIVATE not in before["Web"] and "9443" not in before["TCP"]
    assert not before.get("AllowFunnel", {}).get(PRIVATE)
    for port, path, expected in ((18082, "/v2/health", 200), (18082, "/research/api/catalogue", 404), (18083, "/research/api/catalogue", 403)):
        request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"Host": HOST})
        try:
            with urllib.request.urlopen(request, timeout=10) as response: code = response.status
        except urllib.error.HTTPError as error:
            code = error.code; error.close()
        assert code == expected
    if not args.apply:
        print('{"status":"DIRECT_ROUTES_READY","changed":false}'); return
    assert args.backup and not args.backup.exists()
    with args.backup.open("x", encoding="utf-8") as output: json.dump(before, output, indent=2)
    subprocess.run(["tailscale", "serve", "--bg", "--yes", "--https=9443", "http://127.0.0.1:18083"], check=True, capture_output=True)
    try:
        subprocess.run(["tailscale", "funnel", "--bg", "--yes", "--https=443", "http://127.0.0.1:18082"], check=True, capture_output=True)
        after = status()
        expected = json.loads(json.dumps(before))
        expected["Web"][PUBLIC] = {"Handlers": {"/": {"Proxy": "http://127.0.0.1:18082"}}}
        expected["Web"][PRIVATE] = {"Handlers": {"/": {"Proxy": "http://127.0.0.1:18083"}}}
        expected["TCP"]["9443"] = {"HTTPS": True}
        assert after == expected
    except BaseException:
        subprocess.run(["tailscale", "funnel", "--bg", "--yes", "--https=443", "http://127.0.0.1:18080"], check=True, capture_output=True)
        subprocess.run(["tailscale", "serve", "--https=9443", "off"], check=True, capture_output=True)
        raise
    print('{"status":"DIRECT_ROUTES_APPLIED","researcher_funnel":false,"other_routes_preserved":true}')


if __name__ == "__main__": main()
