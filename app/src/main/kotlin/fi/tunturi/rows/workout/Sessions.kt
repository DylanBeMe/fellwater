package fi.tunturi.rows.workout

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Totals for one finished row, computed from the recorded CSV. */
data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val durationSec: Int,
    val distanceM: Int,
    val strokes: Int,
    val avgSpm: Double,
    val maxSpm: Double,
    val avgWatts: Int,
    val splits: List<Split>,
    /** Target distance the row was started against, in metres. Null = no goal. */
    val goalM: Int? = null,
    /** Damper/difficulty the rower was set to by hand (1-10). The console does not report it. */
    val difficulty: Int? = null,
) {
    val avgPaceSecPer500: Double?
        get() = if (distanceM > 0) durationSec * 500.0 / distanceM else null

    /** "12:34 · 2.05 km · 2:03/500m" — what a racer row should be called. */
    val label: String
        get() = buildString {
            append(clock(durationSec))
            append(" · ")
            append("%.2f km".format(distanceM / 1000.0))
            avgPaceSecPer500?.let {
                append(" · ")
                append(pace(it))
            }
        }

    val dateLabel: String
        get() = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(startedAt))

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
    private fun pace(secPer500: Double): String {
        val total = secPer500.toInt()
        return "%d:%02d/500m".format(total / 60, total % 60)
    }
}

object SessionMath {

    /**
     * Parses the recorder CSV. Columns:
     * elapsed_s,stroke_rate_spm,stroke_count,distance_m,pace_s_per_500m,power_w,resistance,heart_rate_bpm,energy_kcal
     */
    fun summarize(id: String, startedAt: Long, csv: String): SessionSummary? {
        val rows = csv.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val c = line.split(',')
                if (c.size < 6) return@mapNotNull null
                Row(
                    elapsed = c[0].toIntOrNull(),
                    spm = c[1].toDoubleOrNull(),
                    strokes = c[2].toIntOrNull(),
                    distance = c[3].toIntOrNull(),
                    watts = c[5].toIntOrNull(),
                )
            }
            .toList()

        if (rows.isEmpty()) return null
        val duration = rows.lastOrNull { it.elapsed != null }?.elapsed ?: 0
        val distance = rows.lastOrNull { it.distance != null }?.distance ?: 0
        val spms = rows.mapNotNull { it.spm }.filter { it > 0 }
        val watts = rows.mapNotNull { it.watts }.filter { it > 0 }
        val strokes = rows.lastOrNull { it.strokes != null }?.strokes ?: 0
        if (distance <= 0 && duration <= 0) return null

        return SessionSummary(
            id = id,
            startedAt = startedAt,
            durationSec = duration,
            distanceM = distance,
            strokes = strokes,
            avgSpm = if (spms.isEmpty()) 0.0 else spms.average(),
            maxSpm = spms.maxOrNull() ?: 0.0,
            avgWatts = if (watts.isEmpty()) 0 else watts.average().toInt(),
            splits = splits(rows),
        )
    }

    /** Time at each 100 m boundary, interpolated between samples. */
    fun splits(rows: List<Row>, stepM: Int = 100): List<Split> {
        val out = ArrayList<Split>()
        var next = stepM
        var prevD = 0
        var prevT = 0
        for (r in rows) {
            val d = r.distance ?: continue
            val t = r.elapsed ?: continue
            while (d >= next) {
                val span = (d - prevD)
                val travelled = (next - prevD)
                val at = if (span > 0) prevT + (t - prevT) * (travelled.toDouble() / span) else t.toDouble()
                out += Split(next, at.toInt())
                next += stepM
            }
            prevD = d
            prevT = t
        }
        return out
    }

    data class Row(
        val elapsed: Int?,
        val spm: Double?,
        val strokes: Int? = null,
        val distance: Int?,
        val watts: Int?,
    )
}

/**
 * Saved rows live in external app storage so they can be copied off the device or
 * picked up by backup tools. One CSV per session, summary computed on read.
 */
class SessionStore(private val context: Context) {

    private val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
            .apply { mkdirs() }

