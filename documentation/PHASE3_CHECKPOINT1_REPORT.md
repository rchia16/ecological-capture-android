# Phase 3 Checkpoint 1 - Live AARNet Protocol and Encryption Feasibility

Status: PARTIAL
Date: 2026-10-12 (Australia/Sydney)

## Scope implemented

The investigator authorized continuing to this checkpoint. Checkpoint 0 remains partial: that instruction is not evidence that glasses, TalkBack, retained-data inventory or backup recovery passed.

Reviewed current official AARNet API, CLI and encryption guidance. Retrieved and pinned public upstream Python client and REST specification; documented a redaction/reliability patch list. Queried the actual AARNet public `/info` endpoint over HTTPS without credentials. Prepared two synthetic files and a standalone independent download-hash verifier. Downloaded official age v1.3.2 after matching its published release digest, then ran a local synthetic encryption/decryption and recovery proof.

The required actual-account upload/download is not complete. The supplied client was initially absent, then provided and reviewed in the follow-ups below. Investigator specified an external INI, recipient and five-day expiry, then explicitly authorized the current unrotated key for the prepared read-only authentication probe. That probe succeeded. No transfer, upload, email notification or FileSender deletion was performed; secrets and account/transfer contents were not printed.

### Supplied-client follow-up (2026-10-12)

Investigator provided `tools/filesender.py`. Recorded SHA-256 `c2aa6cb9d6dcfd0c019d6b4cd892b457542b9fdc6fda1d693c4cbf60323605ba`, read its full source and compared it with the upstream reference. It is a class-based upload/download variant with current-directory INI fallback, `--days`, query-value encoding and bounded initial configuration requests. Python AST syntax/compilation passed. The original file was not modified, imported or executed.

Three isolated reproductions using only AST-selected methods, fake values and mock transport confirmed progress logging of signed URLs, unredacted exception propagation with logging flags off, and repeat POST after a lost response. All three reproduced the defects, with no network/config-file access; they are not validations of a fixed client. Additional static findings include unsafe downloaded filenames/token-based paths, unbounded download timeouts, raw failure logging, insecure TLS bypass, non-durable transfer ID, automatic failure deletion and possible zero exit on handled failure. See the supplied-client table in `PHASE3_AARNET_PROTOCOL.md` for actual line references and required fixes.

A copied `tools/filesender.py.ini` appeared in Git status by filename. Added a `filesender.py.ini` ignore pattern and confirmed it is ignored without reading its contents. Secret rotation and approved recipient/expiry were requested again after script provision. No actual credential integration or upload occurred.

Investigator later confirmed the secret has not been rotated. The assertion of prior exposure comes from plan section 4.2 ("pasted into development material") and its Checkpoint 1 rotation task; the actual exposure location has not been independently identified. Earlier exposure references repeated that assertion. The synthetic logging reproductions show possible leakage in the CLI, not an actual historical disclosure of this account's key. No real key or INI content was read.

### Separately provisioned location and read-only checker

Investigator reports configuration ready at `C:/Users/rchia/.filesender/filesender.py.ini`; a `Test-Path -LiteralPath` check returned True. File contents were not opened or echoed. The last explicit rotation status remains not rotated; no subsequent rotation confirmation has been supplied. Plan section 17.6 requires a rotated, separately provisioned credential; resolution with the investigator is pending before this checker is run against the actual account.

Added `checkpoint1_auth_probe.py` for the concrete read-only check: explicitly load the external INI internally, require the fixed HTTPS AARNet API host, GET public `/info`, then sign GET `/transfer/@me` using the supplied protocol's HMAC canonicalization. TLS validation is enabled, redirects rejected, requests/responses bounded, no retries or transfer mutations, and output contains no key/account/transfer/URL contents. It does not import or execute the supplied CLI. This is an engineering authentication probe, not the later gateway/FileSender adapter or uploader.

