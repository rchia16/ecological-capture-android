# Phase 3 Checkpoint 3 - Local transfer records and simulation

2026-10-13. This checkpoint adds persisted local transfer state and an offline simulated transport. No gateway or FileSender connection is implemented by the Android transfer code.

## Database and identity

Room version 3 adds `export_snapshots` and `transfer_jobs` with an explicit 2 -> 3 migration. The existing 1 -> 2 migration remains in the chain. No old clip, annotation, VLM field or row is rewritten by the migration. Both new tables start empty; migration/opening/approval does not enqueue anything.

A snapshot row stores its UUID, clip UUID, optional previous snapshot UUID, creation time, exact three SHA-256 digests, app version, optional study pseudonym and READY/SUPERSEDED/PURGED state. A job has a unique snapshot UUID, clip UUID, progress, creation/update times, retry count, redacted error code, source-version digest, previous snapshot UUID, app version and `transportMode = SIMULATION`. It has no FileSender ID, receipt, destination, credential, annotation or model-output text.

Snapshot bytes live in app-private `files/event-exports/<snapshotUUID>/event_<clipUUID>_snapshot_<snapshotUUID>/`. Scientific JSON and manifest use the Checkpoint 2 schemas. The companion `snapshot-record.json` remains outside the three-file bundle. The phone now also persists the three exact hashes in Room after publication. Existing engineering snapshots are not imported, queued or adopted automatically.

The source-version digest hashes deterministic serialization of the current scientific Room state with fixed fingerprint-only identity/time/version values. It detects annotation history, current pointer, VLM provenance/disposition/presentation and clip metadata/review changes. It is not a media integrity proof: source and frozen MP4 have separate streamed hashes. Finalized MP4 is still treated as immutable, and failed source checks preserve it.

## Explicit preparation, retry and revisions

Send requires APPROVED/REVIEWED, a present readable finalized MP4 under the recording root, and valid debug simulation provisioning for that selected disposable clip. It validates before creating the job. A per-clip mutex plus same-version deduplication prevents two taps from creating duplicate jobs. New local annotation/VLM changes require a new explicit Send; the new snapshot links to the preceding persisted snapshot. Superseding a prior snapshot changes only its metadata status, not its bytes.

The job is persisted as PREPARING before snapshot construction. Before publishing and before committing the snapshot row, the repository rechecks scientific state, eligibility and cancellation. READY snapshot insertion, superseding the preceding version and the transition to SIMULATING share a Room transaction. File rename and Room commit are not one cross-filesystem transaction: a crash between them leaves a recorded interrupted attempt, not a claimed success. On startup interrupted PREPARING/SIMULATING attempts become FAILED_RETRYABLE; nothing resumes automatically. Explicit Retry verifies a saved snapshot's exact hashes, or purges only the interrupted job's own staging/orphan and rebuilds it.

Simulation progression is PREPARING -> SIMULATING -> SIMULATED_COMPLETE or FAILED_RETRYABLE/CANCELLED. Messages explicitly state that nothing was sent or archived. `SIMULATED_COMPLETE` cannot be mistaken for `FILESENDER_COMPLETE`. There are no remote-completion/archival transitions.

The application owns simulation coroutines so navigating away does not cancel a running attempt. The existing capture gate defers preparation during capture/inference and capture can cancel it. Streaming preparation and integrity checks check cancellation between bounded buffers. Persisted cancellation prevents further publication/progress; runtime observes cancelled jobs and cancels their active coroutine. Capture/interruption leaves a retryable attempt; explicit Cancel leaves CANCELLED.

## Provisioning and UI

Home -> Approved recordings lists locally approved clips, shows progress/results, offers explicit Send, Retry, Cancel and Review, and flags new changes. Existing Review/description/AI screens are reused for edits. Scrolling content, heading/pane semantics, polite status announcements, wrapped buttons with minimum 56 dp targets, pinned Back and Android Back support are included. Human TalkBack/large-font acceptance is still required.

Wi-Fi-only is displayed as the default for future live sending. This simulated transport never uses a network, so it does not test or enforce actual Wi-Fi/cellular constraints; those belong to the later upload worker.

Default installation eligibility is disabled. A shell-permission-protected debug activity explicitly provisions a randomly identified engineering installation, engineering policy version and selected disposable-clip allowlist. No actual participant enrolment, consent decision, subject identity, gateway token or FileSender secret is provisioned. Subject code stays null. Release builds reject this debug simulation provisioning and omit its activity.

## Delete and cleanup

The existing tombstone/annotation/VLM purge transaction now also cancels the clip's transfer jobs. Delete removes registered pending/completed export jobs plus matching completed Checkpoint 2 engineering exports, then removes original media through the existing workflow. Snapshot rows retain minimal hashes/IDs and become PURGED after copies are removed. Deleted clips disappear from the approved list and cannot be sent or restored through reconciliation.

Cleanup validates fixed UUID paths and canonical containment and rejects symlinks. Partial deletion failure remains visible/retryable. Earlier unregistered engineering pending jobs retain the existing bounded stale-staging cleanup; real data must not be used in that engineering inspection. Production retention, remote cancellation/withdrawal and archival remain separate pending gates.

## Ready manual checks

1. On a disposable clip, Approve in normal Review. Open Home -> Approved recordings. Expect a saved local clip, sending disabled by default and no automatic attempt.
2. From Home -> Approved recordings, choose **Disposable clip setup** (debug builds only). Enable simulation only for the selected approved disposable clip. The ADB fallback remains:

```powershell
& 'D:/Ray/Android/sdk/platform-tools/adb.exe' -s 49280DLAQ0020M shell am start -n com.rchia.ecocapture.phase0/.transfer.TransferSimulationActivity
```

Choose the clip by date/UUID, then Enable disposable clip for simulation. This opens Approved recordings; press Send approved recording explicitly. Expect preparation/progress and “Simulation complete. Nothing was sent or archived.”

3. Choose Review recording, save a changed description, and return. Expect “New local changes since last send”; edits alone start nothing. Send updated recording must create a new immutable version. Earlier source/export bytes remain unchanged in automated tests.
4. On another disposable clip, use the debug chooser's Fail next simulation option. Send should report a recoverable simulation failure and retain the video; Retry should finish using the same frozen snapshot/job. Alternatively Cancel during progress and confirm the local video remains. Completed simulations cannot be remotely cancelled because there was no remote send.
5. With TalkBack and enlarged text, navigate the list, Send/status/Retry/Cancel/Review/Back. Expect accessible labels, short status announcements and reachable wrapping controls. On a disposable clip only, Delete through Review and confirm it stays absent from Approved recordings after reopening.

Report PASS/FAIL/NOT TESTED per item; do not paste scientific text. Previous backup-key recovery, crypto/custody, notification, participant-governance and hardware inspections remain in `PHASE3_OUTSTANDING_CHECKS.md`.
