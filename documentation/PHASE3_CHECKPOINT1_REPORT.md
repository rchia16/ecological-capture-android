# Phase 3 Checkpoint 1 - Live AARNet Protocol and Encryption Feasibility

Status: PARTIAL

Continuation update (2026-10-13): investigator explicitly deferred backup recovery and instructed Checkpoint 2 local snapshot work. Remaining items are tracked in `PHASE3_OUTSTANDING_CHECKS.md`; this authorization does not mark Checkpoint 1 complete or authorize participant uploads. Earlier STOP wording is superseded for that authorized local work only.
Date opened: 2026-10-12; latest transfer evidence: 2026-10-13 (Australia/Sydney)

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

1. External credential location and actual reference script/hash are recorded. Existing-key use was explicitly authorized for the read-only check and succeeded; any further credential use/transfer needs the plan's rotation requirement or an explicit investigator exception resolved for that action. Prior exposure remains an unverified plan assertion.
2. Address the supplied-client review's sensitive diagnostics, misleading failure exit/retry behavior, download path handling and TLS bypasses in a reviewed test harness before using credentials. Do not execute the unmodified CLI.
3. Recipient `raymond.chia@uts.edu.au` and five-day requested expiry are recorded. Obtain concrete external-transfer authorization as required by plan section 17.14; the read-only authorization does not itself authorize an upload.
4. Verify signed authentication and accepted account settings. Upload the two named synthetic files, retaining remote identity and actual server-acknowledged expiry. Complete both files and the transfer, then retrieve status without exposing tokens/URLs.
5. Researcher downloads to an isolated directory and runs the independent verifier against the trusted local expected record. Record actual notification counts, retrieval, hashes and expiry.
6. Approve the crypto choice and private-key custody/recovery process, then prove the ciphertext -> FileSender -> independent workstation -> decrypt -> original hashes path using harmless data. A same-host age test is insufficient.

The initial pre-transfer checks above were followed by the explicitly authorized synthetic transfer and researcher verification recorded below. Further encrypted transfers still require their concrete preparation and authorization.

## Known failures and limitations

- Supplied script remains unsafe for direct credential use. Reviewed engineering auth/synthetic-upload tooling was used instead; the original CLI was not executed.
- Secret remains unrotated; explicit investigator instructions cover the completed read-only check and synthetic transfer. Production rotation is not claimed. Engineering recipient and five-day server expiry are confirmed; account identity is not logged.
- Synthetic plaintext upload, completion, automated download and researcher-reported/download-file hash verification passed. Notification arrival was confirmed; exact notification count was not supplied. Encrypted transfer and independent workstation/key recovery remain untested.
- Crypto proposal has local proof, not institutional approval or independent workstation/key-recovery acceptance.
- Public source fetches required approved host network access; some browser fetches failed. Successful direct HTTPS reads are documented separately.
- Checkpoint 0 has user-reported app/glasses/accessibility acceptance; technical retention/recovery and specific disposable-clip inventory remain pending.

## Exit criteria

- [ ] Signed auth works with rotated secret.
- [x] Signed read works with existing secret under explicit investigator exception; no key rotation claimed.
- [x] Public `/info` returned a usable observed chunk size; account configuration still pending.
- [x] Harmless two-file transfer completes.
- [x] Researcher download matches SHA-256; manual PowerShell hashes independently checked against source and named local files.
- [x] Recipient confirms notification arrival and expiry.
- [ ] Exact notification count/volume characterization recorded.
- [x] Local age encryption/decryption proof passes.
- [ ] Encrypted FileSender/independent-researcher proof and institutional crypto choice completed.
- [x] Actual transfer expiry recorded and confirmed by investigator.
- [x] No secrets or download tokens printed by executed tooling.

## Recommended next checkpoint

Finish the pending Checkpoint 1 live account/recipient/encrypted round-trip checks after required non-secret details and authorization arrive. Keep participant uploads disabled. Resolve remaining baseline acceptance separately; do not treat this partial report as approval to start Checkpoint 2 or a gateway implementation.

## Authorized synthetic transfer - 2026-10-13

Investigator reported the presented manual checks "all fine" and explicitly instructed "send a test transfer". This authorizes the two specified synthetic files using the provisioned current key, recipient `raymond.chia@uts.edu.au` and five-day expiry. No participant footage or private annotation was included. The unrotated-key exception now covers this requested engineering transfer; it is not production or participant-upload approval.

Added `tools/phase3-protocol/checkpoint1_send_test.py` and `test_checkpoint1_send_test.py`. The reviewed sender fixes the host/TLS boundary, redacts errors, restricts inputs to exact known fixture hashes and the authorized recipient/expiry, streams one bounded chunk at a time, persists creation intent and transfer ID, never automatically retries creation and never deletes a transfer on failure. Existing job directories require reconciliation. The original supplied CLI was not executed.

