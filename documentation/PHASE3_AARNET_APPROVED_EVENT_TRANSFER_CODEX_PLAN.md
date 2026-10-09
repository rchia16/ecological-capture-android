# Codex Implementation Brief - Android Phase 3
## Approved Ecological Event Export, BYOD-Safe Gateway, and AARNet FileSender Transfer

**Document version:** 1.0 (implementation plan, not an implementation report)  
**Prepared:** 2026-10-09  
**Project:** Ecological Capture / Smart Glasses Agents  
**Implementation agent:** Codex  
**Current app ID:** `com.rchia.ecocapture.phase0` (MUST remain unchanged)  
**Wearable:** Ray-Ban Meta glasses through Meta Wearables DAT 0.9.0  
**Primary validated development device:** Pixel 9 / Android 16  
**Planned deployment:** Participants' own Android phones (BYOD)  
**Local VLM:** Qwen3-VL-4B-Instruct Q4_K_M + Q8_0 mmproj; local llama.cpp/libmtmd only  
**Transfer:** Phone -> institutionally approved HTTPS gateway -> AARNet FileSender  
**Research archive:** UTS eResearch Storage, initially via a verified researcher-operated procedure  
**Initial upload trigger:** Explicit **Send approved recording** action; NEVER automatic because a clip is approved  
**Status:** PLAN. Do not infer any checkpoint has passed until executed and reported.

> **Codex instruction:** Read this entire document and the current Phase 1/2 reports before writing code. Implement only the next approved checkpoint. After that checkpoint, run its checks, write its report, and STOP for the investigator to approve continuation.

---

# 1. Decision Summary

Phase 3 is a narrowly scoped **research-data transfer** phase. It does not move AI inference to a server.

```text
Ray-Ban Meta glasses
    |
    v
Participant-owned Android phone
    |  (already implemented)
    +-- captured local MP4
    +-- local Qwen3-VL visual suggestion (optional)
    +-- participant's own description (optional)
    +-- immutable VLM output and participant amendment history
    +-- Review Later / Approve / Delete
    |
    |  NEW: participant explicitly chooses Send approved recording
    v
Frozen ecological event snapshot
    |-- video.mp4
    |-- event.json
    `-- manifest.json (SHA-256, byte sizes, schema)
    |
    v
Android WorkManager / HTTPS authenticated upload
    |
    v
EcoCapture Gateway (institutionally approved)
    |-- validates scoped phone credential
    |-- stores a short-lived upload/job record
    |-- rechecks all bytes against manifest
    |-- encrypts the event before FileSender where approved
    |-- holds the FileSender API secret (NEVER the phone)
    |
    v
AARNet FileSender (temporary transport; NOT the archive)
    |
    v
Researcher downloads, decrypts as applicable, verifies hashes
    |
    v
UTS eResearch Storage (research archive; manual workflow initially)
```

There is **no** DGX Spark, remote model, remote annotation, server-based prompt, REDCap API or in-app enrolment in this phase.

## 1.1 Decisions already fixed

- Participants use their **own phones**. Do not require Tailscale/VPN on participant devices.
- REDCap study enrolment and collection of participant demographic/clinical information remain separate.
- At enrolment the investigator provisions a fixed, hidden participant profile. No profile editing/display or enrolment screen in Phase 3.
- Local Qwen generation, model provenance, participant descriptions and AI amendments remain unchanged.
- The DGX Spark is **out of scope**. Tailscale is not part of Phase 3 phone upload.
- Approved clips can be entirely unannotated. Neither Qwen nor participant annotation is compulsory.
- `Approve` continues to mean a **local review decision**, not authorisation to silently transfer all historical approved clips.
- `Send approved recording` is a distinct, explicit action during the initial pilot.
- FileSender is transient transport. An AARNet completion response is NOT an eResearch archive receipt.

## 1.2 Decisions that must be confirmed at early gates

Do not guess or silently work around these:

1. The investigator has authorised this project's actual use of a public HTTPS gateway and FileSender for potentially identifiable egocentric video under the ethics approval/RDMP/PIS.
2. The gateway can be hosted on an approved research service with TLS, access controls, encrypted temporary storage, monitoring and an operational owner.
3. The actual AARNet API account can create, complete, retrieve and verify disposable transfers, and its recipient/email/expiry behavior is acceptable.
4. The encryption path for data on FileSender has been chosen, implemented and tested; the normal API upload does NOT automatically inherit browser-side end-to-end encryption.
5. The deployment supports BYOD Android versions and the separate multi-GB on-phone Qwen model provisioning constraints. This plan does not solve general BYOD inference compatibility.

**Real participant uploads remain DISABLED until those gates are signed off.** Disposable non-participant media may be used for engineering beforehand.

---

# 2. What Phase 2 Already Provides (Protect This)

Read these project files from the current repository if present:

- `documentation/PHASE2_CHECKPOINT8_REPORT.md`
- `documentation/PHASE2_CHECKPOINT9_REPORT.md`
- `documentation/PHASE2_DESCRIPTION_FLOW_REPORT.md`
- `documentation/PHASE2_BACKGROUND_PREPARATION_REPORT.md`
- `documentation/VLM_RUNTIME.md`
- `documentation/PHASE1_COMPLETION_REPORT.md`

Verified in the available Phase 2 reports:

- DAT 0.9 compressed HEVC -> local finalized MP4; Room clips/decisions and Media3 playback.
- `AnnotationEntity` history keeps participant revisions, `isCurrent`, and superseding lineage.
- `VlmRunEntity` retains original output, prompt/model/frame provenance, disposition, and `firstPresentedAtEpochMs`.
- An AI suggestion may be reviewed, used as an editable draft, rejected or regenerated; only an explicit Save creates a participant amendment.
- Local Qwen generation uses saved MP4 frames, with no participant demographics/clinical profile in the prompt.
- Local AI preparation may run through separate WorkManager requests; `VlmExecutionGate` protects capture from native inference and unload conflicts.
- Existing delete handling tombstones the clip, removes annotation/VLM text in Room and then removes the local media, with recovery on partial failure.
- Phase 2 automated recovery and provenance tests passed on Pixel 9, but **physical glasses and TalkBack human acceptance remained outstanding** in the checkpoint report.

Current tested local policy (retain it):

```text
Qwen3-VL-4B-Instruct Q4_K_M
Q8_0 mmproj
llama.cpp/libmtmd (pinned upstream commit)
ecological_scene_description_v2
three chronological frames at 15%, 50%, 85%
maximum long edge 1024; no upscaling
384 generated-token limit
```

Measured on-phone generation is approximately 10-13 minutes for the selected three-frame policy. This latency is known; **do not attempt to optimize or change the model during this transfer phase**.

## Protected invariants from earlier phases

1. Do not rename package ID, reset app data or change DAT capture APIs.
2. Do not alter source MP4 bytes for export, VLM or upload.
3. Do not make annotation compulsory for approval or transfer.
4. `PARTICIPANT`, `PARTICIPANT_AMENDMENT`, and original VLM records remain distinguishable.
5. Do not silently edit, summarize or correct VLM text in exports.
6. No remote service gets raw clinical/demographic profile fields or a REDCap identity.
7. Local review, annotation and playback must work when offline, when the gateway is down and when FileSender is down.
8. Capture should take priority over CPU/network-intensive work without disrupting a recording in progress.
9. Existing clips, decisions, VLM runs and participant annotations must survive app upgrades.
10. Existing deletion tombstones must never resurrect a deleted clip.

---

# 3. Explicit Scope and Exclusions

## In scope

- Immutable export of a **participant-approved ecological event**.
- Versioned event/manifest schemas preserving existing Room scientific provenance.
- Pseudonymous linkage compatible with separately stored REDCap demographics.
- SHA-256 hashes for each exported file and whole transfer artifact.
- Separate local upload/receipt/archival tracking states.
- BYOD-safe public HTTPS gateway with **per-installation** credentials.
- Short-lived gateway staging, quotas, idempotency and safe retry.
- Credential-protected gateway-to-AARNet Python FileSender REST integration.
- Encryption before sending raw event contents to FileSender, as chosen/approved in the security gate.
- Android WorkManager uploads over Wi-Fi by default, with pause/cancel/retry.
- Researcher download, decrypt, SHA-256 verify and initial manual eResearch Storage handling procedure.
- Truthful accessible transfer statuses; no sensitive content in notifications.
- Clear post-upload edits, deletion, withdrawal and late-delivery semantics.
- Instrumentation, integration and BYOD hardware/manual regression tests.

## Out of scope

```text
DGX Spark inference
server-hosted Qwen / remote VLM
Tailscale on participant devices
Tailscale Funnel
remote camera/recording control
uploading continuously or automatically after capture
automatic upload immediately on Approve
REDCap connection or enrolment screens
new demographic/diagnosis form
problem/solution structured questionnaire
cloud AI calls
model replacement or Qwen optimization
face/license plate blurring pipeline
automated long-term eResearch Storage ingest
public research data release
physical deletion of researcher copies without verified process
```

Phase 3 may produce an investigator-facing archival **procedure/ledger**, but must NOT claim automated eResearch Storage archival exists.

---

# 4. Security, Ethics and Data Governance Gate

Egocentric video may contain identifiable bystanders, locations, screens, sensitive text or other unexpected private information. Pseudonymous filenames are not anonymisation.

**Before any real participant recording leaves a phone:**

- Obtain written confirmation that the approved study consent/PIS and RDMP cover upload to the selected gateway, temporary FileSender transfer, researcher receipt, and actual hosting/processing locations.
- Confirm UTS requirements for the data classification, gateway security, temporary-copy retention, recipients, encryption and incident handling.
- Identify an owner for the gateway and a named operator for receiving FileSender transfers before expiry.
- Agree on a withdrawal process when footage has already reached FileSender or researcher storage.
- Ensure participants have a way to withhold any particular clip, even when generally consented to the study.
- Retain the external enrolment process; do NOT solve this by adding an unapproved in-app consent checkbox.

No upload client should be configured with production gateway credentials until this release gate is passed.

## 4.1 External provisioning contract

The investigator provisions, outside participant-facing screens:

```text
participant_code: study-issued pseudonym, not name/REDCap record number
installation_id: random UUID, unique per installation
installation_token: high-entropy random secret, unique per installation
upload_eligibility: explicitly enabled by investigator after external enrolment/consent
policy_version: external upload consent/protocol version
```

- Keep clinical/demographic fields in the hidden local profile or REDCap, not in the upload API request.
- Store the installation credential with Android Keystore-backed protection where practical; never in APK/BuildConfig/resources.
- The gateway must independently allowlist/revoke the installation ID and upload eligibility. A client-side flag alone is not permission.
- Loss/withdrawal can revoke a single installation without rotating all study devices.
- No participant name, institutional password, AARNet API secret or email login is required in the Android app.
- BYOD device compromise is a real residual risk; Keystore is at-rest protection, not a guarantee that a compromised live device cannot access its own token.

## 4.2 Secret isolation

```text
PHONE:
  gateway base URL
  installation ID + scoped token

