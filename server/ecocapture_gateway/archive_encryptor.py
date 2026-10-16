"""Deterministic three-object TAR + pinned age. No private identity or HTTP client."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tarfile

from .errors import GatewayError, require

AGE_VERSION = "v1.3.2"
NAMES = ("event.json", "manifest.json", "video.mp4")


def measure(path):
    state = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while block := stream.read(128 * 1024):
            state.update(block); size += len(block)
    return {"size_bytes": size, "sha256": state.hexdigest()}


def validate_recipient(recipient):
    require(type(recipient) is str and re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", recipient) is not None,
            400, "AGE_RECIPIENT_REQUIRED")


def deterministic_tar(source, destination, expected):
    require(set(expected) == set(NAMES), 409, "ARCHIVE_OBJECT_SET")
    require(not destination.exists() and not destination.is_symlink(), 409, "ARCHIVE_OUTPUT_EXISTS")
    with destination.open("xb") as stream:
        with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name in NAMES:
                path = source / name
                require(path.is_file() and not path.is_symlink(), 409, "UNSAFE_ARCHIVE_SOURCE")
                require(measure(path) == expected[name], 409, "SOURCE_HASH_CHANGED")
                header = tarfile.TarInfo(name)
                header.size = expected[name]["size_bytes"]
                header.mode = 0o600; header.uid = header.gid = header.mtime = 0
                header.uname = header.gname = ""
                with path.open("rb") as data: archive.addfile(header, data)
                require(measure(path) == expected[name], 409, "SOURCE_HASH_CHANGED")
        stream.flush(); os.fsync(stream.fileno())
    result = measure(destination)
    # Rehash exact archive members too, detecting source replacement during tar reads.
    with tarfile.open(destination, "r:") as archive:
        require(archive.getnames() == list(NAMES), 409, "ARCHIVE_MEMBERS_CHANGED")
        for member in archive:
            require(member.isfile() and member.size == expected[member.name]["size_bytes"], 409, "ARCHIVE_MEMBER_CHANGED")
            state = hashlib.sha256()
            with archive.extractfile(member) as stream:
                while block := stream.read(128 * 1024): state.update(block)
            require(state.hexdigest() == expected[member.name]["sha256"], 409, "ARCHIVE_MEMBER_CHANGED")
    return result


def age_encrypt(executable, source, destination, recipient, max_bytes):
    validate_recipient(recipient)
    require(not destination.exists() and not destination.is_symlink(), 409, "CIPHERTEXT_OUTPUT_EXISTS")
    try:
        version = subprocess.run([str(executable), "--version"], capture_output=True, timeout=10, check=True)
        require(version.stdout.decode().strip() == AGE_VERSION, 409, "AGE_VERSION_MISMATCH")
        with destination.open("xb") as output:
            encrypted = subprocess.run([str(executable), "--encrypt", "--recipient", recipient, str(source)],
                stdout=output, stderr=subprocess.DEVNULL, timeout=180)
            output.flush(); os.fsync(output.fileno())
        require(encrypted.returncode == 0, 503, "AGE_ENCRYPTION_FAILED")
        with destination.open("rb") as stream:
            require(stream.read(22).startswith(b"age-encryption.org/v1\n"), 409, "AGE_FORMAT_FAILED")
        result = measure(destination)
        require(0 < result["size_bytes"] <= max_bytes, 413, "CIPHERTEXT_LIMIT")
        return result
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise GatewayError(503, "AGE_EXECUTION_FAILED") from None
