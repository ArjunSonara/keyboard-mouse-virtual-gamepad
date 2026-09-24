package com.virtualpad.app

import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

const val PACKET_MAGIC: Byte = 0xAA.toByte()
const val PACKET_MAGIC_CONFIG: Byte = 0xAC.toByte()
const val PACKET_MAGIC_WHEEL: Byte = 0xAD.toByte()
const val PACKET_MAGIC_MACRO_KEY: Byte = 0xAE.toByte()
const val PACKET_SIZE = 11 // 1 (magic) + 4 (32-bit buttons) + 1 + 1 (stick) + 2 + 2 (mouse)
const val DEFAULT_PORT = 6001

/**
 * Bit order MUST match BUTTON_ORDER in server.py exactly.
 * Bits 0..15 are reserved for standard stock buttons.
 * Bits 16..31 are available for up to 16 custom user buttons (custom_0 .. custom_15).
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
 * toBytes() packs everything into the compact binary wire format (11 bytes).
 */
data class ControllerState(
    val buttons: Int = 0,               // 32-bit bitmask: bits 0..15 stock, bits 16..31 custom
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

    fun withCustomButton(slot: Int, pressed: Boolean): ControllerState {
        if (slot !in 0..15) return this
        val mask = 1 shl (16 + slot)
        val newButtons = if (pressed) buttons or mask else buttons and mask.inv()
        return copy(buttons = newButtons)
    }

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PACKET_MAGIC)
        buf.putInt(buttons)
        buf.put((stickX.coerceIn(-1f, 1f) * 127).toInt().toByte())
        buf.put((stickY.coerceIn(-1f, 1f) * 127).toInt().toByte())
        buf.putShort(mouseDx.coerceIn(-32000, 32000).toShort())
        buf.putShort(mouseDy.coerceIn(-32000, 32000).toShort())
        return buf.array()
    }
}

enum class TransportMode { WIFI, USB }

/**
 * Sends ControllerState and dynamic key config to the PC.
 *  - WIFI: raw UDP to host:port.
 *  - USB:  TCP to 127.0.0.1:port via `adb reverse tcp:port tcp:port`.
 *          All TCP packets are framed with a 2-byte little-endian length prefix.
 */
class NetworkClient {

    var mode: TransportMode = TransportMode.WIFI
    var wifiHost: String = ""
    var port: Int = DEFAULT_PORT

    private val udpSocket = DatagramSocket()
    private var tcpSocket: Socket? = null
    private var tcpOut: OutputStream? = null

