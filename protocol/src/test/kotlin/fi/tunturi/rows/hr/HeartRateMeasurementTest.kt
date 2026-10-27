package fi.tunturi.rows.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementTest {

    @Test
    fun `uint8 rate`() {
        assertEquals(72, HeartRateMeasurement.parse(byteArrayOf(0x00, 72)))
    }

    @Test
    fun `uint16 rate when flag bit 0 is set`() {
        // 0x0104 = 260 bpm is not physiological, but proves the byte order.
        assertEquals(260, HeartRateMeasurement.parse(byteArrayOf(0x01, 0x04, 0x01)))
    }

    @Test
    fun `trailing RR intervals are ignored`() {
        // flags 0x16: contact detected + supported, RR present; 150 bpm, then one RR.
        assertEquals(150, HeartRateMeasurement.parse(byteArrayOf(0x16, 150.toByte(), 0x00, 0x04)))
    }

    @Test
    fun `short frames and zero are not a rate`() {
        assertNull(HeartRateMeasurement.parse(byteArrayOf(0x00)))
        assertNull(HeartRateMeasurement.parse(byteArrayOf(0x01, 0x48)))
        assertNull(HeartRateMeasurement.parse(byteArrayOf(0x00, 0x00)))
    }
}
