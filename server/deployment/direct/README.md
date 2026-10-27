# Direct GB10 uploads and private research catalogue

This replaces the proposed Firebase/FileSender route for new sends. Researchers organise shared originals on GB10 through a private web catalogue; downloading creates a working copy. GB10 is currently the working store. An approved independent archive or backup destination has not been allocated.

## Flow

1. A researcher issues a participant enrolment code using the local GB10 administration CLI. The code expires after 24 hours, binds to one installation, and permits retries only with that installation's original token. The phone creates its bearer token before enrolling; GB10 stores a hash.
2. The participant approves a recording and explicitly chooses Send. The phone freezes an immutable snapshot containing `video.mp4`, `event.json` and `manifest.json`, then encrypts it with age to the GB10 and researcher recovery public keys. Neither private key is in the APK.
3. WorkManager uploads over unmetered connectivity. The authenticated `/v2` API persists chunk offsets and ciphertext hashes, supports exact replay, and isolates installations. Interrupted jobs resume the same ciphertext. Approval alone does not enqueue a send.
4. A separate local worker decrypts and checks the ciphertext, archive entries, schemas, source hashes and participant/event/snapshot linkage. It atomically publishes originals under `study/participant/YYYY-MM-DD/event/snapshot/`. The phone sees `AWAITING_GB10`, then `VERIFIED_GB10` or `NEEDS_ATTENTION`.
5. Researchers connect to Tailscale and open the private catalogue. Shared event tags/notes apply across versions; review status belongs to each snapshot. Concurrent edits are rejected with a refresh prompt. Changes have an actor/time/revision audit. Original participant annotations are immutable.
6. Downloads stream an exact three-file ZIP. Filtered CSV includes catalogue metadata, shared tags and notes. Downloading or receiving never marks a snapshot archived.

## Addresses and deployment boundary

- Public upload: `https://promaxgb10-4e2b.tail06a565.ts.net/v2`
- Private catalogue: `https://promaxgb10-4e2b.tail06a565.ts.net:9443/`
- Public proxy `127.0.0.1:18082`: `/v2/` → upload listener `8002`; `/v1/` → existing gateway `18080`; everything else returns 404. Researcher identity headers are stripped.
- Private proxy `127.0.0.1:18083` → distinct researcher listener `8003`. Tailscale Serve supplies the identity; an explicit local researcher allowlist is also required. There are no researcher routes in the public application.
- Port 9443 uses private Serve, never Funnel. Existing ports 8443 and 10000 are preserved. Preflight found 8443 already public; it is unsuitable for this catalogue.
- All four application/proxy listeners bind to loopback. Trusted local host operators can access these proxies; this is a single trusted host deployment.

