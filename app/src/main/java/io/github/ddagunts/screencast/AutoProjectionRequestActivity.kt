package io.github.ddagunts.screencast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class AutoProjectionRequestActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = intent.getStringExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID)
        if (id != null && MirrorAttemptRegistry.isActive(id)) {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startForegroundService(Intent(this, MirrorProjectionService::class.java).apply {
                    action = MirrorProjectionService.ACTION_START
                    putExtra(MirrorProjectionService.EXTRA_ID, id)
                    putExtra(MirrorProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MirrorProjectionService.EXTRA_RESULT_DATA, result.data)
                })
                MirrorAttemptRegistry.report(id, true)
            } else MirrorAttemptRegistry.report(id, false)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createCastCaptureIntent(
                intent.getBooleanExtra(StartPermissionActivity.EXTRA_WHOLE_SCREEN, true)))
        }
    }
}
