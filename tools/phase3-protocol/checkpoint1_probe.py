"""Offline synthetic FileSender fixtures and age feasibility proof; never uploads."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


CHUNK_BYTES_OBSERVED = 12_582_912  # Public /info observation, 2026-10-12.
NAMES = ("synthetic-note.txt", "synthetic-pattern.bin")
NOTE = b"EcoCapture Checkpoint 1: wholly synthetic, non-participant test bytes.\n"
BLOCK = bytes(range(256)) * 256
PATTERN_SIZE = CHUNK_BYTES_OBSERVED + 17


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(64 * 1024), b""):
            value.update(block)
    return {"size_bytes": path.stat().st_size, "sha256": value.hexdigest()}


def prepare(destination):
    # A fresh directory prevents overwriting other data or mixing private files in.
    destination.mkdir(parents=True, exist_ok=False)
    (destination / NAMES[0]).write_bytes(NOTE)
    with (destination / NAMES[1]).open("wb") as stream:
        remaining = PATTERN_SIZE
        while remaining:
            block = BLOCK[:min(remaining, len(BLOCK))]
            stream.write(block)
            remaining -= len(block)
    evidence = {"schema": "ecocapture.checkpoint1.synthetic.v1",
                "files": {name: digest(destination / name) for name in NAMES}}
    (destination / "expected.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def verify(expected_path, downloads):
    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    if expected.get("schema") != "ecocapture.checkpoint1.synthetic.v1" or set(expected.get("files", {})) != set(NAMES):
        raise ValueError("invalid_expected_fixture")
    if {path.name for path in downloads.iterdir()} != set(NAMES):
        raise ValueError("unexpected_download_files")
    for name in NAMES:
        path = downloads / name
        if path.is_symlink() or not path.is_file() or digest(path) != expected["files"][name]:
            raise ValueError("download_hash_mismatch")
    return {"status": "SHA256_VERIFIED", "files": expected["files"]}


def bundle(fixtures, destination):
    # This two-file feasibility archive is NOT a Phase 3 event export.
    with tarfile.open(destination, "w", format=tarfile.USTAR_FORMAT) as archive:
        for name in NAMES:
            source = fixtures / name
            entry = tarfile.TarInfo(name)
            entry.size = source.stat().st_size
            entry.mode = 0o600
            entry.mtime = 0
            with source.open("rb") as stream:
                archive.addfile(entry, stream)


def command(arguments, expected_success=True):
    # No raw CLI diagnostics, private key or recipient values reach evidence/logs.
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True, timeout=60)
    if (result.returncode == 0) != expected_success:
        raise ValueError("age_command_result_unexpected")
    return result.stdout


def age_proof(fixtures, age, keygen, artifact_root):
    before = {name: digest(fixtures / name) for name in NAMES}
    artifact_root.mkdir(parents=True, exist_ok=True)
    # Only disposable keys; temporary key/decryption files are removed on exit.
    # This is not a production key backup, secure-erasure or separate-host test.
    with tempfile.TemporaryDirectory(prefix="synthetic-age-", dir=artifact_root) as temporary:
        work = Path(temporary)
        key, wrong = work / "key.txt", work / "wrong.txt"
        command([keygen, "-o", key])
        command([keygen, "-o", wrong])
        recipient = command([keygen, "-y", key]).decode("ascii").strip()
        original, repeated = work / "original.tar", work / "repeated.tar"
        bundle(fixtures, original)
        bundle(fixtures, repeated)
        if digest(original) != digest(repeated):
            raise ValueError("archive_not_reproducible")
        encrypted, second = work / "encrypted.age", work / "second.age"
        command([age, "-r", recipient, "-o", encrypted, original])
        command([age, "-r", recipient, "-o", second, original])
        if digest(encrypted)["sha256"] == digest(second)["sha256"]:
            raise ValueError("ciphertext_randomization_failed")
        decrypted = work / "decrypted.tar"
        command([age, "-d", "-i", key, "-o", decrypted, encrypted])
        if digest(original) != digest(decrypted):
            raise ValueError("decryption_hash_mismatch")
        command([age, "-d", "-i", wrong, "-o", work / "wrong.tar", encrypted], False)
        corrupted = work / "corrupted.age"
        shutil.copyfile(encrypted, corrupted)
        with corrupted.open("r+b") as stream:
            stream.seek(corrupted.stat().st_size // 2)
            value = stream.read(1)
            stream.seek(-1, 1)
            stream.write(bytes([value[0] ^ 1]))
        command([age, "-d", "-i", key, "-o", work / "corrupted.tar", corrupted], False)
        backup = work / "backup.txt"
        shutil.copyfile(key, backup)
        key.unlink()  # Disposable key created in this temporary directory only.
        restored = work / "restored.tar"
        command([age, "-d", "-i", backup, "-o", restored, encrypted])
        if digest(original) != digest(restored):
            raise ValueError("backup_restore_hash_mismatch")
        extracted = work / "verified"
        extracted.mkdir()
        with tarfile.open(decrypted) as archive:
            members = archive.getmembers()
            if [member.name for member in members] != list(NAMES) or any(not member.isfile() for member in members):
                raise ValueError("unexpected_archive_members")
            # Copy exactly the fixed fixture names; never extract arbitrary paths.
            for member in members:
                with archive.extractfile(member) as source, (extracted / member.name).open("wb") as target:
                    shutil.copyfileobj(source, target, 64 * 1024)
        verify(fixtures / "expected.json", extracted)
        if before != {name: digest(fixtures / name) for name in NAMES}:
            raise ValueError("source_bytes_changed")
        evidence = {"status": "LOCAL_SYNTHETIC_PROOF_PASSED", "files": before,
                    "archive": digest(original), "encrypted_artifact": digest(encrypted),
                    "checks": ["deterministic_archive", "randomized_ciphertext", "decrypt_exact_bytes",
                               "wrong_key_rejected", "corruption_rejected", "disposable_backup_restores",
                               "extracted_files_verified", "source_bytes_unchanged"],
                    "network_transfer": False, "separate_workstation": False}
    (artifact_root / "local-age-proof.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    creation = commands.add_parser("prepare")
    creation.add_argument("destination", type=Path)
    verification = commands.add_parser("verify")
    verification.add_argument("expected", type=Path)
    verification.add_argument("downloads", type=Path)
    proof = commands.add_parser("age-proof")
    proof.add_argument("fixtures", type=Path)
    proof.add_argument("--age", required=True, type=Path)
    proof.add_argument("--keygen", required=True, type=Path)
    proof.add_argument("--artifacts", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            evidence = prepare(args.destination)
        elif args.action == "verify":
            evidence = verify(args.expected, args.downloads)
        else:
            evidence = age_proof(args.fixtures, args.age, args.keygen, args.artifacts)
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError):
        parser.exit(1, "Synthetic check failed; no live-transfer success claimed.\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
