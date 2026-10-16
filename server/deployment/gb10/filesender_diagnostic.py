"""Explicit read-only FileSender diagnostics; never print raw bodies or signed URLs."""
import argparse
import json
import hashlib
import hmac
import urllib.error
import urllib.parse
import urllib.request
import uuid
import time
from email.utils import parsedate_to_datetime

from ecocapture_gateway.errors import GatewayError, require
from ecocapture_gateway.filesender_adapter import API, FileSenderClient, signed_request, identifier


def owner_request_at(client, timestamp):
    # Diagnostic only: identical canonical signature, explicit comparison time.
    arguments = {"remote_user": client.username, "timestamp": str(round(timestamp))}
    canonical = "get&" + API.removeprefix("https://") + "/transfer/@me?" + "&".join(
        f"{key}={arguments[key]}" for key in sorted(arguments))
    arguments["signature"] = hmac.new(client.secret.encode("ascii"), canonical.encode("ascii"), hashlib.sha1).hexdigest()
    query = "&".join(urllib.parse.quote(key) + "=" + urllib.parse.quote(arguments[key]) for key in sorted(arguments))
    return urllib.request.Request(API + "/transfer/@me?" + query, method="GET",
                                  headers={"Accept": "application/json", "Content-Type": "application/json"})


def compare_clock(client):
    public = urllib.request.Request(API + "/info", headers={"Accept": "application/json"})
    with client.opener.open(public, timeout=120) as response:
        require(response.geturl() == public.full_url, 502, "FILESENDER_REDIRECT_REJECTED")
        try:
            server_time = parsedate_to_datetime(response.headers.get("Date")).timestamp()
        except (ValueError, TypeError, OverflowError):
            raise GatewayError(502, "SERVER_DATE_UNAVAILABLE") from None
        reference = time.monotonic()
    offset = round(server_time - time.time(), 1)
    local = auth_check(client)
    corrected = auth_check(client, owner_request_at(client, server_time + time.monotonic() - reference))
    return {"status": "FILESENDER_CLOCK_COMPARISON", "server_clock_offset_seconds": offset,
            "host_timestamp_http_status": local["http_status"],
            "server_timestamp_http_status": corrected["http_status"],
            "transfer_mutations": 0, "host_clock_changed": False}


def auth_check(client, request=None):
    if request is None:
        request = signed_request(client.username, client.secret, "get", "/transfer/@me")
    started = time.time()
    result = {"status": "FILESENDER_AUTH_DIAGNOSTIC", "endpoint": "/transfer/@me",
              "transfer_mutations": 0}
    try:
        response = client.opener.open(request, timeout=120)
    except urllib.error.HTTPError as error:
        response = error
    try:
        result["http_status"] = response.code
        date = response.headers.get("Date")
        result["server_clock_offset_seconds"] = None
        if date:
            try:
                result["server_clock_offset_seconds"] = round(
                    parsedate_to_datetime(date).timestamp() - (started + time.time()) / 2, 1)
            except (ValueError, TypeError, OverflowError):
                pass
        if response.code >= 400:
            body = response.read(65537)
            result["error_response_is_json"] = False
            if len(body) <= 65536:
                try:
                    parsed = json.loads(body)
                    result["error_response_is_json"] = True
                    message = parsed.get("message") if type(parsed) is dict else None
                    # Emit only fixed categories; never echo any remote string.
                    if type(message) is str:
                        result["error_category"] = next((category for token, category in (
                            ("timestamp", "TIMESTAMP"), ("signature", "SIGNATURE"),
                            ("auth", "AUTHENTICATION"), ("parameter", "PARAMETER"),
                            ("configuration", "CONFIGURATION")) if token in message.lower()), "OTHER")
                except (ValueError, UnicodeError):
                    pass
    finally:
        response.close()
    return result


def find_transfer(client, transfer_id):
    transfer_id = str(uuid.UUID(transfer_id))
    transfers = client.call("get", "/transfer/@me")
    require(type(transfers) is list and all(type(item) is dict for item in transfers),
            502, "OWNER_LIST_FORMAT_UNEXPECTED")
    matches = []
    for item in transfers:
        if item.get("subject") != "EcoCapture encrypted synthetic " + transfer_id:
            continue
        remote_id = identifier(item.get("id"))
        remote = client.call("get", f"/transfer/{remote_id}")
        require(type(remote) is dict and identifier(remote.get("id")) == remote_id,
                502, "REMOTE_TRANSFER_MISMATCH")
        objects = remote.get("files")
        recipients = remote.get("recipients")
        matches.append({"filesender_transfer_id": remote_id,
            "subject_matches": remote.get("subject") == "EcoCapture encrypted synthetic " + transfer_id,
            "file_matches": type(objects) is list and len(objects) == 1 and type(objects[0]) is dict and
                objects[0].get("name") == "approved-event.tar.age" and str(objects[0].get("size")) == "143592",
            "recipient_matches": type(recipients) is list and len(recipients) == 1 and type(recipients[0]) is dict and
                recipients[0].get("email") == "raymond.chia@uts.edu.au",
            "available": remote.get("status") == "available"})
    return {"status": "FILESENDER_OWNER_SEARCH_RESULT", "gateway_transfer_id": transfer_id,
            "listed_transfer_count": len(transfers),
            "entries_without_subject": sum(type(item.get("subject")) is not str for item in transfers),
            "matches": matches, "transfer_mutations": 0,
            "absence_is_not_authorization_to_recreate": True}


def diagnose(client, signed=True):
    request = (signed_request(client.username, client.secret, "get", "/info") if signed else
               urllib.request.Request(API + "/info", headers={"Accept": "application/json"}))
    try:
        with client.opener.open(request, timeout=120) as response:
            # No body is needed to identify the failing HTTP status.
            return {"status": "FILESENDER_INFO_HTTP_RESULT", "http_status": response.status,
                    "method": "GET", "endpoint": "/info", "signed": signed, "transfer_creation_attempted": False}
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        return {"status": "FILESENDER_INFO_HTTP_RESULT", "http_status": status,
                "method": "GET", "endpoint": "/info", "signed": signed, "transfer_creation_attempted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--filesender-config", required=True)
    parser.add_argument("--authorize-read-only", action="store_true", required=True)
    parser.add_argument("--find-transfer", type=uuid.UUID)
    parser.add_argument("--auth-check", action="store_true")
    parser.add_argument("--compare-clock", action="store_true")
    args = parser.parse_args()
    try:
        client = FileSenderClient(args.filesender_config, live_authorized=args.authorize_read_only)
        if args.compare_clock:
            result = compare_clock(client)
        elif args.auth_check:
            result = auth_check(client)
        elif args.find_transfer:
            result = find_transfer(client, str(args.find_transfer))
        else:
            result = {"status": "FILESENDER_INFO_DIAGNOSTIC", "checks":
                      [diagnose(client, signed=False), diagnose(client, signed=True)]}
    except GatewayError as error:
        result = {"status": "FILESENDER_INFO_DIAGNOSTIC_FAILED", "code": error.code}
    except (urllib.error.URLError, OSError, TimeoutError):
        result = {"status": "FILESENDER_INFO_DIAGNOSTIC_FAILED", "code": "NETWORK_OR_TLS_FAILURE"}
    except Exception:
        # Never expose exception text: urllib exceptions can contain signed URLs.
        result = {"status": "FILESENDER_INFO_DIAGNOSTIC_FAILED", "code": "DIAGNOSTIC_FAILED"}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
