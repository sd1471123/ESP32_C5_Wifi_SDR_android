package io.github.sd1471123.sdr

import android.content.Context
import java.io.IOException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Capture thread. Live mode captures at one centre frequency; sweep mode walks the local
 * oscillators across a band and stitches only the clean ±10-20 MHz parts of each capture,
 * exactly like `sdr_core.sweep_step()` — that avoids both the passband roll-off and the 5 GHz
 * LO leakage skirt around the centre.
 */
class SdrEngine(
    private val dev: Espsdr,
    private val listener: Listener,
    /** Activity context, so status texts follow the language picked in the UI. */
    private val context: Context,
) : Thread("sdr-engine"), SdrSource {

    interface Listener {
        fun onFrame(frame: SdrFrame)
        fun onError(t: Throwable)

        /** Non fatal notes ("recording stopped: …"); ignored by default. */
        fun onNotice(text: String) {}
    }

    @Volatile override var mode: Mode = Mode.LIVE
    @Volatile override var band: String = Bands.GHZ24
    @Volatile override var centerMhz: Int = 2437

    /** Manual PHY gain 0..79; a negative value selects the hardware AGC. */
    @Volatile override var gain: Int = 40
    @Volatile override var rateIdx: Int = 0
    @Volatile override var nfft: Int = 512
    @Volatile override var bandwidthMhz: Int = 0
    @Volatile override var loOffsetMhz: Int = 0
    @Volatile var nsamp: Int = 4096

    /** Set to a recorder to stream every capture to disk; null stops recording. */
    @Volatile override var recorder: IqRecorder? = null

    @Volatile private var running = true

    private var appliedFreq = Int.MIN_VALUE
    private var appliedGain = Int.MIN_VALUE
    private var appliedBw = Int.MIN_VALUE

    private var fps = 0f

    override fun startEngine() {
        start()
    }

    override fun stopEngine() {
        running = false
    }

    override fun run() {
        var sweep: Sweep? = null
        var lastMode = mode
        var lastBand = band
        var frames = 0
        var t0 = System.currentTimeMillis()
        try {
            while (running) {
                if (mode != lastMode || band != lastBand) {
                    sweep = null
                    lastMode = mode
                    lastBand = band
                    appliedFreq = Int.MIN_VALUE
                }
                val frame = if (lastMode == Mode.SWEEP) {
                    val state = sweep ?: newSweep().also { sweep = it }
                    sweepStep(state)
                } else {
                    liveStep()
                }
                frames++
                val now = System.currentTimeMillis()
                val dt = now - t0
                if (dt >= 1000) {
                    fps = frames * 1000f / dt
                    frames = 0
                    t0 = now
                }
                listener.onFrame(frame)
            }
        } catch (t: Throwable) {
            if (running) listener.onError(t)
        }
    }

    // ---- live -------------------------------------------------------------
    private fun liveStep(): SdrFrame {
        val lo = centerMhz + loOffsetMhz
        if (lo != appliedFreq) {
            dev.setFreq(lo)
            appliedFreq = lo
        }
        applyGainAndBandwidth()

        val iq = dev.capture(nsamp, rateIdx)
        pump(iq)
        val s = Dsp.spectra(iq, nfft, true)
        val fsMhz = RATES[rateIdx] / 1e6f
        val fLo = lo - fsMhz / 2f
        val fHi = lo + fsMhz / 2f
        val info = context.getString(R.string.engine_info, fps, s.rmsDb, nsamp, fsMhz)
        return SdrFrame(
            s.avgDb, s.peakDb, s.tdDb, fLo, fHi, band, info, null, null, s.clipPct
        )
    }

    private fun applyGainAndBandwidth() {
        if (gain != appliedGain) {
            dev.setGain(if (gain < 0) null else gain)
            appliedGain = gain
        }
        if (bandwidthMhz != appliedBw) {
            dev.setBandwidth(bandwidthMhz)
            appliedBw = bandwidthMhz
        }
    }

    private fun pump(iq: FloatArray) {
        val r = recorder ?: return
        try {
            r.write(iq)
        } catch (e: IOException) {
            recorder = null
            r.close()
            listener.onNotice(context.getString(R.string.status_rec_stopped, e.message.orEmpty()))
        }
    }

    // ---- sweep ------------------------------------------------------------
    private class Sweep(
        val los: IntArray,
        val fRel: FloatArray,
        val sel: BooleanArray,
        val f: FloatArray,
        val df: Float,
        val chans: IntArray,
    ) {
        var i = 0
        val sum = DoubleArray(f.size)
        val cnt = DoubleArray(f.size)
        val max = FloatArray(f.size) { Float.NaN }

        /** Peak hold across sweeps (slow decay): keeps the orange trace complete while sweeping. */
        val holdAll = FloatArray(f.size) { Float.NaN }

        /** Mean spectrum of the last finished sweep: fills the bins not scanned yet. */
        var lastMean: FloatArray? = null

        /** Finished sweeps since the band changed — denominator of the detection rate. */
        var sweeps = 0

        /** Sweeps in which a channel was above the noise floor by [DETECT_DB]. */
        val hits = IntArray(chans.size)

        /** Clipping of the most recent step. */
        var clipPct = 0f

        var t0 = System.currentTimeMillis()
    }

    private fun newSweep(): Sweep {
        val r = Bands.range(band)
        val fs = RATES[0]                              // sweeps always run at 80 MS/s
        val df = fs.toDouble() / nfft / 1e6
        val dfF = df.toFloat()

        val los = ArrayList<Int>()
        val startPad = SWEEP_WIN_MHZ[0].toInt()        // 10 MHz
        var lo = r[0] - startPad
        while (lo <= r[1] + startPad) {
            los.add(lo)
            lo += SWEEP_STEP_MHZ
        }

        val half = nfft / 2
        val fRel = FloatArray(nfft) { (it - half) * dfF }
        val sel = BooleanArray(nfft) {
            val a = abs(fRel[it])
            a >= SWEEP_WIN_MHZ[0] && a <= SWEEP_WIN_MHZ[1]
        }

        val n = ((r[1] - r[0]) / dfF).toInt() + 1
        val f = FloatArray(n) { r[0] + it * dfF }
        return Sweep(los.toIntArray(), fRel, sel, f, dfF, Bands.channels20(band))
    }

    private fun sweepStep(s: Sweep): SdrFrame {
        applyGainAndBandwidth()
        val lo = s.los[s.i]
        dev.setFreq(lo)
        appliedFreq = lo

        val iq = dev.capture(nsamp, 0)
        pump(iq)
        val sp = Dsp.spectra(iq, nfft, true)
        s.clipPct = sp.clipPct
        for (j in 0 until nfft) {
            if (!s.sel[j]) continue
            val idx = Math.round((lo + s.fRel[j] - s.f[0]) / s.df).toInt()
            if (idx < 0 || idx >= s.f.size) continue
            s.sum[idx] += 10.0.pow(sp.avgDb[j] / 10.0)
            s.cnt[idx] += 1.0
            if (sp.peakDb[j].isNaN()) continue
            if (s.max[idx].isNaN() || sp.peakDb[j] > s.max[idx]) s.max[idx] = sp.peakDb[j]
            val ph = s.holdAll[idx]
            if (ph.isNaN() || sp.peakDb[j] > ph) s.holdAll[idx] = sp.peakDb[j]
        }
        s.i++
        val done = s.i >= s.los.size

        val mean = FloatArray(s.f.size)
        for (j in s.f.indices) {
            mean[j] = if (s.cnt[j] > 0) (10.0 * log10(s.sum[j] / s.cnt[j])).toFloat() else Float.NaN
        }
        val sweepMax = s.max.copyOf()
        val hold = s.holdAll.copyOf()
        val floor = noiseFloor(mean)
        val bars = if (done) channelActivity(s.f, sweepMax, floor, s.chans) else null
        val rates = if (done && bars != null) detectionRates(s, bars) else null
        val sec = (System.currentTimeMillis() - s.t0) / 1000f
        val info = if (done) {
            context.getString(R.string.sweep_done, sec, s.los.size, floor)
        } else {
            context.getString(R.string.sweep_progress, s.i, s.los.size, sec)
        }
        // Show the previous sweep in the bins that have not been re-scanned yet, otherwise the
        // whole plot blinks empty at the start of every sweep.
        val shown = mean.copyOf()
        val prev = s.lastMean
        if (prev != null) {
            for (j in shown.indices) {
                if (shown[j].isNaN() && j < prev.size && !prev[j].isNaN()) shown[j] = prev[j]
            }
        }
        val frame = SdrFrame(
            shown, hold, null, s.f[0], s.f[s.f.size - 1], band, info, bars, rates, s.clipPct
        )

        if (done) {
            s.lastMean = mean.copyOf()
            s.i = 0
            s.sum.fill(0.0)
            s.cnt.fill(0.0)
            s.max.fill(Float.NaN)
            for (j in s.holdAll.indices) {
                val v = s.holdAll[j]
                if (!v.isNaN()) s.holdAll[j] = v - PEAK_DECAY_DB
            }
            s.t0 = System.currentTimeMillis()
        }
        return frame
    }

    /** 20th percentile of the sweep — a cheap, robust noise floor estimate. */
    private fun noiseFloor(mean: FloatArray): Float {
        val valid = mean.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return 0f
        return valid[(valid.size * 0.2).toInt().coerceIn(0, valid.size - 1)]
    }

    /** Peak of each 20 MHz channel relative to the noise floor (dB). */
    private fun channelActivity(f: FloatArray, hold: FloatArray, floor: Float, chans: IntArray): FloatArray {
        val out = FloatArray(chans.size)
        for (c in chans.indices) {
            var best = Float.NaN
            for (j in f.indices) {
                if (abs(f[j] - chans[c]) > 8f) continue
                val v = hold[j]
                if (v.isNaN()) continue
                if (best.isNaN() || v > best) best = v
            }
            out[c] = if (best.isNaN()) 0f else max(0f, best - floor)
        }
        return out
    }

    /**
     * Share of finished sweeps in which the channel was [DETECT_DB] above the noise floor,
     * i.e. the desktop app's "detection rate". Averaged over the whole session for the band.
     */
    private fun detectionRates(s: Sweep, bars: FloatArray): FloatArray {
        s.sweeps++
        for (c in bars.indices) if (bars[c] >= DETECT_DB) s.hits[c]++
        return FloatArray(bars.size) { 100f * s.hits[it] / s.sweeps }
    }

    companion object {
        val RATES = intArrayOf(80_000_000, 40_000_000, 20_000_000, 10_000_000, 8_000_000, 4_000_000)
        const val SWEEP_STEP_MHZ = 20

        /** A channel counts as "detected" from this much above the noise floor. */
        const val DETECT_DB = 6f

        /** Peak hold falls by this much after every finished sweep (~1 dB/s). */
        private const val PEAK_DECAY_DB = 2f

        private val SWEEP_WIN_MHZ = floatArrayOf(10f, 20f)
    }
}
