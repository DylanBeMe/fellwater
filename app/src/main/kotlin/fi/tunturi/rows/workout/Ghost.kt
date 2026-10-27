package fi.tunturi.rows.workout

/**
 * Race your own best. The PB is the same CSV the recorder writes, so a session
 * you export can be loaded back as the ghost.
 *
 * Compares by machine-reported distance, interpolating between PB samples so a
 * 1 Hz feed still gives a fair verdict. Not a replacement for a proper split
 * table, but it will not flatter you either.
 */
class Ghost(private val splits: List<Split>) {

    val isEmpty: Boolean get() = splits.isEmpty()

    /**
     * Seconds ahead (negative) or behind (positive) the PB at [distanceM].
     * @return null when the PB has no data covering that distance.
     */
    fun deltaSec(distanceM: Int, elapsedSec: Int): Double? {
        val reference = elapsedAt(distanceM) ?: return null
        return elapsedSec - reference
    }

    /**
     * How far this ghost has travelled at [elapsedSec]. Inverse of [deltaSec]; used
     * to place every racer on the route bar at the same moment.
     */
    fun distanceAt(elapsedSec: Int): Double? {
        if (splits.isEmpty()) return null
        val before = splits.lastOrNull { it.elapsedSec <= elapsedSec }
        val after = splits.firstOrNull { it.elapsedSec >= elapsedSec }
        return when {
            before == null && after == null -> null
            before == null -> after!!.distanceM.toDouble()
            after == null -> splits.last().distanceM.toDouble()
            before.elapsedSec == after.elapsedSec -> before.distanceM.toDouble()
            else -> {
                val span = (after.elapsedSec - before.elapsedSec).toDouble()
                val travelled = (elapsedSec - before.elapsedSec).toDouble()
                before.distanceM + (after.distanceM - before.distanceM) * (travelled / span)
            }
        }
    }

    /** Seconds this ghost took to reach [distanceM]; null if it never got that far. */
    fun elapsedAt(distanceM: Int): Double? {
        val before = splits.lastOrNull { it.distanceM <= distanceM }
        val after = splits.firstOrNull { it.distanceM >= distanceM }
        return when {
            after == null -> null // beyond the distance the PB actually covered
            before == null -> 0.0 // before the first PB sample, i.e. the very start
            before.distanceM == after.distanceM -> before.elapsedSec.toDouble()
            else -> {
                val span = (after.distanceM - before.distanceM).toDouble()
                val travelled = (distanceM - before.distanceM).toDouble()
                before.elapsedSec + (after.elapsedSec - before.elapsedSec) * (travelled / span)
            }
        }
    }

    companion object {
        /**
         * A synthetic opponent that holds a constant pace. This is our own virtual
         * pacer, not a copy of anyone's online multiplayer: no network, no other
         * people, just a pace line to race against.
         */
        // Long enough for any goal on offer (it used to stop at 5 km, so with the
        // 10 km goal the pacers froze halfway and could be "overtaken" for free).
        fun pacer(paceSecPer500m: Double, maxDistanceM: Int = 50_000, stepM: Int = 50): Ghost {
            val splits = ArrayList<Split>()
            var d = 0
            while (d <= maxDistanceM) {
                splits += Split(d, ((d / 500.0) * paceSecPer500m).toInt())
                d += stepM
            }
            return Ghost(splits)
        }

        /** Drops duplicate distances and keeps the list monotonic. */
        fun fromCsv(csv: String): Ghost {
            val cleaned = mutableListOf<Split>()
            var lastDistance = -1
            WorkoutCsv.splits(csv).forEach { split ->
                if (split.distanceM > lastDistance) {
                    cleaned += split
                    lastDistance = split.distanceM
                }
            }
            return Ghost(cleaned)
        }
    }
}
