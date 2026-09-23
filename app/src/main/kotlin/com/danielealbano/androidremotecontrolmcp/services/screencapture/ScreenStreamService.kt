package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class ScreenStreamService : Service() {
    @Inject lateinit var screenStreamHub: ScreenStreamHub

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var encoder: MediaCodec? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        // Existing implementation continues here.
    }

    private fun stopStreaming() {
        // Existing implementation continues here.
    }

    private fun frameFlags(info: MediaCodec.BufferInfo): Int =
        when {
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                ScreenStreamHub.FLAG_CODEC_CONFIG
            }

            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                ScreenStreamHub.FLAG_KEY_FRAME
            }

            else -> {
                0
            }
        }

    private fun requestSyncFrame() {
        val codec = encoder ?: return
        runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_stream_title))
            .setContentText(getString(R.string.screen_stream_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        encoder?.release()
        encoder = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.action.START_SCREEN_STREAM"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.action.STOP_SCREEN_STREAM"
        private const val CHANNEL_ID = "screen_stream"
        private const val NOTIFICATION_ID = 2002

        val running = MutableStateFlow(false)
    }
}