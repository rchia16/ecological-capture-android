# Phase 3 decision log

Date opened: 2026-10-12 (Australia/Sydney).
Scope: Checkpoints 0 through 5 of `PHASE3_AARNET_APPROVED_EVENT_TRANSFER_CODEX_PLAN.md`. Dated entries preserve their historical state; `PHASE3_OUTSTANDING_CHECKS.md` is the current running checklist.

2026-10-16 Checkpoint 5 engineering acceptance: investigator confirmed final notification/recipient/expiry checks after actual encrypted FileSender transfer 3649987, researcher primary-key decryption/ciphertext/TAR/three-source hash verification, completed-job local discard and disposable gateway credential revocation. Marked synthetic engineering PASS; no numeric email count invented. Worker-local FileSender time correction accepted without global host-clock change. Pinned SIF refresh, durable host synchronization, backup-key recovery, secret rotation/exception lifecycle and institutional governance remain on the running checklist. archived false. Stop pending explicit Checkpoint 6 continuation; no participant use authorized.

2026-10-16 clock scope: investigator requires correction within ecological-gateway and rejects the proposed global host-time change. Controlled signed reads already proved host-time HTTP 500 versus server-time HTTP 200 with the same valid credentials. Added explicit --use-filesender-time to the one-shot synthetic FileSender client: bounded fresh fixed-origin HTTPS Date, monotonic advancement/refresh, no process-wide or OS-clock override, no mutation retries/fallback. Frozen expiry is checked with corrected client time and is not extended. Read-only auth-check bypasses Store entirely. Current SIF may use explicit --local-code read-only source overlays pending accepted rebuild. Tests: 62 passes/one Windows skip. Durable host NTP remains deferred; existing REMOTE_CREATION_PENDING job must be reconciled before any new transfer POST. No secret values or private researcher identity read by agent, no remote execution, no Checkpoint 6.

Checkpoint 5 continuation: investigator explicitly instructed continuation. Prepared the full local exact-synthetic encrypt/forward path, pinned age v1.3.2, new ARM64 image definition, additive operational ledger/receipt handling, public fixture upload and independent researcher verifier. Mock HTTP completion remains default and no participant payload passes the forwarding guard. Existing public recipient/key version and five-day engineering recipient profile carried forward for review; existing private identity/INI were not read. Concrete local ciphertext is ready, no remote ID or upload. New GB10 private-credential integration/actual encrypted transfer is pending review/approval under section 17.14; rotation or a new host-specific engineering exception remains explicit. Researcher backup/custody and institutional hosting/crypto/ethics/retention remain unapproved/deferred. Evidence: PHASE3_CHECKPOINT5_REPORT.md and server/deployment/gb10/CHECKPOINT5.md. Checkpoint 6 not started.

2026-10-14 GB10 follow-up: investigator proposed `rchia@100.76.243.123`, requested an Apptainer environment and required all code to remain local before anything runs on GB10. Prepared source-only packaging, a native ARM64 image definition, loopback nginx/gateway launcher, external config/state binds and separately invoked Funnel publication. No SSH connection/password access or remote/public mutation. GB10 is a proposed engineering host, not an institutionally approved participant host. Future SSH can use an interactive password prompt or an SSH agent; no password belongs in this project/image. Native build and actual proxy-path validation remain pending. See `server/deployment/gb10/README.md`.

2026-10-14 Checkpoint 4: investigator approved continuation and explicitly chose "Host undecided - finish the local gateway". Completed the local HTTPS mock gateway, independent hashed installation credentials/revocation, owner-scoped resumable three-file upload, verification, quotas and cleanup. Gateway suite: 23 passed, one Windows symlink-privilege skip; actual loopback TLS smoke: eight checks passed, no public deployment or FileSender calls. Hosting templates are review artifacts only. Public domain/host/operator, institutional security acceptance and phone internet checks remain pending; Checkpoint 4 is PARTIAL and Checkpoint 5 is not started. No participant credentials, automatic subject routing or Android networking were added. Details: `PHASE3_CHECKPOINT4_REPORT.md`, `PHASE3_GATEWAY_API.md`, `server/README.md`.

