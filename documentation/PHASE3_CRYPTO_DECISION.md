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
