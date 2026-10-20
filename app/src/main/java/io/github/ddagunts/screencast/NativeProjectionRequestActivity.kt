package io.github.ddagunts.screencast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class NativeProjectionRequestActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attemptId = intent.getStringExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)
        if (attemptId != null && !MirrorAttemptRegistry.isActive(attemptId)) {
            finish()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, NativeCastForegroundService::class.java).apply {
                action = NativeCastForegroundService.ACTION_START
                putExtras(intent.extras ?: Bundle())
                putExtra(NativeCastForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(NativeCastForegroundService.EXTRA_RESULT_DATA, result.data)
            })
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, true)
        } else {
            if (attemptId != null) MirrorAttemptRegistry.report(attemptId, false)
            Toast.makeText(this, "Screen capture consent required to cast", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createCastCaptureIntent(
                intent.getBooleanExtra(StartPermissionActivity.EXTRA_WHOLE_SCREEN,
                    intent.hasExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)) ||
                    intent.getBooleanExtra(NativeCastForegroundService.EXTRA_AUDIO_ONLY, false)))
        }
    }
}