2026-10-14 follow-up: investigator explicitly remains at Checkpoint 3, requested setup access again and confirmed failure notices should stop when preparation is disabled. Added reusable debug setup access, preference-aware stale failure suppression and intentional skipped worker handling. Corrected build installed; 73 unit passes per variant, 76 phone passes/7 opt-in skips, protected-row/file retention passed. Manual TalkBack verification is pending. No Checkpoint 4 work, profile/subject routing, inference-policy change or external transfer occurred.

Current scope update (2026-10-13): investigator explicitly authorized Checkpoint 2 and deferred backup-key recovery. The running checklist is `PHASE3_OUTSTANDING_CHECKS.md`; Checkpoints 0/1 retain their unfinished evidence. Checkpoint 2 is local-only and does not authorize participant transfer. Engineering snapshot hashes will first be persisted in app-private atomic file records; Room snapshot/job records and their migration remain Checkpoint 3 work.

Checkpoint 3 authorization (2026-10-13): investigator said “Looks good” and explicitly instructed implementation of the next checkpoint. Added Room 3 migration, empty-by-default snapshot/job tables, explicit local simulated Send UI and shell-protected debug provisioning for selected disposable clips only. Production eligibility remains disabled and subject code null; no participant credentials, subject-based routing or network forwarding were introduced. Earlier manual inspection/recovery items remain pending unless specifically evidenced. See `PHASE3_CHECKPOINT3_REPORT.md` and `PHASE3_TRANSFER_STATE.md`.

Checkpoint 3 final validation: builds and 65 JVM tests per variant passed; Pixel 9 suite 76 passes/7 opt-in skips, including 10 new migration/simulation tests. On-device typed row hashing preserved all 13 clips, 13 annotations and 20 VLM runs across actual 2 -> 3 migration and regression; new tables empty. All 24 recording files and two models retained exact hashes. Debug chooser launched without provisioning or selecting any existing recording. Checkpoint 3 remains PARTIAL pending the new physical/accessibility checklist.

Checkpoint 2 implementation update: local snapshots and exact scientific schemas implemented, debug inspection installed. Final offline builds and 65 JVM tests per variant passed, independent Python tests 14 passed, Pixel 9 suite 66 passed/6 opt-in skipped. Protected main DB/recording/model hashes matched across installation and regression. Three disposable-clip manual inspections are pending, so Checkpoint 2 remains PARTIAL. Source approval time is not separately stored and exports as null. Subject code defaults null; no provisioning or destination routing was added. See `PHASE3_CHECKPOINT2_REPORT.md` and `PHASE3_EVENT_SCHEMA.md` for evidence and ready manual commands.

## Fixed project constraints

- Preserve `com.rchia.ecocapture.phase0`, its signing identity, existing recordings, Room data and locally provisioned models.
- Preserve DAT 0.9.0 capture and entirely local Qwen3-VL-4B inference, prompt, provenance and participant revision history.
- Approval is a local decision. Transfer will require a separate explicit Send approved recording action; legacy approvals must never enqueue uploads on upgrade.
- BYOD phones communicate with an approved HTTPS gateway using individual installation credentials. FileSender credentials remain server-side. No participant VPN, FileSender login or remote inference.
- External enrolment/profile provisioning remains separate. No demographics, clinical fields or REDCap identity in exports or transfer requests.
- FileSender completion, researcher verification and verified archival are separate facts. Initial eResearch Storage archival is manual.
- Real participant uploads remain unavailable. Checkpoint 0 adds no transfer implementation, credentials or infrastructure.

## Pending investigator decisions and evidence

Every item below is pending; this log does not constitute ethics, institutional security or hosting approval.

