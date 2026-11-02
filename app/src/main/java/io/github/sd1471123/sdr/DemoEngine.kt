package io.github.sd1471123.sdr

import android.content.Context
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthetic capture back-end: emits the same [SdrFrame] stream as [SdrEngine] without any
 * hardware. Every path of the UI stays identical — live spectrum with peak hold and time domain,
 * band sweep with channel activity / detection rates, and I/Q recording to a `.cfile`.
 *
 * The data is fake but the shapes are not: Wi-Fi access points sit on real channel centres with
 * OFDM-width peaks, the noise floor jitters like a real capture, packets fade in and out, and the
 * auto-scaled dB axis therefore behaves exactly as it does on the board.
 */
class DemoEngine(
    private val listener: SdrEngine.Listener,
    private val context: Context,
) : Thread("demo-engine"), SdrSource {

    override var mode: Mode = Mode.LIVE
    override var band: String = Bands.GHZ24
    override var centerMhz: Int = 2437
    override var gain: Int = 40
    override var rateIdx: Int = 0
    override var nfft: Int = 512
    override var bandwidthMhz: Int = 0
    override var loOffsetMhz: Int = 0

    /** The synthetic source always produces full-scale floats, so this is stored but unused. */
    @Volatile override var iqBits: Int = 8

    @Volatile override var recorder: IqRecorder? = null

    @Volatile private var running = true
    private val rnd = Random(20261102L)
    private var fps = 0f
    private val started = System.currentTimeMillis()

    override fun startEngine() {
        start()
    }

    override fun stopEngine() {
        running = false
    }

    // ---- synthetic band occupancy ----------------------------------------
    private class AccessPoint(val mhz: Float, val amp: Float, val width: Float, val phase: Float)

    private val aps: List<AccessPoint> = if (band == Bands.GHZ5) {
        listOf(
            AccessPoint(5180f, 22f, 8.5f, 0.4f), AccessPoint(5220f, 28f, 8.5f, 1.7f),
            AccessPoint(5240f, 31f, 8.5f, 3.1f), AccessPoint(5260f, 14f, 8f, 4.4f),
            AccessPoint(5320f, 26f, 8.5f, 0.9f), AccessPoint(5500f, 9f, 7f, 2.2f),
            AccessPoint(5560f, 12f, 7f, 5.6f), AccessPoint(5580f, 8f, 6f, 1.1f),
            AccessPoint(5660f, 7f, 7f, 3.8f), AccessPoint(5700f, 10f, 7f, 0.2f),
            AccessPoint(5720f, 18f, 7f, 4.9f), AccessPoint(5745f, 15f, 7f, 2.7f),
            AccessPoint(5805f, 20f, 7f, 5.9f), AccessPoint(5825f, 9f, 6f, 3.3f),
        )
    } else {
        listOf(
            AccessPoint(2412f, 34f, 9f, 0.3f), AccessPoint(2437f, 38f, 9.5f, 2.4f),
            AccessPoint(2462f, 30f, 9f, 4.8f), AccessPoint(2432f, 7f, 3f, 1.2f),
            AccessPoint(2447f, 6f, 3f, 5.2f),
        )
    }

    /** Noise floor follows the gain slider the way a real front end does. */
    private fun floorDb(): Float = -89f + ((if (gain < 0) 40 else gain) - 40) * 0.12f

    private fun truthDb(f: Float, captureCenter: Float, halfSpan: Float): Float {
        var v = floorDb() + (rnd.nextFloat() - 0.5f) * 1.8f
        val t = (System.currentTimeMillis() - started) / 1000f
        for (ap in aps) {
            if (abs(f - ap.mhz) > 80f) continue
            val fade = 1f + 0.06f * sin(t * 0.9f + ap.phase)   // beacons come and go
            v += ap.amp * fade * exp(-0.5f * ((f - ap.mhz) / ap.width).pow(2))
        }
        // capture passband rolls off at the edges, like the unbuffered ADC path
        v -= 3.5f * ((f - captureCenter) / max(halfSpan, 1f)).pow(2)
        return v
    }

    // ---- capture loop ------------------------------------------------------
    override fun run() {
        var frames = 0
        var t0 = System.currentTimeMillis()
        var sweep: SweepState? = null
        var lastMode = mode
        var lastBand = band
        try {
            while (running) {
                if (mode != lastMode || band != lastBand) {
                    sweep = null
                    lastMode = mode
                    lastBand = band
                }
                val frame = if (lastMode == Mode.SWEEP) {
                    val state = sweep ?: SweepState().also { sweep = it }
                    sweepStep(state)
                } else {
                    liveStep()
                }
                frames++
                val now = System.currentTimeMillis()
                if (now - t0 >= 1000) {
                    fps = frames * 1000f / (now - t0)
                    frames = 0
                    t0 = now
                }
                listener.onFrame(frame)
                sleep(if (lastMode == Mode.SWEEP) 42L else 30L)
            }
        } catch (_: InterruptedException) {
            running = false
        } catch (t: Throwable) {
            if (running) listener.onError(t)
        }
    }

    private fun liveStep(): SdrFrame {
        val fsMhz = SdrEngine.RATES[rateIdx] / 1e6f
        val lo = (centerMhz + loOffsetMhz).toFloat()
        val fLo = lo - fsMhz / 2f
        val fHi = lo + fsMhz / 2f
        val n = nfft
        val avg = FloatArray(n)
        val peak = FloatArray(n)
        for (i in 0 until n) {
            val f = fLo + (fHi - fLo) * i / (n - 1)
            val v = truthDb(f, lo, fsMhz / 2f)
            avg[i] = v
            peak[i] = v + 2.5f + rnd.nextFloat() * 3f
        }
        val nsamp = 4096
        record(nsamp, lo, fLo, fHi)
        val base = -48f + ((if (gain < 0) 40 else gain) - 40) * 0.1f
        val td = FloatArray(48) { base + (rnd.nextFloat() - 0.5f) * 3.2f }
        val info = context.getString(R.string.engine_info, fps, base, nsamp, fsMhz, fps * nsamp / 1000f)
        return SdrFrame(avg, peak, td, fLo, fHi, band, info, null, null, 0f)
    }

    /** Fills the recorder with a plausible signal so the recording flow is testable end to end. */
    private fun record(nsamp: Int, lo: Float, fLo: Float, fHi: Float) {
        val rec = recorder ?: return
        val iq = FloatArray(nsamp * 2)
        val tone = lo + (fHi - fLo) * 0.18f                       // a carrier inside the passband
        val w = 2.0 * PI * (tone - lo) / (fHi - fLo)              // normalised rad/sample
        val amp = 0.05f * 10f.pow((gain - 40) / 120f)
        for (k in 0 until nsamp) {
            val noiseI = (rnd.nextFloat() - 0.5f) * 0.02f
            val noiseQ = (rnd.nextFloat() - 0.5f) * 0.02f
            iq[2 * k] = noiseI + amp * cos(w * k).toFloat()
            iq[2 * k + 1] = noiseQ + amp * sin(w * k).toFloat()
        }
        try {
            rec.write(iq)
        } catch (e: Exception) {
            recorder = null
            rec.close()
            listener.onNotice(context.getString(R.string.status_rec_stopped, e.message.orEmpty()))
        }
    }

    // ---- band sweep --------------------------------------------------------
    private inner class SweepState {
        val range = Bands.range(band)
        val los: IntArray = ArrayList<Int>().apply {
            var lo = range[0] - 10
            while (lo <= range[1] + 10) {
                add(lo)
                lo += SdrEngine.SWEEP_STEP_MHZ
            }
        }.toIntArray()
        val df: Float = SdrEngine.RATES[0] / 1e6f / nfft
        val f = FloatArray(((range[1] - range[0]) / df).toInt() + 1) { range[0] + it * df }
        val sum = DoubleArray(f.size)
        val cnt = DoubleArray(f.size)
        val max = FloatArray(f.size) { Float.NaN }
        val held = FloatArray(f.size) { Float.NaN }
        val chans = Bands.channels20(band)
        val hits = IntArray(chans.size)
        var sweeps = 0
        var i = 0
        var clip = 0f
        var lastMean: FloatArray? = null
        var t0 = System.currentTimeMillis()
    }

    private fun sweepStep(s: SweepState): SdrFrame {
        val lo = s.los[s.i].toFloat()
        val half = nfft / 2 * s.df
        for (j in 0 until nfft) {
            val rel = (j - nfft / 2) * s.df
            if (abs(rel) < 10f || abs(rel) > 20f) continue
            val idx = Math.round((lo + rel - s.f[0]) / s.df).toInt()
            if (idx < 0 || idx >= s.f.size) continue
            val v = truthDb(s.f[idx], lo, half)
            s.sum[idx] += 10.0.pow(v / 10.0)
            s.cnt[idx] += 1.0
            if (s.max[idx].isNaN() || v > s.max[idx]) s.max[idx] = v
            if (s.held[idx].isNaN() || v > s.held[idx]) s.held[idx] = v
        }
        record(4096, lo, lo - half, lo + half)
        s.i++
        val done = s.i >= s.los.size

        val mean = FloatArray(s.f.size)
        for (j in s.f.indices) {
            mean[j] = if (s.cnt[j] > 0) (10.0 * log10(s.sum[j] / s.cnt[j])).toFloat() else Float.NaN
        }
        val floor = noiseFloor(mean)
        val bars = if (done) channelActivity(s.f, s.max, floor, s.chans) else null
        val rates = if (done && bars != null) detectionRates(s, bars) else null
        val sec = (System.currentTimeMillis() - s.t0) / 1000f
        s.clip = if (rnd.nextFloat() > 0.85f) 0.4f + rnd.nextFloat() * 1.2f else 0f
        val info = if (done) {
            context.getString(R.string.sweep_done, sec, s.los.size, floor)
        } else {
            context.getString(R.string.sweep_progress, s.i, s.los.size, sec)
        }

        val shown = mean.copyOf()
        val prev = s.lastMean
        if (prev != null) {
            for (j in shown.indices) {
                if (shown[j].isNaN() && j < prev.size && !prev[j].isNaN()) shown[j] = prev[j]
            }
        }
        val frame = SdrFrame(
            shown, s.held.copyOf(), null, s.f[0], s.f[s.f.size - 1], band, info,
            bars, rates, s.clip
        )

        if (done) {
            s.lastMean = mean.copyOf()
            s.i = 0
            s.sum.fill(0.0)
            s.cnt.fill(0.0)
            s.max.fill(Float.NaN)
            for (j in s.held.indices) {
                val v = s.held[j]
                if (!v.isNaN()) s.held[j] = v - 2f          // same 2 dB/sweep peak decay
            }
            s.t0 = System.currentTimeMillis()
        }
        return frame
    }

    private fun noiseFloor(mean: FloatArray): Float {
        val valid = mean.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return 0f
        return valid[(valid.size * 0.2).toInt().coerceIn(0, valid.size - 1)]
    }

    private fun channelActivity(
        f: FloatArray,
        peak: FloatArray,
        floor: Float,
        chans: IntArray,
    ): FloatArray {
        val out = FloatArray(chans.size)
        for (c in chans.indices) {
            var best = Float.NaN
            for (j in f.indices) {
                if (abs(f[j] - chans[c]) > 8f) continue
                val v = peak[j]
                if (v.isNaN()) continue
                if (best.isNaN() || v > best) best = v
            }
            out[c] = if (best.isNaN()) 0f else max(0f, best - floor)
        }
        return out
    }

    private fun detectionRates(s: SweepState, bars: FloatArray): FloatArray {
        s.sweeps++
        for (c in bars.indices) if (bars[c] >= SdrEngine.DETECT_DB) s.hits[c]++
        return FloatArray(bars.size) { 100f * s.hits[it] / s.sweeps }
    }
}
