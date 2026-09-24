package com.virtualpad.app

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Manages the stream control backchannel on TCP port 8081.
 * Dispatches 9-byte little-endian binary control packets to PCMirror host.
 */
class StreamControlClient {

    companion object {
        private const val TAG = "StreamControl"
        private const val CONTROL_PORT = 8081
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isConnected = false
    @Volatile private var hostIp: String = "127.0.0.1"

    fun start(host: String) {
        hostIp = host
        executor.execute {
            connectInternal()
        }
    }

    private fun connectInternal() {
        try {
            disconnectInternal()
            val s = Socket().apply {
                connect(InetSocketAddress(hostIp, CONTROL_PORT), 3000)
                tcpNoDelay = true
                keepAlive = true
            }
            socket = s
            outputStream = s.getOutputStream()
            isConnected = true
            Log.i(TAG, "Connected to stream control channel on $hostIp:$CONTROL_PORT")
        } catch (e: Exception) {
            Log.w(TAG, "Stream control connection error: ${e.message}")
            isConnected = false
        }
    }

    fun stop() {
        executor.execute {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        isConnected = false
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
    }

    private fun sendPacket(type: Byte, p1: Float, p2: Float) {
        executor.execute {
            if (!isConnected || socket == null || socket?.isClosed == true || outputStream == null) {
                connectInternal()
            }
            val out = outputStream ?: return@execute
            try {
                val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                buf.put(type)
                buf.putFloat(p1)
                buf.putFloat(p2)
                out.write(buf.array())
                out.flush()
                Log.d(TAG, "Sent control packet: type=$type, p1=$p1, p2=$p2")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send control packet: ${e.message}, reconnecting...")
                disconnectInternal()
            }
        }
    }

    fun setBitrate(bps: Float) {
        sendPacket(10.toByte(), bps, 0.0f)
    }

    fun setResolution(width: Float, height: Float) {
        sendPacket(11.toByte(), width, height)
    }

    fun requestKeyframe() {
        sendPacket(12.toByte(), 0.0f, 0.0f)
    }

    fun setFps(fps: Float) {
        sendPacket(13.toByte(), fps, 0.0f)
    }

    fun setCursorVisible(visible: Boolean) {
        sendPacket(14.toByte(), if (visible) 1.0f else 0.0f, 0.0f)
    }

    fun sendCongestionFeedback(scale: Float) {
        sendPacket(15.toByte(), scale, 0.0f)
    }

    fun setCodec(codecId: Byte) {
        sendPacket(16.toByte(), codecId.toFloat(), 0.0f)
    }
}
