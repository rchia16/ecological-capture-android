"""Operator-only provisioning via ADC. Never deploy this as an HTTP endpoint."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    study = sub.add_parser("study"); study.add_argument("--id", required=True); study.add_argument("--policy", type=Path, required=True)
    enrol = sub.add_parser("enrol"); enrol.add_argument("--study", required=True); enrol.add_argument("--participant", required=True); enrol.add_argument("--output", type=Path, required=True)
    researcher = sub.add_parser("researcher"); researcher.add_argument("--email", required=True); researcher.add_argument("--study", required=True)
    revoke = sub.add_parser("revoke"); revoke.add_argument("--installation", required=True)
    retry = sub.add_parser("retry"); retry.add_argument("--snapshot", required=True)
    args = parser.parse_args()
    from firebase_admin import auth, firestore, initialize_app
    import sys
    sys.path.insert(0, str(Path(__file__).parent / "functions"))
    from contract import identifier, canonical_uuid, require
    initialize_app(options={"projectId": args.project})
    db = firestore.client()
    if args.command == "study":
        policy = json.loads(args.policy.read_text(encoding="utf-8"))
        require(set(policy) == {"recipients", "key_versions"})
        require(len(policy["recipients"]) == len(set(policy["recipients"])) == 2)
        require(all(type(key) is str and re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", key) for key in policy["recipients"]))
        require(len(policy["key_versions"]) == len(set(policy["key_versions"])) == 2)
        for key in policy["key_versions"]: identifier(key)
        db.collection("studies").document(identifier(args.id)).set({**policy, "enabled": True})
    elif args.command == "enrol":
        require(db.collection("studies").document(identifier(args.study)).get().exists)
        identifier(args.participant)
        require(not args.output.exists(), "FRESH_OUTPUT_DIRECTORY_REQUIRED")
        require(not args.output.resolve().is_relative_to(Path(__file__).resolve().parents[1]), "OUTPUT_MUST_BE_OUTSIDE_REPOSITORY")
        import qrcode
        import qrcode.image.svg
        args.output.mkdir(mode=0o700, parents=False)
        token = secrets.token_urlsafe(32)
        link = "ecocapture://enrol?code=" + token
        # Only the token hash is retained in Firestore. Treat both output files as credentials.
        db.collection("enrolments").document(hashlib.sha256(token.encode()).hexdigest()).create({
            "study_id": args.study, "participant_code": args.participant,
            "enabled": True, "expires_at": time.time() + 24 * 3600, "installation_id": None})
        with (args.output / "enrolment.txt").open("x", encoding="utf-8") as stream: stream.write(link + "\n" + token + "\n")
        image = qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage)
        image.save(args.output / "enrolment.svg")
    elif args.command == "researcher":
        identifier(args.study)
        email = args.email.strip().lower(); require("@" in email)
        ref = db.collection("researchers").document(hashlib.sha256(email.encode()).hexdigest())
        ref.set({"enabled": True, "studies": firestore.ArrayUnion([args.study])}, merge=True)
    elif args.command == "revoke":
        uid = canonical_uuid(args.installation)
        db.collection("installations").document(uid).update({"enabled": False})
        auth.revoke_refresh_tokens(uid)
    else:
        ref = db.collection("packages").document(canonical_uuid(args.snapshot))
        @firestore.transactional
        def retry(transaction):
            row = ref.get(transaction=transaction).to_dict()
            require(row and row["status"] == "NEEDS_ATTENTION", "ATTENTION_STATE_REQUIRED")
            transaction.update(ref, {"status": "AWAITING_GB10", "error_code": None})
        retry(db.transaction())
    print('{"status":"ADMIN_OPERATION_COMPLETE"}')


if __name__ == "__main__":
    try: main()
    except Exception:
        print('{"status":"ADMIN_OPERATION_FAILED"}'); raise SystemExit(1)
