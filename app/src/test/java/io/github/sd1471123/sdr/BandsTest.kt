package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsTest {

    @Test
    fun `band ranges match the firmware`() {
        assertTrue(intArrayOf(2400, 2500).contentEquals(Bands.range(Bands.GHZ24)))
        assertTrue(intArrayOf(5150, 5895).contentEquals(Bands.range(Bands.GHZ5)))
    }

    @Test
    fun `channel tables cover 1-13 and the 5 GHz grid`() {
        val g24 = Bands.channels20(Bands.GHZ24)
        assertEquals(13, g24.size)
        assertEquals(2412, g24.first())
        assertEquals(2472, g24.last())

        val g5 = Bands.channels20(Bands.GHZ5)
        assertEquals(36, g5.size)
        assertEquals(5180, g5.first())                 // ch36
        assertEquals(5880, g5.last())                  // ch176
        for (i in 1 until g5.size) assertEquals(20, g5[i] - g5[i - 1])
    }

    @Test
    fun `channel number works on both bands`() {
        assertEquals(1, Bands.channelNumber(2412f))
        assertEquals(6, Bands.channelNumber(2437f))
        assertEquals(13, Bands.channelNumber(2472f))
        assertEquals(36, Bands.channelNumber(5180f))
        assertEquals(165, Bands.channelNumber(5825f))
    }

    @Test
    fun `channelAt snaps within 10 MHz and rejects anything else`() {
        // the channel table sits on the 5 MHz grid, so "nearest" can be a neighbouring channel
        assertEquals(6, Bands.channelAt(Bands.GHZ24, 2437f))
        assertEquals(5, Bands.channelAt(Bands.GHZ24, 2431f))     // 2432 is nearest on the grid
        assertEquals(4, Bands.channelAt(Bands.GHZ24, 2425f))     // 2427 is nearest on the grid
        assertNull(Bands.channelAt(Bands.GHZ24, 2505f))          // out of band
    }

    @Test
    fun `presets - 20 MHz everywhere plus 40 and 80 on 5 GHz`() {
        val p24 = Bands.presets(Bands.GHZ24)
        assertEquals(13, p24.size)
        assertTrue(p24.all { it.width == 20 })

        val p5 = Bands.presets(Bands.GHZ5)
        assertEquals(48, p5.size)                              // 36 x 20M + 6 x 40M + 6 x 80M
        assertEquals(6, p5.count { it.width == 40 })
        assertEquals(6, p5.count { it.width == 80 })

        // ch36+ 80 MHz group: fc = 5000 + 36*5 + 40 - 10 = 5210
        val p36plus = p5.first { it.width == 80 && it.ch == 36 }
        assertEquals(5210, p36plus.center)
    }

    @Test
    fun `presets are sorted by channel then width`() {
        for (band in listOf(Bands.GHZ24, Bands.GHZ5)) {
            val list = Bands.presets(band)
            for (i in 1 until list.size) {
                val prev = list[i - 1]
                val cur = list[i]
                assertTrue(
                    "preset $i not sorted: ${prev.label} before ${cur.label}",
                    prev.ch < cur.ch || (prev.ch == cur.ch && prev.width <= cur.width)
                )
            }
        }
    }
}
