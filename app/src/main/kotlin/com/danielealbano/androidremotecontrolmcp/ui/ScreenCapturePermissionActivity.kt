package com.danielealbano.androidremotecontrolmcp.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenStreamService

class ScreenCapturePermissionActivity : ComponentActivity() {
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent =
                    Intent(this, ScreenStreamService::class.java).apply {
                        action = ScreenStreamService.ACTION_START
                        putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenStreamService.EXTRA_RESULT_DATA, result.data)
                    }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finish()
            return
        }
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
}