| Decision | Confirmation or evidence needed | Gate |
| --- | --- | --- |
| Baseline acceptance | Current glasses capture/review/description/optional AI checks; TalkBack and enlarged-text results; retained data/model inventory | Checkpoint 0 |
| Disposable glasses fixture | Investigator identifies a harmless, disposable HEVC glasses recording; do not infer that repository or phone footage is disposable | Checkpoint 0 |
| Backup and recovery | Approved location/access/retention for any recovery copy; include Room/WAL consistency, recordings and model reprovisioning; test recovery without overwriting the research installation | Baseline and release |
| Ethics/PIS/RDMP | Written coverage for identifiable egocentric video, gateway processing/hosting locations, temporary FileSender transfer, recipients and researcher receipt | Before participant transfer |
| Gateway host and operator | Approved service/domain, TLS, access controls, encrypted staging, monitoring, incident response and named operational owner | Before deployment |
| FileSender account | Plan reports prior exposure and requires rotation; actual exposure location is not independently verified. Investigator confirms secret has not been rotated. Provision separately and verify actual account API capability with harmless files | Checkpoint 1 |
| Recipient and expiry | Approved recipient, actual expiry limits, notification behavior/volume and operator responsible for download before expiry | Checkpoint 1 |
| Encryption and recovery | Preferred proposal is an age encrypted archive using an investigator public key on the gateway; selection, interoperability and private-key backup/recovery remain unapproved and untested | Checkpoint 1 |
| Provisioning | Study-issued pseudonym, installation UUID/token, external policy version and eligibility; independent gateway allowlist/revocation | Before participant credentials |
| Retention and deletion | Ratify staging/retry/metadata retention, cancelled-upload handling, withdrawal of delivered footage and handling of researcher copies; proposed 24-hour/7-day windows are not approved policy | Before participant transfer |
| Researcher receiving procedure | Approved workstation, controlled ledger, independent decrypt/hash verification, manual eResearch copy and destination rehash | Checkpoint 8 |
| BYOD support | Tested phone/Android matrix, model storage/memory provisioning, participant-description fallback and background limits | Before rollout |

## Baseline observations

Repository baseline: `208c55a` (2026-10-09), with implementation changes in `2c5d610` (2026-10-02). Room is version 2; app version is still `0.1.0-phase0` / code 1. Automatic batch preparation and AI Settings changes are newer than the checkpoint validation described in parts of the Phase 2 reports. They require current manual acceptance.

On 2026-10-12, offline debug/release builds and 52 unit tests per variant passed. The connected Pixel 9 reported Android 16/API 36 and user `RUNNING_LOCKED`. App-private inventory could not be read; replacement installation and instrumentation were deferred to preserve the ability to establish a before/after baseline. Physical glasses, TalkBack and backup recovery were not validated.

## Checkpoint 1 continuation and feasibility

On 2026-10-12 the investigator explicitly instructed "continue to next check point" after the partial baseline report. This authorizes Checkpoint 1 work; it does not establish that the outstanding baseline checks passed or that institutional participant-upload approval exists.

- Public AARNet `/info` returned `upload_chunk_size = 12582912` bytes over validated HTTPS. Signed account requests, account identity, expiry/recipient permissions, notifications and researcher download remain untested.
- Initially no supplied `filesender.py` was located. Investigator subsequently provided `tools/filesender.py`; source review and three isolated synthetic defect reproductions completed. Its hash is recorded in the protocol report. The copied INI was ignored without reading; rotated-credential confirmation and reviewed credential-use harness remain pending.
- Two wholly synthetic files and an independent SHA-256 verifier are ready. Seven verifier tests passed.
- Official age v1.3.2 passed a local eight-check synthetic encryption/decryption/corruption/recovery proof. This supports the proposed mechanism's local feasibility; institutional crypto selection, independent workstation recovery and encrypted FileSender round trip remain pending.
- No credential integration or external upload was executed. Live test requires the supplied script, rotated-secret confirmation, approved recipient/expiry and concrete transfer authorization as required by plan section 17.14. The account secret must remain outside Git/chat/logs.

Evidence: `PHASE3_AARNET_PROTOCOL.md`, `PHASE3_CRYPTO_DECISION.md`, `PHASE3_CHECKPOINT1_REPORT.md`.

