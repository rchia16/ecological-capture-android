# Checkpoint 1 synthetic protocol fixtures

This directory prepares harmless files, independently verifies downloaded bytes, and runs a local age feasibility proof. It never reads FileSender configuration, authenticates, creates a transfer, sends email or uploads anything. It does not implement the later gateway or event exporter.

Use Python 3. The helper needs only the standard library. Run from the repository root:

```powershell
python -B -m unittest discover -s tools/phase3-protocol -p 'test_*.py' -v
python -B tools/phase3-protocol/checkpoint1_probe.py prepare tools/phase3-protocol/artifacts/fixtures
```

Preparation requires a new directory and refuses to overwrite an existing one. For repeat runs choose a fresh directory. The two files are deterministic generated text/bytes; the binary is 12 MiB + 17 bytes to exercise two chunks at the publicly observed AARNet chunk size. `expected.json` is a local comparison record, not a file to upload.

For a separately authorized live test, upload **only** `synthetic-note.txt` and `synthetic-pattern.bin`. Download them into a fresh directory containing exactly those two files. Preserve `expected.json` outside that download directory, then verify:

```powershell
python -B tools/phase3-protocol/checkpoint1_probe.py verify tools/phase3-protocol/artifacts/fixtures/expected.json tools/phase3-protocol/artifacts/downloads
```

The expected record must be the trusted source record, not a manifest downloaded with the files. Success proves matching bytes against that record; it does not prove transfer completion, researcher identity, expiry or archival.

## Local age check

The engineering run used official age v1.3.2 Windows AMD64 tools, downloaded into ignored `artifacts/`. The archive SHA-256 was compared with GitHub's release asset digest over HTTPS:

`f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7`

Source: [official pinned release](https://github.com/FiloSottile/age/releases/tag/v1.3.2). Transparency proof was not independently verified. Do not substitute an unverified binary or turn off TLS checks.

```powershell
python -B tools/phase3-protocol/checkpoint1_probe.py age-proof tools/phase3-protocol/artifacts/fixtures --age tools/phase3-protocol/artifacts/age.exe --keygen tools/phase3-protocol/artifacts/age-keygen.exe --artifacts tools/phase3-protocol/artifacts
```

This creates disposable keys inside a temporary ignored directory, verifies deterministic TAR bytes, randomized encryption, exact decryption/extraction, wrong-key/corruption rejection, a disposable key-copy recovery and unchanged sources. The temporary keys and archives are removed when the command completes; `local-age-proof.json` retains hashes and check names only. Removal is not guaranteed secure erasure. No production key is accepted by this command. The check occurs on one host and does not establish independent researcher receipt or production key recovery.

## Pending live test

A separate read-only auth checker is prepared and mock-tested:

```powershell
python -B tools/phase3-protocol/checkpoint1_auth_probe.py --config C:/Users/rchia/.filesender/filesender.py.ini
```

The investigator explicitly authorized use of the existing unrotated credential for this read-only probe, and its actual run returned `SIGNED_READ_SUCCEEDED` with chunk size 12582912 bytes, zero mutations and zero uploads. This exception does not authorize an upload or establish rotation. The checker internally loads only the explicitly named outside-repository INI, validates the fixed AARNet API host, and performs `GET /info` followed by signed `GET /transfer/@me`. It rejects redirects, uses normal TLS validation and bounded requests/responses, does not retry, and emits only fixed status/error codes and chunk size. It does not import the supplied CLI, log credentials/account/transfer contents, mutate a transfer or upload files. The configuration path is the only command-line credential-related value; secret values never go in arguments.

Nine mock tests cover signature compatibility, literal percent handling in synthetic configuration, fixed host/outside-repository restrictions, safe output/failure handling, redirect/size rejection and request bounds. Together with seven download-verifier tests, **16 tests passed** with ResourceWarning treated as an error. An initial fake HTTP error cleanup warning led to explicitly closing HTTPError responses; the final suite passed without it. This mock result is not signed account validation.

See [protocol record](../../documentation/PHASE3_AARNET_PROTOCOL.md) and [crypto decision](../../documentation/PHASE3_CRYPTO_DECISION.md). The investigator-supplied `tools/filesender.py` remains unsafe for direct credential use; disabling verbose alone does not prevent sensitive errors. Do not execute or import the unmodified script. The separately reviewed engineering tools were used under the investigator's explicit current-key authorization for the read-only probe and later synthetic transfer described below. FileSender INI files are ignored; contents were loaded internally only by the authorized tools and never printed.

## Authorized synthetic transfer actually executed

On 2026-10-13 the investigator explicitly requested the two-file test using the current credential, approved email and five days. Executed once:

```powershell
python -B tools/phase3-protocol/checkpoint1_send_test.py --config C:/Users/rchia/.filesender/filesender.py.ini --fixtures tools/phase3-protocol/artifacts/fixtures --job tools/phase3-protocol/artifacts/test-transfer-20261013 --recipient raymond.chia@uts.edu.au --days 5
```

Do not rerun this to create another transfer. Existing job directories are refused; uncertain results require reconciliation. Transfer `3647625` completed and its two downloaded files matched source hashes. Expiry: 18 October 2026 14:11:01 Sydney. Safe receipt and engineering downloads are ignored local artifacts. No raw CLI, credentials in arguments, automatic create retries or failure deletion were used. Current suite has 26 passing Python tests.

For manual researcher receiving, check the notification and download both files to a folder containing exactly those two files (extract a ZIP first if necessary). From the repository root, replacing the final path with your actual download folder:

```powershell
python -B tools/phase3-protocol/checkpoint1_probe.py verify tools/phase3-protocol/artifacts/fixtures/expected.json "D:/path/to/downloaded-files"
```

Expected result: `SHA256_VERIFIED`. Report notification count, displayed expiry and verification result, without recipient links/tokens. The automated download already passed, but independent human/researcher receipt and encrypted-workstation verification remain separate pending evidence.
# Checkpoint 2 local inspection

Local snapshots, schemas, independent verification and USB inspection are documented in `documentation/PHASE3_EVENT_SCHEMA.md`. `verify_snapshot.py` requires a trusted manifest digest. `pull_snapshot.py` reads only an explicitly selected disposable snapshot and extracts fixed regular filenames; it makes no FileSender request. `checkpoint2_retention.py` computes hashes on the device without copying protected file/DB contents. Generated cross-language fixtures live under ignored `app/build/c2/`; phone/host proof outputs remain under ignored `artifacts/`.

Checkpoint 2 tests:

```powershell
# First run module-qualified Android unit tests to generate synthetic Kotlin fixtures.
python -B -m unittest discover -s tools/phase3-protocol -p 'test_*snapshot.py' -v
```

Unfinished investigator checks are maintained in `documentation/PHASE3_OUTSTANDING_CHECKS.md`.
