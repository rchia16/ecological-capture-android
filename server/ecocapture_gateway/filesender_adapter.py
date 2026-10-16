"""Reviewed fixed-origin FileSender protocol, based on Checkpoint 1. No CLI imports/logging."""
import configparser
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from email.utils import parsedate_to_datetime

from .errors import GatewayError, require

API = "https://filesender.aarnet.edu.au/rest.php"
MAX_RESPONSE = 4 * 1024 * 1024
MAX_CLOCK_OFFSET = 300
MAX_CLOCK_SAMPLE_SECONDS = 5
CLOCK_SAMPLE_TTL = 60


def identifier(value):
    require(type(value) in (str, int) and re.fullmatch(r"[1-9][0-9]{0,18}", str(value)) is not None,
            502, "REMOTE_IDENTIFIER_INVALID")
    return int(value)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise GatewayError(502, "FILESENDER_REDIRECT_REJECTED")


def signed_request(username, secret, method, path, parameters=None, payload=None, raw=None, *, timestamp=None):
    require(method in ("get", "post", "put"), 400, "REMOTE_METHOD_INVALID")
    require(re.fullmatch(r"/(info|transfer(?:/@me|/[1-9][0-9]*)?|file/[1-9][0-9]*(?:/chunk/[0-9]+)?)", path) is not None,
            400, "REMOTE_PATH_INVALID")
    require(payload is None or raw is None)
    arguments = dict(parameters or {})
    arguments.update(remote_user=username, timestamp=str(round(time.time() if timestamp is None else timestamp)))
    body = json.dumps(payload, separators=(",", ":")).encode("ascii") if payload is not None else raw
    canonical = f"{method}&{API.removeprefix('https://')}{path}?" + "&".join(
        f"{key}={arguments[key]}" for key in sorted(arguments))
    signed = canonical.encode("ascii") + (b"&" + body if body is not None else b"")
    arguments["signature"] = hmac.new(secret.encode("ascii"), signed, hashlib.sha1).hexdigest()
    query = "&".join(urllib.parse.quote(key) + "=" + urllib.parse.quote(arguments[key]) for key in sorted(arguments))
    return urllib.request.Request(API + path + "?" + query, data=body, method=method.upper(),
        headers={"Accept": "application/json", "Content-Type": "application/octet-stream" if raw is not None else "application/json"})