Nine additional mock tests passed; final combined suite is **16 passed, 0 failures/errors/skips** in 0.045 seconds. The initial fake HTTPError emitted a cleanup ResourceWarning; explicit error-response closure fixed it, and the final run treated ResourceWarning as an error. No real credentials were involved. Android build/regression invocation again succeeded in 1 second, 134 tasks (3 executed, 131 up to date); the unit tasks reused preceding results. No device install/instrumentation or live auth/upload was performed. Investigator subsequently specified `raymond.chia@uts.edu.au` and five days for the synthetic test. Actual server expiry and notification behavior remain untested.

### Authorized read-only account check

Investigator answered yes to using the existing unrotated key for the read-only AARNet authentication check, after the section 17.6 rotation requirement was explained. Ran the reviewed probe with host network access and the external configuration path. It internally loaded the credential file without printing contents, queried public `/info`, and successfully executed signed `GET /transfer/@me`.

Exact command:

```powershell
python -B tools/phase3-protocol/checkpoint1_auth_probe.py --config C:/Users/rchia/.filesender/filesender.py.ini
```

Exit 0. Safe result: `SIGNED_READ_SUCCEEDED`, `upload_chunk_size = 12582912`, `transfer_mutations = 0`, `uploads = 0`. No account identity, existing transfer metadata, signed URLs or key were output. This establishes authenticated read capability only; creation/finalization permissions, expiry and researcher verification remain pending. Authorization applied to this read-only operation; no external upload or participant-release authorization was inferred. Secret rotation was not performed.

## Files added/modified

- `.gitignore`: excludes `tools/phase3-protocol/artifacts/`, including downloaded tools/reference files, fixture data, disposable temporary keys and local proof summaries; follow-up also excludes FileSender INI credentials.
- `tools/filesender.py`: added by investigator, reviewed without changes; original reference hash recorded above.
- `tools/phase3-protocol/checkpoint1_probe.py`: offline fixture preparation, strict independent download verification and local age proof; contains no upload operation or credential loading.
- `tools/phase3-protocol/test_checkpoint1_probe.py`: seven verifier rejection/acceptance tests.
- `tools/phase3-protocol/checkpoint1_auth_probe.py`: prepared read-only signed-auth checker; not run with actual credentials.
- `tools/phase3-protocol/test_checkpoint1_auth_probe.py`: nine mock auth/secret-isolation tests.
- `tools/phase3-protocol/README.md`: reproducible offline commands and pending live-test conditions.
- `documentation/PHASE3_AARNET_PROTOCOL.md`: verified public configuration, pinned references, protocol review, patch list and pending account evidence.
- `documentation/PHASE3_CRYPTO_DECISION.md`: proposed age mechanism, exact local proof and remaining approval/interoperability requirements.
- `documentation/PHASE3_DECISIONS.md`: records continuation authorization and separates measured feasibility from pending decisions.
- `documentation/PHASE3_CHECKPOINT1_REPORT.md`: this report.

No Android, Room, DAT, Qwen, native runtime or participant-screen code was changed. Earlier Checkpoint 0 files remain present.

## Commands executed

```powershell
rg --files --hidden --no-ignore -g '*filesender*.py' -g '*FileSender*.py' -g AGENTS.md -g '!**/.git/**' -g '!**/node_modules/**'
Get-Command age,age-keygen -ErrorAction SilentlyContinue
python --version
python -B -m unittest discover -s tools/phase3-protocol -p 'test_*.py' -v
python -B tools/phase3-protocol/checkpoint1_probe.py prepare tools/phase3-protocol/artifacts/fixtures
& ./tools/phase3-protocol/artifacts/age.exe --version
python -B tools/phase3-protocol/checkpoint1_probe.py age-proof tools/phase3-protocol/artifacts/fixtures --age tools/phase3-protocol/artifacts/age.exe --keygen tools/phase3-protocol/artifacts/age-keygen.exe --artifacts tools/phase3-protocol/artifacts

$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
& 'C:/Users/rchia/.gradle/wrapper/dists/gradle-8.14.1-bin/baw1sv0jfoi8rxs14qo3h49cs/gradle-8.14.1/bin/gradle.bat' :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain

git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android check-ignore tools/phase3-protocol/artifacts/age.exe tools/phase3-protocol/artifacts/reference-versions.json tools/phase3-protocol/artifacts/local-age-proof.json
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android diff --check
git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android status --short
```