Tailscale Serve supplies and protects identity headers; a Funnel route is public and cannot serve as the researcher authentication boundary. See [Serve documentation](https://tailscale.com/docs/features/tailscale-serve).

## Android build

Build `crypto/android-age` following its README to generate the ignored `app/libs/ecocrypto.aar`, then:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest -PgatewayTransfer=true
```

The default endpoint above is fixed in the APK. An optional `gateway_api_endpoint` in ignored `local.properties` must be HTTPS and end in `/v2`. `gatewayTransfer` and `firebaseTransfer` cannot both be enabled. Without either property, the normal simulation build is unchanged. Enrol through the app's study setup screen or its `ecocapture://gb10-enrol?code=...` link. A QR carries only the enrolment code, never an endpoint or key policy.

No APK has been installed as part of this deployment. Preserve the data-bearing app; do not uninstall it, clear its storage, or reset its database. Before participant use, complete device migration and physical-device upload/cancellation/restart checks with an approved test recording.

## Runtime and configuration

`direct.def` builds a standalone ARM64 Apptainer image with age 1.3.2 checked against its pinned release SHA256, a buildless private frontend and independent public/private ASGI apps. `container_runtime.py` supervises its own public, private, verifier and nginx children, fails on occupied ports and never kills unrelated listeners.

Configuration is a read-only bind at `/direct-config/direct.json`; state is `/direct-state`; GB10's identity is a separate read-only `/direct-secrets/gb10.agekey` bind. None belong in source control or the image. The JSON has these fields:

```json
{
  "study_id": "approved-study-id",
  "recipients": ["REPLACE_WITH_GB10_PUBLIC_AGE_KEY", "REPLACE_WITH_RECOVERY_PUBLIC_AGE_KEY"],
  "key_versions": ["gb10-v1", "recovery-v1"],
  "age_tool": "/usr/local/bin/age",
  "identity": "/direct-secrets/gb10.agekey",
  "gateway": {
    "storage": "/direct-state/private",
    "allowed_hosts": ["promaxgb10-4e2b.tail06a565.ts.net"],
    "max_installation_bytes": 1073741824,
    "max_total_bytes": 10737418240
  }
}
```

The placeholder keys deliberately fail validation: replace them with two distinct real public age recipients. A study ID is pinned to its database; changing studies requires separate state. Generate the GB10 identity on GB10, with directory mode 0700 and key mode 0600. The researcher recovery private key stays with its approved custodian, outside GB10. Establish institutional key custody, backup and access arrangements before a real study.

Each encrypted package is limited to 256 MiB. Quotas reserve four times ciphertext size for receiving and verification copies; completed packages remain quota-counted. The example limits therefore allow at most 256 MiB per installation before an operator increases capacity. The verifier also keeps a conservative free-space margin. Monitor capacity and host availability; there is no automatic purge or archival.

## Deploy a new installation

Package from the repository root using `python server/deployment/direct/package_source.py NEW-OUTPUT.tar.gz`. It selects source files explicitly, excludes local files/build outputs, writes per-file SHA256SUMS and prints the bundle SHA256. Verify that digest after transfer, extract to a new directory, then verify every manifest entry before building:

```sh
apptainer build /home/rchia/projects/ecological-direct/direct.sif server/deployment/direct/direct.def
```

Prepare private config/state/secrets directories. The example user service expects these under `/home/rchia/projects/ecological-direct`. Install only the new `ecocapture-direct.service` unit; leave the accepted existing gateway unit and image running. Reload user systemd and start the new unit. Inspect its status, then run `configure_routes.py` without arguments for read-only readiness checks. Applying routes requires `--apply --backup NEW-BACKUP.json`; the helper validates both loopback boundaries, preserves other routes, and rolls back its changes on an unexpected post-change configuration.

Rollback only these new routes: restore Funnel 443 to `http://127.0.0.1:18080`, remove private Serve 9443, and stop/disable the new direct service. Do not reset all Tailscale routes. Preserve direct state and keys for recovery and any received data.

## Local administration

Run these inside the image with the three binds above. The runtime forwards administration commands to `ecocapture_direct.cli`. There are no browser admin or researcher-provisioning endpoints.

```sh
# Only after the account has been confirmed for this study:
apptainer run [BIND-OPTIONS] direct.sif researcher-access --login CONFIRMED-LOGIN
apptainer run [BIND-OPTIONS] direct.sif researcher-access --login CONFIRMED-LOGIN --disable
apptainer run [BIND-OPTIONS] direct.sif issue-enrolment --participant PARTICIPANT-CODE --output /direct-state/NEW-PRIVATE-ENROLMENT-DIRECTORY
apptainer run [BIND-OPTIONS] direct.sif revoke --installation INSTALLATION-UUID
apptainer run [BIND-OPTIONS] direct.sif retry-verification --snapshot SNAPSHOT-UUID
```

Enrolment output contains a sensitive code and QR. Deliver it privately and do not log it. Revocation blocks further installation API use. Cancellation stops incomplete uploads where possible; it cannot withdraw a package already accepted by GB10. Phone originals remain on the phone.

## Archive later

Once an approved UTS eResearch share is allocated, an operator can copy each complete verified snapshot directory to that share, preserving its three source files. Use a separate mounted destination, verify access and institutional approval, then run:

```sh
apptainer run [BIND-OPTIONS-INCLUDE-APPROVED-SHARE] direct.sif record-archive \
  --snapshot SNAPSHOT-UUID --destination /approved-share/SNAPSHOT-DIRECTORY \
  --label UTS-eResearch --confirm-approved-destination
```

This revalidates the copied manifest, schema and every source hash, rejects the original directory and hardlinks, and records an audit only after success. The explicit destination flag is an operator attestation, not automatic institutional approval. Nothing is copied or deleted by this command. Independent backup, retention and disaster recovery remain separate operational requirements.

## Synthetic deployment, 19 October 2026

The isolated GB10 deployment uses study `synthetic-direct-v1`, disposable keys and synthetic content only. Its source bundle SHA256 is `e5dccf2c43e15369e77b37d29d8a9a152bcf1a1cc68c95dd1f88cb89c9532450`; its SIF SHA256 is `1d81310bff3cb7fd0331ba3dbf441e04886bb6458751f14952a6667dea7d01f5`. The later route helper and local synthetic probe are separate validation tools; the serving code in this image matches that checked bundle.

- New image built successfully on GB10; its 14 direct tests passed with the optional Go interop test skipped there.
- Workstation direct/deployment tests passed, including actual phone-library age encryption, archive-copy validation, restart recovery, conflict checks and original-byte preservation. Two frontend tests passed.
- Final full Python regression ran 93 tests successfully with one Windows symlink-privilege skip.
- Gateway-enabled APK assembly and unit tests passed; gateway instrumentation sources compiled. The default simulation build also passed all 73 Android unit tests and compiled instrumentation sources. No physical-device instrumentation tests ran.
- A copy of the gateway APK is at `app/build/outputs/apk/debug/gateway-transfer-debug.apk`, SHA256 `f3b4c9b5185eb52bca8f214ed156432e552bb4aa8afb2c6dbab85f3c9f2c41fb`. It has not been installed.
- Hosted HTTPS synthetic upload, exact chunk replay, GB10 decryption/schema/hash verification and durable `VERIFIED_GB10` receipt passed. Public catalogue access was blocked. Receipt remained `archived=false`.
- Existing gateway and Tailscale ports 8443/10000 stayed unchanged. Port 9443 is private with no Funnel permission.
- At initial deployment the researcher allowlist was empty and the hosted private endpoint correctly returned 403. Automatic approval review initially rejected granting the unconfirmed account `aiwithgbten@gmail.com`. On 20 October 2026, the user explicitly authorised researcher access for `raymond.chia@uts.edu.au` and then `aiwithgbten@gmail.com`; both grants were applied and verified in the synthetic catalogue. The service remained active and port 9443 remained private. Hosted researcher sign-in remains pending; the equivalent private API and ZIP paths passed locally.

The synthetic recovery private key is in the workstation's private temporary test directory; it is not a study recovery key. Do not enrol participants into this synthetic deployment. No Firebase backend or institutional billing account was created, and no FileSender credentials were used for this route.

## Phone upload acceptance, 22 October 2026

Updated-version acceptance also passed (investigator-reported): after editing a disposable recording's description and sending it again, both snapshots remained downloadable under All snapshots, event tags/notes were shared, and each snapshot retained its own review status. This confirms the requested manual versioning checks; it does not imply independent reinspection of downloaded source bytes or archival verification.

The investigator confirmed that the synthetic phone upload/check passed after receiving enrolment instructions for `SYNTHETIC-PHONE-01` and instructions to check the recording status on the phone and in the private catalogue. The investigator then confirmed that the downloaded ZIP's video plays, that `video.mp4`, `event.json` and `manifest.json` are present, and that tags, notes and review status save correctly. Following instructions to interrupt a disposable upload by turning Wi-Fi off, restart the app, reconnect and confirm completion, and test cancellation separately on another disposable upload, the investigator confirmed these checks passed as well. These are user-reported acceptance results; no new independent inspection of those phone receipts, catalogue records, downloaded packages or interruption/cancellation traces was performed. Study keys/custody, independent backup/archive and participant-use governance remain outstanding; no archival verification is inferred from downloading and playing the package.
