package io.github.ddagunts.screencast

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

internal fun MediaProjectionManager.createCastCaptureIntent(wholeScreen: Boolean): Intent =
    if (wholeScreen && Build.VERSION.SDK_INT >= 34) {
        createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        createScreenCaptureIntent()
    }
