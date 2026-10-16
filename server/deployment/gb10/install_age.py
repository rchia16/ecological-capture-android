"""Build-time download/verification of official age v1.3.2 Linux ARM64, no keys."""
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request

URL = "https://github.com/FiloSottile/age/releases/download/v1.3.2/age-v1.3.2-linux-arm64.tar.gz"
SHA256 = "6b8dc4333c53a5a57c9e5834e3a48f92605d7154014cd07269ff3327db5d37f4"
SIZE = 17771923


def main():
    # Public release asset only. No runtime signing secrets, private key or identities.
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(SIZE + 1)
    if len(data) != SIZE or hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit("AGE_RELEASE_HASH_MISMATCH")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for name in ("age", "age-keygen"):
            member = archive.getmember("age/" + name)
            if not member.isfile() or member.size > 32 * 1024 * 1024:
                raise SystemExit("AGE_RELEASE_MEMBER_INVALID")
            with archive.extractfile(member) as source, (Path("/usr/local/bin") / name).open("xb") as target:
                target.write(source.read())
            (Path("/usr/local/bin") / name).chmod(0o755)


if __name__ == "__main__": main()
