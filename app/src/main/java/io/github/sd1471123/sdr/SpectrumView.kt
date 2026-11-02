package io.github.sd1471123.sdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Spectrum (average line + peak-hold fill), optional time-domain power strip, per-20 MHz-channel
 * activity bars plus detection rates and a scrolling waterfall, all drawn with plain Canvas calls.
 *
 * Interactions: single tap sets a level marker, double tap tunes to that frequency, drag pans,
 * pinch zooms (the view window is clamped to the captured range).
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var avg: FloatArray? = null
    private var peak: FloatArray? = null
    private var td: FloatArray? = null
    private var rates: FloatArray? = null

    private var dataFlo = 2397f
    private var dataFhi = 2477f
    private var viewFlo = 2397f
    private var viewFhi = 2477f

    private var info = ""
    private var band = ""
    private var bars: FloatArray? = null
    private var clipPct = 0f
    private var marker: Float? = null

    /** Called on double tap with the frequency under the finger. */
    var onTune: ((Float) -> Unit)? = null

    /**
     * Adaptive vertical range: [dbTop] follows the strongest peak, [dbBot] sits [dbSpan] dB below
     * it. A fixed -100…0 dBFS window crushed the whole-band sweep (everything around -90 dBFS)
     * into the bottom pixels and turned the waterfall black.
     */
    private var dbTop = -30f
    private var dbBot = -100f
    private var dbSpan = 70f
    private val wfRows = 160

    /** True: axis follows the noise floor. False: user-picked floor + span from the rail. */
    var autoScale = true
    var manualFloor = -100f
        set(value) {
            field = value
            updateScale()
            invalidate()
        }
    var manualSpan = 80f
        set(value) {
            field = value
            updateScale()
            invalidate()
        }

    /** Exponential trace averaging across frames: 0 = off, else weight of the new frame. */
    var smoothing = 0f
    private var smooth: FloatArray? = null

    private var wf: Bitmap? = null
    private var wfPix: IntArray? = null

    private val background = Paint().apply { color = Color.rgb(14, 17, 23) }
    private val grid = Paint().apply { color = Color.rgb(35, 42, 51) }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 200, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val peakFill = Paint().apply { color = Color.argb(48, 255, 165, 61) }
    private val peakLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 165, 61)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint()
    private val tdBackground = Paint().apply { color = Color.rgb(18, 22, 28) }
    private val tdPaint = Paint().apply { color = Color.rgb(0, 160, 210) }
    private val tdLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(143, 163, 184)
        textSize = sp(10f)
    }
    private val rateTrack = Paint().apply { color = Color.rgb(35, 42, 51) }
    private val rateFill = Paint()
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 107, 107)
        textSize = sp(10f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 240, 176)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(203, 210, 220)
        textSize = sp(12f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(107, 116, 128)
        textSize = sp(9f)
    }
    private val markerLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 240, 176)
        textSize = sp(12f)
    }
    private val markerSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 220, 190)
        textSize = sp(10f)
    }
    private val hintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 160, 175)
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val hintBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 105, 118)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 18, 23, 30)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val pathAvg = Path()
    private val pathPeak = Path()
    private val pathPeakFill = Path()
    private val cornerPath = Path()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (width > 0) zoomAt(freqAt(detector.focusX), 1f / detector.scaleFactor)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                marker = freqAt(e.x)
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val f = freqAt(e.x)
                marker = f
                onTune?.invoke(f)
                invalidate()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress || width == 0) return false
                panBy(distanceX / width * (viewFhi - viewFlo))
                return true
            }
        }
    )

    fun setFrame(frame: SdrFrame) {
        if (frame.fLoMhz != dataFlo || frame.fHiMhz != dataFhi) {
            dataFlo = frame.fLoMhz
            dataFhi = frame.fHiMhz
            viewFlo = frame.fLoMhz
            viewFhi = frame.fHiMhz
        }
        if (frame.band != band) {
            bars = null
            rates = null
        }
        avg = smoothTrace(frame.avgDb)
        peak = frame.peakDb
        td = frame.tdDb
        // A sweep only carries the channel strip on its last step: keep the last complete one so
        // the panels below the spectrum do not appear/disappear (and resize the waterfall) 20×/s.
        if (frame.bars != null) bars = frame.bars
        if (frame.rates != null) rates = frame.rates
        if (td != null) {
            bars = null
            rates = null
        }
        clipPct = frame.clipPct
        band = frame.band
        info = frame.info
        updateScale()
        invalidate()
    }

    /**
     * Anchor the axis to the noise floor (20th percentile of the average spectrum) with a 70 dB
     * window, so the noise stays dark blue and access points stand out — on a full-band sweep a
     * fixed -100…0 dBFS range put everything into the bottom 10 % of the plot.
     */
    private fun smoothTrace(raw: FloatArray): FloatArray {
        if (smoothing <= 0f) {
            smooth = null
            return raw
        }
        val prev = smooth
        if (prev == null || prev.size != raw.size) {
            return raw.copyOf().also { smooth = it }
        }
        for (i in raw.indices) {
            val p = prev[i]
            prev[i] = if (p.isNaN() || raw[i].isNaN()) raw[i] else p + smoothing * (raw[i] - p)
        }
        return prev
    }

    /** Wipes every trace; the empty-state hint comes back until the next frame. */
    fun clear() {
        avg = null
        peak = null
        td = null
        bars = null
        rates = null
        marker = null
        smooth = null
        wf?.eraseColor(Color.BLACK)
        invalidate()
    }
    private fun updateScale() {
        if (!autoScale) {
            dbBot = manualFloor
            dbTop = manualFloor + manualSpan
            dbSpan = manualSpan
            return
        }
        val a = avg ?: return
        if (a.isEmpty()) return
        val p = peak ?: a

        var strongest = Float.NEGATIVE_INFINITY
        for (v in p) {
            if (v.isNaN()) continue
            if (v > strongest) strongest = v
        }
        if (strongest == Float.NEGATIVE_INFINITY) return

        val step = (a.size / 256).coerceAtLeast(1)
        val sample = ArrayList<Float>(256)
        var i = 0
        while (i < a.size) {
            val v = a[i]
            if (!v.isNaN()) sample.add(v)
            i += step
        }
        if (sample.isEmpty()) return
        sample.sort()
        val floor = sample[(sample.size * 0.2f).toInt().coerceIn(0, sample.size - 1)]

        var top = floor + 55f
        if (strongest > top - 8f) top = strongest + 8f
        val k = if (top > dbTop) 0.4f else 0.06f
        dbTop += (top - dbTop) * k
        dbBot = dbTop - dbSpan
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    // ---- view window ------------------------------------------------------
    private fun freqAt(x: Float): Float =
        viewFlo + x / width.coerceAtLeast(1) * (viewFhi - viewFlo)

    private fun minSpan(): Float = (dataFhi - dataFlo) / 64f

    private fun zoomAt(focus: Float, factor: Float) {
        val span = viewFhi - viewFlo
        if (span <= 0f) return
        val ratio = ((focus - viewFlo) / span).coerceIn(0f, 1f)
        val newSpan = (span * factor).coerceIn(minSpan(), dataFhi - dataFlo)
        viewFlo = focus - ratio * newSpan
        viewFhi = viewFlo + newSpan
        clampWindow()
        invalidate()
    }

    private fun panBy(deltaMhz: Float) {
        viewFlo += deltaMhz
        viewFhi += deltaMhz
        clampWindow()
        invalidate()
    }

    private fun clampWindow() {
        val dataSpan = dataFhi - dataFlo
        if (dataSpan <= 0f) return
        val span = (viewFhi - viewFlo).coerceIn(minSpan(), dataSpan)
        var lo = viewFlo
        if (lo < dataFlo) lo = dataFlo
        if (lo + span > dataFhi) lo = dataFhi - span
        viewFlo = lo
        viewFhi = lo + span
    }

    // ---- drawing ----------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val radius = 18f * resources.displayMetrics.density
        cornerPath.reset()
        cornerPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(cornerPath)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), background)

        // Header band: band / range / info, so the labels never sit on top of the dB grid.
        val headerH = (38f * resources.displayMetrics.density).toInt()
        val plotH = h - headerH
        val specH = (plotH * 0.46f).toInt()
        val tdH = if (td != null) (plotH * 0.13f).toInt() else 0
        val barH = if (bars != null) (plotH * 0.08f).toInt() else 0
        val rateH = if (rates != null) (plotH * 0.05f).toInt() else 0
        val tdTop = headerH + specH + 2
        val barTop = tdTop + tdH
        val rateTop = barTop + barH
        val wfTop = rateTop + rateH + 2
        val wfH = h - wfTop

        drawSpectrum(canvas, w, headerH, specH)
        drawMarker(canvas, w, headerH, specH)
        drawTimeDomain(canvas, w, tdTop, tdH)
        drawBars(canvas, w, barTop, barH)
        drawRates(canvas, w, rateTop, rateH)
        if (wfH > 0) drawWaterfall(canvas, w, wfTop, wfH)
        drawLabels(canvas, w)
        if (avg == null) drawEmptyHint(canvas, w, h)
        canvas.restore()
    }

    /** Shown before the first frame so the empty panel does not look broken. */
    private fun drawEmptyHint(canvas: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val title = context.getString(R.string.hint_title)
        val body = context.getString(R.string.hint_body)
        val cx = w / 2f
        val cy = h / 2f
        val tw = maxOf(hintTitle.measureText(title), hintBody.measureText(body))
        canvas.drawRoundRect(
            cx - tw / 2f - 20f * d, cy - 24f * d,
            cx + tw / 2f + 20f * d, cy + 24f * d,
            16f * d, 16f * d, hintBg
        )
        canvas.drawText(title, cx, cy - 2f * d, hintTitle)
        canvas.drawText(body, cx, cy + 18f * d, hintBody)
    }

    private fun yFor(db: Float, top: Int, hgt: Int): Float {
        val t = ((db - dbBot) / dbSpan).coerceIn(0f, 1f)
        return top + hgt - 4f - t * (hgt - 8f)
    }

    private fun drawSpectrum(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        var db = Math.ceil((dbBot / 20f).toDouble()).toFloat() * 20f
        while (db <= dbTop) {
            val y = yFor(db, top, hgt)
            canvas.drawLine(0f, y, w.toFloat(), y, grid)
            canvas.drawText("%.0f".format(db), 6f * resources.displayMetrics.density, y - 2f, axis)
            db += 20f
        }
        val a = avg ?: return
        val p = peak ?: a
        pathAvg.reset()
        pathPeak.reset()
        pathPeakFill.reset()

        val dataSpan = dataFhi - dataFlo
        val viewSpan = viewFhi - viewFlo
        if (dataSpan <= 0f || viewSpan <= 0f) return

        var started = false
        var fillStarted = false
        for (i in a.indices) {
            val freq = dataFlo + dataSpan * i / (a.size - 1).coerceAtLeast(1)
            if (freq < viewFlo || freq > viewFhi) {
                started = false
                if (fillStarted) {
                    pathPeak.close()
                    fillStarted = false
                }
                continue
            }
            val x = (freq - viewFlo) / viewSpan * w
            val ya = a[i]
            val yp = p[minOf(i, p.size - 1)]

            if (ya.isNaN()) {
                started = false
            } else {
                val y = yFor(ya, top, hgt)
                if (started) pathAvg.lineTo(x, y) else pathAvg.moveTo(x, y)
                started = true
            }

            if (yp.isNaN()) {
                if (fillStarted) {
                    pathPeak.close()
                    fillStarted = false
                }
            } else {
                val y = yFor(yp, top, hgt)
                if (fillStarted) {
                    pathPeak.lineTo(x, y)
                    pathPeakFill.lineTo(x, y)
                } else {
                    pathPeak.moveTo(x, y)
                    pathPeakFill.moveTo(x, (top + hgt).toFloat())
                    pathPeakFill.lineTo(x, y)
                    fillStarted = true
                }
            }
        }
        if (fillStarted) {
            pathPeakFill.lineTo(w.toFloat(), (top + hgt).toFloat())
            pathPeakFill.close()
        }
        canvas.drawPath(pathPeakFill, peakFill)
        canvas.drawPath(pathPeak, peakLine)
        canvas.drawPath(pathAvg, avgPaint)
    }

    private fun drawMarker(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        val m = marker ?: return
        val viewSpan = viewFhi - viewFlo
        if (viewSpan <= 0f || m < viewFlo || m > viewFhi) return
        val x = (m - viewFlo) / viewSpan * w
        canvas.drawLine(x, top.toFloat(), x, (top + hgt).toFloat(), markerPaint)
        val db = levelAt(m)
        val text = if (db.isNaN()) "%.1f MHz".format(m) else "%.1f MHz  %.1f dBFS".format(m, db)
        val baseY = (top + hgt).toFloat() - 6f
        val right = w - markerLabel.measureText(text) - 4f
        canvas.drawText(text, if (x + 6f > right) right else x + 6f, baseY, markerLabel)

        // Channel / band the tapped frequency belongs to (or why it belongs to none).
        if (band.isNotEmpty()) {
            val chan = Bands.channelAt(band, m)
            val sub = when {
                !Bands.inBand(band, m) -> context.getString(R.string.marker_out_of_band, band)
                chan != null -> context.getString(R.string.marker_channel, chan, band)
                else -> context.getString(R.string.marker_no_channel, band)
            }
            val subRight = w - markerSub.measureText(sub) - 4f
            canvas.drawText(
                sub,
                if (x + 6f > subRight) subRight else x + 6f,
                baseY - markerLabel.textSize - 2f,
                markerSub
            )
        }
    }

    private fun levelAt(freq: Float): Float {
        val a = avg ?: return Float.NaN
        val dataSpan = dataFhi - dataFlo
        if (dataSpan <= 0f || a.isEmpty()) return Float.NaN
        val t = (freq - dataFlo) / dataSpan
        val idx = (t * (a.size - 1)).toInt().coerceIn(0, a.size - 1)
        return a[idx]
    }

    /** Block power of the current capture: one vertical bar per FFT frame. */
    private fun drawTimeDomain(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        val t = td ?: return
        if (hgt <= 0) return
        canvas.drawRect(0f, top.toFloat(), w.toFloat(), (top + hgt).toFloat(), tdBackground)
        if (t.isEmpty()) return

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in t) {
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (lo > hi) return
        if (hi - lo < 12f) {
            val mid = (hi + lo) / 2f
            lo = mid - 6f
            hi = mid + 6f
        }

        val slot = w.toFloat() / t.size
        val bw = slot.coerceAtLeast(1f) - 0.5f
        for (i in t.indices) {
            val v = t[i]
            if (v.isNaN()) continue
            val f = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            val bh = 1f + f * (hgt - 4)
            canvas.drawRect(i * slot, top + hgt - bh, i * slot + bw, top + hgt.toFloat(), tdPaint)
        }
        canvas.drawText(
            context.getString(R.string.time_domain, lo, hi),
            6f * resources.displayMetrics.density,
            top + 12f * resources.displayMetrics.density,
            tdLabel
        )
    }

    private fun drawBars(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        val b = bars ?: return
        if (hgt <= 0 || b.isEmpty()) return
        val slot = w.toFloat() / b.size
        val showName = slot >= 20f
        val chans = Bands.channels20(band)
        for (i in b.indices) {
            val t = (b[i] / 30f).coerceIn(0f, 1f)
            val barHeight = 1f + t * (hgt - 2)
            val x0 = i * slot + 0.5f
            val x1 = x0 + slot - 1f
            barPaint.color = Color.HSVToColor(floatArrayOf(120f - 120f * t, 0.9f, 0.9f))
            canvas.drawRect(x0, top + hgt - barHeight, x1, top + hgt.toFloat(), barPaint)
            if (showName && i < chans.size) {
                val text = Bands.channelNumber(chans[i].toFloat()).toString()
                canvas.drawText(
                    text,
                    x0 + (slot - 1f - tdLabel.measureText(text)) / 2f,
                    top + 11f * resources.displayMetrics.density,
                    tdLabel
                )
            }
        }
    }

    /** Detection rate per channel: horizontal fill inside each slot, 0 … 100 %. */
    private fun drawRates(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        val r = rates ?: return
        if (hgt <= 0 || r.isEmpty()) return
        val slot = w.toFloat() / r.size
        for (i in r.indices) {
            val t = (r[i] / 100f).coerceIn(0f, 1f)
            val x0 = i * slot + 0.5f
            val x1 = x0 + (slot - 1f).coerceAtLeast(1f)
            canvas.drawRect(x0, top + 1f, x1, (top + hgt - 1).toFloat(), rateTrack)
            if (t > 0f) {
                rateFill.color = Color.HSVToColor(floatArrayOf(200f - 200f * t, 0.85f, 0.95f))
                canvas.drawRect(x0, top + 1f, x0 + (x1 - x0) * t, (top + hgt - 1).toFloat(), rateFill)
            }
        }
    }

    private fun drawWaterfall(canvas: Canvas, w: Int, top: Int, hgt: Int) {
        if (w <= 0 || hgt <= 0) return
        ensureWaterfall(w)
        val bmp = wf ?: return
        val pix = wfPix ?: return
        val a = avg
        val viewSpan = viewFhi - viewFlo
        val dataSpan = dataFhi - dataFlo
        if (a != null && a.isNotEmpty() && viewSpan > 0f && dataSpan > 0f) {
            System.arraycopy(pix, w, pix, 0, (wfRows - 1) * w)
            val off = (wfRows - 1) * w
            for (x in 0 until w) {
                val freq = viewFlo + viewSpan * x / (w - 1).coerceAtLeast(1)
                val t = (freq - dataFlo) / dataSpan
                if (t < 0f || t > 1f) {
                    pix[off + x] = Color.BLACK
                    continue
                }
                val idx = (t * (a.size - 1)).toInt().coerceIn(0, a.size - 1)
                val v = a[idx]
                // While a sweep is still in flight most bins are not filled yet: repeat the row
                // above there instead of punching black holes into the waterfall.
                pix[off + x] = if (v.isNaN()) pix[w + x] else colorFor(v)
            }
            bmp.setPixels(pix, 0, w, 0, 0, w, wfRows)
        }
        canvas.drawBitmap(bmp, null, Rect(0, top, w, top + hgt), null)
    }

    private fun ensureWaterfall(w: Int) {
        val current = wf
        if (current != null && current.width == w) return
        current?.recycle()
        wf = Bitmap.createBitmap(w, wfRows, Bitmap.Config.ARGB_8888)
        wfPix = IntArray(w * wfRows)
    }

    private fun colorFor(db: Float): Int {
        val t = ((db - dbBot) / dbSpan).coerceIn(0f, 1f)
        val hue = 240f - 240f * t
        val value = 0.38f + 0.62f * t
        return Color.HSVToColor(floatArrayOf(hue, 1f, value))
    }

    private fun drawLabels(canvas: Canvas, w: Int) {
        if (avg == null) return
        val d = resources.displayMetrics.density
        val left = if (band.isEmpty()) "%.0f".format(viewFlo) else "$band   %.0f".format(viewFlo)
        canvas.drawText(left, 12f * d, 18f * d, label)
        val right = "%.0f MHz".format(viewFhi)
        canvas.drawText(right, w - label.measureText(right) - 12f * d, 18f * d, label)
        if (info.isNotEmpty()) canvas.drawText(info, 12f * d, 33f * d, tdLabel)

        // strongest bin of the average trace, right-aligned on the info row
        val a = avg ?: return
        var best = -1
        for (i in a.indices) {
            if (a[i].isNaN()) continue
            if (best < 0 || a[i] > a[best]) best = i
        }
        if (best < 0) return
        val peakFreq = dataFlo + (dataFhi - dataFlo) * best / (a.size - 1).coerceAtLeast(1)
        val peakText = context.getString(R.string.peak_readout, peakFreq)
        val clipText = if (clipPct >= 0.05f) context.getString(R.string.clip_pct, clipPct) else null
        var xRight = w - 12f * d
        if (clipText != null) {
            canvas.drawText(clipText, xRight - warnPaint.measureText(clipText), 33f * d, warnPaint)
            xRight -= warnPaint.measureText(clipText) + 10f * d
        }
        canvas.drawText(peakText, xRight - label.measureText(peakText), 33f * d, label)
    }
}
