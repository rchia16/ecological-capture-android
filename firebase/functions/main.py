"""Authenticated HTTP control plane. No plaintext footage or private decryption keys."""
import hashlib
import json
import os
import re
import time
from datetime import datetime, timezone

from firebase_admin import auth, firestore, initialize_app, storage
from firebase_functions import https_fn, options
from google.auth.transport.requests import Request
from google.oauth2 import id_token
from google.cloud.firestore_v1.base_query import FieldFilter

from contract import (Rejected, canonical_uuid, identifier, public_row, registration,
                      require, same_registration, token_hash, valid_enrolment)

initialize_app()
REGION = os.environ.get("ECO_REGION", "australia-southeast1")


def db():
    return firestore.client()


def get(collection, key):
    return db().collection(collection).document(key).get().to_dict()


def bearer(request):
    value = request.headers.get("Authorization", "")
    require(value.startswith("Bearer ") and len(value) < 16384, "AUTH_REQUIRED", 401)
    return value[7:]


def firebase_identity(request):
    try:
        return auth.verify_id_token(bearer(request), check_revoked=True)
    except Rejected:
        raise
    except Exception:
        raise Rejected("AUTH_REQUIRED", 401) from None


def installation(request):
    claims = firebase_identity(request)
    row = get("installations", claims["uid"])
    require(row and row.get("enabled") is True and row.get("uid") == claims["uid"], "INSTALLATION_DISABLED", 403)
    study = get("studies", row["study_id"])
    require(study and study.get("enabled") is True, "STUDY_DISABLED", 403)
    return row, study


def researcher(request):
    claims = firebase_identity(request)
    require(claims.get("email_verified") is True, "RESEARCHER_REQUIRED", 403)
    email_hash = hashlib.sha256(claims.get("email", "").lower().encode()).hexdigest()
    row = get("researchers", email_hash)
    require(row and row.get("enabled") is True, "RESEARCHER_REQUIRED", 403)
    return row["studies"]


def worker(request):
    audience = os.environ.get("ECO_WORKER_AUDIENCE", "")
    email = os.environ.get("ECO_WORKER_EMAIL", "")
    require(audience and email, "WORKER_NOT_CONFIGURED", 503)
    try:
        claims = id_token.verify_oauth2_token(bearer(request), Request(), audience=audience)
    except Exception:
        raise Rejected("WORKER_REQUIRED", 403) from None
    require(claims.get("email") == email and claims.get("email_verified") is True,
            "WORKER_REQUIRED", 403)
    # One dedicated receiver per configured study; no Firestore IAM access for GB10.
    return identifier(os.environ.get("ECO_WORKER_STUDY", ""))


def profile(row, study):
    return {"study_id": row["study_id"], "participant_code": row["participant_code"],
            "key_versions": study["key_versions"], "recipients": study["recipients"]}


def enrol(body):
    require(type(body) is dict and set(body) == {"code", "installation_id"})
    uid = canonical_uuid(body["installation_id"])
    ref = db().collection("enrolments").document(token_hash(body["code"]))
    install_ref = db().collection("installations").document(uid)

    @firestore.transactional
    def consume(transaction):
        code = ref.get(transaction=transaction).to_dict()
        valid_enrolment(code, uid, time.time())
        previous = install_ref.get(transaction=transaction).to_dict()
        require(previous is None or (previous.get("enabled") is True and
                previous["study_id"] == code["study_id"] and
                previous["participant_code"] == code["participant_code"]), "INSTALLATION_CONFLICT", 409)
        study = db().collection("studies").document(code["study_id"]).get(transaction=transaction).to_dict()
        require(study and study.get("enabled") is True, "STUDY_DISABLED", 403)
        row = {"uid": uid, "study_id": code["study_id"], "participant_code": code["participant_code"], "enabled": True}
        transaction.set(install_ref, row)
        transaction.update(ref, {"installation_id": uid})
        return profile(row, study)

    # Signing first avoids consuming a code when token-signing permission is absent.
    token = auth.create_custom_token(uid).decode()
    result = consume(db().transaction())
    return {**result, "custom_token": token}


def register(request, body):
    row, study = installation(request)
    data = registration(body, row, study)
    ref = db().collection("packages").document(data["snapshot_id"])

    @firestore.transactional
    def create(transaction):
        existing = ref.get(transaction=transaction).to_dict()
        if existing:
            same_registration(existing, data)
            return existing
        new = {**data, "status": "UPLOADING", "registered_at": int(time.time() * 1000), "archived": False}
        transaction.create(ref, new)
        return new

    saved = create(db().transaction())
    return {"object_path": saved["object_path"], "status": saved["status"], "snapshot_id": saved["snapshot_id"]}


