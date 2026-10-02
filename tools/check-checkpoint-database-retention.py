"""Compare participant records in two adb tar snapshots without printing their text."""
import argparse
import json
from pathlib import Path
import sqlite3
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument("before", type=Path)
parser.add_argument("after", type=Path)
parser.add_argument("--include-deleted", action="store_true", help="Compare every clip and annotation/VLM row, including tombstones")
args = parser.parse_args()


def read_snapshot(path):
    destination = path.parent / (path.stem + "-participant-db")
    destination.mkdir(exist_ok=True)
    base = "ecological_capture.db"
    with tarfile.open(path) as archive:
        for suffix in ("", "-wal", "-shm"):
            member_name = "databases/" + base + suffix
            try:
                source = archive.extractfile(member_name)
            except KeyError:
                if not suffix:
                    raise
                continue
            assert source is not None
            # Exact, fixed filenames; do not extract arbitrary archive paths.
            with source:
                (destination / (base + suffix)).write_bytes(source.read())
    with sqlite3.connect(destination / base) as database:
        database.row_factory = sqlite3.Row
        clips = [dict(row) for row in database.execute("SELECT * FROM clips ORDER BY clipId")]
        active_ids = {row["clipId"] for row in clips if args.include_deleted or row["approvalState"] != "DELETED"}
        annotations = [dict(row) for row in database.execute("SELECT * FROM annotations ORDER BY annotationId")
                       if args.include_deleted or row["clipId"] in active_ids]
        runs = [dict(row) for row in database.execute("SELECT * FROM vlm_runs ORDER BY vlmRunId")
                if args.include_deleted or row["clipId"] in active_ids]
        return {"clips": [row for row in clips if row["clipId"] in active_ids],
                "annotations": annotations, "vlm_runs": runs,
                "schema": [dict(row) for row in database.execute("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")],
                "schema_version": [dict(database.execute("PRAGMA user_version").fetchone())]}


before = read_snapshot(args.before)
after = read_snapshot(args.after)
summary = {table: {"before": len(before[table]), "after": len(after[table]),
                   "identical": before[table] == after[table]} for table in before}
print(json.dumps(summary, indent=2))
if before != after:
    raise SystemExit("Participant records changed; inspect snapshots locally before accepting recovery results.")