GATEWAY:
  FileSender remote-user/API secret
  allowed installation IDs
  researcher recipient destination
  encryption public key only (preferred)

RESEARCHER ARCHIVE WORKSTATION:
  decryption private key (not on phone or gateway)
```

The existing FileSender secret that was pasted into development material must be **rotated** before Phase 3 deployment. Never reproduce old or new values in source, logs or this document.

---

# 5. Architecture and Component Boundaries

## 5.1 Android

```text
ClipRepository / AnnotationRepository / VlmRunRepository
                 |
                 v
      ApprovedEventSnapshotBuilder
                 |
                 v
         ExportSnapshotRepository
                 |
                 v
             TransferRepository
                 |
                 v
       GatewayUploadWorker (WorkManager)
                 |
                 v
          EcoCaptureGatewayClient
                 |
                 v
                HTTPS
```

**Do not** write FileSender HMAC signing or FileSender credentials into Android.

## 5.2 Gateway

```text
HTTPS reverse proxy / TLS
       |
       v
FastAPI EcoCapture Gateway
  |-- installation token auth / revoke / scope
  |-- rate/size quotas
  |-- resumable input object receiver
  |-- disk-space/backpressure guard
  |-- per-job SQLite or PostgreSQL state
  |-- SHA-256 and schema verification
  |-- ephemeral bundle creation
  |-- encryption before FileSender
  `-- background FileSender transfer task
                      |
                      v
              AARNet FileSender REST API
```

Use a **small**, auditable service. Do not include a VLM runtime, frames or video transcoding in the gateway.

## 5.3 Researcher receiving workflow

```text
AARNet transfer notification / researcher transfer list
      |
      v
Download encrypted event bundle
      |
      v
Decrypt with investigator-held private key
      |
      v
Verify manifest + file hashes
      |
      v
Register receipt in research ledger
      |
      v
Copy to approved eResearch Storage location
      |
      v
Verify copied file hashes and mark ARCHIVED_VERIFIED
```

Until the verified archival check is complete, the phone's retained MP4 is an important recovery copy.

---

# 6. Approved Event and Snapshot Policy

A **ClipRecord** is a local recording and review object. An **ExportSnapshot** is a frozen, immutable representation of one approved recording at one point in time.

## 6.1 Eligibility

A recording is eligible for Phase 3 transfer only if:

```text
clip exists and is not deleted/tombstoned
AND finalized readable MP4 exists
AND ApprovalState == APPROVED
AND app installation is provisioned and not locally disabled/revoked
AND external consent/policy gate has been confirmed
AND participant explicitly chooses Send approved recording
```

Never enqueue uploads merely because Phase 1/2 `ApprovalState` is APPROVED. In particular, **no legacy approved clip should auto-upload when Phase 3 is installed**.

## 6.2 Revisions after submission

- The snapshot captures a consistent Room view of annotation revisions, VLM runs and review decision.
- The video bytes are hashed and checked before/after snapshot creation.
- Edits after the snapshot is frozen are valid locally and **must not silently modify the already-submitted event**.
- UI should show `New local changes since last send` when applicable.
- A new explicit send produces a **new snapshot ID** and references the prior snapshot ID; do not overwrite a FileSender transfer.
- De-duplicate retries of the **same** snapshot ID; do not collapse a deliberately new version into an older snapshot.
- Study archival tooling must be able to identify latest/earlier versions unambiguously.

## 6.3 Approve, send and archive are distinct

```text
APPROVED           local participant review decision
SEND_REQUESTED     distinct user action
GATEWAY_VERIFIED   complete plaintext event received and hashes checked
FILESENDER_COMPLETE encrypted event accepted/finalized by FileSender
RESEARCHER_VERIFIED independent download/decrypt/hash verification
ARCHIVED_VERIFIED  file copied to eResearch Storage and independently checked
```

The last two statuses may initially be maintained in a researcher ledger, not automatically pushed to Android. **Never claim they occurred unless an actual researcher-side verification took place.**

---

# 7. Immutable Export Format

One export snapshot contains **exactly three logical files**:

```text
event_<eventUuid>_snapshot_<snapshotUuid>/
  video.mp4
  event.json
  manifest.json
```

All IDs in filenames are opaque UUIDs. Do not export source filenames with capture timestamps if unnecessary.

## 7.1 `event.json` schema (`ecocapture.event.v1`)

