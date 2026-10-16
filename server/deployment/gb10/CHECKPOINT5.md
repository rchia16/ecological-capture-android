# Checkpoint 5 gateway encryption and FileSender engineering test

Implementation is limited by hash guards to the exact generated synthetic three-file fixture. Actual hosted attempts and the unresolved creation outcome are recorded in PHASE3_CHECKPOINT5_REPORT.md. No participant forwarding is authorized. Android Send remains the local simulation; its network integration is a later checkpoint.

## 2026-10-16: worker-local FileSender time

Investigator requested correction within ecological-gateway rather than changing GB10's global time. The optional `--use-filesender-time` flag on `auth-check`, `send` and `reconcile` samples the public fixed-origin `/info` Date over verified HTTPS with redirects/proxies disabled. It requires a zero/absent Age, at most five seconds sample latency, timezone-aware Date and at most 300 seconds host offset. Samples last 60 monotonic seconds, refresh before later signed requests and reject inconsistent refreshes. Failed sampling never falls back to stale host time or retries a mutation. Only this client process uses the sample; OS time, other services, gateway SQLite timestamps and scientific event metadata remain untouched.

Expiry validation uses this client's corrected time; the prepared absolute expiry is never extended or recomputed on retry. This engineering option does not fix the host's TLS clock, NTP or operational monitoring. Durable host synchronization remains deferred. The earlier host-clock-set proposal is withdrawn at investigator request.

The original deployed SIF does not contain this correction. Use the reviewed local source override on this one-shot worker, then include it in the next accepted image build. Copy the four LF-normalized patch files from `server/.packages/checkpoint5-local-time/` to their corresponding existing GB10 source directories. `SHA256SUMS` records their digests. The admin helper's `--local-code` explicitly mounts only filesender_adapter.py, forward_cli.py and forwarding.py read-only over their container paths for that invocation. It does not alter the managed service, SIF or Funnel.

Read-only first check on GB10 as rchia:

```sh
sh server/deployment/gb10/checkpoint5-admin.sh /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state --local-code --secrets-dir /home/rchia/server/ecocapture-filesender auth-check --filesender-config /gateway-secrets/filesender.py.ini --authorize-synthetic-filesender --credential-policy accepted-engineering-exception --use-filesender-time
```

Expected: SIGNED_READ_SUCCEEDED, transfer_mutations 0, host_clock_changed false, uses_filesender_time true. This action never opens the gateway store and never creates or uploads a transfer. Use the same explicit time/source flags for later approved send/reconcile operations. **Do not run send yet:** current job fad7f1a1-8510-41fb-9016-2b969b07df4b is REMOTE_CREATION_PENDING with no saved remote ID; an empty available-transfer list does not resolve unfinished creation. Obtain authoritative reconciliation evidence first. This change preserves that guard.

Hosted investigator auth-check now passed with these exact four fields. A recovery alternative has been prepared locally for investigator review: `retry-creation --accept-possible-orphan --expected-ciphertext-sha256 HASH`, with the same authorization/credential/time options. This is an explicit acceptance of a possibly orphaned remote creation, NOT confirmation that nothing exists. It is synthetic-only, requires pending state/no IDs/no committed bytes/exact frozen ciphertext/unexpired policy/fresh signed owner listing, and blocks if a matching available transfer exists. It atomically records prior state/error/hash/time and remote_absence_verified=0 in forwarding_creation_recovery, then returns READY_TO_SEND without a POST. A primary-key audit limits recovery to once per job; another lost outcome cannot be reset repeatedly. Subsequent send is separately explicit. Do not execute this option before investigator accepts the possible-orphan risk for this exact job. Reviewable LF-only source patch: server/.packages/checkpoint5-creation-recovery/ (four files plus SHA256SUMS). Original clock-only patch remains a historical snapshot.

## Already validated locally

- Deterministic plain USTAR contains exactly event.json, manifest.json and video.mp4, fixed metadata and source bytes.
- Pinned age v1.3.2 encrypts to a public recipient only. Real local decryption with disposable identities verified TAR and all three original hashes; wrong identity fails.
- Separate forwarding metadata table is additive; migration test preserves all pre-existing gateway rows and introduces no forwarding jobs.
- Exact Checkpoint 5 synthetic policy AND exact fixture hashes are required; another policy or participant-like content fails closed.
- One OS-locked worker per job, quota/disk reservations, stable ciphertext/hash/key version across retry, remote ID saved immediately, known-ID recovery and ambiguous-create/finalize reconciliation.
- Plaintext source copies/TAR are removed only after acknowledged remote completion and owner metadata verification. Ciphertext stays quota-counted until explicit discard; no secure-erasure/withdrawal/archive claim.
- Mock HTTP completion stays the default. Explicit operator forwarding changes the receipt only after real acknowledged completion. API receipts never contain signed URLs, download tokens or FileSender keys.

