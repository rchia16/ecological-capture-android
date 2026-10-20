"""Disposable synthetic end-to-end probe. Never accepts participant files."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import secrets
import subprocess
import tarfile
import tempfile
import time
import uuid

import httpx
from ecocapture_gateway.synthetic_fixture import files


def run(base, enrolment, age, private=None):
    code = Path(enrolment).read_text(encoding="utf-8").splitlines()[-1]
    owner, event, snapshot = (str(uuid.uuid4()) for _ in range(3))
    token = secrets.token_urlsafe(32)
    with httpx.Client(base_url=base.rstrip("/"), trust_env=False, timeout=120) as client:
        def request(method, path, **kwargs):
            response = client.request(method, "/v2" + path, **kwargs)
            if response.status_code != 200: raise RuntimeError("SYNTHETIC_REQUEST_FAILED")
            return response.json()
        profile = request("POST", "/enrol", json={"code": code, "installation_id": owner, "token": token})
        client.headers["Authorization"] = "Bearer " + token
        source = files(event, snapshot)
        metadata = json.loads(source["event.json"])
        metadata["study_participant_code"] = profile["participant_code"]
        source["event.json"] = json.dumps(metadata).encode()
        manifest = json.loads(source["manifest.json"])
        manifest["files"][1].update(size_bytes=len(source["event.json"]), sha256=hashlib.sha256(source["event.json"]).hexdigest())
        source["manifest.json"] = json.dumps(manifest).encode()
        with tempfile.TemporaryDirectory(prefix="ecocapture-synthetic-") as tmp:
            plain = Path(tmp) / "synthetic.tar"; cipher = Path(tmp) / "synthetic.tar.age"
            with tarfile.open(plain, "w", format=tarfile.USTAR_FORMAT) as archive:
                for name, data in source.items():
                    entry = tarfile.TarInfo(name); entry.size = len(data); archive.addfile(entry, io.BytesIO(data))
            arguments = [str(age), "--encrypt", "--output", str(cipher)]
            for key in profile["recipients"]: arguments.extend(["--recipient", key])
            subprocess.run([*arguments, str(plain)], check=True, capture_output=True)
            data = cipher.read_bytes()
        body = {"snapshot_id": snapshot, "event_id": event, "recorded_at_epoch_ms": metadata["capture"]["created_at_epoch_ms"],
                "size_bytes": len(data), "ciphertext_sha256": hashlib.sha256(data).hexdigest(),
                "manifest_sha256": hashlib.sha256(source["manifest.json"]).hexdigest(), "key_versions": profile["key_versions"]}
        request("POST", "/packages", json=body)
        # Small chunks exercise continuation and exact replay over the hosted route.
        for offset in range(0, len(data), 16384):
            block = data[offset:offset + 16384]
            arguments = {"content": block, "headers": {"Content-Type": "application/octet-stream", "X-Chunk-SHA256": hashlib.sha256(block).hexdigest()}}
            request("PUT", f"/packages/{snapshot}/chunks/{offset}", **arguments)
            if offset == 0:
                assert request("PUT", f"/packages/{snapshot}/chunks/0", **arguments)["replayed"]
                assert request("GET", f"/packages/{snapshot}")["accepted_bytes"] == len(block)
        request("POST", f"/packages/{snapshot}/complete")
        for _ in range(30):
            receipt = request("GET", f"/packages/{snapshot}")
            if receipt["status"] != "AWAITING_GB10": break
            time.sleep(1)
        assert receipt["status"] == "VERIFIED_GB10" and not receipt["archived"]
        for path in ("/", "/research/api/catalogue", f"/research/api/packages/{snapshot}/download"):
            response = client.get(path, headers={"Tailscale-User-Login": "forged@example.test"})
            assert response.status_code in (401, 404)
        if private:
            import zipfile
            with httpx.Client(base_url=private.rstrip("/"), trust_env=False, timeout=120) as researcher:
                me = researcher.get("/research/api/me"); assert me.status_code == 200
                origin = {"Origin": private.rstrip("/")}
                response = researcher.put(f"/research/api/events/{event}/notes", json={"revision": 0, "tags": ["Synthetic"], "notes": "Disposable deployment validation"}, headers=origin)
                assert response.status_code == 200
                conflict = researcher.put(f"/research/api/events/{event}/notes", json={"revision": 0, "tags": [], "notes": "Stale edit"}, headers=origin)
                assert conflict.status_code == 409
                reviewed = researcher.put(f"/research/api/packages/{snapshot}/review", json={"revision": 0, "review_status": "REVIEWED"}, headers=origin)
                assert reviewed.status_code == 200
                listing = researcher.get("/research/api/catalogue?tag=Synthetic"); assert listing.status_code == 200
                assert any(row["snapshot_id"] == snapshot for row in listing.json()["items"])
                downloaded = researcher.get(f"/research/api/packages/{snapshot}/download"); assert downloaded.status_code == 200
                with zipfile.ZipFile(io.BytesIO(downloaded.content)) as archive:
                    assert set(archive.namelist()) == set(source)
                    assert all(archive.read(name) == value for name, value in source.items())
    return {"status": "SYNTHETIC_VERIFIED_GB10", "archived": False, "public_catalogue_blocked": True, "private_catalogue_checked": bool(private)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True); parser.add_argument("--enrolment", type=Path, required=True)
    parser.add_argument("--age", type=Path, required=True); parser.add_argument("--private-url")
    args = parser.parse_args()
    try: print(json.dumps(run(args.base_url, args.enrolment, args.age, args.private_url)))
    except Exception:
        print('{"status":"SYNTHETIC_PROBE_FAILED"}'); raise SystemExit(1)
