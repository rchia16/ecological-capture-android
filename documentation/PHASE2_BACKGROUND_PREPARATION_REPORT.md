# Optional background AI preparation

Implemented 2026-10-02 following approval of the background preparation recommendation. This extends Checkpoint 7; it does not complete the Checkpoint 8 recovery matrix.

## Participant behavior

Review Recordings contains two additional accessible switches:

- **Allow AI preparation in the background**, off by default. Enabling it requests notification permission when necessary. Background preparation requires notifications to be allowed so Cancel and readiness remain available. Refusal leaves ordinary foreground generation and recording review available.
- **Prepare only while charging**, on by default. This applies to new background requests. Charging-only work waits without loading the model; unplugging stops the attempt and lets Android restart it when constraints are satisfied. Battery-not-low is also required for all background requests.

These preferences are separate from **Prepare AI suggestions automatically**, which remains off by default. Manual Generate and optional automatic preparation use the background scheduler when background preparation is enabled. Enabling background preparation alone does not process every saved recording or create descriptions automatically.

The participant can leave the review screen, use another app, or turn off the display while a scheduled attempt runs. The ongoing notification states preparation status and has a Cancel action. A brief "AI suggestion ready" notification opens Review Recordings. Notifications contain no generated text, recording filenames or participant characteristics. Full output is never automatically spoken or copied into a participant description.

Queued, waiting-for-charge, waiting-for-recording and generation states appear in the existing description flow. Use/Reject and pinned footer controls remain; regeneration stays explicit. Changing the charging switch affects future requests. Turning background preparation off cancels its pending and active requests; turning automatic preparation off cancels automatic background requests without cancelling manually requested background work.

## Execution and persistence

`BackgroundAiPreparation` submits unique per-clip WorkManager requests. `AiPreparationWorker` promotes active inference to a foreground worker with a notification and Cancel action. WorkManager supplies persistent scheduling, constraint monitoring and wake-lock management. API 35+ uses the mediaProcessing service type; earlier supported Android versions use dataSync for local file processing. WorkManager 2.11.2 includes modern foreground-service timeout support. No continuous idle service or display wake flag is used for queued/background work.

`VlmExecutionGate` serializes complete foreground and background participant engine cycles. Workers retry later when another inference or capture owns the gate; queued workers do not load a second model. Starting a recording reserves the gate, cancels current inference and waits for non-cancellable unload before arming the MP4 recorder. After finalization, the gate is released and background work may restart. An in-flight vision encoding call can delay cleanup and therefore recording readiness; the capture UI says it is waiting for AI preparation to stop. An interrupted generation restarts from the beginning, not from a partial native state.

Work requests contain a clip ID and scheduling tags only. The worker retrieves the finalized saved MP4, checks active clip status and uses the same fixed `ecological_scene_description_v2` engine and three-frame defaults. No participant profile, clinical information or annotation text enters generation. Maximum per-attempt generation/load time is 45 minutes, followed by cleanup; model errors fail without repeated automatic inference retries. Scheduling conflicts and recording interruptions retry with scheduler backoff.

The WorkManager request UUID is the persisted VLM run ID. Retries first check for a committed result and skip generation if one exists. Save is transactional and idempotent for that request ID. Partial/cancelled native text is discarded. Completed raw output, uncertainty wording and provenance remain unedited and unpresented; participant amendments still require explicit Use and Save. Approve, Review Later and Delete do not require AI preparation. Delete cancels and joins the selected clip's background execution before deleting media.

Android can delay or stop background work. Force stop prevents work from continuing until the app is reopened; there is no promise of immediate completion after screen lock, restart or system interruption. The existing Pixel 9 foreground measurements (10 to 13 minutes, roughly 4.3 to 4.4 GiB peak RSS) are not a background performance or battery measurement.

## Build and inspection

Debug and release builds pass. The debug update is installed with replacement install, retaining app data, the filled descriptions and model files. No new tests were added or run. The existing generation-state test expectation was updated for the queue/wait states. Runtime behavior, battery impact and screen-reader interaction require the manual checks below; compilation does not establish their success.

## What to check

1. In Review Recordings, enable background preparation and allow notifications. Confirm TalkBack announces both switches and their checked states. Charging-only should initially be on.
2. With the phone unplugged, request a suggestion on a clip without an unresolved suggestion. Confirm it waits for charging and remains cancellable. Plug in and let Android start it.
3. Leave review, switch apps and lock the screen. Check the preparation notification and its Cancel action. Allow an attempt to finish, then tap the readiness notification and review the output explicitly.
4. Request preparation for a second clip while the first runs. Confirm only one generation runs at a time and the other waits. Android backoff may delay the next attempt.
5. Start recording while inference is active. Confirm the UI waits for cleanup, then recording works and the interrupted background attempt restarts later. If a vision call is active, cancellation is not immediate.
6. Use a suggestion, edit and Save. Confirm your description is not replaced automatically by a later background result. Existing filled descriptions and recording decisions should remain intact.
7. Turn background preparation off while work is queued/running. Confirm that work is cancelled and ordinary foreground Generate remains available. Test notification denial separately; it should prevent background preparation without blocking description editing or decisions.

Complete these checks before proceeding with the full Checkpoint 8 recovery matrix.

## Android references

- [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running): foreground notifications, cancellation and Android 16 job quotas. Long-running workers can be delayed by quota exhaustion; this implementation accepts scheduler deferral.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): media processing and local file processing, including service-type permissions and runtime limits.
- [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work): the selected 2.11.2 release and foreground timeout compatibility changes.