    fun save(csv: String, goalM: Int? = null, difficulty: Int? = null, at: Date = Date()): File? {
        if (csv.lineSequence().drop(1).none { it.isNotBlank() }) return null
        val stamp = STAMP.format(at)
        val file = File(dir, "row-$stamp.csv")
        file.writeText(csv)
        // Settings live in a small sidecar so the CSV stays a plain sample log.
        if (goalM != null || difficulty != null) {
            runCatching {
                File(dir, "row-$stamp.meta").writeText(
                    buildString {
                        if (goalM != null) appendLine("goal=$goalM")
                        if (difficulty != null) appendLine("difficulty=$difficulty")
                    }
                )
            }
        }
        return file
    }

    private fun meta(id: String): Map<String, Int> =
        runCatching {
            File(dir, "$id.meta").readLines()
                .mapNotNull { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: return@mapNotNull null)
                    else null
                }
                .toMap()
        }.getOrDefault(emptyMap())

    fun list(): List<SessionSummary> =
        dir.listFiles { f -> f.isFile && f.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    val meta = meta(file.nameWithoutExtension)
                    SessionMath.summarize(
                        id = file.nameWithoutExtension,
                        startedAt = 0L,
                        csv = file.readText(),
                    )?.let { summary ->
                        summary.copy(
                            startedAt = finishedAt(file) - summary.durationSec * 1000L,
                            goalM = meta["goal"],
                            difficulty = meta["difficulty"],
                        )
                    }
                }.getOrNull()
            }
            ?: emptyList()

    fun csv(id: String): String? = runCatching { File(dir, "$id.csv").readText() }.getOrNull()

    fun delete(id: String) {
        runCatching { File(dir, "$id.csv").delete() }
        runCatching { File(dir, "$id.meta").delete() }
    }

    fun directory(): File = dir

    /**
     * The row was saved (finished) at the time in its name, "row-20261027-201500".
     * The file's own timestamp is a poor stand-in: copying or restoring the folder
     * changes it.
     */
    private fun finishedAt(file: File): Long =
        runCatching { STAMP.parse(file.nameWithoutExtension.removePrefix("row-"))!!.time }
            .getOrDefault(file.lastModified())

    // A row in progress is written here every so often, so a crash or a killed app
    // loses at most the last half minute. ".part" keeps it out of list().
    private val draft get() = File(dir, "in-progress.part")
    private val draftMeta get() = File(dir, "in-progress-meta.part")
    private val recovered get() = File(dir, "recovered.part")
    private val recoveredMeta get() = File(dir, "recovered-meta.part")

    fun writeDraft(csv: String, goalM: Int?, difficulty: Int?) {
        runCatching {
            val tmp = File(dir, "in-progress.tmp")
            tmp.writeText(csv)
            tmp.renameTo(draft)
            draftMeta.writeText("goal=${goalM ?: ""}\ndifficulty=${difficulty ?: ""}\n")
        }
    }

    fun clearDraft() {
        runCatching { draft.delete(); draftMeta.delete() }
    }

    /**
     * A draft still here at start-up was left by a run that never finished its row.
     * Moves it aside (so the next row's autosave cannot overwrite it) and summarises it.
     */
    fun takeLeftoverDraft(): SessionSummary? {
        if (draft.exists()) {
            runCatching {
                recovered.delete()
                draft.renameTo(recovered)
                recoveredMeta.delete()
                draftMeta.renameTo(recoveredMeta)
            }
        }
        if (!recovered.exists()) return null
        val summary = runCatching {
            SessionMath.summarize("recovered", recovered.lastModified(), recovered.readText())
        }.getOrNull()
        if (summary == null) discardRecovered()
        return summary?.copy(startedAt = recovered.lastModified() - summary.durationSec * 1000L)
    }

    fun keepRecovered(): File? {
        val csv = runCatching { recovered.readText() }.getOrNull() ?: return null
        val meta = runCatching {
            recoveredMeta.readLines().associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        }.getOrDefault(emptyMap())
        val file = save(csv, meta["goal"], meta["difficulty"], at = Date(recovered.lastModified()))
        discardRecovered()
        return file
    }

    fun discardRecovered() {
        runCatching { recovered.delete(); recoveredMeta.delete() }
    }

    private companion object {
        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    }
}
