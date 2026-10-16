# Phase 3 gateway API v1 - Checkpoint 4 mock transport

Updated 2026-10-14. HTTPS only; no query strings, browser administration, interactive docs, participant profiles, destination selection or FileSender secret input. Installation credentials are issued by local synthetic administration and never embedded in Android. Subject linkage is checked against the installation allowlist; it does not route files to another destination.

## Request contract

All endpoints except health require `Authorization: Bearer <installation-token>`. Missing/invalid tokens return 401; revoked or ineligible installations return 403. Credentials are rechecked within mutation transactions, including after upload streaming. Another owner's transfer returns 404. Duplicate sensitive headers are rejected. UUIDs are canonical lowercase hyphenated strings and SHA-256 values are lowercase 64-character hexadecimal strings.

| Method and path | Behavior |
| --- | --- |
| `GET /v1/health` | HTTPS-only public minimal `{"status":"ok","transport":"mock"}` |
| `POST /v1/transfers` | JSON creation below; returns 201 and authoritative object offsets |
| `GET /v1/transfers/{id}` | Owner-only state, snapshot ID, object sizes/offsets and chunk limit |
| `PUT /v1/transfers/{id}/objects/{name}/chunks/{offset}` | Binary chunk at decimal byte offset; requires `X-Chunk-SHA256` |
| `POST /v1/transfers/{id}/complete` | Empty body; independently verifies all files, schemas and linkage before recording one mock intent |
| `GET /v1/transfers/{id}/receipt` | Owner-only mock receipt after verified completion |
| `POST /v1/transfers/{id}/cancel` | Empty body; stops further chunks and removes local job payload; no remote deletion claim |

Creation requires `Content-Type: application/json` and `Idempotency-Key` equal to the snapshot UUID. Exact body:

```json
{
  "schema": "ecocapture.gateway.transfer.v1",
  "snapshot_id": "<snapshot UUID>",
  "event_id": "<event UUID>",
  "objects": [
    {"name": "video.mp4", "size_bytes": 123, "sha256": "<64 lowercase hex>"},
    {"name": "event.json", "size_bytes": 456, "sha256": "<64 lowercase hex>"},
    {"name": "manifest.json", "size_bytes": 789, "sha256": "<64 lowercase hex>"}
  ]
}
```

Placeholders are descriptive, not runnable data. Exactly these three names are accepted, with no extra keys, duplicate JSON keys, profiles, source paths, recipient email or destination. A repeated identical creation returns the same gateway transfer ID; a changed request or owner using that snapshot ID gets 409. Object order does not affect identity. Manifest SHA-256 comes from authenticated creation; the manifest hashes video and event and does not hash itself.

Chunks require `Content-Type: application/octet-stream`, no JSON wrapper. Default maximum is 4 MiB. Minimum is 64 KiB unless it is the object's final remainder; deliberately smaller test configurations lower the minimum to their chunk limit. Chunks append only at the committed offset. Identical chunk replay returns `{accepted_bytes, replayed:true}`; a conflicting offset returns 409 with authoritative `accepted_bytes`. Hash failures, oversize bodies (including streaming without Content-Length), and rejected writes do not advance the offset. A restart preserves SQLite offsets and discards any uncommitted file tail on the next write; a truncated committed file fails closed.

Completion independently rehashes all three files, checks lengths and the packaged scientific schemas, verifies event/snapshot linkage and compares `study_participant_code` with the installation allowlist. The default allowlist value is null. Valid completion atomically records a unique mock intent and changes `RECEIVING` to `MOCK_FORWARDED`; repeated completion returns the same receipt. Receipt fields are schema, gateway transfer ID, snapshot ID, status `MOCK_FORWARDED`, `mock:true`, manifest SHA-256 and `archived:false`. There are no real FileSender IDs or download links. Cancellation yields `CANCELLED`; explicit cleanup can change expired incomplete/cancelled jobs to `PURGED`. Cancellation can retry a failed purge, and storage reservation is released only after successful removal.

## Bounds and controlled failure

| Default limit | Value |
| --- | --- |
| JSON request / chunk | 32 KiB / 4 MiB |
| Video / event / manifest | 256 MiB / 2 MiB / 64 KiB |
| Reserved payload per installation / overall | 512 MiB / 1 GiB |
| Disk safety margin | 64 MiB plus outstanding reservations at creation |
| Active jobs per installation / overall | 4 / 16 |
| Stored transfer / chunk records | 10,000 / 50,000 |
| Requests per installation | 120 per minute; health/unauthenticated peer traffic also bounded |
| Concurrent requests | 16 |

Limits are configurable positive integers, with hard request-size configuration caps. SQLite WAL and synchronous FULL persist operational metadata; one worker is the supported development deployment. Large completion verification holds the write transaction and can cause other requests to receive retryable service-unavailable responses. Evaluate PostgreSQL, operational metrics and workload limits before increasing concurrency.

Error responses contain only `{error:{code,...safe_fields}}`; no echoed request values, traceback, token, source path or participant text. Principal statuses: 400 malformed/HTTPS/hash/chunk-size failures; 401 authentication; 403 disabled/linkage; 404 unknown/other-owner; 409 offset/idempotency/incomplete/verification/state conflicts; 413 size/quota; 415 media type; 429 rate/active-job limits; 503 disk/metadata capacity/backpressure/service unavailable. Read the authoritative offset after interruption; retrying 409 blindly is incorrect. TLS trust failures are fatal, never downgraded to HTTP. The credential-free `ecocapture_gateway.probe` treats offline/network/5xx as retryable and rejects untrusted certificates and unexpected responses.

The app does not yet call this contract. Checkpoints 6/7 will wire Android networking and resumable background work after gateway hosting/security acceptance and the subsequent forwarding checkpoint.

## Checkpoint 5 explicit operator forwarding extension

Default HTTP completion still produces the mock receipt and never calls FileSender automatically. A separately provisioned `synthetic-checkpoint5-v1` installation AND exact generated three-file fixture hashes allow an operator to prepare a forwarding job. Forward preparation sets transfer state FORWARDING_PENDING; receipt/repeated complete return 409 until acknowledged remote completion. After actual completion, GET receipt and repeated complete return `ecocapture.gateway.filesender-receipt.v1`, status FILESENDER_COMPLETE, mock false, remote ID/expiry, archive/cipher/manifest hashes, age version/key-version and plaintext removal flag, researcher_verified false and archived false. No remote keys, signed URLs or download links appear. Local artifact removal does not invalidate these durable receipt facts.

Forwarding uses an additive forwarding_jobs table with bounded storage reservations; existing jobs/snapshots are unchanged and never queued on upgrade. Once a forwarding row exists, HTTP cancel returns 409 FORWARDING_CANCEL_REQUIRES_OPERATOR, so it cannot race with archive creation or an external request. The explicit discard command handles only safe pre-send or completed local artifacts; it never promises remote deletion. This is engineering-only behavior; production automatic scheduling and Android cancellation integration are not claimed. See [Checkpoint 5 instructions](../server/deployment/gb10/CHECKPOINT5.md).
