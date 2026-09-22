package com.danielealbano.androidremotecontrolmcp.services.screencapture

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class ScreenStreamStatus(
    val running: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val codec: String = "H.264",
)

data class EncodedScreenFrame(
    val presentationTimeUs: Long,
    val flags: Int,
    val payload: ByteArray,
) {
    fun toWireBytes(): ByteArray {
        val buffer =
            ByteBuffer
                .allocate(HEADER_SIZE + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION)
        buffer.putShort(flags.toShort())
        buffer.putLong(presentationTimeUs)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        private val MAGIC =
            byteArrayOf(
                'A'.code.toByte(),
                'R'.code.toByte(),
                'C'.code.toByte(),
                'S'.code.toByte(),
            )
        private const val VERSION = 1.toByte()
        private const val HEADER_SIZE = 4 + 1 + 2 + 8 + 4
    }
}

@Singleton
class ScreenStreamHub
    @Inject
    constructor() {
        private val frames =
            MutableSharedFlow<EncodedScreenFrame>(
                replay = 0,
                extraBufferCapacity = 8,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val codecConfig = AtomicReference<ByteArray?>(null)
        private val _status = MutableStateFlow(ScreenStreamStatus())
        val status = _status.asStateFlow()

        @Volatile
        var onClientConnected: (() -> Unit)? = null

        fun setRunning(width: Int, height: Int, fps: Int) {
            _status.value = ScreenStreamStatus(running = true, width = width, height = height, fps = fps)
        }

        fun setStopped() {
            _status.value = ScreenStreamStatus()
            codecConfig.set(null)
        }

        fun publishCodecConfig(payload: ByteArray) {
            codecConfig.set(payload.copyOf())
        }

        fun publishFrame(presentationTimeUs: Long, flags: Int, payload: ByteArray) {
            frames.tryEmit(
                EncodedScreenFrame(
                    presentationTimeUs = presentationTimeUs,
                    flags = flags,
                    payload = payload,
                ),
            )
        }

        suspend fun streamTo(session: WebSocketSession) {
            onClientConnected?.invoke()
            codecConfig.get()?.let { config ->
                session.send(
                    Frame.Binary(
                        fin = true,
                        data = EncodedScreenFrame(0, FLAG_CODEC_CONFIG, config).toWireBytes(),
                    ),
                )
            }
            frames.collect { frame ->
                session.send(Frame.Binary(fin = true, data = frame.toWireBytes()))
            }
        }

        fun statusJson(): String {
            val value = _status.value
            return """{"running":${value.running},"width":${value.width},"height":${value.height},"fps":${value.fps},"codec":"${value.codec}","transport":"websocket","path":"/screen/stream"}"""
        }

        companion object {
            const val FLAG_KEY_FRAME = 1
            const val FLAG_CODEC_CONFIG = 2
        }
    }
