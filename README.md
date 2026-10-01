# Ecological Capture — Android Phase 0

Android-only Phase 0 prototype for participant-initiated, short egocentric recordings from Ray-Ban Meta / supported Meta AI glasses.

## Phase 0 scope

This build proves the highest-risk path only:

```text
Ray-Ban Meta
  -> Meta Wearables DAT 0.9.0
  -> compressed HEVC camera stream
  -> direct MediaMuxer MP4 passthrough
  -> app-private local storage
  -> audio + haptic confirmation
```

Included:

- Meta Wearables registration and device discovery
- wearable camera permission request
- explicit `DeviceSession -> addCamera(...) -> Camera.stream` lifecycle
- requested stream profile: `HIGH` quality, 15 fps, `compressVideo=true`
- short user-triggered recording, manually stopped or automatically capped at 60 s
- HEVC VPS/SPS/PPS collection so a recording can begin after the stream has already started
- keyframe-aware MP4 start
- atomic `.partial` -> `.mp4` promotion after successful MediaMuxer finalization
- sidecar JSON with basic capture metadata
- app-private storage only
- large Compose controls
- TalkBack semantics
- text-to-speech plus phone vibration feedback
- truthful recording feedback: **"Recording started" is emitted only after a decodable frame has actually begun writing to the MP4**

Not included yet:

- participant information sheet / consent
- upload or backend
- review queue / video playback
- participant dictation
- local VLM
- privacy blurring
- environmental audio
- continuous/background recording

Those belong to later phases.

## Why this differs from the older examples

`rchia16/meta-glasses-sonification` is useful for its registration, permissions, frame handling and accessibility/audio patterns, but its CameraAccess code still shows the older `Wearables.startStreamSession(...)` flow.

This prototype targets **Meta Wearables DAT 0.9.0**, where camera access is explicitly:

```text
Wearables.createSession(...)
  -> DeviceSession.start()
  -> DeviceSession.addCamera(...)
  -> Camera.stream
  -> Stream.start()
```

`rchia16/smartglassesagents` currently pins an older DAT release, so do not copy these Phase 0 classes into that project without first upgrading its DAT dependency and adapting its existing DAT abstraction.

## Requirements

- Android Studio with Android SDK 36
- Java 17
- Android 12 / API 31+ for this Phase 0 baseline
- Meta Wearables DAT 0.9-compatible Meta AI / glasses firmware combination
- Ray-Ban Meta or another DAT-supported Meta AI glasses device
- GitHub personal access token (classic) with `read:packages`
- Meta Wearables Developer Mode **or** application credentials from Wearables Developer Center

## 1. Configure `local.properties`

Copy:

```bash
cp local.properties.example local.properties
```

Then set:

```properties
github_token=YOUR_GITHUB_CLASSIC_PAT_WITH_READ_PACKAGES

# Leave blank only when your DAT Developer Mode setup permits it.
mwdat_application_id=YOUR_WEARABLES_APP_ID
mwdat_client_token=YOUR_WEARABLES_CLIENT_TOKEN

# Android Studio normally adds this:
sdk.dir=/path/to/Android/Sdk
```

You can also provide the GitHub token as:

```bash
export GITHUB_TOKEN=...
```

## 2. Gradle wrapper

The generated bundle contains `gradle-wrapper.properties`, but not the binary `gradle-wrapper.jar` because binary tool assets were not available in the generation environment.

If you already have `rchia16/smartglassesagents` checked out, the simplest option is to copy its wrapper scripts and wrapper JAR into this project:

```bash
cp ../smartglassesagents/gradlew .
cp ../smartglassesagents/gradlew.bat .
cp ../smartglassesagents/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
chmod +x gradlew
```

This project's `gradle/wrapper/gradle-wrapper.properties` is set to Gradle 8.14.1 to match the current Meta CameraAccess sample baseline.

Alternatively, from a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.14.1
```

## 3. Build

```bash
./gradlew assembleDebug
```

Install:

```bash
./gradlew installDebug
```

Or open the project in Android Studio and run the `app` configuration on the participant/test phone.

## 4. First run

1. Launch **Ecological Capture**.
2. Grant the Android Bluetooth permission request.
3. Tap **REGISTER GLASSES APP** if the integration is not registered.
4. Complete the Meta registration flow.
5. Ensure the glasses are paired/available through Meta AI.
6. Tap **START GLASSES CAMERA**.
7. If required, approve the wearable camera permission in the Meta flow.
8. Wait for:
   - vibration; and
   - **"Glasses camera ready."**
9. Tap **RECORD A CLIP**.
10. The app initially says *Preparing recording* while it waits for complete HEVC codec configuration plus a suitable frame.
11. Only when MediaMuxer has actually started writing will the phone vibrate and say:

   **"Recording started."**

12. Tap **STOP RECORDING**, or allow the 60-second Phase 0 maximum to stop it.
13. After successful MP4 finalization, the phone says:

   **"Recording stopped. Saved on this phone."**

## Local output

Files are stored under app-private storage:

```text
files/recordings/
  clip_YYYYMMDD_HHMMSS_SSS.mp4
  clip_YYYYMMDD_HHMMSS_SSS.json