Command executed once:

```powershell
python -B tools/phase3-protocol/checkpoint1_send_test.py --config C:/Users/rchia/.filesender/filesender.py.ini --fixtures tools/phase3-protocol/artifacts/fixtures --job tools/phase3-protocol/artifacts/test-transfer-20261013 --recipient raymond.chia@uts.edu.au --days 5
```

Both files were uploaded in three bounded chunks (71, 12582912 and 17 bytes), both file completions were acknowledged, and transfer completion was acknowledged. The initial final guard incorrectly required a `status` field absent from AARNet's actual owner response, producing `FINALIZATION_NOT_VERIFIED` after completion. Transfer ID was already persisted, so no second transfer or repeat finalization was created. Read-only reconciliation retrieved the same transfer and used its recipient download access to verify both complete files against the source SHA-256 record.

Download troubleshooting encountered a case-sensitive recipient comparison, an existing temporary download directory and an intentionally blocked redirect. AARNet redirects downloads to the same HTTPS host and `/download.php` path. The final read-only download allowed only that exact host/path, with normal TLS and bounded output, using a fresh local directory. Tokens/links remained internal and were not logged. Successful downloaded bytes passed the independent local verifier. This is an automated same-host recipient-route download, not evidence that the investigator has downloaded/decrypted on a separate workstation.

| Result | Actual evidence |
| --- | --- |
| FileSender transfer ID | `3647625` |
| Test ID / email subject suffix | `0449e162-89a3-4da9-a841-62ec19d4b4a8` |
| Recipient | `raymond.chia@uts.edu.au` |
| FileSender completion | Acknowledged and reconciled by successful recipient-route download |
| Server expiry | `1792293061000` epoch ms, **2026-10-18 14:11:01 Australia/Sydney**; exactly the requested five-day timestamp |
| `synthetic-note.txt` | 71 bytes; SHA-256 `104b5784640475d8e6b1748d10f5f7ca567795bee6134fff054fdfb9176475f6` |
| `synthetic-pattern.bin` | 12582929 bytes; SHA-256 `2035167438838a59533e30d07fdda4ecd0ea8a3f14fa41fa255761761fa6ad76` |
| Download comparison | Both exact SHA-256/byte sizes passed |
| Independent researcher receipt | Investigator confirms downloaded files; supplied manual PowerShell SHA-256 values match source, and direct named-file read verifies exact hashes/sizes |
| Notification count | Arrival confirmed; exact numerical count not supplied |
| Encryption / archive | This two-file protocol test is unencrypted synthetic data; encrypted FileSender/independent workstation test and archival remain unperformed |

The non-secret receipt and downloaded synthetic bytes remain under ignored `tools/phase3-protocol/artifacts/test-transfer-20261013/`. Sender completion verification was corrected to require matching completion acknowledgment and owner file metadata, accepting the documented actual response without a status field. A present stalled status still fails. Two regression cases were added. Final Python suite: **26 passed, zero failures/errors/skips**, 0.532 seconds, with ResourceWarning treated as error. Module-qualified Android build/unit invocation succeeded in 8 seconds (134 tasks: 12 executed, 122 up to date); unit tasks reused prior passing results. No phone install or Room run was needed for this Python-only change.

Checkpoint 1 remains PARTIAL pending encrypted independent-workstation feasibility, crypto/key decisions and complete notification-volume evidence. Earlier "not run" entries above describe the preceding state and are superseded for this synthetic plaintext protocol test by this dated evidence. No gateway implementation or next checkpoint began.

### Researcher download confirmation and hashes - 2026-10-13

Investigator confirmed the manual receiving checks and identified `C:/Users/rchia/Downloads`. Their manually computed PowerShell SHA-256 values for both synthetic files match the trusted original hashes. Codex then opened **only** `synthetic-note.txt` and `synthetic-pattern.bin` at that location, streamed SHA-256 and checked byte sizes: 71 and 12582929 bytes, both exact matches. No unrelated Downloads content was enumerated or copied.

Investigator also supplied a ZIP SHA-256: `fcfb49ecec968a99da6de7ab1932cd5af6088b7e7fbffdde6fd9386bf654db16`. This is recorded as user-reported container metadata. Its full filename was truncated and there is no trusted original ZIP digest for comparison; the exact extracted source-file hashes establish the successful plaintext round trip. No ZIP match against an original ZIP is claimed.

The local non-secret receipt was updated with human receipt/hash confirmation, scoped to the two synthetic plaintext files. This does not establish encryption, a separate decryption workstation, private-key recovery or eResearch archival. No additional transfer was sent.

## Researcher encrypted decryption evidence

