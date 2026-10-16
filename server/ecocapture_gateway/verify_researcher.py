"""Researcher-operated age decrypt + independent hashes. Never reads/prints identity contents."""
import argparse
import json
import re
from pathlib import Path
import shutil
import subprocess
import tarfile

from .archive_encryptor import AGE_VERSION, NAMES, measure
from .errors import GatewayError, require
from .manifest_validator import verify


def verify_received(ciphertext, expected_file, age_tool, identity, output):
    for path, code in ((expected_file, "EXPECTED_FILE_UNAVAILABLE"),
                       (ciphertext, "DOWNLOADED_CIPHERTEXT_UNAVAILABLE"),
                       (age_tool, "AGE_EXECUTABLE_UNAVAILABLE"),
                       (identity, "RESEARCHER_IDENTITY_UNAVAILABLE")):
        require(path.is_file(), 400, code)
    try:
        expected = json.loads(expected_file.read_text(encoding="utf-8-sig"))
    except (ValueError, UnicodeError):
        raise GatewayError(400, "EXPECTED_FILE_INVALID_JSON") from None
    except OSError:
        raise GatewayError(400, "EXPECTED_FILE_UNREADABLE") from None
    require(type(expected) is dict and expected.get("participant_data") is False and
            type(expected.get("files")) is dict and set(expected["files"]) == set(NAMES),
            400, "TRUSTED_SYNTHETIC_EXPECTATIONS_REQUIRED")
    measurements = [{"size_bytes": expected.get(prefix + "_size_bytes"), "sha256": expected.get(prefix + "_sha256")}
                    for prefix in ("ciphertext", "archive")] + list(expected["files"].values())
    require(all(type(item) is dict and type(item.get("size_bytes")) is int and item["size_bytes"] > 0 and
                type(item.get("sha256")) is str and re.fullmatch(r"[0-9a-f]{64}", item["sha256"])
                for item in measurements), 400, "EXPECTED_FILE_SCHEMA_INVALID")
    try:
        downloaded = measure(ciphertext)
    except OSError:
        raise GatewayError(400, "DOWNLOADED_CIPHERTEXT_UNREADABLE") from None
    require(downloaded == {"size_bytes": expected["ciphertext_size_bytes"], "sha256": expected["ciphertext_sha256"]},
            409, "DOWNLOADED_CIPHERTEXT_HASH_MISMATCH")
    require(not output.exists() and not output.is_symlink(), 409, "FRESH_VERIFICATION_DIRECTORY_REQUIRED")
    try:
        version = subprocess.run([str(age_tool.resolve()), "--version"], capture_output=True, check=True, timeout=10)
    except subprocess.TimeoutExpired:
        raise GatewayError(400, "AGE_VERSION_CHECK_TIMED_OUT") from None
    except (OSError, subprocess.CalledProcessError):
        raise GatewayError(400, "AGE_EXECUTION_FAILED") from None
    require(version.stdout.decode().strip() == AGE_VERSION, 409, "AGE_VERSION_MISMATCH")
    try:
        output.mkdir(mode=0o700)
    except OSError:
        raise GatewayError(400, "VERIFICATION_OUTPUT_UNAVAILABLE") from None
    temporary = output / "decrypted.part"
    try:
        result = subprocess.run([str(age_tool.resolve()), "--decrypt", "-i", str(identity), "-o", str(temporary), str(ciphertext)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        temporary.unlink(missing_ok=True)
        raise GatewayError(400, "AGE_DECRYPTION_EXECUTION_FAILED") from None
    if result.returncode:
        temporary.unlink(missing_ok=True)
        require(False, 409, "RESEARCHER_DECRYPTION_FAILED")
    require(measure(temporary) == {"size_bytes": expected["archive_size_bytes"], "sha256": expected["archive_sha256"]},
            409, "DECRYPTED_ARCHIVE_HASH_MISMATCH")
    archive_file = output / "approved-event.tar"; temporary.replace(archive_file)
    directory = output / "files"; directory.mkdir(mode=0o700)
    with tarfile.open(archive_file, "r:") as archive:
        members = archive.getmembers()
        require([m.name for m in members] == list(NAMES) and all(m.isfile() for m in members), 409, "UNSAFE_ARCHIVE_MEMBERS")
        for member in members:
            require(member.size == expected["files"][member.name]["size_bytes"], 409, "ARCHIVE_MEMBER_SIZE_MISMATCH")
            with archive.extractfile(member) as source, (directory / member.name).open("xb") as destination:
                shutil.copyfileobj(source, destination, length=128 * 1024)
    checked = verify(directory, expected["files"]["manifest.json"]["sha256"],
                     expected["files"]["event.json"]["sha256"], expected["files"]["video.mp4"]["sha256"])
    return {"status": "CHECKPOINT5_RESEARCHER_SHA256_VERIFIED", "ciphertext_sha256": expected["ciphertext_sha256"],
            "archive_sha256": expected["archive_sha256"], "files": checked["files"], "researcher_verified": True, "archived": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ciphertext", type=Path, required=True)
    parser.add_argument("--expected", type=Path, required=True)
    parser.add_argument("--age-tool", type=Path, required=True)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify_received(args.ciphertext, args.expected, args.age_tool, args.identity, args.output), indent=2))


if __name__ == "__main__":
    try: main()
    except GatewayError as error:
        print(json.dumps({"status": "RESEARCHER_VERIFICATION_FAILED", "code": error.code})); raise SystemExit(1)
    except Exception:
        print('{"status":"RESEARCHER_VERIFICATION_FAILED","code":"LOCAL_VERIFICATION_FAILURE"}'); raise SystemExit(1)
