"""Small, dependency-free wire contract shared with the receiver tests."""
import hashlib
import re
import uuid

MAX_BYTES = 256 * 1024 * 1024
REGISTER_FIELDS = {"snapshot_id", "event_id", "recorded_at_epoch_ms", "size_bytes",
                   "ciphertext_sha256", "manifest_sha256", "key_versions"}


class Rejected(Exception):
    def __init__(self, code, status=400):
        self.code, self.status = code, status


def require(condition, code="INVALID_REQUEST", status=400):
    if not condition:
        raise Rejected(code, status)


def identifier(value):
    require(type(value) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value))
    return value


def canonical_uuid(value):
    try:
        require(type(value) is str and str(uuid.UUID(value)) == value)
    except (ValueError, AttributeError):
        raise Rejected("INVALID_IDENTIFIER") from None
    return value


def sha(value):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value))
    return value


def registration(body, installation, study):
    require(type(body) is dict and set(body) == REGISTER_FIELDS)
    canonical_uuid(body["snapshot_id"]); canonical_uuid(body["event_id"])
    sha(body["ciphertext_sha256"]); sha(body["manifest_sha256"])
    require(type(body["size_bytes"]) is int and 1 <= body["size_bytes"] <= MAX_BYTES)
    require(type(body["recorded_at_epoch_ms"]) is int and 0 <= body["recorded_at_epoch_ms"] <= 253402300799000)
    require(body["key_versions"] == study["key_versions"] and len(body["key_versions"]) == 2,
            "KEY_POLICY_CHANGED", 409)
    return {**body, "study_id": identifier(installation["study_id"]),
            "participant_code": identifier(installation["participant_code"]),
            "owner_uid": installation["uid"],
            "object_path": f"uploads/{installation['uid']}/{body['snapshot_id']}/approved-event.tar.age"}


def same_registration(existing, requested):
    require(all(existing.get(k) == v for k, v in requested.items()), "SNAPSHOT_CONFLICT", 409)


def token_hash(token):
    require(type(token) is str and re.fullmatch(r"[A-Za-z0-9_-]{43}", token))
    return hashlib.sha256(token.encode()).hexdigest()


def valid_enrolment(row, installation_id, now):
    require(row and row.get("enabled") is True and row.get("expires_at", 0) > now,
            "ENROLMENT_UNAVAILABLE", 403)
    require(row.get("installation_id") in (None, installation_id), "ENROLMENT_USED", 403)


def public_row(row):
    fields = REGISTER_FIELDS | {"study_id", "participant_code", "status", "registered_at",
                               "received_at", "gb10_path", "error_code", "archived"}
    return {k: row[k] for k in fields if k in row}
