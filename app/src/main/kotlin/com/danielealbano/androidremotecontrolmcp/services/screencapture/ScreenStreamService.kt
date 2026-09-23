@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@AndroidEntryPoint
class ScreenStreamService : Service() {
    @Inject lateinit var streamHub: ScreenStreamHub

    private val stopping = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var outputThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStream()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = readResultData(intent)
                if (resultCode < 0 || resultData == null) {
                    Log.e(TAG, "Missing MediaProjection permission result")
                    stopSelf()
                    return START_NOT_STICKY
                }

                runCatching {
                    startForegroundCompat()
                    stopStream()
                    stopping.set(false)
                    startStream(resultCode, resultData)
                }.onFailure {
                    Log.e(TAG, "Failed to start screen stream", it)
                    stopStream()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification =
            NotificationCompat
                .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AI screen stream")
                .setContentText("Real-time H.264 display capture is active")
                .setOngoing(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun startStream(
        resultCode: Int,
        resultData: Intent,
    ) {
        val projectionManager =
            getSystemService(MediaProjectionManager::class.java)
                ?: error("MediaProjectionManager unavailable")
        val projection =
            projectionManager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection permission could not be established")

        mediaProjection = projection
        projectionCallback =
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    stopStream()
                    stopSelf()
                }
            }
        projection.registerCallback(requireNotNull(projectionCallback), mainHandler)

        val metrics = resources.displayMetrics
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = windowManager?.maximumWindowMetrics?.bounds
        val rawWidth = bounds?.width() ?: metrics.widthPixels
        val rawHeight = bounds?.height() ?: metrics.heightPixels
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(rawWidth, rawHeight).toFloat())
        val width = evenDimension((rawWidth * scale).roundToInt())
        val height = evenDimension((rawHeight * scale).roundToInt())
        val densityDpi = metrics.densityDpi

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = codec
        val refreshRate =
            getSystemService(DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.refreshRate
                ?: 60f
        val targetFps = selectTargetFps(codec, width, height, refreshRate)
        val bitRate = bitrateFor(targetFps)

        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        encoderSurface = surface
        codec.start()

        virtualDisplay =
            projection.createVirtualDisplay(
                "AndroidRemoteControlMcpScreenStream",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler,
            )

        streamHub.setRunning(width, height, targetFps)
        streamHub.onClientConnected = { requestSyncFrame() }
        _running.value = true
        outputThread = thread(name = "ScreenStreamEncoder", start = true) { drainEncoder(codec) }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!stopping.get()) {
                when (val index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Unit
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        extractCodecConfig(codec.outputFormat)?.let(streamHub::publishCodecConfig)
                    }

                    else -> {
                        handleOutputBuffer(codec, index, info)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (!stopping.get()) Log.e(TAG, "Encoder output loop stopped unexpectedly", e)
        } catch (e: Exception) {
            if (!stopping.get()) Log.e(TAG, "Encoder output loop failed", e)
        }
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        if (index < 0) return
        try {
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null || info.size <= 0) return
            val payload = copyBuffer(buffer, info.offset, info.size)
            val frameFlags = frameFlags(info)
            if (frameFlags and ScreenStreamHub.FLAG_CODEC_CONFIG != 0) {
                streamHub.publishCodecConfig(payload)
            } else {
                streamHub.publishFrame(
                    info.presentationTimeUs,
                    frameFlags,
                    payload,
                )
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun frameFlags(info: MediaCodec.BufferInfo): Int =
        when {
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                ScreenStreamHub.FLAG_CODEC_CONFIG
            }

            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                ScreenStreamHub.FLAG_KEY_FRAME
            }

            else -> 0
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

    private fun stopStream() {
        if (!stopping.compareAndSet(false, true)) return
        _running.value = false
        streamHub.onClientConnected = null
        streamHub.setStopped()
        outputThread?.join(STOP_JOIN_TIMEOUT_MS)
        outputThread = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun readResultData(intent: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= ANDROID_13_API) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun copyBuffer(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
    ): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + size)
        return ByteArray(size).also { duplicate.get(it) }
    }

    private fun extractCodecConfig(format: MediaFormat): ByteArray? {
        val parts =
            listOf("csd-0", "csd-1").mapNotNull { key ->
                format.getByteBuffer(key)?.let { source ->
                    val duplicate = source.duplicate()
                    ByteArray(duplicate.remaining()).also { duplicate.get(it) }
                }
            }
        if (parts.isEmpty()) return null
        val totalSize = parts.sumOf { it.size + START_CODE.size }
        val output = ByteArray(totalSize)
        var position = 0
        for (part in parts) {
            START_CODE.copyInto(output, position)
            position += START_CODE.size
            part.copyInto(output, position)
            position += part.size
        }
        return output
    }

    private fun selectTargetFps(
        codec: MediaCodec,
        width: Int,
        height: Int,
        refreshRate: Float,
    ): Int {
        val displayFps =
            refreshRate
                .takeIf { it.isFinite() && it > 0f }
                ?.toInt()
                ?: DEFAULT_FPS

        val advertisedMax =
            runCatching {
                codec.codecInfo
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .videoCapabilities
                    ?.getSupportedFrameRatesFor(width, height)
                    ?.upper
                    ?.toInt()
                    ?: DEFAULT_FPS
            }.getOrDefault(DEFAULT_FPS)

        return minOf(MAX_FPS, displayFps, advertisedMax).coerceIn(MIN_FPS, MAX_FPS)
    }

    private fun bitrateFor(fps: Int): Int =
        (BASE_BIT_RATE.toLong() * fps / DEFAULT_FPS)
            .coerceAtMost(MAX_BIT_RATE.toLong())
            .toInt()

    private fun evenDimension(value: Int): Int {
        val safeValue = value.coerceAtLeast(MIN_DIMENSION)
        return if (safeValue % 2 == 0) safeValue else safeValue - 1
    }

    override fun onDestroy() {
        stopStream()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MCP:ScreenStream"
        private const val MAX_DIMENSION = 1920
        private const val BASE_BIT_RATE = 12_000_000
        private const val MAX_FPS = 120
        private const val MAX_BIT_RATE = 20_000_000
        private const val DEFAULT_FPS = 60
        private const val MIN_FPS = 30
        private const val MIN_DIMENSION = 2
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 500L
        private const val ANDROID_13_API = 33
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START =
            "com.danielealbano.androidremotecontrolmcp.ACTION_START_SCREEN_STREAM"
        const val ACTION_STOP =
            "com.danielealbano.androidremotecontrolmcp.ACTION_STOP_SCREEN_STREAM"
        const val EXTRA_RESULT_CODE = "screen_stream_result_code"
        const val EXTRA_RESULT_DATA = "screen_stream_result_data"

        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        @Volatile
        var instance: ScreenStreamService? = null
            private set
    }
}
