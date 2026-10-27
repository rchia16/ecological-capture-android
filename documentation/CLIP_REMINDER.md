# Clip reminder

In a recording review, choose **Clip reminder** to read the recording date and time, an approximate suburb if available, and the saved AI visual response. **Listen** reads them aloud using a locally installed English voice. **Stop** or leaving the screen ends speech. The reminder does not write an annotation.

Choose **Add annotation** or **Edit annotation** to write your own optional account. Opening the reminder from the editor preserves the unsaved draft. Existing AI responses remain available even when a historical run was amended or ignored. The VLM prompt and output are unchanged.

From the app's main screen, open **Settings** for study enrolment, AI preparation, and approximate location. On first launch, Android requests approximate location, camera, and microphone permissions. To save an approximate suburb for new recordings, also enable **Save approximate suburb**. The phone requests one location fix when recording starts. Only the resolved suburb or nearby area is saved; coordinates are discarded. An existing approximate suburb can be corrected on the reminder screen while the location setting and permission are enabled. The reminder does not offer an Add suburb action. Earlier clips never use the phone's current location. For those clips, the displayed time explicitly uses the device time zone because their original time zone was not recorded.

Recording context stays on the phone in Room database version 5. It is removed with the clip and is not included in event snapshots or GB10 transfers. An automatic location result cannot overwrite a manual correction or restore a deleted clip.

Verification: build and local unit tests with `-PgatewayTransfer=true`; isolated Android tests for context deletion, manual correction, VLM workflow, and migration. A replacement install on the Pixel 9 preserved the pre-install database, recordings, and model files byte-for-byte; after tests, the recording and model file inventories remained identical. The Android tests use their own databases.
