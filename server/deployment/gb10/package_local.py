"""Create a reviewed source-only GB10 bundle. No SSH, uploads, keys, state or virtualenv."""
import hashlib
import io
from pathlib import Path
import tarfile


def build(root, output):
    root = Path(root).resolve()
    server = root / "server"
    files = [server / "requirements.lock", server / "pyproject.toml", server / "README.md"]
    files.append(server / "deployment/gb10/gateway.synthetic.json")
    for folder, suffixes in (("ecocapture_gateway", {".py", ".json"}), ("tests", {".py"}),
                             ("deployment/gb10", {".py", ".sh", ".def", ".md"})):
        files += [p for p in (server / folder).rglob("*") if p.is_file() and p.suffix in suffixes
                  and "__pycache__" not in p.parts]
    files += list((root / "documentation/schemas").glob("*.json"))
    documents = [root / "documentation" / name for name in (
        "PHASE3_GATEWAY_API.md", "PHASE3_CHECKPOINT4_REPORT.md", "PHASE3_OUTSTANDING_CHECKS.md")]
    checkpoint5_report = root / "documentation/PHASE3_CHECKPOINT5_REPORT.md"
    if checkpoint5_report.is_file(): documents.append(checkpoint5_report)
    files += documents
    entries = {}
    for file in sorted(set(files)):
        if file.is_symlink() or not (file.resolve().is_relative_to(server) or
                                    file.resolve().is_relative_to(root / "documentation/schemas") or file in documents):
            raise ValueError("UNSAFE_PACKAGE_SOURCE")
        name = file.relative_to(root).as_posix()
        entries[name] = file.read_bytes().replace(b"\r\n", b"\n")
    checksums = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in entries.items())
    entries["SHA256SUMS"] = checksums.encode()
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as file, tarfile.open(fileobj=file, mode="w:gz") as archive:
        for name, data in entries.items():
            info = tarfile.TarInfo(name)
            info.size = len(data); info.mtime = 0
            info.mode = 0o755 if name.endswith(".sh") else 0o644
            archive.addfile(info, io.BytesIO(data))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(f"{digest}  {output.name}\n", encoding="ascii")
    return {"status": "LOCAL_GB10_SOURCE_BUNDLE_READY", "files": len(entries),
            "sha256": digest, "remote_actions": 0}


if __name__ == "__main__":
    import argparse
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(build(Path(__file__).resolve().parents[3], args.output)))
