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

    fun start(host: String) {
        stop()
        if (!isAudioEnabled) return
        running = true

        workerThread = Thread {
            while (running) {
                var track: AudioTrack? = null
                var socket: Socket? = null
                try {
                    Log.i(TAG, "Connecting to audio stream on $host:$AUDIO_PORT...")
                    socket = Socket().apply {
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
                    val bufferSize = (minBuf * 2).coerceAtLeast(4096)

                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
                    track.play()
                    Log.i(TAG, "AudioTrack playing at $validRate Hz (Low-Latency)")

                    val pcmBuf = ByteArray(4096)
                    socket.soTimeout = 0 // Continuous stream

                    while (running) {
                        val n = inStream.read(pcmBuf, 0, pcmBuf.size)
                        if (n <= 0) break
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
            start()
        }
    }

    fun stop() {
        running = false
        try { audioSocket?.close() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioSocket = null
        audioTrack = null
        workerThread = null
    }
}
