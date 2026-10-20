"""Install a checksum-verified stable Go SDK into this checkout, never globally."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

root = Path(__file__).resolve().parent / ".tools"
root.mkdir(exist_ok=True)
with urllib.request.urlopen("https://go.dev/dl/?mode=json", timeout=30) as response:
    releases = json.load(response)
release = next(row for row in releases if row["stable"])
asset = next(row for row in release["files"] if row["os"] == "windows" and row["arch"] == "amd64" and row["kind"] == "archive")
destination = root / asset["filename"]
if not destination.exists():
    with urllib.request.urlopen("https://go.dev/dl/" + asset["filename"], timeout=60) as source, destination.open("xb") as output:
        while block := source.read(1024 * 1024): output.write(block)
with destination.open("rb") as stream:
    if hashlib.file_digest(stream, "sha256").hexdigest() != asset["sha256"]:
        raise ValueError("GO_CHECKSUM_MISMATCH")
with zipfile.ZipFile(destination) as archive:
    for member in archive.infolist():
        path = (root / member.filename).resolve()
        if not path.is_relative_to(root.resolve()): raise ValueError("INVALID_GO_ARCHIVE")
    archive.extractall(root)
print(release["version"])
