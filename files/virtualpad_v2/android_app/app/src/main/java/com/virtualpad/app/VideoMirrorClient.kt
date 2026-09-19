package com.virtualpad.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ultra-low latency H.264 video decoding client over TCP port 8080.
 * Decodes directly to SurfaceView hardware surface via MediaCodec.
 * Features automatic reconnection resilience and hardware surface validation.
 */
class VideoMirrorClient(private val surfaceView: SurfaceView) {

    companion object {
        private const val TAG = "VideoMirror"
        private const val VIDEO_PORT = 8080
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var socket: Socket? = null
    private var codec: MediaCodec? = null

    var onResolutionDetected: ((Int, Int) -> Unit)? = null
    var onStreamStateChanged: ((Boolean, String?) -> Unit)? = null
    var onKeyframeRequested: (() -> Unit)? = null

    fun start(host: String) {
        stop()
        running = true

        workerThread = Thread {
            while (running) {
                var activeSocket: Socket? = null
                var activeCodec: MediaCodec? = null
                try {
                    // 1. Wait for hardware surface to be valid
                    var waitCount = 0
                    while (running && !surfaceView.holder.surface.isValid && waitCount < 120) {
                        Thread.sleep(25)
                        waitCount++
                    }
                    if (!running) break
                    if (!surfaceView.holder.surface.isValid) {
                        Log.w(TAG, "Surface not valid yet, waiting...")
                        Thread.sleep(500)
                        continue
                    }

                    Log.i(TAG, "Connecting to video stream on $host:$VIDEO_PORT...")
                    val s = Socket().apply {
                        tcpNoDelay = true
                        receiveBufferSize = 1024 * 1024
                        connect(InetSocketAddress(host, VIDEO_PORT), 3000)
                    }
                    activeSocket = s
                    socket = s
                    onStreamStateChanged?.invoke(true, null)
                    Log.i(TAG, "Video socket connected successfully to $host:$VIDEO_PORT")

                    val dataIn = DataInputStream(s.getInputStream())
                    val packetBuf = ByteArray(4 * 1024 * 1024)

                    var sps: ByteArray? = null
                    var pps: ByteArray? = null
                    var initialPacketLen = 0

                    while (running && (sps == null || pps == null)) {
                        val len = dataIn.readInt()
                        if (len <= 0 || len > packetBuf.size) {
                            throw java.io.IOException("Invalid initial packet length: $len")
                        }
                        dataIn.readFully(packetBuf, 0, len)
                        val params = extractSpsPps(packetBuf, len)
                        if (params != null) {
                            sps = params.first
                            pps = params.second
                            initialPacketLen = len
                            break
                        }
                    }

                    if (sps == null || pps == null) {
                        throw java.io.IOException("Stream ended before SPS/PPS arrived")
                    }

                    val resolution = SpsParser.parse(sps)
                    val streamWidth = resolution?.first ?: DEFAULT_WIDTH
                    val streamHeight = resolution?.second ?: DEFAULT_HEIGHT
                    Log.i(TAG, "Configuring decoder for ${streamWidth}x${streamHeight}")

                    surfaceView.post {
                        onResolutionDetected?.invoke(streamWidth, streamHeight)
                    }

                    val format = MediaFormat.createVideoFormat(MIME, streamWidth, streamHeight).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        try {
                            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
                            setInteger("vendor.low-latency.enable", 1)
                            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                            setInteger("vendor.qti-ext-dec-picture-order.enable", 0)
                            setInteger("vendor.mtk-ext-dec-low-latency.enable", 1)
                        } catch (_: Exception) {}
                    }

                    val surface = surfaceView.holder.surface
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            surface.setFrameRate(120.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                        } catch (_: Exception) {}
                    }

                    val mediaCodec = MediaCodec.createDecoderByType(MIME)
                    val availableInputBuffers = LinkedBlockingQueue<Int>()

                    mediaCodec.setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                            availableInputBuffers.offer(index)
                        }
                        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                            try {
                                c.releaseOutputBuffer(index, true)
                            } catch (_: Exception) {}
                        }
                        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                            Log.e(TAG, "Codec error: ${e.diagnosticInfo}", e)
                        }
                        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                            val w = f.getInteger(MediaFormat.KEY_WIDTH)
                            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                            Log.i(TAG, "Output format changed: ${w}x${h}")
                        }
                    })

                    mediaCodec.configure(format, surface, null, 0)
                    mediaCodec.start()
                    activeCodec = mediaCodec
                    codec = mediaCodec
                    Log.i(TAG, "Decoder started in zero-latency hardware mode")

                    if (initialPacketLen > 0) {
                        val index = availableInputBuffers.poll(100, TimeUnit.MILLISECONDS)
                        if (index != null) {
                            val buf = mediaCodec.getInputBuffer(index)
                            if (buf != null) {
                                buf.clear()
                                buf.put(packetBuf, 0, initialPacketLen)
                                val queueTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
                                mediaCodec.queueInputBuffer(index, 0, initialPacketLen, queueTimeUs, 0)
                            }
                        }
                    }

                    while (running) {
                        val len = dataIn.readInt()
                        if (len <= 0 || len > packetBuf.size) {
                            Log.e(TAG, "Invalid packet length: $len")
                            break
                        }
                        dataIn.readFully(packetBuf, 0, len)
                        val index = availableInputBuffers.poll(8, TimeUnit.MILLISECONDS)
                        if (index == null) {
                            // Decoder input buffers saturated: drop frame to eliminate TCP backpressure and request fresh keyframe
                            onKeyframeRequested?.invoke()
                            continue
                        }
                        val buf = mediaCodec.getInputBuffer(index) ?: continue
                        buf.clear()
                        buf.put(packetBuf, 0, len)
                        val queueTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
                        mediaCodec.queueInputBuffer(index, 0, len, queueTimeUs, 0)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "Video decode loop note: ${e.javaClass.simpleName}: ${e.message}")
                        onStreamStateChanged?.invoke(false, e.message)
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                    }
                } finally {
                    try { activeCodec?.stop(); activeCodec?.release() } catch (_: Exception) {}
                    try { activeSocket?.close() } catch (_: Exception) {}
                    codec = null
                    socket = null
                    Log.i(TAG, "Video decode iteration ended")
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { workerThread?.interrupt() } catch (_: Exception) {}
        socket = null
        codec = null
        workerThread = null
    }

    private fun extractSpsPps(packet: ByteArray, len: Int): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        val startIndices = ArrayList<Int>()
        while (i + 3 < len) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) { startIndices.add(i + 3); i += 3; continue }
                if (i + 3 < len && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) { startIndices.add(i + 4); i += 4; continue }
            }
            i++
        }
        for (idx in 0 until startIndices.size) {
            val start = startIndices[idx]
            val end = if (idx + 1 < startIndices.size) {
                var e = startIndices[idx + 1] - 3
                if (e > start && packet[e - 1] == 0.toByte()) e--
                e
            } else len
            val nalType = packet[start].toInt() and 0x1F
            if (nalType == 7 && sps == null) {
                val prefix = byteArrayOf(0, 0, 0, 1)
                val nal = packet.copyOfRange(start, end)
                sps = prefix + nal
            } else if (nalType == 8 && pps == null) {
                val prefix = byteArrayOf(0, 0, 0, 1)
                val nal = packet.copyOfRange(start, end)
                pps = prefix + nal
            }
        }
        return if (sps != null && pps != null) Pair(sps, pps) else null
    }
}
