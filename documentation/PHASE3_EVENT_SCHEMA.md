# Phase 3 local event snapshot format

Checkpoint 2, 2026-10-13. Local-only engineering implementation. JSON schemas are in `schemas/ecocapture.event.v1.json` and `schemas/ecocapture.manifest.v1.json`. No network transport, profile provisioning, Send UI or Room migration is introduced here.

Checkpoint 3 continuation: `PHASE3_TRANSFER_STATE.md` documents the new Room 3 snapshot/job records and local simulated Send UI. Snapshot scientific schemas remain unchanged. Statements below about Room 2/file-only engineering persistence describe Checkpoint 2; its debug inspection remains available. New explicit simulation snapshots also persist their exact hashes in Room, and normal Delete purges matching completed engineering copies.

## Files and persistence

Each app-private snapshot job is `files/engineering-exports/<snapshotUUID>/`. It contains an engineering `snapshot-record.json` and one bundle directory `event_<clipUUID>_snapshot_<snapshotUUID>/` containing exactly:

- `video.mp4`: byte-identical copy of finalized source media.
- `event.json`: versioned scientific state.
- `manifest.json`: lengths and SHA-256 of video/event; no recursive self-hash.

The local engineering record persists all three exact hashes, lengths, clip/snapshot IDs, creation time and `LOCAL_ONLY`. It is outside the three-file bundle. Checkpoint 3 will add the planned Room snapshot/job entities and migration; this checkpoint deliberately leaves Room version 2 and all existing records untouched. File persistence is the engineering substitute until that migration, not a claim that Room snapshot rows exist.

## Field mapping

| Source | Export |
| --- | --- |
| `ClipEntity.clipId` | `event_id`, `clip_id`; opaque existing UUID |
| New UUID / explicit creation time | `snapshot_id`, snapshot/export timestamps |
| Optional previous snapshot UUID | `previous_snapshot_id`, defaults null; production linkage managed at Checkpoint 3 |
| Approved externally provisioned study pseudonym | `study_participant_code`; null in debug inspection; no REDCap IDs, email, names or profile provisioned by this code |
| Clip creation time, duration, width, height, sample count, creation app version | Corresponding snake-case keys in `capture`; stored values unchanged |
| Review/approval states, `updatedAtEpochMs` | `review` fields; stored values unchanged |
| No dedicated approval timestamp in Room | `approved_at_epoch_ms: null`; `updatedAtEpochMs` is not relabelled as approval time |
| `AnnotationEntity` history | Exact source/text/timestamp/current flag/parent run/superseding reference; sorted by stored save time then UUID |
| Current annotation | Unique current row UUID, or null for no annotations |
| All `VlmRunEntity` output/provenance columns | Exact snake-case fields; original output and description unchanged |
| Stored frame/config JSON strings | `frame_sampling_json`, `generation_config_json` remain exact strings, rather than parsed/reformatted objects |
| Unrecorded first presentation | `first_presented_at_epoch_ms: null`, never zero |
| Exporter app version / explicit export time | `export.schema_version`, `app_version`, `exported_at_epoch_ms` |

Source paths and metadata sidecars are excluded. No codec, recording start or other metadata is invented. Phone timestamps are stored observations; sorting by save time does not establish causal influence or reliable wall-clock chronology. Lineage is validated independently of timestamp order, including phone-clock reversal.

Unknown schema fields are rejected, including structured profile/clinical/credential fields. Original participant/model free text and video are preserved exactly: this is not a content anonymizer and cannot remove information somebody wrote or filmed. A study code is potentially linkable and must be approved/provisioned separately.

## Consistency and failure behavior

The builder reads clip/annotation/VLM rows in one Room transaction, copies/hashes media with bounded 128 KiB buffers, writes/syncs JSON and a local record into a new UUID `.pending` tree, and rechecks original bytes. A final Room transaction compares the full stored rows, rehashes the source and atomically renames the entire job to publish it. Concurrent edit, deletion, disposition/presentation change, missing/corrupt media or cancellation abort publication. Later edits do not modify a published snapshot. Duplicate snapshot IDs are rejected, including existing partial jobs.

An MP4 box walk rejects truncated/invalid structures; Android additionally verifies a video track and a readable sample (sample bound 16 MiB). This is not a full decoder validation of every frame. Source metadata is not silently corrected to match probed values.

Android's trusted app-directory aliases are resolved; file symlinks and source paths outside the recording directory are rejected. The debug entry executes through the existing capture gate: it defers when the gate is occupied, and a capture request cancels export work. No capture/inference APIs or policy are changed.

Normal cancellation/failure removes only the newly allocated pending job. Process death can leave staging; the explicit debug maintenance path removes only canonical UUID `.pending` trees at least 24 hours old, holding the builder lock. This is an engineering cleanup window, not approved production retention or guaranteed secure erasure. Completed snapshots remain until explicitly discarded after inspection. Integration of completed snapshot purging with normal Delete is Checkpoint 3/9 work; engineering copies should only be made from disposable recordings.

The encoder fixes key/array ordering, emits UTF-8 with no BOM/newline, preserves explicit nulls and escapes controls/surrogates. All hashes describe exact bytes, never reserialized JSON. Unknown/cyclic/dangling annotation lineage, multiple current rows and missing amendment parents are rejected.

## Independent verification

`tools/phase3-protocol/verify_snapshot.py` checks exact three-file membership, file types, trusted manifest SHA-256, exact sizes/digests, the local restricted JSON Schema vocabulary, UUID/linkage, ordering/current pointer and revision/run lineage. It requires a caller-trusted manifest digest; a manifest alone cannot establish authenticity. Optional trusted event/video digests can also be supplied. It performs no network calls and prints hashes only.

The Kotlin tests emit wholly synthetic structural MP4-box fixtures under ignored `app/build/c2/`. Those cross-language fixtures test serialization/integrity, not playable video. Actual Android tests use generated HEVC test-pattern video and isolated Room databases, which are removed afterwards.

## Manual inspection on the installed debug app

Prepare three approved disposable clips: no description, participant-only description, and saved VLM amendment with a later revision. Never use valued participant recordings for this inspection.

Open the shell-permission-protected debug chooser:

```powershell
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am start -n com.rchia.ecocapture.phase0/.export.SnapshotInspectionActivity
```

Select the intended disposable clip by its creation date and UUID. If capture or AI occupies the gate, wait and retry. The result shows `event_<eventID>_snapshot_<snapshotID>` and the video digest. This activity exists only in debug, requires Android's DUMP permission for external launch, and has no participant navigation entry.

For each chosen snapshot, substitute its displayed UUIDs and a **new short** ignored output folder:

```powershell
python -B tools/phase3-protocol/pull_snapshot.py --serial 49280DLAQ0020M --event-id <eventUUID> --snapshot-id <snapshotUUID> --output tools/phase3-protocol/artifacts/c2/manual1
```

The helper reads that one snapshot over USB, extracts only the fixed regular files, rejects archive links/duplicates/unknown names and verifies all hashes using the source local record. Expected result: `SNAPSHOT_SHA256_SCHEMA_VERIFIED`. Use `manual2`/`manual3` for the other clips. Open each `bundle/event.json` locally and replay `bundle/video.mp4`; compare the complete saved descriptions and amendment history with the app. Report PASS/FAIL for the three states without pasting scientific text.

Keep inspection outputs local/ignored and explicitly discard disposable engineering copies after acceptance. Do not infer a remote transfer, researcher receipt or archive from these files.
