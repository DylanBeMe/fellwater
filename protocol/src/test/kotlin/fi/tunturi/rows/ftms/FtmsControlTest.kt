package fi.tunturi.rows.ftms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsControlTest {

    @Test
    fun `opcode only commands`() {
        assertArrayEquals(byteArrayOf(0x00), FtmsControl.requestControl())
        assertArrayEquals(byteArrayOf(0x01), FtmsControl.reset())
        assertArrayEquals(byteArrayOf(0x07), FtmsControl.startResume())
    }

    @Test
    fun `stop and pause share opcode 08 with a different parameter`() {
        assertArrayEquals(byteArrayOf(0x08, 0x01), FtmsControl.stop())
        assertArrayEquals(byteArrayOf(0x08, 0x02), FtmsControl.pause())
    }

    @Test
    fun `target power is little endian and survives values above 255`() {
        assertArrayEquals(byteArrayOf(0x05, 0xFA.toByte(), 0x00), FtmsControl.targetPower(250))
        assertArrayEquals(byteArrayOf(0x05, 0x2C, 0x01), FtmsControl.targetPower(300))
    }

    @Test
    fun `target resistance keeps tenths`() {
        assertArrayEquals(byteArrayOf(0x04, 0x50, 0x00), FtmsControl.targetResistanceLevel(80))
        assertArrayEquals(byteArrayOf(0x04, 0xD2.toByte(), 0x04), FtmsControl.targetResistanceLevel(1234))
    }

    @Test
    fun `target cadence uses half-stroke resolution`() {
        assertArrayEquals(byteArrayOf(0x09, 0x56, 0x00), FtmsControl.targetCadence(86))
    }

    @Test
    fun `heart rate is a single byte`() {
        assertArrayEquals(byteArrayOf(0x06, 0x91.toByte()), FtmsControl.targetHeartRate(145))
    }

    @Test
    fun `response indicates request opcode and result`() {
        val ok = ControlResponse.parse(byteArrayOf(0x80.toByte(), 0x00, 0x01))!!
        assertEquals(0x00, ok.requestOp)
        assertTrue(ok.success)

        val denied = ControlResponse.parse(byteArrayOf(0x80.toByte(), 0x04, 0x05))!!
        assertEquals(0x04, denied.requestOp)
        assertFalse(denied.success)
        assertTrue(denied.message.contains("control not permitted"))

        assertNull(ControlResponse.parse(byteArrayOf(0x07)))
        assertNull(ControlResponse.parse(byteArrayOf(0x01, 0x00, 0x01)))
    }
}

class MachineFeaturesTest {

    @Test
    fun `feature bitfields map to capabilities`() {
        val machine = (1 shl 1) or (1 shl 5) or (1 shl 14) // cadence, pace, power
        val target = (1 shl 2) or (1 shl 3) or (1 shl 16) // resistance, power, cadence
        fun u32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        val f = MachineFeatures.parse(u32(machine) + u32(target))!!
        assertTrue(f.reportsCadence)
        assertTrue(f.reportsPace)
        assertTrue(f.reportsPower)
        assertFalse(f.reportsDistance)
        assertTrue(f.acceptsTargetResistance)
        assertTrue(f.acceptsTargetPower)
        assertTrue(f.acceptsTargetCadence)
        assertFalse(f.acceptsTargetDistance)
        assertTrue(f.supportsStructuredWorkouts)
        assertEquals("stroke rate, pace, power", f.summary)
    }

    @Test
    fun `machine with no target support cannot run structured workouts`() {
        val f = MachineFeatures.parse(ByteArray(8))!!
        assertFalse(f.supportsStructuredWorkouts)
        assertEquals("nothing advertised", f.summary)
        assertNull(MachineFeatures.parse(ByteArray(4)))
    }
}
