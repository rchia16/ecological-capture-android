package com.rchia.ecocapture.phase0.transfer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rchia.ecocapture.phase0.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Investigator provisioning for a selected disposable clip; no enrolment/profile or real credential. */
class TransferSimulationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = TransferRuntime.get(this)
        scope.launch {
            val clips = withContext(Dispatchers.IO) { runtime.database.clipDao().observeActiveClips().first().filter { it.approvalState == "APPROVED" } }
            val column = LinearLayout(this@TransferSimulationActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(this@TransferSimulationActivity).apply {
                textSize = 22f
                text = "LOCAL TRANSFER SIMULATION\nChoose only an approved disposable clip. This enables local testing for that clip; nothing leaves this phone."
                setPadding(24, 48, 24, 24)
            })
            clips.forEach { clip ->
                column.addView(TextView(this@TransferSimulationActivity).apply {
                    text = "${java.util.Date(clip.createdAtEpochMs)}\n${clip.clipId}"
                    textSize = 19f
                })
                for (failure in listOf(false, true)) column.addView(Button(this@TransferSimulationActivity).apply {
                    text = if (failure) "ENABLE DISPOSABLE CLIP, FAIL NEXT SIMULATION" else "ENABLE DISPOSABLE CLIP FOR SIMULATION"
                    minHeight = 112
                    setOnClickListener {
                        runtime.provisioning.allowDisposableClip(clip.clipId)
                        if (failure) runtime.provisioning.failNextSimulation()
                        startActivity(Intent(this@TransferSimulationActivity, MainActivity::class.java)
                            .setAction("com.rchia.ecocapture.OPEN_APPROVED_RECORDINGS"))
                        finish()
                    }
                })
            }
            if (clips.isEmpty()) column.addView(TextView(this@TransferSimulationActivity).apply { text = "Approve a disposable clip first." })
            setContentView(ScrollView(this@TransferSimulationActivity).apply { addView(column) })
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
