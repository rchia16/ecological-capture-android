package com.rchia.ecocapture.phase0.vlm

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Test harness only: representative foreground CPU scheduling, no participant generation controls. */
class VlmEngineeringActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "AI engineering comparison\n\nRunning saved-video inference tests.\n\nThis debug screen does not change recording descriptions or decisions."
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(24, 48, 24, 24)
        })
    }
}
