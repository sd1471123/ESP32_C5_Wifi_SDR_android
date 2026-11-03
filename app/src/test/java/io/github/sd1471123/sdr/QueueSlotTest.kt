package io.github.sd1471123.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Side commands are queued one slot per key so a stream of status updates cannot starve the wire.
 * `GPIO` has to be keyed per pin: keyed on the verb alone, a write to GPIO 6 replaced a pending
 * write to GPIO 5 and the first one never reached the board.
 */
class QueueSlotTest {

    @Test
    fun oneSlotPerLedOrBeeperVerb() {
        assertEquals("LED", Espsdr.slotOf("LED 10 20 30"))
        assertEquals(Espsdr.slotOf("LED 1 2 3"), Espsdr.slotOf("LED 4 5 6"))
        assertEquals("BEEP", Espsdr.slotOf("BEEP 5 2700 50"))
        assertEquals(Espsdr.slotOf("BEEP 5 2700 50"), Espsdr.slotOf("BEEP 0"))
    }

    @Test
    fun gpioGetsOneSlotPerPin() {
        assertEquals("GPIO 5", Espsdr.slotOf("GPIO 5 1"))
        assertEquals("GPIO 6", Espsdr.slotOf("GPIO 6 0"))
        assertNotEquals(Espsdr.slotOf("GPIO 5 1"), Espsdr.slotOf("GPIO 6 1"))
        assertEquals(Espsdr.slotOf("GPIO 5 1"), Espsdr.slotOf("GPIO 5 Z"))
    }

    @Test
    fun bareCommandKeysOnItself() {
        assertEquals("GPIO", Espsdr.slotOf("GPIO"))
    }
}
