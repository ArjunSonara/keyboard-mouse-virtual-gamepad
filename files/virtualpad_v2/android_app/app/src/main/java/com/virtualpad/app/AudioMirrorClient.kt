package com.virtualpad.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time WASAPI audio streaming receiver over TCP port 8082.
 * Feeds 16-bit PCM stereo samples directly to AudioTrack with low latency.
 * Features automatic reconnection loop.
 */
class AudioMirrorClient {

    companion object {
        private const val TAG = "AudioMirror"
        private const val AUDIO_PORT = 8082
    }

    @Volatile private var running = false
    private var workerThread: Thread? = null
    private var audioSocket: Socket? = null
    private var audioTrack: AudioTrack? = null

    var isAudioEnabled: Boolean = true

    @Synchronized
    fun start(host: String) {
        stop()
        if (!isAudioEnabled) return
        running = true

        val t = Thread {
            while (running && Thread.currentThread() == workerThread) {
                var track: AudioTrack? = null
                var socket: Socket? = null
                try {
                    Log.i(TAG, "Connecting to audio stream on $host:$AUDIO_PORT...")
                    socket = Socket().apply {
                        receiveBufferSize = 8 * 1024
                        connect(InetSocketAddress(host, AUDIO_PORT), 3000)
                        tcpNoDelay = true
                        soTimeout = 8000
                    }
                    audioSocket = socket
                    val inStream = DataInputStream(socket.getInputStream())

                    // 8-byte handshake: [magic (4 bytes, 0x50434D41), sampleRate (4 bytes, little endian)]
                    val header = ByteArray(8)
                    inStream.readFully(header)
                    val magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val sampleRate = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    Log.i(TAG, "Audio handshake: magic=0x${Integer.toHexString(magic)}, sampleRate=$sampleRate Hz")

                    val validRate = if (sampleRate in 8000..192000) sampleRate else 48000
                    val minBuf = AudioTrack.getMinBufferSize(
                        validRate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufferSize = minBuf.coerceAtLeast(2048)

                    // USAGE_GAME + CONTENT_TYPE_SONIFICATION routes through Android FastMixer (low-latency path)
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()

                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(validRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()

                    val trackBuilder = AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }

                    track = trackBuilder.build()
                    audioTrack = track

                    // API 24+: Set hardware buffer size to ~20ms to eliminate mixer queue latency
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val targetFrames = (validRate * 20) / 1000
                        val minFrames = minBuf / 4
                        track.setBufferSizeInFrames(targetFrames.coerceAtLeast(minFrames))
                    }

                    track.play()
                    Log.i(TAG, "AudioTrack playing at $validRate Hz (Ultra-Low Latency FastMixer)")

                    val pcmBuf = ByteArray(2048)
                    socket.soTimeout = 0 // Continuous stream
                    var framesWritten: Long = 0L

                    while (running) {
                        val n = inStream.read(pcmBuf, 0, pcmBuf.size)
                        if (n <= 0) break

                        // Latency Drift Guard: monitor buffered frame delay
                        framesWritten += (n / 4)
                        val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        val unplayedFrames = framesWritten - headPos
                        val latencyMs = (unplayedFrames * 1000) / validRate

                        // If audio queue drifts beyond 40ms, drop stale frames/backlog
                        if (latencyMs > 40) {
                            if (latencyMs > 100) {
                                track.pause()
                                track.flush()
                                track.play()
                                framesWritten = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            }
                            val avail = inStream.available()
                            if (avail > 0) {
                                val toSkip = avail.coerceAtMost(8192)
                                inStream.skipBytes(toSkip)
                            }
                            continue
                        }

                        track.write(pcmBuf, 0, n)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "Audio stream note: ${e.message}")
                        try { Thread.sleep(1500) } catch (_: Exception) {}
                    }
                } finally {
                    try { track?.stop() } catch (_: Exception) {}
                    try { track?.release() } catch (_: Exception) {}
                    try { socket?.close() } catch (_: Exception) {}
                    if (audioTrack == track) audioTrack = null
                    if (audioSocket == socket) audioSocket = null
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
        val t = workerThread
        workerThread = null
        try { audioSocket?.close() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { t?.interrupt() } catch (_: Exception) {}
        if (t != null && t.isAlive && Thread.currentThread() != t) {
            try { t.join(300) } catch (_: Exception) {}
        }
        audioSocket = null
        audioTrack = null
    }
}
