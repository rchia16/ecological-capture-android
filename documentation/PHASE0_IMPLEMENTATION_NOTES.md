# Phase 0 implementation notes

## Reference alignment

This implementation was written against Meta Wearables DAT **0.9.0** (2026-08-03) rather than the older session APIs present in the supplied example repositories.

Important current API assumptions checked against Meta's 0.9 CameraAccess sample:

- `Wearables.initialize(...)`
- `Wearables.startRegistration(Activity)`
- `Wearables.RequestPermissionContract()`
- `Wearables.checkPermissionStatus(Permission.CAMERA)`
- `Wearables.createSession(DeviceSelector)`
- `DeviceSession.start()` / `.state` / `.errors`
- `DeviceSession.addCamera(StreamConfiguration(...))`
- `Camera.stream`
- `Stream.start()` / `.state` / `.errorStream` / `.videoStream`
- `VideoFrame.isCompressed`
- `VideoFrame.isCodecConfig`
- `VideoFrame.presentationTimeUs`

The direct HEVC-to-MP4 design follows the current SDK capability: `compressVideo=true` bypasses local video re-encoding. The implementation is original project code but follows the same media invariants demonstrated by Meta's sample: cache HEVC parameter sets, start a muxed track at a random access point, keep timestamps monotonic, and finalize MediaMuxer before reporting success.

## Verification performed here

- project source tree manually reviewed
- HEVC NAL parser compiled locally with `kotlinc`
- DAT symbols and lifecycle cross-checked against the current official 0.9 source/sample
- initial replayed `StreamState.STOPPED` lifecycle race corrected
- all media output kept in `filesDir/recordings`

## Verification not possible in the generation environment

A full Android Gradle build was **not** run here because this execution environment does not have a configured Android SDK/Gradle installation or network access to resolve Meta's GitHub Packages artifacts.

Therefore the first action on your development machine should be:

```bash
./gradlew assembleDebug
```

followed by a physical Ray-Ban Meta test. Do not treat this Phase 0 bundle as study-ready until the checklist in `README.md` passes on your intended handset/glasses firmware combination.
