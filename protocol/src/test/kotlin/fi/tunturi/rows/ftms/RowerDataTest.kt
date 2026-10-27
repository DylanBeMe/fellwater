package fi.tunturi.rows.ftms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowerDataTest {

    @Test
    fun `minimal frame decodes rate and count only`() {
        // flags=0x0000, stroke rate raw 60 (=30.0 spm), stroke count 12
        val s = RowerData.parse(byteArrayOf(0x00, 0x00, 0x3C, 0x0C, 0x00))!!
        assertEquals(30.0, s.strokeRateSpm!!, 1e-9)
        assertEquals(12, s.strokeCount)
        assertNull(s.totalDistanceM)
        assertNull(s.instantPaceSecPer500m)
        assertNull(s.elapsedSec)
        assertFalse(s.moreData)
    }

    @Test
    fun `sparse flags walk fields in spec order`() {
        // distance(bit2) | instant pace(bit3) | heart rate(bit11) | elapsed(bit13)
        val flags = (1 shl 2) or (1 shl 3) or (1 shl 11) or (1 shl 13)
        val s = RowerData.parse(
            byteArrayOf(
                (flags and 0xFF).toByte(), (flags shr 8).toByte(),
                0x42, // stroke rate raw 66 = 33.0 spm
                0xFA.toByte(), 0x00, // stroke count 250
                0xD2.toByte(), 0x04, 0x00, // total distance 1234 m
                0xE0.toByte(), 0x2E, // instant pace 12000 = 2:00.0 per 500 m
                0x91.toByte(), // heart rate 145
                0x2C, 0x01, // elapsed 300 s
            )
        )!!
        assertEquals(33.0, s.strokeRateSpm!!, 1e-9)
        assertEquals(250, s.strokeCount)
        assertEquals(1234, s.totalDistanceM)
        assertEquals(120.0, s.instantPaceSecPer500m!!, 1e-9)
        assertEquals(145, s.heartRateBpm)
        assertEquals(300, s.elapsedSec)
        assertNull(s.averagePaceSecPer500m)
        assertNull(s.instantPowerW)
    }

    @Test
    fun `whole-second pace console is not divided by 100`() {
        val flags = 1 shl 3
        val s = RowerData.parse(byteArrayOf((flags and 0xFF).toByte(), 0x00, 0x3C, 0x00, 0x00, 0x76, 0x00))!!
        assertEquals(118.0, s.instantPaceSecPer500m!!, 1e-9)
    }

    @Test
    fun `zero pace means no reading`() {
        val flags = 1 shl 3
        val s = RowerData.parse(byteArrayOf(0x08, 0x00, 0x3C, 0x00, 0x00, 0x00, 0x00))!!
        assertNull(s.instantPaceSecPer500m)
    }

    @Test
    fun `power is signed`() {
        val flags = 1 shl 5
        val s = RowerData.parse(byteArrayOf(0x20, 0x00, 0x3C, 0x00, 0x00, 0xFB.toByte(), 0xFF.toByte()))!!
        assertEquals(-5, s.instantPowerW)
    }

    @Test
    fun `truncated frame returns null instead of crashing`() {
        // distance flag set but no distance bytes follow
        assertNull(RowerData.parse(byteArrayOf(0x04, 0x00, 0x3C, 0x00, 0x00)))
        // not even flags
        assertNull(RowerData.parse(byteArrayOf(0x00)))
    }

    @Test
    fun `metabolic equivalent keeps one decimal`() {
        val flags = 1 shl 12
        val s = RowerData.parse(byteArrayOf(0x00, 0x10, 0x3C, 0x00, 0x00, 0x53))!!
        assertEquals(8.3, s.metabolicEquivalent!!, 1e-9)
    }
}
