# Phase 3 decision log

Date opened: 2026-10-12 (Australia/Sydney).
Scope: Checkpoints 0 and 1 of `PHASE3_AARNET_APPROVED_EVENT_TRANSFER_CODEX_PLAN.md`.

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