Investigator subsequently confirmed the API secret has **not** been rotated and asked where it was exposed. The exposure assertion originates in plan section 4.2 ("pasted into development material") and Checkpoint 1 task 1; this agent has not identified an actual disclosure location, read the INI, or observed the real key in logs. Earlier references to an exposed secret repeated the plan's assertion, not an independent finding. The supplied client's logging defects demonstrate possible disclosure, not evidence of an actual historical leak.

Investigator confirms the external INI is ready at `C:/Users/rchia/.filesender/filesender.py.ini`; existence verified without reading contents. A reviewed read-only auth probe and nine mock tests are prepared; the combined Python suite passes 16 tests. Its actual-account run is pending resolution of section 17.6's rotated-credential requirement. Investigator specifies `raymond.chia@uts.edu.au` and five days for the two synthetic test files. This is the engineering recipient/expiry choice, not an institutional production-recipient approval. No actual credential integration, signed request, transfer or upload has occurred.

STOP. Finish the pending Checkpoint 1 account/round-trip checks and baseline acceptance; do not begin Checkpoint 2 without explicit approval.

## Authorized read-only authentication result

Investigator explicitly approved use of the existing unrotated key for the read-only authentication probe after section 17.6's rotation requirement was explained. `checkpoint1_auth_probe.py` then loaded the external INI internally, queried public `/info` and signed `GET /transfer/@me`, returning `SIGNED_READ_SUCCEEDED` with `upload_chunk_size = 12582912`, zero transfer mutations and zero uploads. No secrets, account identity or transfer metadata were printed. The exception is limited to this read-only check; it does not authorize upload, establish key rotation or pass the participant-release gate. Recipient remains `raymond.chia@uts.edu.au`, proposed expiry five days; actual transfer/download checks remain pending.

## Explicit synthetic-transfer instruction - 2026-10-13

Investigator reported presented manual checks "all fine" and instructed "send a test transfer". This explicitly extends current-credential authorization to the specified harmless engineering transfer. Reviewed sender completed transfer **3647625** to `raymond.chia@uts.edu.au`; server expiry is **2026-10-18 14:11:01 Sydney**. Automated recipient-route download verified both source hashes. No real participant recording or annotation was used. Missing status field/same-origin download redirect were reconciled against the existing transfer, without creating another transfer. Human notification/receiving evidence and the encrypted independent workstation proof remain pending. This is not institutional participant-upload or archival approval.

Investigator then confirmed notification/expiry/download checks and provided manual PowerShell SHA-256 values. Both downloaded files at `C:/Users/rchia/Downloads` were read by exact filename and verified against source hashes/lengths. Human plaintext receipt is confirmed. The supplied ZIP hash is recorded as container metadata without a source ZIP comparison. Exact notification count, encrypted FileSender/workstation verification and key recovery remain pending; no additional transfer was sent.

Investigator subsequently reported key/hash creation. Public recipient validated at `C:/Users/rchia/.ecocapture-keys/checkpoint1-public.txt`; private identity contents were not read. Prepared a two-fixture TAR encrypted to that public key, 12598472 bytes, SHA-256 `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`. Exact encrypted sender profile and 28 passing Python tests are ready. Ciphertext has not been uploaded. Protected backup location, actual researcher decryption/recovery and concrete encrypted-transfer authorization are pending. See the crypto decision document for the local manual procedure; no production approval is inferred.

Investigator then explicitly authorized "send". Executed the prepared ciphertext upload once after checking the interrupted turn had not created an encrypted-transfer job. FileSender transfer **3647689** completed, recipient `raymond.chia@uts.edu.au`, server expiry **2026-10-18 14:49:22 Sydney**. This supersedes the preceding pending-upload statement. Researcher ciphertext download/hash, decryption and backup-key recovery remain pending; private identity was not read by Codex/sender, and no archive claim is made.

Investigator subsequently supplied successful ciphertext hash, researcher-local decryption, matching TAR hash and both original-file verification results. Read-only rechecking of named synthetic outputs passed. Actual encrypted transfer/decryption/hash interoperability is verified. Backup-key recovery and protected custody/storage/approval decisions remain pending; the private identity was not read by Codex and no new checkpoint or archive action began.