## Approval boundary

Project plan section 17.14 requires asking before "any irreversible or billable real external transfer ... or integration of private credentials". Continuing Checkpoint 5 authorizes implementation/preparation; actual new GB10 FileSender secret injection/send is pending the concrete engineering approval. Section 17.6 requests a rotated, separately provisioned credential; prior existing-key exceptions covered the specified harmless Checkpoint 1 tests. Obtain a rotated credential or explicitly accept an engineering-only exception for this GB10 test before secret integration. Do not paste passwords, INI contents or keys into chat.

Recipient for the prepared engineering profile is `raymond.chia@uts.edu.au`, five-day expiry. Public age recipient from Checkpoint 1 is `age1k4d08sjgx3twvq2fffmyedkqxav8ttqtlu58zllz3dv0rlfk7pqs7w3u8d`, key version `checkpoint1-researcher-v1`. Private identity/backup stays off GB10 and Android. Institutional crypto/custody, host/retention/ethics and backup-key recovery remain separate pending gates; no participant use is authorized.

## 1. Copy/build a new image - no FileSender credential required

From Windows PowerShell in the repository root, with Tailscale enabled:

```powershell
scp server/.packages/gb10-gateway-checkpoint5-review.tar.gz server/.packages/gb10-gateway-checkpoint5-review.tar.gz.sha256 rchia@100.76.243.123:/home/rchia/projects/ecological-gateway/
```

On GB10 from `~/projects/ecological-gateway`:

```sh
sha256sum -c gb10-gateway-checkpoint5-review.tar.gz.sha256
tar -xzf gb10-gateway-checkpoint5-review.tar.gz
```

Use the current source-only review TAR, not the earlier Checkpoint 4 archive. From Windows, copy it and its companion SHA-256 to GB10 using ordinary SCP with Tailscale enabled. Preserve both existing SIF files and do not overwrite the accepted fixed image. Review archive paths/source checksums and unpack only the trusted code archive into `~/projects/ecological-gateway` while the old immutable SIF runs. This updates source files; it does not change the running image.

On GB10 from that project directory:

```sh
sha256sum -c SHA256SUMS
apptainer build --arch arm64 --fakeroot gateway-gb10-checkpoint5.sif server/deployment/gb10/gateway-checkpoint5.def
sha256sum gateway-gb10-checkpoint5.sif
imageTestWorkspace=$(mktemp -d /home/rchia/server/ecocapture-cp5-tests.XXXXXX)
apptainer test --containall --cleanenv --workdir "$imageTestWorkspace" /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif
```

The definition downloads only the public official age release and checks archive SHA-256 `6b8dc4333c53a5a57c9e5834e3a48f92605d7154014cd07269ff3327db5d37f4` before installing age/age-keygen executables. No key/identity/credential enters the image. Production base/apt supply-chain approval is separate; record the new SIF hash/test summary. The controller's default image remains the accepted fixed SIF.

