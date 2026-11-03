package io.github.sd1471123.sdr

/**
 * One capture analysed: per-bin average and peak-hold power (dBFS), the RMS level, the
 * block power sequence used by the time-domain strip and the share of clipped samples.
 */
class Spectra(
    val avgDb: FloatArray,
    val peakDb: FloatArray,
    val rmsDb: Float,
    /** Power of each FFT frame inside the capture (dBFS) — the "time domain" view. */
    val tdDb: FloatArray,
    /** Share of I/Q samples sitting at full scale (%), i.e. how much the ADC is clipping. */
    val clipPct: Float,
)

/**
 * Mirrors `sdr_core.spectra()` from the desktop app: split one capture into frames of
 * [nfft] samples, remove the (window weighted) DC per frame, Hann window, FFT, fftshift,
 * then average / peak-hold the power over the frames.
 */
object Dsp {

    /** |sample| at or above this counts as clipped: 127/128 ≈ 0.992 on the wire. */
    private const val CLIP_LEVEL = 0.988f

    fun spectra(x: FloatArray, nfft: Int, dcRemove: Boolean = true): Spectra {
        val n = x.size / 2
        val k = maxOf(1, n / nfft)
        val win = FloatArray(nfft) { i ->
            (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (nfft - 1).coerceAtLeast(1))).toFloat()
        }
        var winSum = 0.0
        for (w in win) winSum += w
        val winNorm = winSum * winSum

        val accMean = DoubleArray(nfft)
        val accMax = DoubleArray(nfft)
        val td = FloatArray(k)
        var energy = 0.0
        var clipped = 0

        for (v in x) {
            if (v >= CLIP_LEVEL || v <= -CLIP_LEVEL) clipped++
        }
        val clipPct = if (x.isEmpty()) 0f else 100f * clipped / x.size

        val re = FloatArray(nfft)
        val im = FloatArray(nfft)
        val half = nfft / 2

        for (f in 0 until k) {
            val base = f * nfft
            var dcRe = 0.0
            var dcIm = 0.0
            if (dcRemove) {
                for (i in 0 until nfft) {
                    val p = (base + i) * 2
                    dcRe += x[p] * win[i]
                    dcIm += x[p + 1] * win[i]
                }
                dcRe /= winSum
                dcIm /= winSum
            }
            var block = 0.0
            for (i in 0 until nfft) {
                val p = (base + i) * 2
                val ri = x[p] - dcRe
                val ii = x[p + 1] - dcIm
                block += ri.toDouble() * ri + ii.toDouble() * ii
                re[i] = (ri * win[i]).toFloat()
                im[i] = (ii * win[i]).toFloat()
            }
            energy += block
            td[f] = (10.0 * Math.log10(block / (nfft * 2.0) + 1e-14)).toFloat()

            Fft.transform(re, im)
            for (i in 0 until nfft) {
                val src = (i + half) % nfft      // fftshift
                val r = re[src].toDouble()
                val m = im[src].toDouble()
                val p = (r * r + m * m) / winNorm
                accMean[i] += p
                if (p > accMax[i]) accMax[i] = p
            }
        }

        val avg = FloatArray(nfft)
        val peak = FloatArray(nfft)
        val frames = k.toDouble()
        for (i in 0 until nfft) {
            avg[i] = (10.0 * Math.log10(accMean[i] / frames + 1e-14)).toFloat()
            peak[i] = (10.0 * Math.log10(accMax[i] + 1e-14)).toFloat()
        }
        val rms = Math.sqrt(energy / (n * 2.0))
        val rmsDb = (20.0 * Math.log10(rms + 1e-9)).toFloat()
        return Spectra(avg, peak, rmsDb, td, clipPct)
    }

    // ---- interference score (status LED) ----------------------------------

    /** A bin this far above the noise floor counts as occupied: the same 12 dB as the strip. */
    const val OCCUPIED_DB = 12f

    /** Share of occupied bins that already reads as "fully red". */
    const val FULL_OCCUPANCY = 0.25f

    /** Level above the floor that counts as a strong interferer; matches the strip's 26 dB red. */
    const val RED_DB = 26f

    private const val HIST_LO = -160f
    private const val HIST_HI = 10f
    private const val HIST_BUCKETS = 512
    private const val HIST_STEP = (HIST_HI - HIST_LO) / HIST_BUCKETS

    /**
     * How busy the band in one averaged spectrum looks, 0..100 — this is what the status LED
     * shows. [db] holds dBFS values; NaN bins (a sweep has not reached them yet) are skipped.
     *
     * The floor is the 20th percentile, the same definition the sweep noise floor uses in
     * [SdrEngine], so the LED and the occupancy strip agree on what "quiet" means. Bins more
     * than [thresholdDb] above the floor count as occupied, and [fullOccupancy] of the bins
     * occupied already saturates that term; the remaining quarter comes from how far the
     * loudest tenth reaches above the floor, so a few strong interferers read higher than a
     * broad low hum. Only relative levels enter the score: moving the gain slider shifts every
     * bin by the same amount and must not repaint the LED.
     */
    fun interferenceScore(
        db: FloatArray,
        thresholdDb: Float = OCCUPIED_DB,
        fullOccupancy: Float = FULL_OCCUPANCY,
        redDb: Float = RED_DB,
    ): Float {
        val counts = IntArray(HIST_BUCKETS)
        var total = 0
        for (v in db) {
            if (v.isNaN()) continue
            counts[bucket(v)]++
            total++
        }
        if (total == 0) return 0f

        val floor = percentile(counts, total, 0.2f)
        val loudest = percentile(counts, total, 0.9f)
        val edge = floor + thresholdDb
        var occupied = 0
        for (i in counts.indices) {
            if (counts[i] != 0 && HIST_LO + (i + 0.5f) * HIST_STEP > edge) occupied += counts[i]
        }

        val share = (occupied.toFloat() / total / fullOccupancy.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        val span = (redDb - thresholdDb).coerceAtLeast(1f)
        val peaks = ((loudest - floor - thresholdDb) / span).coerceIn(0f, 1f)
        return 100f * (0.7f * share + 0.3f * peaks)
    }

    private fun bucket(db: Float): Int =
        ((db - HIST_LO) / HIST_STEP).toInt().coerceIn(0, HIST_BUCKETS - 1)

    /** Percentile of a histogram, without sorting the spectrum: O(buckets) per call. */
    private fun percentile(counts: IntArray, total: Int, p: Float): Float {
        val want = (total * p).toInt().coerceIn(0, total - 1)
        var seen = 0
        for (i in counts.indices) {
            seen += counts[i]
            if (seen > want) return HIST_LO + (i + 0.5f) * HIST_STEP
        }
        return HIST_HI
    }
}
