package com.virtualpad.app

import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

const val PACKET_MAGIC: Byte = 0xAA.toByte()
const val PACKET_SIZE = 9 // 1 (magic) + 2 (buttons) + 1 + 1 (stick) + 2 + 2 (mouse)
const val DEFAULT_PORT = 6001

/**
 * Bit order MUST match BUTTON_ORDER in server.py exactly.
 */
enum class Btn(val bit: Int) {
    LB(0), RB(1), LT(2), RT(3),
    Y(4), X(5), B(6), A(7),
    LSB(8), RSB(9),
    DPAD_UP(10), DPAD_DOWN(11), DPAD_LEFT(12), DPAD_RIGHT(13),
    SMALL_ICON(14), HAMBURGER_ICON(15),
}

/**
 * Full input snapshot. Stick and mouse are floats/ints in natural units;
 * toBytes() packs everything into the compact binary wire format.
 */
data class ControllerState(
    val buttons: Int = 0,               // bitmask, see Btn
    val stickX: Float = 0f,             // -1.0 .. 1.0
    val stickY: Float = 0f,             // -1.0 .. 1.0
    val mouseDx: Int = 0,
    val mouseDy: Int = 0,
) {
    fun withButton(btn: Btn, pressed: Boolean): ControllerState {
        val mask = 1 shl btn.bit
        val newButtons = if (pressed) buttons or mask else buttons and mask.inv()
        return copy(buttons = newButtons)
    }

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PACKET_MAGIC)
        buf.putShort(buttons.toShort())
        buf.put((stickX.coerceIn(-1f, 1f) * 127).toInt().toByte())
        buf.put((stickY.coerceIn(-1f, 1f) * 127).toInt().toByte())
        buf.putShort(mouseDx.coerceIn(-32000, 32000).toShort())
        buf.putShort(mouseDy.coerceIn(-32000, 32000).toShort())
        return buf.array()
    }
}

enum class TransportMode { WIFI, USB }

/**
 * Sends ControllerState to the PC. Two transports:
 *  - WIFI: raw UDP to a user-supplied (or QR-scanned) host:port
 *  - USB:  TCP to 127.0.0.1:port, tunneled over the cable via
 *          `adb reverse tcp:port tcp:port` run once on the PC
 *
 * Sending is EVENT-DRIVEN: submit() sends immediately whenever anything
 * changed (or there's mouse movement), rather than waiting for a fixed
 * tick. A low-rate heartbeat resends the last button/stick state as a
 * safety net against dropped UDP packets - it never resends stale mouse
 * deltas (those are one-shot).
 */
class NetworkClient {

    var mode: TransportMode = TransportMode.WIFI
    var wifiHost: String = ""
    var port: Int = DEFAULT_PORT

    private val udpSocket = DatagramSocket()
    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null

    private var lastSent = ControllerState()
    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    fun connectUsb(onError: (Exception) -> Unit = {}) {
        Thread {
            try {
                tcpSocket?.close()
                val s = Socket("127.0.0.1", port)
                s.tcpNoDelay = true // disable Nagle's algorithm - send immediately
                tcpSocket = s
                tcpOut = s.getOutputStream()
            } catch (e: Exception) {
                onError(e)
            }
        }.start()
    }

    fun disconnectUsb() {
        try { tcpOut?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpOut = null
        tcpSocket = null
    }

    /** Call once you have host:port, e.g. from manual entry or a scanned QR. */
    fun setWifiTarget(host: String, port: Int = DEFAULT_PORT) {
        this.wifiHost = host
        this.port = port
    }

    fun start() {
        if (running.getAndSet(true)) return
        heartbeatThread = Thread {
            while (running.get()) {
                try {
                    // heartbeat: resend last known buttons/stick, but never
                    // replay mouse deltas (those are one-shot events)
                    sendRaw(lastSent.copy(mouseDx = 0, mouseDy = 0).toBytes())
                } catch (_: Exception) {
                    // ignore transient send errors, keep looping
                }
                Thread.sleep(30)
            }
        }
        heartbeatThread?.isDaemon = true
        heartbeatThread?.start()
    }

    fun stop() {
        running.set(false)
        heartbeatThread?.interrupt()
        disconnectUsb()
    }

    /** Event-driven: call this every time touch input changes. Sends immediately. */
    fun submit(state: ControllerState) {
        val changed = state.buttons != lastSent.buttons ||
            state.stickX != lastSent.stickX ||
            state.stickY != lastSent.stickY
        val hasMouseMotion = state.mouseDx != 0 || state.mouseDy != 0

        if (changed || hasMouseMotion) {
            try {
                sendRaw(state.toBytes())
            } catch (_: Exception) {
                // ignore - heartbeat/next event will retry
            }
            // mouse delta is one-shot; don't let the heartbeat replay it
            lastSent = state.copy(mouseDx = 0, mouseDy = 0)
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        when (mode) {
            TransportMode.WIFI -> {
                if (wifiHost.isEmpty()) return
                val addr = InetAddress.getByName(wifiHost)
                udpSocket.send(DatagramPacket(bytes, bytes.size, addr, port))
            }
            TransportMode.USB -> {
                tcpOut?.write(bytes)
                tcpOut?.flush()
            }
        }
    }
}