```

No video is copied to the public Android gallery.

For a debug build, list clips with ADB:

```bash
adb shell run-as com.rchia.ecocapture.phase0 find files/recordings -maxdepth 1 -type f
```

To export a particular clip for Phase 0 verification:

```bash
adb exec-out run-as com.rchia.ecocapture.phase0 \
  cat files/recordings/clip_YYYYMMDD_HHMMSS_SSS.mp4 > phase0_clip.mp4
```

Export its metadata similarly:

```bash
adb exec-out run-as com.rchia.ecocapture.phase0 \
  cat files/recordings/clip_YYYYMMDD_HHMMSS_SSS.json > phase0_clip.json
```

Then inspect it with e.g.:

```bash
ffprobe -hide_banner phase0_clip.mp4
```

Expected characteristics are approximately:

```text
codec: HEVC/H.265
requested resolution: HIGH (normally 720x1280 when maintained)
requested fps: 15
container: MP4
```

The actual frame dimensions/rate are displayed in the app because DAT/transport conditions may adapt the observed stream.

## Important lifecycle behavior implemented

DAT stream state is a replaying state flow. On subscription it may first emit `STOPPED` before `Stream.start()` transitions it. `Phase0ViewModel` therefore tracks whether the stream has ever become active before treating `STOPPED/CLOSED` as a terminal event. This prevents immediate teardown on startup.

The app also does **not** equate a Record-button tap with successful recording. The sequence is:

```text
button tap
  -> arm local .partial MediaMuxer
  -> wait for compressed DAT frames
  -> require complete VPS/SPS/PPS
  -> begin on an HEVC random-access frame (short fallback if necessary)
  -> first MP4 sample written
  -> set isRecordingConfirmed=true
  -> audio/haptic "Recording started"
```

## Phase 0 validation checklist

On a physical phone + glasses, test:

- [ ] application initializes DAT
- [ ] registration succeeds
- [ ] glasses are detected
- [ ] wearable camera permission succeeds
- [ ] stream reaches `STREAMING`
- [ ] first compressed HEVC frame is received
- [ ] observed width/height are non-zero
- [ ] observed FPS is plausible around the requested 15 fps
- [ ] Record does not announce success before a sample is written
- [ ] 30-second clip finalizes and plays
- [ ] 60-second automatic stop finalizes and plays
- [ ] repeated record-stop cycles create separate clips
- [ ] stopping quickly either creates a valid clip or clearly reports no usable video
- [ ] folding/doffing/disconnecting glasses finalizes or clearly fails the current recording
- [ ] no zero-byte `.mp4` is reported as saved
- [ ] TalkBack can focus/activate Register, Start camera, Record and Stop
- [ ] start/stop haptic patterns are distinguishable

Record the phone model, Android version, glasses model, glasses firmware, Meta AI version, DAT version, actual FPS, resolution, file size and any stream errors during this test.

## Phase 0 known limits

- The stream is intentionally not kept alive using a foreground service. Keep the app in the foreground during Phase 0 testing.
- No live preview is rendered. This is deliberate: BLV recording-state reliability and MP4 integrity are the first target, not camera-screen UX.
- No microphone is recorded.
- No upload occurs.
- No automatic privacy processing occurs.
- The local file path shown on screen is primarily an engineering diagnostic and should be removed from the participant-facing production UI later.
- Current minimum SDK is 31 to stay close to Meta's current official CameraAccess sample. Lowering this should be treated as a separate compatibility test.

## Research-oriented Meta telemetry defaults

`AndroidManifest.xml` sets:

```text
com.meta.wearable.mwdat.ANALYTICS_OPT_OUT = true
com.meta.wearable.mwdat.CRASH_REPORTING_OPT_OUT = true
```

This is a conservative default for a research prototype. If you temporarily enable SDK crash reporting during engineering diagnostics, document that decision and re-check it before participant deployment.

## Source layout

```text
app/src/main/java/com/rchia/ecocapture/phase0/
  MainActivity.kt
  Phase0ViewModel.kt
  capture/
    HevcNalParser.kt
    HevcMp4Recorder.kt
  feedback/
    FeedbackController.kt
  ui/
    Phase0Screen.kt
```

Key responsibilities:

- `MainActivity` — Android permissions + wearable permission bridge
- `Phase0ViewModel` — DAT 0.9 session/camera/stream lifecycle + Phase 0 state machine
- `HevcNalParser` — Annex-B HEVC NAL parsing and VPS/SPS/PPS/keyframe detection
- `HevcMp4Recorder` — compressed HEVC -> MP4 passthrough with atomic finalization
- `FeedbackController` — TTS and phone haptics
- `Phase0Screen` — large, TalkBack-addressable controls and diagnostics

## Next phase boundary

Do **not** add the VLM yet. Once the physical-device checklist is stable, the next implementation should add:

```text
local ClipRecord persistence
  -> accessible review queue
  -> consent/PIS
  -> approval state
  -> reliable background upload
```

Only after that should deferred dictation and local VLM review be layered on top.