def complete(request, body):
    row, _ = installation(request)
    require(type(body) is dict and set(body) == {"snapshot_id"})
    snapshot = canonical_uuid(body["snapshot_id"])
    ref = db().collection("packages").document(snapshot)
    package = ref.get().to_dict()
    require(package and package["owner_uid"] == row["uid"], "PACKAGE_UNAVAILABLE", 404)
    if package["status"] != "UPLOADING":
        return public_row(package)
    blob = storage.bucket().get_blob(package["object_path"])
    require(blob and blob.size == package["size_bytes"], "UPLOAD_INCOMPLETE", 409)
    digest = hashlib.sha256()
    # Construct the download from the configured bucket, not a returned mediaLink.
    # Pin its generation and stop at the measured size, without an extra range-at-EOF request.
    download = storage.bucket().blob(package["object_path"], generation=blob.generation)
    remaining = package["size_bytes"]
    with download.open("rb", chunk_size=1024 * 1024, timeout=60) as stream:
        while remaining:
            block = stream.read(min(1024 * 1024, remaining))
            require(block and len(block) <= remaining, "UPLOAD_INCOMPLETE", 409)
            digest.update(block)
            remaining -= len(block)
    require(digest.hexdigest() == package["ciphertext_sha256"], "CIPHERTEXT_HASH_MISMATCH", 409)

    @firestore.transactional
    def finish(transaction):
        current = ref.get(transaction=transaction).to_dict()
        if current["status"] == "UPLOADING":
            transaction.update(ref, {"status": "AWAITING_GB10", "generation": str(blob.generation)})
            current = {**current, "status": "AWAITING_GB10"}
        return public_row(current)
    return finish(db().transaction())


def receipt(request, body):
    study = worker(request)
    require(type(body) is dict and set(body) == {"snapshot_id", "ciphertext_sha256", "status", "gb10_path", "error_code"})
    ref = db().collection("packages").document(canonical_uuid(body["snapshot_id"]))

    @firestore.transactional
    def save(transaction):
        row = ref.get(transaction=transaction).to_dict()
        require(row and row["study_id"] == study, "PACKAGE_UNAVAILABLE", 404)
        require(row["ciphertext_sha256"] == body["ciphertext_sha256"], "RECEIPT_CONFLICT", 409)
        require(row["status"] in ("AWAITING_GB10", "NEEDS_ATTENTION", "VERIFIED_GB10"), "NOT_READY", 409)
        require(body["status"] in ("VERIFIED_GB10", "NEEDS_ATTENTION"))
        day = datetime.fromtimestamp(row["recorded_at_epoch_ms"] / 1000, timezone.utc).strftime("%Y-%m-%d")
        expected_path = f"{study}/{row['participant_code']}/{day}/{row['event_id']}/{row['snapshot_id']}"
        path = body["gb10_path"]
        if body["status"] == "VERIFIED_GB10":
            require(path == expected_path)
            require(body["error_code"] is None)
        else:
            require(path is None and body["error_code"] == "RECEIVE_FAILED")
        if row["status"] == "VERIFIED_GB10":
            require(body["status"] == row["status"] and path == row["gb10_path"], "RECEIPT_CONFLICT", 409)
            return public_row(row)
        update = {k: body[k] for k in ("status", "gb10_path", "error_code")}
        if body["status"] == "VERIFIED_GB10":
            update["received_at"] = int(time.time() * 1000)
        transaction.update(ref, update)
        return public_row({**row, **update})
    return save(db().transaction())


def listing(request, for_worker=False):
    study = identifier(request.args.get("study", ""))
    if for_worker:
        require(study == worker(request), "STUDY_FORBIDDEN", 403)
    else:
        require(study in researcher(request), "STUDY_FORBIDDEN", 403)
    query = db().collection("packages").where(filter=FieldFilter("study_id", "==", study))
    if for_worker:
        query = query.where(filter=FieldFilter("status", "==", "AWAITING_GB10"))
    query = query.order_by("__name__")
    cursor = request.args.get("after")
    if cursor:
        query = query.start_after({"__name__": db().collection("packages").document(canonical_uuid(cursor))})
    rows = list(query.limit(100).stream())
    items = [r.to_dict() if for_worker else public_row(r.to_dict()) for r in rows]
    return {"items": items, "next": rows[-1].id if len(rows) == 100 else None}


@https_fn.on_request(region=REGION, timeout_sec=540, memory=options.MemoryOption.MB_512,
                     max_instances=3, concurrency=4)
def api(request: https_fn.Request) -> https_fn.Response:
    try:
        require(not request.query_string or request.method == "GET")
        require(request.content_length is None or request.content_length <= 16384, "REQUEST_TOO_LARGE", 413)
        route = request.path.removeprefix("/api").rstrip("/")
        body = request.get_json(silent=True) if request.method == "POST" else None
        handlers = {("POST", "/enrol"): lambda: enrol(body),
                    ("POST", "/register"): lambda: register(request, body),
                    ("POST", "/complete"): lambda: complete(request, body),
                    ("POST", "/receipt"): lambda: receipt(request, body),
                    ("GET", "/profile"): lambda: profile(*installation(request)),
                    ("GET", "/studies"): lambda: {"studies": researcher(request)},
                    ("GET", "/catalogue"): lambda: listing(request),
                    ("GET", "/pending"): lambda: listing(request, True)}
        if request.method == "GET" and route.startswith("/status/"):
            row, _ = installation(request)
            package = get("packages", canonical_uuid(route.removeprefix("/status/")))
            require(package and package["owner_uid"] == row["uid"], "PACKAGE_UNAVAILABLE", 404)
            result = public_row(package)
        else:
            require((request.method, route) in handlers, "NOT_FOUND", 404)
            result = handlers[(request.method, route)]()
        return https_fn.Response(json.dumps(result), content_type="application/json", headers={"Cache-Control": "no-store"})
    except Rejected as error:
        return https_fn.Response(json.dumps({"error": error.code}), status=error.status,
                                 content_type="application/json", headers={"Cache-Control": "no-store"})
    except Exception:
        # Never return/log request bodies, tokens, object URLs, or SDK exception text.
        return https_fn.Response('{"error":"SERVICE_UNAVAILABLE"}', status=503,
                                 content_type="application/json", headers={"Cache-Control": "no-store"})
