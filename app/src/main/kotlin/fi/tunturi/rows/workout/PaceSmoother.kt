package fi.tunturi.rows.workout

/**
 * Instantaneous pace swings within every stroke (fast on the drive, slow on the
 * recovery), so following it directly makes the video lurch. This averages over a
 * few seconds — about two strokes at a normal rate.
 *
 * Averages speed, not pace: pace is time per distance, and averaging it would
 * overweight the slow part of each stroke.
 */
class PaceSmoother(private val windowMs: Long = 5_000) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Adds a sample (null or non-positive pace is ignored) and returns the smoothed pace. */
    fun add(paceSecPer500m: Double?, nowMs: Long): Double? {
        if (paceSecPer500m != null && paceSecPer500m > 0) {
            samples.addLast(nowMs to 500.0 / paceSecPer500m)
        }
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) samples.removeFirst()
        if (samples.isEmpty()) return null
        return 500.0 / samples.sumOf { it.second } * samples.size
    }

    fun clear() = samples.clear()
}
