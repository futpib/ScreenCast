package io.github.ddagunts.screencast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit

// Requests runtime permissions when a cast is started, then resumes that start.
class StartPermissionActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val promptStore = getSharedPreferences("runtime_permission_prompts", MODE_PRIVATE)
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 29 &&
                intent.getBooleanExtra(EXTRA_REQUEST_AUDIO, false) &&
                !promptStore.getBoolean(Manifest.permission.RECORD_AUDIO, false) &&
                ContextCompat.checkSelfPermission(this@StartPermissionActivity,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                !promptStore.getBoolean(Manifest.permission.POST_NOTIFICATIONS, false) &&
                ContextCompat.checkSelfPermission(this@StartPermissionActivity,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            proceed()
        } else {
            promptStore.edit {
                needed.forEach { putBoolean(it, true) }
            }
            permissionRequest.launch(needed.toTypedArray())
        }
    }

    private fun proceed() {
        val target = when (intent.getStringExtra(EXTRA_DESTINATION)) {
            DESTINATION_HLS -> MediaProjectionRequestActivity::class.java
            DESTINATION_NATIVE -> NativeProjectionRequestActivity::class.java
            DESTINATION_WEBRTC -> WebRtcProjectionRequestActivity::class.java
            DESTINATION_MEDIA -> MediaPushService::class.java
            else -> { finish(); return }
        }
        val forwarded = Intent(this, target).apply {
            action = intent.action
            putExtras(intent.extras ?: Bundle())
        }
        if (target == MediaPushService::class.java) {
            ContextCompat.startForegroundService(this, forwarded)
        } else {
            startActivity(forwarded)
        }
        finish()
    }

    companion object {
        const val EXTRA_DESTINATION = "start_destination"
        const val EXTRA_REQUEST_AUDIO = "request_audio"
        const val DESTINATION_HLS = "hls"
        const val DESTINATION_NATIVE = "native"
        const val DESTINATION_WEBRTC = "webrtc"
        const val DESTINATION_MEDIA = "media"
    }
}
