package io.github.sd1471123.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class SdrError(message: String) : Exception(message)

/**
 * Client for the ESP-SDR line protocol, i.e. the same wire format `espsdr.py` speaks:
 *
 *   INFO / FREQ <MHz> / GAIN MANUAL <n> / BANDWIDTH <MHz> / CAP16 <n> <rate>
 *   CAP16 replies with `DATA <n> <crc32> <us>` followed by n*2 bytes of signed 8 bit I/Q.
 *
 * Only USB is supported (no Wi-Fi hop: the radio is busy being the receiver). The firmware
 * on the C5 sits behind the native USB Serial/JTAG peripheral and only starts sending once
 * the host asserts DTR, while RTS must stay de-asserted or the board is reset.
 */
class Espsdr(private val usb: UsbManager, val device: UsbDevice) {

    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val rx = ByteArray(1 shl 16)
    private var rxPos = 0
    private var rxLen = 0
    private val lineBuf = ByteArrayOutputStream(256)
    private var nonce = (System.currentTimeMillis() and 0xFFFFFF).toInt()

    var info: String = ""
        private set

    var maxSamples: Int = 16380
        private set

    /** Firmware build date from VERSION? (empty when the firmware lacks the VERSION capability). */
    var buildDate: String = ""
        private set

    val isOpen: Boolean get() = conn != null

