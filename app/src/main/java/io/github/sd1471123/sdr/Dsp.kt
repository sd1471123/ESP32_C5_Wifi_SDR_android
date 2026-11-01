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
}