Investigator provided the local PowerShell transcript for transfer 3647689: ciphertext hash matches the prepared artifact, age decryption using the local researcher identity succeeded, decrypted TAR matched the trusted archive hash, extraction succeeded and `checkpoint1_probe.py verify` returned `SHA256_VERIFIED` for both original synthetic files. Codex independently rechecked the named ciphertext/TAR and extracted-file hashes without opening any private identity. The actual encrypted transfer/decryption/source-hash interoperability path now passes.

Exact read-only verification command:

```powershell
python -B tools/phase3-protocol/checkpoint1_probe.py verify tools/phase3-protocol/artifacts/fixtures/expected.json C:/Users/rchia/Downloads/ecocapture-encrypted-check/files
```

Ciphertext/TAR were additionally hashed by an inline Python streaming SHA-256 check against their documented expected hashes. No decryption with a private key was run by Codex. Backup-key recovery, protected storage/custodian, institutional crypto decision and remaining baseline technical evidence are not inferred from these results. Checkpoint 1 remains PARTIAL; no new checkpoint began.

## Your manual checks

Encrypted transfer execution (2026-10-13): investigator explicitly instructed "send". Checked absence of an existing encrypted-transfer job following the interrupted turn, then executed once:

```powershell
python -B tools/phase3-protocol/checkpoint1_send_test.py --config C:/Users/rchia/.filesender/filesender.py.ini --fixtures tools/phase3-protocol/artifacts/encrypted-test-20261013 --job tools/phase3-protocol/artifacts/encrypted-transfer-20261013 --recipient raymond.chia@uts.edu.au --days 5 --encrypted
```

Exit 0, `FILESENDER_COMPLETE`; transfer **3647689**, test ID `659cfeb0-70ed-4833-a33d-25e34cef8cf9`. Only `synthetic-files.tar.age` was sent, 12598472 bytes, SHA-256 `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`. Actual server expiry: **2026-10-18 14:49:22 Sydney / 1792295362000 epoch ms**, five days as requested. Both file and transfer completion were acknowledged and matching owner metadata retrieved; no duplicate creation, private-key read or archival occurred. This runs the already-tested sender without further code changes; no new regression run was needed for the external execution. Researcher verification is still false in the non-secret receipt at ignored `tools/phase3-protocol/artifacts/encrypted-transfer-20261013/receipt.json`.

Encrypted-test preparation follow-up (2026-10-13): validated and read only the public recipient file at the agreed outside-repository location; private file existence checked without contents. Prepared the encrypted two-fixture archive documented in `PHASE3_CRYPTO_DECISION.md`, ciphertext SHA-256 `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`, 12598472 bytes. No encrypted upload or real-key decryption occurred. Sender now has an exact fixed ciphertext profile; two added mock tests bring the passing Python suite to 28 (0.643 seconds, zero failures/errors/skips). Module-qualified Android build/unit invocation succeeded in 1 second with 134 tasks (3 executed, 131 up to date); unit results were reused. Backup location and external encrypted-transfer authorization remain pending. Manual commands and expected hashes are in the crypto decision document.

Authentication, synthetic upload/download and investigator's downloaded-file hashes passed. Receipt and expiry have been confirmed; no repeat of these manual checks is needed. Earlier ready app checks were reported "all fine". Technical retention and encrypted-workstation evidence remain outstanding.

- [ ] Ready now: review the proposed encryption/key-custody/recovery decision in `PHASE3_CRYPTO_DECISION.md`. Report whether it is approved or which decisions still need institutional review; do not send private keys or passwords.
- [x] Investigator confirmed FileSender notification, intended synthetic files and displayed expiry. Exact notification count was not specified; recipient link/token was not shared.
- [x] Investigator downloaded both files and computed matching SHA-256 hashes. Codex verified the named Downloads files also have exact hashes and expected sizes. No repeat check required.
- [x] Researcher downloaded encrypted transfer 3647689 and verified ciphertext SHA-256 `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`.
- [x] Researcher locally decrypted, verified the TAR hash and both original files; verifier returned `SHA256_VERIFIED`. This confirms the technical workflow; institutional workstation approval remains separate.
- [ ] Pending protected backup location: repeat decryption using the backup identity, preserving the original key. Expected: recovered TAR SHA-256 `4e885c67adbd3584279db77815dfa13a231077a3d1fd696871a76c0000fe642f`. Report safe results and workstation identity only, never private key/password contents.

Report each manual item as PASS, FAIL or NOT TESTED. Outstanding Checkpoint 0 glasses/TalkBack/retention checks still apply. No participant recording is needed for these FileSender tests.

STOP. Do not continue to another checkpoint without explicit approval. Checkpoint 1 is PARTIAL.
