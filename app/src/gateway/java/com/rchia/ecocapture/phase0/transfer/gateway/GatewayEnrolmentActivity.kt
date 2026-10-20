package com.rchia.ecocapture.phase0.transfer.gateway

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import com.rchia.ecocapture.phase0.transfer.TransferRuntime
import kotlinx.coroutines.*

/** QR contains only a single-use code; neither endpoint nor encryption keys come from its URL. */
class GatewayEnrolmentActivity : Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val backend=TransferRuntime.get(this).live as GatewayBackend
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24) }
        val status=TextView(this).apply {
            textSize=22f;text=if(backend.enabled.value)"Secure sending to GB10 is enabled." else
                "Enable secure sending to GB10\nScan the study QR with your phone camera or enter your researcher's enrolment code."
            accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val code=EditText(this).apply {
            hint="Study enrolment code";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            if(intent.data?.scheme=="ecocapture"&&intent.data?.host=="gb10-enrol")setText(intent.data?.getQueryParameter("code")?:"")
        }
        val submit=Button(this).apply { text="ENABLE SENDING";minHeight=112;isEnabled=!backend.enabled.value }
        submit.setOnClickListener {
            val value=code.text.toString().trim()
            if(!value.matches(Regex("[A-Za-z0-9_-]{43}"))){status.text="Check the enrolment code and try again.";return@setOnClickListener}
            submit.isEnabled=false
            scope.launch {
                try{backend.enrol(value);code.text.clear();status.text="Sending enabled. Return to approved recordings and choose what to send."}
                catch(error: CancellationException){throw error}
                catch(_: Exception){status.text="Enrolment could not complete. Check your connection or contact your researcher.";submit.isEnabled=true}
            }
        }
        column.addView(status);column.addView(code);column.addView(submit)
        column.addView(Button(this).apply { text="BACK";minHeight=112;setOnClickListener{finish()} })
        setContentView(ScrollView(this).apply{addView(column)})
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
