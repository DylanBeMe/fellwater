package fi.tunturi.rows.hr

/**
 * Bluetooth SIG Heart Rate Measurement (0x2A37), as sent by every standard chest
 * strap: a flags byte, then the rate as uint8, or uint16 little-endian when flag
 * bit 0 is set. Energy expended and RR intervals may follow; they are not used.
 */
object HeartRateMeasurement {

    /** Beats per minute, or null for a short frame or a strap reporting 0 (no contact). */
    fun parse(value: ByteArray): Int? {
        if (value.size < 2) return null
        val wide = value[0].toInt() and 0x01 != 0
        val bpm = if (wide) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            value[1].toInt() and 0xFF
        }
        return bpm.takeIf { it > 0 }
    }
}
