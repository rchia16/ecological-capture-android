# Phase 3 crypto decision - feasibility evidence

Date: 2026-10-12 (Australia/Sydney).
Status: PROPOSED - local synthetic proof passed; institutional selection and live interoperability pending.

## Proposed pilot mechanism

Phone -> gateway uses validated HTTPS. The gateway will bundle the immutable event and encrypt it with an established age implementation using the investigator's public recipient key. FileSender receives ciphertext; the research workstation holds the private identity and decrypts before independently verifying source hashes. The gateway still sees plaintext and requires approved encrypted storage, staging retention and host controls. This is the plan's preferred proposal, not an approved deployment decision.

The [AARNet encryption guide](https://support.aarnet.edu.au/hc/en-us/articles/5489088169743-Use-encryption-when-sending-files) describes browser AES-GCM/password processing. It does not establish encryption for ordinary API uploads. Optional encryption in the Python client remains a separate path requiring real decrypt/download interoperability testing. Browser voucher support does not resolve this issue.

For the age proposal, ordinary FileSender upload carries an already encrypted artifact. Do not enable another FileSender encryption layer by accident, or invent custom AES chunk handling. Cryptographic library/key/runtime choices for the actual approved gateway remain subject to review.

## Local disposable proof actually executed

Used [official age v1.3.2](https://github.com/FiloSottile/age/releases/tag/v1.3.2), Windows AMD64; `age --version` returned `v1.3.2`. Downloaded archive SHA-256 matched the digest published by GitHub's release metadata over HTTPS:

`f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7`

The optional transparency proof was not independently verified. This is local feasibility evidence using that downloaded binary, not a production supply-chain approval.

`checkpoint1_probe.py age-proof` generated disposable X25519 identities with age-keygen. It packed exactly the two synthetic protocol files into a deterministic TAR, encrypted/decrypted it, safely copied the two expected regular members and independently checked their sizes/hashes. All eight checks passed:

- Repeated plaintext TAR builds had identical bytes.
- Repeated encryption produced different ciphertext.
- Correct-key decryption matched the complete plaintext archive hash.
- A different disposable key failed decryption.
- A flipped ciphertext byte failed decryption.
- A temporary key backup decrypted after the original disposable key file was removed.
- Extracted files matched the trusted fixture record.
- Source fixture hashes stayed unchanged.

| Artifact from this run | Bytes | SHA-256 |
| --- | ---: | --- |
| Two-file plaintext TAR | 12595200 | `4e885c67adbd3584279db77815dfa13a231077a3d1fd696871a76c0000fe642f` |
| Encrypted TAR | 12598472 | `7d8e47a4c7dd4dbd25edbab68cd188bf8d724b316577dd22ff2100427abfa729` |

Ciphertext hashes will differ on new runs. The proof's temporary keys/plaintext/ciphertext were removed on normal completion; only a non-secret hash/check summary remains in ignored artifacts. Temporary-file removal is not guaranteed secure erasure. No production identity was created, provisioned, backed up or used.

This is a two-file synthetic archive, **not** `ecocapture.event.v1` or the later three-file ecological event export. Sender and receiver steps ran on the same Windows host. No FileSender transfer or independent researcher workstation was involved.

## Required decisions and proof before selection is final

1. Investigator/institution approves the gateway plaintext boundary, age representation, key management and hosting/retention controls.
2. Assign a private-key custodian, approved protected storage and independently controlled recovery copy. Keep private keys off Android and the gateway; provision only public recipient keys there.
3. Demonstrate decrypt/hash verification and key recovery on the approved researcher workstation with harmless data, including wrong/lost-key procedure.
4. Complete an authorized encrypted FileSender upload/download and compare ciphertext before decrypting, then compare source file hashes after decryption.
5. Define key version/rotation and retention of old decrypt capability for already transmitted artifacts. Avoid loss of access to historical ciphertext during key rotation.
6. Record the chosen mechanism and actual institutional approval reference without exposing private identities or credentials.

Do not relax encryption if any of these checks fails. No real participant upload is authorized by this local proof.

## Researcher public-key preparation - 2026-10-13

Investigator reported completing key/hash preparation. Read only `C:/Users/rchia/.ecocapture-keys/checkpoint1-public.txt` and validated its single public recipient:

`age1k4d08sjgx3twvq2fffmyedkqxav8ttqtlu58zllz3dv0rlfk7pqs7w3u8d`

Private identity file existence was checked, but its contents were not read or printed. A protected backup location was requested and is not yet recorded. A public encryption recipient is distinct from a SHA-256 file digest; it is the value used to encrypt.

Using only that public recipient, created a deterministic TAR containing the two hash-verified synthetic fixtures, then encrypted it with the verified age tool. No network transfer occurred during preparation.

| Prepared artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `synthetic-files.tar` | 12595200 | `4e885c67adbd3584279db77815dfa13a231077a3d1fd696871a76c0000fe642f` |
| `synthetic-files.tar.age` | 12598472 | `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889` |

Artifacts and non-secret expected metadata are ignored under `tools/phase3-protocol/artifacts/encrypted-test-20261013/`. The engineering sender accepts only its exact name/size/hash in encrypted mode. Two new profile tests passed; combined suite is 28 passed. No private identity was provisioned to the sender. The subsequently authorized upload is recorded below.

## Authorized encrypted transfer - 2026-10-13

Investigator explicitly instructed "send". After checking that the interrupted prior turn had not created a local transfer job, sent the exact prepared ciphertext once to `raymond.chia@uts.edu.au`, with five-day expiry. FileSender transfer **3647689** completed with acknowledged file/transfer completion and matching owner file metadata. Server expiry: **1792295362000 epoch ms / 18 October 2026 14:49:22 Sydney**. Ciphertext hash and size match the table above. No extra transfer was created and no private key was used by the sender.

Non-secret receipt is ignored under `tools/phase3-protocol/artifacts/encrypted-transfer-20261013/receipt.json`. Researcher download/ciphertext hash, decryption, original-file verification and protected backup-key recovery remain pending. API completion does not establish those results or archival.

## Your manual decryption and recovery checks

The encrypted transfer is now ready at `raymond.chia@uts.edu.au`: transfer 3647689, subject suffix `659cfeb0-70ed-4833-a33d-25e34cef8cf9`. Download `synthetic-files.tar.age` to Downloads and verify its SHA-256 against the ciphertext value above. Do not proceed with a mismatched file.

Run these locally on the approved researcher workstation from the project folder. They read your private identity locally; do not paste it or any password into chat. Use a new verification directory so previous output is not overwritten.

```powershell
$ageTool = 'D:/Ray/StudioProjects/ecological-capture-android/tools/phase3-protocol/artifacts/age.exe'
$downloadedCiphertext = 'C:/Users/rchia/Downloads/synthetic-files.tar.age'
$researcherIdentity = 'C:/Users/rchia/.ecocapture-keys/checkpoint1-key.txt'
$verificationDirectory = 'C:/Users/rchia/Downloads/ecocapture-encrypted-check'

Get-FileHash -LiteralPath $downloadedCiphertext -Algorithm SHA256
if ((Get-FileHash -LiteralPath $downloadedCiphertext -Algorithm SHA256).Hash -ne '4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889') { throw 'Downloaded ciphertext hash mismatch.' }
New-Item -ItemType Directory -Path $verificationDirectory -ErrorAction Stop | Out-Null
& $ageTool --decrypt -i $researcherIdentity -o "$verificationDirectory/synthetic-files.tar" $downloadedCiphertext
if ($LASTEXITCODE -ne 0) { throw 'Decryption failed; do not use partial output.' }
$decryptedHash = (Get-FileHash -LiteralPath "$verificationDirectory/synthetic-files.tar" -Algorithm SHA256).Hash
if ($decryptedHash -ne '4e885c67adbd3584279db77815dfa13a231077a3d1fd696871a76c0000fe642f') { throw 'Decrypted archive hash mismatch.' }

New-Item -ItemType Directory -Path "$verificationDirectory/files" -ErrorAction Stop | Out-Null
tar -xf "$verificationDirectory/synthetic-files.tar" -C "$verificationDirectory/files"
if ($LASTEXITCODE -ne 0) { throw 'Extraction failed.' }
python -B tools/phase3-protocol/checkpoint1_probe.py verify tools/phase3-protocol/artifacts/fixtures/expected.json "$verificationDirectory/files"
```

Extraction is permitted only after the exact known synthetic archive hash matches. Expected verifier result: `SHA256_VERIFIED` for both original files.

For recovery, repeat decryption using the protected **backup identity file** as `-i`, with a fresh output such as `recovered-from-backup.tar`. Its SHA-256 must equal the same plaintext archive hash. Do not delete or move the original identity; selecting the backup file proves whether that copy can restore access. Provide the backup path only, never its key contents, and record the approved separate storage/custodian. Exact recovery command will be supplied once the actual backup path is known.

Report ciphertext hash match, decryption success, `SHA256_VERIFIED`, backup-key recovery result and the workstation used. These checks remain pending until actually executed. Institutional crypto/key-custody approval is separate from this synthetic interoperability test.

## Researcher encrypted receipt and decryption result

Investigator supplied the PowerShell transcript from the research Windows workstation: downloaded ciphertext SHA-256 matched `4fb64c6d844dd60393bf445fb8a1f591dac164ad6f7e202f56de9548a5f48889`; local age decryption succeeded using the researcher-held identity; decrypted TAR matched `4e885c67adbd3584279db77815dfa13a231077a3d1fd696871a76c0000fe642f`. Safe extraction and the independent verifier returned `SHA256_VERIFIED` for the 71-byte note and 12582929-byte pattern, both retaining the original source hashes.

Codex also read only the named downloaded ciphertext, decrypted TAR and extracted synthetic files to reconfirm their hashes. Private identity contents were not read by Codex, uploaded or printed. The encrypted FileSender -> researcher download -> local decrypt -> original-file verification engineering path now passes. No institutionally approved workstation status or archival is inferred from the transcript.

Protected backup-key recovery has not yet been demonstrated; backup path/storage/custodian and institutional crypto selection remain pending. Next manual check: decrypt the same ciphertext with the **backup identity** to a fresh output, preserving the original key. The recovered archive must have the same trusted TAR hash above.
