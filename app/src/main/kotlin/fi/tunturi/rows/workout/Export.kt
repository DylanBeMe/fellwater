package fi.tunturi.rows.workout

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Getting finished rows off the tablet: the raw CSV, or TCX for Strava and Garmin Connect. */
object Export {

    const val FOLDER = "Download/Fellwater"

    /**
     * Writes to Downloads via MediaStore, so no storage permission and no
     * FileProvider plumbing. Returns null when the insert fails.
     */
    fun toDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } }.getOrNull()
        if (written == null) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Garmin Training Center XML, which Strava and Garmin Connect both import. TCX has
     * no rowing sport, so the activity is "Other"; Strava lets you set it to Rowing
     * after upload. Stroke rate goes in Cadence, power in the TPX Watts extension.
     */
    fun tcx(startedAtMs: Long, csv: String): String {
        // One point per second: the recorder can log several samples within a second.
        val points = csv.lineSequence().drop(1)
            .mapNotNull { line ->
                val c = line.split(',')
                val elapsed = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                Point(
                    elapsedSec = elapsed,
                    spm = c.getOrNull(1)?.toDoubleOrNull(),
                    distanceM = c.getOrNull(3)?.toIntOrNull(),
                    watts = c.getOrNull(5)?.toIntOrNull(),
                    heartRate = c.getOrNull(7)?.toIntOrNull(),
                    kcal = c.getOrNull(8)?.toIntOrNull(),
                )
            }
            .associateBy { it.elapsedSec }
            .values
            .sortedBy { it.elapsedSec }

        val totalSec = points.lastOrNull()?.elapsedSec ?: 0
        val distance = points.lastOrNull { it.distanceM != null }?.distanceM ?: 0
        val kcal = points.lastOrNull { it.kcal != null }?.kcal ?: 0
        val start = iso(startedAtMs)

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(
                """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" """ +
                    """xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">"""
            )
            appendLine("""  <Activities>""")
            appendLine("""    <Activity Sport="Other">""")
            appendLine("""      <Id>$start</Id>""")
            appendLine("""      <Lap StartTime="$start">""")
            appendLine("""        <TotalTimeSeconds>$totalSec</TotalTimeSeconds>""")
            appendLine("""        <DistanceMeters>$distance</DistanceMeters>""")
            appendLine("""        <Calories>$kcal</Calories>""")
            appendLine("""        <Intensity>Active</Intensity>""")
            appendLine("""        <TriggerMethod>Manual</TriggerMethod>""")
            appendLine("""        <Track>""")
            points.forEach { p ->
                appendLine("""          <Trackpoint>""")
                appendLine("""            <Time>${iso(startedAtMs + p.elapsedSec * 1000L)}</Time>""")
                p.distanceM?.let { appendLine("""            <DistanceMeters>$it</DistanceMeters>""") }
                p.heartRate?.takeIf { it > 0 }?.let {
                    appendLine("""            <HeartRateBpm><Value>$it</Value></HeartRateBpm>""")
                }
                p.spm?.takeIf { it > 0 }?.let { appendLine("""            <Cadence>${it.toInt().coerceAtMost(254)}</Cadence>""") }
                p.watts?.takeIf { it > 0 }?.let {
                    appendLine("""            <Extensions><ns3:TPX><ns3:Watts>$it</ns3:Watts></ns3:TPX></Extensions>""")
                }
                appendLine("""          </Trackpoint>""")
            }
            appendLine("""        </Track>""")
            appendLine("""      </Lap>""")
            appendLine("""    </Activity>""")
            appendLine("""  </Activities>""")
            appendLine("""</TrainingCenterDatabase>""")
        }
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private data class Point(
        val elapsedSec: Int,
        val spm: Double?,
        val distanceM: Int?,
        val watts: Int?,
        val heartRate: Int?,
        val kcal: Int?,
    )
}
