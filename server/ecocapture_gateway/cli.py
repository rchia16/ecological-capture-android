import argparse
import json
import logging
import os
from pathlib import Path
import sys

from .app import create_app
from .config import Config
from .errors import GatewayError
from .store import Store


class RedactedLog(logging.Filter):
    def filter(self, record):
        record.msg = "gateway.transport_event"
        record.args = ()
        record.exc_info = None
        record.stack_info = None
        return True


def main():
    parser = argparse.ArgumentParser(description="Mock-only gateway administration; no production participant provisioning.")
    parser.add_argument("--config", type=Path, required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--port", type=int, default=8000)
    serve.add_argument("--cert", type=Path)
    serve.add_argument("--key", type=Path)
    issue = sub.add_parser("issue-synthetic")
    issue.add_argument("--installation-id", required=True)
    issue.add_argument("--credential-out", type=Path, required=True)
    issue.add_argument("--study-code")
    revoke = sub.add_parser("revoke")
    revoke.add_argument("--installation-id", required=True)
    sub.add_parser("cleanup")
    args = parser.parse_args()
    raw = json.loads(args.config.read_text(encoding="utf-8"))
    raw["storage"] = Path(raw["storage"])
    raw["allowed_hosts"] = tuple(raw["allowed_hosts"])
    config = Config(**raw)
    store = Store(config)
    if args.action == "issue-synthetic":
        output = args.credential_out.absolute()
        repo = Path(__file__).resolve().parents[2]
        if output.resolve().is_relative_to(repo) or output.is_symlink():
            raise ValueError("CREDENTIAL_FILE_MUST_BE_OUTSIDE_REPOSITORY")
        fd = os.open(output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as file:
                token = store.issue(args.installation_id, "synthetic-checkpoint4-v1", args.study_code, eligible=True)
                json.dump({"installation_id": args.installation_id, "token": token, "mode": "SYNTHETIC_ONLY"}, file)
                file.flush(); os.fsync(file.fileno())
        except BaseException:
            output.unlink(missing_ok=True)
            raise
        print('{"status":"SYNTHETIC_CREDENTIAL_WRITTEN"}')
    elif args.action == "revoke":
        store.revoke(args.installation_id)
        print('{"status":"REVOKED"}')
    elif args.action == "cleanup":
        print(json.dumps(store.cleanup()))
    else:
        import uvicorn
        if bool(args.cert) != bool(args.key):
            raise ValueError("TLS_CERT_AND_KEY_REQUIRED_TOGETHER")
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
            logging.getLogger(name).addFilter(RedactedLog())
        uvicorn.run(create_app(config, store), host="127.0.0.1", port=args.port, workers=1,
                    proxy_headers=not bool(args.cert), forwarded_allow_ips="127.0.0.1", access_log=False,
                    ssl_certfile=str(args.cert) if args.cert else None, ssl_keyfile=str(args.key) if args.key else None,
                    server_header=False)


if __name__ == "__main__":
    try:
        main()
    except (GatewayError, ValueError, OSError, TypeError, KeyError):
        print('{"status":"GATEWAY_OPERATION_FAILED"}', file=sys.stderr)
        raise SystemExit(1)
