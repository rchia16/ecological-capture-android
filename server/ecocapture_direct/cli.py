"""Local administration and separately bound services; no browser administration endpoints."""
import argparse
import json
import logging
import os
from pathlib import Path
import sys
import time

from ecocapture_gateway.cli import RedactedLog
from ecocapture_gateway.errors import require
from .config import DirectConfig
from .store import DirectStore


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    for name, port in (("public", 8002), ("researcher", 8003)):
        serve = sub.add_parser(name); serve.add_argument("--port", type=int, default=port)
        serve.add_argument("--cert", type=Path); serve.add_argument("--key", type=Path)
    worker = sub.add_parser("verify"); worker.add_argument("--once", action="store_true")
    enrol = sub.add_parser("issue-enrolment"); enrol.add_argument("--participant", required=True); enrol.add_argument("--output", type=Path, required=True)
    grant = sub.add_parser("researcher-access"); grant.add_argument("--login", required=True); grant.add_argument("--disable", action="store_true")
    revoke = sub.add_parser("revoke"); revoke.add_argument("--installation", required=True)
    retry = sub.add_parser("retry-verification"); retry.add_argument("--snapshot", required=True)
    archive = sub.add_parser("record-archive"); archive.add_argument("--snapshot", required=True)
    archive.add_argument("--destination", type=Path, required=True); archive.add_argument("--label", required=True)
    archive.add_argument("--confirm-approved-destination", action="store_true", required=True)
    args = parser.parse_args()
    if os.name != "nt": os.umask(0o077)
    store = DirectStore(DirectConfig.load(args.config))
    if args.command in ("public", "researcher"):
        import uvicorn
        from .app import public_app, researcher_app
        require(bool(args.cert) == bool(args.key), 400, "TLS_PAIR_REQUIRED")
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access"): logging.getLogger(name).addFilter(RedactedLog())
        app = public_app(store) if args.command == "public" else researcher_app(store)
        uvicorn.run(app, host="127.0.0.1", port=args.port, workers=1, proxy_headers=not bool(args.cert),
                    forwarded_allow_ips="127.0.0.1", access_log=False, server_header=False,
                    ssl_certfile=str(args.cert) if args.cert else None, ssl_keyfile=str(args.key) if args.key else None)
    elif args.command == "verify":
        from .receiver import receiver
        verifier = receiver(store)
        try:
            while True:
                try:
                    verifier.once()
                    if args.once: print('{"status":"DIRECT_VERIFICATION_COMPLETE"}'); return 0
                except Exception:
                    print('{"status":"DIRECT_VERIFICATION_FAILED"}', flush=True)
                    if args.once: return 1
                time.sleep(5)
        finally: verifier.close()
    elif args.command == "issue-enrolment":
        import qrcode
        import qrcode.image.svg
        output = args.output.absolute()
        require(not output.exists() and not output.is_symlink() and
                not output.resolve().is_relative_to(Path(__file__).resolve().parents[2]), 400, "NEW_EXTERNAL_PRIVATE_DIRECTORY_REQUIRED")
        output.mkdir(mode=0o700)
        code = store.issue_code(args.participant)
        link = "ecocapture://gb10-enrol?code=" + code
        with (output / "enrolment.txt").open("x", encoding="utf-8") as stream: stream.write(link + "\n" + code + "\n")
        qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage).save(output / "enrolment.svg")
        print('{"status":"ENROLMENT_WRITTEN"}')
    elif args.command == "researcher-access": store.grant(args.login, not args.disable)
    elif args.command == "revoke": store.revoke(args.installation)
    elif args.command == "retry-verification": store.retry_verification(args.snapshot)
    else: store.record_archive(args.snapshot, args.destination, args.label)
    return 0


if __name__ == "__main__":
    try: raise SystemExit(main())
    except Exception:
        print('{"status":"DIRECT_OPERATION_FAILED"}', file=sys.stderr); raise SystemExit(1)
