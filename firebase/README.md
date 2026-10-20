# Encrypted Firebase transfer

This is an opt-in alternative to the existing gateway/FileSender engineering path. Normal Android builds still simulate transfers. No project, bucket, account, keys, deployment or participant enrolment is created by checking out this code.

The investigator has identified target project **ecological-capture** (project number **605508959167**, parent organisation **uts.edu.au**) and selected Sydney (`australia-southeast1`). Authenticated preflight found no database or storage bucket and billing disabled. The existing Hosting site is `ecological-capture.web.app`. The Android application was registered as `1:605508959167:android:a949c3495d9cbfcbf4ff0a`; no web app or backend was deployed. The investigator has no billing account and is considering the existing Tailscale Funnel/GB10 gateway instead. Keep this Firebase implementation opt-in and do not provision billable resources without resolving that choice.

```text
Explicit Android Send -> immutable snapshot -> age-encrypted TAR -> Firebase Storage
                                                                    |
                                                           completion API
                                                                    |
                                                         Firestore catalogue
                                                          /               \
                                                GB10 pull service    researcher website
                                                decrypt + verify     metadata + CSV
```

## What is implemented

- Streaming phone encryption for two independent X25519 age recipients: GB10 and researcher recovery. Neither private key is included in Android or the cloud. The existing scientific export format is unchanged.
- Installation enrolment by an expiring QR/code, Firebase custom authentication, explicit Send, unmetered-network WorkManager uploads, immutable ciphertext retries and periodic receipt refresh.
- A Python Cloud Function with transactional registration/enrolment, ciphertext size/hash verification, owner-scoped status, study-scoped researcher catalogue and a separate Google identity-authenticated receiver interface.
- Deny-by-default Firestore rules and Storage rules that permit only registered, exact-size, owner-scoped object creation. Clients cannot overwrite or delete objects or write verified receipts.
- An outbound-only GB10 receiver with generation-pinned downloads, authenticated age decryption, existing schema/hash validation, atomic publication, a local SQLite acknowledgement ledger and crash recovery.
- An accessible, responsive catalogue with approved Google sign-in, study/participant/date/status filters, GB10 paths and CSV export. The browser receives no video, annotations, download links or private keys. Sessions use in-memory persistence.

Cloud metadata contains pseudonymous study/participant codes, recording date, event/snapshot IDs, byte counts, ciphertext/manifest hashes, key version labels and receipt timestamps. Researcher allowlists contain email hashes; Firebase Authentication still holds account identities. Choose the project, service locations, account access and retention policy accordingly.

## Local development and checks

Use Node 22, Python 3.12, Java 21, Go 1.26 or newer and the existing Android SDK/NDK. Dependencies are pinned in `dashboard/package-lock.json`, `functions/requirements.txt`, and `../crypto/android-age/go.mod` / `go.sum`. No Firebase Analytics or Crashlytics SDK is included.

From the repository root:

```powershell
python -m venv firebase/functions/.venv
firebase/functions/.venv/Scripts/python.exe -m pip install -r firebase/admin-requirements.txt
python -B -m unittest discover -s firebase/tests -p test_contract.py -v
$env:PYTHONPATH = 'server'
server/.venv/Scripts/python.exe -B -m unittest discover -s server/tests -v
```

From `firebase/dashboard`:

```powershell
npm.cmd ci
npm.cmd test
node node_modules/firebase-tools/lib/bin/firebase.js emulators:exec --config ../firebase.json --project demo-ecocapture --only firestore,storage "node test/run-emulator-tests.mjs"
```

The emulator runner uses only `demo-ecocapture`, anonymous test credentials, explicit localhost Firestore/Storage endpoints and generated fixtures. Auth claims and token signing are injected in Python API tests; actual Google sign-in, service-account signing and GB10 OIDC must also be accepted in a synthetic deployed project. The Python runner supports `.venv/Scripts/python.exe` on Windows and `.venv/bin/python` on Linux. Emulator ports are specified in `firebase.json`.

