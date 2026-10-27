package fi.tunturi.rows.workout

import fi.tunturi.rows.ftms.RowerSample

data class WorkoutRow(
    val elapsedSec: Int?,
    val strokeRateSpm: Double?,
    val strokeCount: Int?,
    val distanceM: Int?,
    val paceSecPer500m: Double?,
    val powerW: Int?,
    val resistanceLevel: Int?,
    val heartRateBpm: Int?,
    val energyKcal: Int?,
)

/** Records what the machine actually sent, nothing derived. */
class WorkoutRecorder {
    private val rows = mutableListOf<WorkoutRow>()

    val size: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty()

    fun record(sample: RowerSample) {
        rows += WorkoutRow(
            elapsedSec = sample.elapsedSec,
            strokeRateSpm = sample.strokeRateSpm,
            strokeCount = sample.strokeCount,
            distanceM = sample.totalDistanceM,
            paceSecPer500m = sample.instantPaceSecPer500m,
            powerW = sample.instantPowerW,
            resistanceLevel = sample.resistanceLevel,
            heartRateBpm = sample.heartRateBpm,
            energyKcal = sample.totalEnergyKcal,
        )
    }

    fun clear() = rows.clear()

    fun csv(): String = buildString {
        appendLine("elapsed_s,stroke_rate_spm,stroke_count,distance_m,pace_s_per_500m,power_w,resistance,heart_rate_bpm,energy_kcal")
        rows.forEach { r ->
            appendLine(
                listOf(
                    r.elapsedSec ?: "", r.strokeRateSpm ?: "", r.strokeCount ?: "",
                    r.distanceM ?: "", r.paceSecPer500m ?: "", r.powerW ?: "",
                    r.resistanceLevel ?: "", r.heartRateBpm ?: "", r.energyKcal ?: "",
                ).joinToString(",")
            )
        }
    }
}

/** Parsed back out of the CSV we exported. */
object WorkoutCsv {
    fun splits(csv: String): List<Split> = csv.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val cols = line.split(',')
            val elapsed = cols.getOrNull(0)?.toIntOrNull()
            val distance = cols.getOrNull(3)?.toIntOrNull()
            if (elapsed == null || distance == null) null else Split(distance, elapsed)
        }
        .toList()
}

data class Split(val distanceM: Int, val elapsedSec: Int)
