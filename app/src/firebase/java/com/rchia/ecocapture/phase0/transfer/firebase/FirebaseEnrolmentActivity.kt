package com.rchia.ecocapture.phase0.transfer.firebase

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import com.rchia.ecocapture.phase0.transfer.TransferRuntime
import kotlinx.coroutines.*

/** QR opens this confirmation screen; it never enrols or sends automatically. */
class FirebaseEnrolmentActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val backend = TransferRuntime.get(this).live as FirebaseBackend
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,48,24,24) }
        val status = TextView(this).apply {
            textSize = 22f
            text = if (backend.enabled.value) "Sending is enabled for this installation." else
                "Enable secure sending\nScan your study QR code with your phone camera, or enter the code supplied by your researcher."
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val code = EditText(this).apply {
            hint = "Study enrolment code"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            if (intent.data?.scheme == "ecocapture" && intent.data?.host == "enrol") setText(intent.data?.getQueryParameter("code") ?: "")
        }
        val submit = Button(this).apply { text = "ENABLE SENDING"; minHeight = 112; isEnabled = !backend.enabled.value }
        submit.setOnClickListener {
            val token = code.text.toString().trim()
            if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) { status.text = "Check the enrolment code and try again."; return@setOnClickListener }
            submit.isEnabled = false
            scope.launch {
                try { backend.enrol(token); code.text.clear(); status.text = "Secure sending enabled. Return to approved recordings to choose what to send." }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { status.text = "Enrolment could not complete. Check your connection or contact your researcher."; submit.isEnabled = true }
            }
        }
        column.addView(status); column.addView(code); column.addView(submit)
        column.addView(Button(this).apply { text = "BACK"; minHeight = 112; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
