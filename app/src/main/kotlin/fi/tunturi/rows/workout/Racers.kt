package fi.tunturi.rows.workout

/** One opponent: either a saved row of yours, or a generated pace boat. */
data class Racer(
    val name: String,
    /** A few characters, for the narrow fullscreen race bar. */
    val shortName: String,
    val ghost: Ghost,
    val isVirtual: Boolean,
    val sessionId: String? = null,
)

object RacerRoster {

    private val paceFactors = listOf(0.94, 1.0, 1.07)

    /**
     * Your best row first, then your most recent ones, then virtual pacers filling
     * the remaining slots, so a new user always has someone to chase and each saved
     * session pushes a pacer out. Virtual racers are always labelled as such.
     *
     * "Best" depends on the goal: with one, it is the row that reached that distance
     * fastest; without, the row with the best average pace (500 m or longer, so a
     * short sprint does not count).
     */
    fun build(
        sessions: List<SessionSummary>,
        csvFor: (String) -> String?,
        basePaceSecPer500: Double,
        goalM: Int? = null,
        slots: Int = 3,
    ): List<Racer> {
        val rows = sessions.mapNotNull { summary ->
            val csv = csvFor(summary.id) ?: return@mapNotNull null
            val ghost = Ghost.fromCsv(csv)
            if (ghost.isEmpty) null else summary to ghost
        }

        val best = if (goalM != null) {
            rows.filter { (s, _) -> s.distanceM >= goalM }
                .minByOrNull { (_, ghost) -> ghost.elapsedAt(goalM) ?: Double.MAX_VALUE }
        } else {
            rows.filter { (s, _) -> s.distanceM >= 500 }
                .minByOrNull { (s, _) -> s.avgPaceSecPer500 ?: Double.MAX_VALUE }
        }

        val real = listOfNotNull(
            best?.let { (s, ghost) -> Racer("Your best · ${s.label}", "Best", ghost, false, s.id) }
        ) + rows.filter { it !== best }.map { (s, ghost) ->
            Racer(s.label, s.dateLabel, ghost, false, s.id)
        }

        val virtual = paceFactors.mapIndexed { i, factor ->
            Racer("Pacer ${i + 1}", "Pacer ${i + 1}", Ghost.pacer(basePaceSecPer500 * factor), isVirtual = true)
        }

        return (real + virtual).take(slots)
    }
}
