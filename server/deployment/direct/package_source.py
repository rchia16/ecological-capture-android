"""Create a source-only, checksummed deployment bundle; excludes local credentials and artifacts."""
import argparse
import hashlib
import io
from pathlib import Path
import tarfile

REPO = Path(__file__).resolve().parents[3]
DIRECTORIES = ("server/ecocapture_gateway", "server/ecocapture_direct", "server/deployment/direct", "documentation/schemas")
FILES = ("server/requirements.lock", "server/deployment/gb10/install_age.py", "server/tests/test_direct.py")
SUFFIXES = {".py", ".html", ".js", ".css", ".json", ".def", ".md", ".service"}


def sources():
    selected = [REPO / name for name in FILES]
    for name in DIRECTORIES:
        selected.extend(path for path in (REPO / name).rglob("*") if path.is_file() and
                        path.suffix in SUFFIXES and not any(part.startswith(".") or part in ("__pycache__", "node_modules") for part in path.relative_to(REPO / name).parts))
    for path in sorted(set(selected)):
        if path.is_symlink() or ".local." in path.name: raise ValueError("LOCAL_FILE_REFUSED")
        yield path.relative_to(REPO).as_posix(), path.read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("output", type=Path)
    args = parser.parse_args()
    entries = list(sources())
    manifest = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in entries).encode()
    with args.output.open("xb") as stream, tarfile.open(fileobj=stream, mode="w:gz") as archive:
        for name, data in [*entries, ("SHA256SUMS", manifest)]:
            entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = 0o644
            archive.addfile(entry, io.BytesIO(data))
    print(hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__": main()
