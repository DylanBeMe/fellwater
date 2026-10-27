package fi.tunturi.rows.ftms

/**
 * Decoded Fitness Machine Service "Rower Data" (0x2AD1) notification.
 *
 * Every field is optional because presence is driven by the frame's flag bits —
 * a console that only sends distance and energy leaves the rest null.
 */
data class RowerSample(
    val strokeRateSpm: Double?,
    val strokeCount: Int?,
    val averageStrokeRateSpm: Double?,
    val totalDistanceM: Int?,
    val instantPaceSecPer500m: Double?,
    val averagePaceSecPer500m: Double?,
    val instantPowerW: Int?,
    val averagePowerW: Int?,
    val resistanceLevel: Int?,
    val totalEnergyKcal: Int?,
    val energyPerHourKcal: Int?,
    val energyPerMinKcal: Int?,
    val heartRateBpm: Int?,
    val metabolicEquivalent: Double?,
    val elapsedSec: Int?,
    val remainingSec: Int?,
    val moreData: Boolean,
)

/** Little-endian reader that refuses to walk past the notification payload. */
internal class Cursor(private val b: ByteArray, private var i: Int, private val end: Int) {
    fun u8(): Int {
        require(i < end) { "short frame at $i" }
        return b[i++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun s16(): Int = u16().let { if (it and 0x8000 != 0) it - 0x10000 else it }

    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)
}

object RowerData {
    private const val MORE_DATA = 1
    private const val AVERAGE_STROKE = 1 shl 1
    private const val TOTAL_DISTANCE = 1 shl 2
    private const val INSTANT_PACE = 1 shl 3
    private const val AVERAGE_PACE = 1 shl 4
    private const val INSTANT_POWER = 1 shl 5
    private const val AVERAGE_POWER = 1 shl 6
    private const val RESISTANCE_LVL = 1 shl 7
    private const val TOTAL_ENERGY = 1 shl 8
    private const val ENERGY_PER_HOUR = 1 shl 9
    private const val ENERGY_PER_MIN = 1 shl 10
    private const val HEART_RATE = 1 shl 11
    private const val MET = 1 shl 12
    private const val ELAPSED = 1 shl 13
    private const val REMAINING = 1 shl 14

    /**
     * shortcut: consoles disagree on pace units — Domyos/AppDevice treats the raw
     * value as whole seconds per 500 m, the SIG table says centiseconds. Nothing
     * realistic is slower than 50 min/500 m, so treat anything above 3000 as
     * centiseconds. Revisit once we have a frame from the R60/R80.
     */
    private fun pace(raw: Int): Double? = when {
        raw <= 0 -> null
        raw > 3000 -> raw / 100.0
        else -> raw.toDouble()
    }

    /** @return null when the payload is too short for its own flag bits. */
    fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): RowerSample? {
        if (length < 2) return null
        val c = Cursor(bytes, offset, offset + length)
        return try {
            val flags = c.u16()
            val strokeRate = c.u8() / 2.0
            val strokeCount = c.u16()
            RowerSample(
                strokeRateSpm = strokeRate,
                strokeCount = strokeCount,
                averageStrokeRateSpm = if (flags and AVERAGE_STROKE != 0) c.u8() / 2.0 else null,
                totalDistanceM = if (flags and TOTAL_DISTANCE != 0) c.u24() else null,
                instantPaceSecPer500m = if (flags and INSTANT_PACE != 0) pace(c.u16()) else null,
                averagePaceSecPer500m = if (flags and AVERAGE_PACE != 0) pace(c.u16()) else null,
                instantPowerW = if (flags and INSTANT_POWER != 0) c.s16() else null,
                averagePowerW = if (flags and AVERAGE_POWER != 0) c.s16() else null,
                resistanceLevel = if (flags and RESISTANCE_LVL != 0) c.s16() else null,
                totalEnergyKcal = if (flags and TOTAL_ENERGY != 0) c.u16() else null,
                energyPerHourKcal = if (flags and ENERGY_PER_HOUR != 0) c.u16() else null,
                energyPerMinKcal = if (flags and ENERGY_PER_MIN != 0) c.u8() else null,
                heartRateBpm = if (flags and HEART_RATE != 0) c.u8() else null,
                metabolicEquivalent = if (flags and MET != 0) c.u8() / 10.0 else null,
                elapsedSec = if (flags and ELAPSED != 0) c.u16() else null,
                remainingSec = if (flags and REMAINING != 0) c.u16() else null,
                moreData = flags and MORE_DATA != 0,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
