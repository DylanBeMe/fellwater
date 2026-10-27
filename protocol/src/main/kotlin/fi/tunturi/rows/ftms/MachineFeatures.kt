package fi.tunturi.rows.ftms

/**
 * Fitness Machine Feature (0x2ACC): two little-endian uint32 bitfields —
 * what the machine reports, and what targets it accepts.
 *
 * Bit numbers are from the SIG FTMS spec. A false flag means "do not offer the
 * feature in the UI"; it is not proof the machine lacks it, since some consoles
 * advertise less than they implement.
 */
class MachineFeatures(private val machine: Int, private val targetSetting: Int) {

    private fun m(bit: Int) = machine and (1 shl bit) != 0
    private fun t(bit: Int) = targetSetting and (1 shl bit) != 0

    val reportsCadence: Boolean get() = m(1)
    val reportsDistance: Boolean get() = m(2)
    val reportsPace: Boolean get() = m(5)
    val reportsResistanceLevel: Boolean get() = m(7)
    val reportsEnergy: Boolean get() = m(9)
    val reportsHeartRate: Boolean get() = m(10)
    val reportsElapsedTime: Boolean get() = m(12)
    val reportsPower: Boolean get() = m(14)

    val acceptsTargetResistance: Boolean get() = t(2)
    val acceptsTargetPower: Boolean get() = t(3)
    val acceptsTargetHeartRate: Boolean get() = t(4)
    val acceptsTargetDistance: Boolean get() = t(8)
    val acceptsTargetTrainingTime: Boolean get() = t(9)
    val acceptsTargetCadence: Boolean get() = t(16)

    /** Structured interval workouts need a target the machine will actually accept. */
    val supportsStructuredWorkouts: Boolean
        get() = acceptsTargetResistance || acceptsTargetPower || acceptsTargetCadence

    val summary: String
        get() = buildList {
            if (reportsCadence) add("stroke rate")
            if (reportsPace) add("pace")
            if (reportsPower) add("power")
            if (reportsDistance) add("distance")
            if (reportsResistanceLevel) add("resistance")
            if (reportsElapsedTime) add("time")
            if (reportsHeartRate) add("heart rate")
        }.joinToString(", ").ifEmpty { "nothing advertised" }

    companion object {
        fun parse(bytes: ByteArray): MachineFeatures? {
            if (bytes.size < 8) return null
            fun u32(at: Int) = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
            return MachineFeatures(u32(0), u32(4))
        }
    }
}
