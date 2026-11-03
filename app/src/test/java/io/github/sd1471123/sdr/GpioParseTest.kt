package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GPIO?` answers `GPIO 0:Z 3:1 ...` without a count. The pin panel used to read the first number
 * as "how many pins", which drew the wrong pins in the wrong states.
 */
class GpioParseTest {

    @Test
    fun parsesPinsAndStates() {
        val pins = Espsdr.parseGpioStates("GPIO 0:Z 1:Z 2:0 14:1")
        assertEquals(listOf(0, 1, 2, 14), pins.keys.toList())
        assertEquals('Z', pins[1])
        assertEquals('0', pins[2])
        assertEquals('1', pins[14])
    }

    @Test
    fun emptyListMeansNoSparePins() {
        assertTrue(Espsdr.parseGpioStates("GPIO").isEmpty())
    }

    @Test
    fun keepsOnlyWellFormedEntries() {
        val pins = Espsdr.parseGpioStates("GPIO 5:Z 6:X 7:1 broken")
        assertEquals(listOf(5, 7), pins.keys.toList())
    }
}
