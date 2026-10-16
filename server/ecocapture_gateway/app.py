import hashlib
import os

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

from .contract import canonical_uuid, creation, parse_json, sha256
from .errors import GatewayError, require
from .security import SecurityBoundary
from .store import Store
from . import manifest_validator


def verify_snapshot(directory, expected):
    result = manifest_validator.verify(directory, expected["manifest.json"]["sha"],
                                      expected["event.json"]["sha"], expected["video.mp4"]["sha"])
    for name, row in expected.items():
        require(result["files"][name]["size_bytes"] == row["size"], 409, "OBJECT_SIZE_MISMATCH")
    return result, manifest_validator.load_json(directory / "event.json")


def create_app(config, store=None):
    store = store or Store(config)
    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)

    @api.exception_handler(GatewayError)
    async def safe_error(request, error):
        return JSONResponse({"error": {"code": error.code, **error.safe_fields}}, error.status)

    @api.exception_handler(RequestValidationError)
    async def invalid(request, error):
        return JSONResponse({"error": {"code": "INVALID_REQUEST"}}, 400)

    @api.exception_handler(HTTPException)
    async def http_error(request, error):
        return JSONResponse({"error": {"code": "NOT_FOUND" if error.status_code == 404 else "INVALID_REQUEST"}}, error.status_code)

    @api.exception_handler(Exception)
    async def unavailable(request, error):
        return JSONResponse({"error": {"code": "SERVICE_UNAVAILABLE"}}, 503)

    async def empty_body(request):
        require(not await request.body())

    @api.get("/v1/health")
    async def health():
        return {"status": "ok", "transport": "mock"}

    @api.post("/v1/transfers", status_code=201)
    async def create(request: Request):
        require(request.headers.get("content-type", "").split(";")[0] == "application/json", 415, "JSON_REQUIRED")
        payload, fingerprint = creation(parse_json(await request.body()), request.headers.get("idempotency-key"), config)
        return await run_in_threadpool(store.create, request.state.owner, payload, fingerprint)

    @api.get("/v1/transfers/{transfer_id}")
    async def status(transfer_id: str, request: Request):
        return await run_in_threadpool(store.status, request.state.owner, transfer_id)

    @api.put("/v1/transfers/{transfer_id}/objects/{name}/chunks/{offset}")
    async def chunk(transfer_id: str, name: str, offset: str, request: Request):
        canonical_uuid(transfer_id)
        require(offset.isdecimal() and len(offset) <= 12)
        offset = int(offset)
        require(request.headers.get("content-type") == "application/octet-stream", 415, "BINARY_REQUIRED")
        expected = sha256(request.headers.get("x-chunk-sha256"))
        temporary = await run_in_threadpool(store.begin_chunk, request.state.owner, transfer_id, name)
        try:
            size = 0
            hash_state = hashlib.sha256()
            with temporary.open("xb") as file:
                async for block in request.stream():
                    size += len(block)
                    require(size <= config.max_chunk_bytes, 413, "BODY_LIMIT")
                    hash_state.update(block)
                    file.write(block)
                file.flush(); os.fsync(file.fileno())
            require(size > 0 and hash_state.hexdigest() == expected, 400, "CHUNK_HASH_MISMATCH")
            return await run_in_threadpool(store.commit_chunk, request.state.owner, transfer_id, name,
                                          offset, temporary, size, expected)
        finally:
            temporary.unlink(missing_ok=True)

    @api.post("/v1/transfers/{transfer_id}/complete")
    async def complete(transfer_id: str, request: Request):
        await empty_body(request)
        return await run_in_threadpool(store.complete, request.state.owner, transfer_id, verify_snapshot)

    @api.post("/v1/transfers/{transfer_id}/cancel")
    async def cancel(transfer_id: str, request: Request):
        await empty_body(request)
        return await run_in_threadpool(store.cancel, request.state.owner, transfer_id)

    @api.get("/v1/transfers/{transfer_id}/receipt")
    async def receipt(transfer_id: str, request: Request):
        return await run_in_threadpool(store.receipt, request.state.owner, transfer_id)

    boundary = SecurityBoundary(api, store)
    boundary.store = store
    return boundary