    private val socketLock = Any()
    private val reconnectSignal = java.lang.Object()
    // Pre-allocated 13-byte buffer: 2 bytes length prefix (11) + 11 bytes PACKET_SIZE payload
    private val usbPacketBuffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)

    private val sendExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var targetAddress: InetAddress? = null

    @Volatile private var lastSent = ControllerState()
    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null
    private var usbReconnectThread: Thread? = null

    @Volatile var activeKeymap: Map<String, String>? = null
    @Volatile var isConnected: Boolean = false
        private set

    fun connectUsb(onError: (Exception) -> Unit = {}) {
        mode = TransportMode.USB
        triggerUsbReconnect()
    }

    fun triggerUsbReconnect() {
        disconnectUsb()
        synchronized(reconnectSignal) {
            try {
                reconnectSignal.notifyAll()
            } catch (_: Exception) {}
        }
    }

    fun disconnectUsb() {
        synchronized(socketLock) {
            isConnected = false
            try { tcpOut?.close() } catch (_: Exception) {}
            try { tcpSocket?.close() } catch (_: Exception) {}
            tcpOut = null
            tcpSocket = null
        }
    }

    /** Call once you have host:port, e.g. from manual entry or a scanned QR. */
    fun setWifiTarget(host: String, port: Int = DEFAULT_PORT) {
        this.wifiHost = host
        this.port = port
        sendExecutor.execute {
            try {
                targetAddress = InetAddress.getByName(host)
                activeKeymap?.let { sendKeymapSync(it) }
            } catch (_: Exception) {
                targetAddress = null
            }
        }
    }

    fun start() {
        if (running.getAndSet(true)) return

        // 1. Dedicated USB Auto-Reconnect Daemon: keeps port 6001 connected 100% of the time
        usbReconnectThread = Thread {
            while (running.get()) {
                if (mode == TransportMode.USB) {
                    val needsConnect = synchronized(socketLock) {
                        tcpSocket == null || !tcpSocket!!.isConnected || tcpSocket!!.isClosed
                    }
                    if (needsConnect) {
                        try {
                            val s = Socket()
                            s.tcpNoDelay = true
                            s.keepAlive = true
                            s.sendBufferSize = 1024
                            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1200)
                            val out = s.getOutputStream()
                            synchronized(socketLock) {
                                tcpSocket = s
                                tcpOut = out
                                isConnected = true
                            }
                            android.util.Log.i("NetworkClient", "[NetworkClient] USB connected to 127.0.0.1:$port")
                            activeKeymap?.let { sendKeymapSync(it) }
                        } catch (e: Exception) {
                            disconnectUsb()
                        }
                    }
                }

                try {
                    synchronized(reconnectSignal) {
                        reconnectSignal.wait(1000)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }.apply {
            isDaemon = true
            name = "NetworkClient-USB-Reconnect"
            start()
        }

        // 2. Continuous Input Heartbeat (resend button holds & stick positions)
        heartbeatThread = Thread {
            while (running.get()) {
                try {
                    // heartbeat: resend last known buttons/stick, never replay mouse deltas
                    if (mode == TransportMode.USB) {
                        writeUsbStateDirect(lastSent.copy(mouseDx = 0, mouseDy = 0))
                    } else {
                        sendRaw(lastSent.copy(mouseDx = 0, mouseDy = 0).toBytes())
                    }
                    Thread.sleep(30)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // ignore transient send errors, keep looping
                }
            }
        }
        heartbeatThread?.isDaemon = true
        heartbeatThread?.start()
    }

    fun stop() {
        running.set(false)
        heartbeatThread?.interrupt()
        synchronized(reconnectSignal) {
            try { reconnectSignal.notifyAll() } catch (_: Exception) {}
        }
        usbReconnectThread?.interrupt()
        disconnectUsb()
        try { sendExecutor.shutdownNow() } catch (_: Exception) {}
    }

    /**
     * Writes state directly to the USB TCP socket using the pre-allocated 13-byte buffer.
     * Bypasses heap allocation and SingleThreadExecutor queue for zero latency.
     */
    private fun writeUsbStateDirect(state: ControllerState) {
        val out = synchronized(socketLock) { tcpOut } ?: return
        synchronized(socketLock) {
            try {
                usbPacketBuffer.clear()
                usbPacketBuffer.putShort(PACKET_SIZE.toShort()) // 11 bytes payload
                usbPacketBuffer.put(PACKET_MAGIC)
                usbPacketBuffer.putInt(state.buttons)
                usbPacketBuffer.put((state.stickX.coerceIn(-1f, 1f) * 127).toInt().toByte())
                usbPacketBuffer.put((state.stickY.coerceIn(-1f, 1f) * 127).toInt().toByte())
                usbPacketBuffer.putShort(state.mouseDx.coerceIn(-32000, 32000).toShort())
                usbPacketBuffer.putShort(state.mouseDy.coerceIn(-32000, 32000).toShort())
                out.write(usbPacketBuffer.array(), 0, 13)
                out.flush()
            } catch (e: Exception) {
                android.util.Log.e("NetworkClient", "USB write failed, triggering reconnect: ${e.message}")
                triggerUsbReconnect()
            }
        }
    }

    /** Event-driven: call this every time touch input changes. Sends immediately. */
    fun submit(state: ControllerState) {
        val buttonChanged = state.buttons != lastSent.buttons
        val stickChanged = state.stickX != lastSent.stickX || state.stickY != lastSent.stickY
        val changed = buttonChanged || stickChanged
        val hasMouseMotion = state.mouseDx != 0 || state.mouseDy != 0

        if (changed || hasMouseMotion) {
            if (mode == TransportMode.USB) {
                // Zero-allocation direct socket streaming on touch thread:
                // Overwrites pre-allocated 13-byte buffer in place and writes directly to socket output stream,
                // completely bypassing SingleThreadExecutor queuing and GC allocations.
                writeUsbStateDirect(state)
            } else {
                val bytes = state.toBytes()
                sendExecutor.execute {
                    try {
                        sendRaw(bytes)
                        if (buttonChanged && mode == TransportMode.WIFI) {
                            // Multi-Touch Burst Redundancy: 2x UDP packet transmission guarantees
                            // that simultaneous button presses arrive immediately even on lossy Wi-Fi.
                            sendRaw(bytes)
                        }
                    } catch (_: Exception) {
                        // ignore - heartbeat/next event will retry
                    }
                }
            }
            // mouse delta is one-shot; don't let the heartbeat replay it
            lastSent = state.copy(mouseDx = 0, mouseDy = 0)
        }
    }

    /**
     * Sends dynamic key mapping configuration to the PC server.
     * Uses length-prefixed framing over TCP (USB) or 3x burst over UDP (WiFi) for reliability.
     */
    fun sendKeymapSync(keymap: Map<String, String>) {
        this.activeKeymap = keymap
        val jsonObj = JSONObject()
        keymap.forEach { (k, v) -> jsonObj.put(k, v) }
        val jsonBytes = jsonObj.toString().toByteArray(Charsets.UTF_8)
        val packet = ByteArray(1 + jsonBytes.size)
        packet[0] = PACKET_MAGIC_CONFIG
        System.arraycopy(jsonBytes, 0, packet, 1, jsonBytes.size)

        sendExecutor.execute {
            try {
                if (mode == TransportMode.USB) {
                    for (attempt in 0 until 15) {
                        val hasOut = synchronized(socketLock) { tcpOut != null }
                        if (hasOut) {
                            sendRaw(packet)
                            break
                        }
                        Thread.sleep(100)
                    }
                } else {
                    for (i in 0 until 3) {
                        sendRaw(packet)
                        Thread.sleep(40)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun sendScrollWheel(delta: Int) {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PACKET_MAGIC_WHEEL)
        buf.putShort(delta.toShort())
        sendExecutor.execute {
            try {
                sendRaw(buf.array())
            } catch (_: Exception) {}
        }
    }

    fun sendMacroKey(key: String, pressed: Boolean) {
        val keyBytes = key.lowercase().toByteArray(Charsets.UTF_8)
        val packet = ByteArray(2 + keyBytes.size)
        packet[0] = PACKET_MAGIC_MACRO_KEY
        packet[1] = if (pressed) 1.toByte() else 0.toByte()
        System.arraycopy(keyBytes, 0, packet, 2, keyBytes.size)
        sendExecutor.execute {
            try {
                sendRaw(packet)
            } catch (_: Exception) {}
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        when (mode) {
            TransportMode.WIFI -> {
                if (wifiHost.isEmpty()) return
                var addr = targetAddress
                if (addr == null) {
                    addr = InetAddress.getByName(wifiHost)
                    targetAddress = addr
                }
                udpSocket.send(DatagramPacket(bytes, bytes.size, addr, port))
            }
            TransportMode.USB -> {
                val out = synchronized(socketLock) { tcpOut } ?: return
                synchronized(socketLock) {
                    try {
                        // Frame with 2-byte little-endian length prefix for TCP
                        val framed = ByteBuffer.allocate(2 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                        framed.putShort(bytes.size.toShort())
                        framed.put(bytes)
                        out.write(framed.array())
                        out.flush()
                    } catch (e: Exception) {
                        android.util.Log.e("NetworkClient", "USB sendRaw failed, triggering reconnect: ${e.message}")
                        triggerUsbReconnect()
                    }
                }
            }
        }
    }
}
