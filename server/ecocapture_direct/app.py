"""Public v2 upload API and physically separate, tailnet-only researcher application."""
import asyncio
from collections import OrderedDict
import hashlib
import io
import os
from pathlib import Path
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid
import zipfile

from fastapi import FastAPI, Request
from starlette.concurrency import run_in_threadpool
from starlette.responses import FileResponse, JSONResponse, Response, StreamingResponse
from ecocapture_gateway.contract import canonical_uuid, parse_json, sha256
from ecocapture_gateway.errors import GatewayError, require
from ecocapture_gateway.security import SecurityBoundary
from ecocapture_gateway.manifest_validator import verify


def errors(api):
    @api.exception_handler(GatewayError)
    async def safe(request, error):
        return JSONResponse({"error": {"code": error.code, **error.safe_fields}}, error.status)
    @api.exception_handler(Exception)
    async def unavailable(request, error):
        return JSONResponse({"error": {"code": "SERVICE_UNAVAILABLE"}}, 503)


def application():
    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)
    errors(api)
    return api


def public_app(store):
    api = application()

    @api.get("/v2/health")
    async def health(): return {"status": "ok", "transport": "direct-gb10", "archived": False}

    @api.post("/v2/enrol")
    async def enrol(request: Request):
        require(request.headers.get("content-type", "").split(";")[0] == "application/json", 415, "JSON_REQUIRED")
        return await run_in_threadpool(store.enrol, parse_json(await request.body()))

    @api.get("/v2/profile")
    async def profile(request: Request): return await run_in_threadpool(store.profile, request.state.owner)

    @api.post("/v2/packages")
    async def register(request: Request):
        require(request.headers.get("content-type", "").split(";")[0] == "application/json", 415, "JSON_REQUIRED")
        return await run_in_threadpool(store.register, request.state.owner, parse_json(await request.body()))

    @api.get("/v2/packages/{snapshot}")
    async def status(snapshot: str, request: Request):
        return await run_in_threadpool(store.status, request.state.owner, snapshot)

    @api.put("/v2/packages/{snapshot}/chunks/{offset}")
    async def chunk(snapshot: str, offset: str, request: Request):
        canonical_uuid(snapshot)
        require(offset.isdecimal() and len(offset) <= 12)
        require(request.headers.get("content-type") == "application/octet-stream", 415, "BINARY_REQUIRED")
        expected = sha256(request.headers.get("x-chunk-sha256"))
        temporary = store.incoming / (str(uuid.uuid4()) + ".chunk")
        try:
            total = 0; digest = hashlib.sha256()
            with temporary.open("xb") as output:
                async for block in request.stream():
                    total += len(block)
                    require(total <= store.config.max_chunk_bytes, 413, "BODY_LIMIT")
                    output.write(block); digest.update(block)
                output.flush(); os.fsync(output.fileno())
            require(total > 0 and digest.hexdigest() == expected, 400, "CHUNK_HASH_MISMATCH")
            return await run_in_threadpool(store.chunk, request.state.owner, snapshot, int(offset), temporary, expected)
        finally: temporary.unlink(missing_ok=True)

    @api.post("/v2/packages/{snapshot}/complete")
    async def complete(snapshot: str, request: Request):
        require(not await request.body())
        return await run_in_threadpool(store.complete, request.state.owner, snapshot)

    @api.post("/v2/packages/{snapshot}/cancel")
    async def cancel(snapshot: str, request: Request):
        require(not await request.body())
        return await run_in_threadpool(store.cancel, request.state.owner, snapshot)

    return SecurityBoundary(api, store, {("GET", "/v2/health"), ("POST", "/v2/enrol")})


