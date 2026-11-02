package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Test

class IqCodecTest {

    /** Packs 20-bit words LSB first, the mirror of the firmware / web-client packing. */
    private fun pack(vararg words: Int): ByteArray {
        var acc = 0L
        var held = 0
        val out = ArrayList<Byte>()
        for (w in words) {
            acc = acc or ((w.toLong() and 0xFFFFF) shl held)
            held += 20
            while (held >= 8) {
                out.add((acc and 0xFF).toByte())
                acc = acc shr 8
                held -= 8
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `unpack8 scales signed bytes to full scale`() {
        val iq = IqCodec.unpack8(byteArrayOf(127.toByte(), (-128).toByte(), 0.toByte(), 64.toByte()), 2)
        assertEquals(127f / 128f, iq[0], 1e-6f)
        assertEquals(-1f, iq[1], 1e-6f)
        assertEquals(0f, iq[2], 1e-6f)
        assertEquals(0.5f, iq[3], 1e-6f)
    }

    @Test
    fun `unpack10 decodes packed 20-bit words`() {
        // sample1: I=511, Q=-512 ; sample2: I=-512, Q=511
        val payload = pack((512 shl 10) or 511, (511 shl 10) or 512)
        assertEquals(5, payload.size)                    // 2 samples = 40 bits = 5 bytes
        val iq = IqCodec.unpack10(payload, 2)
        assertEquals(511f / 512f, iq[0], 1e-6f)
        assertEquals(-1f, iq[1], 1e-6f)
        assertEquals(-1f, iq[2], 1e-6f)
        assertEquals(511f / 512f, iq[3], 1e-6f)
    }

    @Test
    fun `unpack10 handles long streams without drift`() {
        // 64 samples of a symmetric phasor; every sample must round-trip exactly
        val words = IntArray(64) { i ->
            val q = i % 1023 - 511                        // q and -q stay inside -512..511
            (((-q) and 0x3FF) shl 10) or (q and 0x3FF)    // I in bits 0-9, Q in bits 10-19
        }
        val iq = IqCodec.unpack10(pack(*words), 64)
        for (i in 0 until 64) {
            val q = i % 1023 - 511
            assertEquals(q / 512f, iq[2 * i], 1e-6f)
            assertEquals((-(q)) / 512f, iq[2 * i + 1], 1e-6f)
        }
    }
}