For the optional real encryption integration test, build `crypto/android-age/cmd/interop` into an ignored local executable and set `ECO_CRYPTO_INTEROP` to its absolute path before running the emulator suite. It generates disposable keys, encrypts a synthetic export with the same Go code as Android, downloads it through emulated Cloud Storage using the actual receiver adapter, decrypts with age v1.3.2 and verifies a durable cloud receipt. Set `ECO_TEST_PYTHON` to choose another local Python 3.12 executable. [Local validation evidence](VALIDATION.md) records the checks performed for this implementation.

For the website, copy `dashboard/config.example.json` to ignored `dashboard/config.local.json`, fill in the Firebase **web app** configuration, and run `npm.cmd run build` from `dashboard`. This produces `dashboard/dist`; it does not deploy anything. Do not use a generated `demo-ecocapture` configuration for live deployment.

## Android build and enrolment

1. Register the existing Android application ID in Firebase. Copy `android.example.properties` to ignored `android.local.properties`; fill in the Android app ID, web API key, project, bucket and deployed API URL. These Firebase client identifiers are configuration, not service-account secrets.
2. Build the age AAR using `crypto/android-age/build.ps1` with Go/SDK/NDK on PATH. The build installs the pinned gobind tool into GOPATH, tests both encryption recipients, and generates `app/libs/ecocrypto.aar`. On Linux run the equivalent `go install golang.org/x/mobile/cmd/gobind`, `go test ./...`, `go tool gomobile init`, and `go tool gomobile bind -target=android/arm64 -androidapi=31 -javapkg=org.ecocapture.crypto -o ../../app/libs/ecocrypto.aar .` from that directory; put GOPATH/bin on PATH.
3. Run your installed Gradle 8.14.1 with `:app:assembleDebug -PfirebaseTransfer=true`. Without that flag the Firebase source set/dependencies are excluded and sending remains simulated. The repository does not contain a Gradle wrapper executable.
4. An operator issues a QR using the administration command below. Scan it with the phone camera to open the enrolment confirmation screen, or enter the code through **Approved recordings -> Study enrolment**. Nothing uploads during enrolment.
5. Review and approve a disposable recording, then tap **Send approved recording**. The job waits for an unmetered network (normally Wi-Fi), encrypts, uploads, and waits for GB10 verification. Android notification permission affects whether the foreground notification is visible.

The Room 3-to-4 migration adds nullable artifact/receipt columns without changing existing jobs or scientific data. Legacy simulation jobs never become live uploads automatically. Reopening the app re-enqueues only existing explicitly requested Firebase jobs; new approvals do not enqueue work.

System interruption can leave an upload in progress. SDK retries and WorkManager retries retain the same ciphertext and snapshot; a full app restart may restart the byte upload, never create another logical package. Cancellation stops local work but cannot withdraw uploaded copies. A completion whose response is lost is recovered by repeating the idempotent completion request. Existing app Delete removes local exports and stops local jobs; remote withdrawal requires the researcher/operator. No automatic phone or cloud deletion is enabled.

## Synthetic cloud deployment

Complete this with an operator-selected project and approved region. `australia-southeast1` is a configurable engineering default, **not** a statement of institutional approval or that every Firebase service is region-bound.

From `firebase`, sign in with `node dashboard/node_modules/firebase-tools/lib/bin/firebase.js login`. Complete Google sign-in locally; do not paste credentials or authentication codes into chat. Then inspect `apps:list --project ecological-capture` and `firestore:databases:list --project ecological-capture` with the same CLI before creating or changing any existing resources.

