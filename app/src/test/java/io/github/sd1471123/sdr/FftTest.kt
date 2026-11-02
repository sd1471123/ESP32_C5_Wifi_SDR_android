package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FftTest {

    private fun magnitudeAt(re: FloatArray, im: FloatArray, bin: Int): Double =
        Math.hypot(re[bin].toDouble(), im[bin].toDouble())

    @Test
    fun `sine maps to its bin`() {
        val n = 512
        val k = 40                      // exactly k cycles over the window
        val re = FloatArray(n) { cos(2.0 * PI * k * it / n).toFloat() }
        val im = FloatArray(n) { sin(2.0 * PI * k * it / n).toFloat() }
        Fft.transform(re, im)

        val peakBin = (0 until n).maxByOrNull { magnitudeAt(re, im, it) }!!
        assertEquals(k, peakBin)

        // rectangular window: the tone is spread over +/- 1 bin only, everything else is tiny
        val peak = magnitudeAt(re, im, k)
        for (b in intArrayOf(k - 2, k + 2, k + 100)) {
            assertTrue("bin $b should be far below the peak", magnitudeAt(re, im, b) < peak / 50)
        }
    }

    @Test
    fun `dc input stays in bin 0`() {
        val n = 256
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        Fft.transform(re, im)
        assertEquals(n.toDouble(), magnitudeAt(re, im, 0), n * 1e-3)
        assertTrue(magnitudeAt(re, im, 1) < 1e-2)
    }

    @Test
    fun `linearity - sum of two tones is the sum of the spectra`() {
        val n = 256
        fun tone(k: Int, amp: Float): Pair<FloatArray, FloatArray> {
            val re = FloatArray(n) { amp * cos(2.0 * PI * k * it / n).toFloat() }
            val im = FloatArray(n) { amp * sin(2.0 * PI * k * it / n).toFloat() }
            return re to im
        }
        val (a1, a2) = tone(16, 0.5f)
        val (b1, b2) = tone(64, 0.25f)
        val sum1 = FloatArray(n) { a1[it] + b1[it] }
        val sum2 = FloatArray(n) { a2[it] + b2[it] }
        Fft.transform(sum1, sum2)
        Fft.transform(a1, a2)
        Fft.transform(b1, b2)
        for (i in 0 until n) {
            assertEquals(a1[i] + b1[i], sum1[i], 1e-3f)
            assertEquals(a2[i] + b2[i], sum2[i], 1e-3f)
        }
    }
}
