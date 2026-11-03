package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status LED follows [Dsp.interferenceScore]: quiet noise has to stay near zero, a busy band
 * has to climb, and the score must never fall when more of the band is occupied.
 */
class InterferenceScoreTest {

    /** [width] bins at [busy] dBFS, the rest at [quiet]: a band with a share of occupied spectrum. */
    private fun band(width: Int, busy: Float = -70f, quiet: Float = -100f, bins: Int = 256) =
        FloatArray(bins) { if (it < width) busy else quiet }

    @Test
    fun quietNoiseScoresNearZero() {
        val noise = FloatArray(256) { -95f + (it % 7) * 0.6f }   // ~4 dB of jitter, no peaks
        assertTrue(Dsp.interferenceScore(noise) < 5f)
    }

    @Test
    fun halfOccupiedBandScoresHigh() {
        assertTrue(Dsp.interferenceScore(band(128)) > 60f)
    }

    @Test
    fun narrowPeakSitsInTheMiddle() {
        // 8 % of the bins are 30 dB up: clearly occupied, but far from a saturated band.
        val score = Dsp.interferenceScore(band(20))
        assertTrue("narrow peak scored $score", score in 10f..40f)
    }

    @Test
    fun moreOccupancyNeverScoresLower() {
        var last = -1f
        for (width in intArrayOf(0, 16, 64, 128, 160)) {
            val score = Dsp.interferenceScore(band(width))
            assertTrue("width $width scored $score after $last", score >= last - 0.5f)
            last = score
        }
    }

    @Test
    fun aUniformSpectrumCarriesNoInformation() {
        // Every bin at the same level: no floor to stand on, so nothing counts as occupied.
        assertEquals(0f, Dsp.interferenceScore(FloatArray(256) { -70f }), 0.001f)
    }

    @Test
    fun nanBinsAreIgnored() {
        val db = FloatArray(256) { if (it < 200) Float.NaN else -100f }
        assertEquals(0f, Dsp.interferenceScore(db), 0.001f)
    }
}
