package io.github.sd1471123.sdr

/**
 * One selectable channel preset: label, centre frequency in MHz, channel width in MHz and the
 * primary channel number it belongs to (used to keep the list sorted by channel).
 */
class Preset(val label: String, val center: Int, val width: Int, val ch: Int)

/** Band plans and channel tables, matching `wifi_channels.py` on the desktop side. */
object Bands {

    const val GHZ24 = "2.4 GHz"
    const val GHZ5 = "5 GHz"

    fun range(band: String): IntArray =
        if (band == GHZ24) intArrayOf(2400, 2500) else intArrayOf(5150, 5895)

    /** ch1-13 on 2.4 GHz, ch36-177 (20 MHz spacing) on 5 GHz. */
    fun channels20(band: String): IntArray =
        if (band == GHZ24) IntArray(13) { 2412 + it * 5 } else IntArray(36) { 5180 + it * 20 }

    fun channelNumber(freqMhz: Float): Int =
        if (freqMhz < 3000f) Math.round((freqMhz - 2407f) / 5f) else Math.round((freqMhz - 5000f) / 5f)

    /**
     * 20 MHz channel whose block contains [freqMhz] (nearest centre within ±10 MHz), or null
     * when the frequency sits between channels — used for the tap marker readout.
     */
    fun channelAt(band: String, freqMhz: Float): Int? {
        val nearest = channels20(band).minByOrNull { Math.abs(freqMhz - it) } ?: return null
        return if (Math.abs(freqMhz - nearest) <= 10f) channelNumber(nearest.toFloat()) else null
    }

    /** True when [freqMhz] lies inside the band described by [band]. */
    fun inBand(band: String, freqMhz: Float): Boolean {
        val r = range(band)
        return freqMhz >= r[0] && freqMhz <= r[1]
    }

    /**
     * Channel presets: 20 MHz channels everywhere, plus the 40/80 MHz groupings that a
     * Wi-Fi access point actually uses on 5 GHz.
     */
    fun presets(band: String): List<Preset> {
        val out = ArrayList<Preset>()
        for (fc in channels20(band)) {
            val ch = channelNumber(fc.toFloat())
            out.add(Preset("ch$ch $fc·20M", fc, 20, ch))
        }
        if (band == GHZ5) {
            for ((first, width) in listOf(36 to 40, 52 to 40, 100 to 40, 116 to 40, 132 to 40, 149 to 40)) {
                val fc = 5000 + first * 5 + width / 2 - 10
                out.add(Preset("ch$first+ $fc·${width}M", fc, width, first))
            }
            for ((first, width) in listOf(36 to 80, 52 to 80, 100 to 80, 116 to 80, 132 to 80, 149 to 80)) {
                val fc = 5000 + first * 5 + width / 2 - 10
                out.add(Preset("ch$first+ $fc·${width}M", fc, width, first))
            }
        }
        // Channel number ascending; for the same channel the narrow option comes first.
        return out.sortedWith(compareBy({ it.ch }, { it.width }))
    }

    /** Blocks to draw on the channel strip: freq, width. */
    fun strip(band: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (fc in channels20(band)) out.add(intArrayOf(fc, 20))
        return out
    }
}

enum class Mode { LIVE, SWEEP }

/** One frame handed from the capture thread to the UI. */
class SdrFrame(
    val avgDb: FloatArray,
    val peakDb: FloatArray,
    /** Time domain: block power (dBFS) inside one capture, or null while sweeping. */
    val tdDb: FloatArray?,
    val fLoMhz: Float,
    val fHiMhz: Float,
    val band: String,
    val info: String,
    /** Per 20 MHz channel activity above the noise floor (dB); null in live mode. */
    val bars: FloatArray?,
    /** Per 20 MHz channel detection rate (% of sweeps above threshold). */
    val rates: FloatArray?,
    /** Share of samples at full scale (%). */
    val clipPct: Float,
)
