# Phase 3 AARNet protocol record

Date: 2026-10-12 (Australia/Sydney).
Status: PARTIAL - authenticated synthetic transfer, automated recipient-route download and investigator receiving/hash verification passed; encrypted workstation tests pending.

## Evidence boundary

The investigator authorized moving to Checkpoint 1 after a partial Checkpoint 0 report. Baseline device inventory, glasses and TalkBack acceptance remain pending. The later read-only auth probe was explicitly authorized to use the current unrotated key and internally loaded the separately provisioned external INI. No secret contents, recipient link or signed URL were printed, and no participant files were transferred.

The initial run found no supplied script. The investigator subsequently copied it to `tools/filesender.py`; that file has now been inspected without importing or running its CLI. Its exact SHA-256 is `c2aa6cb9d6dcfd0c019d6b4cd892b457542b9fdc6fda1d693c4cbf60323605ba`. Secret rotation and approved recipient/expiry remain unconfirmed. A copied `tools/filesender.py.ini` was also visible by filename; it was excluded from Git without opening it. The supplied script remains unchanged for reference.

## Official interface and observed configuration

[AARNet API guidance](https://support.aarnet.edu.au/hc/en-us/articles/235972948-FileSender-API) identifies `https://filesender.aarnet.edu.au/rest.php` and profile-managed API secrets. The [command-line guide](https://support.aarnet.edu.au/hc/en-us/articles/5276533711887-Use-FileSender-from-the-command-line) describes downloading the Python client/configuration from the account profile. No account login or key rotation was performed by this agent.

An HTTPS `GET https://filesender.aarnet.edu.au/rest.php/info` using Python `urllib.request` with normal TLS validation succeeded without authentication:

| Field | Observed result |
| --- | --- |
| `upload_chunk_size` | 12582912 bytes (12 MiB) |
| `max_transfer_size` | Not present in the selected response fields |
| `max_transfer_files` | Not present in the selected response fields |
| `default_transfer_days_valid` | Not present in the selected response fields |
| `max_transfer_days_valid` | Not present in the selected response fields |

Only the five named fields were selected for output; a raw response was not logged. Public `/info` success proves neither signed authentication nor this account's upload/expiry/recipient permissions. Do not hardcode this chunk size as a permanent server setting. Expiry remains unknown and must be confirmed from approved account settings and the created transfer response.

Browser fetches of `/info` and some upstream pages failed. Direct Python HTTPS requests initially failed inside the network-restricted sandbox, then succeeded with approved network access. No files or credentials were sent by these reads.

## Pinned references

| Reference | Pin | Exact SHA-256 |
| --- | --- | --- |
| Public upstream `scripts/client/filesender.py` | `5de1ebe2333833c5de13361784d250a4f064d99d` | `81839b612a457f10f92fddfac212a280edbe0e5049e42cd738cc27a2302065f8` |
| Public upstream `docs/v2.0/rest/index.md` | `5fff0cb2536e19caf98c7835619b0636ef2bcbab` | `5b5ae29dcc7f1da8e29da698dab035817d1fd462b7e7c647b7ba334c788018d0` |

[Pinned Python reference](https://github.com/filesender/filesender/blob/5de1ebe2333833c5de13361784d250a4f064d99d/scripts/client/filesender.py), [pinned REST specification](https://github.com/filesender/filesender/blob/5fff0cb2536e19caf98c7835619b0636ef2bcbab/docs/v2.0/rest/index.md). Downloaded copies and reference metadata are ignored local engineering artifacts under `tools/phase3-protocol/artifacts/`.

## Protocol to verify with the actual account

The pinned REST specification defines remote-user authentication using timestamped HMAC-SHA1 signed requests. Method, scheme-stripped URL with sorted arguments and exact request-body bytes enter signing. A reviewed implementation must preserve the server's canonicalization and test signatures against the account; neither unit vectors nor a public request substitutes for that test. Keep the host clock synchronized and do not log signed request URLs. [Signed-request specification](https://github.com/filesender/filesender/blob/5fff0cb2536e19caf98c7835619b0636ef2bcbab/docs/v2.0/rest/index.md#signed-request).

The intended test sequence is public `/info`, signed transfer creation, bounded chunk PUTs, separate file completion, transfer completion and owner retrieval. Preserve transfer identity immediately after creation and reconcile uncertain results before retrying creation. Uploaded bytes alone are not completion; researcher download/hash comparison is independent. Recipient notification behavior must be measured. [Upload workflow](https://github.com/filesender/filesender/blob/5fff0cb2536e19caf98c7835619b0636ef2bcbab/docs/v2.0/rest/index.md#the-upload-process).

Exact paths:

```text
GET  /info
POST /transfer
PUT  /file/{fileId}/chunk/{offset}
PUT  /file/{fileId}          {"complete":true}
PUT  /transfer/{transferId} {"complete":true}
GET  /transfer/{transferId}
```

The reference uses file keys and roundtrip tokens during upload. These are sensitive protocol values; keep them out of logs and Android. Guest/voucher endpoints were reviewed in the specification but not exercised, because guest creation sends email and is unnecessary for the chosen gateway architecture.

## Reference-client redaction and reliability review

Findings apply to the pinned public reference. Recheck them against the investigator-supplied version before use. Line numbers refer to the ignored downloaded reference.

| Location | Finding | Required treatment before live use |
| --- | --- | --- |
| 74-81, 128 | Import executes config loading/argument parsing | Do not import the CLI as a library; isolate reviewed protocol logic and explicit credential injection. This reference reads the home INI; a current-directory fallback claimed in the plan is not present here. |
| 95-115, 204-211 | Verbose emits API key; key/password CLI flags expose process arguments | Remove sensitive diagnostics and use separately protected credential handling. |
| 159-173 | Public requests have no timeout; insecure permits trust bypass | Mandatory TLS validation, bounded request timeouts and no insecure fallback. |
| 323-327, 346-351 | Progress or debug emits signed failure URLs and response details | Suppress both paths; use fixed safe error codes rather than URL/body diagnostics. |
| 333, 344, final exception handler | Raw exceptions/body/arguments can escape even without verbose | Wrap failures with redacted errors; do not rely solely on disabling verbose. |
| 328-355 | Recursive long retries can repeat transfer creation after uncertain response | Bound retry/backoff; persist remote identity and reconcile before mutation retries. |
| 568-574 | Transfer identity is not durably persisted | Save immediately, then inspect remote state on restart. |
| 594-595 | Schedules futures for all chunks | Bound outstanding work as well as chunk memory. |
| Final exception handler and exit | Attempts deletion on upload failure and can exit zero | Retain/reconcile recoverable jobs, report failure accurately; verify remote state independently. |
| Optional encryption branch | Site-dependent password encryption path | Prove browser/researcher interoperability explicitly before selecting it. |

These findings rule out unmodified CLI execution as the production adapter. No adapter has been implemented in Checkpoint 1; that belongs to the later gateway checkpoint. No actual API key, recipient token or download URL appears in this record.

## Investigator-supplied client review

The supplied 875-line script is a class-based variant with both upload and download support, current-directory INI fallback, `--days`, initial 60-second configuration timeouts and URL encoding of query values. It differs from the pinned public reference; no release version identifier or source commit was established for it. Exact file hash above is its review pin. Python AST parsing and compilation passed; neither constructor nor module entry point was executed.

| Supplied file location | Finding | Required treatment before credential use |
| --- | --- | --- |
| 107, 874-875 | Constructor reads home/current-directory INI; unguarded module entry point constructs and processes CLI | Do not import/execute the original for tests. A reviewed harness must use explicit protected credential provisioning and avoid implicit fallback to an old configuration. |
| 129-135, 164-167, 281 | API key/password CLI inputs; verbose prints API key | Remove secret output; no secret values in arguments/history. |
| 231-242, 397-403 | Insecure bypass remains for HTTP calls | Fix allowed HTTPS host and enforce normal TLS validation with no bypass. |
| 405-409, 433-438 | Progress or verbose prints full signed URLs, token-bearing query fields and response/exception diagnostics | Emit safe codes only; suppress raw URLs, response bodies and exceptions. |
| 415, 428, 862-865 | Raw errors propagate or print even with verbose/progress off | Redact errors at all boundaries; disabling the two flags alone is insufficient. |
| 410-414, 439-442 | Recursive retries repeat POST after a lost response; five-second sleeps and retry exhaustion use differing bounds | Do not blindly retry create. Persist/reconcile identity, use bounded typed retry policy. |
| 259-265, 312-316, 737 | Missing regex match can raise uncaught AttributeError; expiry range uses assert and does not ensure positive days | Validate required/optional settings and expiry explicitly; do not rely on assert or a client default as account policy. |
| 554-563 | Download path trusts remote filename; default directory is recipient token and is printed | Restrict to expected names, resolve under a fixed private destination, reject traversal/symlinks, never use/log token as a path. |
| 575-589 | Downloads have no timeout, overwrite target on each retry, print filenames/path and raw exception in debug | Bounded streaming into private temporary files, safe retry, hash verification and atomic publish after success. |
| 814-819, 843 | Transfer ID kept only in memory; all chunk futures submitted | Persist identity immediately; limit outstanding chunks. |
| 862-870 | Failure prints raw exception and attempts remote deletion; successful cleanup can leave normal zero process exit | Preserve/reconcile interrupted jobs and report failure truthfully; no automatic deletion or false success. |
| 287-341, 640-678, 701-717 | Password-based optional AES-GCM encrypt/decrypt implementation exists | Local code presence is not actual AARNet interoperability; retain the independently tested age proposal pending approval. |

Three synthetic reproductions executed only AST-selected `flatten` and `call` methods in an isolated class, using fake keys/users, mocked requests and a fake clock/no-op sleep:

1. With progress on and verbose off, a failed request logged a signature, synthetic file key and synthetic roundtrip token.
2. With both flags off, a synthetic transport exception containing a signed URL propagated unchanged.
3. A simulated lost create response automatically caused a second POST. This confirms an uncertain-create retry; it does not prove a real duplicate transfer was created.

All three findings were reproduced. Captured fake diagnostics were checked in memory and not printed; zero live requests and zero credential-file reads occurred. These are audit reproductions of defects, not passing redaction/recovery tests for a fixed client. The supplied reference is not approved for credential use yet.

## Concrete harmless-file test package

Generated locally using `tools/phase3-protocol/checkpoint1_probe.py prepare`:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `synthetic-note.txt` | 71 | `104b5784640475d8e6b1748d10f5f7ca567795bee6134fff054fdfb9176475f6` |
| `synthetic-pattern.bin` | 12582929 | `2035167438838a59533e30d07fdda4ecd0ea8a3f14fa41fa255761761fa6ad76` |

The second file crosses the observed chunk boundary by 17 bytes. Both are generated synthetic bytes without footage or participant metadata. Upload only these two files after live-test authorization; keep the local `expected.json` separately. The independent verifier checks exact names, byte sizes and SHA-256. Seven verifier tests passed, including corruption, truncation, missing/extra files, directory substitution and invalid expected paths.

## Live round-trip record

Investigator confirms the secret has not been rotated. Prior-exposure wording comes from plan section 4.2, which says it was pasted into development material; the actual location/circumstances have not been independently established. The source review identified potential logging leaks but did not run with or reveal the real key.

Investigator subsequently confirmed the external configuration location `C:/Users/rchia/.filesender/filesender.py.ini`. The separate read-only signed-auth probe has nine mock tests (16 total with fixture-verifier tests). It uses fixed-host HTTPS GET requests, redacted errors, no redirects/retries/mutations and no raw account/transfer output.

After the investigator explicitly approved using the existing unrotated key for this read-only check, the actual command succeeded:

```powershell
python -B tools/phase3-protocol/checkpoint1_auth_probe.py --config C:/Users/rchia/.filesender/filesender.py.ini
```

Safe result: `SIGNED_READ_SUCCEEDED`, `upload_chunk_size = 12582912`, `transfer_mutations = 0`, `uploads = 0`. This includes a signed `GET /transfer/@me`, establishing that the credential works for that read operation. It does not prove create/upload permissions, acknowledged expiry, FileSender finalization or researcher receipt. The key remains unrotated. Authorization was specifically for this read-only probe, not an external transfer or participant deployment. Account identity and returned transfer metadata were not logged.

| Evidence | Status |
| --- | --- |
| Secret rotation and protected provisioning | Not rotated; investigator explicitly authorized current key for read-only probe. External INI loaded internally, contents not printed. Prior exposure remains unverified |
| Actual client version/hash | Supplied `tools/filesender.py` reviewed; SHA-256 recorded above, release/source version unknown |
| Account identity | Not recorded; no authenticated account queried |
| Signed auth/account `/info` | Public `/info` and signed `GET /transfer/@me` passed through reviewed probe; 12 MiB chunk size |
| Engineering recipient/expiry | Investigator specifies `raymond.chia@uts.edu.au`, five days; actual server-acknowledged expiry untested |
| Create/upload/file-complete/transfer-complete | Passed for the two synthetic files on 2026-10-13 |
| Transfer ID/acknowledged expiry | `3647625`; 18 October 2026 14:11:01 Sydney |
| Researcher download and independent SHA-256 match | Investigator confirms download/manual hashes; both named local Downloads files independently match source |
| Notification count/rate-limit behavior | Notification arrival confirmed; numerical count and rate-limit behavior not measured |
| Encrypted FileSender round trip | Not run; local age proof only |

Do not mark Checkpoint 1 passed until these actual account and researcher checks are recorded. FileSender completion never establishes archive verification.

## Authorized actual transfer evidence - 2026-10-13

Investigator explicitly requested the test transfer after specifying recipient `raymond.chia@uts.edu.au` and five days. Reviewed isolated sender uploaded only the two fixed hash-verified synthetic files using the external current credential. Transfer **3647625** completed; actual acknowledged expiry is **1792293061000 epoch ms / 18 October 2026 14:11:01 Sydney**. Both files were then downloaded using recipient-route access and independently matched their exact source hashes/lengths listed above. No second transfer was created, and no FileSender delete was attempted.

Actual owner metadata omits the specification/reference's expected status field. The first post-completion guard failed on that absence; persisted identity enabled read-only reconciliation. Future sender checks require matching completion acknowledgment, owner identity/files and expiry rather than an absent status property. AARNet download requests use a same-origin HTTPS `/download.php` redirect; the successful synthetic download permitted only that host/path. No signed URLs, recipient tokens or credential contents were output.

Non-secret receipt is in ignored `tools/phase3-protocol/artifacts/test-transfer-20261013/receipt.json`. This succeeds for plaintext synthetic protocol bytes, not encrypted participant events. User notification count, independent researcher receiving/hash confirmation and encrypted FileSender/workstation feasibility remain pending. Previous "not run" rows describe the pre-transfer state and are superseded by this dated entry for the two-file engineering test.

Investigator subsequently confirmed receipt/expiry and downloaded both files to `C:/Users/rchia/Downloads`. Their manual PowerShell SHA-256 values match the original source hashes. Read-only verification of those two named files also matched exact SHA-256 and lengths. User-reported ZIP hash `fcfb49ecec968a99da6de7ab1932cd5af6088b7e7fbffdde6fd9386bf654db16` is container metadata only; no expected source ZIP exists for comparison. Human plaintext receiving evidence is complete; numerical notification count and encrypted/workstation/key-recovery evidence remain pending. No extra transfer or archive action occurred.

## Authorized encrypted-artifact upload - 2026-10-13

After preparing the two-fixture archive with the investigator public recipient key and receiving explicit "send" authorization, uploaded only `synthetic-files.tar.age` (12598472 bytes, SHA-256 `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`). Transfer **3647689** was acknowledged complete; owner metadata matched. Recipient `raymond.chia@uts.edu.au`, actual expiry **18 October 2026 14:49:22 Sydney**. The sender used no private decryption identity, and FileSender received pre-encrypted bytes. This proves encrypted artifact API upload/finalization, not researcher decrypt/hash/key-recovery or eResearch archival. Those manual checks remain pending in the crypto decision/report.

Investigator then supplied successful ciphertext hash, local age decryption, exact decrypted TAR hash and extracted-file `SHA256_VERIFIED` evidence. Codex read-only checks of those named synthetic outputs also matched. Encrypted FileSender/researcher decryption/hash interoperability therefore passes; protected backup-key recovery and institutional crypto approval remain pending. No private key was read by Codex or sent to FileSender/gateway, and no archival claim is made.
