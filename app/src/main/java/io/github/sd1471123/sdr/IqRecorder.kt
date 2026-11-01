package io.github.sd1471123.sdr

import java.io.IOException
import java.io.OutputStream

/**
 * Writes captures as raw signed 8-bit I/Q (`.cfile`) — the format GNU Radio and inspectrum
 * open directly. The scale matches the wire format: full scale 1.0 is written as ±127.
 */
class IqRecorder(private val out: OutputStream) {

    @Volatile var samples: Long = 0
        private set

    @Volatile var bytes: Long = 0
        private set

    @Volatile var frames: Int = 0
        private set

    @Synchronized
    fun write(iq: FloatArray) {
        val buf = ByteArray(iq.size)
        for (i in iq.indices) {
            val v = (iq[i] * 127f).toInt().coerceIn(-127, 127)
            buf[i] = v.toByte()
        }
        out.write(buf)
        bytes += buf.size
        samples += buf.size / 2
        frames++
    }

    @Synchronized
    fun close() {
        try {
            out.flush()
            out.close()
        } catch (_: IOException) {
        }
    }
}
