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

// Trampoline Activity. MediaProjectionManager.createScreenCaptureIntent() must be
// launched via startActivityForResult from an Activity context — it can't be
// invoked by a Service. On Android 14+ it's also the *only* way to get the
// `android:project_media` appop, without which starting an FGS of type
// mediaProjection throws SecurityException. Cast session params are carried
// through this Activity's Intent extras and forwarded to the service on consent.
class MediaProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attemptId = intent.getStringExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)
        if (attemptId != null && !MirrorAttemptRegistry.isActive(attemptId)) {
            finish()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            logI("projection permission granted")
            val svc = Intent(this, CastForegroundService::class.java).apply {
                action = CastForegroundService.ACTION_START
                putExtras(intent.extras ?: Bundle())
                putExtra(CastForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CastForegroundService.EXTRA_RESULT_DATA, result.data!!)
            }
            startForegroundService(svc)
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, true)
        } else {
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, false)
            logE("projection permission denied")
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