Example structure (illustrative only; actual fields must map to the repository's existing entities and names):

```json
{
  "schema": "ecocapture.event.v1",
  "event_id": "uuid",
  "snapshot_id": "uuid",
  "previous_snapshot_id": null,
  "study_participant_code": "PSEUDONYM",
  "clip_id": "uuid",
  "capture": {
    "started_at_epoch_ms": 0,
    "recorded_duration_ms": 0,
    "video_container": "mp4",
    "video_codec": "hevc",
    "video_width": 720,
    "video_height": 1280,
    "capture_metadata": {}
  },
  "review": {
    "review_state": "REVIEWED",
    "approval_state": "APPROVED",
    "approved_at_epoch_ms": 0,
    "snapshot_created_at_epoch_ms": 0
  },
  "participant_annotations": {
    "current_annotation_id": "uuid-or-null",
    "revisions": [
      {
        "annotation_id": "uuid",
        "source": "PARTICIPANT",
        "text": "The participant's exact saved text.",
        "created_at_epoch_ms": 0,
        "supersedes_annotation_id": null,
        "parent_vlm_run_id": null,
        "is_current": true
      }
    ]
  },
  "vlm_runs": [
    {
      "vlm_run_id": "uuid",
      "model_id": "Qwen3-VL-4B-Instruct",
      "prompt_version": "ecological_scene_description_v2",
      "generated_at_epoch_ms": 0,
      "first_presented_at_epoch_ms": null,
      "disposition": "NOT_PRESENTED",
      "raw_output": "Exact original model output, uncorrected.",
      "description": "Exact saved model description.",
      "model_runtime_frame_generation_provenance": {}
    }
  ],
  "export": {
    "schema_version": 1,
    "app_version": "...",
    "exported_at_epoch_ms": 0
  }
}
```

### Mapping requirements

- Preserve existing `AnnotationEntity` and `VlmRunEntity` values; do not invent fields not present in the Room database.
- Preserve exact participant text, uncertainty, original VLM output, annotation lineage and VLM disposition.
- Include all existing VLM provenance fields supported by Room, including model hash/projector hash, runtime, prompt, frame sampling, tokens/configuration and timing when available.
- `first_presented_at_epoch_ms = null` means no recorded presentation; do not serialize null as zero.
- Capture timestamps are phone-derived metadata, **not** independently verified wall-clock times.
- A clip with no annotation or no VLM run is valid; represent absent values as empty arrays/null.
- Do not synthesize problem or solution reports; those fields are **not** implemented in Phase 2.
- Never include demographics/diagnosis, contacts, REDCap record number, file paths, API tokens, encryption keys or raw FileSender metadata.
- Treat a study participant code as pseudonymous, still potentially linkable to sensitive external information.

## 7.2 `manifest.json` (`ecocapture.manifest.v1`)

```json
{
  "schema": "ecocapture.manifest.v1",
  "event_id": "uuid",
  "snapshot_id": "uuid",
  "created_at_epoch_ms": 0,
  "files": [
    {"path": "video.mp4", "size_bytes": 0, "sha256": "..."},
    {"path": "event.json", "size_bytes": 0, "sha256": "..."}
  ],
  "hash_algorithm": "SHA-256",
  "manifest_version": 1
}
```

The manifest deliberately does not contain its own recursive hash. The app and gateway hash the **exact serialized bytes** of all objects, including the manifest, without re-serializing JSON during verification.

A recommended deterministic export method:

1. Get an internally consistent Room snapshot of `ClipRecord`, annotations, current annotation pointer and all VLM runs.
2. Copy or read a finalized MP4 without modifying the source.
3. Hash the source MP4 and verify it is unchanged when export finishes.
4. Serialize `event.json` deterministically to UTF-8 without changing stored text.
5. Create `manifest.json` with exact file lengths and SHA-256 digests.
6. Persist snapshot identity and manifest hash in Room atomically, leaving media intact if any step fails.
7. Reopen and independently verify the resulting export files.

Use app-private export staging; clean abandoned temporary snapshots after a documented recovery window.

---

# 8. Encryption and FileSender Boundary

**Do not assume API uploads have FileSender browser end-to-end encryption.** AARNet's public guidance describes its optional browser AES-GCM encryption; the supplied Python script has an optional encrypted path but that path must be verified rather than assumed equivalent.

Preferred Phase 3 pilot strategy, pending institutional approval:

```text
Android -> gateway: HTTPS (gateway sees plaintext)
Gateway -> AARNet: single encrypted event archive
AARNet -> researcher: encrypted bytes
Researcher workstation: decrypt, then verify original files
```

Use an established audited file-encryption tool and public-key recipient scheme (for example `age` with an investigator-controlled recipient key) **only after an approved proof of interoperability and key-recovery procedure**. Do not invent ad hoc AES-GCM chunk encryption or reuse a FileSender password as the gateway API secret.

If using the proposed `age` transport:

```text
Gateway has investigator PUBLIC recipient key only.
Gateway packs event files into a deterministic archive and encrypts the archive.
FileSender receives event_<snapshotUuid>.tar.age (ciphertext).
Researcher PRIVATE key remains on a separately controlled workstation.
Researcher decrypts, validates archive contents and independently hashes files.
```

Security expectations:

- TLS validation on both hops, no `--insecure` mode.
- No private encryption key on Android or the gateway.
- Decryption private-key backup/recovery documented and tested with a non-sensitive fixture.
- Encrypt **before** FileSender, then keep plaintext gateway staging as short-lived as operationally possible.
- Avoid logging ciphertext URLs/download bearer tokens.
- Gateway should not have general decrypt capability if public-key encryption is selected.
- If institutional review requires another mechanism, STOP and document it; don't upload participant video in an unapproved protection mode.

---

# 9. Gateway API Contract (Phone-Facing)

The phone should know only a single HTTPS API that you control, not the FileSender REST protocol.

Recommended versioned paths:

```text
GET    /v1/health                    // deliberately minimal, no secrets
POST   /v1/transfers                 // idempotent creation of event snapshot transfer
GET    /v1/transfers/{transferId}    // state + accepted offsets/progress
PUT    /v1/transfers/{transferId}/objects/{objectName}/chunks/{offset}
POST   /v1/transfers/{transferId}/complete
POST   /v1/transfers/{transferId}/cancel
GET    /v1/transfers/{transferId}/receipt
```

Allowed object names:

```text
video.mp4
event.json
manifest.json
```

No arbitrary filesystem paths, URI schemes or arbitrary content destination headers.

## 9.1 `POST /v1/transfers`

Client sends:

```json
{
  "schema": "ecocapture.gateway.transfer.v1",
  "snapshot_id": "uuid",
  "event_id": "uuid",
  "objects": [
    {"name":"video.mp4", "size_bytes":12345678, "sha256":"..."},
    {"name":"event.json", "size_bytes":5120, "sha256":"..."},
    {"name":"manifest.json", "size_bytes":512, "sha256":"..."}
  ]
}
```

Headers contain:

```text
Authorization: Bearer <per-installation token>
Idempotency-Key: <snapshot UUID>
```

Response contains:

```json
{
  "gateway_transfer_id": "opaque-uuid",
  "state": "RECEIVING",
  "max_chunk_bytes": 4194304,
  "objects": [
    {"name":"video.mp4", "accepted_bytes":0},
    {"name":"event.json", "accepted_bytes":0},
    {"name":"manifest.json", "accepted_bytes":0}
  ]
}
```

`max_chunk_bytes` is a proposed gateway limit and MUST be configurable, separately from AARNet's advertised FileSender chunk size.

## 9.2 Resumable, idempotent object uploads

Each `PUT` streams bytes to a temporary file (no whole-video RAM allocation). The gateway:

- verifies token scope, transfer owner and upload eligibility;
- validates fixed object name, offset, chunk bounds and total expected length;
- atomically records accepted byte offsets;
- accepts an identical chunk retry without appending duplicate bytes;
- returns HTTP 409 on conflicting bytes/offset;
- rejects oversized bodies (HTTP 413) before excessive allocation;
- returns the authoritative accepted offset for resume;
- never writes outside the allocated per-transfer directory.

The exact chunk-request headers and success response must be defined in the contract tests before writing Android. Example:

```text
PUT /v1/transfers/{id}/objects/video.mp4/chunks/4194304
Content-Type: application/octet-stream
X-Chunk-SHA256: <digest of this chunk>
```

All errors use stable machine error codes without raw sensitive file paths.

## 9.3 `POST /complete`

Gateway:

1. verifies exact expected lengths of the three objects;
2. recomputes SHA-256 of every received file;
3. validates manifest schema, file linkage and hashes;
4. atomically marks `GATEWAY_VERIFIED`;
5. queues server-side encryption and FileSender transfer;
6. responds without claiming AARNet accepted the transfer yet.

## 9.4 `GET /receipt`

Only after actual FileSender finalisation:

```json
{
  "schema": "ecocapture.gateway.receipt.v1",
  "snapshot_id": "uuid",
  "gateway_transfer_id": "uuid",
  "gateway_verified_at_epoch_ms": 0,
  "filesender_transfer_id": 0,
  "filesender_completed_at_epoch_ms": 0,
  "filesender_expiry_at_epoch_ms": 0,
  "status": "FILESENDER_COMPLETE",
  "plain_manifest_sha256": "...",
  "encrypted_artifact_sha256": "..."
}
```

Do not return a FileSender recipient token, secret-bearing download URL, API key or signed authentication URL to the phone. Do not include a researcher-archive claim in this receipt.

---

# 10. Gateway Identity, Quotas and Storage

Gateway requirements:

- Institutionally approved host, TLS and domain. Localhost development does not count as production security approval.
- Per-installation 256-bit random bearer token, token hash stored server-side; credential can be revoked independently.
- Restrict each credential to only its own submitted transfer IDs; avoid user-controlled participant-code impersonation.
- Configure per-file, per-installation and aggregate disk size limits, request rate limits, maximum active jobs and backpressure.
- No unbounded retries, unbounded JSON, archive paths, multipart file counts or video length.
- All filenames are controlled by server; prevent `..`/symlink path traversal and ZIP/TAR extraction exploits.
- Scan/validate enough structure and SHA-256 to reject malformed/unexpected transfers; do not perform cloud scanning by accident.
- Keep only required event bytes temporarily. Encrypted gateway disk/storage and short-lived isolated staging are necessary for identifiable footage.
- Use a dedicated service account with restricted FS permissions. Do not run gateway or worker as root.
- No participant video/clinical text in access logs, metrics or exceptions.
- Backup the small operational metadata DB as approved, but do not create undisclosed backups of video.
- Operational monitor must report stalled jobs, expiring FileSender transfers, failed forwarding, and low storage capacity.

Initial **proposed** retention targets, to ratify at the security gate:

```text
incomplete gateway plaintext staging: purge after a bounded inactivity window (e.g. 24 h)
completed plaintext event bundle: purge after verified encrypted FileSender upload
encrypted retry artifact: retain only for a bounded retry/receipt window (e.g. 7 days)
gateway operational metadata: retained according to approved study/security policy
```

These are design targets, not assertions of UTS policy.

---

# 11. Gateway -> FileSender Python Adapter

Use the investigator-provided `filesender.py` as a **reference**, not as an unreviewed general-purpose production command.

Document the supplied client's specifics:

- reads `~/.filesender/filesender.py.ini` or `./filesender.py.ini`;
- authenticates with `remote_user`, timestamp and HMAC-SHA1 signature;
- obtains `upload_chunk_size` from `/info`;
- creates a transfer, sends binary chunks, marks files complete, then marks transfer complete;
- has configurable recipients and expiry;
- supports optional encryption, but that must be tested explicitly if selected;
- in verbose/debug paths the supplied script can print **the API key** and failure URLs containing signatures or tokens.

**Production must not call the supplied CLI with `-v`/`--verbose` or `--insecure`.** Prefer a small reviewed Python adapter that imports/pins compatible protocol logic but redirects diagnostics to redacted structured errors and never prints secret-bearing URLs.

FileSender REST reference:

```text
GET  https://filesender.aarnet.edu.au/rest.php/info
POST https://filesender.aarnet.edu.au/rest.php/transfer
PUT  https://filesender.aarnet.edu.au/rest.php/file/{fileId}/chunk/{offset}
PUT  https://filesender.aarnet.edu.au/rest.php/file/{fileId}          {"complete":true}
PUT  https://filesender.aarnet.edu.au/rest.php/transfer/{transferId} {"complete":true}
```

Actual FileSender signed-request and chunk semantics are documented in upstream REST docs; use the account's **real** `/info` response for valid chunk size and expiry parameters. Do not assume transfer-finalisation provides an independent plaintext SHA-256 checksum: perform researcher download/decrypt/hash verification.

Server adapter should initially use **one FileSender transfer per immutable snapshot**, containing one encrypted archive object. Check actual AARNet email notification and rate-limit behavior; if this creates unacceptable email volume, STOP and propose controlled batching before implementing it.

Recommended FileSender email subject:

```text
EcoCapture approved event <opaque snapshot UUID>
```

No participant name, participant code, diagnosis, annotation text, filename from the source device, location or precise capture timestamp in subject or message.

---

# 12. Transfer State Model

Keep event review decisions, snapshot state, and network transfer states independent.

## 12.1 Android `ExportSnapshotEntity`

Suggested fields (map to actual repository conventions before coding):

```kotlin
@Entity(tableName = "export_snapshots")
data class ExportSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val clipId: String,
    val previousSnapshotId: String?,
    val approvedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val eventJsonSha256: String,
    val manifestSha256: String,
    val mp4Sha256: String,
    val snapshotState: String,
    val appVersion: String
)
```

## 12.2 Android `TransferJobEntity`

```kotlin
@Entity(tableName = "transfer_jobs")
data class TransferJobEntity(
    @PrimaryKey val jobId: String,
    val snapshotId: String,
    val clipId: String,
    val state: String,
    val gatewayTransferId: String?,
    val filesenderTransferId: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val retryCount: Int,
    val lastSafeErrorCode: String?,
    val filesenderReceiptAtEpochMs: Long?
)
```

Initial states:

```kotlin
enum class TransferJobState {
    LOCAL_ONLY,
    QUEUED,
    UPLOADING_GATEWAY,
    GATEWAY_VERIFIED,
    FORWARDING_FILESENDER,
    FILESENDER_COMPLETE,
    FAILED_RETRYABLE,
    NEEDS_ATTENTION,
    CANCEL_REQUESTED,
    CANCELLED
}
```

The gateway reports its state; the phone does not invent a FileSender-complete transition based on byte transfer to the gateway. `FILESENDER_COMPLETE` is **not** `ARCHIVED_VERIFIED`. The latter belongs in a separate researcher receipt/ledger in Phase 3.

## 12.3 Gateway state

```text
RECEIVING
VERIFYING
GATEWAY_VERIFIED
ENCRYPTING
FORWARDING_FILESENDER
FILESENDER_COMPLETE
FAILED_RETRYABLE
FAILED_TERMINAL
CANCEL_REQUESTED
CANCELLED
```

`FILESENDER_COMPLETE` requires server-acknowledged file completion **and** transfer completion with a retained transfer ID and expiry timestamp. On retry, inspect the recorded AARNet transfer before creating another; do not blindly create duplicate transfers.

---

# 13. Android WorkManager, UX and Concurrency

## 13.1 Defaults

- Uploads are **manual** after Approve; no automatic scanning/submission of all approved clips.
- Default to `NetworkType.UNMETERED` and `batteryNotLow=true`.
- A visible setting may later allow cellular upload, but never enable it silently. Explain data charges.
- Do not require charging unless empirical data demand it.
- Use `WorkManager` unique work named `ecocapture-upload-<snapshotId>`.
- Work requests store opaque snapshot IDs, not video bytes, annotation text or credentials.
- Use a foreground notification only when required for long-running transfers; follow current Android requirements and quotas.
- No need to keep the screen on for network upload.
- Do not make upload success depend on generating any VLM suggestion.

## 13.2 Concurrency

The existing `VlmExecutionGate` concerns local inference versus capture. Upload is a separate IO operation, but must not starve or destabilise capture.

Rules:

- Never block the capture initiation path on a network call.
- Avoid simultaneous heavy hashing/copying or transfer when capture is actively writing; defer intensive export preparation when appropriate.
- Keep upload worker memory bounded and file IO streamed; never map full MP4 into a giant ByteArray.
- Prefer limiting to one active upload per app, with configurable concurrency later.
- Cancel/invalidate jobs when the source clip becomes DELETED or approval is withdrawn.
- Do not cancel Qwen preparation merely because an upload was queued, unless measured resource conflicts justify a reviewed policy.
- Existing local auto-AI preparation preferences must **never** trigger network upload.

## 13.3 UI wording

Add an accessible **Approved recordings** or **Send recordings** area. Example:

```text
APPROVED RECORDING
Your video is saved on this phone.

[ SEND APPROVED RECORDING ]

Upload setting: Wi-Fi only
```

Status examples:

```text
Waiting for Wi-Fi
Preparing files to send
Sending recording...
Received by the research transfer service
Transfer to AARNet completed (not yet archived)
Could not send. Recording is still on this phone.
```

Offer `Retry`, `Cancel pending transfer` and a clear explanation of what cancellation can still achieve. Do not report `Archived`, `Deleted remotely`, or `Withdrawn` without explicit researcher/system evidence.

All controls must have TalkBack labels, heading semantics, correct focus ordering, accessible progress messages and minimum practical touch targets. Notifications contain no descriptions, source file names, participant profile information or exact capture details.

---

# 14. Post-Send Editing, Delete and Withdrawal

These are not the same action:

```text
Delete local clip     remove phone copy and participant-facing content
Cancel queued upload  prevent an upload that has not finalized
Revoke remote copy    request deletion of FileSender/gateway copy if technically possible
Withdraw research use researcher-managed withdrawal under ethics/retention rules
```

## 14.1 Before gateway completion

- Cancel WorkManager and mark the snapshot `CANCEL_REQUESTED`.
- Ask gateway to discard partial upload and cancel forwarding if not committed.
- Gateway acknowledges actual cancellation and purges staging; the phone must not claim remote deletion until acknowledged.

## 14.2 After FileSender completion

- Phone cannot assume cancellation retracts bytes already sent to AARNet.
- If investigator-controlled transfer deletion is supported, gateway may attempt closure/deletion, but only report success after confirming it.
- A researcher may already have downloaded a copy. Report `Withdrawal request sent to research team` rather than `All copies deleted`.
- Maintain a researcher audit trail of the request and actions, without retaining participant description text unnecessarily.

## 14.3 Deleting the local clip

- Preserve Phase 2 tombstone and annotation/VLM purge behavior.
- New transfer records must be excluded from active queue after deletion.
- Retain only minimal non-content transfer audit references if the ethics/security policy requires them; delete active export text/snapshot files on local deletion.
- Never resurrect media during reconciliation or import an annotation from the gateway.

## 14.4 Superseding a prior exported version

If a participant edits the description after sending, new local text does not replace an already transmitted version. Require a new explicit snapshot/send and preserve linkage to prior snapshot; researcher SOP must distinguish it from a network retry.

---

# 15. Researcher Receipt and Manual Archive SOP

A researcher-operated verification step is part of Phase 3 acceptance, even though eResearch Storage integration itself remains manual.

For each FileSender transfer:

1. Record numeric FileSender transfer ID, opaque snapshot ID, sender and expiry in a secure research ledger.
2. Download the encrypted event archive using the authorized recipient link/account. Treat the link/token as a bearer secret; do not put it in logs or shared spreadsheets.
3. Compute SHA-256 of the downloaded encrypted archive and compare it with the gateway receipt.
4. Decrypt with the investigator's private key on an approved workstation.
5. Extract safely, verifying expected filenames only and rejecting unsafe archive paths/symlinks.
6. Validate `manifest.json` and SHA-256 of `video.mp4` and `event.json`.
7. Optionally probe playable HEVC MP4 and inspect selected test footage for semantic sanity, without changing original files.
8. Copy verified files to the approved UTS eResearch Storage project path.
9. Rehash the destination files and mark `ARCHIVED_VERIFIED` only if hashes and permissions match.
10. Record investigator, date/time, verified artifact hashes and any exceptions in the research ledger.
11. Ensure a process exists to check unattended transfers before FileSender expiry.

The research ledger could initially be a controlled CSV/database under university-managed storage. Do not send participant names, diagnosis or raw description text by email to operate this process.

A transfer still waiting in FileSender close to expiry is a **failure of operational receipt**, even if the phone shows `FILESENDER_COMPLETE`. Escalate before expiry and retain the local original unless a separately approved retention policy authorizes deletion.

---

# 16. Required Source Layout (Indicative)

Do not move existing Phase 1/2 classes solely to match this example.

Android additions:

```text
app/src/main/java/com/rchia/ecocapture/phase0/
  export/
    ApprovedEventSnapshotBuilder.kt
    EventSnapshotSerializer.kt
    EventManifest.kt
    ExportSnapshotRepository.kt

  data/local/
    ExportSnapshotEntity.kt
    ExportSnapshotDao.kt
    TransferJobEntity.kt
    TransferJobDao.kt

  transfer/
    EcoCaptureGatewayClient.kt
    GatewayCredentialStore.kt
    TransferRepository.kt
    TransferState.kt

  worker/
    ApprovedEventUploadWorker.kt

  ui/transfer/
    ApprovedRecordingsScreen.kt
    TransferStatusUiState.kt
```

Gateway additions (in `server/` or a separate approved private repository):

```text
server/
  README.md
  pyproject.toml
  ecocapture_gateway/
    app.py
    auth.py
    config.py
    models.py
    db.py
    upload_store.py
    manifest_validator.py
    archive_encryptor.py
    filesender_adapter.py
    forwarding_worker.py
    receipt.py
    cleanup.py
  tests/
  deployment/
    systemd/
    reverse-proxy/
```

Researcher tool:

```text
tools/researcher-receipt/
  README.md
  verify_event.py
  archive_checklist.md
  sample_ledger.csv
```

Store **no actual participant video**, secrets or real participant profiles in Git or sample files.

---

# 17. Codex Working Rules

**Every checkpoint is hard-gated.**

1. Read current implementation/report files. Do not assume the Phase 2 plan describes unimplemented work; inspect actual code.
2. Begin at Checkpoint 0. Do one checkpoint only. STOP after reporting.
3. Never clear app data, rename the package, uninstall a valued research installation or delete production MP4s.
4. Never use Gradle `connectedDebugAndroidTest` on a data-bearing phone if its UTP configuration uninstalls the app. Use the existing direct `tools/run-room-tests.ps1` retention-safe path, or disposable emulators.
5. Use disposable videos and fake/harmless metadata in all network tests until the external release/security gate is approved.
6. Do not read, print or commit the supplied `.ini` API secret. Require a rotated and separately provisioned credential.
7. On every code checkpoint run module-qualified Gradle build/unit tests and the non-destructive Room suite when relevant.
8. Keep Android local Qwen engine, `VlmEngine`, prompt, native library and model policy unchanged.
9. A failure must never erase the finalized source MP4 or silently change APPROVED to UPLOADED.
10. The FileSender adapter runs server-side in Python. Do not port the entire FileSender Python client to Kotlin.
11. Do not add remote VLM, DGX, Tailscale on BYOD phones, REDCap enrolment or automatic archiving.
12. Never claim user/test hardware, AARNet, gateway, security, TalkBack or researcher archive checks passed unless actually operated.
13. At the end of each checkpoint produce `documentation/PHASE3_CHECKPOINT<N>_REPORT.md` with status, exact commands, screenshots/log summaries if applicable, test results, changed files, data retention verification, manual checks, issues, risk and a STOP marker.
14. STOP and ask the investigator before any irreversible or billable real external transfer, infrastructure provisioning or integration of private credentials.
15. Maintain a decision log `documentation/PHASE3_DECISIONS.md`, especially for gateway host, crypto, consent policy, recipient, expiry, retention and BYOD support.

## Standard report template

```markdown
# Phase 3 Checkpoint N - <name>

Status: PASS / PARTIAL / BLOCKED
Date:

## Scope implemented
## Files added/modified
## Commands executed
## Automated tests (exact counts, pass/fail/skip)
## Physical-device tests (device/OS, only if actually run)
## Gateway and AARNet tests (only if actually run)
## Data-safety / retention check
## Manual acceptance steps
## Known failures and limitations
## Recommended next checkpoint

STOP. Do not continue without explicit approval.
```

---

# 18. Checkpoint Plan

## CHECKPOINT 0 - Baseline Release Gate and Protected Data Audit

**Purpose:** Make sure Phase 2 data and participant-facing functions are safe before network functionality is added.

### Tasks

1. Inspect current repository, app/Room versions, screen flow, existing `VlmExecutionGate`, WorkManager configuration, capture/review/approval/delete logic, and safe test runner.
2. Read/check Phase 2 Checkpoint 8/9 reports and identify any newer commits or reports. Do not claim those reports represent current code if the repository differs.
3. Inventory current test clips, annotation revisions and VLM runs without copying or deleting participant material.
4. Establish app data-safe upgrade process (`adb install -r` with unchanged package/signing identity).
5. Complete outstanding **physical glasses and TalkBack manual acceptance**, including optional local Qwen and automatic preparation controls.
6. Draft external consent/security questions for the investigator and confirm no live participant network transfer at this stage.
7. Identify at least one disposable HEVC glasses recording and entirely synthetic non-participant content for the phase's integration tests.

### Automated checks

- Existing Android debug/release build and unit regressions.
- Room migration/annotation/provenance tests using the retention-safe runner.
- Verify test runner never uninstalls target package or clears valued data.

### Manual acceptance

- Ray-Ban discovery -> capture -> finalize -> queue -> playback.
- Add/edit participant description, VLM suggestion/use/reject, Review Later, Approve, Delete on disposable clips.
- TalkBack and large text throughout the relevant screens.
- Ensure old app data and model files persist across a replacement install.

### Exit criteria

```text
[ ] actual protected code paths documented
[ ] app data backup/retention strategy confirmed
[ ] physical glasses acceptance results recorded
[ ] TalkBack acceptance results recorded
[ ] disposable test clips selected
[ ] no actual network path yet
```

**STOP.** Do not begin FileSender transfer code until the investigator approves the baseline.

---

## CHECKPOINT 1 - Live AARNet Protocol and Encryption Feasibility (Harmless Files Only)

**Purpose:** Prove that the actual AARNet account and Python reference client work before gateway work begins.

### Tasks

1. Rotate the previously exposed FileSender API secret. Provision the new secret in a root/user-protected file outside Git and test reports.
2. Record official API host `https://filesender.aarnet.edu.au/rest.php`, client/version and account identity **without recording the secret**.
3. Request `GET /info`; record exposed `upload_chunk_size`, transfer expiry and accepted options. Distinguish server configuration from assumptions.
4. Use the investigator-supplied Python reference workflow to create an input transfer with two harmless files, send chunks, complete files and transfer.
5. Download as researcher; verify exact byte-for-byte SHA-256 match.
6. Evaluate actual FileSender recipient email behavior and notifications for one-event-per-transfer.
7. Test which encrypted transfer modes genuinely work with the actual AARNet account/client and can be decrypted and verified.
8. Separately check guest/voucher API eligibility if worth exploring; do not choose that as the production BYOD architecture merely because browser vouchers exist.
9. Inspect the supplied client for logging of credentials and signed URLs; produce a redaction/patch list.
10. Decide whether the gateway will use investigator public-key `age` encrypted bundles or a verified, institutionally approved equivalent.

### Tests

```text
[ ] signed auth works with rotated secret
[ ] GET /info returns usable settings
[ ] harmless 2-file transfer completes
[ ] researcher download matches SHA-256
[ ] recipient/notification volume characterized
[ ] encryption + decryption proof passes
[ ] expiry recorded
[ ] no secrets or download tokens printed
```

### Deliverables

- `documentation/PHASE3_AARNET_PROTOCOL.md` (redacted, no credentials)
- `documentation/PHASE3_CRYPTO_DECISION.md`
- Disposable round-trip SHA-256 verification record

### STOP

Do not forward real media to FileSender. If the encryption/receiving path fails, stop and propose alternatives rather than relaxing the security requirement.

---

## CHECKPOINT 2 - Immutable Local Event Snapshot + Manifest

**Purpose:** Generate scientifically faithful export artifacts without any network connection.

### Tasks

1. Define and version `ecocapture.event.v1` and `ecocapture.manifest.v1` with schema validation.
2. Implement `ApprovedEventSnapshotBuilder` and `EventSnapshotSerializer` using existing Room entities; no new annotation data entry system.
3. Keep source MP4 unchanged; derive export filename from random UUIDs.
4. Include saved participant annotation **history**, current pointer, VLM original output/provenance and amendment references.
5. Handle no annotation/no VLM/participant-only/VLM-only/both valid states.
6. Include only the externally provisioned pseudonymous study code as the participant linkage (if approved). No demographics or clinical profile fields.
7. Preserve every already-recorded timestamp as stored; null stays null. No invented reasoning/problem/solution data.
8. Create hashes and manifest; persist source SHA-256, event JSON SHA-256, manifest SHA-256.
9. Detect a concurrent edit/delete during snapshot and either take a consistent DB snapshot or cancel with a retryable error.
10. Use hidden engineering export inspection initially; no participant upload UI.

### Automated tests

- Exact raw original VLM output and all provenance survive export.
- Revision ordering/lineage and current annotation selection.
- Null exposure timestamp and empty annotation/VLM states.
- Source MP4 SHA unchanged.
- Corrupt/missing MP4 fails without deleting source.
- Concurrent deletion/edit aborts or yields a consistent, versioned snapshot.
- No clinical/demographic secrets, REDCap ID, absolute device file paths or API secrets in event/manifest.
- Deterministic JSON byte representation and cross-language hash verification.
- Export interrupted during file creation can be cleaned or safely resumed.

### Manual check

Open exported JSON and replay the original MP4 for at least three **disposable** clips: no annotations, participant annotations only, and a saved VLM amendment.

### Exit criteria

```text
[ ] three logical export files produced locally
[ ] exact hashes pass independent verifier
[ ] Phase 2 data/provenance unchanged
[ ] no network permissions or calls added yet
```

**STOP.** Wait for approval.

---

## CHECKPOINT 3 - Transfer State, Room Migration, and Offline Upload UI

**Purpose:** Introduce independent transfer records without modifying clip review decisions.

### Tasks

1. Add `ExportSnapshotEntity`, `TransferJobEntity`, DAOs and repositories.
2. Use an explicit migration from the actual current DB version (do not assume version 2 without inspecting the repository).
3. Do NOT migrate existing approved clips to `QUEUED` or schedule work on upgrade.
4. Add accessible Approved Recordings/Send area with manual Send action, initially backed by a fake transport only.
5. Show Wi-Fi-only default and truthful pending/progress/result states.
6. Create a transfer only when the recording is APPROVED, present, not tombstoned, validly provisioned, and user explicitly requests send.
7. Preserve distinct local approval and transfer states; do not equate `APPROVED` with `FILESENDER_COMPLETE`.
8. Detect and label changes made after a snapshot; offer a new explicit snapshot for re-sending, without silently mutating an existing snapshot.
9. Ensure Delete cancels/hides queued work and preserves Phase 2 tombstone semantics.

### Automated tests

- DB migration retains all clips/approvals/deferred/tombstones/annotations/VLM records.
- No legacy approved autoqueue.
- Approval without Send leaves no upload job.
- Send on unapproved/deleted/missing clip rejected.
- Fake network fail -> local files intact.
- Two taps -> same idempotent job or explicit confirmation, not duplicate transfers.
- Editing after send marks snapshot superseded; existing snapshot bytes immutable.
- Revision/reopen preserves job states.

### Manual check

Approve a disposable clip; verify nothing is sent automatically. Trigger fake Send. Use TalkBack to check labels/status, then change the description and verify it is flagged as unsent changes.

**STOP.** Wait for approval.

---

## CHECKPOINT 4 - HTTPS Gateway Skeleton, Per-Installation Authentication, and Mock Transport

**Purpose:** Validate a BYOD-safe gateway before any AARNet forwarding.

### Tasks

1. Choose an institutionally approved host and deployment method; record decision in `PHASE3_DECISIONS.md`.
2. Implement HTTPS-only FastAPI gateway behind an appropriate reverse proxy; no DGX/vLLM.
3. Implement per-installation token issuance/allowlist, independent revocation, token hashing and narrowly scoped authorization.
4. Implement idempotent transfer creation, safe file object names, resumable chunks and `/complete` hash verification.
5. Enforce size, request, disk, active-job and rate limits; implement controlled error codes.
6. Store operational metadata in SQLite for development; evaluate PostgreSQL if concurrency/operations warrant it.
7. Build a fake FileSender adapter that records intended transfers but makes no external calls.
8. Add local cleanup and strict path/permissions handling; secrets only via approved secret injection.
9. Review TLS trust, HSTS/proxy headers, log redaction, endpoint exposure, health-response contents and backups.
10. Do NOT issue a long-lived gateway credential to a production participant before approval.

### Test matrix

- Unauthenticated -> 401.
- Revoked -> 401/403.
- Device A cannot query Device B's transfer.
- Oversized/malformed chunk -> reject without partial corruption.
- Chunk replay identical -> idempotent.
- Conflicting chunk -> 409 with authoritative offset.
- Process restart resumes accepted offsets.
- Complete recomputes hashes and rejects mismatch.
- Malicious file name/traversal -> rejected.
- Gateway is offline -> phone/client receives retryable error.
- Raw request logs and exception logs contain no bearer tokens, participant text or event data.

### Exit criteria

```text
[ ] HTTPS test gateway reachable from ordinary internet
[ ] no Tailscale/VPN needed on phone
[ ] per-installation revocation works
[ ] resumable harmless upload works
[ ] no actual FileSender call yet
```

**STOP.** Await investigator confirmation of host and security setup before proceeding.

---

## CHECKPOINT 5 - Gateway Encryption and Actual AARNet Forwarding

**Purpose:** Prove a fully verified gateway -> AARNet -> researcher round trip using disposable data.

### Tasks

1. Integrate the reviewed/pinned Python FileSender adapter, not the unmodified verbose CLI.
2. Implement deterministic bundling of the three event files into one transport archive.
3. Encrypt archive using the mechanism explicitly approved at Checkpoint 1 (e.g. `age` to investigator public key).
4. Verify encrypted artifact SHA-256 and store only non-secret metadata in job DB.
5. Call `GET /info`, create FileSender transfer, upload bounded chunks, complete file and transfer, store transfer ID/expiry.
6. Use configured investigator/research recipient, never participant email or participant identifying information.
7. Persist transfer ID as soon as created; after crash check remote status instead of blindly duplicating transfers.
8. Redact all FileSender signed URLs, tokens, API keys and recipient download links in errors and logs.
9. Ensure completed plaintext gateway staging is securely purged according to the approved retention plan; distinguish best-effort file removal from guaranteed secure erasure.
10. Prevent notifying the Android client `FILESENDER_COMPLETE` until AARNet finalisation is acknowledged.

### End-to-end disposable checks

```text
Gateway receives 3 files
 -> verifies source hashes
 -> creates encrypted archive
 -> FileSender API transfer completes
 -> researcher downloads ciphertext
 -> verifies ciphertext SHA-256
 -> decrypts on researcher workstation
 -> verifies original video/event/manifest SHA-256
```

Additional cases: temporary FileSender 5xx/429, missing credential, signature rejected, upload interruption, restart after remote transfer creation, duplicate `/complete`, notification count and short expiry.

### Exit criteria

```text
[ ] reference FileSender protocol works through gateway
[ ] researcher decryption works
[ ] plaintext hashes preserved
[ ] no credentials/tokens in logs
[ ] no duplicate logical job on retry
[ ] transfer receipt clearly says NOT ARCHIVED
```

**STOP.** No production participant videos yet.

---

## CHECKPOINT 6 - Android Gateway Credential Provisioning and Disposable Upload Client

**Purpose:** Add secure HTTPS communications to participant-style Android installations without touching participant media.

### Tasks

1. Implement `EcoCaptureGatewayClient` and a separate provisioned `GatewayCredentialStore`.
2. Credential/installation ID is investigator provisioned; no participant login, Tailscale or FileSender credential.
3. Use normal Android TLS validation; reject cleartext and unapproved debug trust bypasses in release.
4. Implement chunk resume and authoritative offset queries, bounded retries and typed errors.
5. Use a disposable data blob and synthetic event manifest through the gateway in an engineering mode.
6. Verify loss/revocation of token causes graceful failure while local capture/review/description still work.
7. Prevent gateway tokens from being backed up/exported inadvertently; review Android backup/data-extraction configuration.
8. Verify supported BYOD Android version(s), battery/background limits and HTTPS trust.

### Automated tests

- Mock server request/response/state/offset contract.
- Token injection and redaction.
- TLS and no HTTP fallback.
- Chunk retries and HTTP 409 recovery.
- 401/403, 413, 429, timeout, 5xx mapping.
- No source file buffering in memory.
- No participant-facing technical stack traces.

### Manual acceptance

Provision a development device with a **disposable** installation token. Upload harmless test bytes over Wi-Fi and cellular, confirming the transport behavior and that no FileSender credential is present in APK/logcat.

**STOP.** Await approval before using an MP4.

---

## CHECKPOINT 7 - WorkManager Approved Event Transfer

**Purpose:** Connect a real approved, disposable local recording to the gateway/AARNet transport.

### Tasks

1. Add unique `ApprovedEventUploadWorker` with default unmetered network and battery-not-low constraint.
2. Wire only the explicit Send action to immutable snapshot generation and upload.
3. Stream the three objects; do not load the MP4 into a large byte array.
4. Query server state after interruption and resume by accepted offset.
5. Poll server status with bounded/backoff scheduling; do not run a permanent Android service.
6. Reflect `GATEWAY_VERIFIED`, `FORWARDING_FILESENDER`, `FILESENDER_COMPLETE` only when received from gateway.
7. Preserve local MP4 and original annotation/VLM records on every network failure.
8. Keep local Qwen/automatic AI preparation behavior unchanged. Give capture priority; avoid resource-intensive snapshot hashing during active capture.
9. Show accessible pending/progress/failure/receipt UI and generic notifications.
10. Do not delete source or snapshot solely on AARNet completion.

### Test

Use **three disposable approved glasses clips** representing participant-only, VLM-only, and VLM-plus-participant-amendment states. Reconcile all three against downloaded event bundles.

Failure cases:

```text
start offline -> wait
Wi-Fi disconnect mid-chunk -> resume
same work ID retried -> no duplicate event
cancel before upload -> no remote completed event
process killed -> resume from persisted job state
FileSender delayed -> not displayed as complete
Approve without Send -> no upload
unapproved/deleted -> upload prohibited
```

### Manual acceptance

Record -> save -> optional local Qwen -> description -> Approve -> Send -> review status -> FileSender receipt. Check TalkBack, Wi-Fi-only default and notification wording.

**STOP.** Wait for approval.

---

## CHECKPOINT 8 - Researcher Download, Verification, and Manual Archival Procedure

**Purpose:** Make AARNet a verifiable data-transfer route rather than an unreliable parking area.

### Tasks

1. Implement `tools/researcher-receipt/verify_event.py` and a concise researcher SOP.
2. Input: downloaded encrypted FileSender artifact and authorized decryption credential handled outside logs/CLI history.
3. Verify encrypted file hash against gateway receipt; decrypt and verify original manifest hashes.
4. Validate `event.json` schema; check UUID/linkage and annotation/VLM revision provenance.
5. Produce a non-sensitive verified receipt/ledger entry with transfer ID, snapshot ID, exact hashes, download/verification time, expiry and researcher identity.
6. Copy the successfully verified event to UTS eResearch Storage **manually** following approved university access controls.
7. Rehash final destination files; set `ARCHIVED_VERIFIED` in the research ledger only after this succeeds.
8. Document daily/regular unattended transfer checks and escalation before the FileSender expiry date.
9. Confirm approved participant code can be joined to external REDCap data by investigator without exposing demographics in the transfer bundle.

### Test

- Correct archive passes.
- Corrupted encrypted archive fails.
- Wrong decryption key fails.
- Valid decryption with corrupt `video.mp4` fails manifest hash.
- Missing file/extra unexpected file fails.
- Wrong participant/event linkage fails.
- Repeated receipt is idempotent in ledger.
- eResearch copy failure does NOT mark archived.
- Successful independent eResearch hash verification marks archived.

### Exit criteria

```text
[ ] independent researcher verification passes
[ ] encrypted data were protected in FileSender transit/storage representation
[ ] FileSender completion and archiving remain separate
[ ] an expiry-monitoring procedure exists
```

**STOP.** Await investigator approval of the actual receiving procedure.

---

## CHECKPOINT 9 - Recovery, Deletion, Withdrawal, and Revision Matrix

**Purpose:** Prove transfer failure never destroys source recordings and remote deletion claims are truthful.

### System tests

```text
No Wi-Fi / captive portal
Phone switches Wi-Fi to cellular
Phone force stop and relaunch
Phone reboot
Low battery / WorkManager quota delay
Phone storage nearly full
Gateway offline / TLS certificate failure
Gateway 401/403 token revoked
Gateway 409 resume conflict
Gateway 413 file over limit
Gateway 429 rate limited
Gateway 5xx
Gateway loses power during chunk write
Gateway restarts after hashes complete
Gateway restarts after FileSender transfer create
AARNet API secret rotated
AARNet partial chunk rejection
AARNet transfer finalisation delayed
AARNet expiry before researcher download
Researcher encrypted download damaged
Researcher decryption key unavailable
Clip deleted before Send
Clip deleted while queued
Clip deleted while gateway upload active
Clip deleted after FileSender completion
Clip deleted after researcher download
Clip edited before send
Clip edited after FileSender completion
Two taps on Send / duplicate Worker
Legacy APPROVED clips after migration
Two different snapshots of the same clip
```

### Expected behavior

- No upload failure deletes or modifies the local source MP4.
- All retryable states are explicitly retryable and have no false success feedback.
- On local Delete, preserve tombstone protections and cancel local workers.
- Remote cancel/withdrawal is **requested** separately; do not claim complete deletion of AARNet or downloaded copies unless verified.
- Old snapshot remains identifiable; changed annotations require explicit new send.
- A lost credential blocks upload, not review or annotation.
- Existing Phase 2 background Qwen work does not accidentally upload clips.
- No leaked bearer token/API key/download link in logs, exceptions, notifications or user-visible UI.

### Privacy/security review

Review compiled APK, Android manifest, backup rules, server code and deployment secrets. Confirm there is no AARNet master secret on the phone, no clinical profile in gateway API requests, no remote VLM, no participant-wide cloud analytics and no public research file URLs.

**STOP.** Fix blocking findings before end-to-end release validation.

---

## CHECKPOINT 10 - End-to-End BYOD, Accessibility, and Research Release Gate

**Purpose:** Validate the entire intended participant workflow on the actual supported BYOD device range.

### Full scenario

```text
externally provisioned participant profile / installation credential
   -> glasses connection
   -> participant-initiated capture
   -> finalized local MP4
   -> local participant description and/or optional local Qwen
   -> Review Later or Approve
   -> explicit Send approved recording
   -> Wi-Fi WorkManager upload
   -> gateway receipt and AARNet finalization
   -> researcher download/decrypt/hash verify
   -> manual eResearch Storage copy and hash verification
```

### Acceptance dimensions

1. **Glasses/device:** registration, pairing, capture, playback, orientation, storage and model availability on supported phone models.
2. **Local AI:** VLM stays entirely on phone; no model remote calls; participant text never auto-replaced.
3. **Accessibility:** TalkBack can navigate queue, approval, Send, status, retry and cancellation; large fonts, touch targets and screen-reader announcements.
4. **Participant burden:** no requirement to install Tailscale/FileSender/REDCap apps, no need to interact with research gateway, no compulsory annotations.
5. **Network:** no upload on mobile data by default; offline capture/review unaffected; background completion and interruption recover.
6. **Security:** file encryption, credential isolation, limited upload eligibility, signed/verified artifacts and retention policy.
7. **Scientific data:** original clip/VLM/participant revision provenance complete; absence of annotation valid; researcher digest verification.
8. **Governance:** PIS/RDMP/consent and gateway approval signed off; privacy and withdrawal path documented.
9. **Operations:** transfer monitoring, researcher receipt before expiry, incident-response and key-loss plans.
10. **Data retention:** local source not automatically deleted on FileSender completion; archival verification recorded accurately.

### Important BYOD release blocker

The Phase 2 local Qwen bundle is approximately 3 GB on disk and the Pixel 9 tests showed multi-gigabyte working memory and multi-minute latency. **Do not infer it will run on all participant-owned Android phones.** Maintain a tested supported-device list and graceful `participant description without VLM` fallback. A BYOD compatibility/provisioning plan is required for participant rollout, but redesigning local AI is not a Phase 3 transfer task.

### Completion gate

```text
[ ] source capture/review/local Qwen behavior unchanged
[ ] no automatic send on Approve
[ ] no legacy data auto-upload
[ ] ethics/researcher external consent approval confirmed
[ ] investigator-provisioned per-installation credential works
[ ] AARNet account secret absent from Android APK
[ ] encrypted AARNet transfer is verified
[ ] no DGX or server VLM involved
[ ] approved event includes MP4, exact annotations and original VLM provenance
[ ] SHA-256 verified at phone, gateway and researcher receiving points
[ ] transfer survives Wi-Fi loss and process restart
[ ] FileSender completion never masquerades as archive completion
[ ] research ledger records confirmed archival separately
[ ] deletion/withdrawal behavior is tested and truthfully communicated
[ ] TalkBack and supported BYOD hardware pass manual acceptance
[ ] researcher can retrieve before expiry
[ ] final Phase 3 completion report is produced
```

### Final STOP

Produce `documentation/PHASE3_COMPLETION_REPORT.md` and stop. **Do not start DGX inference, automatic archive ingest, REDCap integration, or a new model phase without a separate instruction.**

---

# 19. Suggested Test Commands and Data Safety

Codex must adapt commands to actual project build scripts and platform. Current repository reports use Android Studio Java plus a cached Gradle distribution because the wrapper may not be executable.

Representative PowerShell commands from the prior reports:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$gradle = 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat'
& $gradle :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain
& ./tools/run-room-tests.ps1 -Serial <ACTUAL_DEVICE_SERIAL>
```

If the current machine differs, resolve paths without guessing. **Do not** use a `connectedDebugAndroidTest` task that uninstalls a data-bearing app. Run server test suites separately with disposable fixture data and mock FileSender credentials. Never execute a real-upload command containing an API secret in shell history or a report.

Suggested gateway test command pattern (exact environment/package to be finalized at Checkpoint 4):

```bash
python -m pytest server/tests
```

Use tests with realistic but wholly synthetic event records and small disposable sample MP4s. The engineering fixture must not contain participant faces, identifiable audio, clinical records or private workplace documents.

---

# 20. Phase 3 Deliverables

Required tracked outputs:

```text
documentation/PHASE3_DECISIONS.md
documentation/PHASE3_AARNET_PROTOCOL.md
documentation/PHASE3_CRYPTO_DECISION.md
documentation/PHASE3_EVENT_SCHEMA.md
documentation/PHASE3_GATEWAY_API.md
documentation/PHASE3_CHECKPOINT0_REPORT.md
...
documentation/PHASE3_CHECKPOINT10_REPORT.md
documentation/PHASE3_COMPLETION_REPORT.md
```

Plus:

- Android export/transfer repositories, migrations, workers and accessible UI.
- Gateway code, authentication, secure resumable receiver, FileSender adapter, encryption, cleanup, health and deployment config.
- Researcher verifier, receipt ledger template and SOP for decrypt/hash/archive.
- Test fixtures (synthetic), mock gateway/FileSender tests and validated real AARNet disposable transfer evidence.
- A written security/consent/hosting decision record without secrets.
- A BYOD support/compatibility matrix and list of known limits.

No actual API keys, real participant videos, clinical profiles, sensitive JSON exports, private keys or download tokens in Git or public deliverables.

---

# 21. References Codex Should Consult

## Current project reports

```text
documentation/PHASE1_COMPLETION_REPORT.md
documentation/PHASE2_CHECKPOINT8_REPORT.md
documentation/PHASE2_CHECKPOINT9_REPORT.md
documentation/PHASE2_DESCRIPTION_FLOW_REPORT.md
documentation/PHASE2_BACKGROUND_PREPARATION_REPORT.md
documentation/VLM_RUNTIME.md
```

## Official/vendor information (verify during Checkpoint 1)

- AARNet FileSender API: https://support.aarnet.edu.au/hc/en-us/articles/235972948-FileSender-API
- AARNet command-line guide: https://support.aarnet.edu.au/hc/en-us/articles/5276533711887-Use-FileSender-from-the-command-line
- AARNet FileSender overview/expiry: https://support.aarnet.edu.au/hc/en-us/articles/230387208-FileSender
- AARNet FileSender encryption: https://support.aarnet.edu.au/hc/en-us/articles/5489088169743-Use-encryption-when-sending-files
- AARNet vouchers (browser flow): https://support.aarnet.edu.au/hc/en-us/articles/115002010314-Receive-files-from-external-users-with-FileSender
- Upstream FileSender REST: https://github.com/filesender/filesender/blob/master/docs/v2.0/rest/index.md
- UTS RDMP available services / eResearch Storage: https://stash.research.uts.edu.au/default/rdmp/availableServicesList
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager

These references describe supported vendor interfaces, **not** an approval of this particular study deployment, encryption design, retention policy or gateway hosting. Verify those separately.

---

# 22. Copy/Paste Task for Codex

> Implement Phase 3 according to `PHASE3_AARNET_APPROVED_EVENT_TRANSFER_CODEX_PLAN.md`. Preserve the existing Phase 1 DAT capture and Phase 2 entirely local Qwen3-VL annotation, review, participant revision history, approval/defer/delete and TalkBack behavior. This phase ONLY adds immutable approved-event export and a secure BYOD-friendly Phone -> HTTPS Gateway -> AARNet FileSender transfer, with independent researcher download/decrypt/hash verification and a manual eResearch Storage archive procedure. Do not put the FileSender API secret in Android. Do not require Tailscale or FileSender login on participant-owned phones. Do not add DGX or remote VLM. External REDCap enrolment and fixed hidden participant profiles remain separate from the app UI. Never auto-upload because a clip is approved; use a distinct manual Send action. Preserve MP4 and annotation data on transfer failure, and never equate FileSender completion with verified archive. Start with Checkpoint 0, inspect the live repository rather than assuming old plan text is current, execute and report ONLY that checkpoint, and STOP for approval before continuing. Use harmless fixtures until FileSender, encryption, hosting and ethics/privacy gates pass. Do not clear app data or use Android test commands that uninstall a data-bearing package.
