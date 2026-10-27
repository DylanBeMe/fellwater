package fi.tunturi.rows.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HEADER =
    "elapsed_s,stroke_rate_spm,stroke_count,distance_m,pace_s_per_500m,power_w,resistance,heart_rate_bpm,energy_kcal"

/** A steady row: [paceSecPer500] held to [distanceM], one sample every 10 s. */
private fun steadyCsv(paceSecPer500: Int, distanceM: Int): String = buildString {
    appendLine(HEADER)
    var t = 0
    while (true) {
        val d = minOf(distanceM, t * 500 / paceSecPer500)
        appendLine("$t,24.0,${t / 3},$d,$paceSecPer500.0,150,,140,${t / 10}")
        if (d >= distanceM) break
        t += 10
    }
}

class PacerTest {

    @Test
    fun `pacers keep going past 5 km`() {
        val pacer = Ghost.pacer(120.0)
        // 2:00/500 m for 40 min = 10 km; it used to freeze at 5 km.
        assertEquals(10_000.0, pacer.distanceAt(2400)!!, 1.0)
    }

    @Test
    fun `elapsedAt reads the time at a distance`() {
        assertEquals(480.0, Ghost.pacer(120.0).elapsedAt(2000)!!, 1.0)
    }
}

class RosterTest {

    private val csvs = mapOf(
        // Most recent first, as SessionStore.list() returns them.
        "recent-slow-2k" to steadyCsv(140, 2000),
        "fast-2k" to steadyCsv(110, 2000),
        "fast-sprint-300m" to steadyCsv(95, 300),
        "medium-5k" to steadyCsv(120, 5000),
    )
    private val sessions = csvs.map { (id, csv) -> SessionMath.summarize(id, 0, csv)!! }

    @Test
    fun `with a goal the fastest row over that distance leads`() {
        val racers = RacerRoster.build(sessions, csvs::get, 150.0, goalM = 5000)
        assertEquals("medium-5k", racers.first().sessionId)
        assertEquals("Best", racers.first().shortName)
    }

    @Test
    fun `without a goal the best average pace leads, ignoring sprints`() {
        val racers = RacerRoster.build(sessions, csvs::get, 150.0)
        assertEquals("fast-2k", racers.first().sessionId)
        // then the most recent others
        assertEquals("recent-slow-2k", racers[1].sessionId)
    }

    @Test
    fun `pacers fill empty slots`() {
        val racers = RacerRoster.build(emptyList(), { null }, 150.0)
        assertEquals(3, racers.size)
        assertTrue(racers.all { it.isVirtual })
    }
}

class TcxTest {

    @Test
    fun `tcx carries totals, one point per second, cadence, power and heart rate`() {
        val csv = """
            $HEADER
            0,20.0,0,0,,100,,130,0
            1,22.0,1,4,,150,,131,1
            1,23.0,1,5,,160,,132,1
            2,24.0,2,9,,170,,,2
        """.trimIndent()
        val tcx = Export.tcx(startedAtMs = 0, csv = csv)

        assertTrue(tcx.contains("<Id>1970-01-01T00:00:00Z</Id>"))
        assertTrue(tcx.contains("<TotalTimeSeconds>2</TotalTimeSeconds>"))
        assertTrue(tcx.contains("<DistanceMeters>9</DistanceMeters>"))
        assertEquals(3, Regex("<Trackpoint>").findAll(tcx).count())
        // the later of two samples in the same second wins
        assertTrue(tcx.contains("<ns3:Watts>160</ns3:Watts>"))
        assertFalse(tcx.contains("<ns3:Watts>150</ns3:Watts>"))
        assertTrue(tcx.contains("<Cadence>24</Cadence>"))
        assertTrue(tcx.contains("<HeartRateBpm><Value>132</Value></HeartRateBpm>"))
    }
}
