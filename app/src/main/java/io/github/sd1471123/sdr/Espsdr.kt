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
 * One on-chip spectrum frame (SPC1): power codes of [bins.size] bins, 20log10(power)
 * with 1 dB per code, averaged (or max-held) over the FFTs the chip ran for it.
 */
class SpecFrame(
    val bins: ByteArray,
    val ffts: Int,
    val pairs: Long,
    val indexUs: Long,
    val gain: Int,
    /** Sample rate the stream was started with, from the ack line (Hz). */
    val rateHz: Int,
)

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

    @Volatile private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var ctrlId: Int = 0
    private val rx = ByteArray(1 shl 16)
    private var rxPos = 0
    private var rxLen = 0
    private val lineBuf = ByteArrayOutputStream(256)
    private var nonce = (System.currentTimeMillis() and 0xFFFFFF).toInt()

    var info: String = ""
        private set

    /** Raw CAPS reply — capability flags are matched with contains(). */
    var caps: String = ""
        private set

    /** "USB" behind the native Serial/JTAG, "UART" behind a bridge, null when unknown. */
    var transport: String? = null
        private set

    /** The UART link's rate when [transport] is "UART" (0 otherwise). */
    var uartBaud: Int = 0
        private set

    /** Line coding the link is currently opened with; 0 when the transport ignores it. */
    var lineBaud: Int = 0
        private set

    var maxSamples: Int = 16380
        private set

    /** Firmware build date from VERSION? (empty when the firmware lacks the VERSION capability). */
    var buildDate: String = ""
        private set

    val isOpen: Boolean get() = conn != null

    fun open() {
        usbOpen()
        rxPos = 0
        rxLen = 0
        lineBuf.reset()
        // A UART bridge (CH343 and friends) really runs at the line coding, so probe the two
        // rates the firmware accepts; the native USB Serial/JTAG ignores it and always answers.
        if (device.vendorId == 0x303a) {
            lineBaud = 0
            waitForBoard()
        } else {
            var ok = false
            for (rate in intArrayOf(2000000, 1000000)) {
                setLineCoding(rate)
                try {
                    resync()
                    lineBaud = rate
                    ok = true
                    break
                } catch (_: SdrError) {
                }
            }
            if (!ok) {
                close()
                throw SdrError("${describe()}: no reply at 2 or 1 MBaud")
            }
        }
        info = cmd("INFO")
        if (!info.contains("SDR")) {
            close()
            throw SdrError("no ESP-SDR firmware on this device: $info")
        }
        info.trim().split(Regex("\\s+")).getOrNull(3)?.toIntOrNull()?.let { maxSamples = it }
        caps = cmd("CAPS")
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
        negotiateTransport()
    }

    /**
     * Native USB Serial/JTAG handshake. A board that a previous session left with an unread
     * stream goes silent: the firmware blocks inside its USB write and never returns to the
     * command loop, so INFO times out until the chip reboots. Retrying the SYNC marker with a
     * USB driven chip reset in between is exactly what replugging the cable does.
     */
    private fun waitForBoard() {
        for (attempt in 0 until 3) {
            if (attempt > 0) chipReset()
            if (pingSync()) return
        }
        close()
        throw SdrError("no SYNC reply — the board did not come back after a USB reset")
    }

    /** One short SYNC round trip; true when the marker came back unchanged. */
    private fun pingSync(waitMs: Int = 700): Boolean {
        drainInput()
        nonce += 1
        val want = "SYNC $nonce"
        try {
            write("$want\n")
        } catch (_: SdrError) {
            return false
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val reply = try {
                readLine(200)
            } catch (_: SdrError) {
                continue
            }
            if (reply == want) return true
        }
        return false
    }

    /**
     * Resets the chip through the USB-JTAG peripheral: DTR falling while RTS is high reboots it
     * and the USB connection itself stays up. DTR is asserted again afterwards so the CDC keeps
     * sending; RTS goes back low so a later close cannot drop the board into the bootloader.
     */
    fun chipReset() {
        val c = conn ?: return
        try {
            c.controlTransfer(0x21, 0x22, 0x0002, ctrlId, null, 0, 500)   // RTS high, DTR low
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(1500)                                            // firmware boots
        } catch (_: InterruptedException) {
        }
        try {
            c.controlTransfer(0x21, 0x22, 0x0001, ctrlId, null, 0, 500)   // DTR high, RTS low
        } catch (_: Exception) {
        }
        rxPos = 0
        rxLen = 0
        lineBuf.reset()
    }

    /** True when the link still answers; a stuck port fails here instead of at the next capture. */
    fun ping(): Boolean = isOpen && pingSync(400)

    /** Opens the CDC data interface and asserts DTR (RTS must stay low or USB-JTAG resets). */
    private fun usbOpen() {
        val c = usb.openDevice(device) ?: throw SdrError("openDevice() failed")
        epIn = null
        epOut = null

        // A CDC-ACM device has two interfaces: the control interface (class 0x02) carries only an
        // interrupt endpoint, the data interface (class 0x0A) carries the bulk pair. The native USB
        // Serial/JTAG of the C5 is exactly like that, so never assume interface 0 has the data
        // endpoints — look for the bulk pair wherever it is.
        ctrlId = 0
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
        setLineCoding(115200)
        // SET_CONTROL_LINE_STATE: DTR = 1 (bit 0), RTS = 0
        c.controlTransfer(0x21, 0x22, 0x0001, ctrlId, null, 0, 500)
    }

    /** CDC SET_LINE_CODING, little-endian dword rate + 8N1. */
    private fun setLineCoding(baud: Int) {
        conn?.controlTransfer(
            0x21, 0x20, 0, ctrlId,
            byteArrayOf(
                baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
                0, 0, 8
            ),
            7, 500
        )
    }

    /** Closes the USB handle without RELEASE — used mid-session by the baud switch. */
    private fun usbClose() {
        conn?.let { c ->
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
        conn = null
    }

    /** Firmware with UARTBAUD reports which interface we are on and the UART's rate. */
    private fun negotiateTransport() {
        transport = null
        uartBaud = 0
        if (!caps.contains("UARTBAUD")) return
        tryCatch {
            val m = Regex("TRANSPORT (UART|USB) (\\d+)").find(cmd("TRANSPORT?")) ?: return
            transport = m.groupValues[1]
            uartBaud = m.groupValues[2].toInt()
        }
    }

    /**
     * Asks a UART-bridged device to move to 2 MBaud — the wire rate actually matters there,
     * unlike the native USB transport. The ack can get lost in the rate switch, so the port is
     * reopened and the transport re-negotiated; on any failure the previous rate wins back.
     */
    fun upgradeBaud(): Boolean {
        if (transport != "UART" || uartBaud >= 2000000) return false
        val oldBaud = uartBaud
        tryCatch { write("BAUD 2000000\n") }
        tryCatch { readLine(800) }                       // the ack, when it survives
        for (rate in intArrayOf(2000000, oldBaud)) {
            usbClose()
            try {
                usbOpen()
                setLineCoding(rate)
                resync()
                negotiateTransport()
                if (transport == "UART" && uartBaud == rate) {
                    lineBaud = rate
                    return rate == 2000000
                }
            } catch (_: SdrError) {
            }
        }
        throw SdrError("device unreachable after baud change")
    }

    /** Best-effort optional command: a missing capability must never break the connection. */
    private inline fun tryCatch(block: () -> Unit) {
        try {
            block()
        } catch (_: SdrError) {
        }
    }

    /**
     * Hands the radio back in the order the firmware expects: stop a running stream, RELEASE
     * the lease, give the reply a moment to leave, then drop DTR before closing the handle.
     * An asserted DTR is what leaves the next session with a half-open CDC (and an unread
     * stream the firmware keeps blocking on), i.e. the "reconnect needs a replug" state.
     */
    fun close() {
        val c = conn ?: return
        if (specActive) tryCatch { stopSpec() }
        try {
            write("RELEASE\n")
        } catch (_: Exception) {
        }
        conn = null
        try {
            Thread.sleep(30)
        } catch (_: InterruptedException) {
        }
        try {
            c.controlTransfer(0x21, 0x22, 0x0000, ctrlId, null, 0, 500)   // DTR low, RTS low
        } catch (_: Exception) {
        }
        try {
            c.close()
        } catch (_: Exception) {
        }
        rxPos = 0
        rxLen = 0
        lineBuf.reset()
    }

    // ---- receiver controls ------------------------------------------------
    fun setFreq(mhz: Int) = cmd("FREQ ${mhz.coerceIn(100, 6000)}")

    fun setBandwidth(mhz: Int) = cmd("BANDWIDTH ${mhz.coerceIn(0, 48)}")

    /** null selects the hardware AGC, otherwise the manual PHY gain index (0..79, see LIMITS?). */
    fun setGain(manual: Int?) = cmd(if (manual == null) "GAIN HARDWARE" else "GAIN MANUAL ${manual.coerceIn(0, 79)}")

    /** Number of controllable GPIO pins, or null when unsupported. */
    fun gpioPins(): Int? = gpioStates()?.size

    /**
     * Pin → drive state from `GPIO?`, which answers `GPIO 0:Z 3:1 ...` and lists only the pins it
     * is willing to drive. The panel therefore works from this map: assuming 0..n-1 would show
     * pins the board does not expose, and the reply carries no count to read a range from.
     */
    fun gpioStates(): Map<Int, Char>? {
        val r = cmd("GPIO?")
        if (!r.startsWith("GPIO")) return null
        return parseGpioStates(r)
    }

    /** Drive one pin: 'Z' high impedance, '0' low, '1' high. */
    fun setGpio(pin: Int, state: Char) = cmd("GPIO $pin $state")

    // ---- side commands (status LED, buzzer) --------------------------------
    // The firmware answers one command at a time, and the capture loop is already talking to
    // it. These are queued and sent from the capture thread between two captures, so the two
    // never interleave on the wire.

    private val pendingLock = Any()

    /**
     * One slot per command word. A newer `LED` replaces the queued one, but a status update never
     * evicts a pending `BEEP` (or the other way round): both are produced by the same frame, and
     * a two-entry FIFO would silently drop one of them on every frame.
     */
    private val pending = LinkedHashMap<String, Queued>(4)

    /** A side command waiting for the wire, plus the hook that reports a refused write. */
    private class Queued(val text: String, val onFail: ((Throwable) -> Unit)?)

    /** True when the firmware exposes the addressable status LED. */
    val hasLed: Boolean get() = caps.contains("LED")

    /** True when the firmware can drive a buzzer pin. */
    val hasBeep: Boolean get() = caps.contains("BEEP")

    fun setLed(r: Int, g: Int, b: Int) =
        cmd("LED ${r.coerceIn(0, 255)} ${g.coerceIn(0, 255)} ${b.coerceIn(0, 255)}")

    /** Duty percent is the loudness; 0 silences the buzzer. */
    fun setBeep(pin: Int, freqHz: Int, duty: Int) =
        if (duty <= 0) cmd("BEEP 0")
        else cmd("BEEP $pin ${freqHz.coerceIn(100, 20000)} ${duty.coerceIn(1, 100)}")

    /**
     * Queues a side command for the next gap between captures. [onFail] runs on the capture
     * thread when the firmware refuses the command, so the caller can undo what it showed.
     */
    fun queue(text: String, onFail: ((Throwable) -> Unit)? = null) {
        synchronized(pendingLock) {
            pending[slotOf(text)] = Queued(text, onFail)    // newest of its kind wins, keep the wire free
        }
    }

    /** True when a side command is still waiting for the wire (the caller may stop a stream). */
    val hasPending: Boolean get() = synchronized(pendingLock) { pending.isNotEmpty() }

    /** Sends queued side commands. Capture thread only, and never while SPEC streams. */
    fun flushQueued() {
        while (true) {
            val next = synchronized(pendingLock) {
                val entries = pending.entries.iterator()
                if (!entries.hasNext()) return@synchronized null
                val queued = entries.next().value
                entries.remove()
                queued
            } ?: return
            // A refused write (a sounding buzzer owns its pin) is swallowed here on purpose:
            // only the hook knows what the UI promised, so reporting is its job.
            try {
                cmd(next.text)
            } catch (e: SdrError) {
                next.onFail?.invoke(e)
            }
        }
    }

    // ---- on-chip spectrum (SPEC) ------------------------------------------
    // `SPEC 0 1 <units> <det> <rate> <n>` makes the chip run the FFT itself and stream
    // frames of 20log10(power) codes. The stream runs until the host sends an empty line,
    // so no other command can be issued while it is up — stopSpec() first.

    /** True when CAPS advertises the on-chip spectrum streamer. */
    val hasSpec: Boolean get() = caps.contains("SPEC")

    private val specBuf = ByteArray(1 shl 16)
    private var specLen = 0
    private var specLog2n = 0
    private var specActive = false
    private var specRateHz = 0
    private var specNewest: SpecFrame? = null

    /**
     * Starts an infinite spectrum stream. [nfft] is rounded down to a power of two in the
     * firmware's 256..2048 range; [det] selects max-hold instead of the FFT average.
     * The ack line `SPEC <n> <rateHz> <n> <freqMHz>` fixes the actual sample rate.
     */
    fun startSpec(rateIdx: Int, nfft: Int, det: Boolean = false) {
        val n = Integer.highestOneBit(nfft).coerceIn(256, 2048)
        write("SPEC 0 1 1 ${if (det) 1 else 0} ${rateIdx.coerceIn(0, 5)} $n\n")
        val ack = readLine(3000)
        if (!ack.startsWith("SPEC ")) throw SdrError("spec start: $ack")
        specRateHz = ack.trim().split(Regex("\\s+")).getOrNull(2)?.toIntOrNull() ?: 0
        specLog2n = Integer.numberOfTrailingZeros(n)
        specLen = 0
        specNewest = null
        specActive = true
        drainInput()
    }

    /** True once the stream has been started and not stopped again. */
    val specRunning: Boolean get() = specActive

    /**
     * Lossy pump: reads whatever the wire currently holds and returns the newest complete
     * frame, or null when no new frame arrived. Stale bytes are skipped via the SPC1 tag,
     * CRC failures cost one byte each, exactly like the web client's resync decoder.
     */
    fun specPump(): SpecFrame? {
        val c = conn ?: throw SdrError("port is closed")
        val ep = epIn ?: throw SdrError("no IN endpoint")
        for (poll in 0 until 2) {
            val n = c.bulkTransfer(ep, rx, rx.size, 8)
            if (n > 0) feedSpec(rx, n) else break
        }
        val f = specNewest
        specNewest = null
        return f
    }

    /**
     * Stops the stream (empty line) and waits for the SPECEND report; returns its status.
     * The report is glued to the tail of the last frame (no newline after the CRC), so
     * it has to be matched inside the line, not at its start.
     */
    fun stopSpec(): Int {
        if (!specActive) return 0
        specActive = false
        write("\n")
        var status = -1
        val deadline = System.currentTimeMillis() + 3000
        try {
            while (System.currentTimeMillis() < deadline) {
                val line = readLine(500)
                val m = Regex("SPECEND (\\d+)").find(line)
                if (m != null) {
                    status = m.groupValues[1].toInt()
                    break
                }
            }
        } catch (_: SdrError) {
        }
        specLen = 0
        specNewest = null
        drainInput()
        return status
    }

    /** Appends wire bytes to the frame accumulator and decodes every complete frame. */
    private fun feedSpec(data: ByteArray, count: Int) {
        if (specLen + count > specBuf.size) specLen = 0   // lossy: drop the backlog
        System.arraycopy(data, 0, specBuf, specLen, count)
        specLen += count
        val n = 1 shl specLog2n
        val frameLen = 32 + n
        var p = 0
        while (p + 4 <= specLen) {
            val b = specBuf
            if (b[p] == 'S'.code.toByte() && b[p + 1] == 'P'.code.toByte() &&
                b[p + 2] == 'C'.code.toByte() && b[p + 3] == '1'.code.toByte()
            ) {
                if (p + frameLen > specLen) break
                if (b[p + 26].toInt() == specLog2n && b[p + 27].toInt() == 2 &&
                    crcAt(p, frameLen)
                ) {
                    val bins = ByteArray(n)
                    System.arraycopy(b, p + 28, bins, 0, n)
                    specNewest = SpecFrame(
                        bins,
                        u16(p + 20),
                        u32(p + 16),
                        u64(p + 8),
                        b[p + 23].toInt() and 0xFF,
                        specRateHz,
                    )
                    p += frameLen
                } else {
                    p++                      // damaged: resync one byte, like the web client
                }
            } else if (b[p] == 'S'.code.toByte() && b[p + 1] == 'P'.code.toByte() &&
                b[p + 2] == 'E'.code.toByte() && b[p + 3] == 'C'.code.toByte()
            ) {
                // "SPECEND <numbers>\n": the firmware ended the stream on its own
                var nl = p
                while (nl < specLen && specBuf[nl] != '\n'.code.toByte()) nl++
                if (nl >= specLen) break
                specActive = false
                p = nl + 1
            } else {
                p++
            }
        }
        if (p > 0) {
            System.arraycopy(specBuf, p, specBuf, 0, specLen - p)
            specLen -= p
        }
    }

    /** zlib CRC32 of specBuf[p .. p+len-4] must equal the little-endian word at p+len-4. */
    private fun crcAt(p: Int, len: Int): Boolean {
        val want = u32(p + len - 4)
        val crc = CRC32()
        crc.update(specBuf, p, len - 4)
        return crc.value == want
    }

    private fun u16(at: Int): Int =
        (specBuf[at].toInt() and 0xFF) or ((specBuf[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(at: Int): Long =
        (u16(at).toLong() and 0xFFFF) or ((u16(at + 2).toLong() and 0xFFFF) shl 16)

    private fun u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

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

    companion object {
        /**
         * Parses a `GPIO?` reply: `GPIO 0:Z 3:1`. The reply carries no count and lists only the
         * pins the firmware is willing to drive, so the panel must not assume 0..n-1.
         */
        fun parseGpioStates(reply: String): Map<Int, Char> {
            val out = LinkedHashMap<Int, Char>()
            for (m in Regex("(\\d+):([Z01])").findAll(reply)) out[m.groupValues[1].toInt()] = m.groupValues[2][0]
            return out
        }

        /**
         * Slot key of a side command: the verb, except for `GPIO`, which is keyed per pin. Keying
         * GPIO on the verb alone let a write to one pin replace a pending write to another, so a
         * run of pin changes only ever applied the last one.
         */
        internal fun slotOf(cmd: String): String {
            val verb = cmd.substringBefore(' ')
            if (verb != "GPIO") return verb
            val rest = cmd.substringAfter(' ', "")
            return if (rest.isEmpty()) verb else "$verb ${rest.substringBefore(' ')}"
        }
    }
}
