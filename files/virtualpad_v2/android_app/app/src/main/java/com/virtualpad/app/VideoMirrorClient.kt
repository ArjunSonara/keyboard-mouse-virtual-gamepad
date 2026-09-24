package com.virtualpad.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra-low latency video decoding client over TCP port 8080.
 * Supports both Hardware H.265 (HEVC) and H.264 (AVC) with automatic bitstream detection.
 * Features 120Hz VSync locked rendering, multi-slice frame ingestion, real-time congestion feedback,
 * and high-precision performance telemetry (live FPS, sub-millisecond decode latency, network throughput).
 */
class VideoMirrorClient(private val surfaceView: SurfaceView) {

    companion object {
        private const val TAG = "VideoMirror"
        private const val VIDEO_PORT = 8080
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    data class StreamParams(
        val mime: String,
        val csd0: ByteArray,
        val csd1: ByteArray?,
        val width: Int,
        val height: Int
    )

    data class StreamTelemetry(
        val fps: Float,
        val bitrateMbps: Float,
        val decodeLatencyMs: Float,
        val droppedFrames: Int,
        val codecName: String,
        val width: Int,
        val height: Int
    )

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var socket: Socket? = null
    private var codec: MediaCodec? = null

    // High-Precision Real-time Telemetry Tracking
    private val framesRenderedCounter = AtomicInteger(0)
    private val bytesCounter = AtomicLong(0L)
    private val droppedFramesCounter = AtomicInteger(0)
    private val latencySumUs = AtomicLong(0L)
    private val latencyCount = AtomicInteger(0)

    @Volatile private var currentStreamWidth = DEFAULT_WIDTH
    @Volatile private var currentStreamHeight = DEFAULT_HEIGHT
    @Volatile private var currentCodecDisplayName = "H.265 / HEVC"

    private var telemetryExecutor: ScheduledExecutorService? = null

    var onResolutionDetected: ((Int, Int) -> Unit)? = null
    var onStreamStateChanged: ((Boolean, String?) -> Unit)? = null
    var onKeyframeRequested: (() -> Unit)? = null
    var onCongestionDetected: ((Float) -> Unit)? = null
    var onCodecDetected: ((String) -> Unit)? = null
    var onTelemetryUpdated: ((StreamTelemetry) -> Unit)? = null

    @Synchronized
    fun start(host: String) {
        stop()
        running = true

        // Reset telemetry counters and launch periodic 500ms calculation ticker
        framesRenderedCounter.set(0)
        bytesCounter.set(0)
        droppedFramesCounter.set(0)
        latencySumUs.set(0L)
        latencyCount.set(0)
        var lastWindowNanos = SystemClock.elapsedRealtimeNanos()

        val exec = Executors.newSingleThreadScheduledExecutor()
        telemetryExecutor = exec
        exec.scheduleWithFixedDelay({
            if (!running) return@scheduleWithFixedDelay
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val dtSec = (nowNanos - lastWindowNanos) / 1_000_000_000.0
            if (dtSec >= 0.2) {
                val frames = framesRenderedCounter.getAndSet(0)
                val bytes = bytesCounter.getAndSet(0)
                val drops = droppedFramesCounter.getAndSet(0)
                val latSum = latencySumUs.getAndSet(0L)
                val latCnt = latencyCount.getAndSet(0)
                lastWindowNanos = nowNanos

                val fps = (frames / dtSec).toFloat()
                val bitrateMbps = ((bytes * 8.0) / (dtSec * 1_000_000.0)).toFloat()
                val avgLatencyMs = if (latCnt > 0) (latSum.toFloat() / latCnt / 1000.0f) else 0.0f

                val telemetry = StreamTelemetry(
                    fps = fps,
                    bitrateMbps = bitrateMbps,
                    decodeLatencyMs = avgLatencyMs,
                    droppedFrames = drops,
                    codecName = currentCodecDisplayName,
                    width = currentStreamWidth,
                    height = currentStreamHeight
                )
                surfaceView.post {
                    onTelemetryUpdated?.invoke(telemetry)
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS)

        val t = Thread {
            while (running && Thread.currentThread() == workerThread) {
                var activeSocket: Socket? = null
                var activeCodec: MediaCodec? = null
                try {
                    // 1. Wait for hardware surface to be valid
                    var waitCount = 0
                    while (running && Thread.currentThread() == workerThread && !surfaceView.holder.surface.isValid && waitCount < 120) {
                        Thread.sleep(25)
                        waitCount++
                    }
                    if (!running || Thread.currentThread() != workerThread) break
                    if (!surfaceView.holder.surface.isValid) {
                        Log.w(TAG, "Surface not valid yet, waiting...")
                        Thread.sleep(500)
                        continue
                    }

                    Log.i(TAG, "Connecting to video stream on $host:$VIDEO_PORT...")
                    val s = Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        receiveBufferSize = 2 * 1024 * 1024
                        connect(InetSocketAddress(host, VIDEO_PORT), 3000)
                    }
                    activeSocket = s
                    socket = s
                    onStreamStateChanged?.invoke(true, null)
                    Log.i(TAG, "Video socket connected successfully to $host:$VIDEO_PORT")

                    val dataIn = DataInputStream(s.getInputStream())
                    val packetBuf = ByteArray(6 * 1024 * 1024)

                    var detectedParams: StreamParams? = null
                    var initialPacketLen = 0

                    // 2. Read stream until parameter sets arrive (HEVC VPS/SPS/PPS or H.264 SPS/PPS)
                    while (running && detectedParams == null) {
                        val len = dataIn.readInt()
                        if (len <= 0 || len > packetBuf.size) {
                            throw java.io.IOException("Invalid initial packet length: $len")
                        }
                        dataIn.readFully(packetBuf, 0, len)
                        bytesCounter.addAndGet((len + 4).toLong())
                        val params = parseCodecParams(packetBuf, len)
                        if (params != null) {
                            detectedParams = params
                            initialPacketLen = len
                            break
                        }
                    }

                    if (detectedParams == null) {
                        throw java.io.IOException("Stream ended before codec parameter sets arrived")
                    }

                    val currentMime = detectedParams.mime
                    val streamWidth = detectedParams.width
                    val streamHeight = detectedParams.height
                    currentStreamWidth = streamWidth
                    currentStreamHeight = streamHeight
                    val codecLabel = if (currentMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265 / HEVC" else "H.264 / AVC"
                    currentCodecDisplayName = codecLabel
                    Log.i(TAG, "Configuring decoder for MIME=$currentMime, ${streamWidth}x${streamHeight}")

                    surfaceView.post {
                        onResolutionDetected?.invoke(streamWidth, streamHeight)
                        onCodecDetected?.invoke(codecLabel)
                    }

                    // 3. Configure MediaFormat for ultra-low latency & 120Hz display
                    val format = MediaFormat.createVideoFormat(currentMime, streamWidth, streamHeight).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(detectedParams.csd0))
                        detectedParams.csd1?.let {
                            setByteBuffer("csd-1", ByteBuffer.wrap(it))
                        }
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6 * 1024 * 1024)
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
                            // Lock hardware display surface to 120Hz VSync
                            surface.setFrameRate(120.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                        } catch (_: Exception) {}
                    }

                    val mediaCodec = MediaCodec.createDecoderByType(currentMime)
                    val availableInputBuffers = LinkedBlockingQueue<Int>()

                    mediaCodec.setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                            availableInputBuffers.offer(index)
                        }
                        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                            try {
                                val finishUs = SystemClock.elapsedRealtimeNanos() / 1000L
                                if (info.presentationTimeUs > 0) {
                                    val latencyUs = finishUs - info.presentationTimeUs
                                    if (latencyUs in 0..500_000L) {
                                        latencySumUs.addAndGet(latencyUs)
                                        latencyCount.incrementAndGet()
                                    }
                                }
                                framesRenderedCounter.incrementAndGet()
                                // Direct zero-latency hardware release aligned with 120Hz VSync
                                c.releaseOutputBuffer(index, true)
                            } catch (_: Exception) {}
                        }
                        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                            Log.e(TAG, "Codec error: ${e.diagnosticInfo}", e)
                        }
                        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                            val w = f.getInteger(MediaFormat.KEY_WIDTH)
                            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                            currentStreamWidth = w
                            currentStreamHeight = h
                            Log.i(TAG, "Output format dynamically adjusted to: ${w}x${h}")
                            surfaceView.post {
                                onResolutionDetected?.invoke(w, h)
                            }
                        }
                    })

                    mediaCodec.configure(format, surface, null, 0)
                    mediaCodec.start()
                    activeCodec = mediaCodec
                    codec = mediaCodec
                    Log.i(TAG, "Hardware decoder started ($currentMime) in 120Hz low-latency mode")

                    // Queue initial packet
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

                    var consecutiveCongestionCount = 0
                    var smoothFrameCount = 0

                    // 4. Main Streaming & Decoding Loop
                    while (running) {
                        val len = dataIn.readInt()
                        if (len <= 0 || len > packetBuf.size) {
                            Log.e(TAG, "Invalid packet length: $len")
                            break
                        }
                        dataIn.readFully(packetBuf, 0, len)
                        bytesCounter.addAndGet((len + 4).toLong())

                        // Poll input buffer with 8ms timeout (~1 frame at 120Hz)
                        val index = availableInputBuffers.poll(8, TimeUnit.MILLISECONDS)
                        if (index == null) {
                            droppedFramesCounter.incrementAndGet()
                            // Decoder queue saturated: congestion event
                            consecutiveCongestionCount++
                            if (consecutiveCongestionCount >= 2) {
                                onCongestionDetected?.invoke(0.80f)
                                consecutiveCongestionCount = 0
                                smoothFrameCount = 0
                            }
                            // Drop saturated frame to prevent TCP pileup and request fresh IDR
                            onKeyframeRequested?.invoke()
                            continue
                        } else {
                            consecutiveCongestionCount = 0
                            smoothFrameCount++
                            if (smoothFrameCount >= 180) { // ~1.5s of smooth decode
                                onCongestionDetected?.invoke(1.0f)
                                smoothFrameCount = 0
                            }
                        }

                        val buf = mediaCodec.getInputBuffer(index) ?: continue
                        if (buf.capacity() < len) {
                            droppedFramesCounter.incrementAndGet()
                            Log.w(TAG, "Frame size $len exceeds buffer capacity ${buf.capacity()}, dropping and requesting keyframe")
                            onKeyframeRequested?.invoke()
                            continue
                        }
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
        }
        workerThread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        try { telemetryExecutor?.shutdownNow() } catch (_: Exception) {}
        telemetryExecutor = null
        surfaceView.post {
            onTelemetryUpdated?.invoke(
                StreamTelemetry(0f, 0f, 0f, 0, currentCodecDisplayName, currentStreamWidth, currentStreamHeight)
            )
        }
        val t = workerThread
        workerThread = null
        try { socket?.close() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { t?.interrupt() } catch (_: Exception) {}
        if (t != null && t.isAlive && Thread.currentThread() != t) {
            try { t.join(300) } catch (_: Exception) {}
        }
        socket = null
        codec = null
    }

    /**
     * Inspects bitstream NAL units to automatically detect whether the stream is
     * H.265 / HEVC (VPS 32, SPS 33, PPS 34) or H.264 / AVC (SPS 7, PPS 8).
     */
    private fun parseCodecParams(packet: ByteArray, len: Int): StreamParams? {
        val startIndices = ArrayList<Int>()
        var i = 0
        while (i + 3 < len) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) { startIndices.add(i + 3); i += 3; continue }
                if (i + 3 < len && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) { startIndices.add(i + 4); i += 4; continue }
            }
            i++
        }

        var isHevc = false
        var hevcVps: ByteArray? = null
        var hevcSps: ByteArray? = null
        var hevcPps: ByteArray? = null

        var avcSps: ByteArray? = null
        var avcPps: ByteArray? = null

        val prefix = byteArrayOf(0, 0, 0, 1)

        for (idx in 0 until startIndices.size) {
            val start = startIndices[idx]
            val end = if (idx + 1 < startIndices.size) {
                var e = startIndices[idx + 1] - 3
                if (e > start && packet[e - 1] == 0.toByte()) e--
                e
            } else len

            val b0 = packet[start].toInt() and 0xFF
            val hevcNalType = (b0 ushr 1) and 0x3F

            if (hevcNalType == 32) { // HEVC VPS
                isHevc = true
                hevcVps = packet.copyOfRange(start, end)
            } else if (hevcNalType == 33 && isHevc) { // HEVC SPS
                hevcSps = packet.copyOfRange(start, end)
            } else if (hevcNalType == 34 && isHevc) { // HEVC PPS
                hevcPps = packet.copyOfRange(start, end)
            } else {
                val avcNalType = b0 and 0x1F
                if (avcNalType == 7 && !isHevc) {
                    avcSps = packet.copyOfRange(start, end)
                } else if (avcNalType == 8 && !isHevc) {
                    avcPps = packet.copyOfRange(start, end)
                }
            }
        }

        if (isHevc && hevcVps != null && hevcSps != null && hevcPps != null) {
            val csd0 = ByteArrayOutputStream().apply {
                write(prefix)
                write(hevcVps)
                write(prefix)
                write(hevcSps)
                write(prefix)
                write(hevcPps)
            }.toByteArray()

            return StreamParams(
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                csd0 = csd0,
                csd1 = null,
                width = DEFAULT_WIDTH,
                height = DEFAULT_HEIGHT
            )
        } else if (!isHevc && avcSps != null && avcPps != null) {
            val fullSps = prefix + avcSps
            val fullPps = prefix + avcPps
            val resolution = SpsParser.parse(fullSps)
            val w = resolution?.first ?: DEFAULT_WIDTH
            val h = resolution?.second ?: DEFAULT_HEIGHT

            return StreamParams(
                mime = MediaFormat.MIMETYPE_VIDEO_AVC,
                csd0 = fullSps,
                csd1 = fullPps,
                width = w,
                height = h
            )
        }

        return null
    }
}