1. Enable Blaze billing, Firebase Authentication (Google provider), Firestore in Native mode and Cloud Storage. Choose locations before provisioning. Register the dashboard web app and add the Hosting domain to authorised Auth domains.
2. Create a dedicated GB10 receiver service account. Grant `roles/storage.objectViewer` only on this study's bucket. GB10 does not need Firestore permissions or public ingress.
3. Give the Cloud Function runtime account Firestore access (`roles/datastore.user`), read access to the study bucket, Firebase Auth access for revoked-token/user checks, and the ability to sign custom tokens (`roles/iam.serviceAccountTokenCreator` on its own service account). Keep these accounts separate. Review the default Cloud Function runtime account rather than assuming it has these permissions.
4. Copy `functions/.env.example` to `functions/.env.PROJECT_ID`. Set the worker email, worker study, API audience and function region. The audience must exactly equal the deployed function's base URL used by GB10. If changing region, update the Hosting rewrite in `firebase.json` and both client configurations as well.
5. Ensure Python 3.12 is available for Firebase function discovery and create the CLI's expected `functions/venv` with the function dependencies (`python3.12 -m venv venv` from `functions`, then install `requirements.txt`). The checkout-local `.venv` used by the test runner is separate. Build the dashboard with the real web configuration.
6. From `firebase`, run the local CLI at `dashboard/node_modules/firebase-tools/lib/bin/firebase.js` with `deploy --project PROJECT_ID --only firestore,storage,functions,hosting`. This is an operator deployment step, not run by the tests. Grant the Storage rules service access to Firestore when prompted by Firebase for cross-service rules.
7. Confirm deny rules, API authentication, Google sign-in and the allowed researcher account in the synthetic project before uploading fixtures. The HTTP function must be reachable by clients; application code authenticates every route except the enrolment-code exchange. Never enable unauthenticated metadata access or public bucket access.

Do not put an age identity, service-account JSON, Firebase CLI token or enrolment code in Git, Android configuration or function environment variables. The public-key policy is separate from those secrets. Treat QR SVG/text outputs as enrolment credentials; protect their directory and remove them after enrolment under the agreed procedure.

## Operator provisioning

Use operator Application Default Credentials outside the repository. `admin.py` is a local CLI and is not deployed. Make a public-key policy from `key-policy.example.json` using distinct GB10 and recovery recipients generated and held on the authorised systems.

```text
python firebase/admin.py --project PROJECT_ID study --id synthetic-study --policy PATH_TO_PUBLIC_KEY_POLICY
python firebase/admin.py --project PROJECT_ID researcher --email RESEARCHER_EMAIL --study synthetic-study
python firebase/admin.py --project PROJECT_ID enrol --study synthetic-study --participant P014 --output NEW_PRIVATE_DIRECTORY_OUTSIDE_REPOSITORY
python firebase/admin.py --project PROJECT_ID revoke --installation INSTALLATION_UUID
python firebase/admin.py --project PROJECT_ID retry --snapshot SNAPSHOT_UUID
```

Enrolment codes expire after 24 hours and bind to one random installation UUID. Repeating an exchange for that same installation within the expiry supports interrupted enrolment. Revocation disables Storage/API access even for a still-cached Firebase token. `retry` only resets a package in `NEEDS_ATTENTION`; it cannot erase a verified receipt. Manage researcher removal by disabling its hashed allowlist entry in the operator Firestore console. Key rotation requires retaining old private identities until all old packages are received; an old frozen policy rejected at registration needs cancellation and a newly requested snapshot.

## GB10 deployment

See [receiver deployment](../server/deployment/firebase/README.md). It is independent of the gateway and requires no Funnel change. Test with synthetic encrypted footage, restart the worker and interrupt its network, then confirm exactly one verified package and receipt. Independently test recovery-key decryption on the researcher workstation.

## Release acceptance

- Check actual Android encryption, background upload/restart, cancellation, local Delete and TalkBack on a disposable installation. Build checks do not replace device tests.
- Confirm cross-installation denial, revoked access, researcher study isolation, custom-token signing and worker OIDC in the deployed project.
- Confirm ciphertext and all three plaintext hashes, participant/snapshot linkage, GB10 restart recovery and recovery-key decryption.
- Verify the browser's sign-in popup/CSP, table filters, UTC dates, CSV and paths with the actual Hosting domain.
- Establish monitoring for repeated `RECEIVER_CHECK_FAILED`, catalogue `NEEDS_ATTENTION` and growing `AWAITING_GB10` backlog; keep logs free of payloads and credentials. Review platform request logging separately.
- Approve retention, cloud billing limits/alerts, access ownership, backups and verified institutional archival before participant rollout. A GB10 receipt always leaves `archived:false`.

The implementation intentionally leaves cloud deletion manual and does not implement browser video playback or automatic archival. Existing gateway/FileSender code and historical checkpoint reports remain available.