class ResearcherBoundary:
    """Only a loopback proxy carrying a Serve identity may reach this listener.

    This application is never mounted on the public upload listener. The deployment
    must keep its proxy port private; local privileged users remain trusted host operators.
    """
    def __init__(self, app, store):
        self.app, self.store = app, store
        self.lock = threading.Lock(); self.inflight = 0
        self.rates = OrderedDict()

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http": return await self.app(scope, receive, send)
        started = False; counted = False
        async def safe_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                message["headers"] += [(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff"),
                    (b"referrer-policy", b"no-referrer"), (b"x-frame-options", b"DENY"),
                    (b"content-security-policy", b"default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")]
            await send(message)
        try:
            require(scope.get("client", (None,))[0] in ("127.0.0.1", "::1"), 403, "PRIVATE_PROXY_REQUIRED")
            require(scope.get("scheme") == "https", 400, "HTTPS_REQUIRED")
            def header(name):
                values = [v for k, v in scope.get("headers", []) if k.lower() == name]
                require(len(values) <= 1, 400, "DUPLICATE_HEADER")
                try: return values[0].decode("ascii") if values else ""
                except UnicodeError: raise GatewayError(400, "INVALID_HEADER") from None
            host = header(b"host")
            require(host and not any(c in host for c in "/\\@") and urlsplit("https://" + host).hostname in self.store.config.allowed_hosts, 400, "INVALID_HOST")
            login = self.store.researcher(header(b"tailscale-user-login"))
            with self.lock:
                minute = int(time.monotonic() // 60)
                for key in list(self.rates):
                    if self.rates[key][0] != minute: del self.rates[key]
                count = self.rates.get(login, (minute, 0))[1]
                require(count < self.store.config.requests_per_minute * 4, 429, "RATE_LIMIT")
                require(self.inflight < self.store.config.max_inflight, 503, "BACKPRESSURE")
                self.rates[login] = (minute, count + 1); self.inflight += 1; counted = True
            scope.setdefault("state", {})["researcher"] = login
            if scope["method"] not in ("GET", "HEAD"):
                require(header(b"origin") == "https://" + host, 403, "SAME_ORIGIN_REQUIRED")
                require(header(b"content-type").split(";")[0] == "application/json", 415, "JSON_REQUIRED")
            length = header(b"content-length")
            require(not length or length.isdecimal() and len(length) <= 12 and int(length) <= self.store.config.max_json_bytes, 413, "BODY_LIMIT")
            size = 0
            async def bounded():
                nonlocal size
                message = await receive()
                if message["type"] == "http.request":
                    size += len(message.get("body", b"")); require(size <= self.store.config.max_json_bytes, 413, "BODY_LIMIT")
                return message
            await self.app(scope, bounded, safe_send)
        except GatewayError as error:
            if not started: await JSONResponse({"error": {"code": error.code}}, error.status)(scope, receive, safe_send)
        except asyncio.CancelledError: raise
        except Exception:
            if not started: await JSONResponse({"error": {"code": "SERVICE_UNAVAILABLE"}}, 503)(scope, receive, safe_send)
        finally:
            if counted:
                with self.lock: self.inflight -= 1


def verified_directory(store, snapshot):
    canonical_uuid(snapshot)
    with store.connect() as db:
        row = db.execute("SELECT * FROM direct_packages WHERE snapshot=?", (snapshot,)).fetchone()
        require(row is not None and row["state"] == "VERIFIED_GB10", 404, "VERIFIED_SNAPSHOT_REQUIRED")
        data = store.describe(row)
    root = store.root / "received"
    directory = root / data["gb10_path"]
    require(directory.resolve().is_relative_to(root.resolve()) and directory.resolve() == directory.absolute(), 500, "UNSAFE_STORAGE")
    verify(directory, data["manifest_sha256"])
    return directory


class ZipSink(io.RawIOBase):
    def __init__(self): self.buffer = bytearray(); self.offset = 0
    def writable(self): return True
    def seekable(self): return False
    def tell(self): return self.offset
    def write(self, value): self.buffer.extend(value); self.offset += len(value); return len(value)
    def take(self):
        value = bytes(self.buffer); self.buffer.clear(); return value


def package_zip(directory):
    # An unseekable ZIP stream keeps memory bounded and creates no extra plaintext ZIP on disk.
    sink = ZipSink()
    with zipfile.ZipFile(sink, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in ("video.mp4", "event.json", "manifest.json"):
            with archive.open(name, "w", force_zip64=True) as output, (directory / name).open("rb") as source:
                while block := source.read(128 * 1024):
                    output.write(block)
                    yield sink.take()
            yield sink.take()
    yield sink.take()


def researcher_app(store):
    api = application()
    web = Path(__file__).parent / "web"

    @api.get("/")
    async def index(): return FileResponse(web / "index.html", media_type="text/html")

    @api.get("/assets/{name}")
    async def asset(name: str):
        require(name in ("app.js", "catalogue.js", "style.css"), 404, "NOT_FOUND")
        return FileResponse(web / name, media_type="text/css" if name.endswith("css") else "text/javascript")

    @api.get("/research/api/me")
    async def me(request: Request): return {"login": request.state.researcher, "study_id": store.direct.study_id}

    @api.get("/research/api/catalogue")
    async def catalogue(request: Request):
        require(len(request.url.query) <= store.config.max_json_bytes and len(request.query_params.multi_items()) == len(request.query_params), 400, "INVALID_FILTER")
        return await run_in_threadpool(store.catalogue, dict(request.query_params))

    @api.put("/research/api/events/{event}/notes")
    async def notes(event: str, request: Request):
        return await run_in_threadpool(store.notes, request.state.researcher, event, parse_json(await request.body()))

    @api.put("/research/api/packages/{snapshot}/review")
    async def review(snapshot: str, request: Request):
        return await run_in_threadpool(store.review, request.state.researcher, snapshot, parse_json(await request.body()))

    @api.get("/research/api/packages/{snapshot}/download")
    async def download(snapshot: str):
        directory = await run_in_threadpool(verified_directory, store, snapshot)
        return StreamingResponse(package_zip(directory), media_type="application/zip",
                                 headers={"Content-Disposition": f'attachment; filename="ecocapture-{snapshot}.zip"'})

    @api.get("/research/api/audit/{target}")
    async def audit(target: str):
        canonical_uuid(target)
        with store.connect() as db:
            rows = db.execute("SELECT actor,changed,kind,revision FROM direct_audit WHERE target=? ORDER BY id DESC LIMIT 100", (target,)).fetchall()
        return {"items": [dict(row) for row in rows]}

    return ResearcherBoundary(api, store)