Primary references: [official age v1.3.2 release](https://github.com/FiloSottile/age/releases/tag/v1.3.2) and [FileSender REST upload/completion contract](https://docs.filesender.org/filesender/v2.0/rest/). The ARM64 asset digest/size were read from GitHub release metadata over HTTPS; optional transparency verification is not claimed.

## 2. Explicitly select the new image for the existing service

After the native tests pass:

```sh
systemctl --user stop ecocapture-gateway.service
python3 server/deployment/gb10/gatewayctl.py install --image /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif
systemctl --user start ecocapture-gateway.service
systemctl --user status ecocapture-gateway.service --no-pager
curl --fail --max-time 10 -H 'Host: promaxgb10-4e2b.tail06a565.ts.net' http://127.0.0.1:18080/v1/health
```

Expected health remains `{"status":"ok","transport":"mock"}`. No HTTP route automatically calls FileSender; external forwarding is the separately authorized operator action below. Startup adds the empty forwarding_jobs table, preserving existing metadata. No token or old approved event is auto-enqueued. Existing 8443/Funnel settings are untouched.

To roll back the process binary, stop the unit, reinstall with `--image /home/rchia/projects/ecological-gateway/gateway-gb10-fixed.sif`, then start. This does not undo operational rows or resume pending forwarding automatically; reconcile any active remote outcome before rollback.

## 3. Provision a disposable gateway token and upload the exact synthetic fixture

On GB10, one line:

```sh
sh server/deployment/gb10/checkpoint5-admin.sh /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state provision
```

Expected: `CHECKPOINT5_SYNTHETIC_CREDENTIAL_WRITTEN`. The private gateway token is `/home/rchia/server/ecocapture-gateway-state/checkpoint5-credentials.json`, mode 0600; only its hash enters the installation allowlist. This is NOT a FileSender secret. Exclusive output creation prevents accidental duplicate provisioning.

With Windows Tailscale enabled, copy the gateway token into the previously protected test directory outside Git:

```powershell
$checkpoint5Folder = Join-Path $env:USERPROFILE '.ecocapture-gateway-tests'
scp rchia@100.76.243.123:/home/rchia/server/ecocapture-gateway-state/checkpoint5-credentials.json "$checkpoint5Folder/checkpoint5-credentials.json"
```

The directory must retain the current-user-only ACL established in Checkpoint 4. Then disable Windows Tailscale/VPN (leave GB10 Tailscale running) and run from the repository root:

```powershell
python -B server/deployment/gb10/checkpoint5_probe.py upload --credentials "$checkpoint5Folder/checkpoint5-credentials.json"
```

Expected: `CHECKPOINT5_SYNTHETIC_SNAPSHOT_UPLOADED`, one gateway transfer UUID and three hashes. Repeating this client action reuses that installation's fixed snapshot; it cannot create another logical snapshot. Keep the returned UUID for every remaining command. No FileSender call yet.

## 4. Prepare archive/ciphertext on GB10 and review the concrete job

Re-enable Windows Tailscale for SSH. Substitute the returned gateway UUID for `GATEWAY_UUID` in this one line:

```sh
sh server/deployment/gb10/checkpoint5-admin.sh /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state prepare --transfer-id GATEWAY_UUID --age-tool /usr/local/bin/age --age-recipient age1k4d08sjgx3twvq2fffmyedkqxav8ttqtlu58zllz3dv0rlfk7pqs7w3u8d --key-version checkpoint1-researcher-v1 --evidence-out /gateway-state/checkpoint5-expected.json
```

Expected: READY_TO_SEND, non-secret plaintext/ciphertext sizes and SHA-256, remote ID null. This freezes public-key version, configured recipient and requested expiry. Evidence contains only synthetic source hashes/public policy, never keys/tokens. Do not blindly repeat prepare or generate a second fixture: status/reconciliation preserve the existing one. The evidence file refuses overwrite; after a failed prepare, keep that file and use a fresh evidence filename when explicitly retrying the same UUID.

Copy the expectations to the protected Windows test directory:

```powershell
scp rchia@100.76.243.123:/home/rchia/server/ecocapture-gateway-state/checkpoint5-expected.json "$checkpoint5Folder/checkpoint5-expected.json"
```

Report READY_TO_SEND and safe hashes/size. This is the concrete review point before any private FileSender provisioning or irreversible send. Ciphertext is randomized: the actual GB10 ciphertext hash differs from the separately prepared local code-review artifact.

## 5. Only after explicit approval: separately provision the FileSender INI

Use a private directory outside the project and SIF, proposed `/home/rchia/server/ecocapture-filesender`, mode 0700, with `filesender.py.ini` owned by rchia and mode 0600. Inject/copy the already prepared account INI through an approved method; do not display its contents. Keep base_url exactly `https://filesender.aarnet.edu.au/rest.php` and user fields username/apikey. The INI is mounted read-only only for the one-shot worker. The running HTTP service never binds the secret directory. No researcher private identity belongs there.

Do not execute the copied original tools/filesender.py; the hardened adapter in the image implements the signed protocol and redacts transport errors. Do not turn on HTTP/debug/access logging.

## 6. Only after explicit approval: send exactly that one encrypted job

After concrete recipient/expiry/key/credential approval, run as one line. `rotated` records the requested credential basis; use `accepted-engineering-exception` only if the investigator explicitly approves that exception for this GB10 test:

```sh
sh server/deployment/gb10/checkpoint5-admin.sh /home/rchia/projects/ecological-gateway/gateway-gb10-checkpoint5.sif /home/rchia/server/ecocapture-gateway-config /home/rchia/server/ecocapture-gateway-state --secrets-dir /home/rchia/server/ecocapture-filesender send --transfer-id GATEWAY_UUID --filesender-config /gateway-secrets/filesender.py.ini --authorize-synthetic-filesender --credential-policy rotated
```

The worker queries /info, creates one single-object transfer, saves its remote ID immediately, uploads bounded ciphertext chunks at explicit offsets, completes file/transfer and verifies owner identity/recipient/expiry/size metadata. FILESENDER_COMPLETE is set only after acknowledgement or reconciled availability. It then removes the staged plaintext three-file copies/TAR; the original phone data is not involved. Ciphertext remains quota-counted for retry/receipt until explicit discard. Completion is not researcher verification or archive.

Report fixed status and remote ID/expiry/ciphertext hash only. Never report API keys, signed URLs, puid/roundtrip tokens or recipient download URLs. The HTTP owner receipt exposes only non-secret completion facts; the one-shot worker prints safe operational status.

## 7. Researcher receipt, decrypt and independent three-file verification

Download `approved-event.tar.age` from the FileSender notification on the researcher workstation; confirm exactly one intended transfer/notification, requested recipient/expiry and safe error-free download. In Windows PowerShell from the repository root, using a new output directory:

```powershell
$env:PYTHONPATH = (Resolve-Path server).Path
python -B -m ecocapture_gateway.verify_researcher --ciphertext C:/Users/rchia/Downloads/approved-event.tar.age --expected "$checkpoint5Folder/checkpoint5-expected.json" --age-tool tools/phase3-protocol/artifacts/age.exe --identity C:/Users/rchia/.ecocapture-keys/checkpoint1-key.txt --output C:/Users/rchia/Downloads/ecocapture-checkpoint5-check
```

Adjust only the actual downloaded filename/fresh output directory if necessary. The private identity is read by age on your workstation; the Python verifier never reads/prints its contents. Ciphertext hash is checked before decrypt; TAR hash checked before extraction; only three regular fixed members are allowed; independent manifest/schema/source hashes must match trusted expectations.

Expected: CHECKPOINT5_RESEARCHER_SHA256_VERIFIED and archived:false. Report only this summary and notification count/expiry. Key-backup recovery and institutionally approved receiving/archive procedure remain separate. No eResearch archive is performed.

## Retry/reconciliation and cleanup

- Do not repeat remote creation or rebuild/re-encrypt a READY job. Explicit send retry uses the same ciphertext and known transfer/file IDs. Same-offset PUT retry after ambiguous chunk acknowledgement must be verified on the actual AARNet account; local stub tests pass but are not that remote proof.
- REMOTE_CREATION_PENDING with no remote ID means the POST may have succeeded. Use the owner web UI to find the exact subject `EcoCapture encrypted synthetic GATEWAY_UUID`, object size/name and recipient. Reconcile that existing ID only; never create another blindly. CLI `reconcile --adopt-remote-transfer ID` verifies matching signed owner metadata before saving it.
- TRANSFER_COMPLETION_PENDING with no observed status/ack requires investigator inspection. `reconcile --confirm-remote-available` is an explicit operator attestation after confirming availability in FileSender, not a way to suppress an error. The adapter checks identity/recipient/size and then avoids replaying completion email. If API exposes available state, reconciliation is automatic for a fully uploaded known-ID job.
- Read-only local status: same helper with `status --transfer-id GATEWAY_UUID`, no secret bind. Send success plus cleanup failure remains durably complete; retry cleanup without creating another transfer.
- After researcher verification, `discard --transfer-id GATEWAY_UUID` removes only this job's checked local staging/ciphertext and releases its reservations; receipt hashes/IDs persist. It never claims remote deletion, secure erasure, withdrawal or archival. Ambiguous active outcomes cannot be discarded blindly.
- Retire the disposable gateway token with the helper action `retire`, then remove only the named external test credentials under the approved private-file procedure. FileSender account key custody is separate; do not delete its account configuration while reconciling an active remote job.

## Manual evidence required before Checkpoint 5 PASS

1. Native new SIF tests/hash and healthy managed service, preserving existing 8443/Funnel. No phone-app regression run is needed for this server-only change.
2. Public upload and GB10 READY_TO_SEND hashes/evidence; no secret integration until concrete approval.
3. One authorized encrypted FileSender transfer with acknowledged completion, actual expiry and notification count; retry/reconciliation evidence without duplicate logical transfers.
4. Independent researcher ciphertext/TAR/three-file hashes and decryption using the researcher-held private identity.
5. Local plaintext removal/ciphertext discard and synthetic token retirement; retain earlier backup/custody/rotation/governance pending items until actually resolved.

**STOP:** do not start Checkpoint 6 or send participant data. Before approval, all live send/provision steps above remain instructions, not executed actions.
