package fi.tunturi.rows.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostTest {

    private val header = "elapsed_s,stroke_rate_spm,stroke_count,distance_m,pace_s_per_500m,power_w,resistance,heart_rate_bpm,energy_kcal"

    private fun row(elapsed: Int, distance: Int) = "$elapsed,24.0,10,$distance,120.0,180,3,140,"

    private val pb = Ghost.fromCsv(
        listOf(
            header,
            row(0, 0),
            row(10, 50),
            row(20, 100),
            row(30, 150),
            row(40, 200),
        ).joinToString("\n")
    )

    @Test
    fun `behind your best reads positive`() {
        assertEquals(2.0, pb.deltaSec(distanceM = 100, elapsedSec = 22)!!, 1e-9)
    }

    @Test
    fun `between samples interpolates instead of rounding up`() {
        // PB: 100 m at 20 s, 150 m at 30 s -> 120 m is worth 24 s
        assertEquals(1.0, pb.deltaSec(distanceM = 120, elapsedSec = 25)!!, 1e-9)
        assertEquals(-4.0, pb.deltaSec(distanceM = 120, elapsedSec = 20)!!, 1e-9)
    }

    @Test
    fun `exact sample distance matches without interpolation`() {
        assertEquals(0.0, pb.deltaSec(distanceM = 150, elapsedSec = 30)!!, 1e-9)
    }

    @Test
    fun `distanceAt inverts the ghost curve for the route bar`() {
        // PB: 0/50/100/150/200 m at 0/10/20/30/40 s
        assertEquals(100.0, pb.distanceAt(20)!!, 1e-9)
        assertEquals(125.0, pb.distanceAt(25)!!, 1e-9)   // halfway between 100@20 and 150@30
        assertEquals(0.0, pb.distanceAt(-5)!!, 1e-9)     // before the start
        assertEquals(200.0, pb.distanceAt(999)!!, 1e-9)  // beyond the end: last split
    }

    @Test
    fun `beyond the PB distance has no reference`() {
        assertNull(pb.deltaSec(distanceM = 250, elapsedSec = 50))
    }

    @Test
    fun `duplicate distances keep the first sample`() {
        val noisy = Ghost.fromCsv(
            listOf(header, row(5, 40), row(6, 40), row(9, 80)).joinToString("\n")
        )
        assertEquals(4.0, noisy.deltaSec(distanceM = 40, elapsedSec = 9)!!, 1e-9)
    }

    @Test
    fun `empty input is an empty ghost`() {
        assertTrue(Ghost.fromCsv(header).isEmpty)
        assertNull(Ghost.fromCsv("").deltaSec(10, 10))
    }
}

class ProgramsTest {

    @Test
    fun `workouts are withheld when the machine cannot take targets`() {
        assertEquals(0, Programs.available(canTargetPower = false).size)
        assertEquals(Programs.presets.size, Programs.available(canTargetPower = true).size)
    }

    @Test
    fun `interval sets are built by repeating work then rest`() {
        val thirtyThirty = Programs.presets.first { it.name == "30/30 x10" }
        assertEquals(20, thirtyThirty.steps.size)
        assertEquals("hard", thirtyThirty.steps[0].label)
        assertEquals("easy", thirtyThirty.steps[1].label)
        assertEquals(250, thirtyThirty.steps[0].targetPowerW)
    }
}

class WorkoutRecorderTest {

    private fun sample(elapsedSec: Int, distanceM: Int, powerW: Int?) = fi.tunturi.rows.ftms.RowerSample(
        strokeRateSpm = 24.0,
        strokeCount = elapsedSec * 2,
        averageStrokeRateSpm = null,
        totalDistanceM = distanceM,
        instantPaceSecPer500m = 120.0,
        averagePaceSecPer500m = null,
        instantPowerW = powerW,
        averagePowerW = null,
        resistanceLevel = 3,
        totalEnergyKcal = 5,
        energyPerHourKcal = null,
        energyPerMinKcal = null,
        heartRateBpm = null,
        metabolicEquivalent = null,
        elapsedSec = elapsedSec,
        remainingSec = null,
        moreData = false,
    )

    @Test
    fun `csv round trips through the ghost loader`() {
        val recorder = WorkoutRecorder()
        recorder.record(sample(0, 0, 180))
        recorder.record(sample(10, 50, 185))
        val csv = recorder.csv()

        assertTrue(csv.startsWith("elapsed_s,"))
        assertEquals(2, recorder.size)

        val splits = WorkoutCsv.splits(csv)
        assertEquals(2, splits.size)
        assertEquals(50, splits[1].distanceM)
        assertEquals(10, splits[1].elapsedSec)
    }

    @Test
    fun `absent fields become empty csv cells, not zeros`() {
        val recorder = WorkoutRecorder()
        recorder.record(sample(0, 0, powerW = null))
        val cells = recorder.csv().lineSequence().drop(1).first().split(',')
        assertEquals("", cells[5])
        assertEquals("0", cells[0])
    }
}
