package com.rchia.ecocapture.phase0.export

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rchia.ecocapture.phase0.BuildConfig
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.vlm.VlmExecutionGate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** ADB-only debug inspection. Requires the shell DUMP permission; absent from release. */
class SnapshotInspectionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            textSize = 20f
            setPadding(24, 48, 24, 24)
            text = "Local export inspection\nPreparing the explicitly selected recording."
        }
        setContentView(view)
        val clipId = intent.getStringExtra("clip_id") ?: run {
            scope.launch {
                val clips = withContext(Dispatchers.IO) {
                    EcologicalCaptureDatabase.getInstance(this@SnapshotInspectionActivity).clipDao()
                        .observeActiveClips().first().filter { it.approvalState == "APPROVED" }
                }
                val column = LinearLayout(this@SnapshotInspectionActivity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(TextView(this@SnapshotInspectionActivity).apply {
                    text = "Engineering export inspection\nChoose only an approved disposable recording. Exports stay on this phone."
                    textSize = 22f
                    setPadding(24, 48, 24, 24)
                })
                clips.forEach { clip ->
                    column.addView(Button(this@SnapshotInspectionActivity).apply {
                        text = "Export disposable clip\n${java.util.Date(clip.createdAtEpochMs)}\n${clip.clipId}"
                        minHeight = 112
                        setOnClickListener {
                            startActivity(android.content.Intent(this@SnapshotInspectionActivity,
                                SnapshotInspectionActivity::class.java).putExtra("clip_id", clip.clipId))
                            finish()
                        }
                    })
                }
                if (clips.isEmpty()) column.addView(TextView(this@SnapshotInspectionActivity).apply {
                    text = "No approved recordings. Approve a disposable clip in the normal app first."
                })
                setContentView(ScrollView(this@SnapshotInspectionActivity).apply { addView(column) })
            }
            return
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    VlmExecutionGate.execute(wait = false) {
                        val builder = ApprovedEventSnapshotBuilder(
                            RoomEventSnapshotStore(EcologicalCaptureDatabase.getInstance(this@SnapshotInspectionActivity)),
                            File(filesDir, "engineering-exports"), File(filesDir, "recordings"),
                            RoomEventSnapshotStore::validateVideo,
                        )
                        builder.cleanAbandonedStaging(System.currentTimeMillis())
                        builder.build(clipId, SnapshotIdentity(UUID.randomUUID().toString(),
                            System.currentTimeMillis(), BuildConfig.VERSION_NAME))
                    }
                }
                view.text = if (result == null) "Export deferred. Stop recording or wait for local AI preparation, then retry."
                else "Local snapshot ready\n\n${result.bundle.name}\n\nVideo SHA-256: ${result.video.sha256}\n\nNothing has been sent."
            } catch (_: Exception) {
                // Do not surface database values, absolute source paths or scientific text in errors/logcat.
                view.text = "Export did not complete. Check approval, saved media and concurrent changes, then retry. Original recording remains intact."
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
