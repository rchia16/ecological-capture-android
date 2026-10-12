"""Read-only FileSender signed-auth check with explicit external INI provisioning.

Does not import the supplied CLI, create transfers, upload files, send email,
delete transfers, or print credential/account/transfer contents.
"""
import argparse
import configparser
import hashlib
import hmac
import json
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request


API = "https://filesender.aarnet.edu.au/rest.php"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024


class ProbeFailure(Exception):
    """Carries a fixed non-secret code only."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, target):
        raise ProbeFailure("REDIRECT_REJECTED")


def load_credentials(path):
    repository = Path(__file__).resolve().parents[2]
    resolved = path.resolve()
    if resolved.is_relative_to(repository):
        raise ProbeFailure("CREDENTIAL_MUST_BE_OUTSIDE_REPOSITORY")
    config = configparser.ConfigParser(interpolation=None)
    try:
        with resolved.open(encoding="utf-8-sig") as stream:
            config.read_file(stream)
        # Exact fixed host; never send credentials to an INI-selected destination.
        if config.get("system", "base_url").rstrip("/") != API:
            raise ProbeFailure("UNAPPROVED_API_HOST")
        username = config.get("user", "username")
        secret = config.get("user", "apikey")
        if not username or not secret or any(ord(char) < 32 for char in username + secret):
            raise ProbeFailure("INVALID_CREDENTIAL_CONFIGURATION")
        username.encode("ascii")
        secret.encode("ascii")
    except (OSError, configparser.Error, UnicodeError):
        raise ProbeFailure("CREDENTIAL_CONFIGURATION_UNAVAILABLE") from None
    return username, secret


def signed_request(username, secret, timestamp):
    # Same lower-case method, sorted raw arguments, HMAC-SHA1 and encoded query
    # as the supplied reference. There is no body on this read-only request.
    path = "/transfer/@me"
    arguments = {"remote_user": username, "timestamp": str(timestamp)}
    raw_query = "&".join(f"{key}={arguments[key]}" for key in sorted(arguments))
    prepared = f"get&{API.removeprefix('https://')}{path}?{raw_query}".encode("ascii")
    arguments["signature"] = hmac.new(secret.encode("ascii"), prepared, hashlib.sha1).hexdigest()
    query = "&".join(urllib.parse.quote(key) + "=" + urllib.parse.quote(arguments[key])
                     for key in sorted(arguments))
    return urllib.request.Request(API + path + "?" + query,
                                  headers={"Accept": "application/json"}, method="GET")


def fetch_json(opener, request):
    try:
        with opener.open(request, timeout=30) as response:
            # Also check the final destination to protect against unusual handlers.
            if response.geturl() != request.full_url:
                raise ProbeFailure("REDIRECT_REJECTED")
            if response.status != 200:
                raise ProbeFailure("UNEXPECTED_HTTP_STATUS")
            data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES:
                raise ProbeFailure("RESPONSE_TOO_LARGE")
        return json.loads(data)
    except urllib.error.HTTPError as error:
        status = error.code
        # Close explicitly: deferred HTTPError cleanup can emit diagnostics that
        # include the signed URL/remote error, even when our CLI redacts failures.
        error.close()
        if status in (401, 403):
            raise ProbeFailure("AUTHENTICATION_REJECTED") from None
        if 300 <= status < 400:
            raise ProbeFailure("REDIRECT_REJECTED") from None
        raise ProbeFailure("REMOTE_HTTP_ERROR") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ProbeFailure("NETWORK_OR_TLS_FAILURE") from None
    except (ValueError, UnicodeError):
        raise ProbeFailure("INVALID_JSON_RESPONSE") from None


def probe(path, opener=None):
    username, secret = load_credentials(path)
    if opener is None:
        opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    info = fetch_json(opener, urllib.request.Request(API + "/info", headers={"Accept": "application/json"}))
    chunk_size = info.get("upload_chunk_size") if isinstance(info, dict) else None
    if type(chunk_size) is not int or not 0 < chunk_size <= 64 * 1024 * 1024:
        raise ProbeFailure("INVALID_CHUNK_CONFIGURATION")
    transfers = fetch_json(opener, signed_request(username, secret, round(time.time())))
    if not isinstance(transfers, list) or any(not isinstance(item, dict) for item in transfers):
        raise ProbeFailure("INVALID_AUTH_RESPONSE")
    # Do not emit account identity, transfer IDs, recipients, tokens or raw response.
    return {"schema": "ecocapture.checkpoint1.auth.v1", "status": "SIGNED_READ_SUCCEEDED",
            "upload_chunk_size": chunk_size, "transfer_mutations": 0, "uploads": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path,
                        help="Explicit separately provisioned INI outside the repository")
    args = parser.parse_args()
    try:
        result = probe(args.config)
    except ProbeFailure as error:
        print(json.dumps({"status": "FAILED", "safe_error_code": str(error)}))
        return 1
    except Exception:
        # Never let urllib/INI/JSON exceptions expose URL or file contents.
        print(json.dumps({"status": "FAILED", "safe_error_code": "UNEXPECTED_PROBE_FAILURE"}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