    fun open() {
        val c = usb.openDevice(device) ?: throw SdrError("openDevice() failed")
        epIn = null
        epOut = null

        // A CDC-ACM device has two interfaces: the control interface (class 0x02) carries only an
        // interrupt endpoint, the data interface (class 0x0A) carries the bulk pair. The native USB
        // Serial/JTAG of the C5 is exactly like that, so never assume interface 0 has the data
        // endpoints — look for the bulk pair wherever it is.
        var ctrlId = 0
        var claimed = false
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            var hasBulk = false
            for (e in 0 until itf.endpointCount) {
                if (itf.getEndpoint(e).type == UsbConstants.USB_ENDPOINT_XFER_BULK) hasBulk = true
            }
            if (!hasBulk) {
                if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM) ctrlId = itf.id
                continue
            }
            if (!c.claimInterface(itf, true)) continue
            claimed = true
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (epIn == null) epIn = ep
                } else {
                    if (epOut == null) epOut = ep
                }
            }
            if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM) ctrlId = itf.id
        }
        if (!claimed || epIn == null || epOut == null) {
            c.close()
            throw SdrError(describe())
        }
        conn = c

        // CDC-ACM line coding 115200 8N1 (ignored by the CDC/ACM firmware, but harmless)
        c.controlTransfer(
            0x21, 0x20, 0, ctrlId,
            byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08), 7, 500
        )
        // SET_CONTROL_LINE_STATE: DTR = 1 (bit 0), RTS = 0
        c.controlTransfer(0x21, 0x22, 0x0001, ctrlId, null, 0, 500)

        rxPos = 0
        rxLen = 0
        lineBuf.reset()
        resync()
        info = cmd("INFO")
        if (!info.contains("SDR")) {
            close()
            throw SdrError("no ESP-SDR firmware on this device: $info")
        }
        info.trim().split(Regex("\\s+")).getOrNull(3)?.toIntOrNull()?.let { maxSamples = it }
        val caps = cmd("CAPS")
        cmd("LIMITS?")
        // pick the cleanest analogue / digital filtering when the firmware offers it
        if (caps.contains("LPF")) tryCatch { cmd("LPF AUTO") }
        if (caps.contains("ALPF")) tryCatch { cmd("ALPF AUTO") }
        if (caps.contains("VERSION")) {
            tryCatch {
                val v = cmd("VERSION?")
                buildDate = Regex("\"build_date\"\\s*:\\s*\"([^\"]+)\"").find(v)?.groupValues?.get(1).orEmpty()
            }
        }
    }

    /** Best-effort optional command: a missing capability must never break the connection. */
    private inline fun tryCatch(block: () -> Unit) {
        try {
            block()
        } catch (_: SdrError) {
        }
    }

    fun close() {
        val c = conn ?: return
        conn = null
        try {
            write("RELEASE\n")
        } catch (_: Exception) {
        }
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    // ---- receiver controls ------------------------------------------------
    fun setFreq(mhz: Int) = cmd("FREQ ${mhz.coerceIn(100, 6000)}")

    fun setBandwidth(mhz: Int) = cmd("BANDWIDTH ${mhz.coerceIn(0, 48)}")

    /** null selects the hardware AGC, otherwise the manual PHY gain index (0..79, see LIMITS?). */
    fun setGain(manual: Int?) = cmd(if (manual == null) "GAIN HARDWARE" else "GAIN MANUAL ${manual.coerceIn(0, 79)}")

    /** Number of controllable GPIO pins (GPIO? → "OK GPIO <n>"), or null when unsupported. */
    fun gpioPins(): Int? {
        val r = cmd("GPIO?")
        return Regex("GPIO (\\d+)").find(r)?.groupValues?.get(1)?.toInt()
    }

    /** Drive one pin: 'Z' high impedance, '0' low, '1' high. */
    fun setGpio(pin: Int, state: Char) = cmd("GPIO $pin $state")

    /**
     * Capture [n] complex samples as interleaved floats (I, Q, I, Q ...) scaled to full
     * scale 1.0. [rateIdx] 0..5 selects 80/40/20/10/8/4 MS/s. [bits] 8 uses CAP16 with
     * signed bytes, 10 uses CAP20 with 10-bit packed samples (firmware LIMITS bits [8,10]).
     */
    fun capture(n: Int, rateIdx: Int, bits: Int = 8): FloatArray {
        val count = n.coerceIn(256, maxSamples)
        val tenBit = bits >= 10
        write("CAP${if (tenBit) 20 else 16} $count $rateIdx\n")
        val header = readLine(3000)
        if (!header.startsWith("DATA")) throw SdrError("capture: $header")
        val parts = header.trim().split(Regex("\\s+"))
        if (parts.size < 3) throw SdrError("capture: bad header $header")
        val got = parts[1].toInt()
        val crc = parts[2].toLong(16)
        // 10-bit wire format: one complex sample = 20 bits (I + Q) = 2.5 bytes, packed LSB first
        val payload = readExact(if (tenBit) got * 20 / 8 else got * 2)
        if (CRC32().apply { update(payload) }.value != crc) throw SdrError("capture: CRC mismatch")
        return if (tenBit) IqCodec.unpack10(payload, got) else IqCodec.unpack8(payload, got)
    }

    fun cmd(text: String): String {
        write(text + "\n")
        val reply = readLine(2000)
        if (reply.startsWith("ERR")) throw SdrError("$text: $reply")
        return reply
    }

    /** Descriptor summary used in the error message: interfaces, classes and endpoint counts. */
    private fun describe(): String {
        val sb = StringBuilder()
        sb.append("找不到 USB 批量端点（%04X:%04X".format(device.vendorId, device.productId))
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            sb.append("  接口#").append(i)
                .append(" class=0x").append(Integer.toHexString(itf.interfaceClass))
                .append(" 端点=").append(itf.endpointCount)
        }
        if (device.interfaceCount == 0) sb.append("  无接口")
        sb.append("）")
        return sb.toString()
    }

    // ---- low level --------------------------------------------------------
    private fun write(s: String) {
        val c = conn ?: throw SdrError("port is closed")
        val ep = epOut ?: throw SdrError("no OUT endpoint")
        val bytes = s.toByteArray(Charsets.US_ASCII)
        if (c.bulkTransfer(ep, bytes, bytes.size, 1000) < 0) throw SdrError("write failed")
    }

    private fun readLine(timeoutMs: Int): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (rxPos < rxLen) {
                val b = rx[rxPos++].toInt() and 0xFF
                if (b == '\n'.code) {
                    val text = lineBuf.toString("US-ASCII").trim()
                    lineBuf.reset()
                    return text
                }
                if (b != '\r'.code) lineBuf.write(b)
            }
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) throw SdrError("timeout waiting for reply")
            val c = conn ?: throw SdrError("port is closed")
            val ep = epIn ?: throw SdrError("no IN endpoint")
            val n = c.bulkTransfer(ep, rx, rx.size, minOf(left, 500))
            if (n > 0) {
                rxPos = 0
                rxLen = n
            }
        }
    }

    private fun readExact(count: Int): ByteArray {
        val out = ByteArray(count)
        var off = 0
        while (off < count) {
            if (rxPos < rxLen) {
                val take = minOf(count - off, rxLen - rxPos)
                System.arraycopy(rx, rxPos, out, off, take)
                rxPos += take
                off += take
            } else {
                val c = conn ?: throw SdrError("port is closed")
                val ep = epIn ?: throw SdrError("no IN endpoint")
                val n = c.bulkTransfer(ep, rx, rx.size, 2000)
                if (n <= 0) throw SdrError("short payload")
                rxPos = 0
                rxLen = n
            }
        }
        return out
    }

    /** Drop whatever is buffered: a previous client may have died mid transfer. */
    private fun drainInput() {
        val c = conn ?: return
        val ep = epIn ?: return
        while (true) {
            val n = c.bulkTransfer(ep, rx, rx.size, 50)
            if (n <= 0) break
        }
        rxPos = 0
        rxLen = 0
        lineBuf.reset()
    }

    private fun resync() {
        for (attempt in 0 until 8) {
            drainInput()
            nonce += 1
            val want = "SYNC $nonce"
            write(want + "\n")
            val deadline = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < deadline) {
                val reply = try {
                    readLine(200)
                } catch (_: SdrError) {
                    null
                }
                if (reply == want) return
            }
        }
        throw SdrError("no SYNC reply (wrong port or firmware?)")
    }
}
