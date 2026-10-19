package io.github.ddagunts.screencast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI

// WebRTC-mode trampoline — identical shape to MediaProjectionRequestActivity
// but hands consent off to WebRtcForegroundService instead. Kept separate so
// the HLS and WebRTC paths can't cross-wire (start one service with the
// other's extras).
class WebRtcProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attemptId = intent.getStringExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)
        if (attemptId != null && !MirrorAttemptRegistry.isActive(attemptId)) {
            finish()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            logI("webrtc: projection permission granted")
            val svc = Intent(this, WebRtcForegroundService::class.java).apply {
                action = WebRtcForegroundService.ACTION_START
                putExtras(intent.extras ?: Bundle())
                putExtra(WebRtcForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(WebRtcForegroundService.EXTRA_RESULT_DATA, result.data!!)
            }
            startForegroundService(svc)
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, true)
        } else {
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, false)
            logE("webrtc: projection permission denied")
            Toast.makeText(this, "Screen capture consent required to cast", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createCastCaptureIntent(
                intent.hasExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)))
        }
    }
}
