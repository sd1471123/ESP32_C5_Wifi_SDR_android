package io.github.sd1471123.sdr

/**
 * Converts CAP16 / CAP20 payloads into interleaved float I/Q scaled to full scale 1.0.
 *
 * Same wire formats as the official web client: 8-bit samples are signed bytes (full scale
 * 128), 10-bit samples are packed LSB first with I in bits 0-9 and Q in bits 10-19 of every
 * 20-bit word (full scale 512, so a 10-bit signed sample runs -512..511).
 *
 * Unlike the web client the Q component is NOT negated: this app keeps the desktop
 * reference convention so both bit depths show the same spectrum orientation.
 */
object IqCodec {

    fun unpack8(payload: ByteArray, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        for (j in 0 until n) {
            out[2 * j] = payload[2 * j] / 128f
            out[2 * j + 1] = payload[2 * j + 1] / 128f
        }
        return out
    }

    fun unpack10(payload: ByteArray, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        var acc = 0L
        var held = 0
        var k = 0
        for (j in 0 until n) {
            while (held < 20) {
                acc = acc or ((payload[k++].toLong() and 0xFF) shl held)
                held += 8
            }
            val word = (acc and 0xFFFFF).toInt()
            acc = acc shr 20
            held -= 20
            out[2 * j] = signExtend10(word and 0x3FF) / 512f
            out[2 * j + 1] = signExtend10((word shr 10) and 0x3FF) / 512f
        }
        return out
    }

    private fun signExtend10(v: Int): Int = (v shl 22) shr 22
}