class FileSenderClient:
    def __init__(self, config, *, live_authorized=False, use_server_time=False):
        require(live_authorized is True, 403, "LIVE_CREDENTIAL_USE_NOT_AUTHORIZED")
        config = Path(config)
        require(not config.is_symlink() and config.is_file(), 400, "CREDENTIAL_FILE_UNAVAILABLE")
        require(not config.resolve().is_relative_to(Path(__file__).resolve().parents[2]),
                400, "CREDENTIAL_MUST_BE_EXTERNAL")
        if os.name != "nt":
            require(config.stat().st_uid == os.getuid() and not config.stat().st_mode & 0o077,
                    400, "CREDENTIAL_PERMISSIONS_UNSAFE")
        parsed = configparser.ConfigParser(interpolation=None)
        try:
            with config.open(encoding="utf-8-sig") as stream: parsed.read_file(stream)
            require(parsed.get("system", "base_url").rstrip("/") == API, 400, "UNAPPROVED_API_HOST")
            self.username = parsed.get("user", "username")
            self.secret = parsed.get("user", "apikey")
            require(self.username and self.secret and not any(ord(c) < 32 for c in self.username + self.secret),
                    400, "INVALID_CREDENTIAL_CONFIGURATION")
            self.username.encode("ascii"); self.secret.encode("ascii")
        except (OSError, configparser.Error, UnicodeError):
            raise GatewayError(400, "CREDENTIAL_CONFIGURATION_UNAVAILABLE") from None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        self.use_server_time = use_server_time
        self._clock_sample = None

    def now(self):
        if not self.use_server_time:
            return time.time()
        current = time.monotonic()
        if self._clock_sample is not None:
            epoch, reference = self._clock_sample
            if 0 <= current - reference < CLOCK_SAMPLE_TTL:
                return epoch + current - reference
        # No OS clock changes or process-wide time overrides. No signed retry.
        previous_sample = self._clock_sample
        self._clock_sample = None
        request = urllib.request.Request(API + "/info", headers={
            "Accept": "application/json", "Cache-Control": "no-cache, no-store", "Pragma": "no-cache"})
        started = time.monotonic()
        try:
            with self.opener.open(request, timeout=MAX_CLOCK_SAMPLE_SECONDS) as response:
                finished = time.monotonic()
                require(response.geturl() == request.full_url, 502, "FILESENDER_REDIRECT_REJECTED")
                require(response.status == 200, 502, "FILESENDER_CLOCK_HTTP_STATUS")
                require(0 <= finished - started <= MAX_CLOCK_SAMPLE_SECONDS, 502, "FILESENDER_CLOCK_SAMPLE_TOO_SLOW")
                require(response.headers.get("Age", "0").strip() == "0", 502, "FILESENDER_CLOCK_CACHED_RESPONSE")
                date = parsedate_to_datetime(response.headers.get("Date"))
                require(date.tzinfo is not None, 502, "FILESENDER_CLOCK_DATE_INVALID")
                epoch = date.timestamp()
                require(abs(epoch - time.time()) <= MAX_CLOCK_OFFSET, 502, "FILESENDER_CLOCK_OFFSET_TOO_LARGE")
                if previous_sample is not None:
                    previous_epoch, previous_reference = previous_sample
                    require(abs(epoch - (previous_epoch + finished - previous_reference)) <= MAX_CLOCK_SAMPLE_SECONDS,
                            502, "FILESENDER_CLOCK_SAMPLE_UNSTABLE")
            # Use monotonic elapsed time so host wall-clock adjustments don't
            # change this process-local signing clock during the sample TTL.
            self._clock_sample = (epoch, finished)
            return epoch + time.monotonic() - finished
        except urllib.error.HTTPError as error:
            error.close()
            raise GatewayError(503, "FILESENDER_CLOCK_SYNC_FAILED") from None
        except (urllib.error.URLError, OSError, TimeoutError):
            raise GatewayError(503, "FILESENDER_CLOCK_SYNC_FAILED") from None
        except (ValueError, TypeError, OverflowError):
            raise GatewayError(502, "FILESENDER_CLOCK_DATE_INVALID") from None

    def call(self, method, path, parameters=None, payload=None, raw=None):
        if method == "get" and path == "/info":
            require(not parameters and payload is None and raw is None, 400, "REMOTE_INFO_ARGUMENTS_INVALID")
            # Public capability endpoint: AARNet returns HTTP 500 when signed.
            outgoing = urllib.request.Request(API + "/info", headers={"Accept": "application/json"})
        else:
            outgoing = signed_request(self.username, self.secret, method, path, parameters, payload, raw,
                                      timestamp=self.now())
        try:
            with self.opener.open(outgoing, timeout=120) as response:
                require(response.geturl() == outgoing.full_url, 502, "FILESENDER_REDIRECT_REJECTED")
                require(response.status == (201 if method == "post" else 200), 502, "FILESENDER_HTTP_STATUS")
                body = response.read(MAX_RESPONSE + 1)
                require(len(body) <= MAX_RESPONSE, 502, "FILESENDER_RESPONSE_LIMIT")
            result = json.loads(body)
            require(result is not False and result is not None, 502, "FILESENDER_NOT_ACKNOWLEDGED")
            return result
        except urllib.error.HTTPError as error:
            status = error.code; error.close()
            code = "FILESENDER_AUTH_REJECTED" if status in (401, 403) else "FILESENDER_RATE_LIMIT" if status == 429 else "FILESENDER_REMOTE_UNAVAILABLE" if status >= 500 else "FILESENDER_HTTP_ERROR"
            raise GatewayError(503 if status == 429 or status >= 500 else 502, code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise GatewayError(503, "FILESENDER_NETWORK_OR_TLS_FAILURE") from None
        except (ValueError, UnicodeError):
            raise GatewayError(502, "FILESENDER_INVALID_RESPONSE") from None