Public web browsing used the plan's AARNet links, upstream references and the official age release. Inline Python `urllib.request` scripts fetched public metadata/configuration with timeouts and normal TLS validation; only allowlisted `/info` fields were printed. Initial sandbox network reads failed; host-access retries succeeded. Exact public resource URLs/pins/hashes are recorded in the protocol and crypto documents. Neither script imported or executed FileSender client code.

Follow-up commands: `Get-FileHash tools/filesender.py -Algorithm SHA256`, full source reads with `Get-Content`, targeted `rg -n` searches, an inline `python -B -` AST compilation/isolated mock reproduction, and `git -c safe.directory=D:/Ray/StudioProjects/ecological-capture-android check-ignore tools/filesender.py.ini`. The isolated reproduction compiled only `flatten` and `call` into a detached class and injected mocked requests/time; it did not execute imports, constructor or module entry point from the reference. Only boolean findings and the reference hash were printed.

Read-only checker preparation checks: `Test-Path -LiteralPath 'C:\Users\rchia\.filesender\filesender.py.ini'`, `python -B -m unittest discover -s tools/phase3-protocol -p 'test_*.py' -v`, followed after HTTPError closure fix by `python -B -W error::ResourceWarning -m unittest discover -s tools/phase3-protocol -p 'test_*.py' -v`. The module-qualified Gradle command above was repeated and succeeded with up-to-date unit tasks.

The download script selected only fixed `age/age.exe` and `age/age-keygen.exe` ZIP members after verifying the archive SHA-256. It did not extract arbitrary archive paths. The FileSender reference was stored only for static review. No credentials, private identity or signed request URLs were printed.

## Automated tests

| Check | Actual result |
| --- | --- |
| Python verifier | 7 passed, 0 failures/errors/skips, 0.059 seconds |
| Local age proof | 8 explicit checks passed; process exit 0 |
| Android module-qualified build/unit invocation | BUILD SUCCESSFUL in 2 seconds, 134 tasks: 3 executed, 131 up to date |
| Android JVM tests | Unit tasks up to date; reuses preceding Checkpoint 0's 52 debug and 52 release passing tests, not new executions |
| Debug/release/instrumentation APK checks | Successful; no installation attempted |
| Room/device suite | Not run; no Android schema/code change, and baseline phone retention preflight remains pending |
| Ignored artifact paths | Confirmed ignored by Git |
| Supplied-client syntax | AST parsing/compilation passed; CLI not executed |
| Supplied-client defect reproductions | 3 confirmed with synthetic mock transport, 0 live calls/credential reads; not fixed-client acceptance |
| Final combined Python suite | 16 passed, 0 failures/errors/skips; nine auth-probe mocks plus seven download-verifier tests |

Verifier cases cover correct bytes, same-length corruption, truncation, missing and extra files, directory substitution and unexpected expected-record paths. Age checks cover deterministic plaintext archive, randomized ciphertext, exact decryption, wrong key, ciphertext corruption, disposable-key backup recovery, extracted-file hashes and unchanged sources. These prove only the synthetic local operations described; no mock or public request is counted as authenticated AARNet success.

## Physical-device tests

None in this checkpoint. No phone content was read, copied, deleted, replaced or uploaded. Remaining Checkpoint 0 glasses, TalkBack, enlarged-text, inventory and retention checks are unchanged.

## Gateway and AARNet tests

| Test | Result |
| --- | --- |
| Public HTTPS AARNet `/info` | Success; `upload_chunk_size = 12582912` bytes (12 MiB) |
| Expiry/other selected limits | Not disclosed in the selected public fields; no account limits inferred |
| Signed auth | Passed for `GET /transfer/@me` using existing unrotated credential under explicit investigator exception; rotated-key check not run |
| Two-file create/chunk/file completion/transfer completion | Not run |
| Researcher download/source SHA-256 comparison | Not run |
| Recipient notification count and acceptable volume | Not measured |
| Encrypted FileSender upload/download/decrypt | Not run |
| Guest/voucher eligibility | Specification review only; no guest creation/email |
| Gateway | No gateway created or deployed |

