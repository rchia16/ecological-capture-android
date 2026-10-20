import asyncio
from collections import OrderedDict
import hashlib
import threading
import time
from urllib.parse import urlsplit

from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse
from .errors import GatewayError, require


class SecurityBoundary:
    """Outer ASGI boundary: never trust forwarded headers directly or log request/exception data."""
    def __init__(self, app, store, anonymous_paths=None):
        self.app = app
        self.store = store
        self.config = store.config
        self.lock = threading.Lock()
        self.inflight = 0
        self.peers = OrderedDict()
        self.anonymous_paths = frozenset(anonymous_paths if anonymous_paths is not None else {("GET", "/v1/health")})

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        started = False
        counted = False

        async def safe_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                message["headers"] += [(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff"),
                                        (b"strict-transport-security", b"max-age=31536000")]
            await send(message)

        try:
            require(scope.get("scheme") == "https", 400, "HTTPS_REQUIRED")
            headers = scope.get("headers", [])
            def header(name, required=False):
                found = [v for k, v in headers if k.lower() == name]
                require(len(found) <= 1 and (not required or len(found) == 1))
                try:
                    return found[0].decode("ascii") if found else ""
                except UnicodeError:
                    raise GatewayError(400, "INVALID_HEADER") from None
            host = header(b"host", True)
            require(not any(c in host for c in "/\\@"), 400, "INVALID_HOST")
            require(urlsplit("https://" + host).hostname in self.config.allowed_hosts, 400, "INVALID_HOST")
            require(not scope.get("query_string"), 400, "QUERY_NOT_ALLOWED")
            with self.lock:
                now = int(time.monotonic() // 60)
                key = hashlib.sha256(str(scope.get("client", ("unknown",))[0]).encode()).hexdigest()
                for peer in list(self.peers):
                    if self.peers[peer][0] != now:
                        del self.peers[peer]
                require(key in self.peers or len(self.peers) < 4096, 429, "RATE_LIMIT")
                window, count = self.peers.get(key, (now, 0))
                require(count < self.config.requests_per_minute * 4, 429, "RATE_LIMIT")
                self.peers[key] = (window, count + 1)
                require(self.inflight < self.config.max_inflight, 503, "BACKPRESSURE")
                self.inflight += 1
                counted = True
            chunk = scope["method"] == "PUT" and "/chunks/" in scope["path"]
            limit = self.config.max_chunk_bytes if chunk else self.config.max_json_bytes
            length = header(b"content-length")
            if length:
                require(length.isdecimal() and len(length) <= 12)
                require(int(length) <= limit, 413, "BODY_LIMIT")
            state = scope.setdefault("state", {})
            if (scope["method"], scope["path"]) not in self.anonymous_paths:
                owner = await run_in_threadpool(self.store.authenticate, header(b"authorization"))
                await run_in_threadpool(self.store.rate, owner)
                state["owner"] = owner
            size = 0
            async def limited_receive():
                nonlocal size
                message = await receive()
                if message["type"] == "http.request":
                    size += len(message.get("body", b""))
                    require(size <= limit, 413, "BODY_LIMIT")
                return message
            await self.app(scope, limited_receive, safe_send)
        except GatewayError as error:
            if not started:
                await JSONResponse({"error": {"code": error.code, **error.safe_fields}}, error.status,
                                   headers={"Retry-After": "60"} if error.status in (429, 503) else None)(scope, receive, safe_send)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Swallow after redacted framework response too: Uvicorn must not print exception data.
            if not started:
                await JSONResponse({"error": {"code": "SERVICE_UNAVAILABLE"}}, 503)(scope, receive, safe_send)
        finally:
            if counted:
                with self.lock:
                    self.inflight -= 1