Source file sizes/hashes and local proof artifact hashes are recorded in the protocol/crypto documents. A publicly readable `/info` response does not prove account eligibility. No transfer ID, acknowledged expiry or FileSender completion receipt exists for this checkpoint.

## Data-safety / retention check

Used only generated text/pattern bytes for local fixture/crypto checks. Android research media, annotation history, VLM output/provenance, decisions, app data and model files were untouched. Earlier preparation did not open actual credentials; the subsequently authorized read-only probe internally opened only the external INI without printing its contents or secret. No investigator private key was provisioned. The age proof used disposable temporary identities; their directory was removed on completion, and inspection found only the persistent `fixtures` directory under artifacts. Temporary-file removal is not secure erasure.

Downloaded binary/reference files and generated data are ignored, with hashes/versions documented for reproduction. Public release digest comparison over HTTPS was completed; age's transparency proof was not independently verified. Institutional production key/storage/security approval is not established by this local test.

## Manual acceptance steps

Before an actual account transfer:

1. Investigator confirms the exposed secret was rotated and supplies protected rotated-credential provisioning details outside Git/logs/chat. Actual reference script path/hash is now recorded; account identity remains pending.
2. Address the supplied-client review's sensitive diagnostics, misleading failure exit/retry behavior, download path handling and TLS bypasses in a reviewed test harness before using credentials. Do not execute the unmodified CLI.
3. Confirm the recipient and explicit expiry for the two synthetic files. Obtain concrete external-transfer authorization as required by plan section 17.14; the general checkpoint continuation instruction is recorded separately.
4. Verify signed authentication and accepted account settings. Upload the two named synthetic files, retaining remote identity and actual server-acknowledged expiry. Complete both files and the transfer, then retrieve status without exposing tokens/URLs.
5. Researcher downloads to an isolated directory and runs the independent verifier against the trusted local expected record. Record actual notification counts, retrieval, hashes and expiry.
6. Approve the crypto choice and private-key custody/recovery process, then prove the ciphertext -> FileSender -> independent workstation -> decrypt -> original hashes path using harmless data. A same-host age test is insufficient.

No actual upload command has been supplied or executed while these details are missing. Protocol source links and exact synthetic fixture hashes are already reviewable.

## Known failures and limitations

- Supplied script remains unsafe for direct credential use. The reviewed read-only probe passed with the current key under an explicit investigator exception; a safe upload/download harness remains pending.
- Secret rotation/account identity, approved recipient/expiry and separately protected credential provisioning unconfirmed.
- Signed read authentication passed; signed mutation/upload protocol, recipient behavior, FileSender expiry and researcher receiving path remain untested.
- Crypto proposal has local proof, not institutional approval or independent workstation/key-recovery acceptance.
- Public source fetches required approved host network access; some browser fetches failed. Successful direct HTTPS reads are documented separately.
- Checkpoint 0 baseline remains partial; no claims of passed physical/manual acceptance were added.

## Exit criteria

- [ ] Signed auth works with rotated secret.
- [x] Signed read works with existing secret under explicit investigator exception; no key rotation claimed.
- [x] Public `/info` returned a usable observed chunk size; account configuration still pending.
- [ ] Harmless two-file transfer completes.
- [ ] Researcher download matches SHA-256.
- [ ] Recipient/notification volume characterized.
- [x] Local age encryption/decryption proof passes.
- [ ] Encrypted FileSender/independent-researcher proof and institutional crypto choice completed.
- [ ] Actual transfer expiry recorded.
- [x] No secrets or download tokens printed by executed tooling.

## Recommended next checkpoint

Finish the pending Checkpoint 1 live account/recipient/encrypted round-trip checks after required non-secret details and authorization arrive. Keep participant uploads disabled. Resolve remaining baseline acceptance separately; do not treat this partial report as approval to start Checkpoint 2 or a gateway implementation.

STOP. Do not continue to another checkpoint without explicit approval. Checkpoint 1 is PARTIAL.
